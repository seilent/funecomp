package net.seilent.funecomp

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
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
import net.seilent.funecomp.artist.ArtBlocks
import net.seilent.funecomp.artist.ArtCache
import net.seilent.funecomp.artist.ArtPicks
import net.seilent.funecomp.artist.ArtPusher
import net.seilent.funecomp.artist.ArtistArt
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.HttpRun
import net.seilent.funecomp.artist.ImageIds
import net.seilent.funecomp.artist.Names
import net.seilent.funecomp.artist.Offer
import net.seilent.funecomp.artist.Offered
import net.seilent.funecomp.artist.PickLink
import net.seilent.funecomp.artist.Sweep
import net.seilent.funecomp.artist.Tile
import net.seilent.funecomp.artist.UrlHttp
import net.seilent.funecomp.link.Link
import net.seilent.funecomp.link.Responder
import net.seilent.funecomp.link.Session
import net.seilent.funecomp.link.TcpTransport
import net.seilent.funecomp.link.Transport
import net.seilent.funecomp.proto.ArtBlock
import net.seilent.funecomp.proto.ArtPick
import net.seilent.funecomp.proto.ArtPickReply
import net.seilent.funecomp.proto.Bye
import net.seilent.funecomp.proto.EqPresetReply
import net.seilent.funecomp.proto.Groups
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
import net.seilent.funecomp.state.Heart
import net.seilent.funecomp.state.PushGate
import net.seilent.funecomp.state.Reconnect
import net.seilent.funecomp.state.Wake
import net.seilent.funecomp.ui.BackdropArt
import net.seilent.funecomp.ui.BrowseTile
import net.seilent.funecomp.ui.Control
import net.seilent.funecomp.ui.LIB_PAGE
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.PickFor
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
    private const val SWEEP = "sweep"
    private const val SWEPT = 2
    private const val TRANSITION_PAD_MS = 1_000L
    private const val OPEN_APP = "open the phone app"
    private val SECRET = Regex("(api_key|key|token|secret)=[^&]*")
    private val ASK_ERRORS = mapOf(
        Err.badValue to "bad value",
        Err.busy to "busy",
        Err.refused to "wrong artist not offered",
        Err.staleRev to "stale rev",
        Err.notFound to "not found",
    )
    private val codec = BitmapCodec { Log.i(TAG, it) }
    private val decoded = Memo<BackdropArt>()
    private val faces = Memo<Found>(FACES)
    internal var forgetting: () -> Unit = {}
    internal var queuedAsk: () -> Unit = {}
    @Volatile var pickAsk: ((PickFor) -> Boolean)? = null
    @Volatile private var app: Context? = null

    internal var faced: (ByteArray, (() -> Pair<Double, Double>?) -> Pair<Double, Double>?) -> Pair<Bitmap, Pair<Double, Double>?>? =
        { src, face -> codec.faced(src, BACKDROP_SIDE, face) }

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

    var blocked by mutableStateOf<List<ArtBlocks.Record>>(emptyList())
        private set

    var picks by mutableStateOf<List<ArtPicks.Pick>>(emptyList())
        private set

    private val backdropLock = Any()
    private val blockedLock = Any()
    private val blockExec = Executors.newSingleThreadExecutor { Thread(it, "artblock").apply { isDaemon = true } }
    private val refreshLock = Any()
    private var refreshNames = HashSet<String>()
    private var refreshFor: List<String>? = null

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
    @Volatile private var backdropRev = 0
    @Volatile private var shown = false
    private val trigger = AtomicInteger()
    @Volatile private var online = true
    @Volatile private var unmetered = false
    private val tracked = HashMap<Network, Boolean>()
    private val breaker = Sweep.Breaker(SystemClock::elapsedRealtime)
    private var wake: Wake? = null
    private var exitLogged = false
    private val clock = ClockSync()
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "clock").apply { isDaemon = true } }
    private var transition: ScheduledFuture<*>? = null
    @Volatile private var thumbs: ThumbWorker? = null
    private val heart = Heart()
    val dapLyrics = DapLyrics({ link?.session }, { device }) { Log.i(TAG, it) }
    @Volatile private var heartToast: ((String, String) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

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
            val l = Link(connect, listener(generation), Reconnect(clock = SystemClock::elapsedRealtime), Session.Config(features = Groups.mask("artBlock", "artPick", "imgOrigin")), SystemClock::elapsedRealtime)
            link = l
            art = worker(context, l)
            l.start()
            return true
        }
    }

    fun stop() {
        app?.let { PickNotice.clear(it) }
        clockOff()
        synchronized(lock) { drop() }
        wake?.drop(ATTEMPT)
        wake?.drop(ART)
        update { DeviceState() }
        dapLyrics.poke()
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
        if (on) trigger.incrementAndGet()
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
        synchronized(tracked) { tracked.clear() }
        unmetered = false
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
        cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                track(network, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            }

            override fun onLost(network: Network) = track(network, null)
        })
    }

    private fun track(network: Network, free: Boolean?) {
        val was = unmetered
        val now = synchronized(tracked) {
            if (free == null) tracked.remove(network) else tracked[network] = free
            tracked.values.any { it }
        }
        unmetered = now
        if (now == was) return
        Log.i(TAG, "art unmetered $now")
        if (now) art?.want()
    }

    private fun swept(context: Context) = context.getSharedPreferences(Main.ART_PREFS, Context.MODE_PRIVATE).getInt(SWEEP, 0) >= SWEPT

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

    override fun log(message: String) {
        Log.i(TAG, message)
    }

    override fun nowPlayingShown(on: Boolean) = dapLyrics.shown(on)

    fun mediaArt(track: Long, px: Int): Bitmap? = thumbs?.media(track, px)

    fun onMediaArt(f: (() -> Unit)?) {
        thumbs?.onMedia = f
    }

    fun artist(context: Context, http: Http = UrlHttp()): ArtistArt = synchronized(lock) {
        thumbs(context)
        app = context.applicationContext
        artist ?: run {
            val c = context.applicationContext
            val version = c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "0"
            network(c)
            val blocks = ArtBlocks(File(c.filesDir, "artblock.tsv")) { Log.w(TAG, it) }
            val picks = ArtPicks(File(c.filesDir, "artpick.tsv")) { Log.w(TAG, it) }
            ArtistArt(http, codec, ArtCache(File(c.filesDir, "art")), { Main.sources(c) }, version, online = { online }, blocks = blocks, picks = picks).also {
                artist = it
                showBlocked { blocks.records() }
                showPicks { picks.list() }
                Thread({ credits = it.credits() }, "art-credits").apply { isDaemon = true }.start()
            }
        }
    }

    internal fun resetArtist() = synchronized(lock) {
        artist = null
        showBlocked { emptyList() }
        showPicks { emptyList() }
    }

    fun sourcesChanged() = art?.want()

    fun reordered() {
        synchronized(refreshLock) {
            refreshFor = device.playback.state.artists
            refreshNames = HashSet(device.playback.state.artists)
        }
        trigger.incrementAndGet()
        art?.want()
    }

    private fun refreshing(name: String): Boolean = synchronized(refreshLock) {
        if (refreshFor != device.playback.state.artists) refreshNames.clear()
        refreshNames.remove(name)
    }

    override fun blockArt(e: ArtCache.Entry) = blockArt(e, null)

    override fun wrongArtist(e: ArtCache.Entry) = wrongArtist(e, null)

    private fun blockArt(e: ArtCache.Entry, crc: Long?, after: () -> Unit = {}) {
        val a = artist ?: return
        val s = a.withSha(e)
        a.blocks.add(ArtBlocks.Block(s.key, s.mbid, s.source, ImageIds.of(s), s.sha, s.name, System.currentTimeMillis(), crc = crc))
        after()
        Log.i(TAG, "art block ${e.name} ${e.source} ${ImageIds.of(e)}")
        blockedArt(a) { a.forget(e) }
    }

    private fun wrongArtist(e: ArtCache.Entry, crc: Long?, after: () -> Unit = {}) {
        val a = artist ?: return
        val by = a.resolvedBy(e)
        a.blocks.add(ArtBlocks.Reject(e.key, e.mbid, by, e.name, System.currentTimeMillis(), crc = crc))
        after()
        Log.i(TAG, "art wrong artist ${e.name} ${e.mbid} by $by")
        blockedArt(a) { a.forget(e) }
    }

    private fun artAsk(q: ArtBlock, st: State, dev: String?, w: Int?, h: Int?, r: Responder) {
        val cmd = q.cmd
        val name = q.name?.takeIf { it.isNotBlank() }
        val crc = q.crc
        val code = if (cmd == null || name == null || crc == null) Err.badValue else artAsk(cmd == Enums.BlockCmd.wrongArtist, name, Names.mbid(q.mbid), crc, st, dev, w, h)
        if (code == null) r.ok() else r.error(code, ASK_ERRORS.getValue(code))
        Log.i(TAG, "art ask ${Enums.BlockCmd.names[cmd ?: -1]} $name -> ${code ?: "ok"}")
    }

    private fun artAsk(wrong: Boolean, name: String, mbid: String?, crc: Long, st: State, dev: String?, w: Int?, h: Int?): Int? {
        val a = artist ?: return Err.busy
        val found = a.candidates(name, mbid).map { it to if (w != null && h != null) a.renderedCrc(it, w, h) else null }
        var hits = found.filter { it.second == crc }.map { it.first }
        if (hits.size > 1 && mbid != null) hits = hits.filter { it.mbid == mbid }.ifEmpty { hits }
        val i = st.artists.indexOfFirst { Names.fold(it) == Names.fold(name) }
        val stMbid = st.mbids.getOrNull(i)?.takeIf { it.isNotBlank() }
        if (wrong) {
            if (stMbid != null) return Err.refused
            if (hits.any { !a.wrongArtistOffered(a.withSha(it), name) }) return Err.refused
        }
        if (hits.isNotEmpty()) {
            val origin = ArtPusher.origin(st.source)
            val push = { a.blocks.addPush(name, if (wrong) null else stMbid ?: mbid, origin) }
            for (e in hits) {
                if (!wrong && a.picked(e)) {
                    blockPick(e, crc, origin)
                    continue
                }
                val done = if (wrong) e.mbid in a.blocks.rejected(a.resolvedBy(e)) else a.blocked(a.withSha(e))
                if (done) Log.i(TAG, "art ask ${e.name} already recorded")
                else if (wrong) wrongArtist(e, crc, push)
                else blockArt(e, crc, push)
            }
            return null
        }
        val keys = found.mapTo(HashSet()) { it.first.key } + a.keyOf(name, mbid)
        val r = a.blocks.covering(wrong, name, keys, mbid, crc, dev)
        if (r != null) return if (r is ArtBlocks.Reject && !a.resolves(r.resolvedBy, name)) Err.refused else null
        return if (found.any { it.second != null }) Err.staleRev else Err.notFound
    }

    private fun blockPick(e: ArtCache.Entry, crc: Long?, origin: Int?) {
        val a = artist ?: return
        val p = a.picks.byCache(e.key) ?: return
        val s = a.withSha(e)
        val at = System.currentTimeMillis()
        p.keys.forEach { k -> a.blocks.add(ArtBlocks.Block(k, p.mbid, s.source, ImageIds.of(s), s.sha, p.name, at, crc = crc)) }
        a.picks.add(p.copy(kind = ArtPicks.NONE, was = p.kind, failedAt = null))
        a.blocks.addPush(p.name, p.mbid, origin)
        Log.i(TAG, "art block pick ${p.name} ${s.source} ${ImageIds.of(s)}")
        showPicks { a.picks.list() }
        blockedArt(a) {
            a.forget(e.key)
            a.picks.save()
        }
    }

    private fun askPick(q: ArtPick, r: Responder) {
        val name = q.name?.trim()?.takeIf { it.isNotEmpty() }
        if (name == null || artist == null) {
            val code = if (name == null) Err.badValue else Err.busy
            r.error(code, ASK_ERRORS.getValue(code))
            Log.i(TAG, "art pick ask $name -> $code")
            return
        }
        val target = PickFor(name, Names.mbid(q.mbid), origin = ArtPusher.origin(device.playback.state.source))
        main.post {
            val shown = pickAsk?.invoke(target) == true
            val c = app
            val out = when {
                shown -> "shown".also { r.ok(ArtPickReply(true).encode()) }
                c != null && PickNotice.post(c, target) -> "notice".also { r.ok(ArtPickReply(false).encode()) }
                else -> "${Err.refused} notifications off".also { r.error(Err.refused, OPEN_APP) }
            }
            Log.i(TAG, "art pick ask $name -> $out")
        }
        queuedAsk()
    }

    override fun pickHandled(name: String) {
        app?.let { PickNotice.done(it, name) }
    }

    override fun pickLink(text: String, done: (Offered) -> Unit) = offered(done) { it.offer(text) }

    override fun takeTile(t: Tile, done: (Offered) -> Unit) = offered(done) { it.take(t) }

    private fun offered(done: (Offered) -> Unit, get: (ArtistArt) -> Offered) {
        val a = artist ?: return done(Offered.Refused(PickLink.UNREACHABLE))
        Thread({
            val o = try {
                get(a)
            } catch (e: RuntimeException) {
                Log.w(TAG, "art pick: $e")
                Offered.Refused(PickLink.UNREACHABLE)
            }
            Log.i(TAG, "art pick offer -> ${if (o is Offered.Refused) o.reason else "ok"}")
            main.post { done(o) }
        }, "art-pick").apply { isDaemon = true }.start()
    }

    override fun browse(name: String, mbid: String?, px: Int, tile: (BrowseTile) -> Unit, done: (Boolean) -> Unit): () -> Unit {
        val a = artist ?: run {
            done(false)
            return {}
        }
        val run = HttpRun { Log.i(TAG, "art browse http ${it.replace(SECRET, "$1=x")}") }
        Thread({
            val ok = try {
                run.on {
                    a.browse(name, mbid, px) { t ->
                        val bmp = codec.faced(t.preview, px) { null }?.first ?: return@browse
                        val b = BrowseTile(t, bmp.asImageBitmap())
                        main.post { if (!run.cancelled) tile(b) }
                    }
                }
            } catch (e: IOException) {
                null
            } catch (e: RuntimeException) {
                Log.w(TAG, "art browse: $e")
                false
            }
            Log.i(TAG, "art browse $name -> ${ok ?: "cancelled"}")
            if (ok != null) main.post { if (!run.cancelled) done(ok) }
        }, "art-browse").apply { isDaemon = true }.start()
        return { run.cancel() }
    }

    override fun pick(target: PickFor, offer: Offer) {
        val a = artist ?: return
        val (k, k2) = ArtPicks.keys(target.name, target.mbid, target.library)
        val mbid = if (offer.kind == ArtPicks.ARTIST) offer.mbid else Names.mbid(target.mbid)
        val p = ArtPicks.Pick(k, k2, target.name.trim(), offer.kind, offer.source, offer.url, offer.id, offer.src?.let { ArtCache.sha256(it) } ?: "", offer.credit, mbid, System.currentTimeMillis())
        Log.i(TAG, "art pick ${p.name} ${p.kind} ${p.source} ${p.keys}")
        pickHandled(p.name)
        picked(a) {
            a.picks.list().filter { o -> o.keys.any { it in p.keys } }.forEach { a.forget(it.cacheKey) }
            a.picks.add(p)
            offer.src?.let { a.store(p, it) }
            a.blocks.addPush(p.name, mbid, if (target.library) Enums.ImgOrigin.library else target.origin)
        }
    }

    override fun undoPick(p: ArtPicks.Pick) {
        val a = artist ?: return
        Log.i(TAG, "art undo pick ${p.name}")
        pickHandled(p.name)
        picked(a) {
            a.picks.remove(p)
            a.forget(p.cacheKey)
            a.blocks.addPush(p.name, Names.mbid(p.key))
        }
    }

    override fun tryAgain(p: ArtPicks.Pick) {
        val a = artist ?: return
        Log.i(TAG, "art pick try again ${p.name}")
        picked(a) { a.picks.retry(p) }
    }

    private fun picked(a: ArtistArt, change: () -> Unit) {
        blockExec.execute {
            forgetting()
            change()
            a.picks.save()
            a.blocks.save()
            synchronized(backdropLock) {
                backdropRev++
                backdrop = null
                backdropFor = null
            }
            decoded.clear()
            showPicks { a.picks.list() }
            trigger.incrementAndGet()
            art?.want()
            credits = a.credits()
        }
    }

    private fun showPicks(list: () -> List<ArtPicks.Pick>) = synchronized(blockedLock) { picks = list() }

    override fun unblock(r: ArtBlocks.Record) {
        val a = artist ?: return
        Log.i(TAG, "art unblock ${r.name}")
        showBlocked { blocked - r }
        blockExec.execute {
            forgetting()
            if (r is ArtBlocks.Block) {
                a.picks.none(r.key)?.takeIf { it.source == r.source && it.id == r.id }?.let { p ->
                    a.blocks.records().filter { it is ArtBlocks.Block && it != r && it.key in p.keys && it.at == r.at && it.source == r.source && it.id == r.id }.forEach { a.blocks.remove(it) }
                    a.picks.add(p.copy(kind = p.was, was = ""))
                    a.picks.save()
                    showPicks { a.picks.list() }
                }
            }
            if (r is ArtBlocks.Reject) {
                a.forgetName(r.resolvedBy)
                a.forget("name:${Names.fold(r.name)}")
                a.forget("name:${Names.fold(r.resolvedBy)}")
            }
            a.forget(r.key)
            a.blocks.remove(r)
            a.blocks.save()
            showBlocked { a.blocks.records() }
            trigger.incrementAndGet()
            art?.want()
        }
    }

    private fun showBlocked(list: () -> List<ArtBlocks.Record>) = synchronized(blockedLock) { blocked = list() }

    private fun blockedArt(a: ArtistArt, forget: () -> Unit) {
        synchronized(backdropLock) {
            backdropRev++
            backdrop = null
            backdropFor = null
        }
        showBlocked { a.blocks.records() }
        trigger.incrementAndGet()
        blockExec.execute {
            forgetting()
            a.blocks.save()
            forget()
            decoded.clear()
            trigger.incrementAndGet()
            art?.want()
            credits = a.credits()
        }
    }

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
        val snap = device.lib.keyed(Enums.View.artists)
        val r = s.call(Msg.LIB_GET, Req.libGet(Enums.View.artists, "", offset, LIB_PAGE, rev.takeUnless { snap }, rev.takeIf { snap }))
        if (r !is Session.Result.Ok) forget(key)
        return r
    }

    private fun worker(context: Context, l: Link): ArtWorker {
        val a = artist(context)
        val forward = Forward(
            { device },
            { name, mbid, w, h ->
                if (refreshing(name)) {
                    a.refresh(name, mbid, w, h).also { synchronized(backdropLock) { backdropFor = null } }
                } else {
                    a.get(name, mbid, w, h)
                }
            },
            ::gate,
            { !shown && online && !breaker.open && (unmetered || swept(context)) },
            { !shown && !Firmwares.sending },
            ::libPage,
            breaker,
            { online },
            { credits = a.credits() },
            object : Forward.Marks {
                override fun begin() = a.beginMark()
                override fun mark(): Any = a.mark()
                override fun holds(mark: Any) = mark is ArtistArt.Mark && a.holds(mark)
            },
            Forward.Blocks(a.blocks, { spec }, a::keyOf, a::hasHit) { !Firmwares.sending },
            a::known,
            { !shown },
        ) { Log.i(TAG, it) }
        return ArtWorker(
            { l.session },
            { s, run ->
                a.scanPlaceholders().takeIf { it > 0 }?.let { Log.i(TAG, "art placeholders dropped $it") }
                forward.run(s, run::live)
            },
            { final -> !shown || showBackdrop(a, final) },
            { if (shown) fetch(a) },
            trigger = { trigger.get() },
            onHttp = { url ->
                Log.i(TAG, "art http ${url.replace(SECRET, "$1=x")}")
                if (PushGate.open(device, SystemClock.elapsedRealtime())) wake?.hold(ART, ART_WAKE_MS)
            },
            ended = { wake?.drop(ART) },
        ) { r, e ->
            try {
                if (r != null) Log.i(TAG, "art ${r.stop} ${r.counts()}")
                if (r?.complete == true && !swept(context)) context.getSharedPreferences(Main.ART_PREFS, Context.MODE_PRIVATE).edit().putInt(SWEEP, SWEPT).apply()
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
        val rev = backdropRev
        val found = s.artists.withIndex().firstNotNullOfOrNull { (i, n) -> a.cachedEntry(n, s.mbids.getOrNull(i))?.let { i to it } }
        val e = found?.second
        if (e == null) {
            if (final) {
                synchronized(backdropLock) {
                    backdropFor = s.artists
                    backdrop = null
                }
                decoded.clear()
            }
            return false
        }
        val full = a.withSha(e)
        val wrong = a.wrongArtistOffered(full, s.artists[found.first]) && s.mbids.getOrNull(found.first).isNullOrBlank()
        val id = "${e.key}@${e.at}"
        val art = decoded.get(id) {
            a.source(e)?.let { src -> faced(src) { detect -> faces.get(id) { Found(detect()) }?.at } }?.let { (bmp, face) ->
                BackdropArt(bmp.asImageBitmap(), face?.let { it.first / bmp.width to it.second / bmp.height })
            }
        } ?: return false
        synchronized(backdropLock) {
            if (rev != backdropRev || a.blocked(full)) return false
            backdropFor = s.artists
            backdrop = BackdropArt(art.image, art.face, full, wrong)
        }
        return true
    }

    private fun drop() {
        generation++
        heart.down()
        heartToast = null
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

    fun lrcPut(track: Long, libRev: Long?, snapRev: Long?, text: ByteArray, crc: Long, synced: Boolean, done: (Int?) -> Unit) {
        val s = link?.session ?: return done(-1)
        s.request(Msg.LRC_PUT, Req.lrcPut(track, libRev, text, crc, snapRev)) { r ->
            if (r is Session.Result.Ok) dapLyrics.dropped(track)
            Log.i(TAG, "lrc put ${java.lang.Long.toUnsignedString(track)} ${text.size} ${if (synced) "synced" else "plain"} -> ${if (r is Session.Result.Failed) "error ${r.error.code}" else r.javaClass.simpleName.lowercase()}")
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
        val lib = device.lib
        val key = lib.askKey(view, parent, offset)
        if (!once(key)) return
        send(Msg.LIB_GET, Req.libGet(view, parent, offset, LIB_PAGE, lib.libRev(view), lib.snapRev(view)), key, fail = { forget(key) })
    }

    override fun play(view: Int?, parent: String?, start: Long, shuffle: Boolean, tracks: List<Long>) {
        val lib = device.lib.takeIf { tracks.isEmpty() }
        send(Msg.PLAY, Req.play(view, parent, lib?.libRev(view), start, shuffle, tracks, lib?.snapRev(view)), "play view=$view parent=$parent start=$start", failed = { code ->
            if (code == Err.badValue && start > 0 && tracks.isEmpty()) play(view, parent, 0, shuffle, tracks)
        })
    }

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

    override fun draft(kind: Int, value: String?, minutes: Long?, unixS: Long?, zoneS: Int?, done: (Int?, String?) -> Unit) =
        send(Msg.DRAFT, Req.draft(kind, value, minutes = minutes, unixS = unixS, zoneS = zoneS), "draft ${Enums.DraftKind.names[kind]}", done = { code, text -> main.post { done(code, text) } })

    override fun eqSet(output: Int, key: String?, ints: Profile?) =
        send(Msg.EQ_SET, Req.eqSet(output, key, ints), "eq set output=$output key=$key")

    override fun eqPreset(op: Int, output: Int?, key: String?, name: String?, ints: Profile?, replace: Boolean, done: (Int?, String?) -> Unit) {
        var saved: String? = null
        send(
            Msg.EQ_PRESET, Req.eqPreset(op, output, key, name, ints, replace.takeIf { it }),
            "eq preset ${Enums.EqPresetOp.names[op]} output=$output key=$key replace=$replace",
            ok = { saved = EqPresetReply.from(it).key },
            done = { code, _ -> main.post { done(code, saved) } },
        )
    }

    override fun action(cmd: Int, track: Long?, done: (Int?, String?) -> Unit) =
        send(Msg.ACTION, Req.action(cmd, track), "action ${Enums.ActionCmd.names[cmd]}", done = done)

    override fun favorite(s: State, toast: (String, String) -> Unit) {
        if (device.hello?.snapRev == null) return
        heartToast = toast
        val t = heart.tap(s) ?: return
        action(Enums.ActionCmd.favorite, t.track) { code, text ->
            heartShow(heart.replied(t, code, text, SystemClock.elapsedRealtime()))
            if (code == null) timer.schedule({ heartShow(heart.tick(t, SystemClock.elapsedRealtime())) }, Heart.WINDOW_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun heartShow(t: Heart.Toast?) {
        if (t != null) main.post { heartToast?.invoke(t.line, t.title) }
    }

    private fun once(key: String) = synchronized(asked) { asked.add(key) }

    private fun forget(key: String) = synchronized(asked) { asked.remove(key) }

    private fun send(type: Int, payload: ByteArray, what: String, ok: ((Node) -> Unit)? = null, fail: (() -> Unit)? = null, failed: ((Int) -> Unit)? = null, done: ((Int?, String?) -> Unit)? = null) {
        val s = link?.session
        if (s == null) {
            Log.i(TAG, "$what: no session")
            fail?.invoke()
            done?.invoke(-1, null)
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
            if (r is Session.Result.Failed) failed?.invoke(r.error.code)
            when (r) {
                is Session.Result.Ok -> done?.invoke(null, null)
                is Session.Result.Failed -> done?.invoke(r.error.code, r.error.text)
                else -> done?.invoke(-1, null)
            }
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
                (hello.snapRev ?: hello.libRev)?.let { t.rev(it) }
                t.session()
            }
            update { it.withHello(hello) }
            synchronized(asked) { asked.clear() }
            bye = null
            everLive = true
            board = hello.board
            Scrobbles.session(hello.has("stateClock"))
            Firmwares.hello(hello.board, hello.version)
            Lyrics.session()
            dapLyrics.session()
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
            dapLyrics.poke()
            val s = State.from(body)
            (s.snapRev ?: s.libRev)?.let { r -> if (r != (old.snapRev ?: old.libRev)) thumbs?.rev(r) }
            heartShow(heart.state(s))
            Scrobbles.state(s, System.currentTimeMillis())
            Firmwares.state()
            onState?.invoke()
            Log.i(TAG, "state applied title=${s.title} artist=${s.artist} playing=${s.playing} volume=${s.volume}/${s.maxVolume} battery=${s.battery} charging=${s.charging} card=${s.cardFreeMiB}/${s.cardTotalMiB} pos=${s.positionMs} sent=${s.sentMs} sample=${s.sampleMs} rtt=${device.rttMs} at=$now lateMs=${device.playback.lateMs} held=${s.positionHeld}")
            if (old.title != null && old.title != s.title) Log.i(TAG, "title changed ${old.title} -> ${s.title}")
            val key = listOf(s.artists, s.output, s.queueRev, s.slot)
            if (key != artKey) {
                artKey = key
                trigger.incrementAndGet()
                art?.want()
            }
        }

        override fun onRtt(ms: Long) {
            if (!live) return
            update { it.withRtt(ms) }
            Log.d(TAG, "rtt ms=$ms used=${device.rttMs} at=${SystemClock.elapsedRealtime()}")
        }

        override fun onRequest(type: Int, body: Node, responder: Responder): Boolean {
            if (!live) return false
            if (type == Msg.ART_PICK) {
                askPick(ArtPick.from(body), responder)
                return true
            }
            if (type != Msg.ART_BLOCK) return false
            val q = ArtBlock.from(body)
            val dev = spec
            val h = device.hello
            val st = device.playback.state
            blockExec.execute { artAsk(q, st, dev, h?.backdropW, h?.backdropH, responder) }
            queuedAsk()
            return true
        }

        override fun onBye(bye: Bye) {
            if (!live) return
            this@Remote.bye = bye.reason
            Log.i(TAG, "bye ${Enums.ByeReason.names[bye.reason] ?: bye.reason}")
        }

        override fun onClosed(reason: String) {
            if (!live) return
            app?.let { PickNotice.clear(it) }
            update { it.copy(hello = null) }
            dapLyrics.poke()
            heart.down()
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
