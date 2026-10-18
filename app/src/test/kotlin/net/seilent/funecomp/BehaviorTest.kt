package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.state.LibKey
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.Battery
import net.seilent.funecomp.ui.Bonded
import net.seilent.funecomp.ui.FirmwareView
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.statusText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
class BehaviorTest {
    @get:Rule
    val rule = createComposeRule()

    private fun start(pages: List<Page>, link: LinkView = LinkView(Phase.LIVE), firmware: FirmwareView = FirmwareView()): Pair<SimControl, Shell> {
        val c = SimControl("ap80")
        val shell = Shell(Nav(pages))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, link, c, shell, firmware = firmware) }
        }
        settle(1000)
        return c to shell
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    @Test
    fun connectionGateOpensNowPlayingOnTheFirstLiveAndStaysOnADrop() {
        val c = SimControl("ap80")
        val shell = Shell(Nav(listOf(Page.Connection)), gated = true)
        var link by mutableStateOf(LinkView(Phase.CONNECTING, "AP80"))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, link, c, shell) }
        }
        settle(1000)
        assertEquals(listOf(Page.Connection), shell.nav.stack.toList())
        rule.onNodeWithTag("conn.status").assertIsDisplayed()
        link = LinkView(Phase.DOWN, "AP80")
        settle(1000)
        assertEquals(listOf(Page.Connection), shell.nav.stack.toList())
        link = LinkView(Phase.LIVE, "AP80", everLive = true)
        settle(1000)
        assertEquals(listOf(Page.Home, Page.NowPlaying), shell.nav.stack.toList())
        assertFalse(shell.gated)
        link = LinkView(Phase.DOWN, "AP80", everLive = true)
        settle(1000)
        assertEquals(listOf(Page.Home, Page.NowPlaying), shell.nav.stack.toList())
        shell.nav.pop()
        shell.nav.push(Page.Connection)
        link = LinkView(Phase.LIVE, "AP80", everLive = true)
        settle(1000)
        assertEquals(listOf(Page.Home, Page.Connection), shell.nav.stack.toList())
    }

    @Test
    fun statusAndRemedyTapsRetryAfterRefused() {
        val (c, _) = start(listOf(Page.Home, Page.Connection), LinkView(Phase.STOPPED, "AP80", bye = Enums.ByeReason.refused))
        rule.onNodeWithTag("conn.status").performClick()
        settle(300)
        assertEquals(1, c.retries)
        rule.onNodeWithTag("conn.remedy").performClick()
        settle(300)
        assertEquals(2, c.retries)
        assertEquals(0, c.asks)
    }

    @Test
    fun statusTapWithoutPermissionAsksForIt() {
        val (c, _) = start(listOf(Page.Home, Page.Connection), LinkView(Phase.STOPPED, "AP80", bye = Enums.ByeReason.refused, permission = false))
        rule.onNodeWithTag("conn.status").performClick()
        settle(300)
        assertEquals(1, c.asks)
        assertEquals(0, c.retries)
    }

    @Test
    fun pairHintOnlyWithoutACompanionBond() {
        val (c, _) = start(listOf(Page.Home, Page.Connection), LinkView(Phase.NONE))
        assertEquals(0, rule.onAllNodesWithTag("conn.pairhint").fetchSemanticsNodes().size)
        c.bonds = listOf(Bonded("WH-1000XM4", "00:00:00:00:00:02"))
        rule.onNodeWithTag("conn.device").performClick()
        settle(300)
        rule.onNodeWithTag("conn.pairhint").assertIsDisplayed()
        rule.onNodeWithTag("conn.btsettings").assertIsDisplayed()
    }

    @Test
    fun pairHintHiddenWhileLive() {
        val (c, shell) = start(listOf(Page.Home))
        c.bonds = listOf(Bonded("WH-1000XM4", "00:00:00:00:00:02"))
        shell.nav.push(Page.Connection)
        settle(1500)
        rule.onNodeWithTag("conn.status").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag("conn.pairhint").fetchSemanticsNodes().size)
    }

    @Test
    fun pairHintHiddenOnTheChosenBond() {
        val (c, shell) = start(listOf(Page.Home), LinkView(Phase.DOWN, "WH-1000XM4"))
        c.bonds = listOf(Bonded("WH-1000XM4", "00:00:00:00:00:02"))
        shell.nav.push(Page.Connection)
        settle(1500)
        rule.onNodeWithTag("conn.status").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag("conn.pairhint").fetchSemanticsNodes().size)
    }

    @Test
    fun refusedWithNoCompanionBondShowsThePathOnce() {
        val (c, shell) = start(listOf(Page.Home), LinkView(Phase.STOPPED, "AP80", bye = Enums.ByeReason.refused))
        c.bonds = listOf(Bonded("WH-1000XM4", "00:00:00:00:00:02"))
        shell.nav.push(Page.Connection)
        settle(1500)
        rule.onNodeWithTag("conn.remedy").assertIsDisplayed()
        rule.onNodeWithTag("conn.pairhint").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag("conn.pairpath").fetchSemanticsNodes().size)
    }

    @Test
    fun backgroundSheetOpensOnLiveAndBackCountsAsNotNow() {
        val c = SimControl("v1")
        val shell = Shell(Nav(listOf(Page.Connection)), gated = true)
        var link by mutableStateOf(LinkView(Phase.CONNECTING, "V1", battery = Battery.OPTIMIZED, askBattery = true))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, link, c, shell) }
        }
        settle(1000)
        assertFalse(shell.sheet.shown)
        link = link.copy(phase = Phase.LIVE, everLive = true)
        settle(1000)
        assertEquals(listOf(Page.Home, Page.NowPlaying), shell.nav.stack.toList())
        assertTrue(shell.sheet.shown)
        assertEquals("background", shell.sheet.title)
        shell.sheet.close()
        settle(500)
        assertEquals(1, c.laters)
        assertEquals(0, c.allows)
        link = link.copy(askBattery = false)
        settle(500)
        assertFalse(shell.sheet.shown)
    }

    @Test
    fun backgroundSheetAllow() {
        val (c, shell) = start(listOf(Page.Home), LinkView(Phase.LIVE, "V1", battery = Battery.OPTIMIZED, askBattery = true))
        assertTrue(shell.sheet.shown)
        assertEquals(listOf("works with the screen off"), shell.sheet.info)
        assertEquals(0, shell.sheet.focus)
        rule.onNodeWithTag("sheet.info").performClick()
        settle(300)
        assertTrue(shell.sheet.shown)
        assertEquals(0, c.allows + c.laters)
        rule.onNodeWithTag("sheet.item.0").performClick()
        settle(500)
        assertEquals(1, c.allows)
        assertEquals(0, c.laters)
        assertFalse(shell.sheet.shown)
    }

    @Test
    fun batteryAndDisconnectLines() {
        val (c, _) = start(listOf(Page.Home, Page.Connection), LinkView(Phase.LIVE, "V1", battery = Battery.RESTRICTED))
        rule.onNode(hasText("restricted", ignoreCase = true) and hasAnyAncestor(hasTestTag("conn.battery"))).assertExists()
        rule.onNodeWithTag("conn.battery").performClick()
        settle(300)
        assertEquals(1, c.allows)
        rule.onNodeWithTag("conn.disconnect").performClick()
        settle(300)
        assertEquals(1, c.disconnects)
    }

    @Test
    fun stoppedLinkHasNoDisconnectLine() {
        start(listOf(Page.Home, Page.Connection), LinkView(Phase.NONE, "V1", battery = Battery.UNRESTRICTED))
        rule.onNode(hasText("stopped", ignoreCase = true) and hasAnyAncestor(hasTestTag("conn.status"))).assertExists()
        rule.onNode(hasText("unrestricted", ignoreCase = true) and hasAnyAncestor(hasTestTag("conn.battery"))).assertExists()
        assertEquals(0, rule.onAllNodesWithTag("conn.disconnect").fetchSemanticsNodes().size)
    }

    @Test
    fun statusWords() {
        assertEquals("stopped", statusText(LinkView(Phase.NONE, "V1")))
        assertEquals("not set", statusText(LinkView(Phase.NONE)))
        assertEquals("connected", statusText(LinkView(Phase.LIVE, "V1")))
        assertEquals(listOf("unrestricted", "optimized", "restricted"), Battery.entries.map { it.word })
    }

    @Test
    fun backDuringPushRestoresThePage() {
        val (_, shell) = start(listOf(Page.Home))
        rule.onNodeWithTag("home.music").performClick()
        settle(100)
        shell.nav.pop()
        settle(1000)
        rule.onNodeWithTag("home").assertIsDisplayed()
        rule.onNodeWithTag("home.music").performClick()
        settle(1500)
        rule.onNodeWithTag("hub.music").assertIsDisplayed()
    }

    @Test
    fun tapBelowTheRowsConfirmsADraft() {
        val (c, _) = start(listOf(Page.Home, Page.Settings))
        rule.onNodeWithTag("set.btCodec").performClick()
        settle(300)
        rule.onNodeWithTag("set.btCodec").performTouchInput { click(centerRight) }
        settle(300)
        assertTrue(c.sent.none { it == "DRAFT" })
        rule.onRoot().performTouchInput { click(bottomCenter.copy(y = bottom - 10f)) }
        settle(300)
        assertEquals(1, c.sent.count { it == "DRAFT" })
    }

    @Test
    fun popKeepsThePageBelow() {
        val (_, shell) = start(listOf(Page.Home, Page.Music))
        rule.onNodeWithTag("pivot.albums").performClick()
        settle(1200)
        shell.nav.push(Page.Album("1", "x", ""))
        settle(1200)
        shell.nav.pop()
        settle(1200)
        assertTrue(rule.onAllNodesWithText("First Love").fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("Utada Hikaru").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun favoriteToggleDropsLibPagesAndReloadsTheOpenList() {
        val (c, _) = start(listOf(Page.Home, Page.Playlists))
        val fav = LibKey(Enums.View.favorites, "")
        assertEquals(0L, c.device.lib.pages.getValue(fav).total)
        c.libGet(Enums.View.songs, "", 0)
        val song = c.device.lib.pages.getValue(LibKey(Enums.View.songs, "")).rows.getValue(0).first!!
        val rev = c.device.lib.rev
        val gets = c.sent.count { it == "LIB_GET" }
        c.action(Enums.ActionCmd.favorite, song)
        assertNotEquals(rev, c.device.lib.rev)
        assertNull(c.device.lib.pages[LibKey(Enums.View.songs, "")])
        settle(500)
        assertTrue(c.sent.count { it == "LIB_GET" } > gets)
        assertEquals(1L, c.device.lib.pages.getValue(fav).total)
        assertEquals(song, c.device.lib.pages.getValue(fav).rows.getValue(0).first)
        rule.onNode(hasText("1 song", ignoreCase = true) and hasAnyAncestor(hasTestTag("row.favorites"))).assertExists()
        val on = c.device.lib.rev
        c.action(Enums.ActionCmd.favorite, song)
        assertNotEquals(on, c.device.lib.rev)
        settle(500)
        assertEquals(0L, c.device.lib.pages.getValue(fav).total)
        rule.onNode(hasText("0 songs", ignoreCase = true) and hasAnyAncestor(hasTestTag("row.favorites"))).assertExists()
        assertEquals(emptyList<Int>(), c.failed)
    }

    private val eqPage = listOf(Page.Home, Page.Settings, Page.Eq(Enums.EqOutput.wired))

    private fun tapSheetItem(i: Int) {
        rule.onNodeWithTag("sheet.item.$i").performClick()
        settle(500)
    }

    @Test
    fun autoEqSourceFetchesNothingUntilASearch() {
        val (c, shell) = start(eqPage)
        rule.onNodeWithTag("eq.import").performClick()
        settle(500)
        assertEquals(listOf("file", "search"), shell.sheet.items)
        tapSheetItem(1)
        assertEquals(emptyList<String>(), c.queries)
        rule.onNodeWithTag("entry").performImeAction()
        settle(500)
        assertEquals(emptyList<String>(), c.queries)
    }

    @Test
    fun autoEqFileShowsTheConfirmWithoutSending() {
        val (c, shell) = start(eqPage)
        val sent = c.sent.size
        rule.onNodeWithTag("eq.import").performClick()
        settle(500)
        tapSheetItem(0)
        assertEquals("Sennheiser HD 650", shell.sheet.title)
        assertEquals(listOf("preamp -6.1 db", "10 bands", "device has no eq presets"), shell.sheet.info)
        assertEquals(listOf("cancel"), shell.sheet.items)
        tapSheetItem(0)
        assertFalse(shell.sheet.shown)
        assertEquals(sent, c.sent.size)
    }

    @Test
    fun autoEqClampsAndRefusals() {
        val (c, shell) = start(eqPage)
        c.eqText = "Preamp: 1.5 dB\nFilter 1: ON PK Fc 15 Hz Gain 14 dB Q 12"
        c.eqFileName = "custom.txt"
        rule.onNodeWithTag("eq.import").performClick()
        settle(500)
        tapSheetItem(0)
        assertEquals(
            listOf("preamp 0.0 db", "1 band", "preamp +1.5 to 0.0", "band 1 fc 15 hz to 20 hz", "band 1 gain +14.0 to +12.0", "band 1 q 12.00 to 10.00", "invalid name", "device has no eq presets"),
            shell.sheet.info,
        )
        tapSheetItem(0)
        c.eqText = (1..11).joinToString("\n") { "Filter $it: ON PK Fc ${it * 100} Hz Gain 1 dB Q 1" }
        rule.onNodeWithTag("eq.import").performClick()
        settle(500)
        tapSheetItem(0)
        assertEquals(listOf("11 filters, max 10"), shell.sheet.info)
        tapSheetItem(0)
        c.eqText = null
        rule.onNodeWithTag("eq.import").performClick()
        settle(500)
        tapSheetItem(0)
        assertEquals(listOf("not readable"), shell.sheet.info)
    }

    @Test
    fun autoEqSearchPicksAResult() {
        val (c, shell) = start(eqPage)
        rule.onNodeWithTag("eq.import").performClick()
        settle(500)
        tapSheetItem(1)
        rule.onNodeWithTag("entry").performTextInput("hd 650")
        rule.onNodeWithTag("entry").performImeAction()
        settle(500)
        assertEquals(listOf("hd 650"), c.queries)
        assertEquals("hd 650", shell.sheet.title)
        assertEquals(4, shell.sheet.items.size)
        tapSheetItem(2)
        assertEquals("Sennheiser HD 650 (2020, worn earpads)", shell.sheet.title)
        tapSheetItem(0)
        c.found = emptyList()
        rule.onNodeWithTag("eq.import").performClick()
        settle(500)
        tapSheetItem(1)
        rule.onNodeWithTag("entry").performTextInput("nothing")
        rule.onNodeWithTag("entry").performImeAction()
        settle(500)
        assertEquals(listOf("no results"), shell.sheet.info)
    }

    @Test
    fun firmwareRowsDriveTheControl() {
        val pages = listOf(Page.Home, Page.Connection, Page.Firmware)
        val (c, shell) = start(pages, firmware = FirmwareView(tag = "v0.4.0", size = 28_043_264, newer = true))
        rule.onNodeWithTag("fw.check").performClick()
        rule.onNodeWithTag("fw.daily").performClick()
        rule.onNodeWithTag("fw.pre").performClick()
        assertEquals(listOf("check", "settings true true", "settings false false"), c.firmware)
        rule.onNodeWithTag("fw.release").performClick()
        settle(500)
        assertEquals(listOf("28.0 mb"), shell.sheet.info)
        tapSheetItem(0)
        assertEquals("download", c.firmware.last())
        rule.onNodeWithTag("fw.pick").performClick()
        settle(500)
        assertEquals("funeos-v0.4.0-v1.upt", shell.sheet.title)
        assertEquals(listOf("28.0 mb", "board v1"), shell.sheet.info)
        assertEquals(listOf("send", "cancel"), shell.sheet.items)
        tapSheetItem(0)
        assertEquals("send picked", c.firmware.last())
    }
}
