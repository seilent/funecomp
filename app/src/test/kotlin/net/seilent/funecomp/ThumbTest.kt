package net.seilent.funecomp

import net.seilent.funecomp.ui.ThumbFade
import net.seilent.funecomp.ui.ThumbMath
import net.seilent.funecomp.ui.ThumbSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbTest {
    private fun bar(t: Int, view: Long, content: Long, scrolled: Long = 0, u: Int = 1) = ThumbMath.of(ThumbSpan(0, t, view, content, scrolled), 25 * u, u)

    @Test
    fun fiveHundredSongsGetTheMinimumPlusOne() {
        val b = bar(186, 186, 500 * 40L)!!
        assertEquals(0, b.y)
        assertEquals(26, b.len)
    }

    @Test
    fun fiveSettingsRowsGetAOneHundredSixtyFourUnitThumb() {
        assertEquals(164, bar(186, 186, 5 * 43L)!!.len)
    }

    @Test
    fun contentThatFitsGetsNone() {
        assertNull(bar(186, 186, 186))
        assertNull(bar(186, 186, 100))
        assertNull(bar(186, 0, 1000))
    }

    @Test
    fun aTrackOfOneUnitPastTheMinimumOrLessGetsNone() {
        assertNull(bar(4 * 26, 400, 4000, u = 4))
        assertNull(bar(4 * 25 + 2, 400, 4000, u = 4))
        assertNotNull(bar(4 * 26 + 1, 400, 4000, u = 4))
    }

    @Test
    fun theThumbRunsFromTheTrackTopToTheTrackBottom() {
        val content = 500 * 40L
        val room = content - 186
        assertEquals(0, bar(186, 186, content, 0)!!.y)
        val end = bar(186, 186, content, room)!!
        assertEquals(186, end.y + end.len)
        val past = bar(186, 186, content, room + 500)!!
        assertEquals(186, past.y + past.len)
        val mid = bar(186, 186, content, room / 2)!!
        assertEquals((186 - mid.len) / 2f, mid.y.toFloat(), 1f)
    }

    @Test
    fun theLengthHoldsItsPixelOverAScroll() {
        val lens = (0L..20_000L step 37).map { bar(744, 744, 80_000, it, 4)!!.len }.toSet()
        assertEquals(1, lens.size)
        assertTrue(lens.single() >= 100)
    }

    @Test
    fun aTopOffsetMovesTheWholeTrack() {
        val b = ThumbMath.of(ThumbSpan(64, 250, 186, 500 * 40L, 0), 25, 1)!!
        assertEquals(64, b.y)
    }

    private fun shown(at: Long = 0) = ThumbFade().also { it.track(true, false, at) }

    @Test
    fun itShowsAtOnceAndHoldsForTheHold() {
        val f = shown()
        assertEquals(1f, f.at(0), 0f)
        assertEquals(1f, f.at(1499), 0f)
        assertEquals(0.03125f, f.at(1750), 1e-4f)
        assertEquals(0f, f.at(2000), 0f)
    }

    @Test
    fun aMoveDuringTheFadeTurnsBackFromWhereItIs() {
        val f = shown()
        f.track(true, false, 1800)
        val from = f.at(1800)
        assertEquals(0.015625f, from, 1e-4f)
        f.track(true, true, 1800)
        assertEquals(from, f.at(1800), 1e-6f)
        assertEquals(from + (1f - from) * 0.96875f, f.at(2050), 1e-4f)
        assertEquals(1f, f.at(2300), 0f)
        f.track(true, false, 2300)
        assertEquals(1f, f.at(3299), 0f)
        assertEquals(0f, f.at(3800), 0f)
    }

    @Test
    fun aMoveWithinTheHoldKeepsItWithNoFadeIn() {
        val f = shown()
        f.track(true, true, 1000)
        assertEquals(1f, f.at(1000), 0f)
        assertEquals(false, f.fading(1000))
        f.track(true, false, 1016)
        assertEquals(1f, f.at(2499), 0f)
        assertEquals(0f, f.at(3000), 0f)
    }

    @Test
    fun aReleaseRestartsTheHold() {
        val f = ThumbFade()
        f.track(true, true, 0)
        assertEquals(1f, f.at(3000), 0f)
        f.release(3000)
        assertEquals(1f, f.at(4400), 0f)
        assertTrue(f.at(4600) < 1f)
        assertEquals(0f, f.at(5000), 0f)
    }

    @Test
    fun itWakesAtTheHoldEndAndFadesUntilTheFadeEnds() {
        val f = shown(100)
        assertEquals(1600, f.wakeAt(100))
        assertEquals(false, f.fading(100))
        f.track(true, false, 1700)
        assertTrue(f.fading(1700))
        assertEquals(-1, f.wakeAt(1700))
        f.track(true, false, 2100)
        assertEquals(false, f.fading(2100))
    }

    @Test
    fun aListThatStopsOverflowingIsOffAndAShowAfterItShowsAgain() {
        val f = shown()
        f.track(false, false, 2500)
        assertEquals(false, f.fading(2500))
        assertEquals(-1, f.wakeAt(2500))
        f.track(true, false, 3000)
        assertEquals(1f, f.at(3000), 0f)
        assertEquals(4500, f.wakeAt(3000))
    }
}
