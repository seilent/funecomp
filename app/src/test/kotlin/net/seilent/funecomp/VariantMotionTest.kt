package net.seilent.funecomp

import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.Decel
import net.seilent.funecomp.ui.VariantMotion
import net.seilent.funecomp.ui.VariantMotion.Dir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class VariantMotionTest {
    private val tol = 1e-4f

    private fun pop(t: Float) = 1f - 2f.pow(-10f * t)

    @Test
    fun theTokensAreTheDapValues() {
        assertEquals(300f, Tok.motion_nowPlayingFade, 0f)
        assertEquals(300f, Tok.motion_nowPlayingIn, 0f)
        assertEquals(380f, Tok.motion_variantFade, 0f)
        assertEquals(0.9f, Tok.motion_nowPlayingScale, 0f)
        assertEquals(0.4f, Tok.motion_nowPlayingOriginX, 0f)
        assertEquals(0.5f, Tok.motion_nowPlayingOriginY, 0f)
        assertEquals(600f, VariantMotion.total(Dir.In), 0f)
        assertEquals(760f, VariantMotion.total(Dir.Out), 0f)
        assertEquals(300f, VariantMotion.rest(Dir.In), 0f)
        assertEquals(380f, VariantMotion.rest(Dir.Out), 0f)
    }

    @Test
    fun localToPhoneFadesTheOldLayerOutOnPopThenScalesTheNewInOnEaseIn() {
        for (e in listOf(0f, 30f, 100f, 150f, 299f)) {
            val f = VariantMotion.frame(Dir.In, e)
            assertTrue(f.from)
            assertEquals("$e alpha", 1f - pop(e / 300f), f.alpha, tol)
            assertEquals(1f, f.scale, 0f)
            assertEquals("$e shade", 1f - pop(e / 300f), f.shade, tol)
        }
        for (e in listOf(300f, 375f, 450f, 525f, 600f)) {
            val f = VariantMotion.frame(Dir.In, e)
            val t = ((e - 300f) / 300f).let { it * it }
            assertFalse(f.from)
            assertEquals("$e alpha", t, f.alpha, tol)
            assertEquals("$e scale", 0.9f + 0.1f * t, f.scale, tol)
            assertEquals(0f, f.shade, tol)
        }
        assertEquals(VariantMotion.frame(Dir.In, 600f), VariantMotion.frame(Dir.In, 900f))
    }

    @Test
    fun phoneToLocalFadesOutThenInOnDecelAtFullScale() {
        for (e in listOf(0f, 95f, 190f, 379f)) {
            val f = VariantMotion.frame(Dir.Out, e)
            assertTrue(f.from)
            assertEquals("$e alpha", 1f - Decel.transform(e / 380f), f.alpha, tol)
            assertEquals(1f, f.scale, 0f)
        }
        for (e in listOf(380f, 475f, 570f, 760f)) {
            val f = VariantMotion.frame(Dir.Out, e)
            assertFalse(f.from)
            assertEquals("$e alpha", Decel.transform((e - 380f) / 380f), f.alpha, tol)
            assertEquals(1f, f.scale, 0f)
        }
        assertEquals("shade on the Pop leg", 1f - pop(150f / 300f), VariantMotion.frame(Dir.Out, 150f).shade, tol)
        assertEquals(0f, VariantMotion.frame(Dir.Out, 300f).shade, tol)
    }
}
