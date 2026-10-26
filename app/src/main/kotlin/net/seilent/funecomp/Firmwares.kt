package net.seilent.funecomp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.seilent.funecomp.artist.Firmware
import net.seilent.funecomp.artist.FirmwareRefused
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.HttpRun
import net.seilent.funecomp.artist.Release
import net.seilent.funecomp.artist.UrlHttp
import net.seilent.funecomp.link.UpdPush
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.state.CardView
import net.seilent.funecomp.state.PushGate
import net.seilent.funecomp.ui.FirmwareView
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.PickedFile
import net.seilent.funecomp.ui.UNSIGNED_RELEASE
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

object Firmwares {
    private const val PREFS = "firmware"
    private const val DAILY = "checkDaily"
    private const val PRE = "prereleases"
    private const val LAST = "lastCheck"
    private const val DAY_MS = 24L * 3600 * 1000
    private const val DAP_MIN = 50
    private const val PHONE_MIN = 20
    private const val WAKE = "firmware"
    private const val WAKE_MS = 60_000L
    internal const val BUSY_RETRY_MS = 5_000L
    private val exec = Executors.newSingleThreadExecutor { Thread(it, "firmware").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var firmware: Firmware? = null
    private var release: Release? = null
    private var run: HttpRun? = null
    private var board: String? = null
    private var version: String? = null
    @Volatile private var updSig = false
    @Volatile private var checkedSig: Boolean? = null
    @Volatile private var unsigned = false
    private val sendExec = Executors.newSingleThreadExecutor { Thread(it, "firmware-send").apply { isDaemon = true } }
    private val retry = Executors.newSingleThreadScheduledExecutor { Thread(it, "firmware-retry").apply { isDaemon = true } }
    private var pending: Source? = null
    private var retryNext: ScheduledFuture<*>? = null
    private var pickedUri: Uri? = null
    @Volatile private var stopSend = false
    @Volatile private var busy = false
    @Volatile private var cardWait: Int? = null
    @Volatile var sending = false
        private set

    internal class Source(val name: String, val size: Long, val open: (Long) -> InputStream) {
        var md5: String? = null
    }

    var view by mutableStateOf(FirmwareView())
        private set

    private fun set(f: (FirmwareView) -> FirmwareView) = synchronized(this) { view = f(view) }

    private fun upd(f: (FirmwareView) -> FirmwareView) = f

    fun init(c: Context, http: Http = UrlHttp(timeoutMs = 30_000)) {
        val app = c.applicationContext
        if (context === app) return
        context = app
        val v = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0"
        firmware = Firmware(File(app.filesDir, "firmware"), http, v)
        view = FirmwareView(checkDaily = prefs(app).getBoolean(DAILY, false), prereleases = prefs(app).getBoolean(PRE, true))
    }

    fun hello(board: String?, version: String?, updSig: Boolean = false) {
        this.board = board
        this.version = version
        this.updSig = updSig
        cardWait = null
        val c = context ?: return
        val p = prefs(c)
        if (checkedSig.let { it != null && it != updSig }) check()
        else if (p.getBoolean(DAILY, false) && System.currentTimeMillis() - p.getLong(LAST, 0) >= DAY_MS) check()
    }

    fun opened() {
        val v = view
        if (unsigned || v.info == Firmware.BAD_SIG || (v.ready && !v.signed && updSig)) check()
    }

    fun state() {
        val c = context ?: return
        val p = prefs(c)
        if (Remote.phase == Phase.LIVE && p.getBoolean(DAILY, false) && System.currentTimeMillis() - p.getLong(LAST, 0) >= DAY_MS) check()
        if (pending == null || sending || stopSend || Remote.device.playback.state.battery == null) return
        cardWait?.let { if (Remote.device.card.epoch <= it) return }
        cardWait = null
        main.post { pending?.let { start(it) } }
    }

    fun send(picked: Boolean) {
        val c = context ?: return
        if (picked) {
            val uri = pickedUri ?: return
            val size = view.pickedSize.takeIf { it > 0 } ?: return
            send(Source(view.pickedName, size) { off -> (c.contentResolver.openInputStream(uri) ?: throw IOException("no stream")).also { skip(it, off) } })
        } else {
            send(firmware?.file(release ?: return)?.takeIf { it.isFile } ?: return)
        }
    }

    internal fun send(f: File) = send(Source(f.name, f.length()) { off -> f.inputStream().also { skip(it, off) } })

    internal fun send(src: Source) {
        pending = src
        stopSend = false
        start(src)
    }

    fun stopSend() {
        stopSend = true
        pending = null
        if (!sending) set { it.copy(send = "", info = "") }
    }

    private fun refusal(): String? {
        val c = context ?: return "not connected"
        val d = Remote.device
        if (Remote.phase != Phase.LIVE) return "not connected"
        if (!d.has("update")) return "device has no updater"
        val st = d.playback.state
        if (st.output != null && st.output != Enums.Output.wired && !PushGate.receiver(d)) return "dap on bluetooth output"
        if (PushGate.card(d)) return CardView.REFUSED
        if ((st.battery ?: 0) < DAP_MIN && st.charging != true) return "dap battery under $DAP_MIN%"
        val bm = c.getSystemService(BatteryManager::class.java)
        if (bm != null && bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) < PHONE_MIN && !bm.isCharging) return "phone battery under $PHONE_MIN%"
        return null
    }

