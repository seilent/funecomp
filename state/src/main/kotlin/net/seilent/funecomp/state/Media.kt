package net.seilent.funecomp.state

import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums

class MediaGate {
    enum class Mode { OFF, ACTIVE, HELD }

    var mode = Mode.OFF
        private set

    fun live(s: State, setting: Boolean, sinkToDap: Boolean): Mode {
        mode = if (wanted(s, setting, sinkToDap)) Mode.ACTIVE else Mode.OFF
        return mode
    }

    fun closed(): Mode {
        mode = if (mode == Mode.ACTIVE) Mode.HELD else Mode.OFF
        return mode
    }

    fun off(): Mode {
        mode = Mode.OFF
        return mode
    }

    companion object {
        fun wanted(s: State, setting: Boolean, sinkToDap: Boolean) =
            setting && !sinkToDap && s.source == Enums.Source.local && s.track != null
    }
}

object RemoteVolume {
    data class Spec(val absolute: Boolean, val max: Int, val current: Int)

    private const val FULL = 255
    private const val PERCENT = 100

    fun spec(s: State): Spec {
        val step = s.volumeStepPct?.takeIf { it > 0 }
        val cap = s.maxVolumePct
        val pct = s.volumePct
        if (step != null && cap != null && pct != null) return Spec(true, ceil(cap, step), ceil(pct.coerceAtMost(cap), step))
        return Spec(false, PERCENT, ((s.volume ?: 0) * PERCENT + FULL / 2) / FULL)
    }

    fun percent(v: Int, s: State): Int? {
        val step = s.volumeStepPct?.takeIf { it > 0 } ?: return null
        val cap = s.maxVolumePct ?: return null
        val max = ceil(cap, step)
        return if (v >= max) cap else (v.coerceAtLeast(0) * step).coerceAtMost(cap)
    }

    private fun ceil(a: Int, b: Int) = (a + b - 1) / b
}
