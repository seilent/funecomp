package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.Held
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.LocalPressSlots
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.PressSlots
import net.seilent.funecomp.ui.Shell
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
class LeavingLookTest {
    @get:Rule
    val rule = createComposeRule()

    private val slots = PressSlots()
    private lateinit var shell: Shell

    private fun start(pages: List<Page>) {
        val c = SimControl("ap80")
        shell = Shell(Nav(pages))
        rule.mainClock.autoAdvance = false
        rule.setUptimeContent {
            CompositionLocalProvider(LocalPressSlots provides slots) {
                Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), c, shell) }
            }
        }
        settle(1000)
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun until(at: Long) = settle((at - rule.mainClock.currentTime).coerceAtLeast(0))

    private fun frame() {
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }

    private fun node(tag: String): SemanticsNodeInteraction = if (tag.startsWith(TEXT)) rule.onNode(hasText(tag.removePrefix(TEXT), ignoreCase = true)) else rule.onNodeWithTag(tag)

    private fun look() = slots.look(rule.mainClock.currentTime)

    private fun noLookThroughTheOutLeg(name: String, from: Long) {
        while (rule.mainClock.currentTime < from + Held.Nav.motion_turnstileOut.toLong()) {
            frame()
            assertEquals("$name +${rule.mainClock.currentTime - from}", 0f, look(), 0f)
            assertEquals("$name drawn +${rule.mainClock.currentTime - from}", 0f, slots.lit(), 0f)
        }
    }

    private fun tapThatLeaves(name: String, hit: String, upAt: Long) {
        val stack = shell.nav.stack.toList()
        val t0 = rule.mainClock.currentTime
        node(hit).performTouchInput { down(center) }
        until(t0 + upAt)
        assertEquals("$name held", 1f, look(), 0f)
        node(hit).performTouchInput { up() }
        assertEquals("$name at the up", 0f, look(), 0f)
        val up = rule.mainClock.currentTime
        noLookThroughTheOutLeg(name, up)
        assertTrue("$name left its page", shell.nav.stack.toList() != stack)
        settle(1500)
    }

    @Test
    fun aTapThatPushesAPageDrawsNoLookOnTheLeavingPage() {
        start(listOf(Page.Home))
        tapThatLeaves("home item", "home.music", 120)
    }

    @Test
    fun aListRowTapThatOpensTheArtistDrawsNoLookOnTheLeavingPage() {
        start(listOf(Page.Home, Page.Music))
        rule.pivotTo("music", "artists") { settle(it) }
        tapThatLeaves("artist row", "${TEXT}Utada Hikaru", 120)
        assertTrue(shell.nav.top is Page.Artist)
    }

    @Test
    fun aPageThatLeavesInATappedItemsHoldDrawsNoLook() {
        start(listOf(Page.Home, Page.NowPlaying))
        val t0 = rule.mainClock.currentTime
        node("np.shuffle").performTouchInput { down(center) }
        until(t0 + 50)
        node("np.shuffle").performTouchInput { up() }
        until(t0 + 120)
        node("back").performTouchInput { down(center) }
        until(t0 + 150)
        node("back").performTouchInput { up() }
        noLookThroughTheOutLeg("shuffle in its hold", rule.mainClock.currentTime)
        assertEquals(listOf(Page.Home), shell.nav.stack.toList())
        settle(1500)
    }

    @Test
    fun aLeaveTheTouchDidNotCauseResetsAHeldPress() {
        start(listOf(Page.Home))
        node("home.music").performTouchInput { down(center) }
        settle(Tok.motion_pressGlow.toLong() + FRAME)
        assertEquals("held", 1f, look(), 0f)
        shell.nav.push(Page.Settings)
        frame()
        frame()
        assertEquals("reset by the stack", 0f, look(), 0f)
        assertEquals("reset drawn", 0f, slots.lit(), 0f)
        noLookThroughTheOutLeg("held through a push", rule.mainClock.currentTime)
        rule.onRoot().performTouchInput { cancel() }
        settle(1500)
        assertEquals(listOf(Page.Home, Page.Settings), shell.nav.stack.toList())
    }

    private companion object {
        const val TEXT = "text:"
        const val FRAME = 16L
    }
}
