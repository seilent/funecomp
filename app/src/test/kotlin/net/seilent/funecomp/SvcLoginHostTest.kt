package net.seilent.funecomp

import android.os.IBinder
import android.os.Process
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.SvcInfo
import net.seilent.funecomp.proto.SvcLoginEnd
import net.seilent.funecomp.proto.SvcPkce
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.proto.Groups
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.FutureTask

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SvcLoginHostTest {
    private val answers = CopyOnWriteArrayList<String>()
    private val deaths = HashMap<IBinder, () -> Unit>()
    private val ends = CopyOnWriteArrayList<String>()
    private val hello = HelloReply(1, 16, "v1", "t", 240, 320, 240, 320, 16384, 30_000, 5_000, Groups.all, 1, 1, 4, services = listOf(SvcInfo("tidal", "TIDAL", listOf("client_id"), true), SvcInfo("plain", "Plain", pkce = false)))
    private val ask = SvcPkce("tidal", "https://login.svcdemo.test/auth?state=s", "login.svcdemo.test", "https://svcdemo.test/cb", 7)
    private val redirect = "https://svcdemo.test/cb?code=c0de&state=s"

    private inner class Page : ISvcLoginPage.Stub() {
        override fun ended() {
            ends += "ended"
        }
    }

    @Before
    fun setUp() {
        Svc.reset()
        Svc.watch = { b, died -> deaths[b] = died }
    }

    @After
    fun tearDown() = Svc.reset()

    private fun record(a: Svc.Answer) {
        answers += when (a) {
            is Svc.Answer.Redirect -> "redirect ${a.url}"
            is Svc.Answer.Error -> "error ${a.code}"
        }
    }

    private fun hold(q: SvcPkce = ask): String {
        Svc.pkce(q, hello, ::record)
        return Svc.begin()!!
    }

    @Test
    fun theAskComesOverTheBinderOnlyForItsToken() {
        val t = hold()
        val page = Page()
        assertNull(Svc.binder.ask("other", page))
        assertNull(Svc.binder.ask(t, null))
        assertArrayEquals(arrayOf(ask.authorizeUrl, ask.host, ask.redirectPrefix, "TIDAL"), Svc.binder.ask(t, page))
        assertTrue(Svc.view.ask!!.page)
        assertTrue(answers.isEmpty())
    }

    @Test
    fun caughtIsRecordedBeforeTheCallReturnsAndADeathAfterItKeepsTheUrl() {
        val t = hold()
        val page = Page()
        Svc.binder.ask(t, page)
        assertTrue(Svc.binder.caught(t, redirect))
        assertEquals(listOf("redirect $redirect"), answers)
        assertNull(Svc.view.ask)
        deaths.getValue(page.asBinder())()
        assertFalse(Svc.binder.closed(t))
        assertFalse(Svc.binder.caught(t, redirect))
        assertEquals(listOf("redirect $redirect"), answers)
    }

    @Test
    fun aDeathWithNoResultAnswersCancelledOnce() {
        val t = hold()
        val page = Page()
        Svc.binder.ask(t, page)
        deaths.getValue(page.asBinder())()
        deaths.getValue(page.asBinder())()
        assertFalse(Svc.binder.cancelled(t))
        assertEquals(listOf("error ${Err.cancelled}"), answers)
        assertEquals("TIDAL" to Svc.ENDED, Svc.view.line?.let { it.title to it.line })
    }

    @Test
    fun aPageClosedWithNoResultEndsWithALineAndBackStaysSilent() {
        val t = hold()
        Svc.binder.ask(t, Page())
        assertTrue(Svc.binder.closed(t))
        assertEquals(Svc.ENDED, Svc.view.line?.line)
        Svc.lineShown(Svc.view.line!!.id)
        val t2 = hold(ask.copy(seq = 8))
        Svc.binder.ask(t2, Page())
        assertTrue(Svc.binder.cancelled(t2))
        assertNull(Svc.view.line)
        assertEquals(listOf("error ${Err.cancelled}", "error ${Err.cancelled}"), answers)
    }

    @Test
    fun anEndThatOvertakesItsAskRefusesThatAskAndHoldsTheNext() {
        assertFalse(Svc.ended(SvcLoginEnd("tidal", 7, Enums.SvcLoginResult.cancelled)))
        Svc.pkce(ask, hello, ::record)
        assertNull(Svc.view.ask)
        assertEquals(listOf("error ${Err.cancelled}"), answers)
        Svc.pkce(ask.copy(seq = 8), hello, ::record)
        assertEquals(8L, Svc.view.ask?.seq)
        assertEquals(1, answers.size)
    }

    @Test
    fun anEndedPairIsForgottenWhenTheLinkDrops() {
        assertFalse(Svc.ended(SvcLoginEnd("tidal", 7, Enums.SvcLoginResult.cancelled)))
        Svc.dropped()
        Svc.pkce(ask, hello, ::record)
        assertEquals(7L, Svc.view.ask?.seq)
        assertTrue(answers.isEmpty())
    }

    @Test
    fun aPageThatNeverAsksEndsTheAskWithALine() {
        val timers = ArrayList<Pair<Long, Runnable>>()
        Svc.later = { ms, r ->
            timers += ms to r
            FutureTask<Unit>({}, Unit)
        }
        val t = hold()
        val start = timers.single { it.first == Svc.START_MS }.second
        start.run()
        start.run()
        assertNull(Svc.view.ask)
        assertEquals(listOf("error ${Err.cancelled}"), answers)
        assertEquals("TIDAL" to Svc.ENDED, Svc.view.line?.let { it.title to it.line })
        assertNull(Svc.binder.ask(t, Page()))
        Svc.pkce(ask.copy(seq = 8), hello, ::record)
        assertEquals(8L, Svc.view.ask?.seq)
        assertEquals(1, answers.size)
    }

    @Test
    fun aNewerStartOrAPageThatAskedKeepsTheAsk() {
        val timers = ArrayList<Pair<Long, Runnable>>()
        Svc.later = { ms, r ->
            timers += ms to r
            FutureTask<Unit>({}, Unit)
        }
        hold()
        val t = Svc.begin()!!
        val starts = timers.filter { it.first == Svc.START_MS }
        assertEquals(2, starts.size)
        starts.first().second.run()
        assertNotNull(Svc.view.ask)
        Svc.binder.ask(t, Page())
        starts.last().second.run()
        assertTrue(Svc.view.ask!!.page)
        assertTrue(answers.isEmpty())
        assertNull(Svc.view.line)
    }

    @Test
    fun anotherUidGetsNothingFromTheBinder() {
        val t = hold()
        ShadowBinder.setCallingUid(Process.myUid() + 1)
        try {
            assertNull(Svc.binder.ask(t, Page()))
            assertFalse(Svc.binder.caught(t, redirect))
            assertFalse(Svc.binder.cancelled(t))
            assertFalse(Svc.binder.closed(t))
        } finally {
            ShadowBinder.setCallingUid(Process.myUid())
        }
        assertTrue(answers.isEmpty())
        assertNotNull(Svc.view.ask)
    }

    @Test
    fun backAndCloseAnswerCancelledOnce() {
        val t = hold()
        Svc.binder.ask(t, Page())
        assertTrue(Svc.binder.cancelled(t))
        assertFalse(Svc.binder.cancelled(t))
        assertFalse(Svc.binder.closed(t))
        assertEquals(listOf("error ${Err.cancelled}"), answers)
    }

    @Test
    fun aWrongTokenAndANonRedirectAreIgnored() {
        val t = hold()
        Svc.binder.ask(t, Page())
        assertFalse(Svc.binder.caught("other", redirect))
        assertFalse(Svc.binder.cancelled("other"))
        assertFalse(Svc.binder.caught(t, "https://evil.test/cb?code=x"))
        assertFalse(Svc.binder.caught(t, "https://svcdemo.test/cbx?code=x"))
        assertFalse(Svc.binder.caught(null, redirect))
        assertTrue(answers.isEmpty())
        assertNotNull(Svc.view.ask)
    }

    @Test
    fun aRedirectPast2048BytesIsCancelledWithALine() {
        val t = hold()
        Svc.binder.ask(t, Page())
        assertTrue(Svc.binder.caught(t, redirect + "x".repeat(2048)))
        assertEquals(listOf("error ${Err.cancelled}"), answers)
        assertEquals(Svc.TOO_LONG, Svc.view.line?.line)
        assertEquals("TIDAL", Svc.view.line?.title)
    }

    @Test
    fun theDeviceEndsTheAskWithNoReply() {
        val t = hold()
        val page = Page()
        Svc.binder.ask(t, page)
        assertFalse(Svc.ended(SvcLoginEnd("tidal", 6, Enums.SvcLoginResult.cancelled)))
        assertNotNull(Svc.view.ask)
        assertTrue(Svc.ended(SvcLoginEnd("tidal", 7, Enums.SvcLoginResult.cancelled)))
        assertNull(Svc.view.ask)
        assertEquals(Svc.ENDED, Svc.view.line?.line)
        assertEquals(listOf("ended"), ends)
        assertFalse(Svc.binder.caught(t, redirect))
        deaths.getValue(page.asBinder())()
        assertTrue(answers.isEmpty())
    }

    @Test
    fun aDropOrTheTimeoutEndsTheAskWithNoReply() {
        Svc.binder.ask(hold(), Page())
        Svc.dropped()
        assertNull(Svc.view.ask)
        assertEquals(Svc.ENDED, Svc.view.line?.line)
        val timers = ArrayList<Pair<Long, Runnable>>()
        Svc.later = { ms, r ->
            timers += ms to r
            FutureTask<Unit>({}, Unit)
        }
        val t = hold(ask.copy(seq = 8))
        Svc.binder.ask(t, Page())
        assertEquals(listOf(Proto.svcPkceMs.toLong(), Svc.START_MS), timers.map { it.first })
        assertNotNull(Svc.view.ask)
        timers.first().second.run()
        assertNull(Svc.view.ask)
        assertFalse(Svc.binder.caught(t, redirect))
        assertTrue(answers.isEmpty())
        assertEquals(listOf("ended", "ended"), ends)
    }

    @Test
    fun doneAfterTheRedirectSaysSignedIn() {
        val t = hold()
        Svc.binder.ask(t, Page())
        Svc.binder.caught(t, redirect)
        assertTrue(Svc.ended(SvcLoginEnd("tidal", 7, Enums.SvcLoginResult.done)))
        assertEquals(Svc.SIGNED_IN, Svc.view.line?.line)
        assertFalse(Svc.ended(SvcLoginEnd("tidal", 7, Enums.SvcLoginResult.done)))
    }

    @Test
    fun aSecondAskIsBusyAndBadAsksAreRefused() {
        Svc.pkce(ask, hello, ::record)
        Svc.pkce(ask.copy(seq = 8), hello, ::record)
        assertEquals(listOf("error ${Err.busy}"), answers)
        Svc.cancel()
        answers.clear()
        Svc.pkce(ask.copy(service = "plain"), hello, ::record)
        Svc.pkce(ask.copy(service = "none"), hello, ::record)
        Svc.pkce(ask.copy(seq = null), hello, ::record)
        Svc.pkce(ask.copy(redirectPrefix = null), hello, ::record)
        assertEquals(listOf("error ${Err.refused}", "error ${Err.refused}", "error ${Err.badValue}", "error ${Err.badValue}"), answers)
        assertNull(Svc.view.ask)
    }

    @Test
    fun aRefusedLinkIsAnswered4AtOnceWithOneLinePerLink() {
        Svc.pkce(ask.copy(authorizeUrl = "https://evil.test/auth"), hello, ::record)
        assertNull(Svc.view.ask)
        assertNull(Svc.begin())
        assertEquals(listOf("error ${Err.refused}"), answers)
        assertEquals("TIDAL" to "login link refused", Svc.view.line?.let { it.title to it.line })
        Svc.lineShown(Svc.view.line!!.id)
        Svc.pkce(ask.copy(authorizeUrl = "https://evil.test/auth"), hello, ::record)
        assertNull(Svc.view.line)
        Svc.pkce(ask.copy(redirectPrefix = "https://svcdemo.test/cb?x=1"), hello, ::record)
        assertNull(Svc.view.ask)
        assertEquals("login link refused", Svc.view.line?.line)
        assertEquals(List(3) { "error ${Err.refused}" }, answers)
        Svc.pkce(ask, hello, ::record)
        assertEquals("https://login.svcdemo.test/auth?state=s", Svc.view.ask?.link)
    }

    @Test
    fun aTapArmsOneOpenFor10s() {
        assertFalse(Svc.spendTap())
        Svc.tapped()
        ShadowSystemClock.advanceBy(Duration.ofMillis(Svc.TAP_MS))
        assertTrue(Svc.spendTap())
        assertFalse(Svc.spendTap())
        Svc.tapped()
        ShadowSystemClock.advanceBy(Duration.ofMillis(Svc.TAP_MS + 1))
        assertFalse(Svc.spendTap())
    }
}
