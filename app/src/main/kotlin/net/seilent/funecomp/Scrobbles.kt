package net.seilent.funecomp

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.seilent.funecomp.artist.Accounts
import net.seilent.funecomp.artist.AppPair
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.LastFm
import net.seilent.funecomp.artist.Listen
import net.seilent.funecomp.artist.ListenBrainz
import net.seilent.funecomp.artist.Scrobbler
import net.seilent.funecomp.artist.Sent
import net.seilent.funecomp.artist.SignIn
import net.seilent.funecomp.artist.UrlHttp
import net.seilent.funecomp.link.Session
import net.seilent.funecomp.proto.PlayLogReply
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.state.Play
import net.seilent.funecomp.state.CardView
import net.seilent.funecomp.state.PlayLogSync
import net.seilent.funecomp.state.Plays
import net.seilent.funecomp.ui.ScrobbleView
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Capture(val base: String, val wrap: (Http) -> Http)

object Scrobbles {
    private const val PREFS = "scrobble"
    private const val OPEN = "open.tsv"
    private const val DRAIN_S = 2L
    private const val PENDING = "pendingToken"
    private const val PENDING_AT = "pendingAt"
    private const val PENDING_MS = 60 * 60 * 1000L
    const val NO_BROWSER = "no browser"
    const val SIGN_IN_AGAIN = "sign in again"
    const val LISTEN_BRAINZ_TOKEN_URL = "https://listenbrainz.org/settings/"
    @Volatile private var exec = executor()
    private var context: Context? = null
    private var pair = AppPair.EMPTY
    private var scrobbler: Scrobbler? = null
    private var plays: Plays? = null
    private var dir: File? = null
    private val lock = Any()
    private var starting = false
    private var queuedFor: String? = null
    private var runningFor: String? = null
    private var rerun = false
    private val keyRefused = HashSet<String>()
    @Volatile private var lastFmError = ""
    @Volatile private var pendingError: Pair<String, String>? = null
    @Volatile private var tokenPage = ""
    internal var now: () -> Long = { System.currentTimeMillis() }
    internal var zone: () -> ZoneId = { ZoneId.systemDefault() }
    private var sync = playLogSync()
    @Volatile private var link: Session? = null
    private var card = CardView()

    @Volatile var opener: ((String) -> Boolean)? = null

    @Volatile var capture: Capture? = null

    var view by mutableStateOf(ScrobbleView())
        private set

    fun init(c: Context, pair: AppPair, http: Http? = null, sync: (File) -> Unit = ::syncDir) {
        if (context != null && http == null) return
        val app = c.applicationContext
        context = app
        this.pair = pair
        val version = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0"
        val d = File(app.filesDir, "scrobble")
        dir = d
        view = ScrobbleView(accounts(app), app = pair, pending = pending(app) != null)
        val cap = capture
        val net = (http ?: UrlHttp()).let { cap?.wrap?.invoke(it) ?: it }
        exec.execute {
            val s = Scrobbler(
                d, net, { accounts(app) }, pair, version, reauth = ::expired, dirSync = sync,
                lastFmBase = cap?.let { "${it.base}/2.0/" } ?: LastFm.BASE, listenBrainzBase = cap?.base ?: ListenBrainz.BASE,
            ) { Log.i(Remote.TAG, it) }
            scrobbler = s
            plays = Plays(File(d, OPEN).takeIf { it.isFile }?.readText()?.let { Play.parse(it) })
            refresh()
            flush()
        }
    }

    internal fun reset() {
        exec.shutdownNow()
        exec.awaitTermination(DRAIN_S, TimeUnit.SECONDS)
        exec = executor()
        context = null
        pair = AppPair.EMPTY
        scrobbler = null
        plays = null
        dir = null
        synchronized(lock) {
            starting = false
            queuedFor = null
            runningFor = null
            rerun = false
        }
        keyRefused.clear()
        lastFmError = ""
        pendingError = null
        tokenPage = ""
        opener = null
        capture = null
        now = { System.currentTimeMillis() }
        zone = { ZoneId.systemDefault() }
        link = null
        sync = playLogSync()
        view = ScrobbleView()
    }

    private fun playLogSync() = PlayLogSync({ scrobbler?.queue?.dap(it) }) { Log.i(Remote.TAG, it) }

