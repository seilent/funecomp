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
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.AppsView
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
class NoInstallerTest {
    @get:Rule
    val rule = createComposeRule()

    private val settingsPages = listOf(Page.Home, Page.Settings)
    private val appsPages = settingsPages + Page.Apps
    private var apps by mutableStateOf(AppsView())
    private var link by mutableStateOf(LinkView(Phase.LIVE, "V1", everLive = true))
    private lateinit var c: SimControl
    private lateinit var shell: Shell

    private fun start(pages: List<Page>, faults: List<String> = emptyList(), board: String = "v1", minor: Int = Proto.minor) {
        c = SimControl(board, minor = minor, faults = faults)
        shell = Shell(Nav(pages))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, link, c, shell, apps = apps) }
        }
        settle(1000)
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun noInstaller(): HelloReply = SimControl("v1", faults = NO_INSTALLER_FAULTS).device.hello!!

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

    private fun sending() {
        apps = AppsView(send = "app 42%")
        settle(300)
        rule.onNodeWithTag("apps.send").performClick()
        settle(1000)
        assertEquals("app", shell.sheet.title)
    }

    @Test
    fun aV1WithoutTheInstallerListsUpdateFirmwareAndSupportMe() {
        start(settingsPages, NO_INSTALLER_FAULTS)
        system()
        tapAbout()
        assertEquals(listOf("update firmware", "support me"), shell.sheet.items)
    }

    @Test
    fun anAp80WithoutTheInstallerListsUpdateFirmwareAndSupportMe() {
        start(settingsPages, NO_INSTALLER_FAULTS, board = "ap80")
        system()
        tapAbout()
        assertEquals(listOf("update firmware", "support me"), shell.sheet.items)
    }

    @Test
    fun aFullV1ListsTheThreeAndInstallAppOpensTheAppsPage() {
        start(settingsPages)
        system()
        tapAbout()
        assertEquals(listOf("update firmware", "install app", "support me"), shell.sheet.items)
        tapItem("install app")
        assertEquals(appsPages, shell.nav.stack.toList())
    }

    @Test
    fun aHelloWithoutTheInstallerPopsTheAppsPageAndClosesItsSheet() {
        start(appsPages)
        sending()
        relinked(noInstaller())
        assertEquals(settingsPages, shell.nav.stack.toList())
        assertFalse(shell.sheet.shown)
    }

    @Test
    fun theBackgroundAskStaysOpenWhenTheAppsPageGoes() {
        link = LinkView(Phase.LIVE, "V1", everLive = true, askBattery = true)
        start(appsPages)
        assertEquals("background", shell.sheet.title)
        val opened = shell.sheet.opened
        relinked(noInstaller())
        assertEquals(settingsPages, shell.nav.stack.toList())
        assertTrue(shell.sheet.shown)
        assertEquals("background", shell.sheet.title)
        assertEquals(opened, shell.sheet.opened)
        assertEquals(0, c.laters)
    }

    @Test
    fun aDropKeepsInstallAppHiddenOnADeviceWithoutTheInstaller() {
        start(settingsPages, NO_INSTALLER_FAULTS)
        c.device = c.device.unlinked()
        settle(600)
        system()
        tapAbout()
        assertEquals(listOf("update firmware", "support me"), shell.sheet.items)
    }

    @Test
    fun aFullRelinkKeepsTheAppsPageAndItsSheet() {
        start(appsPages)
        sending()
        relinked(c.device.hello!!)
        assertEquals(appsPages, shell.nav.stack.toList())
        assertTrue(shell.sheet.shown)
        assertEquals("app", shell.sheet.title)
    }

    @Test
    fun anOpenAboutSheetClosesAtAHelloWithoutTheInstaller() {
        start(settingsPages)
        system()
        tapAbout()
        assertEquals(listOf("update firmware", "install app", "support me"), shell.sheet.items)
        relinked(noInstaller())
        assertFalse(shell.sheet.shown)
    }

    @Test
    fun aBackDuringAHelloWithoutTheInstallerEndsAtSettings() {
        val h = noInstaller()
        start(appsPages)
        c.device = c.device.unlinked()
        settle(600)
        rule.runOnIdle { shell.nav.pop() }
        rule.mainClock.advanceTimeBy(16)
        hello(h)
        settle(1000)
        assertEquals(settingsPages, shell.nav.stack.toList())
    }

    @Test
    fun theAppsPagePushedWithoutTheInstallerGoesBack() {
        start(settingsPages, NO_INSTALLER_FAULTS)
        rule.runOnIdle { shell.nav.push(Page.Apps) }
        settle(1000)
        assertEquals(settingsPages, shell.nav.stack.toList())
    }

    @Test
    fun aLink16PeerKeepsInstallAppAndItsLine() {
        apps = AppsView(info = Apps.NO_INSTALLER)
        start(settingsPages, minor = 16)
        system()
        tapAbout()
        assertEquals(listOf("update firmware", "install app", "support me"), shell.sheet.items)
        tapItem("install app")
        assertEquals(appsPages, shell.nav.stack.toList())
        assertTrue(rule.onAllNodes(hasText(Apps.NO_INSTALLER, ignoreCase = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }
}
