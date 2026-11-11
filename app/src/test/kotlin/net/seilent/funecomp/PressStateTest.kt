package net.seilent.funecomp

import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.PressSlots
import net.seilent.funecomp.ui.PressState
import org.junit.Assert.assertEquals
import org.junit.Test

class PressStateTest {
    private val rise = Tok.motion_pressGlow.toLong()
    private val leg = Tok.motion_press.toLong()
    private val hold = Tok.motion_touchReturnDelay.toLong()

    private fun cubicOut(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)

    @Test
    fun heldRunsFromTheDownToTheUpTheSlopOrAReset() {
        val p = PressState(null, null)
        assertEquals("at rest", false, p.held)
        p.down(0)
        assertEquals("down", true, p.held)
        p.tap(50)
        assertEquals("up", false, p.held)
        p.drop()
        assertEquals("drop after the up", false, p.held)
        p.down(1000)
        assertEquals("second down", true, p.held)
        p.cancel(1050)
        assertEquals("past the slop", false, p.held)
        p.down(2000)
        assertEquals("third down", true, p.held)
        p.reset()
        assertEquals("long press", false, p.held)
        p.down(3000)
        assertEquals("fourth down", true, p.held)
        p.drop()
        assertEquals("dropped", false, p.held)
        val slots = PressSlots()
        val a = PressState(null, slots)
        val b = PressState(null, slots)
        a.down(4000)
        assertEquals("first item down", listOf(true, false), listOf(a.held, b.held))
        b.down(4050)
        assertEquals("second item down", listOf(false, true), listOf(a.held, b.held))
        b.tap(4100)
        assertEquals("second item up", listOf(false, false), listOf(a.held, b.held))
    }

    @Test
    fun theLookRisesCubicOutOver50FromTheDown() {
        val p = PressState(null, null)
        assertEquals("at rest", 0f, p.look(0), 0f)
        p.down(1000)
        assertEquals("at the down", 0f, p.look(1000), 1e-4f)
        for (e in listOf(10L, 25L, 40L)) assertEquals("+$e", cubicOut(e.toFloat() / rise), p.look(1000 + e), 1e-4f)
        assertEquals("risen", 1f, p.look(1000 + rise), 0f)
        assertEquals("held", 1f, p.look(5000), 0f)
    }

    @Test
    fun theUpCutsTheLookAtOnce() {
        val p = PressState(null, null)
        p.down(0)
        p.tap(80)
        assertEquals("the up", 0f, p.look(80), 0f)
        assertEquals("after the up", 0f, p.look(81), 0f)
        assertEquals("past the hold", 0f, p.look(80 + hold + leg), 0f)
        p.down(1000)
        p.tap(1020)
        assertEquals("an up in the rise", 0f, p.look(1020), 0f)
        assertEquals("after an up in the rise", 0f, p.look(1000 + rise), 0f)
    }

    @Test
    fun aCancelReturnsLinearOver100FromItsValue() {
        val p = PressState(null, null)
        p.down(0)
        p.cancel(25)
        val v = cubicOut(25f / rise)
        assertEquals("at the slop in the rise", v, p.look(25), 1e-4f)
        assertEquals("half way back", v / 2, p.look(25 + leg / 2), 1e-4f)
        assertEquals("back", 0f, p.look(25 + leg), 0f)
        p.down(1000)
        p.cancel(1200)
        assertEquals("at the slop", 1f, p.look(1200), 0f)
        assertEquals("three quarters back", 0.25f, p.look(1200 + leg * 3 / 4), 1e-4f)
        assertEquals("back from 1", 0f, p.look(1200 + leg), 0f)
    }

    @Test
    fun theSlotsCutZeroesTheHeldAndTheReturningPress() {
        val slots = PressSlots()
        val a = PressState(null, slots)
        val b = PressState(null, slots)
        a.down(0)
        b.down(100)
        assertEquals("the first returns from its value", 0.5f, a.look(100 + leg / 2), 1e-4f)
        assertEquals("the second is lit", 1f, b.look(100 + leg / 2), 0f)
        slots.cut()
        assertEquals("the first cut", 0f, a.look(100 + leg / 2), 0f)
        assertEquals("the second cut", 0f, b.look(100 + leg / 2), 0f)
        assertEquals("the first stays cut", 0f, a.look(100 + leg * 3 / 4), 0f)
        assertEquals("the second stays cut", 0f, b.look(1000), 0f)
    }

    @Test
    fun onlyAScaledPressScalesAndItHolds200PastTheUp() {
        val p = PressState(null, null)
        p.down(0)
        assertEquals("unscaled in the leg", 1f, p.scaleAt(50), 0f)
        assertEquals("unscaled held", 1f, p.scaleAt(500), 0f)
        val m = PressState(null, null).apply { scaled = true }
        m.down(0)
        assertEquals("half way down", 1f - (1f - Tok.motion_pressScale) / 2, m.scaleAt(leg / 2), 1e-4f)
        assertEquals("down", Tok.motion_pressScale, m.scaleAt(leg), 1e-5f)
        m.tap(150)
        assertEquals("the up cuts the look", 0f, m.look(150), 0f)
        assertEquals("in the hold", Tok.motion_pressScale, m.scaleAt(150 + hold - 1), 1e-5f)
        assertEquals("half way back", Tok.motion_pressScale + (1f - Tok.motion_pressScale) / 2, m.scaleAt(150 + hold + leg / 2), 1e-4f)
        assertEquals("back", 1f, m.scaleAt(150 + hold + leg), 0f)
    }
}
