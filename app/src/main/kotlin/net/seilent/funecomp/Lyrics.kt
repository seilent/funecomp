package net.seilent.funecomp

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.Lrclib
import net.seilent.funecomp.artist.LyricChain
import net.seilent.funecomp.artist.LyricSources
import net.seilent.funecomp.artist.LyricsFileException
import net.seilent.funecomp.artist.NetEase
import net.seilent.funecomp.artist.Sweep
import net.seilent.funecomp.artist.UrlHttp
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.state.LyricsPlan
import net.seilent.funecomp.state.PushGate
import net.seilent.funecomp.ui.Phase
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32

object Lyrics {
    private const val PREFS = "lyrics"
    private const val ORDER = "order"
    private const val PUT_WAIT_S = 30L
    private const val DRAIN_S = 2L
    private const val NO_REPLY = -1
    private const val BREAKER_TRIP = 3
    private const val BREAKER_PAUSE_MS = 15L * 60 * 1000
    private val breakers = LyricSources.ORDER.associateWith { Sweep.Breaker({ SystemClock.elapsedRealtime() }, BREAKER_TRIP, BREAKER_PAUSE_MS) }
    @Volatile private var exec = executor()
    private var context: Context? = null
    private var lrclib: Lrclib? = null
    private var netease: NetEase? = null
    private val done = HashSet<Long>()
    private val refused = HashSet<Triple<String, String, Long>>()
    private val repeats = setOf(Err.refused, Err.badValue, Err.tooBig, Err.crc, Err.unknown)
    private var doneGen = -1
    private val queued = AtomicBoolean(false)
    @Volatile private var generation = 0

    var sources by mutableStateOf(LyricSources())
        private set

    val on: Boolean get() = sources.ordered.isNotEmpty()

    fun init(c: Context, http: Http? = null) {
        if (context != null && http == null) return
        val app = c.applicationContext
        context = app
        val p = prefs(app)
        sources = LyricSources(p.getBoolean(LyricSources.LRCLIB, true), p.getBoolean(LyricSources.NETEASE, true), LyricSources.order(p.getString(ORDER, null)))
        val version = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0"
        val dir = File(app.filesDir, "lyrics")
        val net = http ?: UrlHttp()
        lrclib = Lrclib(dir, net, version, { sources.lrclib })
        netease = NetEase(dir, net, version, { sources.netease })
    }

    internal fun reset() {
        generation++
        exec.shutdownNow()
        exec.awaitTermination(DRAIN_S, TimeUnit.SECONDS)
        exec = executor()
        queued.set(false)
        refused.clear()
        context = null
        lrclib = null
        netease = null
        sources = LyricSources()
        network()
    }

    fun set(id: String, on: Boolean) {
        val c = context ?: return
        if (id !in LyricSources.ORDER) return
        prefs(c).edit().putBoolean(id, on).apply()
        sources = if (id == LyricSources.LRCLIB) sources.copy(lrclib = on) else sources.copy(netease = on)
        if (!on) return
        try {
            exec.execute {
                doneGen = -1
                refused.clear()
            }
        } catch (_: RejectedExecutionException) {
            return
        }
        poke()
    }

    fun move(id: String, by: Int) {
        val c = context ?: return
        val moved = sources.moved(id, by)
        if (moved == sources) return
        sources = moved
        prefs(c).edit().putString(ORDER, moved.order.joinToString(",")).apply()
    }

    fun session() {
        generation++
    }

    fun poke() {
        if (!on || !queued.compareAndSet(false, true)) return
        try {
            exec.execute {
                queued.set(false)
                try {
                    run()
                } catch (t: Throwable) {
                    Log.w(Remote.TAG, "lyrics run: $t", t)
                }
            }
        } catch (_: RejectedExecutionException) {
            queued.set(false)
        }
    }

    private fun run() {
        val gen = generation
        if (doneGen != gen) {
            done.clear()
            doneGen = gen
        }
        val l = lrclib ?: return
        val n = netease ?: return
        val d = Remote.device
        if (!d.has("lyrics") || Remote.phase != Phase.LIVE) return
        val spec = Remote.linkSpec ?: ""
        val version = d.hello?.version ?: ""
        refused.removeAll { it.first == spec && it.second != version }
        for (w in LyricsPlan.wants(d, done + refused.filter { it.first == spec }.map { it.third })) {
            if (gen != generation || !on || Remote.phase != Phase.LIVE) return
            if (Firmwares.sending || !PushGate.open(Remote.device, SystemClock.elapsedRealtime()) || PushGate.card(Remote.device)) {
                exec.schedule({ poke() }, Proto.pushGateMs.toLong(), TimeUnit.MILLISECONDS)
                return
            }
            val s = sources
            if (s.ordered.all { breakers.getValue(it).open }) return
            val chain = try {
                LyricChain(s, l::get, n::get) { breakers.getValue(it).open }.get(Lrclib.Query(w.title, w.artist, w.album, w.durationMs, w.artists))
            } catch (e: LyricsFileException) {
                Log.w(Remote.TAG, "lyrics ${w.title}: $e")
                done += w.track
                return
            }
            for ((id, o) in chain.outcome) {
                when (o) {
                    LyricChain.Outcome.Answered -> breakers.getValue(id).result(false)
                    is LyricChain.Outcome.Failed -> {
                        Log.w(Remote.TAG, "lyrics $id ${w.title}: ${o.e}")
                        breakers.getValue(id).result(true)
                    }
                    else -> {}
                }
            }
            if (!on) return
            val found = chain.found
            if (found == null || !found.synced && !w.plain) {
                if (chain.outcome.values.all { it == LyricChain.Outcome.Answered }) done += w.track
                continue
            }
            val bytes = found.text.toByteArray(Charsets.UTF_8)
            if (bytes.size > Proto.lrcMaxBytes) {
                Log.i(Remote.TAG, "lyrics ${w.title}: ${bytes.size} bytes over ${Proto.lrcMaxBytes}, skipped")
                done += w.track
                continue
            }
            val snap = Remote.device.lib.snap
            val rev = if (snap != null) null else Remote.device.playback.state.libRev ?: Remote.device.hello?.libRev ?: return
            if (gen != generation) return
            val code = put(w.track, rev, snap, bytes, found.synced, found.source)
            if (gen != generation) return
            if (code != Err.busy && code != Err.noCard && code != NO_REPLY) done += w.track
            if (code in repeats) refused += Triple(spec, version, w.track)
        }
    }

    fun network() = breakers.values.forEach { it.network() }

    private fun put(track: Long, libRev: Long?, snapRev: Long?, bytes: ByteArray, synced: Boolean, source: String): Int? {
        val latch = CountDownLatch(1)
        val code = AtomicReference<Int?>(NO_REPLY)
        Remote.lrcPut(track, libRev, snapRev, bytes, CRC32().apply { update(bytes) }.value, synced, source) {
            code.set(it)
            latch.countDown()
        }
        latch.await(PUT_WAIT_S, TimeUnit.SECONDS)
        return code.get()
    }

    private fun executor() = Executors.newSingleThreadScheduledExecutor { Thread(it, "lyrics").apply { isDaemon = true } }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
