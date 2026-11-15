package net.seilent.funecomp

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.ui.BrowseSessions
import net.seilent.funecomp.ui.ConnectionPage
import net.seilent.funecomp.ui.Ctx
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.LocalSheet
import net.seilent.funecomp.ui.LocalUi
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.Ui
import net.seilent.funecomp.ui.statusText
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
class LinkWordsTest {
    @get:Rule
    val rule = createComposeRule()

    private var link by mutableStateOf(LinkView(Phase.STOPPED, "AP80", bye = Enums.ByeReason.companionOff, everLive = true))

    private fun settle(ms: Long = 600) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun connection(path: String?): SimControl {
        val c = SimControl("ap80")
        val shell = Shell(Nav(listOf(Page.Home, Page.Connection)))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            BoxWithConstraints(Modifier.requiredSize(411.dp, 960.dp)) {
                CompositionLocalProvider(LocalUi provides Ui.of(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()), LocalSheet provides shell.sheet) {
                    ConnectionPage(Ctx(c.device, c, shell.nav, shell.sheet, shell.toast, BrowseSessions()), link, phoneAppPath = path)
                }
            }
        }
        settle(1000)
        return c
    }

    private fun texts(tag: String): List<String> =
        rule.onAllNodes(hasAnyAncestor(hasTestTag(tag)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes().flatMap { n -> n.config[SemanticsProperties.Text].map { it.text } }

    private fun count(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun top(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun bluetoothOffReadsAfterNoPermissionAndBeforeThePhase() {
        assertEquals("bluetooth off", statusText(LinkView(Phase.DOWN, "V1", bluetoothOff = true)))
        assertEquals("bluetooth off", statusText(LinkView(Phase.STOPPED, "V1", bye = Enums.ByeReason.companionOff, bluetoothOff = true)))
        assertEquals("no permission", statusText(LinkView(Phase.DOWN, "V1", permission = false, bluetoothOff = true)))
        assertEquals("not connected", statusText(LinkView(Phase.DOWN, "V1")))
        assertEquals("connecting", statusText(LinkView(Phase.CONNECTING, "V1")))
    }

    @Test
    fun thePhoneAppPathSitsUnderStatusWhenThePhoneAppIsOff() {
        val c = connection("settings > system > phone app")
        assertEquals(1, count("conn.phoneapp"))
        assertTrue(texts("conn.phoneapp").any { it.equals("settings > system > phone app", ignoreCase = true) })
        assertTrue(top("conn.phoneapp") > top("conn.status"))
        assertTrue(top("conn.phoneapp") < top("conn.media"))
        assertEquals(0, count("conn.remedy"))
        rule.onNodeWithTag("conn.phoneapp").performClick()
        settle(300)
        assertEquals(1, c.retries)
    }

    @Test
    fun noPathLineWithoutThePath() {
        connection(null)
        rule.onNodeWithTag("conn.status").fetchSemanticsNode()
        assertEquals(0, count("conn.phoneapp"))
    }

    @Test
    fun noPathLineOnARefusedByeOrWhileLive() {
        connection("settings > system > phone app")
        link = LinkView(Phase.STOPPED, "AP80", bye = Enums.ByeReason.refused, everLive = true)
        settle()
        assertEquals(0, count("conn.phoneapp"))
        assertEquals(1, count("conn.remedy"))
        link = LinkView(Phase.LIVE, "AP80", bye = Enums.ByeReason.companionOff, everLive = true)
        settle()
        assertEquals(0, count("conn.phoneapp"))
    }
}
