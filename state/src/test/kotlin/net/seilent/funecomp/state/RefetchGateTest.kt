package net.seilent.funecomp.state

import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Proto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefetchGateTest {
    private fun st(playing: Boolean?, output: Int = Enums.Output.wired, card: Int = Enums.Card.ready, hold: Boolean? = null) =
        State(playing = playing, output = output, card = card, imgHold = hold)

    private fun seq(vararg at: Pair<Long, State>): DeviceState = at.fold(DeviceState()) { d, (t, s) -> d.withState(s, t) }

    @Test
    fun wiredOutputOpensAfterThePushGate() {
        val d = seq(0L to st(true))
        assertFalse(PushGate.refetch(d, Proto.pushGateMs - 1L))
        assertTrue(PushGate.refetch(d, Proto.pushGateMs.toLong()))
    }

    @Test
    fun anyOutputOpensAfterTheIdleTimeSinceTheAppSawThePause() {
        val d = seq(0L to st(true, Enums.Output.btHeadset), 5_000L to st(false, Enums.Output.btHeadset))
        assertEquals(5_000L, d.playback.stoppedAt)
        assertFalse(PushGate.refetch(d, 5_000L + Proto.imgRefetchIdleMs - 1))
        assertTrue(PushGate.refetch(d, 5_000L + Proto.imgRefetchIdleMs))
        val later = d.withState(st(false, Enums.Output.btHeadset), 9_000L)
        assertEquals(5_000L, later.playback.stoppedAt)
    }

    @Test
    fun pausedAtConnectCountsFromTheFirstState() {
        val d = seq(2_000L to st(false, Enums.Output.btHeadset))
        assertEquals(2_000L, d.playback.stoppedAt)
        assertTrue(PushGate.refetch(d, 2_000L + Proto.imgRefetchIdleMs))
    }

    @Test
    fun bluetoothPlaybackTheCardAndAHoldCloseIt() {
        assertFalse(PushGate.refetch(seq(0L to st(true, Enums.Output.btHeadset)), 60_000L))
        assertFalse(PushGate.refetch(seq(0L to st(true, Enums.Output.phoneReceiver)), 60_000L))
        assertFalse(PushGate.refetch(seq(0L to st(false, card = Enums.Card.busy)), 60_000L))
        assertFalse(PushGate.refetch(seq(0L to st(false, hold = true)), 60_000L))
        assertFalse(PushGate.rearm(seq(0L to st(false, hold = true))))
        assertTrue(PushGate.rearm(seq(0L to st(true, Enums.Output.btHeadset))))
    }
}
