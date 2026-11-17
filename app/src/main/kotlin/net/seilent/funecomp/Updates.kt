package net.seilent.funecomp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import net.seilent.funecomp.push.Firmware
import net.seilent.funecomp.push.FirmwareRefused
import net.seilent.funecomp.push.Release
import net.seilent.funecomp.push.Releases
import net.seilent.funecomp.ui.UpdateView
import net.seilent.funekit.artist.Http
import net.seilent.funekit.artist.HttpRun
import net.seilent.funekit.artist.UrlHttp
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

class UpdateStatus : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Updates.status(intent)
}

object Updates {
    internal const val LAST = "updateCheck"
    private const val NOTIFIED = "updateNotified"
    private const val DAY_MS = 24L * 3600 * 1000
    private const val DIR = "update"
    private const val APK = "funecomp.apk"
    private const val REQUEST = 1
    const val ACTION_STATUS = "net.seilent.funecomp.UPDATE_STATUS"
    const val ALLOW = "allow install unknown apps"
    private val exec = Executors.newSingleThreadExecutor { Thread(it, "update").apply { isDaemon = true } }
    private var context: Context? = null
    private var installed = ""
    @Volatile private var feed: Firmware? = null
    @Volatile private var release: Release? = null
    @Volatile private var failed = false
    private var run: HttpRun? = null
    @Volatile private var session = -1
    private var confirm: Intent? = null
    private var asked = false
    var front: ((Intent) -> Unit)? = null

    var view by mutableStateOf(UpdateView())
        private set

    private fun set(f: (UpdateView) -> UpdateView) = synchronized(this) { view = f(view) }

    private fun upd(f: (UpdateView) -> UpdateView) = f

    fun init(c: Context, http: Http? = null, installed: String = BuildConfig.VERSION_NAME) {
        val app = c.applicationContext
        if (context === app && http == null) return
        context = app
        this.installed = installed
        feed = Firmware(File(app.cacheDir, DIR), http ?: UrlHttp(timeoutMs = 30_000), installed, Firmware.APP_URL)
        release = null
        failed = false
        run = null
        session = -1
        confirm = null
        asked = false
        view = UpdateView(installed = installed)
        exec.execute {
            val tag = prefs(app).getString(NOTIFIED, null) ?: return@execute
            if (Releases.newer(tag, Releases.appVersion(installed)) == false) UpdateNotice.clear(app)
        }
    }

    internal fun idle() {
        exec.submit {}.get()
    }

    fun due(chance: Boolean = false) {
        val c = context ?: return
        val f = feed ?: return
        exec.execute {
            if (chance) failed = false
            val now = System.currentTimeMillis()
            val last = prefs(c).getLong(LAST, 0)
            if (f !== feed || failed || view.busy || (now >= last && now - last < DAY_MS)) return@execute
            check(c, f, background = true)
        }
    }

    fun check() {
        val c = context ?: return
        val f = feed ?: return
        exec.execute { if (f === feed && !view.busy) check(c, f, background = false) }
    }

    private fun check(c: Context, f: Firmware, background: Boolean) {
        if (f !== feed) return
        set { it.copy(status = "checking", info = "") }
        val r = try {
            f.app(installed)
        } catch (e: Exception) {
            Log.w(Remote.TAG, "update check: $e")
            null
        }
        if (f !== feed) return
        failed = r == null || r == Firmware.Check.RateLimited
        if (!failed) prefs(c).edit().putLong(LAST, System.currentTimeMillis()).apply()
        if (r is Firmware.Check.Found) {
            release = r.release
            if (r.newer != true) UpdateNotice.clear(c) else if (background) notice(c, r.release.tag)
        } else if (!failed) {
            release = null
        }
        set {
            when (r) {
                null -> it.copy(status = "offline")
                is Firmware.Check.Found -> it.copy(status = "", tag = r.release.tag, size = r.release.asset.size, newer = r.newer)
                Firmware.Check.RateLimited -> it.copy(status = "", info = "rate limited, try later")
                Firmware.Check.NoPublic -> it.copy(status = "", info = "no public releases", tag = null, newer = null)
                else -> it.copy(status = "", info = "no app release", tag = null, newer = null)
            }
        }
    }

    private fun notice(c: Context, tag: String) {
        val p = prefs(c)
        if (p.getString(NOTIFIED, null) == tag) return
        if (UpdateNotice.post(c, tag)) p.edit().putString(NOTIFIED, tag).apply()
    }