    internal fun idle() {
        exec.submit {}.get(DRAIN_S, TimeUnit.SECONDS)
    }

    fun session(clocked: Boolean) = exec.execute { plays?.session(clocked) }

    fun linked(s: Session, playLog: Boolean) {
        exec.execute {
            link = s
            card = CardView()
            val c = context ?: return@execute
            perform(sync.hello(playLog, accounts(c).enabled.isNotEmpty()))
        }
    }

    fun unlinked() = exec.execute {
        link = null
        sync.closed()
    }

    private fun perform(d: PlayLogSync.Do?) {
        val s = link ?: return
        when (d) {
            null -> {}
            is PlayLogSync.Get -> {
                val epoch = card.epoch
                s.request(Msg.PLAYLOG_GET, Req.playLogGet(d.after, d.count)) { r ->
                    exec.execute {
                        when (r) {
                            is Session.Result.Ok -> {
                                val reply = try {
                                    PlayLogReply.from(r.body)
                                } catch (e: RuntimeException) {
                                    Log.w(Remote.TAG, "playlog reply: $e")
                                    null
                                }
                                if (reply == null) sync.getFailed(d.gen, null, epoch) else perform(sync.reply(d.gen, reply))
                            }
                            is Session.Result.Failed -> sync.getFailed(d.gen, r.error.code, epoch)
                            else -> sync.getFailed(d.gen, null, epoch)
                        }
                    }
                }
            }
            is PlayLogSync.Store -> {
                val upTo = try {
                    scrobbler?.addLog(d.log, d.entries, zone())
                } catch (e: IOException) {
                    Log.w(Remote.TAG, "playlog store: $e")
                    null
                } catch (e: RuntimeException) {
                    Log.w(Remote.TAG, "playlog store: $e")
                    null
                }
                perform(sync.stored(d.gen, d.log, upTo, d.more))
                if (!d.more) attempt { note(scrobbler?.flush()) }
                refresh()
            }
            is PlayLogSync.Ack -> s.request(Msg.PLAYLOG_ACK, Req.playLogAck(d.log, d.upTo)) { r ->
                Log.i(Remote.TAG, "playlog ack ${java.lang.Long.toHexString(d.log)} up to ${d.upTo} -> ${if (r is Session.Result.Failed) "error ${r.error.code}" else r.javaClass.simpleName.lowercase()}")
                exec.execute { perform(sync.acked(d.gen, d, (r as? Session.Result.Failed)?.error?.code, r is Session.Result.Ok || r is Session.Result.Failed)) }
            }
        }
    }

    fun state(s: State, wallMs: Long, card: CardView = CardView()) = exec.execute {
        this.card = card
        perform(sync.state(s.playLogSeq, card))
        val p = plays ?: return@execute
        val before = p.open
        val events = p.frame(s, wallMs)
        if (p.open != before) p.open?.let { save(it) }
        for (e in events) {
            val l = listen(e) ?: continue
            when (e) {
                is Plays.Event.NowPlaying -> attempt { scrobbler?.nowPlaying(l) }
                is Plays.Event.Scrobble -> if (!sync.logged) attempt { note(scrobbler?.add(l)) }
            }
        }
        if (events.isNotEmpty()) refresh()
    }

    fun flush() = exec.execute {
        attempt { note(scrobbler?.flush()) }
        refresh()
    }

    fun set(a: Accounts) {
        val c = context ?: return
        val old = accounts(c)
        prefs(c).edit().putBoolean("lastfm", a.lastFm).putBoolean("listenbrainz", a.listenBrainz).commit()
        view = view.copy(accounts = accounts(c))
        exec.execute {
            if (a.lastFm && !old.lastFm) attempt { scrobbler?.enable(Accounts.LAST_FM) }
            if (a.listenBrainz && !old.listenBrainz) attempt { scrobbler?.enable(Accounts.LISTEN_BRAINZ) }
            attempt { note(scrobbler?.flush()) }
            refresh()
            perform(sync.services(a.enabled.isNotEmpty(), (a.lastFm && !old.lastFm) || (a.listenBrainz && !old.listenBrainz)))
        }
    }

