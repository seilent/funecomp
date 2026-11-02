package net.seilent.funecomp

import net.seilent.funecomp.ui.Look
import net.seilent.funecomp.ui.ringLook
import org.junit.Assert.assertEquals
import org.junit.Test

class RingLookTest {
    private fun assertLook(look: Look, alpha: Float, stroke: Float) {
        val s = ringLook(look)
        assertEquals("$look alpha", alpha, s.alpha, 0f)
        assertEquals("$look stroke", stroke, s.stroke, 0f)
    }

    @Test
    fun aTouchRingIsLitAtTheIdleStroke() = assertLook(Look.Touch, 1f, 3f)

    @Test
    fun aToggleIsLitWhenOnAndMidWhenOffAtTheIdleStroke() {
        assertLook(Look.Toggle(true), 1f, 3f)
        assertLook(Look.Toggle(false), 0.6f, 3f)
    }

    @Test
    fun aFocusRingKeepsTheKeyFocusLook() {
        assertLook(Look.Focus(true), 1f, 4f)
        assertLook(Look.Focus(false), 0.6f, 3f)
    }
}
