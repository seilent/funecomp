package net.seilent.funecomp.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.testTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.state.Section
import kotlin.math.max

const val JUMP_EXIT = -1
const val JUMP_NONE = -2
const val JUMP_SEARCH = -3
private const val SLOT_EXIT = 0
private const val SLOT_SEARCH = 1
private const val SEARCH_CENTER = 27f / 64f

class JumpLayout(val cols: Int, val tile: Float, val left: Float) {
    val pitch = tile + Tok.jumpGap
    val rows = (SLOTS + cols - 1) / cols
    val content = Tok.jumpTop + (rows - 1) * pitch + tile + Tok.edge

    fun limit(h: Float) = max(0f, content - h)

    fun x(slot: Int) = left + (slot % cols) * pitch

    fun y(slot: Int) = Tok.jumpTop + (slot / cols) * pitch

    fun offsetFor(slot: Int, h: Float): Float {
        val top = y(slot)
        val off = when {
            top < Tok.edge -> top - Tok.edge
            top + tile > h - Tok.edge -> top + tile - (h - Tok.edge)
            else -> 0f
        }
        return off.coerceIn(0f, limit(h))
    }

    fun hit(x: Float, y: Float, offset: Float, avail: Set<Int>): Int {
        val cx = x - (left - Tok.jumpGap / 2)
        val cy = y + offset - (Tok.jumpTop - Tok.jumpGap / 2)
        if (cx < 0 || cy < 0) return JUMP_NONE
        val col = (cx / pitch).toInt()
        val slot = (cy / pitch).toInt() * cols + col
        return when {
            col >= cols || slot >= SLOTS -> JUMP_NONE
            slot == SLOT_EXIT -> JUMP_EXIT
            slot == SLOT_SEARCH -> JUMP_SEARCH
            slot - EXIT_CELLS in avail -> slot - EXIT_CELLS
            else -> JUMP_NONE
        }
    }

    companion object {
        val EXIT_CELLS = Tok.jumpExitCells.toInt()
        val SLOTS = Tok.jumpCells.toInt() + EXIT_CELLS
        private val MARGIN = (Tok.canvasW - Tok.jumpCols * Tok.tileS - (Tok.jumpCols - 1) * Tok.jumpGap) / 2

        fun slot(letter: Int) = letter + EXIT_CELLS

        fun of(ui: Ui): JumpLayout {
            val span = ui.w - 2 * MARGIN
            val cols = if (ui.landscape) max(Tok.jumpCols.toInt(), ((span + Tok.jumpGap) / (Tok.tileS + Tok.jumpGap)).toInt()) else Tok.jumpCols.toInt()
            return JumpLayout(cols, (span - (cols - 1) * Tok.jumpGap) / cols, MARGIN)
        }
    }
}

private class JumpPress {
    var item by mutableIntStateOf(JUMP_NONE)
    val scale = Animatable(1f)
    private var down: Job? = null
    private var back: Job? = null

    fun down(scope: CoroutineScope, i: Int) {
        down?.cancel()
        back?.cancel()
        val again = item == i
        item = i
        down = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (!again) scale.snapTo(1f)
            scale.animateTo(Tok.motion_pressScale, tween(Tok.motion_press.toInt(), easing = LinearEasing))
        }
    }

    fun release(scope: CoroutineScope) {
        back?.cancel()
        back = scope.launch {
            scale.animateTo(1f, tween(Tok.motion_press.toInt(), easing = LinearEasing))
            item = JUMP_NONE
        }
    }

    fun drop(scope: CoroutineScope) {
        down?.cancel()
        back?.cancel()
        back = scope.launch {
            scale.snapTo(1f)
            item = JUMP_NONE
        }
    }
}

private class WhileNode(var shown: State<Boolean>, var props: SemanticsPropertyReceiver.() -> Unit) : Modifier.Node(), SemanticsModifierNode, ObserverModifierNode {
    override fun onAttach() = onObservedReadsChanged()

    override fun onObservedReadsChanged() {
        observeReads { shown.value }
        invalidateSemantics()
    }

    override fun SemanticsPropertyReceiver.applySemantics() {
        if (shown.value) props()
    }
}

private data class WhileElement(val shown: State<Boolean>, val props: SemanticsPropertyReceiver.() -> Unit) : ModifierNodeElement<WhileNode>() {
    override fun create() = WhileNode(shown, props)

    override fun update(node: WhileNode) {
        node.shown = shown
        node.props = props
        node.invalidateSemantics()
    }
}

private fun Modifier.semanticsWhile(shown: State<Boolean>, props: SemanticsPropertyReceiver.() -> Unit) = this then WhileElement(shown, props)

private fun Modifier.pressed(press: JumpPress, i: Int) = graphicsLayer {
    val s = if (press.item == i) press.scale.value else 1f
    scaleX = s
    scaleY = s
}

