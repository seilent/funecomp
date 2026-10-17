package net.seilent.funecomp

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.seilent.funecomp.artist.Accounts
import net.seilent.funecomp.artist.LastFm
import net.seilent.funecomp.artist.Listen
import net.seilent.funecomp.artist.Scrobbler
import net.seilent.funecomp.artist.UrlHttp
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.state.Play
import net.seilent.funecomp.state.Plays
import net.seilent.funecomp.ui.ScrobbleView
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

object Scrobbles {
    private const val PREFS = "scrobble"
    private const val OPEN = "open.tsv"
    private val exec = Executors.newSingleThreadExecutor { Thread(it, "scrobble").apply { isDaemon = true } }
    private var context: Context? = null
    private var scrobbler: Scrobbler? = null
    private var plays: Plays? = null
    private var dir: File? = null

    var view by mutableStateOf(ScrobbleView())
        private set

    fun init(c: Context) {
        if (context != null) return
        val app = c.applicationContext
        context = app
        val version = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0"
        val d = File(app.filesDir, "scrobble")
        dir = d
        view = ScrobbleView(accounts(app))
        exec.execute {
            val s = Scrobbler(d, UrlHttp(), { accounts(app) }, version, reauth = ::expired) { Log.i(Remote.TAG, it) }
            scrobbler = s
            plays = Plays(File(d, OPEN).takeIf { it.isFile }?.readText()?.let { Play.parse(it) })
            refresh()
            flush()
        }
    }

    fun session() = exec.execute { plays?.session() }

    fun state(s: State, wallMs: Long) = exec.execute {
        val p = plays ?: return@execute
        val before = p.open
        val events = p.frame(s, wallMs)
        if (p.open != before) p.open?.let { save(it) }
        for (e in events) {
            val l = listen(e) ?: continue
            when (e) {
                is Plays.Event.NowPlaying -> attempt { scrobbler?.nowPlaying(l) }
                is Plays.Event.Scrobble -> attempt { scrobbler?.add(l) }
            }
        }
        if (events.isNotEmpty()) refresh()
    }

    fun flush() = exec.execute {
        attempt { scrobbler?.flush() }
        refresh()
    }

    fun set(a: Accounts) {
        val c = context ?: return
        val old = accounts(c)
        store(c, if (a.apiKey != old.apiKey || a.secret != old.secret) a.copy(lastFmUser = "", lastFmSession = "") else a)
        exec.execute {
            if (a.lastFm && !old.lastFm) scrobbler?.enable(Accounts.LAST_FM)
            if (a.listenBrainz && !old.listenBrainz) scrobbler?.enable(Accounts.LISTEN_BRAINZ)
            attempt { scrobbler?.flush() }
            refresh()
        }
    }

    fun lastFmLogin(user: String, password: String) {
        val c = context ?: return
        view = view.copy(lastFm = "signing in")
        exec.execute {
            val a = accounts(c)
            val status = try {
                val (name, key) = scrobbler?.lastFmSession(a.apiKey, a.secret, user, password) ?: return@execute
                store(c, accounts(c).copy(lastFmUser = name, lastFmSession = key))
                attempt { scrobbler?.flush() }
                ""
            } catch (e: LastFm.Failed) {
                Log.w(Remote.TAG, "lastfm session: ${e.code} ${e.message}")
                "sign in failed"
            } catch (e: IOException) {
                Log.w(Remote.TAG, "lastfm session: $e")
                "offline"
            }
            refresh(lastFm = status)
        }
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
                    attempt { scrobbler?.flush() }
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
        store(c, if (service == Accounts.LAST_FM) a.copy(lastFmSession = "") else a.copy(token = ""))
        Log.w(Remote.TAG, "scrobble $service credentials refused")
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

    private fun refresh(lastFm: String? = null, listenBrainz: String? = null) {
        val c = context ?: return
        val queued = scrobbler?.pending() ?: 0
        val v = view
        view = ScrobbleView(accounts(c), queued, lastFm ?: v.lastFm, listenBrainz ?: v.listenBrainz)
    }

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
