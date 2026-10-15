package net.seilent.funecomp

import net.seilent.funecomp.proto.Band
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.ui.Scale
import net.seilent.funecomp.ui.Ui
import net.seilent.funecomp.ui.balanceText
import net.seilent.funecomp.ui.bandText
import net.seilent.funecomp.ui.freqText
import net.seilent.funecomp.ui.gridHz
import net.seilent.funecomp.ui.npArt
import net.seilent.funecomp.ui.npProgressY
import net.seilent.funecomp.ui.npTitleBaseline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTest {
    private val density = 420f / 160f

    @Test
    fun microdapLawOnTheFlip() {
        val ui = Ui.of(1080f, 2520f, density, Scale.MICRODAP)
        assertEquals(1080f / 272f, ui.u, 1e-4f)
        assertEquals(272f, ui.w, 1e-3f)
        assertFalse(ui.landscape)
        assertFalse(ui.short)
        assertEquals(168f, ui.npArt, 1e-3f)
        assertEquals(ui.h - 156f, ui.npProgressY, 1e-3f)
    }

    @Test
    fun fitWidthAndFixedDp() {
        assertEquals(240f, Ui.of(1080f, 2520f, density, Scale.FIT_WIDTH).w, 1e-3f)
        val fixed = Ui.of(1080f, 2520f, density, Scale.FIXED_DP)
        assertEquals(density * 240f / 295f, fixed.u, 1e-4f)
        assertEquals(480f, Ui.of(2520f, 1080f, density, Scale.FIT_WIDTH).w, 1e-3f)
    }

    @Test
    fun shortCanvasTakesTheFuneosRows() {
        val cover = Ui.of(948f, 1048f, density, Scale.FIT_WIDTH)
        assertTrue(cover.short)
        assertEquals(cover.h - 82f, cover.npTitleBaseline, 1e-3f)
        val land = Ui.of(2520f, 1080f, density, Scale.MICRODAP)
        assertTrue(land.landscape)
        assertEquals(minOf(168f, land.h - 106f), land.npArt, 1e-3f)
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
