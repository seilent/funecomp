package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.FirmwareView
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.debugPages
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
class NoUpdaterTest {
    @get:Rule
    val rule = createComposeRule()

    private val settingsPages = listOf(Page.Home, Page.Settings)
    private val firmwarePages = settingsPages + Page.Firmware
    private var firmware by mutableStateOf(FirmwareView())
    private var link by mutableStateOf(LinkView(Phase.LIVE, "V1", everLive = true))
    private lateinit var c: SimControl
    private lateinit var shell: Shell

    private fun start(pages: List<Page>, faults: List<String> = emptyList(), board: String = "v1", edit: (DeviceState) -> DeviceState = { it }) {
        c = SimControl(board, faults = faults)
        c.device = edit(c.device)
        shell = Shell(Nav(pages))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, link, c, shell, firmware = firmware) }
        }
        settle(1000)
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun noUpdater(): HelloReply = SimControl("v1", faults = NO_UPDATER_FAULTS).device.hello!!

    private fun hello(h: HelloReply) {
        val d = c.device
        c.device = d.withHello(h, LINK_FEATURES).copy(schema = d.schema, settings = d.settings, playback = d.playback)
    }

    private fun relinked(h: HelloReply) {
        c.device = c.device.unlinked()
        settle(600)
        hello(h)
        settle(1000)
    }

    private fun system() = rule.walkPivots(c.device.schema.rows, "system") { settle(1000) }

    private fun tapAbout() {
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("rows").performScrollToNode(hasTestTag("set.about"))
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("set.about").performClick()
        settle(600)
    }

    private fun tapItem(name: String) {
        rule.onNodeWithTag("sheet.item.${shell.sheet.items.indexOf(name)}").performClick()
        settle(1000)
    }

    private fun tap(tag: String) {
        rule.onNodeWithTag(tag).performClick()
        settle(1000)
    }

    private fun accept(i: Int) {
        rule.runOnIdle { shell.sheet.accept(i) }
        settle(1000)
    }

    private fun picked() {
        tap("fw.pick")
        accept(0)
        assertEquals("funeos-v0.4.0-v1.upt", shell.sheet.title)
    }

    @Test
    fun aDeviceWithoutTheUpdaterListsInstallAppAndSupportMe() {
        start(settingsPages, NO_UPDATER_FAULTS)
        system()
        tapAbout()
        assertEquals(listOf("install app", "support me"), shell.sheet.items)
        firmware = FirmwareView(send = "firmware 42%")
        settle(300)
        assertEquals(listOf("", ""), shell.sheet.itemValues)
        tapItem("install app")
        assertEquals(settingsPages + Page.Apps, shell.nav.stack.toList())
    }

    @Test
    fun anAp80WithoutTheUpdaterHasNoUpdateItem() {
        start(settingsPages, NO_UPDATER_FAULTS, board = "ap80")
        system()
        tapAbout()
        assertEquals(listOf("install app", "support me"), shell.sheet.items)
    }

    @Test
    fun supportMeKeepsItsPageWithoutTheUpdater() {
        start(settingsPages, NO_UPDATER_FAULTS)
        system()
        tapAbout()
        assertEquals(listOf("install app", "support me"), shell.sheet.items)
        tapItem("support me")
        assertEquals(1, c.supports)
        assertEquals(settingsPages, shell.nav.stack.toList())
    }

    @Test
    fun aHelloWithoutTheUpdaterPopsTheFirmwarePageAndClosesItsSheet() {
        start(firmwarePages)
        picked()
        relinked(noUpdater())
        assertEquals(settingsPages, shell.nav.stack.toList())
        assertFalse(shell.sheet.shown)
    }

    @Test
    fun theBackgroundAskStaysOpenWhenThePageGoes() {
        link = LinkView(Phase.LIVE, "V1", everLive = true, askBattery = true)
        start(firmwarePages)
        assertEquals("background", shell.sheet.title)
        val opened = shell.sheet.opened
        relinked(noUpdater())
        assertEquals(settingsPages, shell.nav.stack.toList())
        assertTrue(shell.sheet.shown)
        assertEquals("background", shell.sheet.title)
        assertEquals(opened, shell.sheet.opened)
        assertEquals(0, c.laters)
    }

    @Test
    fun theAutoCheckSheetGoesWithThePage() {
        start(firmwarePages)
        tap("fw.daily")
        assertEquals("auto check", shell.sheet.title)
        relinked(noUpdater())
        assertEquals(settingsPages, shell.nav.stack.toList())
        assertFalse(shell.sheet.shown)
    }

    @Test
    fun aPickThatReturnsAfterThePopOpensNothing() {
        start(firmwarePages)
        c.holdPick = true
        tap("fw.pick")
        accept(0)
        assertFalse(shell.sheet.shown)
        relinked(noUpdater())
        assertEquals(settingsPages, shell.nav.stack.toList())
        rule.runOnIdle { c.picked!!() }
        settle(600)
        assertFalse(shell.sheet.shown)
    }

    @Test
    fun anUpdaterRelinkKeepsThePageAndItsSheet() {
        start(firmwarePages)
        picked()
        relinked(c.device.hello!!)
        assertEquals(firmwarePages, shell.nav.stack.toList())
        assertTrue(shell.sheet.shown)
        assertEquals("funeos-v0.4.0-v1.upt", shell.sheet.title)
    }

    @Test
    fun aDropKeepsTheItemHiddenOnADeviceWithoutTheUpdater() {
        start(settingsPages, NO_UPDATER_FAULTS)
        c.device = c.device.unlinked()
        settle(600)
        system()
        tapAbout()
        assertEquals(listOf("install app", "support me"), shell.sheet.items)
    }

    @Test
    fun anOpenAboutSheetClosesAtAHelloWithoutTheUpdater() {
        start(settingsPages)
        system()
        tapAbout()
        assertEquals(listOf("update firmware", "install app", "support me"), shell.sheet.items)
        relinked(noUpdater())
        assertFalse(shell.sheet.shown)
    }

    @Test
    fun aBackDuringAHelloWithoutTheUpdaterEndsAtSettings() {
        val h = noUpdater()
        start(firmwarePages)
        c.device = c.device.unlinked()
        settle(600)
        rule.runOnIdle { shell.nav.pop() }
        rule.mainClock.advanceTimeBy(16)
        hello(h)
        settle(1000)
        assertEquals(settingsPages, shell.nav.stack.toList())
    }

    @Test
    fun theDebugRouteBeforeAnyLinkPopsAtAHelloWithoutTheUpdater() {
        start(debugPages("firmware")!!) { DeviceState(schema = it.schema, settings = it.settings, playback = it.playback) }
        assertEquals(firmwarePages, shell.nav.stack.toList())
        hello(noUpdater())
        settle(1000)
        assertEquals(settingsPages, shell.nav.stack.toList())
    }

    @Test
    fun theTreeNeverSaysTheDeviceHasNoUpdater() {
        firmware = FirmwareView(info = "device has no updater")
        start(firmwarePages, NO_UPDATER_FAULTS)
        assertEquals(settingsPages, shell.nav.stack.toList())
        assertEquals(0, rule.onAllNodes(hasText("device has no updater", ignoreCase = true), useUnmergedTree = true).fetchSemanticsNodes().size)
    }
}
