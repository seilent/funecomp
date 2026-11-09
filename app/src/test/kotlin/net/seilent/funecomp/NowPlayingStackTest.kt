package net.seilent.funecomp

import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.proto.gen.TokBoard
import net.seilent.funecomp.ui.LayoutTok
import net.seilent.funecomp.ui.Shape
import net.seilent.funecomp.ui.Ui
import net.seilent.funecomp.ui.npAlbumBaseline
import net.seilent.funecomp.ui.npArt
import net.seilent.funecomp.ui.npArtTop
import net.seilent.funecomp.ui.npArtistBaseline
import net.seilent.funecomp.ui.npBarY
import net.seilent.funecomp.ui.npLyricBottom
import net.seilent.funecomp.ui.npLyricTop
import net.seilent.funecomp.ui.npNextBaseline
import net.seilent.funecomp.ui.npNextCount
import net.seilent.funecomp.ui.npPlayRing
import net.seilent.funecomp.ui.npProgressY
import net.seilent.funecomp.ui.npRingY
import net.seilent.funecomp.ui.npSeekBottom
import net.seilent.funecomp.ui.npTimesBaseline
import net.seilent.funecomp.ui.npTitleBaseline
import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingStackTest {
    private class Row(
        val name: String, val ui: Ui, val artist: Float, val album: Float, val artTop: Float, val artBottom: Float,
        val title: Float, val next: List<Float>, val ring: Float, val lyricTop: Float, val lyricBottom: Float,
        val bar: Float, val times: Float, val band: Float,
    )

    private val rows = listOf(
        Row("P 360x640", Ui.of(945f, 1532f), 125.81f, 150.21f, 155.51f, 304.91f, 344.41f, listOf(359.61f, 371.41f, 383.21f), 427.26f, 85.21f, 383.21f, 305.79f, 320.81f, 330.2f),
        Row("P 600x960", Ui.of(1575f, 2372f), 125.81f, 150.21f, 155.51f, 304.91f, 344.41f, listOf(359.61f, 371.41f, 383.21f), 427.26f, 85.21f, 383.21f, 305.79f, 320.81f, 330.2f),
        Row("P 411x960", Ui.of(1078.875f, 2372f), 204.49f, 228.89f, 234.19f, 383.59f, 423.09f, listOf(438.29f, 450.09f, 461.89f), 505.94f, 163.89f, 461.89f, 384.47f, 399.49f, 408.88f),
        Row("SN at H0", Ui(3f, 320f, 457.8294f, Shape.SN), 111.03f, 135.43f, 140.73f, 290.13f, 329.63f, listOf(344.83f, 356.63f, 368.43f), 412.48f, 86f, 368.43f, 291.01f, 306.03f, 315.42f),
        Row("SN 479.9", Ui(3f, 320f, 479.9f, Shape.SN), 125.74f, 150.14f, 155.44f, 304.84f, 344.34f, listOf(359.54f, 371.34f, 383.14f), 427.19f, 86f, 383.14f, 305.72f, 320.74f, 330.13f),
        Row("SN 443.7", Ui(3f, 320f, 443.7f, Shape.SN), 96.9f, 121.3f, 126.6f, 276f, 315.5f, listOf(330.7f, 342.5f, 354.3f), 398.35f, 86f, 354.3f, 276.88f, 291.9f, 301.29f),
        Row("SN 341", Ui(3f, 320f, 341f, Shape.SN), 89f, 113.4f, 118.7f, 196.9f, 236.4f, listOf(251.6f), 295.65f, 86f, 251.6f, 197.78f, 212.8f, 222.19f),
        Row("SN 320", Ui(3f, 320f, 320f, Shape.SN), 89f, 113.4f, 118.7f, 210.2f, 249.7f, listOf(264.9f), 291.8f, 86f, 264.9f, 211.08f, 226.1f, 235.49f),
    )

    @Test
    fun portraitAndShortNarrowHangTheR1ColumnFromTheBottom() {
        for (r in rows) {
            val ui = r.ui
            val n = r.name
            assertEquals(n, r.artist, ui.npArtistBaseline, 0.01f)
            assertEquals(n, r.album, ui.npAlbumBaseline, 0.01f)
            assertEquals(n, r.artTop, ui.npArtTop, 0.01f)
            assertEquals(n, r.artBottom, ui.npArtTop + ui.npArt, 0.01f)
            assertEquals(n, r.title, ui.npTitleBaseline, 0.01f)
            assertEquals(n, r.next.size, ui.npNextCount)
            r.next.forEachIndexed { i, y -> assertEquals("$n next $i", y, ui.npNextBaseline + LayoutTok.npNextLinePitch * i, 0.01f) }
            assertEquals(n, r.ring, ui.npRingY, 0.01f)
            assertEquals(n, r.lyricTop, ui.npLyricTop, 0.01f)
            assertEquals(n, r.lyricBottom, ui.npLyricBottom, 0.01f)
            assertEquals(n, r.bar, ui.npBarY, 0.01f)
            assertEquals(n, r.bar - Tok.npBarGap, ui.npArtTop + ui.npArt, 0.01f)
            assertEquals(n, r.times, ui.npTimesBaseline, 0.01f)
            assertEquals(n, r.artBottom, ui.npProgressY, 0.01f)
            assertEquals(n, r.band, ui.npSeekBottom, 0.01f)
        }
    }

    @Test
    fun atH0TheStackIsTheR1ColumnWithTheRingsUnderThePlayRingTop() {
        val ui = Ui(3f, 320f, 457.8294f, Shape.SN)
        val r1 = TokBoard.r1
        val d = ui.npTitleBaseline - r1.npTitleY
        assertEquals(r1.npAlbumY + d, ui.npAlbumBaseline, 0.5f)
        assertEquals(r1.npArtTop + d, ui.npArtTop, 0.5f)
        assertEquals(r1.npArt, ui.npArt, 0.5f)
        assertEquals(r1.npArtistY + d, ui.npArtistBaseline, 0.5f)
        assertEquals((ui.npPlayRing - LayoutTok.npRing) / 2, ui.npRingY - (r1.npRingY + d), 0.01f)
        assertEquals(17.15f, ui.npRingY - (r1.npRingY + d), 0.01f)
    }

    @Test
    fun shortNarrowMeetsPortraitAtH480RowByRow() {
        val p = Ui(3f, 320f, 480f, Shape.P)
        val sn = Ui(3f, 320f, 479.9f, Shape.SN)
        val pick = listOf<(Ui) -> Float>({ it.npArtistBaseline }, { it.npAlbumBaseline }, { it.npArtTop }, { it.npArt }, { it.npTitleBaseline }, { it.npNextBaseline }, { it.npRingY }, { it.npLyricBottom })
        pick.forEachIndexed { i, f -> assertEquals("$i", f(p), f(sn), 0.1f) }
        assertEquals(85.21f, p.npLyricTop, 0.01f)
        assertEquals(86f, sn.npLyricTop, 0.01f)
    }

    @Test
    fun theArtStops149AndAShortWindowIsTheV1Column() {
        assertEquals(TokBoard.r1.npArt, Ui(3f, 320f, 443.7f, Shape.SN).npArt, 0.01f)
        assertEquals(TokBoard.r1.npArt, Ui(3f, 320f, 435.8f, Shape.SN).npArt, 0.01f)
        assertEquals(TokBoard.r1.npArt - 0.1f, Ui(3f, 320f, 435.7f, Shape.SN).npArt, 0.01f)
        val v1 = Ui(3f, 320f, 320f, Shape.SN)
        assertEquals(LayoutTok.npArtTop, v1.npArtTop, 0.3f)
        assertEquals(LayoutTok.npArt, v1.npArt, 0.3f)
        assertEquals(LayoutTok.npAlbumY, v1.npAlbumBaseline, 0.3f)
        assertEquals(LayoutTok.npTitleY, v1.npTitleBaseline, 0.3f)
        assertEquals(LayoutTok.npRingY, v1.npRingY, 0.3f)
        assertEquals(LayoutTok.npArtistY, v1.npArtistBaseline, 1f)
    }

    @Test
    fun theLyricsBandIs298UnlessShortNarrowClampsIt() {
        for (r in rows) {
            val ui = r.ui
            if (ui.npLyricTop > 86f || ui.shape == Shape.P) assertEquals(r.name, 298f, ui.npLyricBottom - ui.npLyricTop, 0.01f)
        }
    }
}
