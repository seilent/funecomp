package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.SvcInfo
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.ONLINE_ART
import net.seilent.funecomp.ui.ONLINE_LYRICS
import net.seilent.funecomp.ui.ONLINE_SCROBBLING
import net.seilent.funecomp.ui.ONLINE_STREAMING
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
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
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class PhoneTitleFitTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var shell: Shell

    private val services = listOf(
        SvcInfo("tidal", "TIDAL", listOf("client_id", "client_secret", "pkce_client_id", "pkce_client_secret"), true),
        SvcInfo("svcdemo", "SVCDEMO", emptyList(), false),
    )

    private fun settle() {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()
    }

    private fun cut(leg: String): List<String> {
        val titles = rule.onAllNodesWithTag("settings.title", useUnmergedTree = true).fetchSemanticsNodes().map { n ->
            val out = ArrayList<TextLayoutResult>()
            n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(out)
            out.single()
        }.filter { it.layoutInput.constraints.hasBoundedWidth }
        assertTrue("$leg has no bounded title", titles.isNotEmpty())
        return titles.filter { it.multiParagraph.intrinsics.maxIntrinsicWidth > it.layoutInput.constraints.maxWidth + 0.5f }.map { "$leg: ${it.layoutInput.text.text}" }
    }

    private fun tag(n: SemanticsNode) = n.config[SemanticsProperties.TestTag]

    private fun settingsLegs(): List<String> {
        val found = ArrayList<String>()
        repeat(16) {
            val hub = rule.onNodeWithTag("hub.settings").fetchSemanticsNode()
            val u = Ui.of(hub.size.width.toFloat(), hub.size.height.toFloat()).u
            val ns = rule.onAllNodes(SemanticsMatcher("pivot") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("pivot.") == true }, useUnmergedTree = true)
                .fetchSemanticsNodes().sortedBy { it.positionInRoot.x }
            val active = ns.indexOfFirst { abs(it.positionInRoot.x - hub.positionInRoot.x - 20f * u) < 1f }
            if (active < 0) error("no active settings pivot")
            found += cut("settings ${tag(ns[active])}")
            val next = ns.getOrNull(active + 1) ?: return found
            rule.onNodeWithTag(tag(next), useUnmergedTree = true).performClick()
            settle()
        }
        error("settings pivots did not end")
    }

    @Test
    fun everyPhoneTitleFitsAtTheNarrowestPortrait() {
        Svc.reset()
        val c = SimControl("v1", services = services)
        shell = Shell(Nav(listOf(Page.Home, Page.Connection)))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) {
                App(c.device, LinkView(Phase.LIVE, "V1", everLive = true), c, shell)
            }
        }
        settle()
        val found = ArrayList(cut("connection"))
        val pages = listOf(
            Page.Online(ONLINE_ART) to "online art",
            Page.Online(ONLINE_LYRICS) to "online lyrics",
            Page.Online(ONLINE_SCROBBLING) to "online scrobbling",
            Page.Online(ONLINE_STREAMING) to "online streaming",
            Page.Firmware to "firmware",
            Page.Settings to "settings",
        )
        for ((page, leg) in pages) {
            rule.runOnIdle {
                shell.nav.popTo(Page.Home)
                shell.nav.push(page)
            }
            settle()
            if (page == Page.Online(ONLINE_STREAMING)) assertTrue(leg, rule.onAllNodesWithTag("svc.tidal", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
            found += if (page == Page.Settings) settingsLegs() else cut(leg)
        }
        assertEquals(emptyList<String>(), found)
    }
}
