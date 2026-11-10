package net.seilent.funecomp

import net.seilent.funecomp.ui.PressSlots
import net.seilent.funecomp.ui.PressState
import org.junit.Assert.assertEquals
import org.junit.Test

class PressStateTest {
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
}
