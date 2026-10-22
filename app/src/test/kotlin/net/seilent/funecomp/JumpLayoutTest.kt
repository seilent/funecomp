package net.seilent.funecomp

import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.JUMP_EXIT
import net.seilent.funecomp.ui.JUMP_NONE
import net.seilent.funecomp.ui.JUMP_SEARCH
import net.seilent.funecomp.ui.JumpLayout
import net.seilent.funecomp.ui.Ui
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JumpLayoutTest {
    private class Row(val name: String, val ui: Ui, val cols: Int, val tile: Float, val rows: Int, val content: Float, val limit: Float)

    private val dap = Ui(1f, Tok.canvasW, Tok.canvasH, false)

    private val all = (0 until Tok.jumpCells.toInt()).toSet()

    private val table = listOf(
        Row("dap", dap, 4, 53f, 10, 598f, 278f),
        Row("360x640", Ui.of(945f, 1532f), 4, 67.0f, 10, 738.2f, 258.2f),
        Row("411x960", Ui.of(1078.875f, 2372f), 4, 61.0f, 10, 678.0f, 80.0f),
        Row("600x960", Ui.of(1575f, 2372f), 4, 72.7f, 10, 794.8f, 314.8f),
        Row("960x600", Ui.of(2411f, 1427f), 8, 54.5f, 5, 320.5f, 36.4f),
        Row("flip960x411", Ui.of(2411f, 930.875f), 12, 53.7f, 4, 258.8f, 0f),
        Row("1280x800", Ui.of(3251f, 1952f), 8, 54.5f, 5, 320.5f, 32.3f),
        Row("cover361x399", Ui.of(947.625f, 748.375f), 8, 54.5f, 5, 320.5f, 0f),
    )

    private fun center(g: JumpLayout, slot: Int) = (g.x(slot) + g.tile / 2) to (g.y(slot) + g.tile / 2)

    @Test
    fun theDapCanvasIsTheDapGrid() {
        val g = JumpLayout.of(dap)
        assertEquals(listOf(8f, 65f, 122f, 179f), (0 until 4).map { g.x(it) })
        assertEquals(53f, g.tile, 0f)
        assertEquals(278f, g.limit(Tok.canvasH), 0f)
        for (letter in 0 until 18) assertEquals("$letter", 0f, g.offsetFor(JumpLayout.slot(letter), Tok.canvasH), 0f)
        for (letter in 18..21) assertEquals("$letter", 50f, g.offsetFor(JumpLayout.slot(letter), Tok.canvasH), 0f)
        assertEquals(278f, g.offsetFor(JumpLayout.slot(37), Tok.canvasH), 0f)
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
            assertEquals(r.name, r.ui.w - 8f, g.x(g.cols - 1) + g.tile, 0.001f)
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
            val (sx, sy) = center(g, 1)
            assertEquals(JUMP_SEARCH, g.hit(sx, sy - offset, offset, all))
            val y = g.y(9) - offset
            assertEquals(7, g.hit(g.x(9) - 2f, y, offset, all))
            assertEquals(7, g.hit(g.x(9) + g.tile + 1.99f, y, offset, all))
            assertEquals(8, g.hit(g.x(9) + g.tile + 2f, y, offset, all))
            assertEquals(3, g.hit(g.x(5), g.y(5) - 2f - offset, offset, all))
            assertEquals(3, g.hit(g.x(9), g.y(9) - 2.01f - offset, offset, all))
            assertEquals(JUMP_NONE, g.hit(5.99f, y, offset, all))
            assertEquals(JUMP_NONE, g.hit(Tok.canvasW - 5.99f, y, offset, all))
            assertEquals(JUMP_NONE, g.hit(g.x(0), Tok.jumpTop - 2.01f - offset, offset, all))
            assertEquals(JUMP_NONE, g.hit(g.x(0), g.content - offset, offset, all))
            assertEquals(JUMP_NONE, g.hit(g.x(9), y, offset, all - 7))
        }
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
