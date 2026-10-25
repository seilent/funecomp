package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import net.seilent.funecomp.proto.gen.Enums
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

    private fun start(board: String = "ap80", vararg pivots: String): SimControl = startAt(411, 960, board, *pivots)

    private fun startAt(w: Int, h: Int, board: String = "ap80", vararg pivots: String): SimControl = startAt(w.dp, h.dp, board, *pivots)

    private fun startAt(w: Dp, h: Dp, board: String = "ap80", vararg pivots: String): SimControl {
        val c = SimControl(board)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(w, h)) { App(c.device, LinkView(Phase.LIVE), c, shell) }
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
        val c = start()
        val before = scrolledTo("resume")
        rule.onNodeWithTag("set.resume").performClick()
        settle(600)
        assertTrue(shell.sheet.shown)
        clearsThePanel("resume", 411, 960)
        shell.sheet.close()
        settle(1000)
        assertEquals(before, top("set.resume"), 1.5f)
        assertEquals(emptyList<String>(), writes(c))
    }

    @Test
    @Config(qualifiers = "w362dp-h400dp-420dpi")
    fun theCoverScreenRowClearsTheGutterInWholePixels() {
        startAt((948 / 2.625f).dp, (1048 / 2.625f).dp)
        scrolledTo("resume")
        rule.onNodeWithTag("set.resume").performClick()
        settle(600)
        assertTrue(shell.sheet.shown)
        clearsThePanelPx("resume", 948f, 1048f)
    }

    @Test
    fun aLandscapeSheetTallerThanTheRoomLeavesTheLastRowInPlace() {
        startAt(960, 411)
        val before = scrolledTo("resume")
        rule.onNodeWithTag("set.resume").performClick()
        settle(600)
        assertTrue(shell.sheet.shown)
        assertEquals(before, top("set.resume"), 1.5f)
    }

    @Test
    fun aValueSheetScrollsItsRowAboveThePanel() {
        val c = start()
        tap("crossfeed")
        assertTrue(shell.sheet.shown)
        clearsThePanel("crossfeed", 411, 960)
        val row = rule.onNodeWithTag("set.crossfeed").fetchSemanticsNode().boundsInRoot
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
        val c = start("ap80", "library", "display", "power", "system")
        tap("usbMode")
        assertEquals(listOf("STORAGE", "DOCK", "AUDIO"), shell.sheet.items)
        assertEquals(0, shell.sheet.focus)
        pick(1)
        assertEquals(listOf("DRAFT"), writes(c))
        assertEquals("dock", c.device.settings.values["usbMode"])
        tap("usbMode")
        assertEquals(1, shell.sheet.focus)
        pick(1)
        assertEquals(1, drafts(c))
        shows("usbMode", "DOCK")
    }

    @Test
    fun codecPickIsADraftOnTheBluetoothPivotAp80() = codecPick("ap80")

    @Test
    fun codecPickIsADraftOnTheBluetoothPivotV1() = codecPick("v1")

    private fun codecPick(board: String) {
        val c = start(board, "library", "display", "power", "system", "bluetooth")
        tap("btCodec")
        assertEquals("codec", shell.sheet.title)
        assertEquals(listOf("AUTO", "APTX", "AAC", "SBC"), shell.sheet.items)
        pick(2)
        assertEquals(listOf("DRAFT"), writes(c))
        assertEquals("aac", c.device.settings.values["btCodec"])
    }

    @Test
    fun aToggleTapOpensTheOffOnSheet() {
        val c = start("ap80", "library", "display")
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
        val c = start("ap80", "library", "display", "power", "system")
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
        val c = start("ap80", "library", "display")
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
    @Config(qualifiers = "w361dp-h399dp-420dpi")
    fun aFocusedItemBelowManyInfoLinesShowsOnOpen() {
        val c = SimControl("ap80")
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(361.dp, 399.dp)) { App(c.device, LinkView(Phase.LIVE), c, shell) }
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
