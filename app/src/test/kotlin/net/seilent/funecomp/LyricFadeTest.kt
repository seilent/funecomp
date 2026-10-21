package net.seilent.funecomp

import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.Decel
import net.seilent.funecomp.ui.LyricFade
import net.seilent.funecomp.ui.LyricFade.Dir
import net.seilent.funecomp.ui.LyricFade.Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricFadeTest {
    private val f = Tok.motion_variantFade
    private val t = 2 * f

    private fun Frame.layers() = if (lyrics) 0f to alpha else alpha to 0f

    private fun near(at: String, want: Frame, got: Frame, tol: Float) {
        assertEquals("$at normal", want.layers().first, got.layers().first, tol)
        assertEquals("$at lyrics", want.layers().second, got.layers().second, tol)
        assertEquals("$at shade", want.shade, got.shade, tol)
    }

    @Test
    fun theTimelineIsTwoDecelLegsWithTheShadeOnTheOutLeg() {
        assertEquals(380f, f, 0f)
        assertEquals(760f, LyricFade.T, 0f)
        for (e in listOf(0f, 50f, 100f, 200f, 300f, 379f)) {
            val q = Decel.transform(e / f)
            assertEquals(Frame(false, 1f - q, q), LyricFade.frame(Dir.Enter, e))
            assertEquals(Frame(true, 1f - q, 1f - q), LyricFade.frame(Dir.Close, e))
        }
        for (e in listOf(380f, 450f, 600f, 700f, 760f)) {
            val q = Decel.transform((e - f) / f)
            assertEquals(Frame(true, q, 1f), LyricFade.frame(Dir.Enter, e))
            assertEquals(Frame(false, q, 0f), LyricFade.frame(Dir.Close, e))
        }
    }

    @Test
    fun theEndsAndTheMidpoint() {
        assertEquals(Frame(false, 1f, 0f), LyricFade.frame(Dir.Enter, 0f))
        assertEquals(Frame(false, 1f, 0f), LyricFade.frame(Dir.Close, t))
        assertEquals(Frame(true, 1f, 1f), LyricFade.frame(Dir.Enter, t))
        assertEquals(Frame(true, 1f, 1f), LyricFade.frame(Dir.Close, 0f))
        assertEquals(0f, LyricFade.frame(Dir.Enter, f).alpha, 0f)
        assertEquals(0f, LyricFade.frame(Dir.Close, f).alpha, 0f)
        assertEquals(1f, LyricFade.frame(Dir.Enter, f).shade, 0f)
        assertEquals(LyricFade.frame(Dir.Enter, 0f), LyricFade.frame(Dir.Enter, -5f))
        assertEquals(LyricFade.frame(Dir.Enter, t), LyricFade.frame(Dir.Enter, t + 5f))
    }

    @Test
    fun theForwardRunsElapsedLinearlyFromTheFirstFrame() {
        for (ms in 0..t.toInt()) assertEquals("$ms", ms.toFloat(), LyricFade.forward(5_000L, 5_000L + ms), 0f)
        assertEquals(t, LyricFade.forward(5_000L, 5_000L + t.toLong() + 50), 0f)
        assertEquals(0f, LyricFade.forward(5_000L, 4_990L), 0f)
    }

    private val start = 1_000L

    @Test
    fun aBackBetweenTwoFramesTakesTheElapsedAtTheBack() {
        val b = LyricFade.back(start, start + 380)
        assertEquals(LyricFade.Back(start + 380, 380f), b)
        assertEquals(376f, LyricFade.reverse(b, start + 384), 0f)
        assertEquals(8f, LyricFade.reverse(b, start + 752), 0f)
        assertEquals(0f, LyricFade.reverse(b, start + 760), 0f)
        assertEquals(Frame(false, 1f, 0f), LyricFade.frame(Dir.Enter, LyricFade.reverse(b, start + 768)))
    }

    @Test
    fun aBackPlaysTheEnterTimelineBackwardsFromTheBack() {
        for (eB in listOf(100L, 380L, 600L)) {
            val back = start + eB
            val b = LyricFade.back(start, back)
            assertEquals("$eB", eB.toFloat(), b.eB, 0f)
            near("$eB first ms", LyricFade.frame(Dir.Enter, eB.toFloat()), LyricFade.frame(Dir.Enter, LyricFade.reverse(b, back + 1)), 1f / 256)
            for (ms in 0..eB) {
                val e = LyricFade.reverse(b, back + ms)
                assertEquals("$eB at $ms", LyricFade.frame(Dir.Enter, (eB - ms).toFloat()), LyricFade.frame(Dir.Enter, e))
                if (ms < eB) assertTrue("$eB at $ms", e > 0f)
            }
            assertEquals("$eB", Frame(false, 1f, 0f), LyricFade.frame(Dir.Enter, LyricFade.reverse(b, back + eB)))
        }
    }

    @Test
    fun aScaleOfTwoStretchesTheForwardAndTheReverse() {
        for (ms in 0..(2 * t).toInt()) assertEquals("$ms", ms / 2f, LyricFade.forward(5_000L, 5_000L + ms, 2f), 0f)
        assertEquals(t, LyricFade.forward(5_000L, 5_000L + 2 * t.toLong() + 50, 2f), 0f)
        val b = LyricFade.back(start, start + 400, 2f)
        assertEquals(LyricFade.Back(start + 400, 200f), b)
        assertEquals(150f, LyricFade.reverse(b, start + 500, 2f), 0f)
        assertEquals(0.5f, LyricFade.reverse(b, start + 799, 2f), 0f)
        assertEquals(0f, LyricFade.reverse(b, start + 800, 2f), 0f)
    }

    @Test
    fun aBackBeforeTheFirstFrameOrPastTheEndIsBounded() {
        assertEquals(0f, LyricFade.reverse(LyricFade.back(null, 9_000L), 9_016L), 0f)
        assertEquals(t, LyricFade.back(start, start + 790).eB, 0f)
        assertEquals(0f, LyricFade.back(start, start - 5).eB, 0f)
    }
}
