package net.seilent.funecomp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.PROMPT_GUARD_MS
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
class NewBoardTouchTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val r1 = "00:00:00:00:00:04"
    private val boards = Boards()
    private lateinit var c: SimControl
    private lateinit var shell: Shell

    private fun find(now: Long, round: Long = now) = boards.found(Find(r1, "fune r1", true, true, false, false), now, round)

    private fun start() {
        c = SimControl("ap80")
        c.boards = boards
        shell = Shell(Nav(listOf(Page.Home)))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), c, shell, detected = boards.prompt) }
        }
        settle(1000)
        find(0)
        settle(16)
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun prompted() = shell.sheet.shown && shell.sheet.title == "fune r1"

    private fun scrimTap() = rule.onNodeWithTag("sheet").performTouchInput {
        down(Offset(centerX, 40f))
        up()
    }

    private fun pairTap() = rule.onNodeWithTag("sheet.item.0").performTouchInput {
        down(center)
        up()
    }

    private fun back() = rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }

    @Test
    fun aScrimTapAsThePromptRisesKeepsIt() {
        start()
        assertTrue(prompted())
        settle(200)
        scrimTap()
        settle(16)
        assertTrue(prompted())
        assertEquals(emptyList<String>(), c.boardLaters)
        assertEquals(emptyList<String>(), c.boardCancels)
    }

    @Test
    fun aPairDownInTheFirstSecondDoesNothing() {
        start()
        settle(900)
        pairTap()
        settle(16)
        assertTrue(prompted())
        assertEquals(emptyList<String>(), c.paired)
    }

    @Test
    fun aPairAfterTheFirstSecondPairs() {
        start()
        settle(1100)
        pairTap()
        settle(16)
        assertFalse(prompted())
        assertEquals(listOf(r1), c.paired)
    }

    @Test
    fun aDownInTheFirstSecondWithItsUpAfterDoesNothing() {
        start()
        settle(800)
        rule.onNodeWithTag("sheet.item.0").performTouchInput { down(center) }
        settle(400)
        rule.onNodeWithTag("sheet.item.0").performTouchInput { up() }
        settle(16)
        assertTrue(prompted())
        assertEquals(emptyList<String>(), c.paired)
    }

    @Test
    fun backInTheFirstSecondDoesNothing() {
        start()
        settle(500)
        back()
        settle(16)
        assertTrue(prompted())
        assertEquals(emptyList<String>(), c.boardLaters)
    }

    @Test
    fun backAfterTheFirstSecondIsLaterAndTheNextRoundOffersItOnce() {
        start()
        settle(1500)
        back()
        settle(400)
        assertFalse(prompted())
        assertEquals(listOf(r1), c.boardLaters)
        assertEquals(emptyList<String>(), c.boardCancels)
        find(30_000, 30_000)
        settle(16)
        assertTrue(prompted())
    }

    @Test
    fun aScrimTapAfterTheFirstSecondIsLaterAndASecondLaterHoldsTheBoard() {
        start()
        settle(1500)
        scrimTap()
        settle(400)
        assertFalse(prompted())
        assertEquals(listOf(r1), c.boardLaters)
        c.boardNow = 1_000
        find(500, 0)
        settle(16)
        assertFalse(prompted())
        find(30_000, 30_000)
        settle(16)
        assertTrue(prompted())
        settle(1500)
        scrimTap()
        settle(400)
        c.boardNow = 31_000
        find(60_000, 60_000)
        settle(16)
        assertFalse(prompted())
    }

    @Test
    fun cancelHoldsTheBoardForItsWindow() {
        start()
        settle(1500)
        rule.onNodeWithTag("sheet.item.1").performTouchInput {
            down(center)
            up()
        }
        settle(400)
        assertEquals(listOf(r1), c.boardCancels)
        find(30_000, 30_000)
        settle(16)
        assertFalse(prompted())
    }

    @Test
    fun aSemanticsClickIsJudgedByItsArrival() {
        start()
        settle(500)
        rule.onNodeWithTag("sheet.item.0").performSemanticsAction(SemanticsActions.OnClick)
        settle(16)
        assertTrue(prompted())
        assertEquals(emptyList<String>(), c.paired)
        settle(600)
        rule.onNodeWithTag("sheet.item.0").performSemanticsAction(SemanticsActions.OnClick)
        settle(16)
        assertEquals(listOf(r1), c.paired)
    }

    @Test
    fun aSecondGuardedOpenGetsItsOwnSecond() {
        start()
        settle(900)
        rule.runOnUiThread { shell.sheet.open("other", listOf("a", "b"), guard = PROMPT_GUARD_MS) {} }
        settle(300)
        assertTrue(shell.sheet.armed)
        settle(800)
        assertFalse(shell.sheet.armed)
    }
}
