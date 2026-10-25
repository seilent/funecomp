package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Enums
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
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h600dp-420dpi")
class MinuteSchemaTest {
    @get:Rule
    val rule = createComposeRule()

    private val shell = Shell(Nav(listOf(Page.Home, Page.Settings)))
    private var t = 0L

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun top(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top

    private fun barTop() = rule.onAllNodes(hasTestTag("bar") and hasAnyAncestor(hasTestTag("hub.settings"))).fetchSemanticsNodes().single().boundsInRoot.top

    @Test
    fun aMinuteSchemaKeepsTheSheetAndTheRows() {
        val c = SimControl("v1", now = { t })
        c.draft(Enums.DraftKind.clock, unixS = 1767225600, zoneS = 0) { _, _ -> }
        c.tick()
        rule.mainClock.autoAdvance = false
        rule.setContent { Box(Modifier.requiredSize(411.dp, 600.dp)) { App(c.device, LinkView(Phase.LIVE), c, shell) } }
        settle(1000)
        for (p in listOf("library", "display", "power", "system")) {
            rule.onNodeWithTag("pivot.$p").performClick()
            settle(1000)
        }
        rule.onNode(hasText("2026-01-01 00:00") and hasAnyAncestor(hasTestTag("set.dateTime"))).assertExists()
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("rows").performScrollToNode(hasTestTag("set.about"))
        rule.mainClock.autoAdvance = false
        settle(600)
        rule.onNodeWithTag("set.scrobble").performClick()
        settle(1000)
        assertTrue(shell.sheet.shown)
        val title = shell.sheet.title
        val focus = shell.sheet.focus
        val row = top("set.scrobble")
        val bar = barTop()
        assertEquals(row, bar, 1f)
        val scrolled = top("set.dateTime")
        val strip = rule.onNodeWithTag("pivot.system").fetchSemanticsNode().boundsInRoot
        val rev = c.device.schema.rev!!
        for (m in 1..5) {
            t += 60_000
            c.tick()
            repeat(8) {
                rule.mainClock.advanceTimeByFrame()
                rule.waitForIdle()
                assertEquals(bar, barTop(), 0.5f)
            }
            settle(600)
            assertEquals(rev + m, c.device.schema.rev)
            rule.onNode(hasText("2026-01-01 00:0$m") and hasAnyAncestor(hasTestTag("set.dateTime"))).assertExists()
            assertTrue(shell.sheet.shown)
            assertEquals(title, shell.sheet.title)
            assertEquals(row, top("set.scrobble"), 0.5f)
            assertEquals(bar, barTop(), 0.5f)
            assertEquals(scrolled, top("set.dateTime"), 0.5f)
            assertEquals(strip, rule.onNodeWithTag("pivot.system").fetchSemanticsNode().boundsInRoot)
            assertEquals(focus, shell.sheet.focus)
        }
    }
}