@Composable
fun JumpGrid(avail: Set<Int>, focus: Int, open: Boolean, scroll: ScrollState, onJump: (Int) -> Unit, onSearch: () -> Unit, onClose: () -> Unit) {
    val ui = LocalUi.current
    val g = JumpLayout.of(ui)
    val tile = ui.px(g.tile)
    val shown = rememberUpdatedState(open)
    val press = remember { JumpPress() }
    val scope = rememberCoroutineScope()
    val act = rememberUpdatedState { i: Int ->
        when {
            i == JUMP_EXIT -> onClose()
            i == JUMP_SEARCH -> onSearch()
            i in avail -> onJump(i)
        }
    }
    val hit by rememberUpdatedState { x: Float, y: Float -> g.hit(x / ui.u, y / ui.u, scroll.value / ui.u, avail) }
    Box(
        Modifier.fillMaxSize().layout { m, c ->
            val p = m.measure(c)
            layout(p.width, p.height) { if (shown.value) p.place(0, 0) }
        }.then(if (open) Modifier else Modifier.clearAndSetSemantics {}),
    ) {
        Box(Modifier.fillMaxSize().bleed(LocalBleed.current).background(Color(Tok.color_bg)).press({}))
        Box(
            Modifier.fillMaxSize().pointerInput(scroll) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var live = if (scroll.isScrollInProgress) JUMP_NONE else hit(first.position.x, first.position.y)
                    val pressable = live != JUMP_EXIT && live != JUMP_NONE
                    if (pressable) press.down(scope, live)
                    while (true) {
                        val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == first.id }
                        if (c == null || !c.pressed) {
                            val tap = c != null && !c.isConsumed && shown.value
                            if (pressable && live != JUMP_NONE) press.drop(scope)
                            if (tap && live != JUMP_NONE) act.value(live)
                            break
                        }
                        if (live != JUMP_NONE && (c.position - first.position).getDistance() > viewConfiguration.touchSlop) {
                            if (pressable) press.release(scope)
                            live = JUMP_NONE
                        }
                    }
                }
            }.verticalScroll(scroll, flingBehavior = rememberFling()).then(if (open) Modifier.testTag("jump") else Modifier),
        ) {
            Box(Modifier.size(ui.px(ui.w).dp(), ui.px(g.content).dp())) {
                RoleText(
                    "exit",
                    Roles.jumpExit,
                    Modifier.at(ui.px(Tok.edge), ui.px(g.y(SLOT_EXIT)) + tile / 2, ay = 0.5f)
                        .semanticsWhile(shown) { onClick("exit") { act.value(JUMP_EXIT); true }; testTag = "jump.exit" },
                )
                JumpSearch(ui.px(g.x(SLOT_SEARCH)), ui.px(g.y(SLOT_SEARCH)), tile, press, act, shown)
                for (i in 0 until Tok.jumpCells.toInt()) {
                    val slot = JumpLayout.slot(i)
                    JumpLetter(i, ui.px(g.x(slot)), ui.px(g.y(slot)), tile, i == focus, i in avail, press, act, shown)
                }
            }
        }
    }
}

@Composable
private fun JumpSearch(x: Float, y: Float, tile: Float, press: JumpPress, act: State<(Int) -> Unit>, shown: State<Boolean>) {
    val ui = LocalUi.current
    Box(
        Modifier.at(x, y).size(tile.dp()).pressed(press, JUMP_SEARCH)
            .border(ui.px(Tok.letterTileBorder).dp(), white(Tok.alpha_low))
            .clipToBounds().semanticsWhile(shown) { onClick("search") { act.value(JUMP_SEARCH); true }; testTag = "jump.search" },
    ) {
        val c = ui.px(Tok.jumpSearchGlyph) * SEARCH_CENTER
        val at = Modifier.at(tile - ui.px(Tok.letterInsetX) - c, tile - ui.px(Tok.letterInsetBaseline) - c, 0.5f, 0.5f)
        Glyph(GlyphName.SEARCH_RING, Tok.jumpSearchGlyph, white(Roles.letterGlyph.alpha), at)
        Glyph(GlyphName.SEARCH_HANDLE, Tok.jumpSearchGlyph, white(Roles.letterGlyph.alpha), at)
    }
}

@Composable
private fun JumpLetter(i: Int, x: Float, y: Float, tile: Float, on: Boolean, has: Boolean, press: JumpPress, act: State<(Int) -> Unit>, shown: State<Boolean>) {
    val ui = LocalUi.current
    val letter = Section.letter(i)
    Box(
        Modifier.at(x, y).size(tile.dp()).pressed(press, i)
            .then(if (has) Modifier.border(ui.px(if (on) Tok.letterTileBorderFocused else Tok.letterTileBorder).dp(), white(if (on) Tok.alpha_full else Tok.alpha_low)) else Modifier)
            .clipToBounds().semanticsWhile(shown) {
                selected = on
                if (has) onClick(letter) { act.value(i); true }
                testTag = "jump.$i"
            },
    ) {
        RoleText(
            letter,
            Roles.letterGlyph,
            Modifier.atBaseline(tile - ui.px(Tok.letterInsetX), tile - ui.px(Tok.letterInsetBaseline), ax = 1f),
            alpha = if (has) Roles.letterGlyph.alpha else Tok.alpha_low,
        )
    }
}
