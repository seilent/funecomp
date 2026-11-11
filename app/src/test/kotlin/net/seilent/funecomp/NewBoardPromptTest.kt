package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.Battery
import net.seilent.funecomp.ui.Detected
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
class NewBoardPromptTest {
    @get:Rule
    val rule = createComposeRule()

    private val r1 = Detected("fune r1", "00:00:00:00:00:04")
    private var detected by mutableStateOf<Detected?>(null)
    private var link by mutableStateOf(LinkView(Phase.LIVE, "AP80", everLive = true))
    private lateinit var c: SimControl
    private lateinit var shell: Shell

    private fun start(pages: List<Page> = listOf(Page.Home)) {
        c = SimControl("ap80")
        shell = Shell(Nav(pages))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, link, c, shell, detected = detected) }
        }
        settle(1000)
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun prompted() = shell.sheet.shown && shell.sheet.title == "fune r1"

    @Test
    fun theBoardShowsWithItsNameDetectedPairAndCancel() {
        start()
        detected = r1
        settle(600)
        assertTrue(prompted())
        assertEquals(listOf("pair", "cancel"), shell.sheet.items)
        assertEquals(listOf("detected"), shell.sheet.info)
        assertEquals(0, shell.sheet.focus)
        shell.sheet.accept(0)
        assertEquals(listOf(r1.address), c.paired)
    }

    @Test
    fun cancelIsACancelAndADismissIsALater() {
        start()
        detected = r1
        settle(600)
        shell.sheet.accept(1)
        assertEquals(listOf(r1.address), c.boardCancels)
        detected = null
        settle(100)
        detected = r1.copy()
        settle(600)
        shell.sheet.close()
        assertEquals(listOf(r1.address), c.boardCancels)
        assertEquals(listOf(r1.address), c.boardLaters)
    }

    @Test
    fun itWaitsForAnOpenSheet() {
        start()
        shell.sheet.open("volume step", listOf("2%", "5%")) {}
        detected = r1
        settle(600)
        assertFalse(prompted())
        shell.sheet.close(dismissed = false)
        settle(600)
        assertTrue(prompted())
    }

    @Test
    fun theBackgroundAskGoesFirst() {
        start()
        link = link.copy(battery = Battery.OPTIMIZED, askBattery = true)
        detected = r1
        settle(600)
        assertEquals("background", shell.sheet.title)
        link = link.copy(askBattery = false)
        shell.sheet.accept(1)
        settle(600)
        assertTrue(prompted())
    }

    @Test
    fun itWaitsForTheVolumeOverlay() {
        start()
        shell.overlay.show()
        detected = r1
        settle(300)
        assertFalse(prompted())
        settle(OVERLAY_SPAN)
        assertTrue(prompted())
    }

    @Test
    fun itWaitsForAPageTurnToRest() {
        start()
        shell.nav.push(Page.Settings)
        settle(16)
        detected = r1
        settle(16)
        assertTrue(shell.nav.turning)
        assertFalse(prompted())
        settle(1_000)
        assertFalse(shell.nav.turning)
        assertTrue(prompted())
    }

    @Test
    fun itShowsOverNowPlayingInLyricsMode() {
        start(listOf(Page.Home, Page.NowPlaying))
        c.mode = true
        rule.onNodeWithTag("np.art").performClick()
        settle(1000)
        detected = r1
        settle(600)
        assertTrue(prompted())
    }

    @Test
    fun itClosesWhenTheBoardGoes() {
        start()
        detected = r1
        settle(600)
        assertTrue(prompted())
        detected = null
        settle(100)
        assertFalse(shell.sheet.shown)
        assertEquals(emptyList<String>(), c.boardCancels)
    }

    @Test
    fun itShowsOnTheConnectionPageBeforeTheFirstLink() {
        link = LinkView(Phase.CONNECTING, "AP80")
        start(listOf(Page.Connection))
        detected = r1
        settle(600)
        assertTrue(prompted())
    }
}
