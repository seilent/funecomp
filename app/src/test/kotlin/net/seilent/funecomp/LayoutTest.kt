package net.seilent.funecomp

import net.seilent.funecomp.proto.Band
import net.seilent.funecomp.proto.Row
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.JumpLayout
import net.seilent.funecomp.ui.Roles
import net.seilent.funecomp.ui.Shape
import net.seilent.funecomp.ui.Ui
import net.seilent.funecomp.ui.albumArtTop
import net.seilent.funecomp.ui.albumArtistBaseline
import net.seilent.funecomp.ui.albumRowsTop
import net.seilent.funecomp.ui.albumRowsX
import net.seilent.funecomp.ui.albumTextX
import net.seilent.funecomp.ui.albumTitleBaseline
import net.seilent.funecomp.ui.balanceText
import net.seilent.funecomp.ui.bandText
import net.seilent.funecomp.ui.contentTop
import net.seilent.funecomp.ui.detailLabelBaseline
import net.seilent.funecomp.ui.detailListX
import net.seilent.funecomp.ui.detailNameBaseline
import net.seilent.funecomp.ui.detailNameRight
import net.seilent.funecomp.ui.detailRingTop
import net.seilent.funecomp.ui.detailRowsTop
import net.seilent.funecomp.ui.display
import net.seilent.funecomp.ui.freqText
import net.seilent.funecomp.ui.gridHz
import net.seilent.funecomp.ui.headerBaseline
import net.seilent.funecomp.ui.headerScale
import net.seilent.funecomp.ui.homeBarAlpha
import net.seilent.funecomp.ui.homeMenuX
import net.seilent.funecomp.ui.homePitch
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
import net.seilent.funecomp.ui.npHeartHitPx
import net.seilent.funecomp.ui.npHeartYPx
import net.seilent.funecomp.ui.npLyricBottom
import net.seilent.funecomp.ui.npLyricTop
import net.seilent.funecomp.ui.npMarkCorner
import net.seilent.funecomp.ui.npMarkFits
import net.seilent.funecomp.ui.npNextBaseline
import net.seilent.funecomp.ui.npNextCount
import net.seilent.funecomp.ui.npPlayRing
import net.seilent.funecomp.ui.npProgressY
import net.seilent.funecomp.ui.npRingCenter
import net.seilent.funecomp.ui.npRingY
import net.seilent.funecomp.ui.npRowHit
import net.seilent.funecomp.ui.npSeekBottom
import net.seilent.funecomp.ui.npTextX
import net.seilent.funecomp.ui.npTitleBaseline
import net.seilent.funecomp.ui.npTransportX
import net.seilent.funecomp.ui.overlayMinusY
import net.seilent.funecomp.ui.overlayNumberBaseline
import net.seilent.funecomp.ui.overlayRingY
import net.seilent.funecomp.ui.pivotBottom
import net.seilent.funecomp.ui.pivotTop
import net.seilent.funecomp.ui.step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

