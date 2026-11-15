package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.Ui
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class ReadOnlyRowsTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var c: SimControl
    private lateinit var shell: Shell

    private fun start() {
        c = SimControl("v1")
        shell = Shell(Nav(listOf(Page.Home, Page.Settings)))
        rule.mainClock.autoAdvance = false
        rule.setUptimeContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, "V1", everLive = true), c, shell) }
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

    private fun walk(to: String, from: String? = null) = rule.walkPivots(c.device.schema.rows, to, from) { settle(1000) }

    private fun shown(tag: String) {
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("rows").performScrollToNode(hasTestTag(tag))
        rule.mainClock.autoAdvance = false
        settle(600)
    }

    private fun tap(tag: String) {
        shown(tag)
        rule.onNodeWithTag(tag).performClick()
        settle(600)
    }

    private fun u() = rule.onNodeWithTag("hub.settings").fetchSemanticsNode().size.let { Ui.of(it.width.toFloat(), it.height.toFloat()).u }

    private fun title(tag: String): TextLayoutResult {
        val n = rule.onAllNodes(hasAnyAncestor(hasTestTag(tag)) and SemanticsMatcher("title") { it.config.getOrNull(SemanticsProperties.TestTag) == "settings.title" }, useUnmergedTree = true)
            .fetchSemanticsNodes().single()
        val out = ArrayList<TextLayoutResult>()
        n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(out)
        return out.single()
    }

    private fun assertInfoTitle(tag: String) {
        val style = title(tag).layoutInput.style
        with(rule.density) { assertEquals(tag, (Tok.type_settingsValue_size * u()).roundToInt().toFloat(), style.fontSize.toPx(), 0.5f) }
        assertEquals(tag, FontWeight.Light, style.fontWeight)
    }

    private fun assertNoStepAtTheDown(tag: String) {
        shown(tag)
        val rest = title(tag).layoutInput.style.color.alpha
        rule.onNodeWithTag(tag, useUnmergedTree = true).performTouchInput { down(center) }
        frame()
        assertEquals("$tag the frame after the down", rest, title(tag).layoutInput.style.color.alpha, 0.005f)
        rule.onNodeWithTag(tag, useUnmergedTree = true).performTouchInput { cancel() }
        settle(600)
    }

    private fun writes() = c.sent.filter { it == "SET" || it == "DRAFT" || it == "ACTION" }

    @Test
    fun phoneAppTakesTheInfoLookAndNoStep() {
        start()
        walk("system")
        assertInfoTitle("set.companion")
        assertNoStepAtTheDown("set.companion")
    }

    @Test
    fun theSizeDraftTakesTheInfoLookAndNoStep() {
        start()
        walk("display")
        assertInfoTitle("set.size")
        assertNoStepAtTheDown("set.size")
    }

    @Test
    fun aTapOnAReadOnlyRowSendsNothingAndOpensNoSheet() {
        start()
        walk("display")
        tap("set.size")
        walk("system", "display")
        tap("set.companion")
        assertEquals(emptyList<String>(), writes())
        assertFalse(shell.sheet.shown)
    }

    @Test
    fun eqCorrectionEqAndAboutKeepTheirPageAndSheet() {
        start()
        walk("effects")
        tap("set.eq")
        assertTrue(shell.nav.top is Page.Eq)
        rule.runOnIdle { shell.nav.pop() }
        settle(1000)
        tap("set.eqEar")
        assertTrue(shell.nav.top is Page.Eq)
        rule.runOnIdle { shell.nav.pop() }
        settle(1000)
        walk("system", "effects")
        tap("set.about")
        assertTrue(shell.sheet.shown)
    }
}