    private fun start(src: Source) {
        if (sending || stopSend) return
        refusal()?.let { why ->
            set { it.copy(info = why, send = if (it.send.isEmpty()) "" else paused(it.send)) }
            return
        }
        sending = true
        set { it.copy(send = if (it.send.isEmpty()) "firmware 0%" else it.send, info = "") }
        sendExec.execute {
            try {
                val v = try {
                    transfer(src)
                } catch (e: Exception) {
                    Log.w(Remote.TAG, "firmware send: $e")
                    if (pending === src) pending = null
                    upd { it.copy(send = "", info = "send failed") }
                }
                set(v)
            } finally {
                sending = false
                Remote.dropWake(WAKE)
            }
            Remote.sourcesChanged()
            val next = pending
            if (next != null && !stopSend && cardWait == null) retryIn(if (next === src && busy) BUSY_RETRY_MS else Proto.pushGateMs.toLong())
        }
    }

    private fun retryIn(ms: Long) = synchronized(retry) {
        retryNext?.cancel(false)
        retryNext = retry.schedule({ main.post { pending?.let { start(it) } } }, ms, TimeUnit.MILLISECONDS)
    }

    internal fun reset() {
        synchronized(retry) { retryNext?.cancel(false) }
        pending = null
        busy = false
        cardWait = null
        stopSend = false
        updSig = false
        checkedSig = null
        unsigned = false
        view = FirmwareView()
    }

    private fun open() = PushGate.open(Remote.device, SystemClock.elapsedRealtime()) || PushGate.receiver(Remote.device)

    private fun paused(send: String) = "paused ${send.removePrefix("paused ").removePrefix("firmware ")}"

    private fun cardRefused() {
        cardWait = Remote.device.card.takeIf { !it.seen }?.epoch
    }

    private fun transfer(src: Source): (FirmwareView) -> FirmwareView {
        busy = false
        cardWait = null
        val s = Remote.session() ?: return upd { it.copy(send = paused(it.send), info = "not connected") }
        val sum = src.md5 ?: md5(src).also { src.md5 = it }
        val b = board ?: return upd { it.copy(send = paused(it.send), info = "not connected") }
        val r = UpdPush(s, b, src.size, sum, src.open, gate = {
            !stopSend && Remote.phase == Phase.LIVE && refusal() == null && open()
        }) { at ->
            Remote.holdWake(WAKE, WAKE_MS)
            val t = "firmware ${if (src.size > 0) at * 100 / src.size else 0}%"
            if (t != view.send) set { it.copy(send = t) }
        }.run()
        Log.i(Remote.TAG, "firmware send ${src.name} -> ${r.javaClass.simpleName.lowercase()}")
        if (r !is UpdPush.Outcome.Done && stopSend) {
            if (pending === src) pending = null
            return upd { it.copy(send = "", info = "") }
        }
        return when (r) {
            is UpdPush.Outcome.Done -> {
                if (pending === src) pending = null
                upd { it.copy(send = "", info = "ready on the dap") }
            }
            is UpdPush.Outcome.Paused -> {
                val why = refusal() ?: if (!open()) "waiting for playback to settle" else ""
                upd { it.copy(send = paused(it.send), info = why) }
            }
            is UpdPush.Outcome.Aborted -> if (r.abort.code == Enums.AbortCode.busy || r.abort.code == Enums.AbortCode.noCard) {
                if (r.abort.code == Enums.AbortCode.noCard) cardRefused() else busy = true
                val why = if (r.abort.code == Enums.AbortCode.noCard) CardView.REFUSED else "dap busy"
                upd { it.copy(send = paused(it.send), info = why) }
            } else {
                if (pending === src) pending = null
                upd { it.copy(send = "", info = "send aborted") }
            }
            is UpdPush.Outcome.Failed -> if (r.error?.code == Err.busy || r.error?.code == Err.noCard) {
                if (r.error?.code == Err.noCard) cardRefused() else busy = true
                val why = if (r.error?.code == Err.noCard) CardView.REFUSED else "dap busy"
                upd { it.copy(send = paused(it.send), info = why) }
            } else {
                if (pending === src) pending = null
                val why = r.error?.text?.takeIf { it.startsWith("remove ") } ?: "send failed"
                upd { it.copy(send = "", info = why) }
            }
        }
    }

