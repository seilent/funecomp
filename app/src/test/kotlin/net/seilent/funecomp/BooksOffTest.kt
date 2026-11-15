package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.state.Plays
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class BooksOffTest {
    @get:Rule
    val rule = createComposeRule()

    private var now = 0L

    private fun start(vararg faults: String): SimControl {
        val c = SimControl("v1", minor = 21, faults = faults.toList(), appFeatures = LINK_FEATURES or Groups.mask("books"), stateNow = { now })
        val shell = Shell(Nav(listOf(Page.Home, Page.NowPlaying)))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, everLive = true), c, shell) }
        }
        settle(1000)
        return c
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun count(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun emptySlots() {
        for (tag in listOf("np.skipBack", "np.skipAhead", "np.speed", "np.shuffle", "np.repeat", "np.heart")) assertEquals(tag, 0, count(tag))
        for (tag in listOf("np.prev", "np.toggle", "np.nextTrack")) assertTrue(tag, count(tag) > 0)
    }

    @Test
    fun aMinor21PeerWithoutBit48LeakingTheBookTagsGetsNoBookControls() {
        val c = start("bookplay", "nobooks", "bookleak")
        val s = c.device.playback.state
        assertFalse(c.device.books)
        assertEquals(true to 150, s.book to s.speedPct)
        emptySlots()
        assertTrue(Plays().frame(s, 1_000).isEmpty())
        val at = c.device.positionMs(now)
        now += 10_000
        assertEquals(at + 15_000, c.device.positionMs(now))
    }

    @Test
    fun aDroppedLinkKeepsTheBookStateWithEmptySlots() {
        val c = start("bookplay")
        assertTrue(c.device.books)
        c.device = c.device.unlinked()
        settle(300)
        assertFalse(c.device.books)
        assertEquals(true, c.device.playback.state.book)
        emptySlots()
    }
}
