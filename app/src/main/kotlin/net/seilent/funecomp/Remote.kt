package net.seilent.funecomp

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import net.seilent.funecomp.art.ArtWorker
import net.seilent.funecomp.art.BitmapCodec
import net.seilent.funecomp.art.Forward
import net.seilent.funecomp.art.Memo
import net.seilent.funecomp.art.ThumbWorker
import net.seilent.funecomp.artist.ArtCache
import net.seilent.funecomp.artist.ArtPusher
import net.seilent.funecomp.artist.ArtistArt
import net.seilent.funecomp.artist.Sweep
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
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.state.ClockSync
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.state.PushGate
import net.seilent.funecomp.state.Reconnect
import net.seilent.funecomp.state.Wake
import net.seilent.funecomp.ui.BackdropArt
import net.seilent.funecomp.ui.Control
import net.seilent.funecomp.ui.LIB_PAGE
import net.seilent.funecomp.ui.Phase
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

object Remote : Control {
    const val TAG = "Funecomp"
    private const val BACKDROP_SIDE = 2048
    private const val FETCH_W = 360
    private const val FETCH_H = 480
    private const val FACES = 64
    private const val ATTEMPT_WAKE_MS = 20_000L
    private const val ATTEMPT = "attempt"
    private const val ART = "art"
    private const val ART_WAKE_MS = 60_000L
    private const val TRANSITION_PAD_MS = 1_000L
    private val SECRET = Regex("(api_key|key|token|secret)=[^&]*")
    private val codec = BitmapCodec { Log.i(TAG, it) }
    private val decoded = Memo<BackdropArt>()
    private val faces = Memo<Found>(FACES)

    var device by mutableStateOf(DeviceState())
        private set

    var phase by mutableStateOf(Phase.NONE)
        private set

    var bluetoothOff by mutableStateOf(false)
        private set

