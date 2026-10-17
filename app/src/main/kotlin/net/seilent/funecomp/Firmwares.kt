package net.seilent.funecomp

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.seilent.funecomp.artist.Firmware
import net.seilent.funecomp.artist.FirmwareRefused
import net.seilent.funecomp.artist.HttpRun
import net.seilent.funecomp.artist.Release
import net.seilent.funecomp.artist.UrlHttp
import net.seilent.funecomp.ui.FirmwareView
import net.seilent.funecomp.ui.PickedFile
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

object Firmwares {
    private const val PREFS = "firmware"
    private const val DAILY = "checkDaily"
    private const val PRE = "prereleases"
    private const val LAST = "lastCheck"
    private const val DAY_MS = 24L * 3600 * 1000
    private val exec = Executors.newSingleThreadExecutor { Thread(it, "firmware").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var firmware: Firmware? = null
    private var release: Release? = null
    private var run: HttpRun? = null
    private var board: String? = null
    private var version: String? = null

    var view by mutableStateOf(FirmwareView())
        private set

    fun init(c: Context) {
        if (context != null) return
        val app = c.applicationContext
        context = app
        val v = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0"
        firmware = Firmware(File(app.filesDir, "firmware"), UrlHttp(timeoutMs = 30_000), v)
        view = FirmwareView(checkDaily = prefs(app).getBoolean(DAILY, false), prereleases = prefs(app).getBoolean(PRE, false))
    }

    fun hello(board: String?, version: String?) {
        this.board = board
        this.version = version
        val c = context ?: return
        val p = prefs(c)
        if (p.getBoolean(DAILY, false) && System.currentTimeMillis() - p.getLong(LAST, 0) >= DAY_MS) check()
    }

    fun check() = exec.execute {
        val c = context ?: return@execute
        val f = firmware ?: return@execute
        val b = board
        view = view.copy(status = "checking", info = "")
        val v = try {
            prefs(c).edit().putLong(LAST, System.currentTimeMillis()).apply()
            when (val r = f.check(b, version, prefs(c).getBoolean(PRE, false))) {
                is Firmware.Check.Found -> {
                    release = r.release
                    val ready = f.file(r.release).let { it.isFile && it.length() == r.release.asset.size }
                    view.copy(status = "", info = if (r.newer == null) "device runs a dev build" else "", tag = r.release.tag, size = r.release.asset.size, newer = r.newer, ready = ready, download = "")
                }
                Firmware.Check.NoAsset -> view.copy(status = "", info = if (b == null) "not connected" else "no release for $b", tag = null)
                Firmware.Check.NoPublic -> view.copy(status = "", info = "no public releases", tag = null)
                Firmware.Check.RateLimited -> view.copy(status = "", info = "rate limited, try later")
            }
        } catch (e: IOException) {
            Log.w(Remote.TAG, "firmware check: $e")
            view.copy(status = "offline")
        }
        view = v
    }

    fun download() {
        val rel = release ?: return
        if (view.downloading) return
        val r = HttpRun()
        run = r
        view = view.copy(downloading = true, download = percent(firmware?.downloaded(rel) ?: 0, rel.asset.size), info = "")
        exec.execute {
            val f = firmware ?: return@execute
            val v = try {
                r.on {
                    f.download(rel) { have, size ->
                        val t = percent(have, size)
                        if (t != view.download) view = view.copy(download = t)
                    }
                }
                view.copy(downloading = false, ready = true, download = "")
            } catch (e: FirmwareRefused) {
                view.copy(downloading = false, download = "", info = e.message ?: "")
            } catch (e: IOException) {
                Log.w(Remote.TAG, "firmware download: $e")
                if (r.cancelled) {
                    f.clear(rel)
                    view.copy(downloading = false, download = "", ready = false)
                } else {
                    view.copy(downloading = false, download = "paused ${percent(f.downloaded(rel), rel.asset.size)}")
                }
            }
            if (run === r) run = null
            view = v
        }
    }

    fun cancel() {
        val r = run
        if (r != null) {
            r.cancel()
            return
        }
        val rel = release ?: return
        exec.execute {
            firmware?.clear(rel)
            view = view.copy(ready = false, download = "")
        }
    }

    fun settings(checkDaily: Boolean, prereleases: Boolean) {
        val c = context ?: return
        prefs(c).edit().putBoolean(DAILY, checkDaily).putBoolean(PRE, prereleases).apply()
        view = view.copy(checkDaily = checkDaily, prereleases = prereleases)
    }

    fun read(uri: Uri, done: (PickedFile) -> Unit) {
        val c = context ?: return
        exec.execute {
            val (name, size) = try {
                c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                    if (it.moveToFirst()) (it.getString(0) ?: "") to (if (it.isNull(1)) -1L else it.getLong(1)) else null
                }
            } catch (e: RuntimeException) {
                null
            } ?: ((uri.lastPathSegment?.substringAfterLast('/') ?: "") to -1L)
            val p = PickedFile(name, size, board)
            main.post { done(p) }
        }
    }

    private fun percent(have: Long, size: Long) = "${if (size > 0) (have * 100 / size).coerceIn(0, 100) else 0} %"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
