package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.SPEED_STEPS
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.speedWord
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
class NowPlayingBookTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var shell: Shell

    private fun start(vararg faults: String): SimControl {
        val c = SimControl("v1", minor = 21, faults = faults.toList(), appFeatures = LINK_FEATURES or Groups.mask("books"))
        shell = Shell(Nav(listOf(Page.Home, Page.NowPlaying)))
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

    private fun push(c: SimControl, change: (State) -> State) {
        c.push(Msg.STATE, change(c.device.playback.state).encode())
        settle(300)
    }

    private fun openSpeed() {
        rule.onNodeWithTag("np.speed").performClick()
        settle(600)
    }

    private fun speedOpen() = shell.sheet.shown && shell.sheet.title == "speed"

    @Test
    fun aBookDrawsTheThreeRingsAndNoShuffleRepeatOrHeartAndMusicAfterItGetsThemBack() {
        val c = start("bookplay")
        assertEquals(true, c.device.playback.state.book)
        for (tag in listOf("np.skipBack", "np.skipAhead", "np.speed", "np.prev", "np.toggle", "np.nextTrack")) assertTrue(tag, count(tag) > 0)
        for (tag in listOf("np.shuffle", "np.repeat", "np.heart")) assertEquals(tag, 0, count(tag))
        c.play(null, null, 0, false, listOf(c.sim.lib.tracks[0].id))
        settle(300)
        for (tag in listOf("np.shuffle", "np.repeat", "np.heart")) assertTrue(tag, count(tag) > 0)
        for (tag in listOf("np.skipBack", "np.skipAhead", "np.speed")) assertEquals(tag, 0, count(tag))
    }

    @Test
    fun theSkipRingsSkipBackAndForwardThirtySeconds() {
        val c = start("bookplay")
        rule.onNodeWithTag("np.skipBack").performClick()
        settle(300)
        rule.onNodeWithTag("np.skipAhead").performClick()
        settle(300)
        assertEquals(listOf(-30_000, 30_000), c.skips)
        assertTrue(c.transports.isEmpty())
    }

    @Test
    fun theSpeedRingOpensTheSheetAtTheAppliedSpeedAndAPickSendsAndCloses() {
        val c = start("bookplay")
        openSpeed()
        assertTrue(speedOpen())
        assertEquals(SPEED_STEPS.map { speedWord(it) }, shell.sheet.items)
        assertEquals(SPEED_STEPS.indexOf(150), shell.sheet.focus)
        shell.sheet.accept(SPEED_STEPS.indexOf(125))
        settle(600)
        assertEquals(listOf(125), c.speeds)
        assertFalse(shell.sheet.shown)
        assertEquals(125, c.device.playback.state.speedPct)
    }

    @Test
    fun theSpeedSheetStaysAcrossChaptersAndClosesWhenTheBookOrItsSpeedGoes() {
        val c = start("bookplay")
        openSpeed()
        c.transport(Enums.TransportCmd.next)
        settle(300)
        assertEquals(true, c.device.playback.state.book)
        assertTrue(speedOpen())
        push(c) { it.copy(album = "Another Book") }
        assertFalse(speedOpen())
        push(c) { it.copy(album = "Kokoro") }
        openSpeed()
        push(c) { it.copy(artist = "Another Author") }
        assertFalse(speedOpen())
        push(c) { it.copy(artist = "Natsume Soseki") }
        openSpeed()
        push(c) { it.copy(book = null) }
        assertFalse(speedOpen())
        push(c) { it.copy(book = true) }
        openSpeed()
        push(c) { it.copy(speedPct = null) }
        assertFalse(speedOpen())
        push(c) { it.copy(speedPct = 150) }
        openSpeed()
        c.device = c.device.unlinked()
        settle(300)
        assertFalse(speedOpen())
    }

    @Test
    fun leavingNowPlayingClosesTheSpeedSheet() {
        start("bookplay")
        openSpeed()
        assertTrue(speedOpen())
        shell.nav.pop()
        settle(600)
        assertFalse(speedOpen())
    }

    @Test
    fun anUnstretchableBookDrawsTheSkipRingsAndAnEmptySpeedPlace() {
        val c = start("bookplay", "nostretch")
        assertEquals(null, c.device.playback.state.speedPct)
        assertTrue(count("np.skipBack") > 0)
        assertTrue(count("np.skipAhead") > 0)
        assertEquals(0, count("np.speed"))
        assertEquals(0, count("np.heart"))
    }
}
