package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.FirmwareView
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.batteryText
import net.seilent.funecomp.ui.cardText
import net.seilent.funecomp.ui.firmwareText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class AboutTest {
    @get:Rule
    val rule = createComposeRule()

    private var firmware by mutableStateOf(FirmwareView())
    private val shell = Shell(Nav(listOf(Page.Home, Page.Settings)))

    private fun start(board: String = "ap80", edit: (DeviceState) -> DeviceState = { it }): SimControl {
        val c = SimControl(board)
        c.device = edit(c.device)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), c, shell, firmware = firmware) }
        }
        settle(1000)
        return c
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun system() {
        for (p in listOf("library", "display", "power", "system")) {
            rule.onNodeWithTag("pivot.$p").performClick()
            settle(1000)
        }
    }

    private fun tapAbout() {
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("rows").performScrollToNode(hasTestTag("set.about"))
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("set.about").performClick()
        settle(600)
    }

    private fun noAbout(d: DeviceState) = d.copy(schema = d.schema.copy(rows = d.schema.rows.filter { it.key != "about" }))

    @Test
    fun aboutRowShowsTheVersionAndOpensTheSheet() {
        val c = start()
        val h = c.device.hello!!
        val st = c.device.playback.state
        system()
        tapAbout()
        rule.onNode(hasText(h.version!!.uppercase()) and hasAnyAncestor(hasTestTag("set.about")), useUnmergedTree = true).assertExists()
        assertEquals("about", shell.sheet.title)
        assertEquals(listOf("funeOS ${h.version}", "seilent", "board", "dap battery", "card"), shell.sheet.info)
        assertEquals(listOf("", "", h.board, if (st.battery != null) batteryText(st) else "", cardText(st) ?: ""), shell.sheet.infoValues)
        assertEquals(listOf(true, true), shell.sheet.infoMixed)
        assertEquals(listOf("update firmware"), shell.sheet.items)
        assertEquals(listOf(firmwareText(firmware)), shell.sheet.itemValues)
    }

    @Test
    fun theFirmwareValueFollowsTheViewAndThePickOpensFirmware() {
        start()
        system()
        tapAbout()
        firmware = FirmwareView(send = "firmware 42%")
        settle(300)
        assertEquals(listOf("42%"), shell.sheet.itemValues)
        rule.onNode(hasText("42%") and hasAnyAncestor(hasTestTag("sheet.item.0")), useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("sheet.item.0").performClick()
        settle(1000)
        assertFalse(shell.sheet.shown)
        assertEquals(listOf(Page.Home, Page.Settings, Page.Firmware), shell.nav.stack.toList())
    }

    @Test
    fun aSchemaWithoutAboutGetsThePhoneRowOnSystem() {
        start(edit = ::noAbout)
        system()
        tapAbout()
        assertEquals("about", shell.sheet.title)
    }

    @Test
    fun aSchemaWithoutSystemGetsTheSystemPivot() {
        start { d -> d.copy(schema = d.schema.copy(rows = d.schema.rows.filter { it.pivot != "system" })) }
        system()
        tapAbout()
        assertEquals("about", shell.sheet.title)
    }

    @Test
    fun anEmptySchemaHasNoSystemPivotAndNoAboutRow() {
        start { d -> d.copy(schema = d.schema.copy(rows = emptyList())) }
        assertEquals(0, rule.onAllNodesWithTag("pivot.system").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithTag("set.about").fetchSemanticsNodes().size)
    }

    @Test
    fun withoutHelloTheSheetHasTheCreditAndTheItem() {
        start { d -> d.copy(hello = null) }
        system()
        tapAbout()
        assertEquals(listOf("seilent"), shell.sheet.info)
        assertEquals(listOf("update firmware"), shell.sheet.items)
    }

    @Test
    fun batteryAndCardRowsWaitForTheirValuesWithoutMovingThePanel() {
        val c = start { d -> d.copy(playback = d.playback.copy(state = d.playback.state.copy(battery = null, charging = null, cardFreeMiB = null, cardTotalMiB = null))) }
        system()
        tapAbout()
        assertEquals(5, shell.sheet.info.size)
        assertEquals("", shell.sheet.infoValues[3])
        assertEquals("", shell.sheet.infoValues[4])
        val before = rule.onNodeWithTag("sheet.info").fetchSemanticsNode().boundsInRoot.top
        val p = c.device.playback
        c.device = c.device.copy(playback = p.copy(state = p.state.copy(battery = 80, cardFreeMiB = 2048, cardTotalMiB = 4096)))
        settle(300)
        assertEquals("80%", shell.sheet.infoValues[3])
        assertNotNull(cardText(c.device.playback.state))
        assertEquals(cardText(c.device.playback.state), shell.sheet.infoValues[4])
        assertEquals(before, rule.onNodeWithTag("sheet.info").fetchSemanticsNode().boundsInRoot.top, 0.5f)
        assertTrue(shell.sheet.shown)
    }
}
