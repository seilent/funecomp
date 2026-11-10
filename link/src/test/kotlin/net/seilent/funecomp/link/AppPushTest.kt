package net.seilent.funecomp.link

import net.seilent.funecomp.proto.AppBeginReply
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AppPushTest {
    private val sims = ArrayList<Sim>()
    private val sessions = ArrayList<Session>()
    private val bytes = ByteArray(60_000) { (it * 11 + 5).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
    private val size = bytes.size.toLong()
    private val logs = CopyOnWriteArrayList<String>()

    @After
    fun tearDown() {
        sessions.forEach { it.close("done") }
        sims.forEach { it.stop() }
    }

    private fun sim(vararg faults: String, replyMs: Long = Proto.svcReplyMs.toLong()): Int {
        val s = Sim(Options(Board.of("v1"), Faults(faults.toList()), log = { logs += it }, appReplyMs = replyMs))
        sims += s
        return s.listen(0)
    }

    private fun session(port: Int, timeoutMs: Long = Proto.requestTimeoutMs.toLong()): Session {
        val ready = CountDownLatch(1)
        val s = Session(TcpTransport("127.0.0.1", port), object : Session.Listener {
            override fun onHello(hello: HelloReply) = ready.countDown()
        }, Session.Config("test", requestTimeoutMs = timeoutMs, features = Groups.all))
        sessions += s
        Thread { s.run() }.apply { isDaemon = true }.start()
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        return s
    }

    private val events = CopyOnWriteArrayList<String>()
    private val seen = CopyOnWriteArrayList<Long>()

    private fun push(s: Session, replyMs: Long = Proto.svcReplyMs.toLong()) = AppPush(
        s, "paddle", 3, size, sha,
        { off -> ByteArrayInputStream(bytes, off.toInt(), bytes.size - off.toInt()) },
        progress = { events += "data"; seen += it },
        begun = { r: AppBeginReply -> events += "begin ${r.offset}" },
        replyMs = replyMs,
    ).run()

    @Test
    fun aBeginPastTheCallBoundRetriesAsBusyTimeout() {
        val r = push(session(sim("app-open-ms=2000")), replyMs = 1000)
        assertTrue("$r", r is AppPush.Outcome.Retry && r.error.code == Err.busy && r.error.text == AppPush.TIMEOUT && !r.ended)
        assertTrue(events.none { it.startsWith("begin") })
    }

    @Test
    fun anEndPastTheCallBoundRetriesWithTheEndSentMark() {
        val r = push(session(sim("app-check-ms=5000", replyMs = 5000)), replyMs = 1000)
        assertTrue("$r", r is AppPush.Outcome.Retry && r.error.code == Err.busy && r.error.text == AppPush.TIMEOUT && r.ended)
    }

    @Test
    fun theBeginWaitsForTheDeviceReplyBound() {
        val r = push(session(sim("app-open-ms=2000"), timeoutMs = 1000))
        assertTrue("$r", r is AppPush.Outcome.Asked)
    }

    @Test
    fun noDataGoesBeforeTheBeginReply() {
        assertTrue(push(session(sim("app-open-ms=300"))) is AppPush.Outcome.Asked)
        assertEquals("begin 0", events.first())
        assertTrue(logs.indexOfFirst { "rx APP_DATA" in it } > logs.indexOfFirst { it.startsWith("app begin stage=") })
    }

    @Test
    fun piecesAreOneChunkAtMostAndContiguousFromTheReplyOffset() {
        val port = sim("app-abort-at=20000@busy")
        val first = push(session(port)) as AppPush.Outcome.Aborted
        assertEquals(Enums.AbortCode.busy, first.abort.code)
        seen.clear()
        events.clear()
        assertTrue(push(session(port)) is AppPush.Outcome.Asked)
        assertEquals("begin ${first.abort.offset}", events.first())
        assertEquals(first.abort.offset, seen.first())
        assertEquals(size, seen.last())
        assertTrue(seen.zipWithNext().all { (a, b) -> b - a in 1..Proto.updChunk.toLong() })
    }

    @Test
    fun aReplyAtTheSizeSendsTheEndWithNoData() {
        val port = sim("app-end-lost")
        val lost = push(session(port))
        assertTrue("$lost", lost is AppPush.Outcome.Paused && lost.ended)
        seen.clear()
        assertTrue(push(session(port)) is AppPush.Outcome.Asked)
        assertEquals(listOf(size), seen.toList())
    }

    @Test
    fun theEndWaitsForTheDeviceReplyBoundAndCheckingIsOneEnd() {
        val r = push(session(sim("app-check-ms=5000", replyMs = 2000), timeoutMs = 1000))
        assertTrue("$r", r is AppPush.Outcome.Checking)
        assertTrue(r.ended)
    }

    @Test
    fun anAbortMidStreamIsAbortedWithItsOffsetAndCode() {
        val r = push(session(sim("app-abort-at=20000@writeError"))) as AppPush.Outcome.Aborted
        assertEquals(Enums.AbortCode.writeError, r.abort.code)
        assertEquals(Proto.updChunk.toLong(), r.abort.offset)
    }

    @Test
    fun anAbortOnTheLastPieceThenEndTwoIsAnAbort() {
        val r = push(session(sim("app-abort-at=${size - 1}@noCard", "app-abort-late")))
        assertTrue("$r", r is AppPush.Outcome.Aborted && r.abort.code == Enums.AbortCode.noCard && r.ended)
    }

    @Test
    fun aDeviceWithoutTheBitFailsWithNoFrame() {
        val r = push(session(sim("features=${Groups.all and Groups.mask("appPkg").inv()}"))) as AppPush.Outcome.Failed
        assertNull(r.error)
        assertTrue(events.isEmpty())
    }
}
