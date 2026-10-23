package net.seilent.funecomp.state

import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Proto

object PushGate {
    fun open(d: DeviceState, now: Long): Boolean {
        val p = d.playback
        if (p.state.output != Enums.Output.wired) return false
        val start = p.gateAt ?: return true
        return now - start >= Proto.pushGateMs
    }

    fun card(d: DeviceState): Boolean = d.cardDown
}
