package net.seilent.funecomp

import net.seilent.funecomp.proto.Band
import net.seilent.funecomp.proto.Row
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.ui.Ui
import net.seilent.funecomp.ui.albumArtTop
import net.seilent.funecomp.ui.albumArtistBaseline
import net.seilent.funecomp.ui.albumTitleBaseline
import net.seilent.funecomp.ui.balanceText
import net.seilent.funecomp.ui.bandText
import net.seilent.funecomp.ui.display
import net.seilent.funecomp.ui.freqText
import net.seilent.funecomp.ui.gridHz
import net.seilent.funecomp.ui.homeBarAlpha
import net.seilent.funecomp.ui.homeMenuX
import net.seilent.funecomp.ui.homeRingEnter
import net.seilent.funecomp.ui.homeRingHitBottom
import net.seilent.funecomp.ui.homeRingHitRight
import net.seilent.funecomp.ui.homeRingX
import net.seilent.funecomp.ui.homeRingY
import net.seilent.funecomp.ui.homeSlotTop
import net.seilent.funecomp.ui.npAlbumBaseline
import net.seilent.funecomp.ui.npArt
import net.seilent.funecomp.ui.npArtTop
import net.seilent.funecomp.ui.npArtistBaseline
import net.seilent.funecomp.ui.npHeartFits
import net.seilent.funecomp.ui.npMarkFits
import net.seilent.funecomp.ui.npLyricBottom
import net.seilent.funecomp.ui.npLyricTop
import net.seilent.funecomp.ui.npProgressY
import net.seilent.funecomp.ui.npRingCenter
import net.seilent.funecomp.ui.npRingY
import net.seilent.funecomp.ui.npTitleBaseline
import net.seilent.funecomp.ui.npTransportX
import net.seilent.funecomp.ui.step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTest {
    @Test
    fun microdapLawOnTheFlip() {
        val ui = Ui.of(1080f, 2520f)
        assertEquals(1080f / 272f, ui.u, 1e-4f)
        assertEquals(272f, ui.w, 1e-3f)
        assertFalse(ui.landscape)
        assertEquals(168f, ui.npArt, 1e-3f)
        assertEquals(405.778f, ui.npProgressY, 1e-3f)
        assertEquals(ui.npProgressY, ui.npArtTop + ui.npArt, 1e-3f)
        assertEquals(ui.npArtTop - 14f, ui.npAlbumBaseline, 1e-3f)
        assertEquals(ui.npAlbumBaseline - 24f, ui.npArtistBaseline, 1e-3f)
        assertEquals(443.778f, ui.npTitleBaseline, 1e-3f)
        for (album in listOf(ui, Ui.of(272f, 480f), Ui.of(360f, 640f))) {
            assertEquals(117f, album.albumArtistBaseline, 1e-3f)
            assertEquals(141f, album.albumTitleBaseline, 1e-3f)
            assertEquals(155f, album.albumArtTop, 1e-3f)
        }
        val zune = Ui.of(272f, 480f)
        assertEquals(137f, zune.npArt, 1e-3f)
        assertEquals(155f, zune.npArtTop, 1e-3f)
        assertEquals(117f, zune.npArtistBaseline, 1e-3f)
    }

    @Test
    fun npBottomStackLiftsByAThirdOfTheSurplus() {
        val flip = Ui.of(411f, 960f - 148f / 2.625f)
        assertEquals(598.016f, flip.h, 1e-3f)
        assertEquals(381.344f, flip.npProgressY, 1e-3f)
        assertEquals(213.344f, flip.npArtTop, 1e-3f)
        assertEquals(175.344f, flip.npArtistBaseline, 1e-3f)
        assertEquals(419.344f, flip.npTitleBaseline, 1e-3f)
        assertEquals(513.344f, flip.npRingY, 1e-3f)
        val small = Ui.of(360f, 640f - 148f / 2.625f)
        assertEquals(480f, small.h, 1e-3f)
        assertEquals(292f, small.npProgressY, 1e-3f)
        assertEquals(330f, small.npTitleBaseline, 1e-3f)
        assertEquals(424f, small.npRingY, 1e-3f)
        val land = Ui.of(960f - 109f / 2.625f, 411f - 148f / 2.625f)
        assertTrue(land.landscape)
        assertEquals(272f, land.h, 1e-3f)
        assertEquals(246f, land.npProgressY, 1e-3f)
        assertEquals(116f, land.npTitleBaseline, 1e-3f)
        assertEquals(216f, land.npRingY, 1e-3f)
    }

    @Test
    fun theLyricsBandIs298InPortrait() {
        for (ui in listOf(Ui.of(272f, 480f), Ui.of(360f, 640f - 148f / 2.625f), Ui.of(411f, 960f - 148f / 2.625f))) {
            assertEquals(298f, ui.npLyricBottom - ui.npLyricTop, 1e-3f)
        }
        val flip = Ui.of(411f, 960f - 148f / 2.625f)
        assertEquals(flip.npArtistBaseline, flip.npLyricTop, 1e-3f)
        assertEquals(86f, Ui.of(272f, 480f).npLyricTop, 1e-3f)
        val land = Ui.of(960f - 109f / 2.625f, 411f - 148f / 2.625f)
        assertEquals(60f, land.npLyricTop, 1e-3f)
        assertEquals(176f, land.npLyricBottom, 1e-3f)
    }

    @Test
    fun transportSitsAtTheRingMidpoints() {
        val ui = Ui.of(272f, 480f)
        assertEquals((ui.npRingCenter(0) + ui.npRingCenter(1)) / 2, ui.npTransportX(0), 1e-3f)
        assertEquals((ui.npRingCenter(1) + ui.npRingCenter(2)) / 2, ui.npTransportX(1), 1e-3f)
        assertEquals(85.68f, ui.npTransportX(0), 1e-3f)
    }

    @Test
    fun heartFitsOnlyOnA48Art() {
        assertFalse(npHeartFits(47.9f))
        assertTrue(npHeartFits(48f))
    }

    @Test
    fun theMarkFitsBesideA32ArtAndInTheCornerOfA92Art() {
        assertFalse(npMarkFits(31.9f, false))
        assertTrue(npMarkFits(32f, false))
        assertFalse(npMarkFits(91.9f, true))
        assertTrue(npMarkFits(92f, true))
    }

    @Test
    fun theCoverIsLandscape() {
        val cover = Ui.of(948f, 748f)
        assertTrue(cover.landscape)
        assertEquals(480f, cover.w, 1e-3f)
        assertEquals(378.734f, cover.h, 1e-3f)
        assertEquals(322.734f, cover.npRingY, 1e-3f)
    }

    @Test
    fun landscapeTakesTheFuneosRows() {
        val land = Ui.of(2520f, 1080f)
        assertTrue(land.landscape)
        assertEquals(minOf(168f, land.h - 106f), land.npArt, 1e-3f)
    }

    @Test
    fun homeRingSitsLeftOfMusic() {
        val portrait = Ui.of(411f, 960f)
        assertEquals(50f, portrait.homeRingX, 1e-3f)
        assertEquals(97.9f, portrait.homeRingY(1), 1e-3f)
        assertEquals(0f, portrait.homeMenuX, 1e-3f)
        assertEquals(62f, portrait.homeRingHitRight, 1e-3f)
        assertEquals(79.2f, portrait.homeSlotTop(1), 1e-3f)
        assertEquals(119f, portrait.homeRingHitBottom(1), 1e-3f)
        val land = Ui.of(960f, 600f)
        assertTrue(land.landscape)
        assertEquals(290f, land.homeRingX, 1e-3f)
        assertEquals(90.9f, land.homeRingY(1), 1e-3f)
        assertEquals(240f, land.homeMenuX, 1e-3f)
        assertEquals(302f, land.homeRingHitRight, 1e-3f)
        assertEquals(72.2f, land.homeSlotTop(1), 1e-3f)
        assertEquals(112f, land.homeRingHitBottom(1), 1e-3f)
    }

    @Test
    fun homeBarFadesNearMusic() {
        assertEquals(0f, homeBarAlpha(1f, 1), 1e-6f)
        assertEquals(1f, homeBarAlpha(0f, 1), 1e-6f)
        assertEquals(1f, homeBarAlpha(2f, 1), 1e-6f)
        assertEquals(1f, homeBarAlpha(5f, 1), 1e-6f)
        assertEquals(0.5f, homeBarAlpha(0.5f, 1), 1e-6f)
        assertEquals(0.5f, homeBarAlpha(1.5f, 1), 1e-6f)
    }

    @Test
    fun homeRingEntersOverThePeek() {
        assertEquals(0f, homeRingEnter(0f), 1e-6f)
        val half = homeRingEnter(210f)
        assertTrue(half > 0.5f && half < 1f)
        assertEquals(1f, homeRingEnter(420f), 1e-6f)
        assertEquals(1f, homeRingEnter(1000f), 1e-6f)
    }

    @Test
    fun rangeRows() {
        val accent = Row("accentHue", "ACCENT", "display", Enums.RowKind.range, min = 0, max = 350, step = 10)
        assertEquals("", accent.display("230"))
        assertEquals("240", accent.step("230", 1))
        assertEquals("0", accent.step("350", 1))
        assertEquals("350", accent.step("0", -1))
        assertEquals("250", accent.step("237", 1))
        val balance = Row("balance", "BALANCE", "output", Enums.RowKind.range, min = -20, max = 20, step = 1)
        assertEquals("r 1.5 db", balance.display("3"))
        assertEquals("20", balance.step("20", 1))
        assertEquals("-20", balance.step("-20", -1))
    }

    @Test
    fun valueTexts() {
        assertEquals("center", balanceText(0))
        assertEquals("l 2.5 db", balanceText(-5))
        assertEquals("r 10 db", balanceText(20))
        assertEquals("105 hz", freqText(105f))
        assertEquals("2.4 khz", freqText(2400f))
        assertEquals("20 khz", freqText(20000f))
        assertEquals(1000f, gridHz(34), 1e-3f)
        assertEquals(20f, gridHz(0), 1e-3f)
        assertEquals(20000f, gridHz(60), 1e-3f)
        assertEquals("ls 105 hz +5.5 db q 0.70", bandText(Band(Enums.BandType.lowShelf, -1, 105_000, 55, 70)))
        assertEquals("off", bandText(null))
    }
}
