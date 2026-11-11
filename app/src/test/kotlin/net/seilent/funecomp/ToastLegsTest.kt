package net.seilent.funecomp

import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.ToastLegs
import net.seilent.funecomp.ui.ToastPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToastLegsTest {
    private val end = (Tok.motion_toastRise + Tok.motion_toastHold).toLong()

    private fun shown() = ToastLegs().also { it.show(0L, held = false) }

    @Test
    fun theStripHoldsItsPlaceThroughPlusEightThenMovesLinearOver136() {
        val legs = shown()
        val lead = Tok.motion_toastOutMoveAt.toLong()
        for (t in end - 1..end + lead) assertEquals("+${t - end} ms", 1f, legs.pos(t), 0f)
        assertTrue("+${lead + 1} ms", legs.pos(end + lead + 1) < 1f)
        for (t in end..end + 200) {
            val p = ((t - end - Tok.motion_toastOutMoveAt) / Tok.motion_toastOutMove).coerceIn(0f, 1f)
            assertEquals("+${t - end} ms", 1f - p, legs.pos(t), 1f / Tok.motion_toastOutMove)
        }
    }

    @Test
    fun theAlphaHoldsThroughPlusTwoThenFallsOneMinusEaseOutOver181() {
        val legs = shown()
        val lead = Tok.motion_toastOutAt.toLong()
        for (t in end - 1..end + lead) assertEquals("+${t - end} ms", 1f, legs.fade(t), 0f)
        assertTrue("+${lead + 1} ms", legs.fade(end + lead + 1) < 1f)
        for (t in end..end + 200) {
            val u = ((t - end - Tok.motion_toastOutAt) / Tok.motion_toastOut).coerceIn(0f, 1f)
            assertEquals("+${t - end} ms", 1f - u * (2f - u), legs.fade(t), 2f / Tok.motion_toastOut)
        }
    }

    @Test
    fun theToastIsGoneOnceBothLegsEnd() {
        val legs = shown()
        val last = maxOf(Tok.motion_toastOutAt + Tok.motion_toastOut, Tok.motion_toastOutMoveAt + Tok.motion_toastOutMove).toLong()
        assertEquals(ToastPhase.OUT, legs.phase(end + last - 1))
        assertEquals(ToastPhase.HIDDEN, legs.phase(end + last))
    }
}
