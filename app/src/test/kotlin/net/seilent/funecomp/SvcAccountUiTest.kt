package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.link.Session
import net.seilent.funecomp.proto.ErrorReply
import net.seilent.funecomp.proto.SvcInfo
import net.seilent.funecomp.proto.SvcLoginEnd
import net.seilent.funecomp.proto.SvcLogout
import net.seilent.funecomp.proto.SvcPkce
import net.seilent.funecomp.proto.SvcState
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.sim.SvcAcct
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.ONLINE_STREAMING
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.accountValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class SvcAccountUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val tidal = SvcInfo("tidal", "TIDAL", listOf("client_id"), true)
    private val clock = AtomicLong(1_000_000)

    @Before
    fun setUp() = Svc.reset()

    @After
    fun tearDown() = Svc.reset()

    private fun start(vararg accounts: String, faults: List<String> = emptyList(), minor: Int = Proto.minor, features: Long = LINK_FEATURES): Pair<SimControl, Shell> {
        val c = SimControl("v1", now = { clock.get() }, faults = faults, minor = minor, services = listOf(tidal), accounts = accounts.map { SvcAcct.parse(it) }, appFeatures = features)
        val shell = Shell(Nav(listOf(Page.Home, Page.Online(ONLINE_STREAMING))))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp).testTag("frame")) {
                App(c.device, LinkView(Phase.LIVE, "V1", everLive = true), c, shell, svc = Svc.view)
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

    private fun row() {
        rule.onNodeWithTag("svc.tidal").performClick()
        settle(600)
    }

    private fun state(c: SimControl, a: String) {
        c.sim.svcSet(null, SvcAcct.parse(a))
        clock.addAndGet(Proto.stateIntervalMs.toLong())
        c.tick()
        settle(600)
    }

    private fun exists(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theRowReadsTheAccountSignedInNotSignedInOrNothing() {
        val (c, _) = start("tidal:in:Night Owl")
        assertEquals("Night Owl", accountValue(c.device, "tidal"))
        state(c, "tidal:in")
        assertEquals("signed in", accountValue(c.device, "tidal"))
        state(c, "tidal:out")
        assertEquals("not signed in", accountValue(c.device, "tidal"))
        state(c, "tidal:unknown")
        assertEquals("", accountValue(c.device, "tidal"))
        assertTrue(exists("svc.tidal"))
        assertEquals("", accountValue(DeviceState(), "tidal"))
        val old = SimControl("v1", minor = 16, services = listOf(tidal), accounts = listOf(SvcAcct.parse("tidal:in:Night Owl")))
        assertEquals("", accountValue(old.device, "tidal"))
        assertNull(old.device.accounts)
    }

    @Test
    fun theSheetOffersSignInOnlySignedOutAndSignOutOnlySignedIn() {
        val (c, shell) = start("tidal:out")
        row()
        assertEquals(listOf("sign in", "custom keys", "cancel"), shell.sheet.items)
        assertTrue(shell.sheet.info.isEmpty())
        shell.sheet.close()
        state(c, "tidal:in:Night Owl")
        row()
        assertEquals(listOf("sign out", "custom keys", "cancel"), shell.sheet.items)
        assertTrue(shell.sheet.info.isEmpty())
        shell.sheet.close()
        state(c, "tidal:unknown")
        row()
        assertEquals(listOf("custom keys", "cancel"), shell.sheet.items)
        assertEquals(listOf("sign in on the dap"), shell.sheet.info)
    }

    @Test
    fun beforeTheFirstStateThereIsNoLineNoItemAndNoValue() {
        val (c, shell) = start("tidal:out")
        c.device = c.device.copy(accounts = null)
        settle(600)
        row()
        assertEquals(listOf("custom keys", "cancel"), shell.sheet.items)
        assertTrue(shell.sheet.info.isEmpty())
        assertEquals("", accountValue(c.device, "tidal"))
    }

    @Test
    fun aDeviceWithoutBit35KeepsTheSignInOnTheDapSheet() {
        val (_, shell) = start("tidal:in:Night Owl", minor = 16)
        row()
        assertEquals(listOf("custom keys", "cancel"), shell.sheet.items)
        assertEquals(listOf("sign in on the dap"), shell.sheet.info)
        assertTrue(exists("svc.tidal"))
    }

    @Test
    fun signInShowsTheWaitingSheetUntilTheReplyAndCancelDeclines() {
        val (c, shell) = start("tidal:out")
        c.holdSvc = true
        row()
        pick(0)
        assertEquals("TIDAL", shell.sheet.title)
        assertEquals(listOf("waiting for the dap"), shell.sheet.info)
        assertEquals(listOf("cancel"), shell.sheet.items)
        assertEquals("start tidal", c.svc.last())
        c.svcReply()
        settle(600)
        assertFalse(shell.sheet.shown)
        row()
        assertEquals(listOf("sign in", "custom keys", "cancel"), shell.sheet.items)
        pick(0)
        pick(0)
        assertFalse(shell.sheet.shown)
        assertEquals("decline tidal", c.svc.last())
    }

    @Test
    fun theWaitingSheetGivesWayToTheAsk() {
        val (c, shell) = start("tidal:out")
        c.opens.remember("tidal", "login.svcdemo.test")
        c.continued = { Svc.begin() }
        c.holdSvc = true
        row()
        pick(0)
        assertEquals(listOf("waiting for the dap"), shell.sheet.info)
        Svc.pkce(SvcPkce("tidal", "https://login.svcdemo.test/auth", "login.svcdemo.test", "https://svcdemo.test/cb", 5), c.device.hello) {}
        settle(600)
        assertFalse(shell.sheet.shown)
        assertEquals(listOf("take tidal 5 open", "continue"), c.svc.filter { it.startsWith("take") || it == "continue" })
        assertTrue(c.svc.none { it.startsWith("decline") })
    }

    @Test
    fun noSignItemWhileAStartOrASignOutWaits() {
        val (c, shell) = start("tidal:out")
        c.holdSvc = true
        row()
        pick(0)
        shell.sheet.close()
        settle(600)
        row()
        assertEquals(listOf("custom keys", "cancel"), shell.sheet.items)
        shell.sheet.close()
        c.svcReply(closed = true)
        settle(600)
        row()
        assertEquals(listOf("sign in", "custom keys", "cancel"), shell.sheet.items)
    }

    @Test
    fun theServiceSheetClosesWhenItsSignItemChangesButAKeysDraftStays() {
        val (c, shell) = start("tidal:out")
        row()
        state(c, "tidal:in:Night Owl")
        assertFalse(shell.sheet.shown)
        row()
        pick(1)
        val d = shell.keys.draft!!
        state(c, "tidal:out")
        assertTrue(shell.sheet.owns(d))
        state(c, "tidal:unknown")
        assertTrue(shell.sheet.owns(d))
    }

    @Test
    fun theConfirmNamesTheAccountAndTheCountAndFocusesKeep() {
        val (c, shell) = start("tidal:in:Night Owl:12345")
        row()
        pick(0)
        assertEquals("sign out?", shell.sheet.title)
        assertEquals(listOf("sign out", "keep signed in"), shell.sheet.items)
        assertEquals(1, shell.sheet.focus)
        assertEquals(listOf("Night Owl", "removes 12345 downloads on this card"), shell.sheet.info)
        assertEquals(listOf(true, false), shell.sheet.infoMixed)
        state(c, "tidal:in:Night Owl:1")
        assertEquals(listOf("Night Owl", "removes 1 download on this card"), shell.sheet.info)
        assertEquals(1, shell.sheet.focus)
        state(c, "tidal:in:Night Owl:0")
        assertEquals(listOf("Night Owl"), shell.sheet.info)
        shell.sheet.close()
        state(c, "tidal:in")
        row()
        pick(0)
        assertTrue(shell.sheet.info.isEmpty())
        pick(1)
        assertTrue(c.logouts.isEmpty())
    }

    @Test
    fun signOutSendsTheRawAccountAndTheShownCount() {
        val (c, shell) = start("tidal:in:Night Owl:0")
        row()
        pick(0)
        pick(0)
        assertEquals(SvcLogout("tidal", "Night Owl", 0), c.logouts.last())
        assertEquals("TIDAL" to "signed out", shell.toast.artist to shell.toast.title)
        state(c, "tidal:in:Night Owl:3")
        row()
        pick(0)
        pick(0)
        assertEquals(SvcLogout("tidal", "Night Owl", 3), c.logouts.last())
        state(c, "tidal:in:Night Owl")
        row()
        pick(0)
        pick(0)
        assertEquals(SvcLogout("tidal", "Night Owl", null), c.logouts.last())
    }

    @Test
    fun aSignOutShowsNoSheetAndNoSignItemUntilItsReply() {
        val (c, shell) = start("tidal:in:Night Owl")
        c.holdSvc = true
        row()
        pick(0)
        pick(0)
        assertFalse(shell.sheet.shown)
        row()
        assertEquals(listOf("custom keys", "cancel"), shell.sheet.items)
        shell.sheet.close()
        c.push(Msg.SVC_STATE, SvcState(listOf(SvcInfo("tidal", "TIDAL", listOf("client_id"), true, false))).encode())
        settle(600)
        row()
        assertEquals(listOf("custom keys", "cancel"), shell.sheet.items)
        shell.sheet.close()
        settle(600)
        c.svcReply()
        settle(600)
        assertEquals("TIDAL" to "signed out", shell.toast.artist to shell.toast.title)
        row()
        assertEquals(listOf("sign in", "custom keys", "cancel"), shell.sheet.items)
    }

    @Test
    fun theConfirmDropsAnAccountLineLongerThanTwoLines() {
        val (c, shell) = start("tidal:in:Night Owl:3")
        row()
        pick(0)
        assertTrue(exists("sheet.info"))
        assertTrue(exists("sheet.info.1"))
        shell.sheet.close()
        state(c, "tidal:in:${"Night Radio Plus Extended Listener ".repeat(6).trim()}:3")
        row()
        pick(0)
        assertTrue(exists("sheet.info"))
        assertFalse(exists("sheet.info.1"))
    }

    @Test
    fun aSignedInOrAccountChangeClosesTheConfirmAndADropToo() {
        val (c, shell) = start("tidal:in:Night Owl")
        row()
        pick(0)
        state(c, "tidal:in:Day Owl")
        assertFalse(shell.sheet.shown)
        row()
        pick(0)
        state(c, "tidal:out")
        assertFalse(shell.sheet.shown)
        state(c, "tidal:in:Day Owl")
        row()
        pick(0)
        c.device = c.device.unlinked()
        settle(600)
        assertFalse(shell.sheet.shown)
    }

    @Test
    fun aReconnectToAnotherDapWithTheSameAccountClosesTheSheets() {
        val (c, shell) = start("tidal:in:Night Owl")
        val hello = c.device.hello!!
        val accounts = c.device.accounts
        fun relinked() {
            c.device = c.device.unlinked()
            settle(600)
            c.device = c.device.withHello(hello, LINK_FEATURES).copy(accounts = accounts)
            settle(600)
        }
        row()
        relinked()
        assertFalse(shell.sheet.shown)
        row()
        pick(0)
        relinked()
        assertFalse(shell.sheet.shown)
        c.holdSvc = true
        row()
        pick(0)
        pick(0)
        assertFalse(shell.sheet.shown)
        val shows = shell.toast.shows
        c.device = c.device.unlinked()
        c.svcReply(closed = true)
        settle(600)
        assertFalse(shell.sheet.shown)
        assertEquals(shows, shell.toast.shows)
    }

    @Test
    fun aDownloadsChangedReopensTheConfirmWithTheNewCount() {
        val (c, shell) = start("tidal:in:Night Owl:3")
        row()
        pick(0)
        c.sim.svcSet(null, SvcAcct.parse("tidal:in:Night Owl:5"))
        pick(0)
        assertEquals(SvcLogout("tidal", "Night Owl", 3), c.logouts.last())
        assertTrue(shell.sheet.shown)
        assertEquals("sign out?", shell.sheet.title)
        assertEquals(1, shell.sheet.focus)
        assertEquals(listOf("Night Owl", "removes 5 downloads on this card"), shell.sheet.info)
        pick(0)
        assertEquals(SvcLogout("tidal", "Night Owl", 5), c.logouts.last())
        assertEquals("TIDAL" to "signed out", shell.toast.artist to shell.toast.title)
    }

    @Test
    fun noneSentWhileTheDapKnowsACountReopensWithIt() {
        val (c, shell) = start("tidal:in:Night Owl:?")
        row()
        pick(0)
        assertEquals(listOf("Night Owl"), shell.sheet.info)
        c.sim.svcSet(null, SvcAcct.parse("tidal:in:Night Owl:4"))
        pick(0)
        assertEquals(SvcLogout("tidal", "Night Owl", null), c.logouts.last())
        assertEquals(listOf("Night Owl", "removes 4 downloads on this card"), shell.sheet.info)
        assertEquals(1, shell.sheet.focus)
    }

    @Test
    fun aDownloadsPushWhileTheSignOutWaitsOpensNothingAndADropDropsTheReopen() {
        val (c, shell) = start("tidal:in:Night Owl:3")
        c.holdSvc = true
        row()
        pick(0)
        pick(0)
        state(c, "tidal:in:Night Owl:4")
        assertFalse(shell.sheet.shown)
        c.svcReply()
        settle(600)
        assertTrue(shell.sheet.shown)
        assertEquals(listOf("Night Owl", "removes 4 downloads on this card"), shell.sheet.info)
        shell.sheet.close()
        c.holdSvc = false
        Svc.logoutReply("tidal", Session.Result.Failed(ErrorReply(Err.staleRev, "downloads changed")), false)
        Svc.dropped(true)
        state(c, "tidal:in:Night Owl:6")
        assertFalse(shell.sheet.shown)
        assertNull(Svc.view.reopen)
    }

    @Test
    fun aCardSwapAnswersNoCardThenNotCountedThenChanged() {
        val (c, shell) = start("tidal:in:Night Owl:3", faults = listOf("card-seq=busy:800,ready"))
        row()
        pick(0)
        assertEquals(listOf("Night Owl"), shell.sheet.info)
        pick(0)
        assertEquals("TIDAL" to "card not available", shell.toast.artist to shell.toast.title)
        clock.addAndGet(800)
        c.tick()
        settle(600)
        row()
        pick(0)
        pick(0)
        assertEquals("TIDAL" to "downloads not counted yet", shell.toast.artist to shell.toast.title)
        assertFalse(shell.sheet.shown)
        c.sim.svcSet(null, SvcAcct.parse("tidal:in:Night Owl:7"))
        row()
        pick(0)
        pick(0)
        assertEquals(SvcLogout("tidal", "Night Owl", null), c.logouts.last())
        assertEquals(listOf("Night Owl", "removes 7 downloads on this card"), shell.sheet.info)
    }

    @Test
    fun aStaleAccountToastsAccountChangedAndAnotherSevenRefuses() {
        val (c, shell) = start("tidal:in:Night Owl")
        row()
        pick(0)
        c.sim.svcSet(null, SvcAcct.parse("tidal:in:Day Owl"))
        pick(0)
        assertEquals("TIDAL" to "account changed", shell.toast.artist to shell.toast.title)
        assertEquals(Svc.REFUSED, Svc.logoutLine(Session.Result.Failed(ErrorReply(Err.staleRev, "other")), false))
    }

    @Test
    fun aStoppedHelperRefusesSignInAndSignOut() {
        val (c, shell) = start("tidal:out", faults = listOf("svc-stopped=tidal"))
        row()
        pick(0)
        assertEquals("TIDAL" to "refused on the dap", shell.toast.artist to shell.toast.title)
        state(c, "tidal:in:Night Owl")
        row()
        pick(0)
        pick(0)
        assertEquals("TIDAL" to "refused on the dap", shell.toast.artist to shell.toast.title)
    }

    @Test
    fun signInWithAnAttachedAskBringsItsPageAndSendsNoStart() {
        val (c, shell) = start("tidal:out")
        Svc.pkce(SvcPkce("tidal", "https://login.svcdemo.test/auth", "login.svcdemo.test", "https://svcdemo.test/cb", 4), c.device.hello) {}
        Svc.begin()
        settle(600)
        row()
        assertEquals(listOf("sign in", "custom keys", "cancel"), shell.sheet.items)
        pick(0)
        assertEquals("front", c.svc.last())
        assertTrue(c.svc.none { it.startsWith("start") })
        assertFalse(shell.sheet.shown)
    }

    @Test
    fun anEndDoneDoesNotOverrideALaterSignedOutState() {
        val (c, _) = start("tidal:out")
        c.push(Msg.SVC_LOGIN_END, SvcLoginEnd("tidal", 1, Enums.SvcLoginResult.done).encode())
        settle(600)
        assertEquals("not signed in", accountValue(c.device, "tidal"))
        c.push(Msg.SVC_STATE, SvcState(listOf(SvcInfo("tidal", "TIDAL", listOf("client_id"), true, true, "Night Owl"))).encode())
        c.push(Msg.SVC_LOGIN_END, SvcLoginEnd("tidal", 2, Enums.SvcLoginResult.done).encode())
        c.push(Msg.SVC_STATE, SvcState(listOf(SvcInfo("tidal", "TIDAL", listOf("client_id"), true, false))).encode())
        settle(600)
        assertEquals("not signed in", accountValue(c.device, "tidal"))
    }
}
