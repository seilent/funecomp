package net.seilent.funecomp

import net.seilent.funecomp.link.Session
import net.seilent.funecomp.proto.BindAskReply
import net.seilent.funecomp.proto.ErrorReply
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.Lib
import net.seilent.funecomp.proto.LibDeleteReply
import net.seilent.funecomp.proto.LibRow
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.PageInfo
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Tags
import net.seilent.funecomp.state.CardView
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.state.LibDelete
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executor

class LibDeletesTest {
    private class Sent(val session: Any?, val type: Int, val payload: ByteArray, val timeoutMs: Long, val done: (Session.Result) -> Unit) {
        val name: String get() = Msg.info.getValue(type).name
    }

    private val key = "rfcomm:AA:BB:CC:00:00:01"
    private val session = Any()
    private val token = ByteArray(16) { (it + 3).toByte() }
    private var now = 1_000L
    private val sent = ArrayList<Sent>()
    private val toasts = ArrayList<Pair<String, String>>()
    private var route: ((String, String) -> Unit)? = { l, t -> toasts += l to t }
    private var device = DeviceState().withHello(hello(), Groups.all)
    private val request = BindAsk.Request { s, type, payload, timeoutMs, done -> sent += Sent(s, type, payload, timeoutMs, done) }
    private val deletes = LibDeletes(Executor { it.run() }, request, BindAsk(request), { device }, { route }, { it() }, { now })

    private fun hello(snap: Long = 4) = HelloReply(1, 18, "v1", "t", 240, 320, 240, 320, 16384, 30_000, 5_000, Groups.all, 1, 1, snap, bootId = 77)

    private fun song() = LibDelete.ask(device, LibRow("Raspberry Beret", 1, 201, "201"), false, key, session)!!

    private fun album() = LibDelete.ask(device, LibRow("Windswept Adan", 4, 301, "301", artist = "Ichiko Aoba"), true, key, session)!!

    private fun tap() = Tap(now) { now }

    private fun ok(type: String, body: ByteArray) = Session.Result.Ok(Node.decode("$type.reply", body))

    private fun fail(code: Int, text: String) = Session.Result.Failed(ErrorReply(code, text))

    private fun snapTo(snap: Long) {
        device = device.withState(State(snapRev = snap, libRev = 1), now)
    }

    @Before
    fun setUp() = BindTokens.reset()

    @After
    fun tearDown() = BindTokens.reset()

    @Test
    fun bothMessagesWaitTheDeleteBoundAndASessionTimeout() {
        deletes.delete(song(), tap())
        assertEquals("BIND_ASK", sent.single().name)
        assertEquals(100_000L, sent.single().timeoutMs)
        sent.single().done(ok("BIND_ASK", BindAskReply(token).encode()))
        assertEquals("LIB_DELETE", sent[1].name)
        assertEquals(100_000L, sent[1].timeoutMs)
        assertTrue(sent.all { it.session === session })
    }

    @Test
    fun oneDeleteAtATime() {
        BindTokens.put(key, token)
        deletes.delete(song(), tap())
        deletes.delete(album(), tap())
        assertEquals(listOf("LIB_DELETE"), sent.map { it.name })
        assertTrue(deletes.waiting)
    }

    @Test
    fun theMarkClearsOnClosedTimeoutAndTheLongBusyAndNothingIsSentAgain() {
        BindTokens.put(key, token)
        for (r in listOf(Session.Result.Closed, Session.Result.Timeout, fail(Err.busy, "busy"))) {
            sent.clear()
            deletes.delete(song(), tap())
            assertTrue(deletes.waiting)
            sent.single().done(r)
            assertFalse(deletes.waiting)
            assertEquals(1, sent.size)
        }
        assertTrue(toasts.isEmpty())
    }

