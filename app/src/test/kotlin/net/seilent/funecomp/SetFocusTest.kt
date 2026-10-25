package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import net.seilent.funecomp.proto.Row
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class SetFocusTest {
    @get:Rule
    val rule = createComposeRule()

    private val shell = Shell(Nav(listOf(Page.Home, Page.Settings)))

    private fun start(restore: StateRestorationTester? = null): SimControl {
        val c = SimControl("ap80")
        rule.mainClock.autoAdvance = false
        val content = @Composable { Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE), c, shell) } }
        if (restore != null) restore.setContent(content) else rule.setContent(content)
        settle(1000)
        return c
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun frames(n: Int): List<Float> = (1..n).map {
        frame()
        barTop()
    }

    private fun frame() {
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }

    private fun pivot(p: String) {
        rule.onNodeWithTag("pivot.$p").performClick()
        settle(1000)
    }

    private fun focusOn(key: String) {
        rule.onNodeWithTag("set.$key").performClick()
        settle(600)
        if (shell.sheet.shown) {
            shell.sheet.close()
            settle(600)
        }
    }

    private fun schema(c: SimControl, edit: (List<Row>) -> List<Row>) {
        val s = c.device.schema
        c.device = c.device.copy(schema = s.copy(rev = (s.rev ?: 0) + 1, rows = edit(s.rows)))
    }

    private fun row(key: String, pivot: String) = Row(key, key.uppercase(), pivot, Enums.RowKind.choice, listOf("a", "b"), words = listOf("A", "B"))

    private fun insertBefore(c: SimControl, at: String, vararg add: Row) = schema(c) { rows ->
        val i = rows.indexOfFirst { it.key == at }
        rows.subList(0, i) + add.toList() + rows.subList(i, rows.size)
    }

    private fun drop(c: SimControl, vararg keys: String) = schema(c) { rows -> rows.filter { it.key !in keys } }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun barTop(): Float = rule.onAllNodes(hasTestTag("bar") and hasAnyAncestor(hasTestTag("hub.settings"))).fetchSemanticsNodes().single().boundsInRoot.top

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun barOn(key: String) = abs(barTop() - bounds("set.$key").top) < 1f

    @Test
    fun aRowInsertedAboveKeepsFocusAndTheBarJumps() {
        val c = start()
        pivot("library")
        pivot("display")
        focusOn("batteryPercent")
        assertTrue(barOn("batteryPercent"))
        val before = bounds("set.batteryPercent").top
        insertBefore(c, "saverSeconds", row("navOrder", "display"), row("navButton", "display"))
        val from = barTop()
        val seen = frames(8)
        val to = bounds("set.batteryPercent").top
        assertTrue(to > before)
        assertTrue(seen.all { abs(it - from) < 1f || abs(it - to) < 1f })
        assertTrue(barOn("batteryPercent"))
        settle(600)
        assertTrue(barOn("batteryPercent"))
        val start = barTop()
        rule.onNodeWithTag("set.clock").performClick()
        val clock = bounds("set.clock").top
        assertTrue(frames(4).any { abs(it - start) > 1f && abs(it - clock) > 1f })
        settle(600)
        if (shell.sheet.shown) shell.sheet.close()
        settle(600)
        assertTrue(barOn("clock"))
    }

    @Test
    fun rowsAddedThenRemovedKeepTheFocusedKey() {
        val c = start()
        pivot("library")
        pivot("display")
        focusOn("batteryPercent")
        insertBefore(c, "clock", row("navOrder", "display"), row("navButton", "display"))
        settle(600)
        assertTrue(barOn("batteryPercent"))
        drop(c, "navOrder", "navButton")
        settle(600)
        assertTrue(barOn("batteryPercent"))
    }

    @Test
    fun aRemovedKeyFallsBackToItsIndexAndKeepsTheNewRow() {
        val c = start()
        pivot("library")
        pivot("display")
        focusOn("clock")
        drop(c, "clock")
        settle(600)
        assertTrue(barOn("batteryPercent"))
        insertBefore(c, "brightness", row("navOrder", "display"))
        settle(600)
        assertTrue(barOn("batteryPercent"))
    }

    @Test
    fun theLastRowRemovedMovesFocusToTheNewLast() {
        val c = start()
        for (p in listOf("library", "display", "power")) pivot(p)
        focusOn("inlineRemote")
        drop(c, "inlineRemote")
        settle(600)
        assertTrue(barOn("screenOffKeys"))
    }

    @Test
    fun aPivotInsertedBeforeKeepsTheShownPivotWithNoMotion() {
        val c = start()
        pivot("library")
        pivot("display")
        pivot("power")
        val left = bounds("set.lockKnob").left
        schema(c) { rows -> listOf(row("extraRow", "extra")) + rows }
        repeat(20) {
            frame()
            assertTrue(exists("set.lockKnob"))
            assertEquals(left, bounds("set.lockKnob").left, 0.5f)
        }
        assertFalse(exists("set.extraRow"))
    }

    @Test
    fun aRemovedPivotSlidesTheOldRowsOutThenShowsItsNeighbour() {
        val c = start()
        for (p in listOf("library", "display", "power", "system", "bluetooth")) pivot(p)
        val left = bounds("set.btCodec").left
        drop(c, "btCodec")
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(150)
        rule.waitForIdle()
        assertTrue(exists("set.btCodec"))
        assertTrue(bounds("set.btCodec").left > left + 1f)
        settle(1000)
        assertFalse(exists("set.btCodec"))
        assertTrue(exists("set.about"))
    }

    @Test
    fun eachPivotKeepsItsOwnFocus() {
        start()
        val top = bounds("set.output").top
        focusOn("powerOnVolume")
        pivot("library")
        pivot("display")
        focusOn("scale")
        assertTrue(barOn("scale"))
        repeat(2) {
            rule.onNodeWithTag("hub.settings").performTouchInput { swipeRight() }
            settle(1000)
        }
        assertTrue(barOn("powerOnVolume"))
        assertEquals(top, bounds("set.output").top, 0.5f)
        pivot("library")
        pivot("display")
        assertTrue(barOn("scale"))
    }

    @Test
    fun aRangeEditWhoseRowLeavesEndsKeepingItsValue() {
        val c = start()
        val was = c.device.settings.values["balance"]
        rule.onNodeWithTag("set.balance").performClick()
        settle(600)
        rule.onNodeWithTag("set.balance").performTouchInput { click(Offset(width * 0.9f, height / 2f)) }
        settle(600)
        val stepped = c.device.settings.values["balance"]
        assertNotEquals(was, stepped)
        val sets = c.sent.count { it == "SET" }
        drop(c, "balance")
        settle(600)
        assertEquals(sets, c.sent.count { it == "SET" })
        assertEquals(stepped, c.device.settings.values["balance"])
        assertFalse(exists("set.confirm"))
        Espresso.pressBack()
        settle(1000)
        assertEquals(Page.Home, shell.nav.top)
    }

    @Test
    fun pivotAndFocusSurviveRecreation() {
        val restore = StateRestorationTester(rule)
        val c = start(restore)
        pivot("library")
        pivot("display")
        focusOn("artistPhoto")
        restore.emulateSavedInstanceStateRestore()
        settle(1000)
        assertTrue(exists("set.artistPhoto"))
        insertBefore(c, "clock", row("navOrder", "display"))
        settle(600)
        assertTrue(barOn("artistPhoto"))
    }

    @Test
    fun aClearedSchemaDrawsNoStaleRows() {
        val c = start()
        pivot("library")
        pivot("display")
        assertTrue(exists("set.brightness"))
        schema(c) { emptyList() }
        settle(600)
        assertFalse(exists("set.brightness"))
    }

    @Test
    fun anEmptySchemaKeepsThePivotIndexForTheFallback() {
        val c = start()
        for (p in listOf("library", "display", "power")) pivot(p)
        val all = c.device.schema.rows
        schema(c) { emptyList() }
        settle(600)
        schema(c) { all.filter { it.pivot != "power" } }
        settle(1000)
        assertTrue(exists("set.usbMode"))
    }

    @Test
    fun aRangeEditWhoseRowMovesToAnotherPivotEnds() {
        val c = start()
        rule.onNodeWithTag("set.balance").performClick()
        settle(600)
        rule.onNodeWithTag("set.balance").performTouchInput { click(Offset(width * 0.9f, height / 2f)) }
        settle(600)
        val sets = c.sent.count { it == "SET" }
        schema(c) { rows -> rows.map { if (it.key == "balance") it.copy(pivot = "display") else it } }
        settle(600)
        assertEquals(sets, c.sent.count { it == "SET" })
        Espresso.pressBack()
        settle(1000)
        assertEquals(Page.Home, shell.nav.top)
    }

    @Test
    fun aFallbackAfterARebuildJumps() {
        val c = start()
        for (p in listOf("library", "display", "power")) pivot(p)
        focusOn("inlineRemote")
        val from = barTop()
        drop(c, "inlineRemote", "screenOffKeys")
        val seen = frames(8)
        settle(600)
        assertTrue(barOn("lockKnob"))
        val to = barTop()
        assertTrue(abs(to - from) > 1f)
        assertTrue(seen.all { abs(it - from) < 1f || abs(it - to) < 1f })
    }

    @Test
    fun aSchemaArrivingAfterARestoreShowsTheSavedPivotWithNoMotion() {
        val restore = StateRestorationTester(rule)
        val c = start(restore)
        for (p in listOf("library", "display", "power")) pivot(p)
        val all = c.device.schema.rows
        val left = bounds("set.lockKnob").left
        schema(c) { emptyList() }
        restore.emulateSavedInstanceStateRestore()
        settle(1000)
        schema(c) { all }
        val seen = (1..30).mapNotNull {
            frame()
            assertFalse(exists("set.output") || exists("set.brightness") || exists("set.usbMode"))
            if (exists("set.lockKnob")) bounds("set.lockKnob").left else null
        }
        assertTrue(seen.size >= 29)
        assertTrue(seen.all { abs(it - left) < 0.5f })
    }
}
