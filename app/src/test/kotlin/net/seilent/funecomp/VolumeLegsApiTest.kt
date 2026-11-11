package net.seilent.funecomp

import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.VolumeLegs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeLegsApiTest {
    private fun p(e: Long, d: Float) = (e / d).coerceIn(0f, 1f)

    private fun easeOut(t: Float) = t.coerceIn(0f, 1f).let { it * (2f - it) }

    private val t0 = 10_000L

    private val fade = t0 + (Tok.motion_volumeOpenEnd + Tok.motion_volumeRestHold).toLong()

    private val out = fade + Tok.motion_volumeRestSlow.toLong()

    @Test
    fun theScrimComesInLinearFromItsLead() {
        val l = VolumeLegs()
        l.open(t0)
        for (e in listOf(0L, 50L, 120L, 200L, 245L, 300L)) {
            assertEquals("scrim at $e", p(e + Tok.motion_volumeScrimLead.toLong(), Tok.motion_volumeOpen), l.scrimK(t0 + e), 1e-4f)
        }
    }

    @Test
    fun theScrimGoesOutLinearFromTheSlowLegsEnd() {
        val l = VolumeLegs()
        l.open(t0)
        assertEquals(1f, l.scrimK(fade + 100), 1e-4f)
        assertEquals(1f, l.scrimK(out - 1), 1e-4f)
        for (e in listOf(0L, 60L, 133L, 200L, 267L)) {
            assertEquals("scrim out at $e", 1f - p(e, Tok.motion_volumeScrimOut), l.scrimK(out + e), 1e-4f)
        }
        assertTrue(l.visible(out + Tok.motion_volumeScrimOut.toLong() - 1))
        assertFalse(l.visible(out + Tok.motion_volumeScrimOut.toLong()))
        assertFalse(l.closing(out - 1))
        assertTrue(l.closing(out))
    }

    @Test
    fun aTouchCloseTakesTheScrimOutOnEaseOutFromItsLead() {
        val l = VolumeLegs()
        l.open(t0)
        val at = t0 + 1_000L
        l.hide(at)
        assertTrue(l.closing(at))
        for (e in listOf(0L, 10L, 30L, 50L, 71L)) {
            val want = 1f - easeOut(p(e + Tok.motion_volumeTouchScrimLead.toLong(), Tok.motion_volumeTouchScrim))
            assertEquals("touch scrim at $e", want, l.scrimK(at + e), 1e-4f)
        }
        val gone = maxOf(Tok.motion_volumeTouchClose.toLong(), (Tok.motion_volumeTouchScrim - Tok.motion_volumeTouchScrimLead).toLong())
        assertTrue(l.visible(at + gone - 1))
        assertFalse(l.visible(at + gone))
    }

    @Test
    fun aReviveBringsTheScrimBackLinearFromItsValue() {
        val l = VolumeLegs()
        l.open(t0)
        val at = out + 100L
        val from = l.scrimK(at)
        l.open(at)
        assertFalse(l.closing(at))
        assertEquals(from, l.scrimK(at), 1e-4f)
        assertEquals(minOf(1f, from + 100f / Tok.motion_volumeNumberBack), l.scrimK(at + 100), 1e-4f)
    }
}
