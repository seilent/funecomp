package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.state.EqSlots
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.Held
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Roles
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.Ui
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class PressedStepTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var c: SimControl
    private lateinit var shell: Shell

    private fun start(pages: List<Page>, board: String = "ap80", w: Int = 411, h: Int = 960, prep: (SimControl) -> Unit = {}) {
        c = SimControl(board)
        prep(c)
        shell = Shell(Nav(pages))
        rule.mainClock.autoAdvance = false
        rule.setUptimeContent {
            Box(Modifier.requiredSize(w.dp, h.dp)) { App(c.device, LinkView(Phase.LIVE, board.uppercase(), everLive = true), c, shell) }
        }
        settle(1000)
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun frame() {
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }

    private fun slop(): Float {
        val size = rule.onRoot().fetchSemanticsNode().size
        return Ui.of(size.width.toFloat(), size.height.toFloat()).px(Tok.touchSlop)
    }

    private fun alpha(n: SemanticsNode): Float {
        val out = ArrayList<TextLayoutResult>()
        n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(out)
        return out.single().layoutInput.style.color.alpha
    }

    private fun texts(tag: String) = rule.onAllNodes(hasAnyAncestor(hasTestTag(tag)) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes()

    private fun tagged(tag: String) = rule.onAllNodes(hasTestTag(tag) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes().single()

    private fun word(item: String) = texts("home.$item").single { it.config[SemanticsProperties.Text].joinToString("") == item }

    private fun title(row: String) = texts(row).single { it.config.getOrNull(SemanticsProperties.TestTag) == "settings.title" }

    private fun settingsRows() = rule.onAllNodes(SemanticsMatcher("set row") { n -> n.config.getOrNull(SemanticsProperties.TestTag).let { it != null && it.startsWith("set.") && it != "set.confirm" } }, useUnmergedTree = true)
        .fetchSemanticsNodes().sortedBy { it.positionInRoot.y }.map { it.config[SemanticsProperties.TestTag] }

    private fun litThrough(name: String, ms: Long, read: () -> Float?) {
        val from = rule.mainClock.currentTime
        while (rule.mainClock.currentTime < from + ms) {
            frame()
            val a = read() ?: return
            assertEquals("$name +${rule.mainClock.currentTime - from}", Tok.alpha_full, a, 0.005f)
        }
    }

    @Test
    fun aHeaderWordStepsAtTheDownRestsPastTheSlopAndHoldsThroughItsPop() {
        start(listOf(Page.Home, Page.Music))
        assertEquals("at rest", Roles.pageHeader.alpha, alpha(tagged("header.word")), 0.005f)
        rule.onNodeWithTag("header").performTouchInput { down(center) }
        frame()
        assertEquals("the frame after the down", Tok.alpha_full, alpha(tagged("header.word")), 0.005f)
        rule.onNodeWithTag("header").performTouchInput { moveBy(Offset(0f, slop() + 10f)) }
        frame()
        assertEquals("past the slop", Roles.pageHeader.alpha, alpha(tagged("header.word")), 0.005f)
        rule.onNodeWithTag("header").performTouchInput { up() }
        settle(500)
        assertEquals(listOf(Page.Home, Page.Music), shell.nav.stack.toList())
        rule.onNodeWithTag("header").performTouchInput { down(center) }
        frame()
        rule.onNodeWithTag("header").performTouchInput { up() }
        litThrough("through the pop", Held.Nav.motion_turnstileOut.toLong() - FRAME) { rule.onAllNodes(hasTestTag("header.word"), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.let { alpha(it) } }
        settle(1000)
        assertEquals(listOf(Page.Home), shell.nav.stack.toList())
    }

    @Test
    fun anInactivePivotStepsAndHoldsThroughTheStripMove() {
        start(listOf(Page.Home, Page.Music))
        val label = rule.pivotNodes().map { it.config[SemanticsProperties.TestTag] }.first { rule.activePivot("music") != it.removePrefix("pivot.") }
        assertEquals("at rest", Roles.pivotInactive.alpha, alpha(tagged(label)), 0.005f)
        rule.onNodeWithTag(label, useUnmergedTree = true).performTouchInput { down(center) }
        frame()
        assertEquals("the frame after the down", Tok.alpha_full, alpha(tagged(label)), 0.005f)
        rule.onNodeWithTag(label, useUnmergedTree = true).performTouchInput { up() }
        litThrough("through the strip move", STRIP_READ) { alpha(tagged(label)) }
        settle(1000)
    }

    @Test
    fun aSettingsTitleStepsAtOnceRestsAtTheCancelAndHoldsThroughTheFocusFadeOfItsTap() {
        start(listOf(Page.Home, Page.Settings))
        val row = settingsRows()[1]
        assertEquals("at rest", Roles.pageRowTitle.dim, alpha(title(row)), 0.005f)
        rule.onNodeWithTag(row, useUnmergedTree = true).performTouchInput { down(center) }
        frame()
        assertEquals("the frame after the down", Tok.alpha_full, alpha(title(row)), 0.005f)
        settle(Tok.motion_focusAlpha.toLong() / 2)
        assertEquals("not the lerp at +100", Tok.alpha_full, alpha(title(row)), 0.005f)
        rule.onNodeWithTag(row, useUnmergedTree = true).performTouchInput { cancel() }
        frame()
        assertEquals("at rest at the cancel", Roles.pageRowTitle.dim, alpha(title(row)), 0.005f)
        settle(500)
        rule.onNodeWithTag(row, useUnmergedTree = true).performTouchInput { down(center) }
        frame()
        rule.onNodeWithTag(row, useUnmergedTree = true).performTouchInput { up() }
        litThrough("through the focus fade of its tap", Tok.motion_focusAlpha.toLong() + FRAME) { alpha(title(row)) }
        settle(1000)
    }

    @Test
    @Config(qualifiers = "w960dp-h600dp-420dpi")
    fun aFlingCatchLeavesAHomeWordsHoldToItsEnd() {
        start(listOf(Page.Home), w = 960, h = 600)
        val item = rule.onNodeWithTag("home.connection").fetchSemanticsNode().boundsInRoot
        val step = slop() / 2
        val t0 = rule.mainClock.currentTime
        rule.onRoot().performTouchInput {
            down(item.center)
            repeat(FLING_MOVES) { moveBy(Offset(0f, -step), MOVE_MS) }
            up()
        }
        until(t0 + CATCH_AT)
        rule.onRoot().performTouchInput { down(Offset(item.center.x, item.center.y)) }
        until(t0 + CATCH_AT + FRAME * 2)
        assertEquals("held past the catch", Tok.alpha_full, alpha(word("connection")), 0.005f)
        rule.onRoot().performTouchInput { cancel() }
        settle(1000)
    }

    private fun until(at: Long) = settle((at - rule.mainClock.currentTime).coerceAtLeast(0))

    @Test
    fun aHomeWordWhoseTapPushesDrawsLitThroughTheOutLeg() {
        start(listOf(Page.Home))
        rule.onNodeWithTag("home.music").performTouchInput { down(center) }
        settle(FRAME * 3)
        rule.onNodeWithTag("home.music").performTouchInput { up() }
        assertEquals(listOf(Page.Home, Page.Music), shell.nav.stack.toList())
        litThrough("home word on the leaving page", Held.Nav.motion_turnstileOut.toLong() - FRAME) { texts("home.music").firstOrNull { it.config[SemanticsProperties.Text].joinToString("") == "music" }?.let { alpha(it) } }
        settle(1000)
    }

    @Test
    fun aSettingsRowWhoseTapPushesDrawsLitThroughTheOutLeg() {
        start(listOf(Page.Home, Page.Settings), board = "v1")
        rule.walkPivots(c.device.schema.rows, "effects") { settle(500) }
        assertTrue(rule.onAllNodes(hasTestTag("set.eq"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithTag("set.eq", useUnmergedTree = true).performTouchInput { down(center) }
        settle(FRAME * 3)
        rule.onNodeWithTag("set.eq", useUnmergedTree = true).performTouchInput { up() }
        assertTrue("pushes the EQ page", shell.nav.top is Page.Eq)
        litThrough("settings title on the leaving page", Held.Nav.motion_turnstileOut.toLong() - FRAME) { texts("set.eq").firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == "settings.title" }?.let { alpha(it) } }
        settle(1000)
    }

    @Test
    fun aSheetItemHoldsThroughTheClose() {
        start(listOf(Page.Home, Page.Settings))
        shell.sheet.open("confirm", listOf("apply", "cancel")) {}
        settle(1000)
        val item = "sheet.item.1"
        assertEquals("at rest", Roles.dialogItem.dim, alpha(texts(item).first()), 0.005f)
        rule.onNodeWithTag(item, useUnmergedTree = true).performTouchInput { down(center) }
        frame()
        assertEquals("the frame after the down", Tok.alpha_full, alpha(texts(item).first()), 0.005f)
        rule.onNodeWithTag(item, useUnmergedTree = true).performTouchInput { up() }
        litThrough("through the close", Tok.motion_sheet.toLong()) { texts(item).firstOrNull()?.let { alpha(it) } }
        settle(1000)
    }

    @Test
    fun theGroupSheetsPlayItemHoldsThroughTheCloseItsPlayCauses() {
        start(listOf(Page.Home, Page.Music))
        rule.pivotTo("music", "artists") { settle(it) }
        rule.onNodeWithTag("row.0").performTouchInput { longClick() }
        settle(1000)
        assertEquals("play", shell.sheet.items.first())
        val item = "sheet.item.0"
        rule.onNodeWithTag(item, useUnmergedTree = true).performTouchInput { down(center) }
        frame()
        rule.onNodeWithTag(item, useUnmergedTree = true).performTouchInput { up() }
        litThrough("play through the close", Tok.motion_sheet.toLong()) { texts(item).firstOrNull()?.let { alpha(it) } }
        settle(1000)
        assertEquals(Page.NowPlaying, shell.nav.top)
    }

    @Test
    fun aTappableStateLineSteps() {
        start(listOf(Page.Home, Page.Settings, Page.Eq(Enums.EqOutput.wired), Page.AutoEq(EqSlots.HEADPHONES))) { it.autoEq = null }
        settle(500)
        val line = texts("aeq.state").first()
        assertEquals("at rest", Tok.alpha_low, alpha(line), 0.005f)
        rule.onNodeWithTag("aeq.state", useUnmergedTree = true).performTouchInput { down(center) }
        frame()
        assertEquals("the frame after the down", Tok.alpha_full, alpha(texts("aeq.state").first()), 0.005f)
        rule.onNodeWithTag("aeq.state", useUnmergedTree = true).performTouchInput { cancel() }
        frame()
        assertEquals("at rest at the cancel", Tok.alpha_low, alpha(texts("aeq.state").first()), 0.005f)
    }

    private companion object {
        const val FRAME = 16L
        const val MOVE_MS = 4L
        const val FLING_MOVES = 12
        const val CATCH_AT = 100L
        const val STRIP_READ = 400L
    }
}
