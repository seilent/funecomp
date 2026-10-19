package net.seilent.funecomp.state

import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Proto

data class Play(val track: Long, val libRev: Long?, val startMs: Long, val playedMs: Long = 0, val scrobbled: Boolean = false) {
    fun line() = "$track\t${libRev ?: ""}\t$startMs\t$playedMs\t${if (scrobbled) 1 else 0}"

    companion object {
        fun parse(line: String): Play? {
            val f = line.trim().split('\t')
            if (f.size != 5) return null
            return Play(
                f[0].toLongOrNull() ?: return null,
                f[1].toLongOrNull(),
                f[2].toLongOrNull() ?: return null,
                f[3].toLongOrNull() ?: return null,
                f[4] == "1",
            )
        }
    }
}

class Plays(open: Play? = null) {
    sealed interface Event {
        val play: Play
        val state: State

        data class NowPlaying(override val play: Play, override val state: State) : Event
        data class Scrobble(override val play: Play, override val state: State) : Event
    }

    var open: Play? = open
        private set

    private var prev: State? = null
    private var prevAt = 0L
    private var offset: Long? = null
    private var base: Long? = null
    private var refine = false
    private var announced = false
    private var clocked = false
    private var clock = DeviceClock()

    fun session(clocked: Boolean = false) {
        prev = null
        offset = null
        base = null
        this.clocked = clocked
        clock = DeviceClock()
    }

    fun frame(s: State, arrivalMs: Long): List<Event> {
        val track = s.track
        if (s.source != Enums.Source.local || track == null) {
            prev = null
            return emptyList()
        }
        val sample = s.sampleMs
        val pos = s.positionMs ?: 0
        val sent = s.sentMs?.takeIf { clocked && sample != null }
        if (sent != null) {
            clock = clock.add(arrivalMs, arrivalMs - sent)
            offset = clock.offset
        } else if (sample != null) {
            offset = minOf(offset ?: Long.MAX_VALUE, arrivalMs - sample)
        }
        val p = prev
        val o = open
        val events = ArrayList<Event>()
        val same = when {
            o == null || o.track != track || o.libRev != s.libRev -> false
            p != null -> p.track == track && !repeated(p, s)
            else -> kotlin.math.abs(startOf(s, arrivalMs) - o.startMs) <= CONTINUE_MS
        }
        if (same && p == null) refine = false
        var play = if (same && o != null) o else {
            base = null
            refine = true
            announced = false
            Play(track, s.libRev, startOf(s, arrivalMs))
        }
        if (refine && base == null && s.positionHeld != true) base = sample?.let { it - pos }
        if (same && p != null && p.playing == true) {
            val moved = pos - (p.positionMs ?: 0)
            val before = p.sampleMs
            val cap = if (sample != null && before != null) sample - before else arrivalMs - prevAt
            if (moved > 0) play = play.copy(playedMs = play.playedMs + minOf(moved, cap.coerceAtLeast(0)))
        }
        val b = base
        val off = offset
        if (refine && b != null && off != null) play = play.copy(startMs = b + off)
        if (s.playing == true && !announced) {
            announced = true
            events += Event.NowPlaying(play, s)
        }
        val duration = s.durationMs ?: 0
        if (!play.scrobbled && duration > MIN_DURATION_MS && play.playedMs >= minOf(duration / 2, SCROBBLE_AT_MS)) {
            play = play.copy(scrobbled = true)
            events += Event.Scrobble(play, s)
        }
        open = play
        prev = s
        prevAt = arrivalMs
        return events
    }

    private fun repeated(p: State, s: State): Boolean {
        val duration = s.durationMs ?: return false
        if (duration <= 0) return false
        return (s.positionMs ?: 0) < REPEAT_START_MS && (p.positionMs ?: 0) > duration - Proto.stateIntervalMs - REPEAT_SLACK_MS
    }

    private fun startOf(s: State, arrivalMs: Long): Long {
        val pos = s.positionMs ?: 0
        val sample = s.sampleMs
        val off = offset
        return if (sample != null && off != null) sample - pos + off else arrivalMs - pos
    }

    companion object {
        const val MIN_DURATION_MS = 30_000L
        const val SCROBBLE_AT_MS = 240_000L
        const val CONTINUE_MS = 30_000L
        const val REPEAT_START_MS = 5_000L
        const val REPEAT_SLACK_MS = 2_000L
    }
}
