package net.seilent.funecomp

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.Lrclib
import net.seilent.funecomp.artist.Sweep
import net.seilent.funecomp.artist.UrlHttp
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.state.LyricsPlan
import net.seilent.funecomp.state.PushGate
import net.seilent.funecomp.ui.Phase
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32

object Lyrics {
    private const val PREFS = "lyrics"
    private const val LRCLIB = "lrclib"
    private const val PUT_WAIT_S = 30L
    private const val NO_REPLY = -1
    private const val BREAKER_TRIP = 3
    private const val BREAKER_PAUSE_MS = 15L * 60 * 1000
    private val breaker = Sweep.Breaker({ SystemClock.elapsedRealtime() }, BREAKER_TRIP, BREAKER_PAUSE_MS)
    private val exec = Executors.newSingleThreadScheduledExecutor { Thread(it, "lyrics").apply { isDaemon = true } }
    private var context: Context? = null
    private var lrclib: Lrclib? = null
    private val done = HashSet<Long>()
    private var doneGen = -1
    private val queued = AtomicBoolean(false)
    @Volatile private var generation = 0

    var on by mutableStateOf(false)
        private set

    fun init(c: Context, http: Http? = null) {
        if (context != null && http == null) return
        val app = c.applicationContext
        context = app
        on = prefs(app).getBoolean(LRCLIB, false)
        val version = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0"
        lrclib = Lrclib(File(app.filesDir, "lyrics"), http ?: UrlHttp(), version, { on })
    }

    internal fun reset() {
        context = null
        lrclib = null
        on = false
    }

    fun set(on: Boolean) {
        val c = context ?: return
        prefs(c).edit().putBoolean(LRCLIB, on).apply()
        this.on = on
        if (on) poke()
    }

    fun session() {
        generation++
    }

    fun poke() {
        if (!on || !queued.compareAndSet(false, true)) return
        exec.execute {
            queued.set(false)
            try {
                run()
            } catch (t: Throwable) {
                Log.w(Remote.TAG, "lyrics run: $t", t)
            }
        }
    }

    private fun run() {
        val gen = generation
        if (doneGen != gen) {
            done.clear()
            doneGen = gen
        }
        val l = lrclib ?: return
        val d = Remote.device
        if (!d.has("lyrics") || Remote.phase != Phase.LIVE) return
        for (w in LyricsPlan.wants(d, done)) {
            if (gen != generation || !on || Remote.phase != Phase.LIVE) return
            if (Firmwares.sending || !PushGate.open(Remote.device, SystemClock.elapsedRealtime())) {
                exec.schedule({ poke() }, Proto.pushGateMs.toLong(), TimeUnit.MILLISECONDS)
                return
            }
            if (breaker.open) return
            val text = try {
                l.get(Lrclib.Query(w.title, w.artist, w.album, w.durationMs)).also { breaker.result(false) }
            } catch (e: IOException) {
                Log.w(Remote.TAG, "lyrics ${w.title}: $e")
                breaker.result(true)
                return
            }
            if (!on) return
            if (text == null) {
                done += w.track
                continue
            }
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.size > Proto.lrcMaxBytes) {
                Log.i(Remote.TAG, "lyrics ${w.title}: ${bytes.size} bytes over ${Proto.lrcMaxBytes}, skipped")
                done += w.track
                continue
            }
            val rev = Remote.device.playback.state.libRev ?: Remote.device.hello?.libRev ?: return
            if (gen != generation) return
            val code = put(w.track, rev, bytes)
            if (gen != generation) return
            if (code != Err.busy && code != NO_REPLY) done += w.track
        }
    }

    fun network() = breaker.network()

    private fun put(track: Long, libRev: Long, bytes: ByteArray): Int? {
        val latch = CountDownLatch(1)
        val code = AtomicReference<Int?>(NO_REPLY)
        Remote.lrcPut(track, libRev, bytes, CRC32().apply { update(bytes) }.value) {
            code.set(it)
            latch.countDown()
        }
        latch.await(PUT_WAIT_S, TimeUnit.SECONDS)
        return code.get()
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
