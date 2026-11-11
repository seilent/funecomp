package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class ToastCoverTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var shell: Shell
    private var link by mutableStateOf(LinkView(Phase.LIVE, "AP80", everLive = true))

    private fun start(pages: List<Page>) {
        val c = SimControl("ap80")
        shell = Shell(Nav(pages))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, link, c, shell) }
        }
        settle(1000)
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun shown(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun frame() {
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }

    private fun top() = rule.onNodeWithTag("toast").fetchSemanticsNode().boundsInRoot.top

    @Test
    fun noToastDrawsOverTheVolumeOverlayAndItShowsOnceTheOverlayCloses() {
        start(listOf(Page.Home))
        shell.overlay.show()
        settle(Tok.motion_volumeOpenEnd.toLong())
        assertTrue(shown("overlay"))
        shell.toast.show("artist", "title")
        settle(Tok.motion_toastRise.toLong() + 100)
        assertTrue(!shown("toast"))
        shell.overlay.hide()
        settle(Tok.motion_volumeTouchScrim.toLong() + 100)
        assertTrue(!shown("overlay"))
        assertTrue(shown("toast"))
    }

    @Test
    fun aToastUnderATouchClosedOverlayReturnsWithNoRise() {
        start(listOf(Page.Home))
        shell.overlay.show()
        settle(Tok.motion_volumeOpenEnd.toLong())
        shell.toast.show("artist", "title")
        settle(50)
        assertTrue(!shown("toast"))
        val exit = rule.onNodeWithTag("overlay.exit.hit", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
        rule.onRoot().performTouchInput { click(exit) }
        var frames = 0
        while (shown("overlay")) {
            check(frames++ < 30) { "the overlay is still drawn" }
            frame()
        }
        frames = 0
        while (!shown("toast")) {
            check(frames++ < 5) { "no toast after the overlay" }
            frame()
        }
        val first = top()
        settle(500)
        assertEquals(top(), first, 0.5f)
    }

    @Test
    fun theHeldToastHidesUnderTheVolumeOverlay() {
        start(listOf(Page.Home, Page.NowPlaying))
        link = LinkView(Phase.CONNECTING, "AP80", everLive = true)
        settle(1000)
        assertTrue(shown("toast"))
        shell.overlay.show()
        settle(Tok.motion_volumeOpenEnd.toLong())
        assertTrue(!shown("toast"))
        shell.overlay.hide()
        settle(Tok.motion_volumeTouchScrim.toLong() + 100)
        assertTrue(shown("toast"))
    }
}
