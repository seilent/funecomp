package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.Ui
import net.seilent.funecomp.ui.homeItems
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
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class HomeLayoutTest {
    @get:Rule
    val rule = createComposeRule()

    private class Frame(val w: Int, val h: Int, val shell: Shell)

    private val c = SimControl("v1")
    private val link = LinkView(Phase.LIVE, "V1", everLive = true)
    private val names = homeItems(c.device, link).map { it.name }
    private var frame by mutableStateOf<Frame?>(null)
    private var shown = false

    private fun show(w: Int, h: Int): Shell {
        if (!shown) {
            shown = true
            rule.mainClock.autoAdvance = false
            rule.setContent {
                val f = frame ?: return@setContent
                key(f) { Box(Modifier.requiredSize(f.w.dp, f.h.dp)) { App(c.device, link, c, f.shell) } }
            }
        }
        val shell = Shell(Nav(listOf(Page.Home)))
        frame = Frame(w, h, shell)
        settle(1000)
        return shell
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun ui() = rule.onNodeWithTag("home").fetchSemanticsNode().size.let { Ui.of(it.width.toFloat(), it.height.toFloat()) }

    private fun layout(n: SemanticsNode): TextLayoutResult {
        val out = ArrayList<TextLayoutResult>()
        n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(out)
        return out.single()
    }

    private fun texts(item: String) = rule.onAllNodes(hasAnyAncestor(hasTestTag("home.$item")) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes()

    private fun word(item: String) = texts(item).single { it.config[SemanticsProperties.Text].joinToString("") == item }

    private fun alpha(item: String) = layout(word(item)).layoutInput.style.color.alpha

    private fun scroll() = rule.onNodeWithTag("home.column").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun marked(item: String) {
        names.forEach { assertEquals("$it with $item marked", if (it == item) 1f else 0.6f, alpha(it), 0.005f) }
    }

    @Test
    fun theTouchMarkDimsTheOthers() {
        val shell = show(411, 960)
        marked("now playing")
        rule.onNodeWithTag("home.settings").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(100)
        val mid = alpha("settings")
        assertEquals(0.8f, mid, 0.05f)
        rule.mainClock.advanceTimeBy(48)
        assertEquals(0.4f * 48 / Tok.motion_focusAlpha, alpha("settings") - mid, 0.01f)
        rule.mainClock.advanceTimeBy(100)
        marked("settings")
        rule.onNodeWithTag("home.settings").performTouchInput { up() }
        settle(1000)
        assertEquals(listOf(Page.Home, Page.Settings), shell.nav.stack.toList())
    }

    @Test
    fun theMarkMovesAtTheDown() {
        val shell = show(411, 960)
        rule.onNodeWithTag("home.playlists").performTouchInput { down(center) }
        settle(300)
        marked("playlists")
        rule.onNodeWithTag("home.playlists").performTouchInput { cancel() }
        settle(1000)
        marked("playlists")
        assertEquals(listOf(Page.Home), shell.nav.stack.toList())
    }

    @Test
    fun aDragFromAnItemScrollsTheColumnAndKeepsTheMark() {
        val shell = show(960, 600)
        val x = ui().px(100f)
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("home.column").performTouchInput { swipe(Offset(x, bottom - 1f), Offset(x, top + 1f)) }
        rule.mainClock.autoAdvance = false
        settle(1000)
        val end = scroll()
        assertTrue(end > 0f)
        marked("now playing")
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("home.settings").performTouchInput { swipe(center, center + Offset(0f, height.toFloat())) }
        rule.mainClock.autoAdvance = false
        settle(1000)
        assertTrue("scroll ${scroll()} from $end", scroll() < end)
        marked("settings")
        assertEquals(listOf(Page.Home), shell.nav.stack.toList())
    }
}
