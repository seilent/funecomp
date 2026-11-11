package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.Ui
import net.seilent.funecomp.ui.debugPages
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class JumpExitSizeTest {
    @get:Rule
    val rule = createComposeRule()

    private val divider = SemanticsMatcher("aeq divider") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("aeq.div.") == true }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    @Test
    fun theJumpExitDrawsAtItsToken() {
        val c = SimControl("v1")
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, "V1", everLive = true), c, Shell(Nav(debugPages("autoeq")!!))) }
        }
        settle(1500)
        rule.onAllNodes(divider)[0].performClick()
        settle(500)
        val ui = rule.onRoot().fetchSemanticsNode().size.let { Ui.of(it.width.toFloat(), it.height.toFloat()) }
        val out = ArrayList<TextLayoutResult>()
        rule.onNodeWithTag("jump.exit", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(out)
        val drawn = with(rule.density) { out.single().layoutInput.style.fontSize.toPx() }
        assertEquals(ui.fontPx(Tok.type_jumpExit_size), drawn, 0.5f)
    }
}