    fun lastFmStart() {
        val c = context ?: return
        if (pair.empty) return
        pending(c)?.let { t ->
            open(c, t)
            return
        }
        synchronized(lock) {
            if (starting) return
            starting = true
        }
        exec.execute {
            var posted = false
            try {
                val t = try {
                    if (accounts(c).lastFmSession.isNotBlank()) null else token(c)
                } catch (e: Exception) {
                    Log.w(Remote.TAG, "lastfm token: ${code(e)}")
                    lastFmError = signIn(e, false).row
                    refresh()
                    null
                } ?: return@execute
                posted = Handler(Looper.getMainLooper()).post {
                    synchronized(lock) { starting = false }
                    if (opener == null) return@post
                    prefs(c).edit().putString(PENDING, t).putLong(PENDING_AT, now()).commit()
                    lastFmError = ""
                    pendingError = null
                    open(c, t)
                }
            } finally {
                if (!posted) synchronized(lock) { starting = false }
            }
        }
    }

    private fun token(c: Context): String? {
        val a = accounts(c)
        if (a.apiKey.isNotEmpty() || a.secret.isNotEmpty()) store(c, a.copy(apiKey = "", secret = ""))
        return scrobbler?.lastFmToken()
    }

    private fun open(c: Context, t: String) {
        val o = opener ?: return
        val url = Uri.Builder().scheme("https").authority("www.last.fm").path("/api/auth/")
            .appendQueryParameter("api_key", pair.key).appendQueryParameter("token", t).build().toString()
        if (!o(url)) {
            dropPending(c)
            lastFmError = NO_BROWSER
        }
        exec.execute { refresh() }
    }

    fun resumed() {
        val c = context ?: return
        val t = pending(c)
        if (t != null) lastFmExchange(t) else exec.execute { refresh() }
    }

    fun lastFmExchange(expected: String) {
        synchronized(lock) {
            if (queuedFor == expected) return
            if (runningFor == expected) {
                rerun = true
                return
            }
            queuedFor = expected
        }
        exec.execute { exchange(expected) }
    }

    private fun exchange(expected: String) {
        synchronized(lock) {
            queuedFor = null
            runningFor = expected
            rerun = false
        }
        val c = context
        try {
            if (c == null || pending(c) != expected) return
            val (name, key) = try {
                scrobbler?.lastFmSession(expected) ?: return
            } catch (e: Exception) {
                Log.w(Remote.TAG, "lastfm session: ${code(e)}")
                val r = signIn(e, true)
                if (pending(c) == expected) {
                    if (r.keeps) {
                        pendingError = expected to r.row
                    } else {
                        dropPending(c)
                        lastFmError = r.row
                    }
                }
                return
            }
            store(c, accounts(c).copy(lastFmUser = name, lastFmSession = key))
            dropPending(c)
            keyRefused -= Accounts.LAST_FM
            lastFmError = ""
            attempt { note(scrobbler?.flush()) }
        } finally {
            val again = synchronized(lock) {
                runningFor = null
                rerun.also { rerun = false }
            }
            refresh()
            if (again && c != null && pending(c) == expected) lastFmExchange(expected)
        }
    }

    fun signOut(service: String) {
        val c = context ?: return
        exec.execute {
            val e = prefs(c).edit()
            if (service == Accounts.LAST_FM) {
                listOf("lastfmUser", "lastfmSession", "apiKey", "secret", PENDING, PENDING_AT).forEach { e.remove(it) }
                lastFmError = ""
                pendingError = null
            } else {
                listOf("token", "listenbrainzUser").forEach { e.remove(it) }
            }
            e.commit()
            keyRefused -= service
            refresh(listenBrainz = if (service == Accounts.LISTEN_BRAINZ) "" else null)
        }
    }

    fun listenBrainzOpen() {
        val o = opener ?: return
        tokenPage = if (o(LISTEN_BRAINZ_TOKEN_URL)) "" else NO_BROWSER
        exec.execute { refresh() }
    }

    fun listenBrainzToken(token: String) {
        val c = context ?: return
        view = view.copy(listenBrainz = "checking")
        exec.execute {
            val status = try {
                val user = scrobbler?.listenBrainzUser(token)
                if (user == null) {
                    "invalid"
                } else {
                    store(c, accounts(c).copy(token = token.trim(), listenBrainzUser = user))
                    attempt { note(scrobbler?.flush()) }
                    ""
                }
            } catch (e: IOException) {
                Log.w(Remote.TAG, "listenbrainz token: $e")
                "offline"
            }
            refresh(listenBrainz = status)
        }
    }

