package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import kotlinx.coroutines.CompletableDeferred
import net.seilent.funecomp.artist.AutoEqList
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.state.EqSlots
import net.seilent.funecomp.state.LibKey
import net.seilent.funecomp.state.Section
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.Battery
import net.seilent.funecomp.ui.Bonded
import net.seilent.funecomp.ui.FirmwareView
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.Ui
import net.seilent.funecomp.ui.statusText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.ln
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class BehaviorTest {
    @get:Rule
    val rule = createComposeRule()

    private fun start(pages: List<Page>, link: LinkView = LinkView(Phase.LIVE), firmware: FirmwareView = FirmwareView(), board: String = "ap80", minor: Int = Proto.minor): Pair<SimControl, Shell> {
        val c = SimControl(board, minor = minor)
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
    fun deviceSheetListsCompanionBondsAndTheLinkDevice() {
        val (c, shell) = start(listOf(Page.Home), LinkView(Phase.DOWN, "JBL Flip 6"))
        c.bonds = listOf(Bonded("JBL Flip 6", "00:00:00:00:00:04"), Bonded("TempoTec V1", "00:00:00:00:00:01", true), Bonded("Fitbit", "00:00:00:00:00:05"))
        shell.nav.push(Page.Connection)
        settle(1500)
        rule.onNodeWithTag("conn.device").performClick()
        settle(300)
        assertEquals(listOf("TempoTec V1", "JBL Flip 6", "pair a device"), shell.sheet.items)
        assertEquals(1, shell.sheet.focus)
        assertEquals(0, c.btSettings)
    }

    @Test
    fun deviceTapWithNoCompanionBondOpensThePicker() {
        val (c, shell) = start(listOf(Page.Home), LinkView(Phase.NONE))
        c.bonds = listOf(Bonded("JBL Flip 6", "00:00:00:00:00:04"))
        shell.nav.push(Page.Connection)
        settle(1500)
        rule.onNodeWithTag("conn.device").performClick()
        settle(300)
        assertEquals(1, c.pairDevices)
        assertEquals(0, c.btSettings)
        assertFalse(shell.sheet.shown)
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
        link = link.copy(askBattery = false)
        settle(500)
        assertEquals(1, c.laters)
        assertEquals(0, c.allows)
        assertFalse(shell.sheet.shown)
    }

    @Test
    fun reconnectSheetFollowsTheBatterySheet() {
        val c = SimControl("v1")
        val shell = Shell(Nav(listOf(Page.Home)))
        var link by mutableStateOf(LinkView(Phase.LIVE, "V1", everLive = true, battery = Battery.OPTIMIZED, askBattery = true, askReconnect = true))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, link, c, shell) }
        }
        settle(1000)
        assertEquals("background", shell.sheet.title)
        rule.onNodeWithTag("sheet.item.1").performClick()
        link = link.copy(askBattery = false)
        settle(1000)
        assertTrue(shell.sheet.shown)
        assertEquals("reconnect after update", shell.sheet.title)
        assertEquals(listOf("on", "off"), shell.sheet.items)
        assertEquals(listOf("the link restarts by itself"), shell.sheet.info)
        rule.onNodeWithTag("sheet.item.0").performClick()
        link = link.copy(askReconnect = false, reconnect = true)
        settle(500)
        assertEquals(listOf(true), c.reconnects)
        assertFalse(shell.sheet.shown)
    }

    @Test
    fun reconnectSheetBackCountsAsOff() {
        val (c, shell) = start(listOf(Page.Home), LinkView(Phase.LIVE, "V1", everLive = true, askReconnect = true))
        assertEquals("reconnect after update", shell.sheet.title)
        shell.sheet.close()
        settle(500)
        assertEquals(listOf(false), c.reconnects)
    }

    @Test
    fun reconnectSheetClosedWithoutAnAnswerShowsAgain() {
        val (c, shell) = start(listOf(Page.Home), LinkView(Phase.LIVE, "V1", everLive = true, askReconnect = true))
        assertEquals("reconnect after update", shell.sheet.title)
        shell.sheet.close(dismissed = false)
        settle(500)
        assertTrue(shell.sheet.shown)
        assertEquals("reconnect after update", shell.sheet.title)
        assertEquals(emptyList<Boolean>(), c.reconnects)
    }

    @Test
    fun reconnectSheetWaitsForLive() {
        val (c, shell) = start(listOf(Page.Home), LinkView(Phase.DOWN, "V1", everLive = true, askReconnect = true))
        assertFalse(shell.sheet.shown)
        assertEquals(emptyList<Boolean>(), c.reconnects)
    }

    @Test
    fun reconnectAndMediaRowsOpenTheOffOnSheet() {
        val (c, shell) = start(listOf(Page.Home, Page.Connection), LinkView(Phase.LIVE, "V1", reconnect = true, mediaControls = false))
        rule.onNode(hasText("on", ignoreCase = true) and hasAnyAncestor(hasTestTag("conn.reconnect"))).assertExists()
        rule.onNodeWithTag("conn.reconnect").performClick()
        settle(500)
        assertEquals("reconnect on update", shell.sheet.title)
        assertEquals(listOf("off", "on"), shell.sheet.items)
        assertEquals(1, shell.sheet.focus)
        tapSheetItem(1)
        assertEquals(emptyList<Boolean>(), c.reconnects)
        rule.onNodeWithTag("conn.reconnect").performClick()
        settle(500)
        shell.sheet.close()
        settle(500)
        assertEquals(emptyList<Boolean>(), c.reconnects)
        rule.onNodeWithTag("conn.reconnect").performClick()
        settle(500)
        tapSheetItem(0)
        assertEquals(listOf(false), c.reconnects)
        rule.onNodeWithTag("conn.media").performClick()
        settle(500)
        assertEquals("phone media controls", shell.sheet.title)
        assertEquals(0, shell.sheet.focus)
        tapSheetItem(0)
        assertEquals(emptyList<Boolean>(), c.medias)
        rule.onNodeWithTag("conn.media").performClick()
        settle(500)
        tapSheetItem(1)
        assertEquals(listOf(true), c.medias)
    }

    @Test
    fun backgroundSheetAllow() {
        val c = SimControl("v1")
        val shell = Shell(Nav(listOf(Page.Home)))
        var link by mutableStateOf(LinkView(Phase.LIVE, "V1", battery = Battery.OPTIMIZED, askBattery = true))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, link, c, shell) }
        }
        settle(1000)
        assertTrue(shell.sheet.shown)
        assertEquals(listOf("works with the screen off"), shell.sheet.info)
        assertEquals(0, shell.sheet.focus)
        rule.onNodeWithTag("sheet.info").performClick()
        settle(300)
        assertTrue(shell.sheet.shown)
        assertEquals(0, c.allows + c.laters)
        rule.onNodeWithTag("sheet.item.0").performClick()
        link = link.copy(askBattery = false)
        settle(500)
        assertEquals(1, c.allows)
        assertEquals(0, c.laters)
        assertFalse(shell.sheet.shown)
    }

    @Test
    fun batteryAndDisconnectLines() {
        val (c, _) = start(listOf(Page.Home, Page.Connection), LinkView(Phase.LIVE, "V1", battery = Battery.RESTRICTED))
        rule.onNode(hasText("restricted", ignoreCase = true) and hasAnyAncestor(hasTestTag("conn.background"))).assertExists()
        rule.onNodeWithTag("conn.background").performClick()
        settle(300)
        assertEquals(1, c.allows)
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("rows").performScrollToNode(hasTestTag("conn.disconnect"))
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("conn.disconnect").performClick()
        settle(300)
        assertEquals(1, c.disconnects)
        for (t in listOf("board", "version", "dapbattery", "card", "firmware", "lyrics", "scrobbling")) assertEquals(0, rule.onAllNodesWithTag("conn.$t").fetchSemanticsNodes().size)
    }

    @Test
    fun stoppedLinkHasNoDisconnectLine() {
        start(listOf(Page.Home, Page.Connection), LinkView(Phase.NONE, "V1", battery = Battery.UNRESTRICTED))
        rule.onNode(hasText("stopped", ignoreCase = true) and hasAnyAncestor(hasTestTag("conn.status"))).assertExists()
        rule.onNode(hasText("unrestricted", ignoreCase = true) and hasAnyAncestor(hasTestTag("conn.background"))).assertExists()
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

    private fun row(tag: String, vararg texts: String) {
        for (t in texts) rule.onNode(hasText(t) and hasAnyAncestor(hasTestTag(tag))).assertExists()
    }

    private fun deviceRows(board: String, brightness: String) {
        val (c, shell) = start(listOf(Page.Home, Page.Settings), board = board)
        val rows = c.device.schema.rows
        rule.walkPivots(rows, "effects") { settle(1000) }
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("rows").performScrollToNode(hasTestTag("set.crossfeed"))
        rule.mainClock.autoAdvance = false
        settle(1000)
        row("set.crossfeed", "CROSSFEED", "OFF")
        rule.walkPivots(rows, "display", "effects") { settle(1000) }
        row("set.brightness", "BRIGHTNESS", brightness)
        if (board == "v1") row("set.size", "SIZE", "STANDARD") else row("set.scale", "SCALE", "1.5")
        rule.walkPivots(rows, "screens", "display") { settle(1000) }
        row("set.screenLock", "LOCKSCREEN", "OFF")
        row("set.saverLyricsScene", "LYRICS SCENE", "OFF")
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("rows").performScrollToNode(hasTestTag("set.artistPhoto"))
        rule.mainClock.autoAdvance = false
        settle(1000)
        row("set.artistPhoto", "ARTIST PHOTO", "ON")
        rule.walkPivots(rows, "connect", "screens") { settle(1000) }
        assertEquals(board == "v1", rule.onAllNodesWithTag("set.wifi").fetchSemanticsNodes().isNotEmpty())
        if (board == "v1") row("set.wifi", "WI-FI")
        rule.walkPivots(rows, "system", "connect") { settle(1000) }
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("rows").performScrollToNode(hasTestTag("set.about"))
        rule.mainClock.autoAdvance = false
        settle(1000)
        rule.onNodeWithTag("set.about").performClick()
        settle(600)
        assertEquals("about", shell.sheet.title)
    }

    @Test
    fun deviceRowsOnTheAp80() = deviceRows("ap80", "55%")

    @Test
    fun deviceRowsOnTheV1() = deviceRows("v1", "35%")

    @Test
    fun popKeepsThePageBelow() {
        val (_, shell) = start(listOf(Page.Home, Page.Music))
        rule.pivotTo("music", "artists") { settle(it) }
        shell.nav.push(Page.Album("1", "x", ""))
        settle(1200)
        shell.nav.pop()
        settle(1200)
        assertTrue(rule.onAllNodesWithText("Utada Hikaru").fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("First Love").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun favoriteToggleDropsListPagesAndReloadsTheOpenList() {
        val (c, _) = start(listOf(Page.Home, Page.Playlists))
        val fav = LibKey(Enums.View.favorites, "")
        assertEquals(0L, c.device.lib.pages.getValue(fav).total)
        c.libGet(Enums.View.songs, "", 0)
        val song = c.device.lib.pages.getValue(LibKey(Enums.View.songs, "")).rows.getValue(0).first!!
        val rev = c.device.lib.rev
        val gets = c.sent.count { it == "LIB_GET" }
        c.action(Enums.ActionCmd.favorite, song)
        assertNotEquals(rev, c.device.lib.rev)
        assertNotNull(c.device.lib.pages[LibKey(Enums.View.songs, "")])
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
    fun autoEqFileShowsTheConfirmWithoutSending() {
        val (c, shell) = start(eqPage, minor = 4)
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
        val (c, shell) = start(eqPage, minor = 4)
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

    private val aeqRow = SemanticsMatcher("aeq row") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("aeq.row.") == true }

    private val aeqDiv = SemanticsMatcher("aeq divider") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("aeq.div.") == true }

    private fun tags(m: SemanticsMatcher) = rule.onAllNodes(m).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] }

    private fun top(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top

    private fun gridOpen() = rule.onAllNodesWithTag("jump").fetchSemanticsNodes().any { it.layoutInfo.isPlaced }

    private val jumpNode = SemanticsMatcher("jump node") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("jump") == true }

    private fun jumpNodes() = rule.onAllNodes(jumpNode, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun stateLine(text: String) = rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag("aeq.state"))).assertExists()

    private fun type(text: String) {
        rule.onNodeWithTag("aeq.field").performClick()
        rule.onNodeWithTag("aeq.field").performTextClearance()
        rule.onNodeWithTag("aeq.field").performTextInput(text)
        settle(300)
    }

    private fun jumpTo(letter: Int) {
        rule.onAllNodes(aeqDiv)[0].performClick()
        settle(300)
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("jump").performScrollToNode(hasTestTag("jump.$letter"))
        rule.mainClock.autoAdvance = false
        settle(300)
        rule.onNodeWithTag("jump.$letter").performClick()
        settle(300)
    }

    private fun gridScroll() = rule.onNodeWithTag("jump").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]

    private fun width(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.width

    private fun openGrid(divider: String = "aeq.div.#") {
        rule.onNodeWithTag(divider).performClick()
        settle(300)
        assertTrue(gridOpen())
    }

    private fun itemTag(i: AutoEqList.Item) = when (i) {
        is AutoEqList.Divider -> "aeq.div.${Section.letter(i.letter)}"
        is AutoEqList.Hit -> "aeq.row.${i.row.entry.name}"
    }

    private fun openAutoEq(list: AutoEqList? = SimControl.FULL): Pair<SimControl, Shell> {
        val (sim, shell) = start(eqPage)
        sim.autoEq = list
        rule.onNodeWithTag("eq.import").performClick()
        settle(500)
        assertEquals(listOf("file", "search"), shell.sheet.items)
        assertEquals(0, sim.lists)
        tapSheetItem(1)
        settle(1500)
        return sim to shell
    }

    @Test
    fun autoEqSearchOpensTheListAndFetchesTheIndexOnce() {
        val (c, shell) = openAutoEq()
        assertEquals(Page.AutoEq(EqSlots.HEADPHONES), shell.nav.top)
        assertEquals(1, c.lists)
        rule.onNodeWithTag("aeq.field").assertIsNotFocused()
        rule.onNode(hasText("type to search"), useUnmergedTree = true).assertExists()
        val first = c.autoEq!!.items("")
        assertEquals("aeq.div.#", itemTag(first[0]))
        assertEquals(top("rows"), top("aeq.div.#"))
        val row = (first[1] as AutoEqList.Hit).row.entry
        rule.onNode(hasText(row.source) and hasAnyAncestor(hasTestTag("aeq.row.${row.name}"))).assertExists()
        settle(1000)
        assertEquals(1, c.lists)
    }

    @Test
    fun autoEqTypingNarrowsTheRowsLive() {
        openAutoEq()
        type("oratory hd 6")
        val want = listOf(
            "Sennheiser HD 560S", "Sennheiser HD 569", "Sennheiser HD 600", "Sennheiser HD 620S", "Sennheiser HD 630VB",
            "Sennheiser HD 650", "Sennheiser HD 660 S", "Sennheiser HD 660S (Dekoni Fenestrated Sheepskin Earpads)",
            "Sennheiser HD 660S2", "Sennheiser HD 6XX", "Superlux HD 651", "Superlux HD 660", "Superlux HD 660 Pro 150 Ohm",
            "Superlux HD 662F", "Superlux HD 662F (Brainwavz round XL pleather earpads)", "Superlux HD 668B",
            "Superlux HD 668B (velour earpads)", "Superlux HD 681", "Superlux HD 681 (velour earpads)",
            "Superlux Hd 681B (Brainwavz round hybrid earpads)", "Superlux HD 681B (pleather earpads)", "Superlux HD 681B (velour earpads)",
        ).map { "aeq.row.$it" }
        val shown = tags(aeqRow)
        assertTrue(shown.isNotEmpty())
        assertEquals(want.take(shown.size), shown)
        assertEquals(listOf("aeq.div.s"), tags(aeqDiv))
    }

    @Test
    fun autoEqQueryChangeShowsTheFirstMatchAtTheTop() {
        val (c, _) = openAutoEq()
        jumpTo(19)
        assertEquals(top("rows"), top("aeq.div.s"))
        type("hd")
        val first = itemTag(c.autoEq!!.items("hd")[0])
        assertNotEquals("aeq.div.s", first)
        assertEquals(top("rows"), top(first))
    }

    @Test
    fun autoEqSlowQuerySupersededByANewerOneNeverShows() {
        val (c, _) = openAutoEq()
        val gate = CompletableDeferred<Unit>()
        c.slow["sennheiser"] = gate
        type("sennheiser")
        rule.onNodeWithTag("aeq.div.#").assertIsDisplayed()
        type("rode")
        assertEquals(listOf("aeq.div.r"), tags(aeqDiv))
        gate.complete(Unit)
        settle(500)
        assertEquals(listOf("aeq.div.r"), tags(aeqDiv))
        assertEquals(listOf("RØDE NTH-100", "RØDE NTH-100 (front dampening removed)", "RØDE NTH-100M").map { "aeq.row.$it" }, tags(aeqRow))
    }

    @Test
    fun autoEqTypingWhileLoadingAppliesOnArrival() {
        val (c, shell) = start(eqPage)
        c.holdList = true
        shell.nav.push(Page.AutoEq(EqSlots.HEADPHONES))
        settle(1500)
        stateLine("loading")
        type("rode")
        stateLine("loading")
        c.heldList!!()
        settle(300)
        assertEquals(listOf("aeq.div.r"), tags(aeqDiv))
        assertEquals(listOf("RØDE NTH-100", "RØDE NTH-100 (front dampening removed)", "RØDE NTH-100M").map { "aeq.row.$it" }, tags(aeqRow))
    }

    @Test
    fun autoEqSearchFailedRetriesOncePerTap() {
        val (c, shell) = start(eqPage)
        c.autoEq = null
        shell.nav.push(Page.AutoEq(EqSlots.HEADPHONES))
        settle(1500)
        stateLine("search failed")
        assertEquals(1, c.lists)
        rule.onNodeWithTag("aeq.state").performClick()
        settle(300)
        assertEquals(2, c.lists)
        stateLine("search failed")
        c.autoEq = SimControl.FULL
        c.holdList = true
        rule.onNodeWithTag("aeq.state").performClick()
        settle(300)
        stateLine("loading")
        rule.onNodeWithTag("aeq.state").performClick()
        settle(300)
        assertEquals(3, c.lists)
        c.heldList!!()
        settle(300)
        rule.onNodeWithTag("aeq.div.#").assertIsDisplayed()
    }

    @Test
    fun autoEqNoResults() {
        openAutoEq()
        type("zzqx")
        stateLine("no results")
        assertEquals(emptyList<String>(), tags(aeqRow))
    }

    @Test
    fun autoEqDividerOpensTheGridOnItsLetter() {
        openAutoEq()
        type("rode")
        rule.onNodeWithTag("aeq.div.r").performClick()
        settle(300)
        assertTrue(gridOpen())
        rule.onNodeWithTag("jump.18").assertIsSelected()
        rule.onNodeWithTag("jump.1").assertIsNotSelected()
        rule.onNodeWithTag("jump.1").performClick()
        settle(300)
        assertTrue(gridOpen())
        rule.onNodeWithTag("jump.18").performClick()
        settle(300)
        assertFalse(gridOpen())
        assertEquals(top("rows"), top("aeq.div.r"))
    }

    @Test
    fun autoEqGridEmptyLetterExitAndBack() {
        val (_, shell) = openAutoEq()
        rule.onNodeWithTag("aeq.div.#").performClick()
        settle(300)
        rule.onNodeWithTag("jump.0").assertIsSelected()
        rule.onNodeWithTag("jump.27").performClick()
        settle(300)
        assertTrue(gridOpen())
        assertEquals(top("rows"), top("aeq.div.#"))
        rule.onNodeWithTag("jump.exit").performClick()
        settle(300)
        assertFalse(gridOpen())
        rule.onNodeWithTag("aeq.div.#").performClick()
        settle(300)
        Espresso.pressBack()
        settle(300)
        assertFalse(gridOpen())
        assertEquals(Page.AutoEq(EqSlots.HEADPHONES), shell.nav.top)
        Espresso.pressBack()
        settle(1500)
        assertEquals(Page.Eq(Enums.EqOutput.wired), shell.nav.top)
    }

    @Test
    fun autoEqClosedGridHasNoSemanticsNodes() {
        openAutoEq()
        assertEquals(0, jumpNodes())
        assertEquals(0, rule.onAllNodes(hasText("漢")).fetchSemanticsNodes().size)
        rule.onNodeWithTag("aeq.div.#").performClick()
        settle(300)
        assertEquals(1 + Tok.jumpCells.toInt() + 1 + 1, jumpNodes())
        assertEquals(1, rule.onAllNodes(hasText("漢")).fetchSemanticsNodes().size)
        rule.onNodeWithTag("jump.exit").performClick()
        settle(300)
        assertEquals(0, jumpNodes())
        assertEquals(0, rule.onAllNodes(hasText("漢")).fetchSemanticsNodes().size)
    }

    @Test
    fun theOpenGridShowsTheScrollThumbOnItsBand() {
        openAutoEq()
        openGrid()
        val img = rule.onRoot().pixels()
        val ui = Ui.of(img.width.toFloat(), img.height.toFloat())
        val r = thumbRun(img, img.width - ui.px(Tok.scrollbar_width).roundToInt() / 2, ui.px(3f).roundToInt())
        assertNotNull(r)
        assertEquals(0f, gridScroll().value(), 0f)
        assertEquals(ui.px(Tok.edge).roundToInt(), r!!.first)
    }

    @Test
    fun theJumpGridKeepsItsDecay() {
        openAutoEq()
        openGrid()
        val size = rule.onRoot().fetchSemanticsNode().size
        val ui = Ui.of(size.width.toFloat(), size.height.toFloat())
        val want = (80f - Tok.flingStop) / (1000f / Tok.motion_flingTick * -ln(Tok.motion_flingRetention))
        val step = ui.px(80f) * 4 / 1000f
        rule.onNodeWithTag("jump").performTouchInput {
            down(center)
            repeat(((ui.px(Tok.touchSlop) + ui.px(2f)) / step).toInt() + 1) { moveBy(Offset(0f, -step), 4L) }
        }
        val at = gridScroll().value()
        assertTrue("$at", at > 0f && (gridScroll().maxValue() - at) / ui.u > want * 1.1f)
        rule.onNodeWithTag("jump").performTouchInput { up() }
        settle(4000)
        assertEquals(want, (gridScroll().value() - at) / ui.u, want * 0.05f)
    }

    @Test
    fun aGridOpenedDuringTheRiseReturnsTheToastWithNoRise() {
        val (_, shell) = openAutoEq()
        shell.toast.show("artist", "title")
        settle(50)
        rule.onNodeWithTag("aeq.div.#").performClick()
        var frames = 0
        while (!gridOpen()) {
            check(frames++ < 5) { "no grid after the divider" }
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
        }
        frames = 0
        while (rule.onAllNodesWithTag("toast").fetchSemanticsNodes().isNotEmpty()) {
            check(frames++ < 3) { "the toast stays over the grid" }
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
        }
        rule.onNodeWithTag("jump.exit").performSemanticsAction(SemanticsActions.OnClick)
        frames = 0
        while (rule.onAllNodesWithTag("toast").fetchSemanticsNodes().isEmpty()) {
            check(frames++ < 5) { "no toast after the close" }
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
        }
        val first = rule.onNodeWithTag("toast").fetchSemanticsNode().boundsInRoot.top
        settle(500)
        assertEquals(rule.onNodeWithTag("toast").fetchSemanticsNode().boundsInRoot.top, first, 0.5f)
    }

    @Test
    fun noToastDrawsOverTheJumpGridAndItReturnsWithNoRise() {
        val (_, shell) = openAutoEq()
        openGrid()
        shell.toast.show("artist", "title")
        settle(100)
        assertTrue(rule.onAllNodesWithTag("toast").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("jump.exit").performSemanticsAction(SemanticsActions.OnClick)
        var frames = 0
        while (rule.onAllNodesWithTag("toast").fetchSemanticsNodes().isEmpty()) {
            check(frames++ < 5) { "no toast after the close" }
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
        }
        val first = rule.onNodeWithTag("toast").fetchSemanticsNode().boundsInRoot.top
        settle(500)
        assertEquals(rule.onNodeWithTag("toast").fetchSemanticsNode().boundsInRoot.top, first, 0.5f)
    }

    @Test
    fun anEmptyJumpLetterIsDimWithNoBorder() {
        openAutoEq(SimControl.NO_Q)
        openGrid()
        val empty = tags(jumpNode).first { tag ->
            val i = tag.removePrefix("jump.").toIntOrNull()
            i != null && Section.letter(i).all { it in 'A'..'Z' || it in 'a'..'z' } &&
                rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick) == null
        }
        assertTrue(empty, Section.letter(empty.removePrefix("jump.").toInt()).equals("q", ignoreCase = true))
        val img = rule.onNodeWithTag(empty).pixels().also { System.gc() }
        fun luma(x: Int, y: Int) = img.getRGB(x, y).let { maxOf(it shr 16 and 0xff, it shr 8 and 0xff, it and 0xff) }
        val ground = luma(img.width / 4, img.height / 4)
        val ring = (0 until img.width).flatMap { x -> listOf(luma(x, 0), luma(x, img.height - 1)) } +
            (0 until img.height).flatMap { y -> listOf(luma(0, y), luma(img.width - 1, y)) }
        assertTrue("$empty ground $ground ring ${ring.min()} to ${ring.max()}", ring.max() - ground <= 2)
        val glyph = (0 until img.height).maxOf { y -> (0 until img.width).maxOf { x -> luma(x, y) } }
        assertEquals(empty, Tok.jump_emptyAlpha * 255, (glyph - ground).toFloat(), 6f)
    }

    @Test
    fun autoEqGridSwipeScrollsAndStopsAtTheLimit() {
        openAutoEq()
        openGrid()
        assertEquals(0f, gridScroll().value(), 0f)
        val max = gridScroll().maxValue()
        assertTrue("$max", max > 0f)
        rule.mainClock.autoAdvance = true
        repeat(3) { rule.onNodeWithTag("jump").performTouchInput { swipeUp() } }
        rule.mainClock.autoAdvance = false
        settle(1000)
        assertEquals(max, gridScroll().value(), 0.5f)
        assertTrue(gridOpen())
    }

    @Test
    fun autoEqGridScrollBySemanticsMovesTowardTheLimit() {
        openAutoEq()
        openGrid()
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("jump").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 50f) }
        rule.mainClock.autoAdvance = false
        settle(300)
        assertEquals(50f, gridScroll().value(), 0.5f)
    }

    @Test
    fun autoEqGridTapDuringAScrollOnlyStopsIt() {
        val c = SimControl("ap80")
        val shell = Shell(Nav(eqPage + Page.AutoEq(EqSlots.HEADPHONES)))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(360.dp, 640.dp)) { App(c.device, LinkView(Phase.LIVE), c, shell, insets = WindowInsets(0.dp)) }
        }
        settle(1000)
        openGrid()
        val list = top("aeq.div.#")
        val max = gridScroll().maxValue()
        rule.onNodeWithTag("jump").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, max) }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        val moving = gridScroll().value()
        assertTrue("$moving of $max", moving > 0f && moving < max)
        rule.onNodeWithTag("jump.19").performClick()
        val caught = gridScroll().value()
        settle(1000)
        assertTrue(gridOpen())
        assertEquals(caught, gridScroll().value(), 0.5f)
        assertEquals(list, top("aeq.div.#"))
    }

    @Test
    fun autoEqGridHorizontalDragClearsThePressAndKeepsTheOffset() {
        openAutoEq()
        openGrid()
        val tile = width("jump.19")
        val slop = Tok.touchSlop * Ui.of(411f * rule.density.density, 960f * rule.density.density).u
        rule.onNodeWithTag("jump.19").performTouchInput { down(center) }
        settle(150)
        assertEquals(tile * Tok.motion_pressScale, width("jump.19"), 1f)
        rule.onNodeWithTag("jump.19").performTouchInput { moveBy(Offset(slop * 3, 0f)) }
        settle(150)
        assertEquals(tile, width("jump.19"), 0.5f)
        assertEquals(0f, gridScroll().value(), 0f)
        rule.onNodeWithTag("jump.19").performTouchInput { up() }
        settle(300)
        assertTrue(gridOpen())
    }

    @Test
    fun autoEqGridLetterReturningFromADragKeepsItsValueAndAThirdLetterSnapsIt() {
        openAutoEq()
        openGrid()
        val tile = width("jump.19")
        val slop = Tok.touchSlop * Ui.of(411f * rule.density.density, 960f * rule.density.density).u
        rule.onNodeWithTag("jump.19").performTouchInput { down(center) }
        settle(100)
        rule.onNodeWithTag("jump.19").performTouchInput { moveBy(Offset(slop * 3, 0f)) }
        rule.onNodeWithTag("jump.19").performTouchInput { up() }
        settle(20)
        rule.onNodeWithTag("jump.20").performTouchInput { down(center) }
        rule.mainClock.advanceTimeByFrame()
        val back = width("jump.19") / tile
        assertTrue("a returning letter keeps its value: $back", back > Tok.motion_pressScale + 0.005f && back < 0.995f)
        rule.onNodeWithTag("jump.20").performTouchInput { moveBy(Offset(slop * 3, 0f)) }
        rule.onNodeWithTag("jump.20").performTouchInput { up() }
        rule.onNodeWithTag("jump.21").performTouchInput { down(center) }
        rule.mainClock.advanceTimeByFrame()
        assertEquals("a third letter snaps the returning one", tile, width("jump.19"), 0.5f)
        assertTrue("the third letter starts from rest", width("jump.21") / tile > 0.99f)
        rule.onNodeWithTag("jump.21").performTouchInput { up() }
        settle(500)
    }

    @Test
    fun autoEqGridVerticalDragFromATileJumpsNowhere() {
        openAutoEq()
        openGrid()
        val list = top("aeq.div.#")
        rule.onNodeWithTag("jump.19").performTouchInput { swipe(center, center - Offset(0f, height * 1.5f), 1000) }
        settle(1000)
        assertTrue(gridOpen())
        assertTrue(gridScroll().value() > 0f)
        assertEquals(list, top("aeq.div.#"))
    }

    @Test
    fun autoEqGridCancelledTouchDropsThePressAndActsNot() {
        openAutoEq()
        openGrid()
        val tile = width("jump.19")
        rule.onNodeWithTag("jump.19").performTouchInput { down(center) }
        settle(150)
        assertEquals(tile * Tok.motion_pressScale, width("jump.19"), 1f)
        rule.onNodeWithTag("jump.19").performTouchInput { cancel() }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        assertTrue(gridOpen())
        assertEquals(tile, width("jump.19"), 0.5f)
        settle(500)
        assertTrue(gridOpen())
    }

    @OptIn(InternalComposeTracingApi::class)
    @Test
    fun autoEqGridOpenAndCloseRecomposeOnlyTheFocusTiles() {
        openAutoEq()
        openGrid()
        rule.onNodeWithTag("jump.exit").performClick()
        settle(300)
        var tiles = 0
        Composer.setTracer(object : CompositionTracer {
            override fun isTraceInProgress() = true

            override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
                if (info.startsWith("net.seilent.funecomp.ui.JumpLetter ")) tiles++
            }

            override fun traceEventEnd() {}
        })
        val steps = mutableListOf<Int>()
        try {
            openGrid()
            steps += tiles
            tiles = 0
            rule.onNodeWithTag("jump.exit").performClick()
            settle(300)
            steps += tiles
            tiles = 0
            jumpTo(19)
            steps += tiles
            tiles = 0
            openGrid("aeq.div.s")
            steps += tiles
        } finally {
            Composer.setTracer(null)
        }
        assertEquals(listOf(1, 1, 2, 1), steps)
    }

    @Test
    fun autoEqGridJumpHoldsThePressThroughTheCloseAndWritesNoStateAfterIt() {
        openAutoEq()
        openGrid()
        val tile = width("jump.19")
        rule.onNodeWithTag("jump.19").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(Tok.motion_press.toLong() + 32)
        assertEquals(tile * Tok.motion_pressScale, width("jump.19"), 1f)
        rule.onNodeWithTag("jump.19").performTouchInput { up() }
        settle(100)
        assertFalse(gridOpen())
        var writes = 0
        val observer = Snapshot.registerApplyObserver { _, _ -> writes++ }
        try {
            settle(Tok.motion_touchReturnDelay.toLong() + Tok.motion_press.toLong() + 200)
        } finally {
            observer.dispose()
        }
        assertEquals(0, writes)
        rule.onNodeWithTag("aeq.div.s").performClick()
        rule.mainClock.advanceTimeByFrame()
        assertTrue(gridOpen())
        assertEquals(tile, width("jump.19"), 0.5f)
    }

    @Test
    fun autoEqGridOpensOnTheLastLetterInTheBandFromTheFirstFrame() {
        val c = SimControl("ap80")
        c.autoEq = SimControl.KANJI
        val shell = Shell(Nav(eqPage + Page.AutoEq(EqSlots.HEADPHONES)))
        var size by mutableStateOf(411.dp to 960.dp)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(size.first, size.second)) { App(c.device, LinkView(Phase.LIVE), c, shell, insets = WindowInsets(0.dp)) }
        }
        settle(1000)
        size = 360.dp to 640.dp
        settle(1000)
        rule.onNodeWithTag("rows").performScrollToIndex(SimControl.KANJI.items("").indexOfFirst { it is AutoEqList.Divider && it.letter == 37 })
        settle(300)
        rule.onNodeWithTag("aeq.div.漢").performClick()
        rule.mainClock.advanceTimeByFrame()
        assertTrue(gridOpen())
        val h = with(rule.density) { 640.dp.toPx() }
        val u = Ui.of(with(rule.density) { 360.dp.toPx() }, h).u
        val grid = rule.onNodeWithTag("jump").fetchSemanticsNode().boundsInRoot.top
        val tile = rule.onNodeWithTag("jump.37").fetchSemanticsNode().boundsInRoot
        assertTrue("${gridScroll().value()}", gridScroll().value() > 0f)
        assertTrue("${tile.top - grid}", tile.top - grid >= Tok.edge * u - 1f)
        assertTrue("${tile.bottom - grid}", tile.bottom - grid <= h - Tok.edge * u + 1f)
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("jump").performScrollToNode(hasTestTag("jump.37"))
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("jump.37").performClick()
        settle(300)
        assertFalse(gridOpen())
        rule.onNodeWithTag("aeq.div.漢").assertIsDisplayed()
    }

    @Test
    fun autoEqDividerTapClearsTheFieldFocus() {
        openAutoEq()
        rule.onNodeWithTag("aeq.field").performClick()
        settle(300)
        rule.onNodeWithTag("aeq.field").assertIsFocused()
        openGrid()
        rule.onNodeWithTag("aeq.field").assertIsNotFocused()
    }

    @Test
    fun autoEqSearchTileClosesTheGridAndFocusesTheField() {
        openAutoEq()
        openGrid()
        rule.onNodeWithTag("jump.search").performClick()
        settle(300)
        assertFalse(gridOpen())
        rule.onNodeWithTag("aeq.field").assertIsFocused()
    }

    @Test
    fun autoEqGridSemanticsClickActs() {
        openAutoEq()
        openGrid()
        rule.onNodeWithTag("jump.exit").performSemanticsAction(SemanticsActions.OnClick)
        settle(300)
        assertFalse(gridOpen())
        openGrid()
        rule.onNodeWithTag("jump.26").performSemanticsAction(SemanticsActions.OnClick)
        settle(300)
        assertFalse(gridOpen())
        assertEquals(top("rows"), top("aeq.div.z"))
    }

    @Test
    fun autoEqRestoredOpenGridKeepsItsLetters() {
        val c = SimControl("ap80")
        val shell = Shell(Nav(eqPage + Page.AutoEq(EqSlots.HEADPHONES)))
        val restore = StateRestorationTester(rule)
        rule.mainClock.autoAdvance = false
        restore.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE), c, shell) }
        }
        settle(1000)
        rule.onNodeWithTag("aeq.div.#").performClick()
        settle(300)
        assertTrue(gridOpen())
        rule.mainClock.autoAdvance = true
        restore.emulateSavedInstanceStateRestore()
        rule.mainClock.autoAdvance = false
        settle(1000)
        assertTrue(gridOpen())
        rule.onNodeWithTag("jump.19").performClick()
        settle(300)
        assertFalse(gridOpen())
        assertEquals(top("rows"), top("aeq.div.s"))
    }

    @Test
    fun autoEqEqualResultKeepsTheScroll() {
        openAutoEq()
        jumpTo(19)
        assertEquals(top("rows"), top("aeq.div.s"))
        rule.onNodeWithTag("aeq.field").performClick()
        rule.onNodeWithTag("aeq.field").performTextInput(" ")
        settle(300)
        rule.onNodeWithTag("aeq.field").assert(hasText(" "))
        assertEquals(top("rows"), top("aeq.div.s"))
    }

    @Test
    fun autoEqRowTapShowsTheConfirm() {
        val (c, shell) = openAutoEq()
        type("oratory1990 hd 650")
        assertEquals(listOf("aeq.row.Sennheiser HD 650"), tags(aeqRow))
        rule.onNodeWithTag("aeq.row.Sennheiser HD 650").performClick()
        settle(500)
        assertEquals(listOf("Sennheiser HD 650"), c.profiles)
        assertEquals("Sennheiser HD 650", shell.sheet.title)
        assertEquals(listOf("preamp -6.1 db", "10 bands"), shell.sheet.info)
        assertEquals(listOf("apply", "cancel"), shell.sheet.items)
    }

    @Test
    fun autoEqLastRowScrollsAboveTheKeyboard() {
        val c = SimControl("ap80")
        val shell = Shell(Nav(eqPage + Page.AutoEq(EqSlots.HEADPHONES)))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE), c, shell, insets = WindowInsets(0.dp), ime = WindowInsets(bottom = 300.dp)) }
        }
        settle(1000)
        jumpTo(26)
        val name = SimControl.FULL.rows.last().entry.name
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("rows").performScrollToNode(hasTestTag("aeq.row.$name"))
        rule.onNodeWithTag("rows").performTouchInput { swipeUp() }
        rule.mainClock.autoAdvance = false
        settle(1000)
        val last = rule.onNodeWithTag("aeq.row.$name").fetchSemanticsNode().boundsInRoot.bottom
        val keyboard = with(rule.density) { (960.dp - 300.dp).toPx() }
        assertTrue("$last above $keyboard", last <= keyboard + 1f)
    }

    @Test
    fun autoEqResizeKeepsTheQueryAndTheScroll() {
        val c = SimControl("ap80")
        val shell = Shell(Nav(eqPage + Page.AutoEq(EqSlots.HEADPHONES)))
        var size by mutableStateOf(411.dp to 960.dp)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(size.first, size.second)) { App(c.device, LinkView(Phase.LIVE), c, shell) }
        }
        settle(1000)
        type("hd")
        jumpTo(19)
        assertEquals(top("rows"), top("aeq.div.s"))
        size = 361.dp to 399.dp
        settle(1000)
        rule.onNodeWithTag("aeq.field").assert(hasText("hd"))
        assertEquals(top("rows"), top("aeq.div.s"))
        assertEquals(1, c.lists)
    }

    @Test
    @Config(qualifiers = "w474dp-h420dp-420dpi")
    fun prereleasesOnTheCoverScreenScrollsAboveThePanel() {
        val c = SimControl("v1")
        val shell = Shell(Nav(listOf(Page.Home, Page.Settings, Page.Firmware)))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(COVER_W.dp, COVER_H.dp)) { App(c.device, LinkView(Phase.LIVE), c, shell, firmware = FirmwareView(tag = "v0.4.0", size = 28_043_264, newer = true)) }
        }
        settle(1000)
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("rows").performScrollToNode(hasTestTag("fw.pre"))
        rule.mainClock.autoAdvance = false
        settle(300)
        rule.onNodeWithTag("fw.pre").performClick()
        settle(600)
        assertTrue(shell.sheet.shown)
        val d = rule.density.density
        val gutter = Ui.of((COVER_W * d).roundToInt().toFloat(), (COVER_H * d).roundToInt().toFloat()).px(Tok.gutter)
        val row = rule.onNodeWithTag("fw.pre").fetchSemanticsNode().boundsInRoot
        val panel = rule.onNodeWithTag("sheet.panel").fetchSemanticsNode().boundsInRoot
        assertTrue("${row.bottom} ${panel.top} $gutter", row.bottom <= panel.top - gutter)
    }

    @Test
    fun firmwareRowsDriveTheControl() {
        val pages = listOf(Page.Home, Page.Settings, Page.Firmware)
        val (c, shell) = start(pages, firmware = FirmwareView(tag = "v0.4.0", size = 28_043_264, newer = true))
        rule.onNodeWithTag("fw.check").performClick()
        rule.onNodeWithTag("fw.daily").performClick()
        settle(500)
        assertEquals("auto check", shell.sheet.title)
        assertEquals(listOf("off", "on"), shell.sheet.items)
        assertEquals(0, shell.sheet.focus)
        tapSheetItem(0)
        rule.onNodeWithTag("fw.daily").performClick()
        settle(500)
        tapSheetItem(1)
        rule.onNodeWithTag("fw.pre").performClick()
        settle(500)
        assertEquals(1, shell.sheet.focus)
        tapSheetItem(0)
        assertEquals(listOf("opened", "check", "settings true true", "settings false false"), c.firmware)
        rule.onNodeWithTag("fw.release").performClick()
        settle(500)
        assertEquals(listOf("28.0 mb"), shell.sheet.info)
        tapSheetItem(0)
        assertEquals("download", c.firmware.last())
        rule.onNodeWithTag("fw.pick").performClick()
        settle(500)
        assertEquals("pick file", shell.sheet.title)
        tapSheetItem(0)
        assertEquals("funeos-v0.4.0-v1.upt", shell.sheet.title)
        assertEquals(listOf("28.0 mb", "board v1"), shell.sheet.info)
        assertEquals(listOf("send", "cancel"), shell.sheet.items)
        tapSheetItem(0)
        assertEquals("send picked", c.firmware.last())
    }
}