    @Test
    fun aNotAllowedDropsTheTokenAsksOnceAndASecondOneToastsAndAsksNoMore() {
        BindTokens.put(key, token)
        deletes.delete(song(), tap())
        sent.last().done(fail(Err.refused, "not allowed"))
        assertNull(BindTokens.get(key))
        assertEquals("BIND_ASK", sent.last().name)
        assertEquals(DeviceState.CONFIRM to "Raspberry Beret", toasts.last())
        val fresh = ByteArray(16) { (it + 9).toByte() }
        sent.last().done(ok("BIND_ASK", BindAskReply(fresh).encode()))
        assertEquals("LIB_DELETE", sent.last().name)
        assertTrue(fresh.contentEquals(Node.decode("LIB_DELETE", sent.last().payload).bytes(Tags.LibDelete.bindToken)))
        sent.last().done(fail(Err.refused, "not allowed"))
        assertEquals(listOf("LIB_DELETE", "BIND_ASK", "LIB_DELETE"), sent.map { it.name })
        assertEquals("song not deleted" to "Raspberry Beret", toasts.last())
        assertFalse(deletes.waiting)
    }

    @Test
    fun anOutcomeIsKeptWhileTheRouteIsAwayAndShownAtItsReturn() {
        BindTokens.put(key, token)
        deletes.delete(song(), tap())
        route = null
        sent.last().done(fail(Err.readOnly, "card is read only"))
        assertTrue(toasts.isEmpty())
        route = { l, t -> toasts += l to t }
        deletes.started()
        assertEquals(listOf("song not deleted" to "card is read only"), toasts)
        deletes.started()
        assertEquals(1, toasts.size)
    }

    @Test
    fun aKeptOutcomeGoesAfterAHelloOrFiveMinutes() {
        BindTokens.put(key, token)
        deletes.delete(song(), tap())
        route = null
        sent.last().done(fail(Err.readOnly, "card is read only"))
        device = device.withHello(hello(), Groups.all)
        route = { l, t -> toasts += l to t }
        deletes.started()
        assertTrue(toasts.isEmpty())
        deletes.delete(song(), tap())
        route = null
        sent.last().done(fail(Err.readOnly, "card is read only"))
        now += LibDeletes.KEEP_MS + 1
        route = { l, t -> toasts += l to t }
        deletes.started()
        assertTrue(toasts.isEmpty())
    }

    @Test
    fun theExitBaseIsTheSnapAtTheReplyNotALaterState() {
        val posts = ArrayList<() -> Unit>()
        val d = LibDeletes(Executor { it.run() }, request, BindAsk(request), { device }, { route }, { posts += it }, { now })
        fun drain() = posts.toList().also { posts.clear() }.forEach { it() }
        BindTokens.put(key, token)
        d.delete(album(), tap())
        sent.last().done(ok("LIB_DELETE", LibDeleteReply(4, 0).encode()))
        snapTo(5)
        drain()
        assertEquals(4L, d.owed!!.base)
        val stack = arrayListOf("home", "music", LibDelete.artistPage("Ichiko Aoba"), LibDelete.albumPage("301"))
        d.exits(key, session, LibDeletes.Exits({ stack }, { false }) { stack.removeLastOrNull() != null })
        assertEquals(listOf("home", "music", LibDelete.artistPage("Ichiko Aoba")), stack)
        assertEquals("LIB_GET", sent.last().name)
        val ask = Node.decode("LIB_GET", sent.last().payload)
        assertEquals(Enums.View.artistAlbums, ask.int(Tags.LibGet.view))
        assertEquals("Ichiko Aoba", ask.str(Tags.LibGet.parent))
        assertEquals(1L, ask.long(Tags.LibGet.count))
        sent.last().done(fail(Err.staleRev, "stale rev"))
        drain()
        assertEquals(listOf("home", "music"), stack)
        assertNull(d.owed)
    }

