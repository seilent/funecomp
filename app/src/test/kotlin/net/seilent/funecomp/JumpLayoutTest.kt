package net.seilent.funecomp

import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.state.Section
import net.seilent.funecomp.ui.JUMP_EXIT
import net.seilent.funecomp.ui.JUMP_NONE
import net.seilent.funecomp.ui.JUMP_SEARCH
import net.seilent.funecomp.ui.JumpLayout
import net.seilent.funecomp.ui.LayoutTok
import net.seilent.funecomp.ui.Roles
import net.seilent.funecomp.ui.Shape
import net.seilent.funecomp.ui.Ui
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JumpLayoutTest {
    private class Row(val name: String, val ui: Ui, val cols: Int, val tile: Float, val rows: Int, val content: Float, val limit: Float)

    private val dap = Ui(1f, Tok.canvasW, LayoutTok.canvasH, Shape.SN)

    private val all = (0 until Tok.jumpCells.toInt()).toSet()

    private val margin = (Tok.canvasW - Tok.jumpCols * Tok.tileS - (Tok.jumpCols - 1) * Tok.jumpGap) / 2

    private val half = Tok.jumpGap / 2

    private val table = listOf(
        Row("dap", dap, 4, 53f, 10, 603.7f, 283.7f),
        Row("360x640", Ui.of(945f, 1532f), 4, 67.02f, 10, 743.91f, 263.91f),
        Row("411x960", Ui.of(1078.875f, 2372f), 4, 61.0f, 10, 683.7f, 85.68f),
        Row("600x960", Ui.of(1575f, 2372f), 4, 72.68f, 10, 800.5f, 320.5f),
        Row("960x600", Ui.of(2411f, 1427f), 8, 54.38f, 5, 324.4f, 40.3f),
        Row("flip960x411", Ui.of(2411f, 930.875f), 12, 53.55f, 4, 262.45f, 0f),
        Row("1280x800", Ui.of(3251f, 1952f), 8, 54.38f, 5, 324.4f, 36.19f),
        Row("cover474x419", Ui.of(1244f, 1101f), 6, 54.18f, 7, 440.24f, 120.24f),
        Row("sn411x438", Ui.of(1078.875f, 1149.75f), 4, 73.0f, 10, 803.7f, 462.68f),
        Row("sw320", Ui(3f, 320f, 320f, Shape.SW), 5, 57.55f, 8, 525.64f, 205.64f),
        Row("sw1001x1000", Ui.of(1001f, 1000f), 5, 57.62f, 8, 526.15f, 206.15f),
    )

    private fun center(g: JumpLayout, slot: Int) = (g.x(slot) + g.tile / 2) to (g.y(slot) + g.tile / 2)

    @Test
    fun theDapCanvasIsTheDapGrid() {
        val g = JumpLayout.of(dap)
        assertEquals(7.64f, margin, 0.001f)
        listOf(7.64f, 64.88f, 122.12f, 179.36f).forEachIndexed { i, x -> assertEquals("$i", x, g.x(i), 0.01f) }
        assertEquals(53f, g.tile, 0.001f)
        assertEquals(283.7f, g.limit(LayoutTok.canvasH), 0.01f)
        for (letter in 0 until 19) assertEquals("$letter", 0f, g.offsetFor(JumpLayout.slot(letter), LayoutTok.canvasH), 0f)
        for (letter in 19..22) assertEquals("$letter", 54.74f, g.offsetFor(JumpLayout.slot(letter), LayoutTok.canvasH), 0.01f)
        assertEquals(283.7f, g.offsetFor(JumpLayout.slot(37), LayoutTok.canvasH), 0.01f)
    }

    @Test
    fun everySizeMatchesThePlanTable() {
        for (r in table) {
            val g = JumpLayout.of(r.ui)
            assertEquals(r.name, r.cols, g.cols)
            assertEquals(r.name, r.tile, g.tile, 0.05f)
            assertEquals(r.name, r.rows, g.rows)
            assertEquals(r.name, r.content, g.content, 0.05f)
            assertEquals(r.name, r.limit, g.limit(r.ui.h), 0.05f)
            assertEquals(r.name, r.ui.w - margin, g.x(g.cols - 1) + g.tile, 0.001f)
        }
    }

    @Test
    fun everyLetterOpensInsideTheBand() {
        for (r in table) {
            val g = JumpLayout.of(r.ui)
            for (letter in 0 until Tok.jumpCells.toInt()) {
                val slot = JumpLayout.slot(letter)
                val top = g.y(slot) - g.offsetFor(slot, r.ui.h)
                assertTrue("${r.name} $letter top $top", top >= Tok.edge - 0.001f)
                assertTrue("${r.name} $letter bottom ${top + g.tile}", top + g.tile <= r.ui.h - Tok.edge + 0.001f)
            }
        }
    }

    @Test
    fun theHitIsThePitchCell() {
        val g = JumpLayout.of(dap)
        for (offset in listOf(0f, 50f)) {
            val (ex, ey) = center(g, 0)
            assertEquals(JUMP_EXIT, g.hit(ex, ey - offset, offset, all))
            val (hx, hy) = center(g, 1)
            assertEquals(0, g.hit(hx, hy - offset, offset, all))
            val (zx, zy) = center(g, 27)
            assertEquals(26, g.hit(zx, zy - offset, offset, all))
            val (sx, sy) = center(g, 28)
            assertEquals(JUMP_SEARCH, g.hit(sx, sy - offset, offset, all))
            val (kx, ky) = center(g, 29)
            assertEquals(27, g.hit(kx, ky - offset, offset, all))
            val y = g.y(9) - offset
            assertEquals(8, g.hit(g.x(9) - half + 0.01f, y, offset, all))
            assertEquals(8, g.hit(g.x(9) + g.tile + half - 0.01f, y, offset, all))
            assertEquals(9, g.hit(g.x(9) + g.tile + half + 0.01f, y, offset, all))
            assertEquals(4, g.hit(g.x(5), g.y(5) - half + 0.01f - offset, offset, all))
            assertEquals(4, g.hit(g.x(9), g.y(9) - half - 0.01f - offset, offset, all))
            assertEquals(JUMP_NONE, g.hit(margin - half - 0.01f, y, offset, all))
            assertEquals(JUMP_NONE, g.hit(Tok.canvasW - margin + half + 0.01f, y, offset, all))
            assertEquals(JUMP_NONE, g.hit(g.x(0), Tok.jumpTop - half - 0.01f - offset, offset, all))
            assertEquals(JUMP_NONE, g.hit(g.x(0), g.content - offset, offset, all))
            assertEquals(JUMP_NONE, g.hit(g.x(9), y, offset, all - 8))
        }
    }

    @Test
    fun theSearchTileIsTheSlotAfterZ() {
        val z = 26
        assertEquals("z", Section.letter(z))
        assertEquals("あ", Section.letter(z + 1))
        assertEquals(1, JumpLayout.slot(0))
        assertEquals(27, JumpLayout.slot(z))
        assertEquals(29, JumpLayout.slot(z + 1))
        val slots = (0 until Tok.jumpCells.toInt()).map { JumpLayout.slot(it) }
        assertEquals((1 until JumpLayout.SLOTS).toSet() - 28, slots.toSet())
        val g = JumpLayout.of(dap)
        for (letter in 0 until Tok.jumpCells.toInt()) {
            val (x, y) = center(g, JumpLayout.slot(letter))
            assertEquals("$letter", letter, g.hit(x, y, 0f, all))
        }
        val (sx, sy) = center(g, 28)
        assertEquals(JUMP_SEARCH, g.hit(sx, sy, 0f, all))
    }

    @Test
    fun theExitIsElevenAtEverySize() {
        for (r in table) assertEquals(r.name, 11f * r.ui.u, r.ui.px(Roles.jumpExit.size), 0.0001f)
    }

    @Test
    fun thePartialLastRowHitsNothing() {
        val ui = Ui.of(2411f, 930.875f)
        val g = JumpLayout.of(ui)
        assertEquals(12, g.cols)
        assertEquals(37, g.hit(center(g, 39).first, center(g, 39).second, 0f, all))
        for (slot in 40..47) assertEquals("$slot", JUMP_NONE, g.hit(center(g, slot).first, center(g, slot).second, 0f, all))
    }
}
