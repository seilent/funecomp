package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.espresso.Espresso
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tags
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.Ui
import net.seilent.funecomp.proto.gen.Tok
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class SelectorUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val shell = Shell(Nav(listOf(Page.Home, Page.Settings)))

    private var role = Enums.BtRole.receiver

    private fun start(board: String = "ap80", to: String? = null): SimControl = startAt(411, 960, board, to)

    private fun startAt(w: Int, h: Int, board: String = "ap80", to: String? = null): SimControl = startAt(w.dp, h.dp, board, to)

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private val owner = Owner()

    private fun startAt(w: Dp, h: Dp, board: String = "ap80", to: String? = null): SimControl {
        val c = SimControl(board, btRole = role)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                Box(Modifier.requiredSize(w, h)) { App(c.device, LinkView(Phase.LIVE), c, shell) }
            }
        }
        settle(1000)
        to?.let { rule.walkPivots(c.device.schema.rows, it) { settle(1000) } }
        return c
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun tap(key: String) {
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("rows").performScrollToNode(hasTestTag("set.$key"))
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("set.$key").performClick()
        settle(600)
    }

    private fun pick(i: Int) {
        rule.onNodeWithTag("sheet.item.$i").performClick()
        settle(600)
    }

    private fun shows(key: String, text: String) {
        rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag("set.$key"))).assertExists()
    }

    private fun writes(c: SimControl) = c.sent.filter { it == "SET" || it == "DRAFT" }

    private fun sets(c: SimControl) = c.sent.count { it == "SET" }

    private fun drafts(c: SimControl) = c.sent.count { it == "DRAFT" }

    @Test
    fun volumeStepPickAp80() = volumeStepPick("ap80")

    @Test
    fun volumeStepPickV1() = volumeStepPick("v1")

    private fun volumeStepPick(board: String) {
        val c = start(board)
        tap("volumeStep")
        assertTrue(shell.sheet.shown)
        assertEquals("volume step", shell.sheet.title)
        assertEquals(listOf("2%", "5%", "10%"), shell.sheet.items)
        assertEquals(1, shell.sheet.focus)
        assertEquals(emptyList<String>(), writes(c))
        pick(2)
        assertFalse(shell.sheet.shown)
        assertEquals(listOf("SET"), writes(c))
        assertEquals("10", c.device.settings.values["volumeStep"])
        shows("volumeStep", "10%")
    }

    private fun clearsThePanel(key: String, w: Int, h: Int) {
        val d = rule.density.density
        clearsThePanelPx(key, (w * d).roundToInt().toFloat(), (h * d).roundToInt().toFloat())
    }

    private fun clearsThePanelPx(key: String, w: Float, h: Float) {
        val gutter = Ui.of(w, h).px(Tok.gutter)
        val row = rule.onNodeWithTag("set.$key").fetchSemanticsNode().boundsInRoot
        val panel = rule.onNodeWithTag("sheet.panel").fetchSemanticsNode().boundsInRoot
        assertTrue("${row.bottom} ${panel.top} $gutter", row.bottom <= panel.top - gutter)
    }

    private fun top(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top

    private fun scrolledTo(key: String): Float {
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("rows").performScrollToNode(hasTestTag("set.$key"))
        rule.mainClock.autoAdvance = false
        settle(300)
        return top("set.$key")
    }

    @Test
    fun theLastValueRowScrollsPastTheEndAndBack() {
        val c = startAt(411, 640, "ap80", "screens")
        val before = scrolledTo("navBar")
        rule.onNodeWithTag("set.navBar").performClick()
        settle(600)
        assertTrue(shell.sheet.shown)
        assertTrue(top("set.navBar") < before)
        clearsThePanel("navBar", 411, 640)
        val open = top("set.navBar")
        shell.sheet.close()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(Tok.motion_scroll.toLong() / 2)
        val half = top("set.navBar")
        assertTrue("$open $half $before", half - open > (before - open) / 2)
        rule.mainClock.advanceTimeBy(Tok.motion_scroll.toLong())
        assertEquals(before, top("set.navBar"), 1.5f)
        assertTrue(rule.onAllNodesWithTag("sheet.panel").fetchSemanticsNodes().isNotEmpty())
        settle(1000)
        assertEquals(before, top("set.navBar"), 1.5f)
        assertEquals(emptyList<String>(), writes(c))
    }

    private fun pastTheEnd(): Float {
        startAt(411, 640, "ap80", "screens")
        val before = scrolledTo("navBar")
        rule.onNodeWithTag("set.navBar").performClick()
        settle(600)
        assertTrue(shell.sheet.shown)
        assertTrue(shell.sheet.pastList != null)
        return before
    }

    private fun atItsEnd(before: Float) {
        assertEquals(0f, shell.sheet.past)
        assertNull(shell.sheet.pastList)
        assertEquals(before, top("set.navBar"), 1.5f)
    }

    @Test
    fun aBackDuringThePreScrollPutsTheListAtItsEndAtOnce() {
        startAt(411, 640, "ap80", "screens")
        val before = scrolledTo("navBar")
        rule.onNodeWithTag("set.navBar").performClick()
        val end = rule.mainClock.currentTime + Tok.motion_scroll.toLong()
        while (top("set.navBar") >= before && rule.mainClock.currentTime < end) rule.mainClock.advanceTimeByFrame()
        assertTrue(shell.sheet.pastList != null)
        assertTrue(top("set.navBar") < before)
        rule.runOnIdle { shell.sheet.close() }
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        atItsEnd(before)
    }

    @Test
    fun aSyntheticKeeplessSwapPutsTheListAtItsEndAtOnce() {
        val before = pastTheEnd()
        rule.runOnIdle { shell.sheet.openEntry("minutes") {} }
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        atItsEnd(before)
    }

    @Test
    fun aSheetReplacedByAnotherWithAKeepKeepsItsRowAbovePanel() {
        val before = pastTheEnd()
        val keep = shell.sheet.keep
        rule.runOnIdle { shell.sheet.open("again", listOf("a", "b"), keep = keep) {} }
        settle(600)
        assertTrue(shell.sheet.shown)
        assertTrue(top("set.navBar") < before)
        clearsThePanel("navBar", 411, 640)
    }

    @Test
    fun aSheetReplacedByAShorterOneOnTheSameListLandsTheRowJustAboveIt() {
        val before = pastTheEnd()
        val keep = shell.sheet.keep
        val first = rule.onNodeWithTag("sheet.panel").fetchSemanticsNode().boundsInRoot.top
        rule.runOnIdle { shell.sheet.open("again", listOf("a"), keep = keep) {} }
        settle(600)
        val panel = rule.onNodeWithTag("sheet.panel").fetchSemanticsNode().boundsInRoot
        assertTrue("${panel.top} $first", panel.top > first)
        val d = rule.density.density
        val gutter = Ui.of((411 * d).roundToInt().toFloat(), (640 * d).roundToInt().toFloat()).px(Tok.gutter)
        val row = rule.onNodeWithTag("set.navBar").fetchSemanticsNode().boundsInRoot
        assertTrue("${row.top} $before", row.top < before)
        assertEquals(panel.top - gutter, row.bottom, 1.5f)
    }

    @Test
    fun aCloseAfterAPageChangePutsTheListAtItsEndAtOnce() {
        pastTheEnd()
        rule.runOnIdle {
            shell.nav.reset(listOf(Page.Home, Page.NowPlaying))
            shell.sheet.close()
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        assertEquals(0f, shell.sheet.past)
        assertNull(shell.sheet.pastList)
    }

    @Test
    fun aCloseWhileStoppedPutsTheListAtItsEndAtOnce() {
        val before = pastTheEnd()
        rule.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            shell.sheet.close()
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        atItsEnd(before)
    }

    @Test
    fun aCloseOnItsPageReturnsWithTheClose() {
        val before = pastTheEnd()
        rule.runOnIdle { shell.sheet.close() }
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        assertTrue(shell.sheet.pastList != null)
        settle(1000)
        atItsEnd(before)
    }

    private fun rowClearsTheGutterAt(w: Dp, h: Dp) {
        startAt(w, h, "ap80", "library")
        scrolledTo("resume")
        rule.onNodeWithTag("set.resume").performClick()
        settle(600)
        assertTrue(shell.sheet.shown)
        val d = rule.density.density
        clearsThePanelPx("resume", (w.value * d).roundToInt().toFloat(), (h.value * d).roundToInt().toFloat())
    }

    private fun rowStaysInPlaceAt(w: Dp, h: Dp) {
        startAt(w, h, "ap80", "library")
        val before = scrolledTo("resume")
        rule.onNodeWithTag("set.resume").performClick()
        settle(600)
        assertTrue(shell.sheet.shown)
        val d = rule.density.density
        val ui = Ui.of((w.value * d).roundToInt().toFloat(), (h.value * d).roundToInt().toFloat())
        val room = rule.onNodeWithTag("sheet.panel").fetchSemanticsNode().boundsInRoot.top - ui.px(Tok.gutter) - rule.onNodeWithTag("rows").fetchSemanticsNode().boundsInRoot.top
        assertTrue("$room", room < ui.px(Tok.rowHeight))
        assertEquals(before, top("set.resume"), 1.5f)
    }

    @Test
    @Config(qualifiers = "w362dp-h400dp-420dpi")
    fun aShortNarrowBoxRowClearsTheGutterInWholePixels() = rowClearsTheGutterAt((948 / 2.625f).dp, (1048 / 2.625f).dp)

    @Test
    @Config(qualifiers = "w474dp-h420dp-420dpi")
    fun theCoverScreenSheetTallerThanTheRoomLeavesTheRowInPlace() = rowStaysInPlaceAt(COVER_W.dp, COVER_H.dp)

    @Test
    @Config(qualifiers = "w362dp-h400dp-420dpi")
    fun aShortWideSheetTallerThanTheRoomLeavesTheRowInPlace() = rowStaysInPlaceAt(361.dp, 343.dp)

    @Test
    fun aLandscapeSheetTallerThanTheRoomLeavesTheLastRowInPlace() {
        startAt(960, 411, "ap80", "library")
        val before = scrolledTo("resume")
        rule.onNodeWithTag("set.resume").performClick()
        settle(600)
        assertTrue(shell.sheet.shown)
        assertEquals(before, top("set.resume"), 1.5f)
    }

    @Test
    fun aValueSheetScrollsItsRowAboveThePanel() {
        val c = startAt(411, 640, "ap80", "screens")
        val before = top("set.artistPhoto")
        tap("artistPhoto")
        assertTrue(shell.sheet.shown)
        assertTrue(top("set.artistPhoto") < before)
        clearsThePanel("artistPhoto", 411, 640)
        val row = rule.onNodeWithTag("set.artistPhoto").fetchSemanticsNode().boundsInRoot
        assertTrue(row.top >= rule.onNodeWithTag("rows").fetchSemanticsNode().boundsInRoot.top)
        shell.sheet.close()
        settle(600)
        assertEquals(emptyList<String>(), writes(c))
    }

    @Test
    fun pickOfTheLiveValueSendsNothing() {
        val c = start()
        tap("volumeStep")
        pick(1)
        assertEquals(emptyList<String>(), writes(c))
        shows("volumeStep", "5%")
    }

    @Test
    fun scrimAndBackSendNothing() {
        val c = start()
        tap("volumeStep")
        rule.onRoot().performTouchInput { click(topCenter.copy(y = top + 10f)) }
        settle(600)
        assertFalse(shell.sheet.shown)
        tap("volumeStep")
        assertTrue(shell.sheet.shown)
        Espresso.pressBack()
        settle(600)
        assertFalse(shell.sheet.shown)
        assertEquals(listOf(Page.Home, Page.Settings), shell.nav.stack.toList())
        assertEquals(emptyList<String>(), writes(c))
    }

    @Test
    fun aLocalChangeWhileOpenIsReadAtPick() {
        val c = start()
        tap("volumeStep")
        assertEquals(1, shell.sheet.focus)
        c.set("volumeStep", "10")
        settle(300)
        assertTrue(shell.sheet.shown)
        pick(1)
        assertEquals(2, sets(c))
        assertEquals("5", c.device.settings.values["volumeStep"])
        tap("volumeStep")
        c.set("volumeStep", "2")
        settle(300)
        pick(0)
        assertEquals(3, sets(c))
    }

    @Test
    fun usbModePickSendsOneDraft() {
        val c = start("ap80", "connect")
        tap("usbMode")
        assertEquals(listOf("PROMPT", "STORAGE", "DOCK", "AUDIO"), shell.sheet.items)
        assertEquals(0, shell.sheet.focus)
        pick(2)
        assertEquals(listOf("DRAFT"), writes(c))
        assertEquals("dock", c.device.settings.values["usbMode"])
        tap("usbMode")
        assertEquals(2, shell.sheet.focus)
        pick(2)
        assertEquals(1, drafts(c))
        shows("usbMode", "DOCK")
    }

    @Test
    fun codecPickIsADraftOnTheBluetoothPivotAp80() = codecPick("ap80")

    @Test
    fun codecPickIsADraftOnTheBluetoothPivotV1() = codecPick("v1")

    private fun codecPick(board: String) {
        val c = start(board, "bluetooth")
        tap("btCodec")
        assertEquals("codec", shell.sheet.title)
        assertEquals(listOf("AUTO", "APTX", "AAC", "SBC"), shell.sheet.items)
        pick(2)
        assertEquals(listOf("DRAFT"), writes(c))
        assertEquals(Enums.BtRole.receiver, c.draftBodies.single().int(Tags.Draft.role))
        assertEquals("aac", c.device.settings.values["btCodec"])
    }

    @Test
    fun theHeadphonesCodecPickIsADraftWithItsRole() {
        role = Enums.BtRole.headphones
        val c = start("v1", "bluetooth")
        tap("btCodecHeadphones")
        assertEquals("codec", shell.sheet.title)
        assertEquals(listOf("AUTO", "LDAC", "APTX", "AAC", "SBC"), shell.sheet.items)
        pick(1)
        assertEquals(listOf("DRAFT"), writes(c))
        assertEquals(Enums.BtRole.headphones, c.draftBodies.single().int(Tags.Draft.role))
        assertEquals("ldac", c.device.settings.values["btCodecHeadphones"])
        shows("btCodecHeadphones", "LDAC")
    }

    @Test
    fun aToggleTapOpensTheOffOnSheet() {
        val c = start("ap80", "screens")
        assertEquals("on", c.device.settings.values["quickplay"])
        tap("quickplay")
        assertTrue(shell.sheet.shown)
        assertEquals("quickplay", shell.sheet.title)
        assertEquals(listOf("OFF", "ON"), shell.sheet.items)
        assertEquals(1, shell.sheet.focus)
        assertEquals(emptyList<String>(), writes(c))
        assertEquals("on", c.device.settings.values["quickplay"])
        shell.sheet.close()
        settle(600)
        assertEquals(emptyList<String>(), writes(c))
        tap("quickplay")
        pick(1)
        assertEquals(emptyList<String>(), writes(c))
        tap("quickplay")
        pick(0)
        assertEquals(listOf("SET"), writes(c))
        assertEquals("off", c.device.settings.values["quickplay"])
        shows("quickplay", "OFF")
    }

    @Test
    fun aReadOnlyToggleDoesNothing() {
        val c = start("ap80", "system")
        tap("companion")
        assertFalse(shell.sheet.shown)
        assertEquals(emptyList<String>(), writes(c))
    }

    @Test
    fun balanceStepsAndTheHeaderReverts() {
        val c = start()
        tap("balance")
        assertFalse(shell.sheet.shown)
        rule.onNodeWithTag("set.balance").performTouchInput { click(centerRight) }
        settle(300)
        assertEquals("1", c.device.settings.values["balance"])
        rule.onNodeWithTag("header").performClick()
        settle(300)
        assertEquals("0", c.device.settings.values["balance"])
        assertEquals(2, sets(c))
        assertEquals(listOf(Page.Home, Page.Settings), shell.nav.stack.toList())
    }

    @Test
    fun tapBelowTheRowsConfirmsARangeEdit() {
        val c = start()
        tap("balance")
        rule.onNodeWithTag("set.balance").performTouchInput { click(centerRight) }
        settle(300)
        rule.onRoot().performTouchInput { click(bottomCenter.copy(y = bottom - 10f)) }
        settle(300)
        assertEquals(1, sets(c))
        assertEquals("1", c.device.settings.values["balance"])
        rule.onNodeWithTag("set.balance").performTouchInput { click(centerRight) }
        settle(300)
        assertEquals(1, sets(c))
    }

    @Test
    fun aSchemaWithoutTheRowClosesItsSelector() {
        val c = start()
        tap("volumeStep")
        val s = c.device.schema
        c.device = c.device.copy(schema = s.copy(rows = s.rows.filter { it.key != "volumeStep" }))
        settle(600)
        assertFalse(shell.sheet.shown)
        assertEquals(emptyList<String>(), writes(c))
    }

    @Test
    fun aSchemaTurningAToggleReadOnlyClosesItsSheet() {
        val c = start("ap80", "screens")
        tap("quickplay")
        assertTrue(shell.sheet.shown)
        val s = c.device.schema
        c.device = c.device.copy(schema = s.copy(rows = s.rows.map { if (it.key == "quickplay") it.copy(flags = listOf(Enums.RowFlag.readOnly)) else it }))
        settle(600)
        assertFalse(shell.sheet.shown)
        assertEquals(emptyList<String>(), writes(c))
    }

    @Test
    fun aSchemaWithOtherValuesClosesItsSelector() {
        val c = start()
        tap("volumeStep")
        val s = c.device.schema
        c.device = c.device.copy(schema = s.copy(rows = s.rows.map { if (it.key == "volumeStep") it.copy(values = listOf("1", "5", "10"), words = listOf("1%", "5%", "10%")) else it }))
        settle(600)
        assertFalse(shell.sheet.shown)
        assertEquals(emptyList<String>(), writes(c))
    }

    @Test
    fun aSchemaWithOtherWordsClosesItsSelector() {
        val c = start()
        tap("volumeStep")
        val s = c.device.schema
        c.device = c.device.copy(schema = s.copy(rows = s.rows.map { if (it.key == "volumeStep") it.copy(words = listOf("2 %", "5 %", "10 %")) else it }))
        settle(600)
        assertFalse(shell.sheet.shown)
        assertEquals(emptyList<String>(), writes(c))
    }

    @Test
    fun aSchemaWithOtherFlagsClosesItsSelector() {
        val c = start()
        tap("volumeStep")
        val s = c.device.schema
        c.device = c.device.copy(schema = s.copy(rows = s.rows.map { if (it.key == "volumeStep") it.copy(flags = listOf(Enums.RowFlag.readOnly)) else it }))
        settle(600)
        assertFalse(shell.sheet.shown)
        assertEquals(emptyList<String>(), writes(c))
    }

    @Test
    @Config(qualifiers = "w474dp-h420dp-420dpi")
    fun aFocusedItemBelowManyInfoLinesShowsOnOpen() {
        val c = SimControl("ap80")
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(COVER_W.dp, COVER_H.dp)) { App(c.device, LinkView(Phase.LIVE), c, shell) }
        }
        settle(1000)
        shell.sheet.open("confirm", listOf("apply", "cancel"), info = (1..12).map { "line $it" }) {}
        settle(600)
        rule.onNodeWithTag("sheet.item.0").assertIsDisplayed()
    }

    @Test
    fun aSchemaChangeLeavesAnotherSheetOpen() {
        val c = start()
        tap("volumeStep")
        var later = 0
        shell.sheet.open("background", listOf("allow", "not now"), onDismiss = { later++ }) {}
        settle(300)
        val s = c.device.schema
        c.device = c.device.copy(schema = s.copy(rows = s.rows.filter { it.key != "volumeStep" }))
        settle(600)
        assertTrue(shell.sheet.shown)
        assertEquals("background", shell.sheet.title)
        assertEquals(0, later)
    }

    @Test
    fun anUnchangedSchemaKeepsTheSelector() {
        val c = start()
        tap("volumeStep")
        val s = c.device.schema
        c.device = c.device.copy(schema = s.copy(rows = s.rows.map { if (it.key == "volumeStep") it.copy(label = "STEP") else it }, rev = (s.rev ?: 0) + 1))
        settle(600)
        assertTrue(shell.sheet.shown)
        pick(2)
        assertEquals(listOf("SET"), writes(c))
    }
}
