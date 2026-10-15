package net.seilent.funecomp

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import net.seilent.funecomp.art.ArtWorker
import net.seilent.funecomp.art.BitmapCodec
import net.seilent.funecomp.artist.ArtCache
import net.seilent.funecomp.artist.ArtPusher
import net.seilent.funecomp.artist.ArtistArt
import net.seilent.funecomp.artist.UrlHttp
import net.seilent.funecomp.link.Link
import net.seilent.funecomp.link.Session
import net.seilent.funecomp.link.TcpTransport
import net.seilent.funecomp.link.Transport
import net.seilent.funecomp.proto.Bye
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Profile
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.SetReply
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.state.PushGate
import net.seilent.funecomp.state.Reconnect
import net.seilent.funecomp.ui.BackdropArt
import net.seilent.funecomp.ui.Control
import net.seilent.funecomp.ui.LIB_PAGE
import net.seilent.funecomp.ui.Phase
import java.io.File
import java.io.IOException

object Remote : Control {
    const val TAG = "Funecomp"
    private const val BACKDROP_SIDE = 2048
    private const val FETCH_W = 384
    private const val FETCH_H = 480
    private val codec = BitmapCodec { Log.i(TAG, it) }

    var device by mutableStateOf(DeviceState())
        private set

    var phase by mutableStateOf(Phase.NONE)
        private set

    var bye by mutableStateOf<Int?>(null)
        private set

    var everLive by mutableStateOf(false)
        private set

    var board by mutableStateOf<String?>(null)
        private set

    var backdrop by mutableStateOf<BackdropArt?>(null)
        private set

    var credits by mutableStateOf<List<ArtCache.Entry>>(emptyList())
        private set

    private val lock = Any()
    private val stateLock = Any()
    @Volatile private var link: Link? = null
    @Volatile private var generation = 0
    private var spec: String? = null
    private val asked = HashSet<String>()
    private var artist: ArtistArt? = null
    @Volatile private var art: ArtWorker? = null
    @Volatile private var artKey: Pair<List<String>, Int?>? = null
    @Volatile private var backdropFor: List<String>? = null

    fun debuggable(context: Context) = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    fun start(context: Context, spec: String): Boolean {
        synchronized(lock) {
            if (spec == this.spec && link != null) {
                link?.foreground()
                return true
            }
            drop()
            val connect = connector(context.applicationContext, spec)
            if (connect == null) {
                Log.w(TAG, "link $spec not usable")
                return false
            }
            this.spec = spec
            update { DeviceState() }
            backdrop = null
            backdropFor = null
            phase = Phase.CONNECTING
            bye = null
            everLive = false
            Log.i(TAG, "link $spec")
            val l = Link(connect, listener(generation), clock = SystemClock::elapsedRealtime)
            link = l
            art = worker(context, l)
            l.start()
            return true
        }
    }

    fun stop() {
        synchronized(lock) { drop() }
        update { DeviceState() }
        phase = Phase.NONE
        everLive = false
    }

    fun userAction() = link?.userAction()

    fun artist(context: Context): ArtistArt = synchronized(lock) {
        artist ?: run {
            val c = context.applicationContext
            val version = c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "0"
            ArtistArt(UrlHttp(), codec, ArtCache(File(c.filesDir, "art")), { Main.sources(c) }, version).also {
                artist = it
                Thread({ credits = it.credits() }, "art-credits").apply { isDaemon = true }.start()
            }
        }
    }

    fun sourcesChanged() = art?.want()

    private fun worker(context: Context, l: Link): ArtWorker {
        val a = artist(context)
        return ArtWorker(
            { l.session },
            { s -> ArtPusher(s, a::get) { PushGate.open(device, SystemClock.elapsedRealtime()) }.run(ArtPusher.order(device.playback.state)) },
            { final -> showBackdrop(a, final) },
            { fetch(a) },
        ) { r, e ->
            try {
                if (r != null) Log.i(TAG, "art ${r.stop} pushed=${r.pushed} stored=${r.stored} missing=${r.missing} failed=${r.failed}")
                if (e != null) Log.w(TAG, "art: $e")
                credits = a.credits()
            } catch (t: Throwable) {
                Log.w(TAG, "art done: $t")
            }
        }
    }

