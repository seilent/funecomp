package net.seilent.funecomp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.state.Section
import kotlin.math.max
import kotlin.math.min

private const val LANDSCAPE_COLS = 9
const val JUMP_EXIT = -1
const val JUMP_NONE = -2

class JumpLayout(val cols: Int, val tile: Float, val top: Float) {
    fun x(slot: Int) = Tok.edge + (slot % cols) * (tile + Tok.jumpGap)

    fun y(slot: Int) = top + (slot / cols) * (tile + Tok.jumpGap)

    fun hit(x: Float, y: Float, avail: Set<Int>): Int {
        val exit = Tok.jumpExitCells.toInt()
        for (slot in 0 until Tok.jumpCells.toInt() + exit) {
            if (slot in 1 until exit) continue
            val x0 = x(slot)
            val y0 = y(slot)
            if (x >= x0 && x < x0 + tile && y >= y0 && y < y0 + tile) {
                if (slot == 0) return JUMP_EXIT
                val i = slot - exit
                return if (i in avail) i else JUMP_NONE
            }
        }
        return JUMP_NONE
    }

    companion object {
        fun of(ui: Ui): JumpLayout {
            val cols = if (ui.landscape) LANDSCAPE_COLS else Tok.jumpCols.toInt()
            val slots = Tok.jumpCells.toInt() + Tok.jumpExitCells.toInt()
            val rows = (slots + cols - 1) / cols
            val gap = Tok.jumpGap
            val tile = min((ui.w - 2 * Tok.edge - (cols - 1) * gap) / cols, (ui.h - 2 * Tok.edge - (rows - 1) * gap) / rows)
            return JumpLayout(cols, tile, max(Tok.edge, (ui.h - rows * tile - (rows - 1) * gap) / 2))
        }
    }
}

@Composable
fun JumpGrid(avail: Set<Int>, focus: Int, open: Boolean, onJump: (Int) -> Unit, onClose: () -> Unit) {
    val ui = LocalUi.current
    val g = JumpLayout.of(ui)
    val tile = ui.px(g.tile)
    val shown by rememberUpdatedState(open)
    Box(
        Modifier.fillMaxSize().layout { m, c ->
            val p = m.measure(c)
            layout(p.width, p.height) { if (shown) p.place(0, 0) }
        }.then(if (open) Modifier.testTag("jump") else Modifier.clearAndSetSemantics {}),
    ) {
        Box(Modifier.fillMaxSize().bleed(LocalBleed.current).background(Color(Tok.color_bg)).press({}))
        Box(
            Modifier.fillMaxSize().press({ o ->
                when (val i = g.hit(o.x / ui.u, o.y / ui.u, avail)) {
                    JUMP_EXIT -> onClose()
                    JUMP_NONE -> Unit
                    else -> onJump(i)
                }
            }),
        ) {
            RoleText("exit", Roles.overlayLabel, Modifier.at(ui.px(Tok.edge), ui.px(g.y(0)) + tile / 2, ay = 0.5f).then(if (open) Modifier.testTag("jump.exit") else Modifier))
            for (i in 0 until Tok.jumpCells.toInt()) {
                val slot = i + Tok.jumpExitCells.toInt()
                val on = i == focus
                Box(
                    Modifier.at(ui.px(g.x(slot)), ui.px(g.y(slot))).size(tile.dp())
                        .border(ui.px(if (on) Tok.letterTileBorderFocused else Tok.letterTileBorder).dp(), white(if (on) Tok.alpha_full else Tok.alpha_low))
                        .clipToBounds().then(if (open) Modifier.semantics { selected = on }.testTag("jump.$i") else Modifier),
                ) {
                    RoleText(
                        Section.letter(i),
                        Roles.letterGlyph,
                        Modifier.atBaseline(tile - ui.px(Tok.letterInsetX), tile - ui.px(Tok.letterInsetBaseline), ax = 1f),
                        alpha = if (i in avail) Roles.letterGlyph.alpha else Tok.alpha_low,
                    )
                }
            }
        }
    }
}
