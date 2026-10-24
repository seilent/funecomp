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

    fun receiver(d: DeviceState): Boolean {
        val s = d.playback.state
        return s.source == Enums.Source.phone && s.output == Enums.Output.phoneReceiver
    }

    fun phoneArt(d: DeviceState, rule: PhoneArtRule = PHONE_ART_RULE): Boolean {
        if (!receiver(d)) return false
        return when (rule) {
            PhoneArtRule.Any -> true
            PhoneArtRule.PausedOnly -> d.playback.state.playing != true
        }
    }

    fun artKey(d: DeviceState, rule: PhoneArtRule = PHONE_ART_RULE): List<Any?> {
        val s = d.playback.state
        return listOf(s.artists, s.output, s.queueRev, s.slot, phoneArt(d, rule))
    }

    fun card(d: DeviceState): Boolean = d.cardDown
}