    private fun fetch(a: ArtistArt) {
        val h = device.hello
        val w = h?.backdropW ?: FETCH_W
        val hh = h?.backdropH ?: FETCH_H
        for ((name, mbid) in ArtPusher.order(device.playback.state)) {
            try {
                if (a.get(name, mbid, w, hh) != null) return
            } catch (e: IOException) {
                Log.w(TAG, "art fetch $name: $e")
            }
        }
    }

    private fun showBackdrop(a: ArtistArt, final: Boolean): Boolean {
        val s = device.playback.state
        if (s.artists == backdropFor && backdrop != null) return true
        val src = s.artists.withIndex().firstNotNullOfOrNull { (i, n) -> a.cached(n, s.mbids.getOrNull(i)) }
        if (src == null) {
            if (final) {
                backdropFor = s.artists
                backdrop = null
            }
            return false
        }
        val (bmp, face) = codec.faced(src, BACKDROP_SIDE) ?: return false
        backdropFor = s.artists
        backdrop = BackdropArt(bmp.asImageBitmap(), face?.let { it.first / bmp.width to it.second / bmp.height })
        return true
    }

    private fun drop() {
        generation++
        spec = null
        synchronized(asked) { asked.clear() }
        art?.stop()
        art = null
        artKey = null
        val old = link ?: return
        link = null
        Log.i(TAG, "link stop")
        Thread({ old.stop() }, "link-stop").apply { isDaemon = true }.start()
    }

    override fun transport(cmd: Int, seekMs: Long?, on: Boolean?, repeat: Int?) =
        send(Msg.TRANSPORT, Req.transport(cmd, seekMs, on, repeat), "transport ${Enums.TransportCmd.names[cmd]}")

    override fun volume(detents: Int) = send(Msg.VOLUME, Req.volume(step = detents), "volume step $detents")

    fun volumeSet(n: Int) = send(Msg.VOLUME, Req.volume(set = n), "volume set $n")

    override fun libGet(view: Int, parent: String, offset: Long) {
        val rev = device.lib.rev
        val key = "lib $view $parent $offset $rev"
        if (!once(key)) return
        send(Msg.LIB_GET, Req.libGet(view, parent, offset, LIB_PAGE, rev), key, fail = { forget(key) })
    }

    override fun play(view: Int?, parent: String?, start: Long, shuffle: Boolean, tracks: List<Long>) =
        send(Msg.PLAY, Req.play(view, parent, if (tracks.isEmpty()) device.lib.rev else null, start, shuffle, tracks), "play view=$view parent=$parent start=$start")

    override fun queuePage(offset: Long) {
        val key = "queue $offset ${device.queue.rev}"
        if (!once(key)) return
        send(Msg.QUEUE_OP, Req.queueOp(Enums.QueueCmd.page, offset = offset, count = LIB_PAGE), key, fail = { forget(key) })
    }

    override fun queueOp(cmd: Int, slot: Long?, to: Long?, tracks: List<Long>) {
        val rev = device.queue.rev ?: device.playback.state.queueRev
        send(Msg.QUEUE_OP, Req.queueOp(cmd, rev, slot = slot, to = to, tracks = tracks), "queue ${Enums.QueueCmd.names[cmd]} slot=$slot to=$to")
    }

    override fun set(key: String, value: String) = send(Msg.SET, Req.set("$key=$value\n"), "set $key=$value", ok = { body ->
        val r = SetReply.from(body)
        update { it.withSetReply(r.rev) }
    })

    override fun draft(kind: Int, value: String?, minutes: Long?, unixS: Long?, zoneS: Int?) =
        send(Msg.DRAFT, Req.draft(kind, value, minutes = minutes, unixS = unixS, zoneS = zoneS), "draft ${Enums.DraftKind.names[kind]}")

