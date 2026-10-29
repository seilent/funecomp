package net.seilent.funecomp

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Resources
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
import net.seilent.funecomp.art.Gallery
import net.seilent.funecomp.art.Memo
import net.seilent.funecomp.art.Pending
import net.seilent.funecomp.art.ThumbWorker
import net.seilent.funecomp.artist.ArtBlocks
import net.seilent.funecomp.artist.ArtCache
import net.seilent.funecomp.artist.ArtPicks
import net.seilent.funecomp.artist.ArtPusher
import net.seilent.funecomp.artist.ArtistArt
import net.seilent.funecomp.artist.Backdrop
import net.seilent.funecomp.artist.BrowseFeed
import net.seilent.funecomp.artist.Clock
import net.seilent.funecomp.artist.FullPush
import net.seilent.funecomp.artist.GalleryStore
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.HttpRun
import net.seilent.funecomp.artist.ImageIds
import net.seilent.funecomp.artist.Names
import net.seilent.funecomp.artist.Offer
import net.seilent.funecomp.artist.Offered
import net.seilent.funecomp.artist.PickLink
import net.seilent.funecomp.artist.Size
import net.seilent.funecomp.artist.Sources
import net.seilent.funecomp.artist.Sweep
import net.seilent.funecomp.artist.Targets
import net.seilent.funecomp.artist.Tile
import net.seilent.funecomp.artist.UrlHttp
import net.seilent.funecomp.artist.YouTube
import net.seilent.funecomp.link.ArtGet
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
import net.seilent.funecomp.proto.LyrOffsetReply
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Profile
import net.seilent.funecomp.proto.QueueOpReply
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.SetReply
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.state.CardView
import net.seilent.funecomp.state.ClockSync
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.state.Heart
import net.seilent.funecomp.state.PhoneKey
import net.seilent.funecomp.state.PushGate
import net.seilent.funecomp.state.Reconnect
import net.seilent.funecomp.state.Wake
import net.seilent.funecomp.ui.BackdropArt
import net.seilent.funecomp.ui.BrowseTile
import net.seilent.funecomp.ui.Control
import net.seilent.funecomp.ui.LIB_PAGE
import net.seilent.funecomp.ui.OffsetEdit
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.PickFor
import net.seilent.funecomp.ui.Preview
import net.seilent.funecomp.ui.Previewed
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

object Remote : Control {
    const val TAG = "Funecomp"
    const val RECEIVER = DeviceState.RECEIVER
    private val REFUSALS = setOf(RECEIVER)
    private const val BACKDROP_SIDE = 2048
    private const val BACKDROP_MAX = 4096
    private const val REFETCH_SLACK_MS = 200L
    private const val FETCH_W = 360
    private const val FETCH_H = 480
    private const val FACES = 64
    private const val ATTEMPT_WAKE_MS = 20_000L
    private const val ATTEMPT = "attempt"
    private const val ART = "art"
    private const val ART_WAKE_MS = 60_000L
    private const val SWEEP = "sweep"
    private const val SWEPT = 2
    private const val BARE_SCAN = "barescan"
    private const val PHONE_TARGET = "phoneTarget"
    private const val DEVICE_TARGETS = "deviceTargets"
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
    internal var asking: () -> Unit = {}
    @Volatile var pickAsk: ((PickFor) -> Boolean)? = null
    private val pickLock = Any()
    private var resolving: HttpRun? = null
    @Volatile private var app: Context? = null

    internal var faced: (ByteArray, (() -> Pair<Double, Double>?) -> Pair<Double, Double>?) -> Pair<Bitmap, Pair<Double, Double>?>? =
        { src, face -> codec.faced(src, decodeSide(src), face) }

    @Volatile private var browseStart = 0L
    internal var browseDecode: (ByteArray, Int) -> ImageBitmap? = { b, px -> codec.faced(b, px) { null }?.first?.asImageBitmap() }

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

