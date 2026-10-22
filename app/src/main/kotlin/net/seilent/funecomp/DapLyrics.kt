package net.seilent.funecomp

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.seilent.funecomp.link.LyrGet
import net.seilent.funecomp.link.Session
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.state.LyricsFetch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class DapLyrics(
    private val link: () -> Session?,
    private val device: () -> DeviceState,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val phone: PhoneLyrics? = null,
    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { Thread(it, "daplyrics").apply { isDaemon = true } },
    private val log: (String) -> Unit = {},
) {
    private val fetch = LyricsFetch()
    @Volatile private var shown = false
    @Volatile private var generation = 0
    private var settle: ScheduledFuture<*>? = null

    var show by mutableStateOf<LyricsFetch.Show>(LyricsFetch.Show.Off)
        private set

    fun shown(on: Boolean) {
        shown = on
        if (on) synchronized(fetch) { fetch.opened() }
        poke()
    }

    fun session() {
        synchronized(fetch) {
            generation++
            fetch.session()
        }
        poke()
    }

    fun dropped(track: Long) {
        synchronized(fetch) { fetch.dropped(track) }
        poke()
    }

    fun offsetSet(track: Long) {
        val d = device()
        if (d.playback.state.track != track) return
        synchronized(fetch) { fetch.hold(track, d.playback.receivedAt) }
        poke()
    }

    fun unhold() {
        synchronized(fetch) { fetch.unhold() }
        poke()
    }

    fun poke() = exec.execute { pump() }

    internal fun idle() {
        exec.submit {}.get()
    }

    private fun pump() {
        phone?.see(device().playback.state, clock(), shown)?.let { wait ->
            settle?.cancel(false)
            settle = exec.schedule({ pump() }, wait, TimeUnit.MILLISECONDS)
        }
        val (d, gen) = synchronized(fetch) { fetch.decide(device(), clock()) to generation }
        show = d.show
        val w = d.want ?: return
        if (!shown) return
        val s = link() ?: return
        val got = try {
            when (val r = LyrGet(s) { !shown || device().playback.state.track != w.track }.run(w.track, w.have)) {
                is LyrGet.Outcome.Lines -> LyricsFetch.Got.Lines(LyricsFetch.Copy(r.crc, r.kind, r.lines))
                is LyrGet.Outcome.None -> LyricsFetch.Got.None(r.crc)
                is LyrGet.Outcome.Have -> LyricsFetch.Got.Have(r.crc)
                is LyrGet.Outcome.Failed -> LyricsFetch.Got.Failed(r.code)
                LyrGet.Outcome.Cancelled -> null
            }
        } catch (e: RuntimeException) {
            log("lyr ${java.lang.Long.toUnsignedString(w.track)}: $e")
            LyricsFetch.Got.Failed(null)
        }
        if (got != null) {
            val retry = synchronized(fetch) { if (gen == generation) fetch.done(w, got, clock(), device().card) else null }
            if (retry != null) exec.schedule({ pump() }, retry, TimeUnit.MILLISECONDS)
            log("lyr ${java.lang.Long.toUnsignedString(w.track)} crc=${w.crc} -> ${got.javaClass.simpleName.lowercase()}${(got as? LyricsFetch.Got.Failed)?.code?.let { " $it" } ?: ""}")
        }
        exec.execute { pump() }
    }
}
