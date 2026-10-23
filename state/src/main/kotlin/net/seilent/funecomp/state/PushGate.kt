package net.seilent.funecomp.state

import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Proto

object PushGate {
    enum class PhoneArtRule { Any, PausedOnly }

    val PHONE_ART_RULE = PhoneArtRule.Any

    fun open(d: DeviceState, now: Long): Boolean {
        val p = d.playback
        if (p.state.output != Enums.Output.wired) return false
        val start = p.gateAt ?: return true
        return now - start >= Proto.pushGateMs
    }

    fun phoneArt(d: DeviceState, rule: PhoneArtRule = PHONE_ART_RULE): Boolean {
        val s = d.playback.state
        if (s.source != Enums.Source.phone || s.output != Enums.Output.phoneReceiver) return false
        return when (rule) {
            PhoneArtRule.Any -> true
            PhoneArtRule.PausedOnly -> s.playing != true
        }
    }

    fun card(d: DeviceState): Boolean = d.cardDown
}