    var galleryMissing by mutableStateOf<Set<String>>(emptySet())
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
    internal val linkSpec: String? get() = spec
    private val asked = HashSet<String>()
    private var artist: ArtistArt? = null
    private var pending: Pending? = null
    @Volatile private var art: ArtWorker? = null
    @Volatile private var artKey: List<Any?>? = null
    @Volatile private var backdropFor: List<String>? = null
    @Volatile private var backdropRev = 0
    @Volatile private var shown = false
    private val trigger = AtomicInteger()
    @Volatile private var online = true
    @Volatile private var unmetered = false
    @Volatile private var targets = Targets.NONE
    @Volatile private var window: Size? = null
    private val tracked = HashMap<Network, Boolean>()
    private val breaker = Sweep.Breaker(SystemClock::elapsedRealtime)
    private var wake: Wake? = null
    private var exitLogged = false
    private val clock = ClockSync()
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "clock").apply { isDaemon = true } }
    private var transition: ScheduledFuture<*>? = null
    @Volatile private var thumbs: ThumbWorker? = null
    private val heart = Heart()
    val phoneLyrics: PhoneLyrics = PhoneLyrics(Lyrics::phone, Lyrics::phoneChain, { dapLyrics.poke() }) { Log.i(TAG, it) }
    val dapLyrics: DapLyrics = DapLyrics({ link?.session }, { device }, phone = phoneLyrics) { Log.i(TAG, it) }
    val phoneCover = PhoneCover({ crc, cancel -> link?.session?.let { ArtGet(it, cancel).run(PhoneCover.PHONE_TRACK, Proto.artMaxPx) } }, log = { Log.i(TAG, it) })
    @Volatile private var offset: OffsetEdit? = null
    @Volatile private var heartToast: ((String, String) -> Unit)? = null
    private val refusalToast = AtomicReference<((String, String) -> Unit)?>()
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
            val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
            val l = Link(connect, listener(generation), Reconnect(clock = SystemClock::elapsedRealtime), Session.Config(version, features = Groups.mask("artBlock", "artPick", "imgOrigin", "playLog", "imgSource")), SystemClock::elapsedRealtime)
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
        phoneCover.clear()
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

    private fun targets(c: Context): Targets {
        val p = c.getSharedPreferences(Main.ART_PREFS, Context.MODE_PRIVATE)
        return Targets.of(p.getString(PHONE_TARGET, null), p.getString(DEVICE_TARGETS, null)).also { targets = it }
    }

    internal fun decodeSide(src: ByteArray): Int {
        val win = window ?: return BACKDROP_SIDE
        val s = codec.bounds(src) ?: return BACKDROP_SIDE
        return Backdrop.side(s, win, BACKDROP_SIDE, BACKDROP_MAX)
    }

    fun window(context: Context, w: Int, h: Int) = synchronized(lock) {
        if (w <= 0 || h <= 0) return@synchronized
        window = Targets.grow(window, Size(w, h))
        val p = context.getSharedPreferences(Main.ART_PREFS, Context.MODE_PRIVATE)
        val old = Size.parse(p.getString(PHONE_TARGET, null))
        val now = Targets.grow(old, Targets.phone(w, h))
        if (now != old) {
            p.edit().putString(PHONE_TARGET, now.toString()).apply()
            Log.i(TAG, "art target $now")
        }
        targets(context)
    }

    private fun backdropSeen(hello: HelloReply) {
        val c = app ?: return
        val board = hello.board?.takeIf { it.isNotBlank() } ?: return
        val size = Size(hello.backdropW ?: return, hello.backdropH ?: return)
        synchronized(lock) {
            val p = c.getSharedPreferences(Main.ART_PREFS, Context.MODE_PRIVATE)
            val all = Targets.devices(p.getString(DEVICE_TARGETS, null))
            val now = Targets.grow(all[board], size)
            if (now != all[board]) p.edit().putString(DEVICE_TARGETS, Targets.devices(all + (board to now))).apply()
            targets(c)
        }
    }

    private fun scanBare(context: Context, a: ArtistArt) {
        val prefs = context.getSharedPreferences(Main.ART_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(BARE_SCAN, false)) return
        try {
            val n = a.scanBare()
            if (n == null) {
                Log.i(TAG, "art bare scan skipped")
                return
            }
            prefs.edit().putBoolean(BARE_SCAN, true).apply()
            Log.i(TAG, "art bare scan dropped $n")
            if (n > 0) {
                resetBackdrop()
                decoded.clear()
            }
        } catch (e: IOException) {
            Log.w(TAG, "art bare scan: $e")
        }
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
        thumbs ?: ThumbWorker(File(context.applicationContext.filesDir, "thumb"), { link?.session }, { device.card }) { Log.i(TAG, it) }.also { thumbs = it }
    }

    override fun wantArt(slot: Int, track: Long, px: Int, album: String?) {
        if (device.has("art")) thumbs?.want(slot, track, px, album)
    }

    override fun art(track: Long, px: Int): ImageBitmap? = thumbs?.image(track, px)

    override fun log(message: String) {
        Log.i(TAG, message)
    }

    override fun nowPlayingShown(on: Boolean) = dapLyrics.shown(on)

    override fun lyricsOffset(track: Long, ms: Int?, done: (Int?, Int?) -> Unit) {
        var got: Int? = null
        send(Msg.LYR_OFFSET, Req.lyrOffset(track, ms), "lyr offset ${java.lang.Long.toUnsignedString(track)} ${ms ?: "read"}", ok = { got = LyrOffsetReply.from(it).ms }, done = { code, _ -> done(code, got) })
    }

    override fun lyricsOffsetEdit(edit: OffsetEdit?) {
        offset = edit
    }

    override fun lyricsHold(track: Long?) = if (track == null) dapLyrics.unhold() else dapLyrics.offsetSet(track)

    override fun phoneLyricsMode() = phoneLyrics.mode

    override fun phoneLyricsMode(on: Boolean) {
        phoneLyrics.mode = on
    }

    override fun phoneOffset(key: PhoneKey, ms: Int?, done: (Int?, Int?) -> Unit) {
        if (ms != null && ms !in -Proto.lyrOffsetMaxMs..Proto.lyrOffsetMaxMs) return done(Err.badValue, null)
        if (ms != null) {
            phoneLyrics.offset(key, ms)
            dapLyrics.poke()
        }
        done(null, phoneLyrics.offset(key))
    }

    override fun phoneCover(crc: Long): ImageBitmap? = phoneCover.image(crc)

    override fun lyricsMode() = app?.let { LyricsMode.on(it) } == true

    override fun lyricsMode(on: Boolean) {
        app?.let { LyricsMode.on(it, on) }
    }

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
            targets(c)
            ArtistArt(http, codec, ArtCache(File(c.filesDir, "art")), { Main.sources(c) }, version, online = { online }, blocks = blocks, picks = picks, gallery = GalleryStore(File(c.filesDir, "pickimg")), log = { Log.w(TAG, it.replace(SECRET, "$1=x")) }, browseLog = { Log.i(TAG, it.replace(SECRET, "$1=x")) }, targets = { targets }).also {
                artist = it
                showBlocked { blocks.records() }
                showPicks { picks.list() }
                blockExec.execute { gallery(it) }
                Thread({ credits = it.credits() }, "art-credits").apply { isDaemon = true }.start()
            }
        }
    }

    private fun pending(context: Context): Pending = synchronized(lock) {
        pending ?: Pending(File(context.applicationContext.filesDir, "art/pending.txt")) { Log.w(TAG, it) }.also { pending = it }
    }

    internal fun resetArtist() = synchronized(lock) {
        artist = null
        breaker.network()
        pending = null
        showBlocked { emptyList() }
        showPicks { emptyList() }
        galleryMissing = emptySet()
    }

    fun sourcesChanged() {
        resetBackdrop()
        art?.want()
    }

    fun phoneAudio(playing: Boolean) {
        Log.i(TAG, "phone a2dp ${if (playing) "playing" else "idle"}")
        if (!playing) art?.want()
    }

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

    override fun blockArt(e: ArtCache.Entry) = blockArt(e, shownCrc(e))

    override fun wrongArtist(e: ArtCache.Entry) = wrongArtist(e, shownCrc(e))

    private fun shownCrc(e: ArtCache.Entry): Long? {
        val h = device.hello ?: return null
        val a = artist ?: return null
        if (h.has("imgSource")) a.pushCrc(e)?.let { return it }
        return a.renderedCrc(e, h.backdropW ?: return null, h.backdropH ?: return null)
    }

    private fun crcs(a: ArtistArt, e: ArtCache.Entry, w: Int?, h: Int?): Set<Long> =
        setOfNotNull(if (w != null && h != null) a.renderedCrc(e, w, h) else null, a.pushCrc(e))

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
        a.blocks.add(ArtBlocks.Reject(e.key, e.mbid, by, e.name, System.currentTimeMillis(), crc = crc, channel = e.channel))
        after()
        Log.i(TAG, "art wrong artist ${e.name} ${e.mbid} by $by")
        blockedArt(a) { a.forget(e) }
    }

    private fun artAsk(q: ArtBlock, st: State, dev: String?, w: Int?, h: Int?, r: Responder) {
        asking()
        val cmd = q.cmd
        val name = q.name?.takeIf { it.isNotBlank() }
        val crc = q.crc
        val code = if (cmd == null || name == null || crc == null) Err.badValue else artAsk(cmd == Enums.BlockCmd.wrongArtist, name, Names.mbid(q.mbid), crc, st, dev, w, h)
        if (code == null) r.ok() else r.error(code, ASK_ERRORS.getValue(code))
        Log.i(TAG, "art ask ${Enums.BlockCmd.names[cmd ?: -1]} $name -> ${code ?: "ok"}")
    }

    private fun artAsk(wrong: Boolean, name: String, mbid: String?, crc: Long, st: State, dev: String?, w: Int?, h: Int?): Int? {
        val a = artist ?: return Err.busy
        val found = a.candidates(name, mbid).map { it to crcs(a, it, w, h) }
        var hits = found.filter { crc in it.second }.map { it.first }
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
                val done = if (!wrong) a.blocked(a.withSha(e)) else if (e.channel.isNotEmpty()) e.channel in a.blocks.rejectedChannels(a.resolvedBy(e)) else e.mbid in a.blocks.rejected(a.resolvedBy(e))
                if (done) Log.i(TAG, "art ask ${e.name} already recorded")
                else if (wrong) wrongArtist(e, crc, push)
                else blockArt(e, crc, push)
            }
            return null
        }
        val keys = found.mapTo(HashSet()) { it.first.key } + a.keyOf(name, mbid)
        val r = a.blocks.covering(wrong, name, keys, mbid, crc, dev)
        if (r != null) return if (r is ArtBlocks.Reject && !a.resolves(r.resolvedBy, name)) Err.refused else null
        return if (found.any { it.second.isNotEmpty() }) Err.staleRev else Err.notFound
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

    override fun pickLink(text: String, name: String, done: (Previewed) -> Unit) = resolve(done, PickLink.UNREACHABLE, name) { it.offer(text) }

    override fun takeTile(t: Tile, done: (Previewed) -> Unit) = resolve(done, PickLink.UNREACHABLE) { it.take(t) }

    fun galleryPicked(read: () -> Gallery?, done: (Previewed) -> Unit) = resolve(done, PickLink.NOT_IMAGE) { a ->
        read()?.let { g -> g.jpeg?.let { a.galleryOffer(it, g.name) } ?: Offered.Refused(PickLink.NOT_IMAGE) }
    }

    fun cancelPick() = synchronized(pickLock) { resolving?.cancel() }

    private fun resolve(done: (Previewed) -> Unit, failed: String, name: String = "", get: (ArtistArt) -> Offered?): () -> Unit {
        val run = HttpRun { Log.i(TAG, "art pick http ${it.replace(SECRET, "$1=x")}") }
        synchronized(pickLock) {
            resolving?.cancel()
            resolving = run
        }
        val a = artist
        Thread({
            val p = try {
                if (a == null) Previewed.Refused(failed) else run.on { previewed(a, name, get(a), run) }
            } catch (e: IOException) {
                Previewed.Refused(failed)
            } catch (e: RuntimeException) {
                Log.w(TAG, "art pick: ${e.toString().replace(SECRET, "$1=x")}")
                Previewed.Refused(failed)
            }
            if (run.cancelled) {
                Log.i(TAG, "art pick cancelled")
                return@Thread
            }
            Log.i(TAG, "art pick offer -> ${(p as? Previewed.Refused)?.reason ?: (p as? Previewed.Ok)?.previews?.size?.let { "ok $it" } ?: "closed"}")
            main.post { if (!run.cancelled) done(p) }
        }, "art-pick").apply { isDaemon = true }.start()
        return { run.cancel() }
    }

    private fun previewed(a: ArtistArt, name: String, o: Offered?, run: HttpRun): Previewed {
        if (o == null) return Previewed.Closed
        if (o is Offered.Refused) return Previewed.Refused(o.reason)
        val h = device.hello
        val w = h?.backdropW ?: FETCH_W
        val hh = h?.backdropH ?: FETCH_H
        val shown = (o as Offered.Ok).offers.mapNotNull { offer ->
            if (run.cancelled) return Previewed.Closed
            if (offer.kind == ArtPicks.ARTIST) {
                val src = offer.mbid?.let { m ->
                    try {
                        a.previewArtist(name.ifBlank { offer.title }, m, w, hh)
                    } catch (e: IOException) {
                        if (run.cancelled) return Previewed.Closed
                        null
                    }
                }
                Preview(offer, src?.let { preview(it, w, hh) })
            } else {
                offer.src?.let { preview(it, w, hh) }?.let { Preview(offer, it) }
            }
        }
        return if (shown.isEmpty()) Previewed.Refused(PickLink.TOO_SMALL) else Previewed.Ok(shown)
    }

    internal fun preview(src: ByteArray, w: Int, h: Int, decode: (ByteArray, (() -> Pair<Double, Double>?) -> Pair<Double, Double>?) -> Pair<Bitmap, Pair<Double, Double>?>? = faced): ImageBitmap? {
        val (bmp, face) = decode(src) { it() } ?: return null
        val r = Backdrop.crop(bmp.width, bmp.height, w, h, face) ?: return null
        val crop = Bitmap.createBitmap(bmp, r.x, r.y, r.w, r.h)
        if (crop !== bmp) bmp.recycle()
        val side = maxOf(Resources.getSystem().displayMetrics.run { maxOf(widthPixels, heightPixels) }, maxOf(w, h) * 4)
        val long = maxOf(r.w, r.h)
        if (long <= side) return crop.asImageBitmap()
        val out = Bitmap.createScaledBitmap(crop, maxOf(1, r.w * side / long), maxOf(1, r.h * side / long), true)
        if (out !== crop) crop.recycle()
        return out.asImageBitmap()
    }

    override fun browse(name: String, mbid: String?, px: Int, sections: (List<String>) -> Unit, tile: (String, BrowseTile) -> Unit, sectionDone: (String) -> Unit, search: (YouTube.Search) -> Unit, done: (Boolean) -> Unit): () -> Unit {
        val a = artist ?: run {
            sections(emptyList())
            done(false)
            return {}
        }
        val log: (String) -> Unit = { Log.i(TAG, it.replace(SECRET, "$1=x")) }
        val run = HttpRun { log("art browse http $it") }
        val n = AtomicInteger()
        val pool = Executors.newFixedThreadPool(ArtistArt.BROWSE_WORKERS) { r -> Thread(r, "art-browse-${n.incrementAndGet()}").apply { isDaemon = true } }
        run.onCancel { pool.shutdownNow() }
        val out = object : BrowseFeed.Out<ImageBitmap> {
            override fun sections(order: List<String>) = sections(order)

            override fun tile(section: String, t: Tile, image: ImageBitmap) = tile(section, BrowseTile(t, image))

            override fun sectionDone(section: String) = sectionDone(section)

            override fun search(outcome: YouTube.Search) = search(outcome)

            override fun done(ok: Boolean) = done(ok)
        }
        val feed = BrowseFeed({ browseDecode(it, px) }, { main.post(it) }, Clock.SYSTEM, run, { r -> Thread(r, "art-browse-decode").apply { isDaemon = true } }, out, log)
        val t0 = SystemClock.elapsedRealtime()
        browseStart = t0
        Thread({
            Log.i(TAG, "art browse start $name")
            var ended = true
            try {
                run.on { a.browse(name, mbid, px, feed, pool) }
            } catch (e: IOException) {
                ended = false
            } catch (e: RuntimeException) {
                Log.w(TAG, "art browse: ${e.toString().replace(SECRET, "$1=x")}")
                feed.fail()
            } finally {
                pool.shutdown()
                val ok = if (ended && !run.cancelled) feed.result.toString() else "cancelled"
                Log.i(TAG, "art browse end $name -> $ok +${SystemClock.elapsedRealtime() - t0}")
                if (ended) feed.finish()
            }
        }, "art-browse").apply { isDaemon = true }.start()
        return { run.cancel() }
    }

    override fun browseShown(section: String) {
        Log.i(TAG, "art browse shown $section +${SystemClock.elapsedRealtime() - browseStart}")
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
            offer.src?.let {
                if (p.source == Sources.GALLERY) a.gallery?.put(it)
                a.store(p, it)
            }
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
            gallery(a)
            resetBackdrop()
            decoded.clear()
            showPicks { a.picks.list() }
            trigger.incrementAndGet()
            art?.want()
            credits = a.credits()
        }
    }

    private fun showPicks(list: () -> List<ArtPicks.Pick>) = synchronized(blockedLock) { picks = list() }

    private fun gallery(a: ArtistArt) {
        a.pruneGallery()?.takeIf { it > 0 }?.let { Log.i(TAG, "art pick pruned $it") }
        galleryMissing = a.galleryMissing()
    }

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

    override fun clearArt(source: String?) {
        val a = artist ?: return
        blockExec.execute {
            forgetting()
            val crcs = HashMap<String, Set<Long>>()
            val seen = { e: ArtCache.Entry, c: Set<Long> -> crcs[e.key] = c }
            val gone = if (source == null) a.clearAll(seen) else a.clearSource(source, seen)
            val now = System.currentTimeMillis()
            for (e in gone) if (a.picks.of(e.mbid, e.name) == null) a.blocks.cleared(e.key, e.mbid, e.name, now, crcs[e.key].orEmpty())
            a.blocks.save()
            if (source == null) app?.getSharedPreferences(Main.ART_PREFS, Context.MODE_PRIVATE)?.edit()?.remove(SWEEP)?.apply()
            Log.i(TAG, "art clear ${source ?: "all"} ${gone.size}")
            resetBackdrop()
            decoded.clear()
            trigger.incrementAndGet()
            art?.want()
            credits = a.credits()
        }
    }

    override fun artUsage(done: (Map<String?, ArtCache.Usage>) -> Unit) {
        val a = artist ?: return
        blockExec.execute {
            val u = a.usage()
            main.post { done(u) }
        }
    }

    private fun resetBackdrop() = synchronized(backdropLock) {
        backdropRev++
        backdrop = null
        backdropFor = null
    }

    private fun blockedArt(a: ArtistArt, forget: () -> Unit) {
        resetBackdrop()
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

    @Volatile var a2dp: android.bluetooth.BluetoothA2dp? = null

    @Volatile var phoneA2dp: () -> Boolean = ::a2dpPlaying

    internal fun a2dpPlaying(): Boolean {
        val p = a2dp ?: return false
        return try {
            p.connectedDevices.any { p.isA2dpPlaying(it) }
        } catch (_: SecurityException) {
            false
        }
    }

    private fun bluetooth(): Boolean = PushGate.bluetooth(device) || phoneA2dp()

    internal fun refetchData(): Boolean = held(!Firmwares.sending && !phoneA2dp() && PushGate.refetch(device, SystemClock.elapsedRealtime()))

    internal fun refetchRearm(): Boolean = held(!Firmwares.sending && PushGate.rearm(device))

    private fun held(open: Boolean): Boolean {
        if (open) wake?.hold(ART, ART_WAKE_MS)
        return open
    }

    internal val wakeHeld: Set<String> get() = wake?.held ?: emptySet()

    private fun phoneGate(): Boolean {
        val open = PushGate.phoneArt(device) && !Firmwares.sending && !PushGate.card(device)
        if (open) wake?.hold(ART, ART_WAKE_MS)
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
            { name, mbid, w, h, library ->
                val search = !library || unmetered
                if (refreshing(name)) {
                    a.refresh(name, mbid, w, h, search).also { synchronized(backdropLock) { backdropFor = null } }
                } else {
                    a.get(name, mbid, w, h, search)
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
                override fun holds(mark: Any) = mark is ArtistArt.Mark && a.holds(mark, unmetered)
            },
            Forward.Blocks(a.blocks, { spec }, a::keyOf, a::hasHit) { !Firmwares.sending && !PushGate.card(device) },
            a::known,
            { !shown },
            { PushGate.card(device) },
            pending(context),
            ::phoneGate,
            object : Forward.Upgrades {
                override fun due() = a.upgrades().map { it.name to it.mbid }
                override fun run(name: String, mbid: String?, w: Int, h: Int) =
                    a.cachedEntry(name, mbid)?.takeIf { a.upgradable(it) }?.let { e -> a.upgrade(e, w, h).let { Forward.Upgraded(it.replaced, it.bytes, e.key, it.at) } }
                override fun open() = !shown && online && !breaker.open && unmetered
                override fun at(key: String) = a.hitAt(key)
            },
            { Main.sources(context).anyOn },
            FullPush(a.full, ::bluetooth) { Log.i(TAG, it) },
            Forward.Refetch(
                ::refetchData,
                ::refetchRearm,
                { unmetered },
                { device.card.epoch },
                { device.playback.state.playing == true },
            ),
        ) { Log.i(TAG, it) }
        return ArtWorker(
            { l.session },
            { s, run ->
                a.scanPlaceholders().takeIf { it > 0 }?.let { Log.i(TAG, "art placeholders dropped $it") }
                scanBare(context, a)
                forward.run(s, run::live)
            },
            { final -> !shown || showBackdrop(a, final) },
            { if (shown) fetch(a) },
            trigger = { trigger.get() },
            onHttp = { url ->
                Log.i(TAG, "art http ${url.replace(SECRET, "$1=x")}")
                if (PushGate.open(device, SystemClock.elapsedRealtime()) || PushGate.phoneArt(device)) wake?.hold(ART, ART_WAKE_MS)
            },
            ended = { wake?.drop(ART) },
        ) { r, e ->
            try {
                if (r != null) Log.i(TAG, "art ${r.stop} ${r.counts()}")
                if (r?.complete == true && !swept(context)) context.getSharedPreferences(Main.ART_PREFS, Context.MODE_PRIVATE).edit().putInt(SWEEP, SWEPT).apply()
                if (e != null) Log.w(TAG, "art: ${e.toString().replace(SECRET, "$1=x")}")
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
                Log.w(TAG, "art fetch $name: ${e.toString().replace(SECRET, "$1=x")}")
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
        Scrobbles.unlinked()
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

    override fun transport(cmd: Int, seekMs: Long?, on: Boolean?, repeat: Int?) {
        if (preRefused(device.transportRefusal(cmd))) return
        val refusable = cmd != Enums.TransportCmd.pause && cmd != Enums.TransportCmd.repeat
        send(Msg.TRANSPORT, Req.transport(cmd, seekMs, on, repeat), "transport ${Enums.TransportCmd.names[cmd]}", done = if (refusable) ::playRefused else ::refused)
    }

    override fun volume(detents: Int) = send(Msg.VOLUME, Req.volume(step = detents), "volume step $detents")

    override fun volumePercent(pct: Int, done: () -> Unit) =
        send(Msg.VOLUME, Req.volume(percent = pct), "volume percent $pct", ok = { done() }, fail = done)

    fun lrcPut(track: Long, libRev: Long?, snapRev: Long?, text: ByteArray, crc: Long, synced: Boolean, source: String, done: (Int?) -> Unit) {
        val s = link?.session ?: return done(-1)
        s.request(Msg.LRC_PUT, Req.lrcPut(track, libRev, text, crc, snapRev)) { r ->
            if (r is Session.Result.Ok) {
                dapLyrics.dropped(track)
                offset?.replaced(track)
            }
            Log.i(TAG, "lrc put ${java.lang.Long.toUnsignedString(track)} ${text.size} ${if (synced) "synced" else "plain"} $source -> ${if (r is Session.Result.Failed) "error ${r.error.code}" else r.javaClass.simpleName.lowercase()}")
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
        }, done = ::playRefused)
    }

    override fun queuePage(offset: Long) {
        val key = "queue $offset ${device.queue.rev} ${device.card.epoch}"
        if (!once(key)) return
        send(Msg.QUEUE_OP, Req.queueOp(Enums.QueueCmd.page, offset = offset, count = LIB_PAGE), key, fail = { forget(key) })
    }

    override fun queueOp(cmd: Int, slot: Long?, to: Long?, tracks: List<Long>) {
        val rev = device.queue.rev ?: device.playback.state.queueRev
        send(Msg.QUEUE_OP, Req.queueOp(cmd, rev, slot = slot, to = to, tracks = tracks), "queue ${Enums.QueueCmd.names[cmd]} slot=$slot to=$to", done = if (cmd == Enums.QueueCmd.playAt || cmd == Enums.QueueCmd.playNext) ::playRefused else ::refused)
    }

    override fun queueAdd(cmd: Int, view: Int?, parent: String?, tracks: List<Long>, done: (Long?, Int?) -> Unit) {
        val lib = device.lib.takeIf { tracks.isEmpty() }
        val rev = if (cmd == Enums.QueueCmd.add) null else device.queue.rev ?: device.playback.state.queueRev
        var added: Long? = null
        send(
            Msg.QUEUE_OP,
            Req.queueOp(cmd, rev, tracks = tracks, view = view.takeIf { lib != null }, parent = parent.takeIf { lib != null }, libRev = lib?.libRev(view), snapRev = lib?.snapRev(view)),
            "queue ${Enums.QueueCmd.names[cmd]} view=$view parent=$parent tracks=${tracks.size}",
            ok = { body ->
                val r = QueueOpReply.from(body)
                added = r.added
                r.rev?.let { rev -> update { it.withQueueRev(rev) } }
            },
            done = { code, _ -> main.post { done(added, code) } },
        )
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

    override fun action(cmd: Int, track: Long?, done: (Int?, String?) -> Unit) {
        if (cmd == Enums.ActionCmd.rescan && preRefused(CardView.REFUSED.takeIf { device.cardDown })) return done(Err.noCard, CardView.REFUSED)
        send(Msg.ACTION, Req.action(cmd, track), "action ${Enums.ActionCmd.names[cmd]}", done = { code, text ->
            if (cmd == Enums.ActionCmd.shuffleLibrary || cmd == Enums.ActionCmd.rescan) playRefused(code, text) else refused(code, text)
            done(code, text)
        })
    }

    private fun preRefused(line: String?): Boolean {
        line ?: return false
        Log.i(TAG, "refused before send, $line")
        main.post { refusalToast.get()?.invoke(line, "") }
        return true
    }

    override fun refusals(show: ((String, String) -> Unit)?, owner: ((String, String) -> Unit)?) {
        if (owner == null) refusalToast.set(show) else refusalToast.compareAndSet(owner, show)
    }

    private fun playRefused(code: Int?, text: String?) {
        if (code == Err.noCard) main.post { refusalToast.get()?.invoke(CardView.REFUSED, "") }
        refused(code, text)
    }

    private fun refused(code: Int?, text: String?) {
        val line = text?.takeIf { code == Err.refused && it in REFUSALS } ?: return
        main.post { refusalToast.get()?.invoke(line, "") }
    }

    override fun favorite(s: State, toast: (String, String) -> Unit) {
        if (!Heart.shown(device.hello, s)) return
        heartToast = toast
        val t = heart.tap(s) ?: return
        action(Enums.ActionCmd.favorite, t.track) { code, text ->
            heartShow(heart.replied(t, code, text, SystemClock.elapsedRealtime(), device.hello?.minor ?: 0))
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
            backdropSeen(hello)
            synchronized(asked) { asked.clear() }
            bye = null
            everLive = true
            board = hello.board
            Scrobbles.session(hello.has("stateClock"))
            link?.session?.let { Scrobbles.linked(it, hello.has("playLog")) }
            Firmwares.hello(hello.board, hello.version)
            Lyrics.session()
            dapLyrics.session()
            if (synchronized(clock) { clock.hello(hello) }) sendClock("hello")
            phase(Phase.LIVE)
            phoneLyrics.retry()
            wake?.drop(ATTEMPT)
            Log.i(TAG, "hello board=${hello.board} version=${hello.version} idle=${hello.idleMs} busy=${hello.busyMs}")
        }

        override fun onPush(type: Int, body: Node) {
            if (!live) return
            val edit = offset
            val mark = if (type == Msg.STATE) edit?.mark() else null
            val now = SystemClock.elapsedRealtime()
            var old = device.playback.state
            var epoch = device.card.epoch
            val pushed = if (type == Msg.STATE) State.from(body) else null
            if (pushed?.source == Enums.Source.phone && old.source != Enums.Source.phone) phoneLyrics.mode = lyricsMode()
            update {
                old = it.playback.state
                epoch = it.card.epoch
                it.reduce(type, body, now)
            }
            if (type == Msg.STATE || type == Msg.QUEUE) Lyrics.poke()
            if (type == Msg.LIB || type == Msg.QUEUE) synchronized(asked) { asked.removeAll { k -> k.startsWith(if (type == Msg.LIB) "lib " else "queue ") } }
            if (type != Msg.STATE) return
            dapLyrics.poke()
            val s = pushed!!
            edit?.state(s, mark)
            phoneCover.state(s)
            pending?.take(Forward.phoneArtists(s))
            (s.snapRev ?: s.libRev)?.let { r -> if (r != (old.snapRev ?: old.libRev)) thumbs?.rev(r) }
            heartShow(heart.state(s))
            Scrobbles.state(s, System.currentTimeMillis(), device.card)
            Firmwares.state()
            onState?.invoke()
            Log.i(TAG, "state applied title=${s.title} artist=${s.artist} playing=${s.playing} volume=${s.volume}/${s.maxVolume} battery=${s.battery} charging=${s.charging} card=${s.cardFreeMiB}/${s.cardTotalMiB} pos=${s.positionMs} sent=${s.sentMs} sample=${s.sampleMs} rtt=${device.rttMs} at=$now lateMs=${device.playback.lateMs} held=${s.positionHeld}")
            if (old.title != null && old.title != s.title) Log.i(TAG, "title changed ${old.title} -> ${s.title}")
            val key = PushGate.artKey(device)
            val ready = device.card.epoch != epoch
            if (ready) {
                Log.i(TAG, "card ready edge ${device.card.epoch}")
                thumbs?.cardReady()
            }
            if (key != artKey || ready) {
                artKey = key
                trigger.incrementAndGet()
                art?.want()
            }
            if (old.playing == true && s.playing != true && device.has("imgSource")) main.postDelayed({ art?.want() }, Proto.imgRefetchIdleMs + REFETCH_SLACK_MS)
        }

        override fun onRefused(code: Int, abort: Boolean) {
            if (!live || code != if (abort) Enums.AbortCode.noCard else Err.noCard) return
            update { it.copy(card = it.card.refused()) }
            Log.i(TAG, "card refused ${if (abort) "abort" else "error"} $code")
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
            offset?.end(false)
            phoneCover.clear()
            update { it.copy(hello = null) }
            dapLyrics.poke()
            heart.down()
            art?.cancel()
            clockOff()
            Lyrics.session()
            Scrobbles.unlinked()
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
