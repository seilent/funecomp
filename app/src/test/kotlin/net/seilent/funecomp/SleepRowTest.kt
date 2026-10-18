package net.seilent.funecomp

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Row
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.sleepStart
import net.seilent.funecomp.ui.sleepText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class SleepRowTest {
    @get:Rule
    val rule = createComposeRule()

    private val sleep = Row(
        "sleep", "SLEEP TIMER", "power", Enums.RowKind.draft,
        listOf("0", "15", "30", "45", "60", "90", "120"),
        words = listOf("OFF", "15 MIN", "30 MIN", "45 MIN", "60 MIN", "90 MIN", "120 MIN"),
    )

    private fun start(board: String = "ap80"): SimControl {
        val c = SimControl(board, now = SystemClock::elapsedRealtime)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE), c, Shell(Nav(listOf(Page.Home, Page.Settings)))) }
        }
        settle(1000)
        for (p in listOf("library", "display", "power")) {
            rule.onNodeWithTag("pivot.$p").performClick()
            settle(1000)
        }
        return c
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun advance(c: SimControl, ms: Long) {
        rule.waitForIdle()
        ShadowSystemClock.advanceBy(Duration.ofMillis(ms))
        rule.mainClock.advanceTimeBy(ms)
        c.tick()
        rule.waitForIdle()
    }

    private fun simState(c: SimControl) = State.from(Node.decode("STATE", c.sim.state()))

    private fun shows(text: String) {
        settle(1000)
        rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag("set.sleep"))).assertExists()
    }

    private fun tap() {
        rule.onNodeWithTag("set.sleep").performClick()
        settle(300)
    }

    private fun step(right: Boolean) {
        rule.onNodeWithTag("set.sleep").performTouchInput { click(if (right) centerRight else centerLeft) }
        settle(300)
    }

    private fun confirm() {
        rule.onNodeWithTag("set.confirm").performClick()
        settle(300)
    }

    private fun drafts(c: SimControl) = c.sent.count { it == "DRAFT" }

    @Test
    fun advanceMovesTheSharedClock() {
        val c = start()
        c.draft(Enums.DraftKind.sleep, minutes = 30)
        val before = SystemClock.elapsedRealtime()
        advance(c, 60_001)
        assertTrue(SystemClock.elapsedRealtime() - before in 60_001L..60_100L)
        assertEquals(1740L, simState(c).sleepLeftS)
    }

    @Test
    fun textIsTheDeviceCopy() {
        assertEquals("30 min left", sleepText(sleep, 30))
        assertEquals("120 min left", sleepText(sleep, 120))
        assertEquals("OFF", sleepText(sleep, 0))
    }

    @Test
    fun editStartsOnTheNearestStepAndTheLowerOnATie() {
        val cases = listOf(30 to "30", 29 to "30", 52 to "45", 53 to "60", 105 to "90", 7 to "0", 8 to "15", 1 to "0", 0 to "0")
        for ((m, v) in cases) assertEquals("$m", v, sleepStart(sleep, m))
    }

    @Test
    fun aStepAtOffDoesNotSend() {
        val c = start()
        tap()
        step(false)
        confirm()
        assertEquals(0, drafts(c))
        shows("OFF")
    }

    @Test
    fun flowAp80() = flow("ap80")

    @Test
    fun flowV1() = flow("v1")

    private fun flow(board: String) {
        val c = start(board)
        tap()
        step(true)
        step(true)
        confirm()
        assertEquals("DRAFT", c.sent.last())
        shows("30 MIN LEFT")
        val at = c.device.playback.receivedAt
        advance(c, 60_001)
        assertEquals(at, c.device.playback.receivedAt)
        shows("29 MIN LEFT")
        tap()
        shows("30 MIN")
        confirm()
        assertEquals(1, drafts(c))
        shows("29 MIN LEFT")
        tap()
        step(true)
        step(false)
        confirm()
        assertEquals(2, drafts(c))
        assertEquals(1800L, simState(c).sleepLeftS)
        shows("30 MIN LEFT")
        tap()
        step(false)
        step(false)
        confirm()
        assertEquals(3, drafts(c))
        assertEquals(0L, simState(c).sleepLeftS)
        shows("OFF")
        c.transport(Enums.TransportCmd.play)
        tap()
        step(true)
        confirm()
        shows("15 MIN LEFT")
        advance(c, 900_000)
        assertEquals(0L, c.device.playback.state.sleepLeftS)
        assertEquals(false, c.device.playback.state.playing)
        shows("OFF")
    }

    @Test
    fun anOlderPeerShowsOffAndOpensOnOff() {
        val c = start()
        c.draft(Enums.DraftKind.sleep, minutes = 30)
        val p = c.device.playback
        c.device = c.device.copy(playback = p.copy(state = p.state.copy(sleepLeftS = null)))
        shows("OFF")
        tap()
        shows("OFF")
        step(true)
        shows("15 MIN")
    }
}