    override fun eqSet(output: Int, key: String?, ints: Profile?) =
        send(Msg.EQ_SET, Req.eqSet(output, key, ints), "eq set output=$output key=$key")

    override fun action(cmd: Int, track: Long?) = send(Msg.ACTION, Req.action(cmd, track), "action ${Enums.ActionCmd.names[cmd]}")

    private fun once(key: String) = synchronized(asked) { asked.add(key) }

    private fun forget(key: String) = synchronized(asked) { asked.remove(key) }

    private fun send(type: Int, payload: ByteArray, what: String, ok: ((Node) -> Unit)? = null, fail: (() -> Unit)? = null) {
        val s = link?.session
        if (s == null) {
            Log.i(TAG, "$what: no session")
            fail?.invoke()
            return
        }
        s.request(type, payload) { r ->
            val out = when (r) {
                is Session.Result.Ok -> "ok"
                is Session.Result.Failed -> "error ${r.error.code} ${r.error.text}"
                else -> r.javaClass.simpleName.lowercase()
            }
            Log.i(TAG, "$what -> $out")
            if (r is Session.Result.Ok) ok?.invoke(r.body) else fail?.invoke()
        }
    }

    private fun connector(context: Context, spec: String): (() -> Transport)? {
        if (spec.startsWith("rfcomm:")) {
            val addr = spec.removePrefix("rfcomm:")
            return { RfcommTransport(context, addr) }
        }
        if (!debuggable(context) || !spec.startsWith("tcp:")) return null
        val hostPort = spec.removePrefix("tcp:")
        val at = hostPort.lastIndexOf(':')
        if (at <= 0) return null
        val host = hostPort.substring(0, at)
        val port = hostPort.substring(at + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return { TcpTransport(host, port) }
    }

    private fun update(f: (DeviceState) -> DeviceState) = synchronized(stateLock) { device = f(device) }

    private fun listener(gen: Int) = object : Link.Listener {
        private val live get() = gen == generation

        override fun onAttempt(at: Long) {
            if (!live) return
            if (phase != Phase.LIVE) phase = Phase.CONNECTING
            Log.i(TAG, "attempt at=$at")
        }

        override fun onHello(hello: HelloReply) {
            if (!live) return
            artKey = null
            update { it.withHello(hello) }
            synchronized(asked) { asked.clear() }
            phase = Phase.LIVE
            bye = null
            everLive = true
            board = hello.board
            Log.i(TAG, "hello board=${hello.board} version=${hello.version} idle=${hello.idleMs} busy=${hello.busyMs}")
        }

        override fun onPush(type: Int, body: Node) {
            if (!live) return
            val now = SystemClock.elapsedRealtime()
            var old = device.playback.state
            update {
                old = it.playback.state
                it.reduce(type, body, now)
            }
            if (type == Msg.LIB || type == Msg.QUEUE) synchronized(asked) { asked.removeAll { k -> k.startsWith(if (type == Msg.LIB) "lib " else "queue ") } }
            if (type != Msg.STATE) return
            val s = State.from(body)
            Log.i(TAG, "state applied title=${s.title} artist=${s.artist} playing=${s.playing} volume=${s.volume}/${s.maxVolume}")
            if (old.title != null && old.title != s.title) Log.i(TAG, "title changed ${old.title} -> ${s.title}")
            val key = s.artists to s.output
            if (key != artKey) {
                artKey = key
                art?.want()
            }
        }

        override fun onRtt(ms: Long) {
            if (live) update { it.withRtt(ms) }
        }

        override fun onBye(bye: Bye) {
            if (!live) return
            this@Remote.bye = bye.reason
            Log.i(TAG, "bye ${Enums.ByeReason.names[bye.reason] ?: bye.reason}")
        }

        override fun onClosed(reason: String) {
            if (!live) return
            update { it.copy(hello = null) }
            phase = Phase.DOWN
            Log.i(TAG, "closed $reason")
        }

        override fun onStopped(stop: Reconnect.Stop) {
            if (!live) return
            phase = Phase.STOPPED
            Log.i(TAG, "stopped $stop")
        }
    }
}