    fun allow() = set { it.copy(info = ALLOW) }

    fun install() {
        val c = context ?: return
        val f = feed ?: return
        val rel = release ?: return
        if (view.busy || view.installing) return
        val r = HttpRun()
        run = r
        set { it.copy(downloading = true, download = Firmwares.percent(0, rel.asset.size), info = "") }
        exec.execute {
            val v: (UpdateView) -> UpdateView = try {
                File(c.cacheDir, DIR).listFiles()?.forEach { if (it.isDirectory && it.name != rel.tag) it.deleteRecursively() }
                val apk = r.on {
                    f.download(rel) { have, size ->
                        val t = Firmwares.percent(have, size)
                        if (t != view.download) set { it.copy(download = t) }
                    }
                }
                if (r.cancelled) throw IOException("cancelled")
                set { it.copy(downloading = false, download = "", verifying = true) }
                val why = refusal(archive(c, apk), mine(c)) ?: commit(c, apk)
                upd { it.copy(verifying = false, installing = why == null, info = why ?: "") }
            } catch (e: FirmwareRefused) {
                upd { it.copy(downloading = false, download = "", verifying = false, info = e.message ?: "download failed") }
            } catch (e: Exception) {
                Log.w(Remote.TAG, "update download: $e")
                upd { it.copy(downloading = false, download = "", verifying = false, info = if (r.cancelled) "" else "download failed") }
            } finally {
                f.clear(rel)
            }
            if (run === r) run = null
            if (f === feed) set(v)
        }
    }

    fun cancel() {
        run?.cancel()
    }

    private fun archive(c: Context, apk: File): PackageInfo? = c.packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)

    private fun mine(c: Context): PackageInfo? = c.packageManager.getPackageInfo(c.packageName, PackageManager.GET_SIGNING_CERTIFICATES)

    private fun signers(p: PackageInfo?): List<String>? {
        val s = p?.signingInfo ?: return null
        if (s.hasMultipleSigners()) return null
        return s.signingCertificateHistory?.map { Firmware.sha256(it.toByteArray()) }?.takeIf { it.isNotEmpty() }
    }

    internal fun refusal(apk: PackageInfo?, mine: PackageInfo?): String? {
        if (apk == null) return Apps.DAMAGED
        if (mine == null || apk.packageName != mine.packageName) return "wrong package"
        val have = signers(apk)
        if (have == null || have != signers(mine)) return "unknown signing key"
        return if (apk.longVersionCode <= mine.longVersionCode) Apps.OLDER else null
    }

    private fun commit(c: Context, apk: File): String? {
        val pi = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(c.packageName)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        var id = -1
        return try {
            pi.mySessions.forEach { runCatching { pi.abandonSession(it.sessionId) } }
            id = pi.createSession(params)
            session = id
            pi.openSession(id).use { s ->
                s.openWrite(APK, 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    s.fsync(out)
                }
                val done = Intent(c, UpdateStatus::class.java).setAction(ACTION_STATUS)
                s.commit(PendingIntent.getBroadcast(c, REQUEST, done, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT).intentSender)
            }
            null
        } catch (e: Exception) {
            Log.w(Remote.TAG, "update install: $e")
            if (id >= 0) runCatching { pi.abandonSession(id) }
            session = -1
            "install failed"
        }
    }

    fun status(i: Intent) {
        if (i.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != session) return
        asked = false
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val ask = IntentCompat.getParcelableExtra(i, Intent.EXTRA_INTENT, Intent::class.java)
                if (ask == null) {
                    set { it.copy(installing = false, info = "install failed") }
                } else {
                    confirm = ask
                    show()
                }
            }
            PackageInstaller.STATUS_SUCCESS -> set { it.copy(installing = false) }
            PackageInstaller.STATUS_FAILURE_ABORTED -> set { it.copy(installing = false, info = "install cancelled") }
            else -> {
                Log.w(Remote.TAG, "update install: ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                set { it.copy(installing = false, info = "install failed") }
            }
        }
    }

    fun resumed(canInstall: Boolean) {
        if (canInstall && view.info == ALLOW) set { it.copy(info = "") }
        if (asked) set { it.copy(installing = false) }
        asked = false
        show()
    }

    private fun show() {
        val f = front ?: return
        val i = confirm ?: return
        confirm = null
        asked = true
        f(i)
    }

    private fun prefs(c: Context) = c.getSharedPreferences(Main.DEVICE_PREFS, Context.MODE_PRIVATE)
}
