package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.Held
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.onlinePivots
import net.seilent.funecomp.ui.settingsPivots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.ceil

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class HomeLineTest {
    @get:Rule
    val rule = createComposeRule()

    private val c = SimControl("v1")
    private val link = LinkView(Phase.LIVE, "V1", everLive = true)
    private var size by mutableStateOf<Size?>(null)

    private fun show(s: Size) {
        rule.mainClock.autoAdvance = false
        size = s
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
    }

    private class Line(val text: String, val layout: TextLayoutResult, val left: Float, val labelLeft: Float)

    private fun line(item: String): Line {
        val nodes = rule.onAllNodes(hasAnyAncestor(hasTestTag("home.$item")), useUnmergedTree = true).fetchSemanticsNodes()
        val texts = nodes.mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString("")?.let { it to n } }
        val (label, sub) = texts.partition { it.first.lowercase() == item }.let { it.first.single().second to it.second.single() }
        val layouts = ArrayList<TextLayoutResult>()
        sub.second.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return Line(sub.first, layouts.single(), sub.second.boundsInRoot.left, label.boundsInRoot.left)
    }

    private fun check(item: String, names: List<String>, whole: Set<String>) {
        rule.setContent {
            val s = size ?: return@setContent
            key(s) {
                Box(Modifier.requiredSize(s.w.dp, s.h.dp)) { App(c.device, link, c, Shell(Nav(listOf(Page.Home))), insets = s.bars) }
            }
        }
        for (s in SIZES) {
            show(s)
            val l = line(item)
            val words = l.text.lowercase().split(' ')
            val need = ceil(l.layout.multiParagraph.intrinsics.maxIntrinsicWidth)
            val u = (l.left - l.labelLeft) / Held.Home.homeSubtitleX
            val edge = rule.onNodeWithTag("home.column").fetchSemanticsNode().boundsInRoot.right - Tok.edge * u
            assertTrue("$item at ${s.name}: ${l.text} needs $need of ${l.layout.layoutInput.constraints.maxWidth}", need <= l.layout.layoutInput.constraints.maxWidth)
            assertTrue("$item at ${s.name}: ${l.text} ends at ${l.left + need} past $edge", l.left + need <= edge + 1f)
            assertEquals("$item at ${s.name}: ${l.text}", names.take(words.size), words)
            if (words.size < names.size) {
                val input = l.layout.layoutInput
                val more = TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection)
                    .measure("${l.text} ${names[words.size].uppercase()}", input.style, softWrap = false, maxLines = 1)
                assertTrue("$item at ${s.name}: ${l.text} has room for ${names[words.size]}", more.size.width > input.constraints.maxWidth)
            }
            if (s.name in whole) assertEquals("$item at ${s.name}", names, words)
        }
    }

    @Test
    fun theSettingsLineShowsWholePivotNamesThatFit() {
        val names = settingsPivots(c.device.schema.rows)
        assertTrue(names.size >= 7)
        check("settings", names, setOf("flip960x411"))
    }

    @Test
    fun theOnlineLineShowsWholePivotNamesThatFit() {
        check("online", onlinePivots(c.device), SIZES.map { it.name }.toSet())
    }
}