    private fun md5(src: Source): String {
        val md = MessageDigest.getInstance("MD5")
        src.open(0).use { input ->
            val buf = ByteArray(65536)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun skip(input: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val k = input.skip(left)
            if (k <= 0) {
                if (input.read() < 0) throw IOException("short source")
                left--
            } else {
                left -= k
            }
        }
    }

    fun check(): Unit = exec.execute {
        val c = context ?: return@execute
        val f = firmware ?: return@execute
        val b = board
        val sig = updSig
        set { it.copy(status = "checking", info = "") }
        val v: (FirmwareView) -> FirmwareView = try {
            prefs(c).edit().putLong(LAST, System.currentTimeMillis()).apply()
            val r = f.check(b, version, prefs(c).getBoolean(PRE, true), sig)
            checkedSig = sig
            if (sig != updSig) check()
            unsigned = r == Firmware.Check.Unsigned
            when (r) {
                is Firmware.Check.Found -> {
                    release = r.release
                    val ready = f.complete(r.release)
                    val signed = ready && f.signature(r.release) != null
                    upd { it.copy(status = "", info = if (r.newer == null) "device runs a dev build" else "", tag = r.release.tag, size = r.release.asset.size, newer = r.newer, ready = ready, signed = signed, sigListed = r.release.asset.sigUrl != null, download = "") }
                }
                Firmware.Check.Unsigned -> upd { it.copy(status = "", info = UNSIGNED_RELEASE, tag = null) }
                Firmware.Check.NoAsset -> upd { it.copy(status = "", info = if (b == null) "not connected" else "no release for $b", tag = null) }
                Firmware.Check.OnlyPre -> upd { it.copy(status = "", info = "only prereleases", tag = null) }
                Firmware.Check.NoPublic -> upd { it.copy(status = "", info = "no public releases", tag = null) }
                Firmware.Check.RateLimited -> upd { it.copy(status = "", info = "rate limited, try later") }
            }
        } catch (e: IOException) {
            Log.w(Remote.TAG, "firmware check: $e")
            upd { it.copy(status = "offline") }
        }
        set(v)
    }

    fun download() {
        val rel = release ?: return
        if (view.downloading) return
        val r = HttpRun()
        run = r
        val at = percent(firmware?.downloaded(rel) ?: 0, rel.asset.size)
        set { it.copy(downloading = true, download = at, info = "") }
        exec.execute {
            val f = firmware ?: return@execute
            val v: (FirmwareView) -> FirmwareView = try {
                r.on {
                    f.downloadSigned(rel) { have, size ->
                        val t = percent(have, size)
                        if (t != view.download) set { it.copy(download = t) }
                    }
                }
                val signed = f.signature(rel) != null
                upd { it.copy(downloading = false, ready = true, signed = signed, download = "") }
            } catch (e: FirmwareRefused) {
                val ready = f.complete(rel)
                upd { it.copy(downloading = false, download = "", ready = ready, signed = false, info = e.message ?: "") }
            } catch (e: IOException) {
                Log.w(Remote.TAG, "firmware download: $e")
                if (r.cancelled) {
                    f.clear(rel)
                    upd { it.copy(downloading = false, download = "", ready = false, signed = false) }
                } else if (f.complete(rel)) {
                    upd { it.copy(downloading = false, ready = true, signed = false, download = "paused 100%") }
                } else {
                    val left = percent(f.downloaded(rel), rel.asset.size)
                    upd { it.copy(downloading = false, download = "paused $left") }
                }
            }
            if (run === r) run = null
            set(v)
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
            set { it.copy(ready = false, signed = false, download = "") }
        }
    }

    fun settings(checkDaily: Boolean, prereleases: Boolean) {
        val c = context ?: return
        prefs(c).edit().putBoolean(DAILY, checkDaily).putBoolean(PRE, prereleases).apply()
        set { it.copy(checkDaily = checkDaily, prereleases = prereleases) }
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
            try {
                c.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                Log.w(Remote.TAG, "firmware pick: $e")
            }
            pickedUri = uri
            set { it.copy(pickedName = name, pickedSize = size) }
            val p = PickedFile(name, size, board)
            main.post { done(p) }
        }
    }

    private fun percent(have: Long, size: Long) = "${if (size > 0) (have * 100 / size).coerceIn(0, 100) else 0}%"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
