package net.seilent.funecomp

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Row
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.sleepStart
import net.seilent.funecomp.ui.sleepText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private val shell = Shell(Nav(listOf(Page.Home, Page.Settings)))

    private fun start(board: String = "ap80", schema: String? = null, pivots: List<String> = listOf("library", "display", "power"), w: Int = 411, h: Int = 960): SimControl {
        val c = SimControl(board, now = SystemClock::elapsedRealtime, schema = schema)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(w.dp, h.dp)) { App(c.device, LinkView(Phase.LIVE), c, shell) }
        }
        settle(1000)
        for (p in pivots) {
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

    private fun pick(i: Int) {
        rule.onNodeWithTag("sheet.item.$i").performClick()
        settle(600)
    }

    private fun dismiss() {
        rule.onRoot().performTouchInput { click(topCenter.copy(y = top + 10f)) }
        settle(600)
    }

    private fun type(text: String) {
        pick(7)
        assertEquals("sleep timer", shell.sheet.title)
        rule.onNodeWithTag("entry").performTextInput(text)
        rule.onNodeWithTag("entry").performImeAction()
        settle(600)
    }

    private fun toast(shows: Int) {
        assertEquals(shows, shell.toast.shows)
        assertEquals("sleep timer" to "0 to 999", shell.toast.artist to shell.toast.title)
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
    fun offAtOffDoesNotSend() {
        val c = start()
        tap()
        assertEquals(0, shell.sheet.focus)
        assertEquals(emptyList<String>(), shell.sheet.info)
        pick(0)
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
        assertEquals(listOf("OFF", "15 MIN", "30 MIN", "45 MIN", "60 MIN", "90 MIN", "120 MIN", "type value"), shell.sheet.items)
        assertEquals(0, drafts(c))
        pick(2)
        assertEquals("DRAFT", c.sent.last())
        shows("30 MIN LEFT")
        val at = c.device.playback.receivedAt
        advance(c, 60_001)
        assertEquals(at, c.device.playback.receivedAt)
        shows("29 MIN LEFT")
        tap()
        assertEquals(2, shell.sheet.focus)
        assertEquals(listOf("29 min left"), shell.sheet.info)
        dismiss()
        assertEquals(1, drafts(c))
        shows("29 MIN LEFT")
        tap()
        pick(2)
        assertEquals(2, drafts(c))
        assertEquals(1800L, simState(c).sleepLeftS)
        shows("30 MIN LEFT")
        tap()
        pick(0)
        assertEquals(3, drafts(c))
        assertEquals(0L, simState(c).sleepLeftS)
        shows("OFF")
        tap()
        pick(0)
        assertEquals(3, drafts(c))
        c.transport(Enums.TransportCmd.play)
        tap()
        pick(1)
        shows("15 MIN LEFT")
        advance(c, 900_000)
        assertEquals(0L, c.device.playback.state.sleepLeftS)
        assertEquals(false, c.device.playback.state.playing)
        shows("OFF")
    }

    @Test
    fun typedMinutesAp80() = typedMinutes("ap80")

    @Test
    fun typedMinutesV1() = typedMinutes("v1")

    private fun typedMinutes(board: String) {
        val c = start(board)
        tap()
        type("37")
        assertFalse(shell.sheet.shown)
        assertEquals(1, drafts(c))
        assertEquals(37L * 60, simState(c).sleepLeftS)
        shows("37 MIN LEFT")
        tap()
        assertEquals(2, shell.sheet.focus)
        assertEquals(listOf("37 min left"), shell.sheet.info)
        pick(7)
        rule.onNodeWithTag("entry").assert(hasText(""))
        rule.onNode(hasText("37") and hasAnyAncestor(hasTestTag("sheet")), useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("entry").performTextInput("1000")
        rule.onNodeWithTag("entry").performImeAction()
        settle(600)
        assertTrue(shell.sheet.shown)
        assertEquals("sleep timer", shell.sheet.title)
        assertEquals(1, drafts(c))
        toast(1)
        rule.onNodeWithTag("entry").performTextClearance()
        rule.onNodeWithTag("entry").performTextInput("0")
        rule.onNodeWithTag("entry").performImeAction()
        settle(600)
        assertFalse(shell.sheet.shown)
        assertEquals(2, drafts(c))
        assertEquals(0L, simState(c).sleepLeftS)
        shows("OFF")
        tap()
        type("0")
        assertFalse(shell.sheet.shown)
        assertEquals(2, drafts(c))
    }

    @Test
    fun aRefusedDraftToasts() {
        val c = start()
        val s = c.device.schema
        c.device = c.device.copy(schema = s.copy(rows = s.rows.map { if (it.key == "sleep") it.copy(max = 2000) else it }))
        settle(300)
        tap()
        type("1500")
        assertEquals(1, drafts(c))
        assertEquals(listOf(Err.badValue), c.failed)
        assertFalse(shell.sheet.shown)
        assertEquals(1, shell.toast.shows)
        assertEquals("sleep timer" to "0 to 2000", shell.toast.artist to shell.toast.title)
        shows("OFF")
    }

    @Test
    fun noTypedItemWithoutMax() {
        val c = start("ap80", "schema-steps.txt", listOf("power"))
        assertEquals(null, c.device.schema.rows.first { it.key == "sleep" }.max)
        tap()
        assertEquals(listOf("OFF", "15 MIN", "30 MIN", "45 MIN", "60 MIN", "90 MIN", "120 MIN"), shell.sheet.items)
        pick(3)
        assertEquals(45L * 60, simState(c).sleepLeftS)
        c.draft(Enums.DraftKind.sleep, minutes = 37)
        assertEquals(listOf(Err.badValue), c.failed)
    }

    @Test
    @Config(qualifiers = "w960dp-h411dp-420dpi")
    fun theTimeLeftLineShowsWhenTheFocusFitsLandscape() {
        val c = start(w = 960, h = 411)
        c.draft(Enums.DraftKind.sleep, minutes = 45)
        tap()
        assertEquals(3, shell.sheet.focus)
        rule.onNodeWithTag("sheet.info").assertIsDisplayed()
        rule.onNodeWithTag("sheet.item.3").assertIsDisplayed()
    }

    @Test
    fun anOlderPeerShowsOffAndOpensOnOff() {
        val c = start()
        c.draft(Enums.DraftKind.sleep, minutes = 30)
        val p = c.device.playback
        c.device = c.device.copy(playback = p.copy(state = p.state.copy(sleepLeftS = null)))
        shows("OFF")
        tap()
        assertEquals(0, shell.sheet.focus)
        assertEquals(emptyList<String>(), shell.sheet.info)
    }
}
