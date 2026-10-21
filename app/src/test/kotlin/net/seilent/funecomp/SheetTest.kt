package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.BrowseSessions
import net.seilent.funecomp.ui.Ctx
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.onOffSheet
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
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class SheetTest {
    @get:Rule
    val rule = createComposeRule()

    private val eqLines = listOf("preamp 0.0 db", "1 band", "preamp +1.5 to 0.0", "band 1 fc 15 hz to 20 hz", "band 1 gain +14.0 to +12.0", "band 1 q 12.00 to 10.00", "invalid name", "device has no eq presets")

    private fun start(w: Int, h: Int): Pair<SimControl, Shell> {
        val c = SimControl("ap80")
        val shell = Shell(Nav(listOf(Page.Home)))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(w.dp, h.dp)) { App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), c, shell) }
        }
        settle(1000)
        return c to shell
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun line(i: Int) = if (i == 0) "sheet.info" else "sheet.info.$i"

    private fun scrollTo(tag: String) {
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("sheet.list").performScrollToNode(hasTestTag(tag))
        rule.mainClock.autoAdvance = false
        settle(100)
    }

    @Test
    fun leadLinesStayInPlaceWhileTheItemsScroll() {
        val (_, shell) = start(411, 960)
        shell.sheet.open("long", (1..20).map { "item $it" }, info = listOf("first line", "second line")) {}
        settle(600)
        val first = bounds(line(0)).top
        val second = bounds(line(1)).top
        scrollTo("sheet.item.19")
        rule.onNodeWithTag("sheet.item.19").assertIsDisplayed()
        assertEquals(first, bounds(line(0)).top, 1.5f)
        assertEquals(second, bounds(line(1)).top, 1.5f)
        assertTrue(bounds("sheet.item.19").top > second)
    }

    @Test
    fun valueLambdasRedrawTheOpenSheet() {
        val (_, shell) = start(411, 960)
        var value by mutableStateOf("1 %")
        var lines by mutableStateOf(listOf("battery"))
        shell.sheet.open("about", listOf("update"), infoLines = { lines }, infoValues = { listOf(value) }, itemValues = { listOf(value) }) {}
        settle(600)
        assertEquals(2, rule.onAllNodesWithText("1 %", useUnmergedTree = true).fetchSemanticsNodes().size)
        value = "2 %"
        lines = listOf("battery", "card")
        settle(300)
        assertEquals(0, rule.onAllNodesWithText("1 %", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(2, rule.onAllNodesWithText("2 %", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(listOf("battery", "card"), shell.sheet.info)
        rule.onNodeWithTag(line(1), useUnmergedTree = true).assertExists()
    }

    @Test
    fun onOffSheetListsOffThenOnAndSendsOnlyAChange() {
        val (c, shell) = start(411, 960)
        val sets = ArrayList<Boolean>()
        val ctx = Ctx(c.device, c, shell.nav, shell.sheet, shell.toast, BrowseSessions())
        onOffSheet(ctx, "phone media controls", true) { sets += it }
        settle(600)
        assertEquals("phone media controls", shell.sheet.title)
        assertEquals(listOf("off", "on"), shell.sheet.items)
        assertEquals(1, shell.sheet.focus)
        rule.onNodeWithTag("sheet.item.1").performClick()
        settle(600)
        assertFalse(shell.sheet.shown)
        assertEquals(emptyList<Boolean>(), sets)
        onOffSheet(ctx, "phone media controls", true) { sets += it }
        settle(600)
        rule.onNodeWithTag("sheet.item.0").performClick()
        settle(600)
        assertEquals(listOf(false), sets)
        onOffSheet(ctx, "reconnect on update", false) { sets += it }
        settle(600)
        assertEquals(0, shell.sheet.focus)
        shell.sheet.close()
        settle(600)
        assertEquals(listOf(false), sets)
    }

    private fun capKeepsTheLastItemReachable(lines: List<String>, items: List<String>, fixed: Int) {
        val (_, shell) = start(960, 411)
        var picked = -1
        shell.sheet.open("sheet", items, info = lines) { picked = it }
        settle(600)
        for (i in 0 until fixed) rule.onNode(hasTestTag(line(i)) and !hasAnyAncestor(hasTestTag("sheet.list")), useUnmergedTree = true).assertExists()
        assertEquals(bounds(line(fixed - 1)).bottom, bounds("sheet.list").top, 1.5f)
        assertEquals(bounds("sheet.list").top, bounds("sheet.item.0").top, 1.5f)
        rule.onNodeWithTag("sheet.item.${items.lastIndex}").assertIsDisplayed()
        scrollTo(line(fixed))
        rule.onNode(hasTestTag(line(fixed)) and hasAnyAncestor(hasTestTag("sheet.list")), useUnmergedTree = true).assertExists()
        assertEquals(bounds("sheet.list").top, bounds(line(fixed)).top, 1.5f)
        val last = "sheet.item.${items.lastIndex}"
        scrollTo(last)
        rule.onNodeWithTag(last).assertIsDisplayed()
        rule.onNodeWithTag(last).performClick()
        settle(600)
        assertEquals(items.lastIndex, picked)
    }

    @Test
    fun landscapeCapKeepsTheAutoEqActionsReachable() = capKeepsTheLastItemReachable(eqLines, listOf("cancel", "apply"), 3)

    @Test
    fun landscapeCapKeepsTheAboutItemReachable() = capKeepsTheLastItemReachable(listOf("funeOS dev", "seilent", "board", "dap battery", "card"), listOf("update firmware"), 4)

    @Test
    fun aLongListOpensAtItsTopWhileTheFocusFitsTheWindow() {
        val (_, shell) = start(960, 411)
        shell.sheet.open("long", (1..20).map { "item $it" }, 2) {}
        settle(600)
        assertEquals(bounds("sheet.list").top, bounds("sheet.item.0").top, 1.5f)
        rule.onNodeWithTag("sheet.item.2").assertIsDisplayed()
    }

    @Test
    fun aLongListOpensWithAFocusPastTheWindowOnItsLastRow() {
        val (_, shell) = start(960, 411)
        shell.sheet.open("long", (1..20).map { "item $it" }, 15) {}
        settle(600)
        rule.onNodeWithTag("sheet.item.15").assertIsDisplayed()
        assertEquals(bounds("sheet.list").bottom, bounds("sheet.item.15").bottom, 1.5f)
    }

    @Test
    fun aSleepSheetInLandscapeShowsOff() {
        val (_, shell) = start(960, 411)
        shell.sheet.open("sleep timer", listOf("off", "15 min", "30 min", "45 min", "60 min", "90 min", "120 min", "type value"), 3, info = listOf("44 min left")) {}
        settle(600)
        rule.onNodeWithTag("sheet.item.0").assertIsDisplayed()
        rule.onNodeWithTag("sheet.item.3").assertIsDisplayed()
        assertEquals(bounds("sheet.list").top, bounds("sheet.item.0").top, 1.5f)
    }

    @Test
    fun aLongMixedLineWrapsToASecondLineAndPushesTheItems() {
        val (_, shell) = start(360, 640)
        val long = "funeOS 0.4.0 dev build from a branch with a rather long name"
        assertEquals(60, long.length)
        shell.sheet.open("about", listOf("update firmware"), info = listOf("seilent")) {}
        settle(600)
        val one = bounds(line(0)).height
        shell.sheet.close()
        settle(600)
        shell.sheet.open("about", listOf("update firmware"), info = listOf(long), infoMixed = { listOf(true) }) {}
        settle(600)
        val layouts = ArrayList<TextLayoutResult>()
        rule.onNode(hasAnyAncestor(hasTestTag(line(0))) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val l = layouts.single()
        assertEquals(2, l.lineCount)
        assertTrue(l.getLineEnd(0) < long.length)
        assertEquals(l.getLineEnd(0), l.getLineStart(1))
        val two = bounds(line(0)).height
        assertTrue(two > one)
        assertEquals(bounds(line(0)).bottom, bounds("sheet.item.0").top, 1.5f)
    }

    @Test
    fun aLineWhoseRestWouldNotFitBreaksAtTheLastCodePointThatFits() {
        val (_, shell) = start(360, 640)
        val version = "funeOS 0.4.0-dev-118-g3c887a9-uirework-dirty+20261021-long"
        shell.sheet.open("about", listOf("update firmware"), info = listOf(version), infoMixed = { listOf(true) }) {}
        settle(600)
        val layouts = ArrayList<TextLayoutResult>()
        rule.onNode(hasAnyAncestor(hasTestTag(line(0))) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val l = layouts.single()
        assertEquals(2, l.lineCount)
        assertTrue(l.getLineEnd(0) > "funeOS ".length + 1)
    }
}