    @Test
    fun theArtistProbeCarriesNoRevSoAStaleRevMeansTheArtistIsGone() {
        BindTokens.put(key, token)
        deletes.delete(album(), tap())
        sent.last().done(ok("LIB_DELETE", LibDeleteReply(4, 0).encode()))
        snapTo(5)
        val stack = arrayListOf("home", LibDelete.artistPage("Ichiko Aoba"))
        deletes.exits(key, session, LibDeletes.Exits({ stack }, { false }) { stack.removeLastOrNull() != null })
        val probe = Node.decode("LIB_GET", sent.last().payload)
        assertNull(probe.long(Tags.LibGet.libRev))
        assertNull(probe.long(Tags.LibGet.snapRev))
        snapTo(6)
        sent.last().done(fail(Err.staleRev, "stale rev"))
        assertEquals(1, sent.count { it.name == "LIB_GET" })
        assertEquals(listOf("home"), stack)
        assertNull(deletes.owed)
    }

    @Test
    fun theQuietAnswersShowNothing() {
        BindTokens.put(key, token)
        val quiet = listOf(fail(Err.busy, "delete waiting"), fail(Err.notFound, "not found"), fail(Err.badValue, "bad value"), fail(Err.noLibrary, "no library"), fail(Err.unknown, "unknown"), Session.Result.NotReady, Session.Result.Unsupported)
        for (r in quiet) {
            deletes.delete(song(), tap())
            sent.last().done(r)
        }
        assertEquals(quiet.size, sent.count { it.name == "LIB_DELETE" })
        assertTrue(toasts.isEmpty())
    }

    @Test
    fun aBindReplyWithAShortTokenToastsAndSendsNoDelete() {
        deletes.delete(song(), tap())
        sent.last().done(ok("BIND_ASK", BindAskReply(ByteArray(15)).encode()))
        assertEquals(listOf("BIND_ASK"), sent.map { it.name })
        assertEquals("song not deleted" to "Raspberry Beret", toasts.last())
        assertFalse(deletes.waiting)
    }

    @Test
    fun anArtistLeavesOnAnOkWithNoAlbumsAndStaysWithOne() {
        for ((total, left) in listOf(0L to listOf("home"), 1L to listOf("home", LibDelete.artistPage("Ichiko Aoba")))) {
            BindTokens.put(key, token)
            device = DeviceState().withHello(hello(), Groups.all)
            deletes.delete(album(), tap())
            sent.last().done(ok("LIB_DELETE", LibDeleteReply(4, 0).encode()))
            snapTo(5)
            val stack = arrayListOf("home", LibDelete.artistPage("Ichiko Aoba"))
            deletes.exits(key, session, LibDeletes.Exits({ stack }, { false }) { stack.removeLastOrNull() != null })
            device = device.withLib(Lib(1, Enums.View.artistAlbums, "Ichiko Aoba", PageInfo(0, total, false), emptyList(), 5))
            sent.last().done(ok("LIB_GET", ByteArray(0)))
            assertEquals("total $total", left, stack)
            assertNull(deletes.owed)
        }
    }

    @Test
    fun aDeleteGoneStaleOnTheQueueSendsNothing() {
        val queued = ArrayList<Runnable>()
        val d = LibDeletes({ queued += it }, request, BindAsk(request), { device }, { route }, { it() }, { now })
        BindTokens.put(key, token)
        d.delete(song(), tap())
        snapTo(5)
        queued.forEach { it.run() }
        assertTrue(sent.isEmpty())
        assertTrue(toasts.isEmpty())
        assertFalse(d.waiting)
    }

    @Test
    fun aCardAnswerAfterTheSendToastsTheCardLine() {
        BindTokens.put(key, token)
        deletes.delete(song(), tap())
        sent.last().done(fail(Err.noCard, "card not available"))
        assertEquals(listOf(CardView.REFUSED to ""), toasts)
        assertFalse(deletes.waiting)
    }