    private fun expired(service: String) {
        val c = context ?: return
        val a = accounts(c)
        if (service == Accounts.LAST_FM) {
            store(c, a.copy(lastFmSession = "", apiKey = "", secret = ""))
            lastFmError = SIGN_IN_AGAIN
        } else {
            store(c, a.copy(token = ""))
        }
        Log.w(Remote.TAG, "scrobble $service credentials refused")
    }

    private fun note(r: Map<String, Sent>?) {
        for ((service, sent) in r ?: return) {
            if (sent == Sent.KEY) keyRefused += service
            if (sent == Sent.OK) keyRefused -= service
        }
    }

    private fun code(e: Exception) = (e as? LastFm.Failed)?.let { "error ${it.code ?: "none"}" } ?: "io"

    private fun signIn(e: Exception, session: Boolean) = (e as? IOException)?.let { LastFm.signIn(it, session) } ?: SignIn.FAILED

    private fun pending(c: Context): String? {
        val p = prefs(c)
        val t = p.getString(PENDING, null) ?: return null
        val age = now() - p.getLong(PENDING_AT, 0)
        if (age in 0 until PENDING_MS) return t
        dropPending(c)
        return null
    }

    private fun dropPending(c: Context) {
        prefs(c).edit().remove(PENDING).remove(PENDING_AT).commit()
        pendingError = null
    }

    private fun listen(e: Plays.Event): Listen? {
        val s = e.state
        val artist = (s.artist ?: s.artists.joinToString(", ")).trim()
        val title = s.title?.trim().orEmpty()
        if (artist.isEmpty() || title.isEmpty()) return null
        return Listen(e.play.startMs / 1000, artist, title, s.album?.trim()?.ifEmpty { null }, s.durationMs?.let { it / 1000 })
    }

    private fun attempt(block: () -> Unit) {
        try {
            block()
        } catch (e: IOException) {
            Log.w(Remote.TAG, "scrobble: $e")
        } catch (e: RuntimeException) {
            Log.w(Remote.TAG, "scrobble: $e")
        }
    }

    private fun save(p: Play) {
        val f = File(dir ?: return, OPEN)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(p.line() + "\n")
        tmp.renameTo(f)
    }

    private fun refresh(listenBrainz: String? = null) {
        val c = context ?: return
        val queued = try {
            scrobbler?.pending() ?: 0
        } catch (e: IOException) {
            Log.w(Remote.TAG, "scrobble: $e")
            view.queued
        }
        val t = pending(c)
        val lastFm = if (t != null) pendingError?.takeIf { it.first == t }?.second.orEmpty() else lastFmError
        view = ScrobbleView(accounts(c), queued, lastFm, listenBrainz ?: view.listenBrainz, pair, t != null, keyRefused.toSet(), tokenPage)
    }

    private fun syncDir(d: File) {
        try {
            val fd = Os.open(d.path, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
        } catch (e: ErrnoException) {
            throw IOException("fsync ${d.name}", e)
        }
    }

    private fun executor() = Executors.newSingleThreadExecutor { Thread(it, "scrobble").apply { isDaemon = true } }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun accounts(c: Context): Accounts {
        val p = prefs(c)
        return Accounts(
            p.getBoolean("lastfm", false),
            p.getString("apiKey", "") ?: "",
            p.getString("secret", "") ?: "",
            p.getString("lastfmUser", "") ?: "",
            p.getString("lastfmSession", "") ?: "",
            p.getBoolean("listenbrainz", false),
            p.getString("token", "") ?: "",
            p.getString("listenbrainzUser", "") ?: "",
        )
    }

    private fun store(c: Context, a: Accounts) {
        prefs(c).edit()
            .putBoolean("lastfm", a.lastFm)
            .putString("apiKey", a.apiKey)
            .putString("secret", a.secret)
            .putString("lastfmUser", a.lastFmUser)
            .putString("lastfmSession", a.lastFmSession)
            .putBoolean("listenbrainz", a.listenBrainz)
            .putString("token", a.token)
            .putString("listenbrainzUser", a.listenBrainzUser)
            .commit()
        view = view.copy(accounts = a)
    }
}