class LayoutTest {
    @Test
    fun microdapLawOnTheFlip() {
        val ui = Ui.of(1080f, 2520f)
        assertEquals(1080f / 272f, ui.u, 1e-4f)
        assertEquals(272f, ui.w, 1e-3f)
        assertEquals(Shape.P, ui.shape)
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
        assertEquals(Shape.L, land.shape)
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
    fun theCoverIsShortAndWide() {
        val cover = Ui.of(1244f, 1101f)
        assertEquals(Shape.SW, cover.shape)
        assertEquals(3.4406f, cover.u, 1e-4f)
        assertEquals(361.56f, cover.w, 1e-2f)
        assertEquals(320f, cover.h, 1e-3f)
        assertEquals(264f, cover.npRingY, 1e-3f)
        assertEquals(126f, cover.npArt, 1e-3f)
        assertEquals(174f, cover.npTextX, 1e-3f)
        assertEquals(206f, cover.npProgressY, 1e-3f)
        assertEquals(60f, cover.npLyricTop, 1e-3f)
        assertEquals(224f, cover.npLyricBottom, 1e-3f)
    }

    @Test
    fun landscapeTakesTheFuneosRows() {
        val land = Ui.of(2520f, 1080f)
        assertEquals(Shape.L, land.shape)
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
        assertEquals(Shape.L, land.shape)
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

    @Test
    fun theSeekBandEndsTheSk1HeightBelowTheProgressLine() {
        val p = Ui.of(1080f, 2520f)
        for (ui in listOf(Ui.of(2411f, 930.875f), Ui.of(1244f, 1101f), p)) assertEquals(ui.npProgressY + 19.8f, ui.npSeekBottom, 1e-3f)
        assertEquals(p.npTitleBaseline - Tok.capHeight * Roles.npTitle.size, p.npSeekBottom, 1e-3f)
    }

    private class Win(val name: String, val wPx: Float, val hPx: Float, val shape: Shape, val u: Float, val w: Float, val h: Float)

    @Test
    fun everyWindowTakesItsShapeAndU() {
        val table = listOf(
            Win("360x640", 945f, 1532f, Shape.P, 1532f / 480f, 945f * 480f / 1532f, 480f),
            Win("411x960", 1078.875f, 2372f, Shape.P, 1078.875f / 272f, 272f, 2372f * 272f / 1078.875f),
            Win("600x960", 1575f, 2372f, Shape.P, 2372f / 480f, 1575f * 480f / 2372f, 480f),
            Win("960x600", 2411f, 1427f, Shape.L, 2411f / 480f, 480f, 1427f * 480f / 2411f),
            Win("flip960x411", 2411f, 930.875f, Shape.L, 930.875f / 272f, 2411f * 272f / 930.875f, 272f),
            Win("1280x800", 3251f, 1952f, Shape.L, 3251f / 480f, 480f, 1952f * 480f / 3251f),
            Win("cover474x419", 1244f, 1101f, Shape.SW, 3.4406f, 361.56f, 320f),
            Win("sn411x438", 1078.875f, 1149.75f, Shape.SN, 3.3715f, 320f, 341.0f),
            Win("split top", 1078.875f, 1040.8f, Shape.SW, 3.2525f, 331.7f, 320f),
            Win("box 948x1048", 948f, 1048f, Shape.SN, 2.9625f, 320f, 353.8f),
            Win("wm361x343", 947.625f, 900.375f, Shape.SW, 2.8137f, 336.8f, 320f),
            Win("480x320", 1440f, 960f, Shape.SW, 3f, 480f, 320f),
            Win("320x480", 960f, 1440f, Shape.P, 3f, 320f, 480f),
        )
        for (r in table) {
            val ui = Ui.of(r.wPx, r.hPx)
            assertEquals(r.name, r.shape, ui.shape)
            assertEquals(r.name, r.u, ui.u, 1e-3f)
            assertEquals(r.name, r.w, ui.w, 0.06f)
            assertEquals(r.name, r.h, ui.h, 0.06f)
        }
    }

    @Test
    fun uIsContinuousAndNeverBelowTheTwoTermLaw() {
        val d = 0.01f
        for (w in 300..3000) for (h in 300..3000) {
            val wf = w.toFloat()
            val hf = h.toFloat()
            val u = Ui.of(wf, hf).u
            val old = if (wf > hf) minOf(wf / 480f, hf / 272f) else minOf(wf / 272f, hf / 480f)
            if (u < old) fail("$w x $h below $old")
            if (abs(Ui.of(wf + d, hf).u - u) > 1e-4f * u) fail("$w x $h jumps in w")
            if (abs(Ui.of(wf, hf + d).u - u) > 1e-4f * u) fail("$w x $h jumps in h")
        }
    }

    @Test
    fun theHeartHitStaysInsideTheArtOnEveryWindow() {
        var least = Float.MAX_VALUE
        for (w in 300..3000 step 2) for (h in 300..3000 step 2) {
            val ui = Ui.of(w.toFloat(), h.toFloat())
            val art = ui.npArt
            if (npHeartFits(art) && art < least) least = art
            if (npHeartFits(art) && art < (ui.npHeartYPx(0f) + ui.npHeartHitPx / 2) / ui.u) fail("$w x $h ${ui.shape} art $art")
        }
        assertEquals(52.6f, least, 0.5f)
    }

    private fun Ui.getters() = listOf(
        pivotTop, pivotBottom, contentTop, npTextX, npArtTop, npAlbumBaseline, npArtistBaseline, npArt, npProgressY,
        npSeekBottom, npTitleBaseline, npNextBaseline, npRingY, npLyricTop, npLyricBottom, npPlayRing, npRowHit,
        npRingCenter(0), npRingCenter(1), npRingCenter(2), detailNameBaseline, detailRingTop, detailLabelBaseline,
        detailRowsTop, detailNameRight, detailListX, albumArtistBaseline, albumTitleBaseline, albumArtTop, albumRowsTop,
        albumRowsX, albumTextX, overlayRingY, overlayMinusY, overlayNumberBaseline, homePitch, homeMenuX,
    )

    @Test
    fun shortNarrowMeetsPortraitAtH480() {
        val p = Ui.of(960f, 1440f)
        val sn = Ui.of(960f, 1439.7f)
        assertEquals(Shape.P, p.shape)
        assertEquals(Shape.SN, sn.shape)
        assertEquals(479.9f, sn.h, 1e-3f)
        assertEquals(p.npNextCount, sn.npNextCount)
        assertEquals(p.npMarkCorner, sn.npMarkCorner)
        p.getters().zip(sn.getters()).forEachIndexed { i, (a, b) -> assertEquals("$i", a, b, 0.2f) }
    }

    private class Sn(
        val h: Float, val art: Float, val count: Int, val progress: Float, val title: Float, val lyricBottom: Float,
        val ring: Float, val play: Float, val hit: Float,
    )

    @Test
    fun shortNarrowTakesTheSectionFourValues() {
        val rows = listOf(
            Sn(480f, 137f, 3, 292f, 330f, 384f, 424f, 64f, 64f),
            Sn(449f, 137f, 3, 261f, 299f, 353f, 393f, 64f, 64f),
            Sn(400f, 88f, 3, 212f, 250f, 304f, 344f, 64f, 64f),
            Sn(364.6f, 52.6f, 3, 176.6f, 214.6f, 268.6f, 308.6f, 64f, 64f),
            Sn(364.5f, 88.5f, 1, 212.5f, 250.5f, 268.5f, 308.5f, 64f, 64f),
            Sn(328.6f, 52.6f, 1, 176.6f, 214.6f, 232.6f, 272.6f, 64f, 64f),
            Sn(328.5f, 84.5f, 1, 208.5f, 246.5f, 264.5f, 288.5f, 32f, 48f),
            Sn(320f, 76f, 1, 200f, 238f, 256f, 280f, 32f, 48f),
        )
        for (r in rows) {
            val ui = Ui(3f, 320f, r.h, Shape.SN)
            val n = "${r.h}"
            assertEquals(n, r.art, ui.npArt, 1e-3f)
            assertEquals(n, r.count, ui.npNextCount)
            assertEquals(n, r.progress, ui.npProgressY, 1e-3f)
            assertEquals(n, r.progress + 19.8f, ui.npSeekBottom, 1e-3f)
            assertEquals(n, r.title, ui.npTitleBaseline, 1e-3f)
            assertEquals(n, r.title + 18f, ui.npNextBaseline, 1e-3f)
            assertEquals(n, r.lyricBottom, ui.npLyricBottom, 1e-3f)
            assertEquals(n, 86f, ui.npLyricTop, 1e-3f)
            assertEquals(n, r.ring, ui.npRingY, 1e-3f)
            assertEquals(n, r.play, ui.npPlayRing, 1e-3f)
            assertEquals(n, r.hit, ui.npRowHit, 1e-3f)
            assertEquals(n, ui.npArtTop + ui.npArt, ui.npProgressY, 1e-3f)
            assertEquals(n, 16f, ui.npTextX, 1e-3f)
            assertFalse(n, ui.npMarkCorner)
            assertEquals(n, 160f * r.h / 480f, ui.overlayNumberBaseline, 1e-3f)
            assertEquals(n, 0f, ui.homeMenuX, 1e-3f)
            assertEquals(n, 55f, ui.homePitch, 1e-3f)
            assertEquals(n, 320f * 0.13f, ui.npRingCenter(0), 1e-3f)
        }
        val tall = Ui(3f, 320f, 480f, Shape.SN)
        assertEquals(74f, tall.pivotTop, 1e-3f)
        assertEquals(106f, tall.pivotBottom, 1e-3f)
        assertEquals(128f, tall.contentTop, 1e-3f)
        assertEquals(117f, tall.npArtistBaseline, 1e-3f)
        assertEquals(141f, tall.npAlbumBaseline, 1e-3f)
        assertEquals(155f, tall.npArtTop, 1e-3f)
        assertEquals(110f, tall.detailNameBaseline, 1e-3f)
        assertEquals(150f, tall.detailRingTop, 1e-3f)
        assertEquals(200f, tall.detailLabelBaseline, 1e-3f)
        assertEquals(212f, tall.detailRowsTop, 1e-3f)
        assertEquals(155f, tall.albumArtTop, 1e-3f)
        assertEquals(283f, tall.albumRowsTop, 1e-3f)
        assertEquals(216f, tall.overlayRingY, 1e-3f)
        assertEquals(372f, tall.overlayMinusY, 1e-3f)
        val mid = Ui(3f, 320f, 465f, Shape.SN)
        assertEquals(66.3f, mid.pivotTop, 0.05f)
        assertEquals(140f, mid.npArtTop, 1e-3f)
        assertEquals(268f, mid.albumRowsTop, 1e-3f)
        for (h in listOf(449f, 400f, 364.6f, 320f)) {
            val ui = Ui(3f, 320f, h, Shape.SN)
            val n = "$h"
            assertEquals(n, 58f, ui.pivotTop, 1e-3f)
            assertEquals(n, 86f, ui.pivotBottom, 1e-3f)
            assertEquals(n, 98f, ui.contentTop, 1e-3f)
            assertEquals(n, 86f, ui.npArtistBaseline, 1e-3f)
            assertEquals(n, 110f, ui.npAlbumBaseline, 1e-3f)
            assertEquals(n, 124f, ui.npArtTop, 1e-3f)
            assertEquals(n, 94f, ui.detailNameBaseline, 1e-3f)
            assertEquals(n, 134f, ui.detailRingTop, 1e-3f)
            assertEquals(n, 184f, ui.detailLabelBaseline, 1e-3f)
            assertEquals(n, 196f, ui.detailRowsTop, 1e-3f)
            assertEquals(n, 86f, ui.albumArtistBaseline, 1e-3f)
            assertEquals(n, 110f, ui.albumTitleBaseline, 1e-3f)
            assertEquals(n, 124f, ui.albumArtTop, 1e-3f)
            assertEquals(n, 252f, ui.albumRowsTop, 1e-3f)
        }
        val short = Ui(3f, 320f, 320f, Shape.SN)
        assertEquals(144f, short.overlayRingY, 1e-3f)
        assertEquals(248f, short.overlayMinusY, 1e-3f)
        assertEquals(106.667f, short.overlayNumberBaseline, 1e-3f)
    }

    @Test
    fun shortWideTakesTheSectionFourValues() {
        for (w in listOf(320f, 336.8f, 353.9f, 354.1f, 405.2f, 405.56f, 479.9f)) {
            val ui = Ui(3f, w, 320f, Shape.SW)
            val n = "$w"
            assertEquals(n, 58f, ui.pivotTop, 1e-3f)
            assertEquals(n, 86f, ui.pivotBottom, 1e-3f)
            assertEquals(n, 98f, ui.contentTop, 1e-3f)
            assertEquals(n, 126f, ui.npArt, 1e-3f)
            assertEquals(n, 72f, ui.npArtTop, 1e-3f)
            assertEquals(n, 174f, ui.npTextX, 1e-3f)
            assertEquals(n, 60f, ui.npArtistBaseline, 1e-3f)
            assertEquals(n, 84f, ui.npAlbumBaseline, 1e-3f)
            assertEquals(n, 116f, ui.npTitleBaseline, 1e-3f)
            assertEquals(n, 140f, ui.npNextBaseline, 1e-3f)
            assertEquals(n, 3, ui.npNextCount)
            assertEquals(n, 206f, ui.npProgressY, 1e-3f)
            assertEquals(n, 225.8f, ui.npSeekBottom, 1e-3f)
            assertEquals(n, 264f, ui.npRingY, 1e-3f)
            assertEquals(n, 64f, ui.npPlayRing, 1e-3f)
            assertEquals(n, 64f, ui.npRowHit, 1e-3f)
            assertEquals(n, w * 0.13f, ui.npRingCenter(0), 1e-3f)
            assertEquals(n, w * 0.5f, ui.npRingCenter(1), 1e-3f)
            assertEquals(n, w * 0.87f, ui.npRingCenter(2), 1e-3f)
            assertEquals(n, 60f, ui.npLyricTop, 1e-3f)
            assertEquals(n, 224f, ui.npLyricBottom, 1e-3f)
            assertTrue(n, ui.npMarkCorner)
            assertTrue(n, npMarkFits(ui.npArt, ui.npMarkCorner))
            assertTrue(n, npHeartFits(ui.npArt))
            assertEquals(n, 0f, ui.homeMenuX, 1e-3f)
            assertEquals(n, 55f, ui.homePitch, 1e-3f)
            assertEquals(n, 94f, ui.detailNameBaseline, 1e-3f)
            assertEquals(n, 134f, ui.detailRingTop, 1e-3f)
            assertEquals(n, 184f, ui.detailLabelBaseline, 1e-3f)
            assertEquals(n, w, ui.detailNameRight, 1e-3f)
            assertEquals(n, 0f, ui.detailListX, 1e-3f)
            assertEquals(n, 86f, ui.albumArtistBaseline, 1e-3f)
            assertEquals(n, 110f, ui.albumTitleBaseline, 1e-3f)
            assertEquals(n, 124f, ui.albumArtTop, 1e-3f)
            assertEquals(n, 252f, ui.albumRowsTop, 1e-3f)
            assertEquals(n, 0f, ui.albumRowsX, 1e-3f)
            assertEquals(n, 16f, ui.albumTextX, 1e-3f)
            assertEquals(n, 144f, ui.overlayRingY, 1e-3f)
            assertEquals(n, 248f, ui.overlayMinusY, 1e-3f)
            assertEquals(n, 106.667f, ui.overlayNumberBaseline, 1e-3f)
        }
        assertEquals(5, JumpLayout.of(Ui(3f, 353.9f, 320f, Shape.SW)).cols)
        assertEquals(6, JumpLayout.of(Ui(3f, 354.1f, 320f, Shape.SW)).cols)
    }

    @Test
    fun theHeaderScaleIsTheFrameOnP() {
        val p = Ui(1f, 272f, 480f, Shape.P)
        assertEquals(1.13333f, p.headerScale, 1e-4f)
        assertEquals(1.06882f, Ui(1f, 320f, 465f, Shape.SN).headerScale, 1e-4f)
        assertEquals(1f, Ui(1f, 320f, 449f, Shape.SN).headerScale, 1e-4f)
        val sw = Ui(1f, 480f, 320f, Shape.SW)
        assertEquals(1f, sw.headerScale, 1e-4f)
        assertEquals(1f, Ui(1f, 640f, 272f, Shape.L).headerScale, 1e-4f)
        assertEquals(58.933f, p.headerBaseline, 1e-3f)
        assertEquals(52f, sw.headerBaseline, 1e-3f)
    }
}
