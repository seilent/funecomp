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
