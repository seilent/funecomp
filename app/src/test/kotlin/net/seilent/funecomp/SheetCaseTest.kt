package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.artist.Accounts
import net.seilent.funecomp.artist.ArtCache
import net.seilent.funecomp.artist.Sources
import net.seilent.funecomp.proto.SvcInfo
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.state.AutoEq
import net.seilent.funecomp.state.AutoEqResult
import net.seilent.funecomp.state.EqSlots
import net.seilent.funecomp.state.SvcLogins
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.BackdropArt
import net.seilent.funecomp.ui.Bonded
import net.seilent.funecomp.ui.BrowseSessions
import net.seilent.funecomp.ui.Ctx
import net.seilent.funecomp.ui.KeyDraft
import net.seilent.funecomp.ui.KeysFlow
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.ONLINE_SCROBBLING
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.PickFor
import net.seilent.funecomp.ui.ScrobbleView
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.SvcAsk
import net.seilent.funecomp.ui.artSheet
import net.seilent.funecomp.ui.artistChooser
import net.seilent.funecomp.ui.askSheet
import net.seilent.funecomp.ui.autoEqConfirm
import net.seilent.funecomp.ui.loginSheet
import net.seilent.funecomp.ui.pickSheet
import net.seilent.funecomp.ui.keysSheet
import net.seilent.funecomp.ui.serviceSheet
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
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class SheetCaseTest {
    @get:Rule
    val rule = createComposeRule()

    private val wired = Enums.EqOutput.wired

    private fun start(pages: List<Page> = listOf(Page.Home), board: String = "ap80", minor: Int = Proto.minor, services: List<SvcInfo> = emptyList(), link: LinkView = LinkView(Phase.LIVE, "AP80", everLive = true), scrobble: ScrobbleView = ScrobbleView(), prep: (SimControl) -> Unit = {}): Pair<SimControl, Shell> {
        val c = SimControl(board, minor = minor, services = services)
        prep(c)
        val shell = Shell(Nav(pages))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, link, c, shell, scrobble = scrobble) }
        }
        settle(1000)
        return c to shell
    }

    private fun settle(ms: Long = 600) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun ctx(c: SimControl, shell: Shell) = Ctx(c.device, c, shell.nav, shell.sheet, shell.toast, BrowseSessions())

    private fun texts(tag: String): List<String> =
        rule.onAllNodes(hasAnyAncestor(hasTestTag(tag)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes().flatMap { n -> n.config[SemanticsProperties.Text].map { it.text } }

    private fun item(i: Int) = texts("sheet.item.$i").first()

    private fun line(i: Int) = texts(if (i == 0) "sheet.info" else "sheet.info.$i").first()

    private fun title() = rule.onNodeWithTag("sheet.title", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    private fun tap(tag: String) {
        rule.onNodeWithTag(tag).performClick()
        settle()
    }

    private fun saved(c: SimControl, name: String) =
        c.eqPreset(Enums.EqPresetOp.save, EqSlots.HEADPHONES, null, name, AutoEq.profile((AutoEq.parse(SimControl.HD650, name) as AutoEqResult.Ok).eq), false) { _, _ -> }

    @Test
    fun theProfileSheetKeepsDevicePresetNamesInTheirCase() {
        start(listOf(Page.Home, Page.Settings, Page.Eq(wired)), prep = { saved(it, "Harman 2019"); saved(it, "HD 650 oratory1990") })
        tap("eq.profile")
        assertEquals(listOf("OFF", "CUSTOM", "Harman 2019", "HD 650 oratory1990"), (0..3).map { item(it) })
        assertEquals("profile", title())
    }

    @Test
    fun theGraphicPresetSheetKeepsDevicePresetNamesInTheirCase() {
        start(listOf(Page.Home, Page.Settings, Page.Eq(wired)), prep = { it.eqSet(EqSlots.HEADPHONES, mode = Enums.EqMode.graphic, graphicKey = "flat") })
        tap("eq.preset")
        assertEquals(listOf("OFF", "FLAT", "Rock", "CUSTOM"), (0..3).map { item(it) })
    }

    @Test
    fun autoEqTitlesAndTheReplaceLineKeepTheHeadphoneName() {
        val (c, shell) = start(listOf(Page.Home, Page.Settings, Page.Eq(wired)))
        val owner = Any()
        autoEqConfirm(ctx(c, shell), EqSlots.HEADPHONES, "Sennheiser HD 650", SimControl.HD650, owner)
        settle()
        assertEquals("Sennheiser HD 650", title())
        assertEquals("APPLY", item(0))
        tap("sheet.item.0")
        autoEqConfirm(ctx(c, shell), EqSlots.HEADPHONES, "Sennheiser HD 650", SimControl.HD650, owner)
        settle()
        tap("sheet.item.0")
        assertEquals("replace preset?", title())
        assertEquals("Sennheiser HD 650", line(0))
        autoEqConfirm(ctx(c, shell), EqSlots.HEADPHONES, "", null, owner)
        settle()
        assertEquals("import autoeq", title())
        autoEqConfirm(ctx(c, shell), EqSlots.HEADPHONES, "Sennheiser HD 650", null, owner)
        settle()
        assertEquals("Sennheiser HD 650", title())
        assertEquals("CANCEL", item(0))
        autoEqConfirm(ctx(c, shell), EqSlots.HEADPHONES, "Headphone Model 001", SimControl.HD650, owner)
        settle()
        tap("sheet.item.0")
        assertEquals("Headphone Model 001", title())
        assertEquals(listOf("SELECT THAT PROFILE", "SAVE AS"), listOf(item(0), item(1)))
    }

    @Test
    fun serviceSheetsKeepTheServiceNameTheHostAndTheCode() {
        val (c, shell) = start(board = "v1", services = listOf(SvcInfo("tidal", "TIDAL", listOf("client_id"), true)))
        val owner = Any()
        loginSheet(ctx(c, shell), SvcLogins.Login("tidal", 7, "https://link.tidal.com/AbCd-1234", "link.tidal.com", "AbCd-1234"), owner)
        settle()
        assertEquals("TIDAL", title())
        assertEquals(listOf("link.tidal.com", "AbCd-1234"), listOf(line(0), line(1)))
        assertEquals("OPEN", item(0))
        shell.sheet.close()
        settle()
        askSheet(ctx(c, shell), SvcAsk("tidal", "TIDAL", 7, "Login.Tidal.com", "https://Login.Tidal.com/x"), owner)
        settle()
        assertEquals("TIDAL", title())
        assertEquals("Login.Tidal.com", line(0))
        shell.sheet.close()
        settle()
        val flow = KeysFlow()
        serviceSheet(ctx(c, shell), flow, SvcInfo("tidal", "Tidal Max", listOf("Client_ID", "Client_Secret"), true))
        settle()
        assertEquals("Tidal Max", title())
        assertEquals("SIGN IN ON THE DAP", line(0))
        tap("sheet.item.0")
        assertEquals("Tidal Max", title())
        assertEquals(listOf("CLIENT_ID", "CLIENT_SECRET", "CANCEL"), (0..2).map { item(it) })
        tap("sheet.item.0")
        assertEquals("client_id", title())
        shell.sheet.close()
        settle()
        c.holdKeys = true
        keysSheet(ctx(c, shell), flow, KeyDraft("tidal", "Tidal Max", listOf("Client_ID")).also { it.set("Client_ID", "x") })
        settle()
        tap("sheet.item.1")
        assertEquals("Tidal Max", title())
        assertEquals("WAITING FOR THE DAP", line(0))
        shell.sheet.close()
        settle()
        shell.sheet.open("Plain Title", listOf("Item One")) {}
        settle()
        assertEquals("plain title", title())
        assertEquals("ITEM ONE", item(0))
    }

    @Test
    fun artistsDevicesAndLibraryTitlesFollowTheDap() {
        val (c, shell) = start(link = LinkView(Phase.DOWN, "JBL Flip 6"))
        val s = c.device.playback.state
        c.device = c.device.withState(s.copy(artists = listOf("ryo", "Kagura Nana")), 0)
        artistChooser(ctx(c, shell))
        settle()
        assertEquals(listOf("RYO", "KAGURA NANA"), listOf(item(0), item(1)))
        shell.sheet.close()
        settle()
        pickSheet(ctx(c, shell), PickFor("Kagura Nana"))
        settle()
        assertEquals("kagura nana", title())
        shell.sheet.close()
        settle()
        artSheet(ctx(c, shell), BackdropArt(ImageBitmap(1, 1), null, ArtCache.Entry("k", "Radiohead", null, "commons", null, "", 0)))
        settle()
        assertEquals("radiohead", title())
        assertEquals(Sources.label("commons").uppercase(), line(0))
        shell.sheet.close()
        settle()
        c.bonds = listOf(Bonded("TempoTec V1", "00:00:00:00:00:01", true))
        shell.nav.push(Page.Connection)
        settle(1500)
        tap("conn.device")
        assertEquals("TEMPOTEC V1", item(0))
    }

    @Test
    fun accountAndFirmwareFileTitlesKeepTheirCase() {
        val (_, shell) = start(listOf(Page.Home, Page.Online(ONLINE_SCROBBLING)), scrobble = ScrobbleView(Accounts(listenBrainz = true, token = "token", listenBrainzUser = "SeiLent")))
        tap("scrobble.token")
        assertEquals("SeiLent", title())
        assertTrue(shell.sheet.shown)
    }

    @Test
    fun theLastFmAccountTitleKeepsItsCase() {
        start(listOf(Page.Home, Page.Online(ONLINE_SCROBBLING)), scrobble = ScrobbleView(Accounts(true, "", "", "SeiLent", "session"), app = ZEROS))
        tap("scrobble.account")
        assertEquals("SeiLent", title())
    }

    @Test
    fun aPickedFirmwareFileKeepsItsName() {
        start(listOf(Page.Home, Page.Settings, Page.Firmware), board = "v1", minor = 14, prep = { it.pickName = "funeOS-v1.upt" })
        tap("fw.pick")
        assertEquals("funeOS-v1.upt", title())
    }
}