    @Volatile var onPhase: (() -> Unit)? = null
    @Volatile var onTail: ((Long) -> Unit)? = null
    @Volatile var onState: (() -> Unit)? = null
    @Volatile var onDrop: (() -> Unit)? = null

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
    @Volatile private var artKey: List<Any?>? = null
    @Volatile private var backdropFor: List<String>? = null
    @Volatile private var shown = false
    @Volatile private var trigger = 0
    @Volatile private var online = true
    private val breaker = Sweep.Breaker(SystemClock::elapsedRealtime)
    private var wake: Wake? = null
    private var exitLogged = false
    private val clock = ClockSync()
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "clock").apply { isDaemon = true } }
    private var transition: ScheduledFuture<*>? = null
    @Volatile private var thumbs: ThumbWorker? = null

    fun debuggable(context: Context) = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    fun start(context: Context, spec: String): Boolean {
        synchronized(lock) {
            if (spec == this.spec && link != null) {
                link?.foreground()
                return true
            }
            drop()
            thumbs(context)
            val connect = connector(context.applicationContext, spec)
            if (connect == null) {
                Log.w(TAG, "link $spec not usable")
                return false
            }
            this.spec = spec
            update { DeviceState() }
            backdrop = null
            backdropFor = null
            decoded.clear()
            bye = null
            everLive = false
            phase(Phase.CONNECTING)
            Log.i(TAG, "link $spec")
            wake(context)
            val l = Link(connect, listener(generation), Reconnect(clock = SystemClock::elapsedRealtime), clock = SystemClock::elapsedRealtime)
            link = l
            art = worker(context, l)
            l.start()
            return true
        }
    }

    fun stop() {
        clockOff()
        synchronized(lock) { drop() }
        wake?.drop(ATTEMPT)
        wake?.drop(ART)
        update { DeviceState() }
        everLive = false
        bluetoothOff = false
        phase(Phase.NONE)
    }

    fun userAction() = link?.userAction()

    fun kick(why: String) {
        val l = link ?: return
        if (phase == Phase.LIVE) return
        Log.i(TAG, "kick $why")
        wake?.hold(ATTEMPT, ATTEMPT_WAKE_MS)
        l.kick()
        if (l.stopped != null || l.paused) wake?.drop(ATTEMPT)
    }

    fun bluetooth(on: Boolean) {
        val l = link ?: return
        if (on == !bluetoothOff) return
        bluetoothOff = !on
        Log.i(TAG, "bluetooth ${if (on) "on" else "off"}")
        if (on) {
            l.userAction()
            kick("bluetooth")
        } else {
            l.pause()
        }
        onPhase?.invoke()
    }

    fun shown(on: Boolean) {
        shown = on
        if (on) trigger++
        if (!on) {
            backdrop = null
            backdropFor = null
            decoded.clear()
        }
        art?.want()
    }

    fun logExit(context: Context) {
        if (exitLogged) return
        exitLogged = true
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val e = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return
        Log.i(TAG, "exit reason=${e.reason} status=${e.status} importance=${e.importance} at=${e.timestamp} ${e.description ?: ""}")
    }

    private fun network(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        online = cm.activeNetwork != null
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                online = true
                breaker.network()
                Lyrics.network()
                art?.want()
                Scrobbles.flush()
            }

            override fun onLost(network: Network) {
                online = cm.activeNetwork != null
            }
        })
    }

    private fun wake(context: Context) {
        if (wake != null) return
        val pm = context.getSystemService(PowerManager::class.java) ?: return
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "funecomp:link").apply { setReferenceCounted(false) }
        wake = Wake({ wl.acquire(it) }, { if (wl.isHeld) wl.release() }, SystemClock::elapsedRealtime)
    }

    private fun phase(p: Phase) {
        phase = p
        onPhase?.invoke()
    }

    fun thumbs(context: Context): ThumbWorker = synchronized(lock) {
        thumbs ?: ThumbWorker(File(context.applicationContext.filesDir, "thumb"), { link?.session }) { Log.i(TAG, it) }.also { thumbs = it }
    }

    override fun wantArt(slot: Int, track: Long, px: Int, album: String?) {
        if (device.has("art")) thumbs?.want(slot, track, px, album)
    }

    override fun art(track: Long, px: Int): ImageBitmap? = thumbs?.image(track, px)

    fun mediaArt(track: Long, px: Int): Bitmap? = thumbs?.media(track, px)

    fun onMediaArt(f: (() -> Unit)?) {
        thumbs?.onMedia = f
    }

    fun artist(context: Context): ArtistArt = synchronized(lock) {
        thumbs(context)
        artist ?: run {
            val c = context.applicationContext
            val version = c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "0"
            network(c)
            ArtistArt(UrlHttp(), codec, ArtCache(File(c.filesDir, "art")), { Main.sources(c) }, version, online = { online }).also {
                artist = it
                Thread({ credits = it.credits() }, "art-credits").apply { isDaemon = true }.start()
            }
        }
    }

    fun sourcesChanged() = art?.want()

    fun session(): Session? = link?.session

    fun holdWake(tag: String, ms: Long) = wake?.hold(tag, ms)

    fun dropWake(tag: String) = wake?.drop(tag)

    private fun gate(): Boolean {
        val open = PushGate.open(device, SystemClock.elapsedRealtime()) && !Firmwares.sending
        if (open) wake?.hold(ART, ART_WAKE_MS) else wake?.drop(ART)
        return open
    }

    private fun libPage(s: Session, offset: Long, rev: Long?): Session.Result {
        val key = "lib ${Enums.View.artists}  $offset $rev"
        once(key)
        val r = s.call(Msg.LIB_GET, Req.libGet(Enums.View.artists, "", offset, LIB_PAGE, rev))
        if (r !is Session.Result.Ok) forget(key)
        return r
    }

    private fun worker(context: Context, l: Link): ArtWorker {
        val a = artist(context)
        val forward = Forward(
            { device },
            a::get,
            ::gate,
            { !shown && online && !breaker.open },
            ::libPage,
            breaker,
            { online },
            { credits = a.credits() },
            object : Forward.Marks {
                override fun begin() = a.beginMark()
                override fun mark(): Any = a.mark()
                override fun holds(mark: Any) = mark is ArtistArt.Mark && a.holds(mark)
            },
        ) { Log.i(TAG, it) }
        return ArtWorker(
            { l.session },
            { s, run -> forward.run(s, run::live) },
            { final -> !shown || showBackdrop(a, final) },
            { if (shown) fetch(a) },
            trigger = { trigger },
            onHttp = { url ->
                Log.i(TAG, "art http ${url.replace(SECRET, "$1=x")}")
                if (PushGate.open(device, SystemClock.elapsedRealtime())) wake?.hold(ART, ART_WAKE_MS)
            },
            ended = { wake?.drop(ART) },
        ) { r, e ->
            try {
                if (r != null) Log.i(TAG, "art ${r.stop} ${r.counts()}")
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
        val e = s.artists.withIndex().firstNotNullOfOrNull { (i, n) -> a.cachedEntry(n, s.mbids.getOrNull(i)) }
        if (e == null) {
            if (final) {
                backdropFor = s.artists
                backdrop = null
                decoded.clear()
            }
            return false
        }
        val id = "${e.key}@${e.at}"
        val art = decoded.get(id) {
            a.source(e)?.let { src -> codec.faced(src, BACKDROP_SIDE) { detect -> faces.get(id) { Found(detect()) }?.at } }?.let { (bmp, face) ->
                BackdropArt(bmp.asImageBitmap(), face?.let { it.first / bmp.width to it.second / bmp.height })
            }
        } ?: return false
        backdropFor = s.artists
        backdrop = art
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

    override fun volumePercent(pct: Int, done: () -> Unit) =
        send(Msg.VOLUME, Req.volume(percent = pct), "volume percent $pct", ok = { done() }, fail = done)

    fun lrcPut(track: Long, libRev: Long, text: ByteArray, crc: Long, done: (Int?) -> Unit) {
        val s = link?.session ?: return done(-1)
        s.request(Msg.LRC_PUT, Req.lrcPut(track, libRev, text, crc)) { r ->
            Log.i(TAG, "lrc put ${java.lang.Long.toUnsignedString(track)} ${text.size} -> ${if (r is Session.Result.Failed) "error ${r.error.code}" else r.javaClass.simpleName.lowercase()}")
            done(
                when (r) {
                    is Session.Result.Ok -> null
                    is Session.Result.Failed -> r.error.code
                    is Session.Result.Unsupported -> Err.unknown
                    else -> -1
                },
            )
        }
    }

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

    fun clockChanged(why: String) {
        if (phase == Phase.LIVE && synchronized(clock) { clock.armed }) sendClock(why)
    }

    private fun sendClock(why: String) {
        val s = link?.session ?: return
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val (unixS, zoneS) = ClockSync.draft(now, zone)
        s.request(Msg.DRAFT, Req.draft(Enums.DraftKind.clock, unixS = unixS, zoneS = zoneS, automatic = true)) { r ->
            if (r is Session.Result.Failed) synchronized(clock) { clock.failed(r.error.code) }
            Log.i(TAG, "clock auto $why unixS=$unixS zoneS=$zoneS -> ${if (r is Session.Result.Failed) "error ${r.error.code}" else r.javaClass.simpleName.lowercase()}")
        }
        synchronized(clock) {
            transition?.cancel(false)
            transition = ClockSync.next(now, zone)?.let { at ->
                timer.schedule({ clockChanged("transition") }, (at - now).coerceAtLeast(0) + TRANSITION_PAD_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun clockOff() = synchronized(clock) {
        clock.closed()
        transition?.cancel(false)
        transition = null
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
            if (phase != Phase.LIVE) phase(Phase.CONNECTING)
            Log.i(TAG, "attempt at=$at")
        }

        override fun onHello(hello: HelloReply) {
            if (!live) return
            artKey = null
            thumbs?.let { t ->
                hello.libRev?.let { t.rev(it) }
                t.session()
            }
            update { it.withHello(hello) }
            synchronized(asked) { asked.clear() }
            bye = null
            everLive = true
            board = hello.board
            Scrobbles.session()
            Firmwares.hello(hello.board, hello.version)
            Lyrics.session()
            if (synchronized(clock) { clock.hello(hello) }) sendClock("hello")
            phase(Phase.LIVE)
            wake?.drop(ATTEMPT)
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
            if (type == Msg.STATE || type == Msg.QUEUE) Lyrics.poke()
            if (type == Msg.LIB || type == Msg.QUEUE) synchronized(asked) { asked.removeAll { k -> k.startsWith(if (type == Msg.LIB) "lib " else "queue ") } }
            if (type != Msg.STATE) return
            val s = State.from(body)
            s.libRev?.let { r -> if (r != old.libRev) thumbs?.rev(r) }
            Scrobbles.state(s, System.currentTimeMillis())
            Firmwares.state()
            onState?.invoke()
            Log.i(TAG, "state applied title=${s.title} artist=${s.artist} playing=${s.playing} volume=${s.volume}/${s.maxVolume} battery=${s.battery} charging=${s.charging} card=${s.cardFreeMiB}/${s.cardTotalMiB}")
            if (old.title != null && old.title != s.title) Log.i(TAG, "title changed ${old.title} -> ${s.title}")
            val key = listOf(s.artists, s.output, s.queueRev, s.slot)
            if (key != artKey) {
                artKey = key
                trigger++
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
            art?.cancel()
            clockOff()
            Lyrics.session()
            phase(Phase.DOWN)
            onDrop?.invoke()
            wake?.drop(ATTEMPT)
            Log.i(TAG, "closed $reason")
        }

        override fun onStopped(stop: Reconnect.Stop) {
            if (!live) return
            phase(Phase.STOPPED)
            wake?.drop(ATTEMPT)
            Log.i(TAG, "stopped $stop")
        }

        override fun onWait(at: Long, tail: Boolean) {
            if (!live || !tail) return
            Log.i(TAG, "tail at=$at")
            onTail?.invoke(at)
        }
    }
}

private class Found(val at: Pair<Double, Double>?)
