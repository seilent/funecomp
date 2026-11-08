package net.seilent.funecomp

import android.os.SystemClock
import net.seilent.funecomp.link.Session
import net.seilent.funecomp.proto.ErrorReply
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.SvcInfo
import net.seilent.funecomp.proto.SvcLogin
import net.seilent.funecomp.proto.SvcLoginEnd
import net.seilent.funecomp.proto.SvcLoginStart
import net.seilent.funecomp.proto.SvcLoginStartReply
import net.seilent.funecomp.proto.SvcLogout
import net.seilent.funecomp.proto.SvcPkce
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.state.SvcAccount
import net.seilent.funecomp.state.SvcOpens
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.FutureTask

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SvcStartTest {
    private val logs = CopyOnWriteArrayList<String>()
    private val answers = CopyOnWriteArrayList<String>()
    private val fronts = CopyOnWriteArrayList<String>()
    private val timers = ArrayList<Pair<Long, Runnable>>()
    private val hello = HelloReply(1, 17, "v1", "t", 240, 320, 240, 320, 16384, 30_000, 5_000, Groups.all, 1, 1, 4, services = listOf(SvcInfo("tidal", "TIDAL", listOf("client_id"), true), SvcInfo("radio", "Radio", pkce = false)))
    private val ask = SvcPkce("tidal", "https://login.svcdemo.test/auth?state=s5", "login.svcdemo.test", "https://svcdemo.test/cb", 5)

    private class Sent(val type: Int, val payload: ByteArray, val timeoutMs: Long, val done: (Session.Result) -> Unit)

    private val sent = ArrayList<Sent>()

    @Before
    fun setUp() {
        Svc.reset()
        Svc.later = { ms, r ->
            timers += ms to r
            FutureTask<Unit>({}, Unit)
        }
        Svc.hello(hello, "dap")
    }

    @After
    fun tearDown() = Svc.reset()

    private fun record(a: Svc.Answer) {
        answers += when (a) {
            is Svc.Answer.Redirect -> "redirect ${a.url}"
            is Svc.Answer.Error -> "error ${a.code}"
        }
    }

    private fun start(service: String = "tidal", join: (Long) -> String? = { null }): () -> String? {
        var line: String? = "none"
        Svc.start(service, { type, payload, timeout, done -> sent += Sent(type, payload, timeout, done) }, join, { fronts += it }, { logs += it }) { line = it }
        return { line }
    }

    private fun ok(seq: Long) = Session.Result.Ok(Node.decode("SVC_LOGIN_START.reply", SvcLoginStartReply(seq).encode()))

    private fun failed(code: Int, text: String) = Session.Result.Failed(ErrorReply(code, text))

    private fun timer(ms: Long) = timers.last { it.first == ms }.second

    private fun startLine(r: Session.Result): String? {
        val line = start()
        sent.last().done(r)
        return line()
    }

    private fun logoutLine(r: Session.Result, signedOut: Boolean = false): String? {
        var line: String? = "none"
        Svc.logout("tidal", "Night Owl", 3, { type, payload, timeout, done -> sent += Sent(type, payload, timeout, done) }, { signedOut }, { logs += it }) { line = it }
        sent.last().done(r)
        return line
    }

    @Test
    fun aStartGoesOutOnceWithTheServiceTimeout() {
        start()
        assertEquals(1, sent.size)
        assertEquals(Msg.SVC_LOGIN_START, sent[0].type)
        assertEquals(Proto.svcReplyMs.toLong(), sent[0].timeoutMs)
        assertEquals(SvcLoginStart("tidal"), SvcLoginStart.from(Node.decode("SVC_LOGIN_START", sent[0].payload)))
        assertEquals(setOf("tidal"), Svc.view.held)
        sent[0].done(ok(5))
        assertTrue(Svc.view.held.isEmpty())
    }

    @Test
    fun everyStartReplyHasItsLine() {
        assertNull(startLine(ok(5)))
        assertNull(startLine(Session.Result.Closed))
        assertEquals(Svc.SIGNED_IN, startLine(failed(Err.exists, "signed in")))
        assertEquals(Svc.OFF, startLine(failed(Err.off, "off")))
        assertEquals("not installed", startLine(failed(Err.notFound, "not installed")))
        assertEquals(Svc.NO_CARD, startLine(failed(Err.noCard, "card not available")))
        assertEquals(Svc.NO_KEYS, startLine(failed(Err.refused, "no keys")))
        assertEquals(Svc.NO_REPLY, startLine(failed(Err.refused, "timeout")))
        assertEquals(Svc.BUSY, startLine(failed(Err.refused, "retry later")))
        for (t in listOf("refused", "stopped", "broken", "helper said no")) assertEquals(t, Svc.REFUSED, startLine(failed(Err.refused, t)))
        assertEquals(Svc.REFUSED, startLine(failed(Err.badValue, "bad value")))
        assertEquals(Svc.REFUSED, startLine(failed(Err.unknown, "unknown")))
        assertEquals(Svc.ENDED, startLine(failed(Err.cancelled, "cancelled")))
        assertEquals(Svc.NO_REPLY, startLine(failed(Err.busy, "timeout")))
        assertEquals(Svc.BUSY, startLine(failed(Err.busy, "busy")))
        assertEquals(Svc.NO_REPLY, startLine(Session.Result.Timeout))
        assertEquals(Svc.NO_REPLY, startLine(Session.Result.Unsupported))
        assertEquals(Svc.NO_REPLY, startLine(Session.Result.NotReady))
    }

    @Test
    fun everyLogoutReplyHasItsLine() {
        assertEquals(Svc.SIGNED_OUT, logoutLine(Session.Result.Ok(Node("SVC_LOGOUT.reply", emptyList()))))
        assertNull(logoutLine(Session.Result.Closed))
        assertEquals(Svc.ACCOUNT_CHANGED, logoutLine(failed(Err.staleRev, "stale")))
        assertNull(logoutLine(failed(Err.staleRev, "downloads changed")))
        assertEquals(Svc.REFUSED, logoutLine(failed(Err.staleRev, "something else")))
        assertEquals(Svc.OFF, logoutLine(failed(Err.off, "off")))
        assertEquals("not installed", logoutLine(failed(Err.notFound, "not installed")))
        assertEquals(Svc.NO_CARD, logoutLine(failed(Err.noCard, "card not available")))
        for (t in listOf("stopped", "broken", "refused")) assertEquals(t, Svc.REFUSED, logoutLine(failed(Err.refused, t)))
        assertEquals(Svc.NO_REPLY, logoutLine(failed(Err.busy, "timeout")))
        assertEquals(Svc.SIGNED_OUT, logoutLine(failed(Err.busy, "timeout"), signedOut = true))
        assertEquals(Svc.NOT_COUNTED, logoutLine(failed(Err.busy, "downloads not counted")))
        assertEquals(Svc.BUSY, logoutLine(failed(Err.busy, "busy")))
        assertEquals(Svc.REFUSED, logoutLine(failed(Err.unknown, "unknown")))
        assertEquals(Svc.NO_REPLY, logoutLine(Session.Result.Timeout))
        assertEquals(Svc.NO_REPLY, logoutLine(Session.Result.Unsupported))
    }

    @Test
    fun theLogoutCarriesTheRawAccountAndTheShownCount() {
        val send = { account: String, downloads: Long? ->
            Svc.logout("tidal", account, downloads, { type, payload, timeout, done -> sent += Sent(type, payload, timeout, done) }, { false }, { logs += it }) {}
            sent.last()
        }
        val s = send("Night Owl", 3)
        assertEquals(Msg.SVC_LOGOUT, s.type)
        assertEquals(Proto.svcReplyMs.toLong(), s.timeoutMs)
        assertEquals(SvcLogout("tidal", "Night Owl", 3), SvcLogout.from(Node.decode("SVC_LOGOUT", s.payload)))
        assertEquals(setOf("tidal"), Svc.view.held)
        assertEquals(SvcLogout("tidal", "Night Owl", 0), SvcLogout.from(Node.decode("SVC_LOGOUT", send("Night Owl", 0).payload)))
        assertNull(SvcLogout.from(Node.decode("SVC_LOGOUT", send("", null).payload)).downloads)
        s.done(Session.Result.Ok(Node("SVC_LOGOUT.reply", emptyList())))
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.none { "Night Owl" in it })
    }

    @Test
    fun aBoundArmPassesOnlyItsKey() {
        start()
        Svc.pkce(ask.copy(seq = 4), hello, ::record)
        assertFalse(Svc.armed("tidal", 4, ask.authorizeUrl!!))
        Svc.cancel()
        sent[0].done(ok(5))
        assertFalse(Svc.armed("tidal", 5, ask.authorizeUrl!!))
        Svc.pkce(ask, hello, ::record)
        assertTrue(Svc.armed("tidal", 5, ask.authorizeUrl!!))
        assertFalse(Svc.armed("tidal", 6, ask.authorizeUrl!!))
        assertFalse(Svc.armed("tidal", 5, "https://login.svcdemo.test/auth?state=other"))
        assertFalse(Svc.armed("radio", 5, ask.authorizeUrl!!))
        Svc.armTaken("tidal", 5, ask.authorizeUrl!!)
        assertFalse(Svc.armed("tidal", 5, ask.authorizeUrl!!))
    }

    @Test
    fun theArmEndsAt120sAndABoundOneSaysNoReply() {
        start()
        assertEquals(Proto.svcReplyMs + 30_000L, timers.single().first)
        timer(Svc.ARM_MS).run()
        assertNull(Svc.view.line)
        start()
        sent.last().done(ok(5))
        Svc.pkce(ask, hello, ::record)
        timer(Svc.ARM_MS).run()
        assertFalse(Svc.armed("tidal", 5, ask.authorizeUrl!!))
        assertEquals("TIDAL" to Svc.NO_REPLY, Svc.view.line?.let { it.title to it.line })
    }

    @Test
    fun aStopWithABoundArmSaysNothingAt120sAndALinkLossKeepsIt() {
        start()
        sent.last().done(ok(5))
        Svc.pkce(ask, hello, ::record)
        Svc.dropped(false)
        timer(Svc.ARM_MS).run()
        assertNull(Svc.view.line)
        Svc.hello(hello, "dap")
        start()
        sent.last().done(ok(5))
        Svc.pkce(ask, hello, ::record)
        Svc.dropped(true)
        timer(Svc.ARM_MS).run()
        assertEquals("TIDAL" to Svc.NO_REPLY, Svc.view.line?.let { it.title to it.line })
    }

    @Test
    fun everyReplyButOkEndsTheArm() {
        fun armedAfter(r: Session.Result): Boolean {
            Svc.cancel()
            start()
            sent.last().done(r)
            Svc.pkce(ask, hello, ::record)
            return Svc.armed("tidal", 5, ask.authorizeUrl!!)
        }
        assertTrue(armedAfter(ok(5)))
        for (r in listOf(failed(Err.busy, "timeout"), Session.Result.Timeout, failed(Err.refused, "refused"), failed(Err.busy, "busy"), Session.Result.Closed, Session.Result.Unsupported)) assertFalse(armedAfter(r))
    }

    @Test
    fun theArmEndsAtItsEndAtSignedInAtCancelAndAtAnotherDevice() {
        fun bound() {
            Svc.cancel()
            start()
            sent.last().done(ok(5))
            Svc.pkce(ask, hello, ::record)
            assertTrue(Svc.armed("tidal", 5, ask.authorizeUrl!!))
        }
        bound()
        Svc.ended(SvcLoginEnd("tidal", 4, Enums.SvcLoginResult.cancelled))
        assertTrue(Svc.armed("tidal", 5, ask.authorizeUrl!!))
        Svc.over(SvcLoginEnd("tidal", 5, Enums.SvcLoginResult.done))
        assertFalse(Svc.armed("tidal", 5, ask.authorizeUrl!!))
        bound()
        Svc.state(mapOf("tidal" to SvcAccount(false)))
        assertTrue(Svc.armed("tidal", 5, ask.authorizeUrl!!))
        Svc.state(mapOf("tidal" to SvcAccount(true)))
        assertFalse(Svc.armed("tidal", 5, ask.authorizeUrl!!))
        bound()
        Svc.decline("tidal")
        assertFalse(Svc.armed("tidal", 5, ask.authorizeUrl!!))
        bound()
        Svc.dropped(true)
        assertTrue(Svc.armed("tidal", 5, ask.authorizeUrl!!))
        Svc.hello(hello, "dap")
        assertTrue(Svc.armed("tidal", 5, ask.authorizeUrl!!))
        Svc.dropped(false)
        assertFalse(Svc.armed("tidal", 5, ask.authorizeUrl!!))
        Svc.hello(hello, "dap")
        bound()
        Svc.dropped(true)
        Svc.hello(hello, "other dap")
        assertFalse(Svc.armed("tidal", 5, ask.authorizeUrl!!))
    }

    @Test
    fun aRefusedLinkEndsTheArm() {
        start()
        sent[0].done(ok(5))
        Svc.pkce(ask.copy(authorizeUrl = "https://evil.test/auth"), hello, ::record)
        assertEquals(listOf("error ${Err.refused}"), answers)
        Svc.pkce(ask, hello, ::record)
        assertFalse(Svc.armed("tidal", 5, ask.authorizeUrl!!))
    }

    @Test
    fun aJoinTakesTheHeldLoginsLinkAndMarksAHeldAskArmed() {
        start("radio") { seq -> "https://link.svcdemo.test/C$seq".takeIf { seq == 4L } }
        sent[0].done(ok(4))
        assertTrue(Svc.armed("radio", 4, "https://link.svcdemo.test/C4"))
        Svc.loginSeen(SvcLogin("radio", "https://link.svcdemo.test/other", "link.svcdemo.test", "X", 300, 4))
        assertTrue(Svc.armed("radio", 4, "https://link.svcdemo.test/C4"))
        Svc.pkce(ask, hello, ::record)
        assertFalse(Svc.view.ask!!.armed)
        start()
        sent.last().done(ok(5))
        assertTrue(Svc.view.ask!!.armed)
        assertTrue(Svc.armed("tidal", 5, ask.authorizeUrl!!))
        assertTrue(fronts.isEmpty())
    }

    @Test
    fun aStartWithAnAttachedAskFrontsItsPageAndSendsNothing() {
        Svc.pkce(ask, hello, ::record)
        val t = Svc.begin()!!
        val line = start()
        assertEquals(listOf(t), fronts)
        assertTrue(sent.isEmpty())
        assertNull(line())
        Svc.cancel()
        answers.clear()
        Svc.pkce(ask.copy(seq = 6), hello, ::record)
        start()
        assertEquals(1, sent.size)
        val t6 = Svc.begin()!!
        sent[0].done(ok(6))
        assertEquals(listOf(t, t6), fronts)
        assertFalse(Svc.armed("tidal", 6, ask.authorizeUrl!!))
        assertTrue(answers.isEmpty())
    }

    @Test
    fun aCancelBeforeTheReplyDeclinesTheSeqTheReplyBrings() {
        start()
        Svc.decline("tidal")
        sent[0].done(ok(6))
        Svc.pkce(ask.copy(seq = 6), hello, ::record)
        assertEquals(listOf("error ${Err.cancelled}"), answers)
        assertNull(Svc.view.ask)
        Svc.pkce(ask.copy(seq = 7), hello, ::record)
        assertEquals(7L, Svc.view.ask?.seq)
        assertEquals(1, answers.size)
    }

    @Test
    fun aDeclineSeenOnceMatchesAllFiveFieldsInALaterSession() {
        start()
        Svc.decline("tidal")
        sent[0].done(ok(1))
        val q = ask.copy(seq = 1, authorizeUrl = "https://login.svcdemo.test/auth?state=b0s1")
        Svc.pkce(q, hello, ::record)
        Svc.dropped(true)
        Svc.hello(hello, "dap")
        Svc.pkce(q, hello, ::record)
        assertEquals(List(2) { "error ${Err.cancelled}" }, answers)
        Svc.pkce(q.copy(authorizeUrl = "https://login.svcdemo.test/auth?state=b1s1"), hello, ::record)
        assertEquals(2, answers.size)
        assertEquals(1L, Svc.view.ask?.seq)
    }

    @Test
    fun aCancelWithNoReplyAndADropCatchesOnlyTheFirstAskWithin15s() {
        start()
        Svc.decline("tidal")
        sent[0].done(Session.Result.Closed)
        Svc.dropped(true)
        Svc.hello(hello, "dap")
        ShadowSystemClock.advanceBy(Duration.ofMillis(Svc.REASK_MS))
        Svc.pkce(ask, hello, ::record)
        assertEquals(listOf("error ${Err.cancelled}"), answers)
        Svc.pkce(ask.copy(seq = 9), hello, ::record)
        assertEquals(9L, Svc.view.ask?.seq)
        Svc.cancel()
        answers.clear()
        start()
        Svc.decline("tidal")
        sent.last().done(Session.Result.Closed)
        Svc.dropped(true)
        Svc.hello(hello, "dap")
        ShadowSystemClock.advanceBy(Duration.ofMillis(Svc.REASK_MS + 1))
        Svc.pkce(ask, hello, ::record)
        assertTrue(answers.isEmpty())
        Svc.cancel()
        answers.clear()
        start()
        Svc.decline("tidal")
        sent.last().done(Session.Result.Closed)
        Svc.dropped(true)
        Svc.hello(hello, "other dap")
        Svc.pkce(ask, hello, ::record)
        assertTrue(answers.isEmpty())
    }

    @Test
    fun aDeclineEndsAtItsEndAtSignedInAt600sAndAtASignInTap() {
        fun declined(): Boolean {
            Svc.cancel()
            Svc.pkce(ask, hello, ::record)
            return (Svc.view.ask == null).also { Svc.cancel() }
        }
        fun decline() {
            start()
            Svc.decline("tidal")
            sent.last().done(ok(5))
        }
        decline()
        assertTrue(declined())
        decline()
        Svc.dropped(true)
        Svc.hello(hello, "dap")
        assertTrue(declined())
        decline()
        Svc.over(SvcLoginEnd("tidal", 5, Enums.SvcLoginResult.cancelled))
        assertFalse(declined())
        decline()
        Svc.state(mapOf("tidal" to SvcAccount(true)))
        assertFalse(declined())
        decline()
        timer(Proto.svcPkceMs.toLong()).run()
        assertFalse(declined())
        decline()
        start()
        assertFalse(declined())
    }

    @Test
    fun aDownloadsChangedReopensFromTheNextStateOfThatSessionOnly() {
        logoutLine(failed(Err.staleRev, "downloads changed"))
        assertNull(Svc.view.reopen)
        Svc.state(mapOf("radio" to SvcAccount(true)))
        assertNull(Svc.view.reopen)
        Svc.state(mapOf("tidal" to SvcAccount(true, "Night Owl", 5)))
        assertEquals("tidal", Svc.view.reopen)
        Svc.reopened()
        Svc.state(mapOf("tidal" to SvcAccount(true, "Night Owl", 6)))
        assertNull(Svc.view.reopen)
        logoutLine(failed(Err.staleRev, "stale"))
        Svc.state(mapOf("tidal" to SvcAccount(true, "Night Owl", 5)))
        assertNull(Svc.view.reopen)
        logoutLine(failed(Err.staleRev, "something else"))
        Svc.state(mapOf("tidal" to SvcAccount(true, "Night Owl", 5)))
        assertNull(Svc.view.reopen)
        logoutLine(failed(Err.staleRev, "downloads changed"))
        Svc.dropped(true)
        Svc.hello(hello, "dap")
        Svc.state(mapOf("tidal" to SvcAccount(true, "Night Owl", 5)))
        assertNull(Svc.view.reopen)
        var line: String? = "none"
        Svc.logout("tidal", "Night Owl", 3, { type, payload, timeout, done -> sent += Sent(type, payload, timeout, done) }, { false }, { logs += it }) { line = it }
        Svc.state(mapOf("tidal" to SvcAccount(true, "Night Owl", 5)))
        sent.last().done(failed(Err.staleRev, "downloads changed"))
        assertNull(line)
        assertNull(Svc.view.reopen)
        Svc.state(mapOf("tidal" to SvcAccount(true, "Night Owl", 5)))
        assertEquals("tidal", Svc.view.reopen)
    }

    @Test
    fun anArmedKeyPassesTheCapButNeverTheHostRuleOrAnUnlistedService() {
        Svc.opens.remember("tidal", "login.svcdemo.test")
        repeat(SvcOpens.CAP) { Svc.opens.take("tidal", 100L + it, "https://login.svcdemo.test/x$it", true, true, SvcOpens.Kind.OPEN, SystemClock.elapsedRealtime()) }
        fun take(seq: Long, host: String, listed: Boolean): SvcOpens.Verdict {
            Svc.cancel()
            start()
            sent.last().done(ok(seq))
            Svc.pkce(ask.copy(seq = seq), hello, ::record)
            return Remote.svcTake("tidal", seq, ask.authorizeUrl!!, host, listed).also { assertFalse(Svc.armed("tidal", seq, ask.authorizeUrl!!)) }
        }
        assertEquals(SvcOpens.Verdict.ASK, take(5, "login.svcdemo.test", false))
        assertEquals(SvcOpens.Verdict.ASK, take(6, "other.svcdemo.test", true))
        assertEquals(SvcOpens.Verdict.OPEN, take(7, "login.svcdemo.test", true))
        Svc.cancel()
        Svc.pkce(ask.copy(seq = 8), hello, ::record)
        assertEquals(SvcOpens.Verdict.ASK, Remote.svcTake("tidal", 8, ask.authorizeUrl!!, "login.svcdemo.test", true))
    }
}
