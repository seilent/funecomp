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
import net.seilent.funecomp.artist.MemoryStore
import net.seilent.funecomp.artist.NeMix
import net.seilent.funecomp.artist.NetEase
import net.seilent.funecomp.artist.Sweep
import net.seilent.funecomp.artist.UrlHttp
import net.seilent.funecomp.link.LyrGet
import net.seilent.funecomp.proto.LrcReply
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.state.LyricsFetch
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
    private const val NEMIX = "nemix"
    private const val TRIES = 2
    private const val PUT_WAIT_S = 30L
    private const val DRAIN_S = 2L
    private const val NO_REPLY = -1
    private const val OK = 0
    private const val BREAKER_TRIP = 3
    private const val BREAKER_PAUSE_MS = 15L * 60 * 1000
    private val breakers = LyricSources.ORDER.associateWith { Sweep.Breaker({ SystemClock.elapsedRealtime() }, BREAKER_TRIP, BREAKER_PAUSE_MS) }
    @Volatile private var exec = executor()
    private var context: Context? = null
    private var lrclib: Lrclib? = null
    private var netease: NetEase? = null
    private val done = HashSet<Long>()
    private val attempts = HashMap<Long, Int>()
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
        val n = NetEase(dir, net, version, { sources.netease }, log = { Log.i(Remote.TAG, it) })
        netease = n
        if (p.getInt(NEMIX, 0) < NeMix.VERSION) {
            try {
                exec.execute {
                    try {
                        if (n.sweep().errors == 0) p.edit().putInt(NEMIX, NeMix.VERSION).apply()
                    } catch (t: Throwable) {
                        Log.w(Remote.TAG, "nemix sweep: $t", t)
                    }
                }
            } catch (_: RejectedExecutionException) {
            }
        }
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
        Remote.phoneLyrics.clear()
        network()
    }

    fun set(id: String, on: Boolean) {
        val c = context ?: return
        if (id !in LyricSources.ORDER) return
        prefs(c).edit().putBoolean(id, on).apply()
        sources = if (id == LyricSources.LRCLIB) sources.copy(lrclib = on) else sources.copy(netease = on)
        Remote.phoneLyrics.sources(if (on) null else id)
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
        Remote.phoneLyrics.sources(null)
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
            attempts.clear()
            doneGen = gen
        }
        val l = lrclib ?: return
        val n = netease ?: return
        val d = Remote.device
        if (!d.has("lyrics") || Remote.phase != Phase.LIVE) return
        val spec = Remote.linkSpec ?: ""
        val version = d.hello?.version ?: ""
        refused.removeAll { it.first == spec && it.second != version }
        val skip = done + refused.filter { it.first == spec }.map { it.third }
        val marked = try {
            LyricsPlan.held(d, skip).mapNotNull { h -> h.keys.firstNotNullOfOrNull { n.marked(key(h.title, it, h.album, h.durationMs)) }?.let { h to it } }
        } catch (e: LyricsFileException) {
            Log.w(Remote.TAG, "nemix marker: $e")
            emptyList()
        }
        val wants = LyricsPlan.wants(d, skip + marked.map { it.first.track })
        for ((h, reason) in marked.filter { it.first.playing }) if (!replace(h, reason, gen, l, n, spec, version)) return
        for (w in wants) if (!want(w, gen, l, n, spec, version)) return
        for ((h, reason) in marked.filterNot { it.first.playing }) if (!replace(h, reason, gen, l, n, spec, version)) return
    }

    private fun key(title: String, artist: String, album: String?, durationMs: Long?) = Lrclib.Query(title, artist, album, durationMs).key

    private fun blocked(gen: Int): Boolean {
        if (gen != generation || !on || Remote.phase != Phase.LIVE) return true
        if (Firmwares.sending || !PushGate.open(Remote.device, SystemClock.elapsedRealtime()) || PushGate.card(Remote.device)) {
            exec.schedule({ poke() }, Proto.pushGateMs.toLong(), TimeUnit.MILLISECONDS)
            return true
        }
        return sources.ordered.all { breakers.getValue(it).open }
    }

    private fun chain(q: Lrclib.Query, track: Long, l: Lrclib, n: NetEase): LyricChain.Chain? {
        val chain = try {
            LyricChain(sources, l::get, n::get) { breakers.getValue(it).open }.get(q)
        } catch (e: LyricsFileException) {
            Log.w(Remote.TAG, "lyrics ${q.track}: $e")
            done += track
            return null
        }
        for ((id, o) in chain.outcome) {
            when (o) {
                LyricChain.Outcome.Answered -> breakers.getValue(id).result(false)
                is LyricChain.Outcome.Failed -> {
                    Log.w(Remote.TAG, "lyrics $id ${q.track}: ${o.e}")
                    breakers.getValue(id).result(true)
                }
                else -> {}
            }
        }
        return chain
    }

    private fun want(w: LyricsPlan.Want, gen: Int, l: Lrclib, n: NetEase, spec: String, version: String): Boolean {
        if (blocked(gen)) return false
        val chain = chain(Lrclib.Query(w.title, w.artist, w.album, w.durationMs, w.artists), w.track, l, n) ?: return false
        if (!on) return false
        val found = chain.found
        if (found == null || !found.synced && !w.plain) {
            if (chain.outcome.values.all { it == LyricChain.Outcome.Answered }) done += w.track
            return true
        }
        val bytes = found.text.toByteArray(Charsets.UTF_8)
        if (bytes.size > Proto.lrcMaxBytes) {
            Log.i(Remote.TAG, "lyrics ${w.title}: ${bytes.size} bytes over ${Proto.lrcMaxBytes}, skipped")
            done += w.track
            return true
        }
        val code = send(w.track, bytes, found, gen) ?: return false
        if (code == Err.busy || code == Err.noCard || code == NO_REPLY) return true
        done += w.track
        if (code in repeats) refused += Triple(spec, version, w.track)
        if (code == OK) n.unmark(listOf(w.artist, w.artists.joinToString(", ")).distinct().map { key(w.title, it, w.album, w.durationMs) })
        return true
    }

    private fun replace(h: LyricsPlan.Held, reason: String, gen: Int, l: Lrclib, n: NetEase, spec: String, version: String): Boolean {
        if (blocked(gen)) return false
        val chain = chain(Lrclib.Query(h.title, h.artist, h.album, h.durationMs, h.artists), h.track, l, n) ?: return false
        if (!on) return false
        var found = chain.found
        if (found?.synced != true && chain.outcome.values.any { it != LyricChain.Outcome.Answered }) return true
        var bytes = found?.text?.toByteArray(Charsets.UTF_8)
        if (bytes != null && bytes.size > Proto.lrcMaxBytes) {
            Log.i(Remote.TAG, "lyrics ${h.title}: ${bytes.size} bytes over ${Proto.lrcMaxBytes}, skipped")
            found = null
            bytes = null
        }
        val id = java.lang.Long.toUnsignedString(h.track)
        val code = if (found != null && bytes != null && (found.synced || !h.synced)) {
            send(h.track, bytes, found, gen) ?: return false
        } else if (!LyricsFetch.lyrflags(Remote.device)) {
            Log.i(Remote.TAG, "lrc del $id kept, link below 1.15")
            done += h.track
            return true
        } else {
            if (found != null && !revKnown()) return false
            val shown = shown(h.track)
            if (gen != generation) return false
            when (shown) {
                Shown.Clean -> {
                    Log.i(Remote.TAG, "lrc del $id skipped, dap copy clean")
                    done += h.track
                    unmark(h, n)
                    return true
                }
                Shown.Glyphs -> {
                    Log.i(Remote.TAG, "lrc del $id kept, dap copy glyphs only")
                    done += h.track
                    return true
                }
                Shown.Bad -> {}
                is Shown.Failed -> return answered(h, shown.code, n, spec, version)
            }
            val (del, flags) = del(h.track, reason)
            if (gen != generation) return false
            if (del != OK) {
                del
            } else if (found != null && bytes != null) {
                send(h.track, bytes, found, gen) ?: return false
            } else {
                Log.i(Remote.TAG, "lrc del $id verdict ${if (flags != null) "gone" else "kept"}")
                done += h.track
                if (flags != null) unmark(h, n)
                return true
            }
        }
        return answered(h, code, n, spec, version)
    }

    private fun answered(h: LyricsPlan.Held, code: Int, n: NetEase, spec: String, version: String): Boolean {
        when {
            code == OK -> {
                done += h.track
                unmark(h, n)
            }
            code == Err.noCard -> {}
            code == Err.busy || code == NO_REPLY -> {
                val t = (attempts[h.track] ?: 0) + 1
                attempts[h.track] = t
                if (t >= TRIES) done += h.track
            }
            else -> {
                done += h.track
                if (code in repeats) refused += Triple(spec, version, h.track)
            }
        }
        return true
    }

    private sealed interface Shown {
        data object Clean : Shown

        data object Glyphs : Shown

        data object Bad : Shown

        data class Failed(val code: Int) : Shown
    }

    private fun shown(track: Long): Shown = when (val r = Remote.lyrGet(track)) {
        is LyrGet.Outcome.Lines -> when (val v = NeMix.check(r.lines.joinToString("\n") { it.text })) {
            NeMix.Verdict.Ok -> Shown.Clean
            is NeMix.Verdict.Reject -> if (v.reason == NeMix.HAN) Shown.Glyphs else Shown.Bad
        }
        is LyrGet.Outcome.None -> Shown.Clean
        is LyrGet.Outcome.Failed -> if (r.code == Err.notFound) Shown.Clean else Shown.Failed(r.code ?: NO_REPLY)
        else -> Shown.Failed(NO_REPLY)
    }

    private fun revKnown() = Remote.device.lib.snap != null || Remote.device.playback.state.libRev != null || Remote.device.hello?.libRev != null

    private fun unmark(h: LyricsPlan.Held, n: NetEase) = n.unmark(h.keys.map { key(h.title, it, h.album, h.durationMs) })

    private fun send(track: Long, bytes: ByteArray, found: Lrclib.Found, gen: Int): Int? {
        val snap = Remote.device.lib.snap
        val rev = if (snap != null) null else Remote.device.playback.state.libRev ?: Remote.device.hello?.libRev ?: return null
        if (gen != generation) return null
        val code = put(track, rev, snap, bytes, found.synced, found.source)
        if (gen != generation) return null
        return code ?: OK
    }

    private fun del(track: Long, reason: String): Pair<Int, LrcReply?> {
        val latch = CountDownLatch(1)
        val code = AtomicReference<Pair<Int, LrcReply?>>(NO_REPLY to null)
        Remote.lrcDel(track, reason) { c, flags ->
            code.set((c ?: OK) to flags)
            latch.countDown()
        }
        latch.await(PUT_WAIT_S, TimeUnit.SECONDS)
        return code.get()
    }

    fun network() {
        breakers.values.forEach { it.network() }
        Remote.phoneLyrics.retry()
    }

    fun phone(task: () -> Unit): Boolean = try {
        exec.execute {
            try {
                task()
            } catch (t: Throwable) {
                Log.w(Remote.TAG, "phone lyrics: $t", t)
            }
        }
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    fun phoneChain(a: PhoneLyrics.Ask): PhoneLyrics.Found {
        val l = lrclib ?: return PhoneLyrics.Found.Unavailable
        val n = netease ?: return PhoneLyrics.Found.Unavailable
        if (Remote.phase != Phase.LIVE) return PhoneLyrics.Found.Unavailable
        val store = MemoryStore()
        return PhoneLyrics.ask(a, sources, { l.get(it, store) }, { n.get(it, store) }, { breakers.getValue(it).open }, { id, failed -> breakers.getValue(id).result(failed) }) { Log.w(Remote.TAG, it) }
    }

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
