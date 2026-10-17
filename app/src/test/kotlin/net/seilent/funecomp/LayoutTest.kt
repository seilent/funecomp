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
import net.seilent.funecomp.ui.npAlbumBaseline
import net.seilent.funecomp.ui.npArt
import net.seilent.funecomp.ui.npArtTop
import net.seilent.funecomp.ui.npArtistBaseline
import net.seilent.funecomp.ui.npProgressY
import net.seilent.funecomp.ui.npTitleBaseline
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
        assertEquals(ui.h - 156f, ui.npProgressY, 1e-3f)
        assertEquals(ui.npProgressY, ui.npArtTop + ui.npArt, 1e-3f)
        assertEquals(ui.npArtTop - 14f, ui.npAlbumBaseline, 1e-3f)
        assertEquals(ui.npAlbumBaseline - 24f, ui.npArtistBaseline, 1e-3f)
        assertEquals(ui.h - 118f, ui.npTitleBaseline, 1e-3f)
        for (album in listOf(ui, Ui.of(272f, 480f), Ui.of(360f, 640f))) {
            assertEquals(117f, album.albumArtistBaseline, 1e-3f)
            assertEquals(141f, album.albumTitleBaseline, 1e-3f)
            assertEquals(155f, album.albumArtTop, 1e-3f)
        }
        val zune = Ui.of(272f, 480f)
        assertEquals(156f, zune.npArtTop, 1e-3f)
        assertEquals(118f, zune.npArtistBaseline, 1e-3f)
    }

    @Test
    fun landscapeTakesTheFuneosRows() {
        val land = Ui.of(2520f, 1080f)
        assertTrue(land.landscape)
        assertEquals(minOf(168f, land.h - 106f), land.npArt, 1e-3f)
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
