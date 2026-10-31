package net.seilent.funecomp

import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.SvcInfo
import net.seilent.funecomp.proto.SvcLogin
import net.seilent.funecomp.proto.SvcLoginEnd
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Msg
import androidx.compose.foundation.layout.WindowInsets
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LoginPage
import net.seilent.funecomp.ui.Ui
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.ONLINE_STREAMING
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.SvcAsk
import net.seilent.funecomp.ui.SvcLine
import net.seilent.funecomp.ui.SvcView
import net.seilent.funecomp.ui.onlinePivots
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class SvcUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val tidal = SvcInfo("tidal", "TIDAL", listOf("client_id", "client_secret"), true)
    private var svc by mutableStateOf(SvcView())
    private var keyboard by mutableStateOf(0.dp)

    private fun start(pages: List<Page> = listOf(Page.Home), services: List<SvcInfo> = listOf(tidal), faults: List<String> = emptyList(), known: List<String> = emptyList()): Pair<SimControl, Shell> {
        val c = SimControl("v1", faults = faults, services = services)
        known.forEach { c.opens.remember("tidal", it) }
        c.continued = { svc.ask?.let { svc = svc.copy(ask = it.copy(page = true)) } }
        c.cancelled = { svc = svc.copy(ask = null) }
        val shell = Shell(Nav(pages))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp).testTag("frame")) {
                App(c.device, LinkView(Phase.LIVE, "V1", everLive = true), c, shell, svc = svc, ime = WindowInsets(bottom = keyboard))
            }
        }
        settle(1000)
        return c to shell
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun pick(i: Int) {
        rule.onNodeWithTag("sheet.item.$i").performClick()
        settle(600)
    }

    private fun exists(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun login(c: SimControl, seq: Long, uri: String = "https://link.svcdemo.test/ABCDE") {
        c.push(Msg.SVC_LOGIN, SvcLogin("tidal", uri, "link.svcdemo.test", "ABCDE", 300, seq).encode())
        settle(600)
    }

    private var host by mutableStateOf("login.svcdemo.test")

    private fun countEdges(): Pair<Float, Float> {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp).testTag("frame")) {
                LoginPage("TIDAL", host, {}, insets = WindowInsets(0.dp), ime = WindowInsets(0.dp))
            }
        }
        settle(1000)
        val b = rule.onNodeWithTag("count").fetchSemanticsNode().boundsInRoot
        return b.left to b.right
    }

    @Test
    fun theLoginHostLineKeepsTheEndOfALongHost() {
        val ui = Ui.of(rule.density.run { 411.dp.toPx() }, rule.density.run { 960.dp.toPx() })
        val edge = ui.px(Tok.hubEdge)
        val end = ui.px(ui.w) - edge
        host = "accounts.signin.identity.provider.example.region.cluster.edge.authentication.login.svcdemo.test"
        val (left, right) = countEdges()
        assertEquals(edge, left, 1f)
        assertEquals(end, right, 1f)
        host = "login.svcdemo.test"
        settle(1000)
        val short = rule.onNodeWithTag("count").fetchSemanticsNode().boundsInRoot
        assertEquals(edge, short.left, 1f)
        assertTrue(short.right < end - 10f)
    }

    @Test
    fun theStreamingPivotNeedsAServiceAndBit32() {
        val (c, _) = start()
        assertEquals(listOf("art", "lyrics", "scrobbling", "streaming"), onlinePivots(c.device))
        assertEquals(listOf("art", "lyrics", "scrobbling"), onlinePivots(SimControl("v1").device))
        assertEquals(listOf("art", "lyrics", "scrobbling"), onlinePivots(SimControl("v1", services = listOf(tidal), faults = listOf("features=${Groups.all and Groups.mask("svcLogin").inv()}")).device))
        assertEquals(listOf("art", "lyrics", "scrobbling"), onlinePivots(SimControl("v1", services = listOf(tidal), appFeatures = LINK_FEATURES and Groups.mask("svcLogin").inv()).device))
    }

    @Test
    fun aDeviceCodeLoginOpensItsSheetAndOpenUsesTheLink() {
        val (c, shell) = start()
        login(c, 7)
        assertTrue(shell.sheet.shown)
        assertEquals("TIDAL", shell.sheet.title)
        assertEquals(listOf("link.svcdemo.test", "ABCDE"), shell.sheet.info)
        assertEquals(listOf("open", "cancel"), shell.sheet.items)
        assertTrue("sheet" in c.svc)
        pick(0)
        assertTrue("open tidal 7" in c.svc)
        assertTrue("seen tidal 7" in c.svc)
        assertFalse(shell.sheet.shown)
        login(c, 7)
        assertFalse(shell.sheet.shown)
    }

    @Test
    fun aNewSeqReplacesTheSheetAndItsEndClosesIt() {
        val (c, shell) = start()
        login(c, 7)
        login(c, 8)
        assertTrue(shell.sheet.shown)
        c.push(Msg.SVC_LOGIN_END, SvcLoginEnd("tidal", 8, Enums.SvcLoginResult.expired).encode())
        settle(600)
        assertFalse(shell.sheet.shown)
        assertFalse("open tidal 8" in c.svc)
    }

    @Test
    fun aRefusedLinkShowsNoSheet() {
        val (c, shell) = start()
        login(c, 7, "link.svcdemo.test/ABCDE")
        assertFalse(shell.sheet.shown)
        assertTrue(c.svc.none { it.startsWith("take") || it.startsWith("open") })
    }

    private fun ask(seq: Long, host: String = "login.svcdemo.test", page: Boolean = false) = SvcView(SvcAsk("tidal", "TIDAL", seq, host, "https://$host/auth?state=s$seq", page))

    @Test
    fun aFirstHostShowsTheHostWithContinueAndTheNextAskOpensByItself() {
        val (c, shell) = start()
        svc = ask(7)
        settle(600)
        assertEquals("TIDAL", shell.sheet.title)
        assertEquals(listOf("login.svcdemo.test"), shell.sheet.info)
        assertEquals(listOf("continue", "cancel"), shell.sheet.items)
        assertEquals(listOf("take tidal 7 ask", "sheet"), c.svc)
        pick(0)
        assertEquals(listOf("known tidal login.svcdemo.test", "continue"), c.svc.drop(2))
        svc = ask(7, page = true)
        settle(600)
        assertFalse(shell.sheet.shown)
        svc = SvcView()
        settle(600)
        c.svc.clear()
        svc = ask(8)
        settle(600)
        assertFalse(shell.sheet.shown)
        assertEquals(listOf("take tidal 8 open", "continue", "sheet"), c.svc)
        svc = SvcView()
        settle(600)
        c.svc.clear()
        svc = ask(9, "other.svcdemo.test")
        settle(600)
        assertEquals(listOf("other.svcdemo.test"), shell.sheet.info)
        pick(1)
        assertEquals(listOf("take tidal 9 ask", "sheet", "cancel"), c.svc)
        assertFalse(c.opens.known("tidal", "other.svcdemo.test"))
        svc = SvcView()
        settle(600)
        assertFalse(shell.sheet.shown)
    }

    @Test
    fun aKnownHostOpensBothKindsWithNoTapAndAPauseStartsNothingMore() {
        val (c, shell) = start(known = listOf("login.svcdemo.test", "link.svcdemo.test"))
        svc = ask(7)
        settle(600)
        assertFalse(shell.sheet.shown)
        assertEquals(listOf("take tidal 7 open", "continue", "sheet"), c.svc)
        svc = ask(7, page = true)
        settle(600)
        c.svc.clear()
        login(c, 8)
        assertEquals("TIDAL", shell.sheet.title)
        assertEquals(listOf("open", "cancel"), shell.sheet.items)
        assertEquals(listOf("link.svcdemo.test", "ABCDE"), shell.sheet.info)
        assertEquals(listOf("take tidal 8 open", "open tidal 8", "sheet"), c.svc)
        shell.front = false
        settle(600)
        shell.front = true
        settle(600)
        assertEquals(listOf("take tidal 8 open", "open tidal 8", "sheet"), c.svc)
        assertTrue(shell.sheet.shown)
    }

    @Test
    fun anUnlistedServiceAsksWithItsSheet() {
        val (c, shell) = start(known = listOf("link.svcdemo.test"))
        c.opens.remember("other", "link.svcdemo.test")
        c.push(Msg.SVC_LOGIN, SvcLogin("other", "https://link.svcdemo.test/ABCDE", "link.svcdemo.test", "ABCDE", 300, 3).encode())
        settle(600)
        assertEquals(listOf("open", "cancel"), shell.sheet.items)
        assertEquals(listOf("take other 3 ask", "sheet"), c.svc)
    }

    @Test
    fun theServiceSheetGivesWayToAnAsk() {
        val (c, shell) = start(listOf(Page.Home, Page.Online(ONLINE_STREAMING)), known = listOf("login.svcdemo.test"))
        rule.onNodeWithTag("svc.tidal").performClick()
        settle(600)
        assertEquals(listOf("sign in on the dap"), shell.sheet.info)
        svc = ask(7)
        settle(600)
        assertFalse(shell.sheet.shown)
        assertEquals(listOf("take tidal 7 open", "continue", "sheet"), c.svc)
    }

    @Test
    fun anotherSheetOrAPausedPageWaitsAndTheOpenRunsWhenItClears() {
        val (c, shell) = start(known = listOf("login.svcdemo.test"))
        shell.sheet.open("other", listOf("a")) {}
        settle(600)
        svc = ask(7)
        settle(600)
        assertTrue(c.svc.isEmpty())
        shell.sheet.close()
        settle(600)
        assertEquals(listOf("take tidal 7 open", "continue", "sheet"), c.svc)
        svc = SvcView()
        settle(600)
        c.svc.clear()
        shell.front = false
        settle(600)
        svc = ask(8)
        settle(600)
        assertTrue(c.svc.isEmpty())
        shell.front = true
        settle(600)
        assertEquals(listOf("take tidal 8 open", "continue", "sheet"), c.svc)
    }

    @Test
    fun anOpenKeyboardWaitsAndTheOpenRunsWhenItCloses() {
        val (c, _) = start(known = listOf("login.svcdemo.test"))
        keyboard = 300.dp
        settle(600)
        svc = ask(7)
        settle(600)
        assertTrue(c.svc.isEmpty())
        keyboard = 0.dp
        settle(600)
        assertEquals(listOf("take tidal 7 open", "continue", "sheet"), c.svc)
    }

    @Test
    fun aFocusedSearchFieldWithTheKeyboardClosedDoesNotWait() {
        val (c, _) = start(listOf(Page.Home, Page.Settings, Page.Eq(0), Page.AutoEq(0)), known = listOf("login.svcdemo.test"))
        rule.onNodeWithTag("aeq.field").performClick()
        settle(600)
        svc = ask(7)
        settle(600)
        assertEquals(listOf("take tidal 7 open", "continue", "sheet"), c.svc)
    }

    @Test
    fun aLineShowsAsAToastOnce() {
        val (_, shell) = start()
        val before = shell.toast.shows
        svc = SvcView(line = SvcLine("TIDAL", "signed in", 1))
        settle(600)
        assertEquals(before + 1, shell.toast.shows)
        assertEquals("TIDAL" to "signed in", shell.toast.artist to shell.toast.title)
    }

    @Test
    fun aLinePendingAtTheFirstCompositionShowsToo() {
        svc = SvcView(line = SvcLine("TIDAL", "signed in", 1))
        start()
        assertTrue(exists("toast"))
        assertEquals("signed in", rule.onNodeWithTag("toast.title").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text })
    }

    private fun keys(c: SimControl, shell: Shell) {
        rule.onNodeWithTag("svc.tidal").performClick()
        settle(600)
        pick(0)
        assertEquals("TIDAL", shell.sheet.title)
        assertTrue(c.secured)
    }

    @Test
    fun aServiceRowOpensTheSignInSheetAndCustomKeysOpensTheKeysSheet() {
        val (c, shell) = start(listOf(Page.Home, Page.Online(ONLINE_STREAMING)))
        c.secures.clear()
        rule.onNodeWithTag("svc.tidal").performClick()
        settle(600)
        assertEquals("TIDAL", shell.sheet.title)
        assertEquals(listOf("sign in on the dap"), shell.sheet.info)
        assertEquals(listOf("custom keys", "cancel"), shell.sheet.items)
        assertFalse(c.secured)
        assertFalse(true in c.secures)
        assertNull(shell.keys.draft)
        pick(0)
        assertEquals(listOf("client_id", "client_secret", "cancel"), shell.sheet.items)
        assertTrue(c.secured)
        pick(2)
        assertFalse(shell.sheet.shown)
        assertFalse(c.secured)
    }

    @Test
    fun cancelOnTheSignInSheetSendsAndSecuresNothing() {
        val (c, shell) = start(listOf(Page.Home, Page.Online(ONLINE_STREAMING)))
        rule.onNodeWithTag("svc.tidal").performClick()
        settle(600)
        pick(1)
        assertFalse(shell.sheet.shown)
        assertFalse(c.secured)
        assertNull(shell.keys.draft)
        assertTrue(c.svc.none { it.startsWith("keys") })
    }

    @Test
    fun aServiceWithNoSecretNamesGetsTheLineAndCancelOnly() {
        val (c, shell) = start(listOf(Page.Home, Page.Online(ONLINE_STREAMING)), services = listOf(tidal, SvcInfo("svcdemo", "SVCDEMO", emptyList(), false)))
        rule.onNodeWithTag("svc.svcdemo").performClick()
        settle(600)
        assertEquals("SVCDEMO", shell.sheet.title)
        assertEquals(listOf("sign in on the dap"), shell.sheet.info)
        assertEquals(listOf("cancel"), shell.sheet.items)
        pick(0)
        assertFalse(shell.sheet.shown)
        assertFalse(c.secured)
    }

    @Test
    fun theKeysSheetSendsOnlyFilledNamesAndDropsTheValues() {
        val (c, shell) = start(listOf(Page.Home, Page.Online(ONLINE_STREAMING)))
        keys(c, shell)
        assertEquals(listOf("client_id", "client_secret", "cancel"), shell.sheet.items)
        assertEquals(listOf("not set", "not set"), shell.sheet.itemValues)
        pick(0)
        assertEquals("client_id", shell.sheet.title)
        secretField().append(" s3cret ")
        secretField().onEditorAction(EditorInfo.IME_ACTION_DONE)
        settle(600)
        assertEquals(listOf("client_id", "client_secret", "send", "cancel"), shell.sheet.items)
        assertEquals(listOf("set", "not set"), shell.sheet.itemValues)
        assertTrue(c.secured)
        val d = shell.keys.draft!!
        pick(2)
        assertFalse(d.any)
        assertEquals("keys tidal [client_id]", c.svc.last())
        assertFalse(c.secured)
        assertNull(shell.keys.draft)
        assertFalse(shell.sheet.shown)
        assertEquals("TIDAL" to "keys sent", shell.toast.artist to shell.toast.title)
    }

    @Test
    fun theWaitingSheetShowsUntilTheReplyAndEveryCodeHasItsLine() {
        val (c, shell) = start(listOf(Page.Home, Page.Online(ONLINE_STREAMING)), faults = listOf("svc-off"))
        c.holdKeys = true
        keys(c, shell)
        pick(0)
        secretField().append("x")
        secretField().onEditorAction(EditorInfo.IME_ACTION_DONE)
        settle(600)
        pick(2)
        assertEquals(listOf("waiting for the dap"), shell.sheet.info)
        assertFalse(c.secured)
        c.keysReply!!()
        settle(600)
        assertFalse(shell.sheet.shown)
        assertEquals("TIDAL" to "streaming is off on the dap", shell.toast.artist to shell.toast.title)
    }

    @Test
    fun cancelScrimAndBackDropTheDraftAndTheSecureFlag() {
        val (c, shell) = start(listOf(Page.Home, Page.Online(ONLINE_STREAMING)))
        keys(c, shell)
        pick(2)
        assertFalse(c.secured)
        assertNull(shell.keys.draft)
        keys(c, shell)
        shell.sheet.scrim()
        settle(600)
        assertFalse(c.secured)
        assertNull(shell.keys.draft)
        keys(c, shell)
        pick(0)
        secretField().append("x")
        shell.sheet.close()
        settle(600)
        assertFalse(c.secured)
        assertNull(shell.keys.draft)
        assertTrue(c.svc.none { it.startsWith("keys") })
    }

    @Test
    fun aDropOfBit32ClosesTheKeysSheet() {
        val (c, shell) = start(listOf(Page.Home, Page.Online(ONLINE_STREAMING)))
        keys(c, shell)
        c.device = c.device.copy(hello = c.device.hello!!.copy(features = c.device.hello!!.features and Groups.mask("svcLogin").inv()))
        settle(600)
        assertFalse(shell.sheet.shown)
        assertFalse(c.secured)
        assertFalse(exists("svc.tidal"))
    }
}