    @Test
    fun anAllowWhileTheRouteIsAwaySendsTheDeleteAndKeepsItsOutcome() {
        deletes.delete(song(), tap())
        assertEquals(DeviceState.CONFIRM to "Raspberry Beret", toasts.single())
        route = null
        sent.last().done(ok("BIND_ASK", BindAskReply(token).encode()))
        assertEquals(listOf("BIND_ASK", "LIB_DELETE"), sent.map { it.name })
        sent.last().done(ok("LIB_DELETE", LibDeleteReply(0, 1).encode()))
        assertEquals(1, toasts.size)
        route = { l, t -> toasts += l to t }
        deletes.started()
        assertEquals("song not deleted" to "Raspberry Beret", toasts.last())
        assertFalse(deletes.waiting)
    }

    @Test
    fun aSendThatThrowsClearsTheMarkAndAsksNothing() {
        val throwing = BindAsk.Request { _, type, _, _, _ -> if (type == Msg.LIB_DELETE) throw IllegalStateException("closed") }
        val d = LibDeletes(Executor { it.run() }, throwing, BindAsk(throwing), { device }, { route }, { it() }, { now })
        BindTokens.put(key, token)
        d.delete(song(), tap())
        assertFalse(d.waiting)
        assertTrue(toasts.isEmpty())
        assertTrue(token.contentEquals(BindTokens.get(key)))
    }

    @Test
    fun anAllowWhileAnotherDeleteIsInFlightSendsNoSecond() {
        deletes.delete(song(), tap())
        assertEquals("BIND_ASK", sent.single().name)
        BindTokens.put(key, token)
        deletes.delete(album(), tap())
        assertEquals(listOf("BIND_ASK", "LIB_DELETE"), sent.map { it.name })
        sent[0].done(ok("BIND_ASK", BindAskReply(token).encode()))
        assertEquals(listOf("BIND_ASK", "LIB_DELETE"), sent.map { it.name })
        assertTrue(deletes.waiting)
    }

    @Test
    fun anAllowWhoseTokenIsGoneByTheSendToastsAndSendsNothing() {
        val queued = ArrayList<Runnable>()
        val d = LibDeletes({ queued += it }, request, BindAsk(request), { device }, { route }, { it() }, { now })
        fun drain() = queued.toList().also { queued.clear() }.forEach { it.run() }
        d.delete(song(), tap())
        drain()
        assertEquals("BIND_ASK", sent.single().name)
        sent.single().done(ok("BIND_ASK", BindAskReply(token).encode()))
        BindTokens.drop(key, BindTokens.get(key)!!)
        drain()
        assertEquals(listOf("BIND_ASK"), sent.map { it.name })
        assertEquals("song not deleted" to "Raspberry Beret", toasts.last())
        assertFalse(d.waiting)
    }

    @Test
    fun anArtistProbeFailureOtherThanStaleRevKeepsThePage() {
        for (r in listOf(fail(Err.noLibrary, "no library"), fail(Err.busy, "busy"), Session.Result.Closed, Session.Result.Timeout)) {
            BindTokens.put(key, token)
            device = DeviceState().withHello(hello(), Groups.all)
            deletes.delete(album(), tap())
            sent.last().done(ok("LIB_DELETE", LibDeleteReply(4, 0).encode()))
            snapTo(5)
            val stack = arrayListOf("home", LibDelete.artistPage("Ichiko Aoba"))
            deletes.exits(key, session, LibDeletes.Exits({ stack }, { false }) { stack.removeLastOrNull() != null })
            assertEquals("LIB_GET", sent.last().name)
            sent.last().done(r)
            assertEquals("$r", listOf("home", LibDelete.artistPage("Ichiko Aoba")), stack)
            assertNull(deletes.owed)
        }
    }

    @Test
    fun aCaptureGoneStaleSendsNothing() {
        for (seeded in listOf(true, false)) {
            BindTokens.reset()
            if (seeded) BindTokens.put(key, token)
            device = DeviceState().withHello(hello(), Groups.all)
            val ask = song()
            snapTo(5)
            deletes.delete(ask, tap())
            assertTrue("seeded $seeded", sent.isEmpty())
            assertTrue("seeded $seeded", toasts.isEmpty())
            assertFalse("seeded $seeded", deletes.waiting)
        }
    }
}
