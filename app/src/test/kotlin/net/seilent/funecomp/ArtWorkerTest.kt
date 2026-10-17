package net.seilent.funecomp

import net.seilent.funecomp.art.ArtWorker
import net.seilent.funecomp.artist.ArtPusher
import net.seilent.funecomp.link.Session
import net.seilent.funecomp.link.TcpTransport
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ArtWorkerTest {
    private val sims = ArrayList<Sim>()
    private val sessions = ArrayList<Session>()
    private val workers = ArrayList<ArtWorker>()

    @After
    fun tearDown() {
        workers.forEach { it.stop() }
        sessions.forEach { it.close("done") }
        sims.forEach { it.stop() }
    }

    private fun session(vararg faults: String): Session {
        val sim = Sim(Options(Board.of("ap80"), Faults(faults.toList())))
        sims += sim
        val port = sim.listen(0)
        val ready = CountDownLatch(1)
        val s = Session(TcpTransport("127.0.0.1", port), object : Session.Listener {
            override fun onHello(hello: HelloReply) = ready.countDown()
        })
        sessions += s
        Thread { s.run() }.apply { isDaemon = true }.start()
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        return s
    }

    private class Calls {
        val push = AtomicInteger()
        val fetch = AtomicInteger()
        val shows = AtomicInteger()
        val finals = AtomicInteger()
        val done = AtomicInteger()
    }

    private fun worker(
        c: Calls,
        session: Session? = null,
        gateWaitMs: Long = 20,
        shown: Boolean = false,
        trigger: () -> Any? = { null },
        push: (Session, ArtWorker.Run) -> ArtPusher.Report = { _, _ -> ArtPusher.Report(ArtPusher.Stop.DONE) },
    ) = ArtWorker(
        { session },
        { s, run ->
            c.push.incrementAndGet()
            push(s, run)
        },
        { final ->
            c.shows.incrementAndGet()
            if (final) c.finals.incrementAndGet()
            shown
        },
        { c.fetch.incrementAndGet() },
        gateWaitMs,
        trigger,
        done = { _, _ -> c.done.incrementAndGet() },
    ).also { workers += it }

    private fun waitFor(ms: Long = 5000, cond: () -> Boolean) {
        val end = System.nanoTime() / 1_000_000 + ms
        while (!cond()) {
            if (System.nanoTime() / 1_000_000 > end) throw AssertionError("timed out")
            Thread.sleep(10)
        }
    }

    private fun artThreads() = Thread.getAllStackTraces().keys.count { it.name == "art" && it.isAlive }

    @Test
    fun triggersDuringARunFoldIntoOneMoreRun() {
        val c = Calls()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val w = worker(c, session()) { _, _ ->
            if (c.push.get() == 1) {
                entered.countDown()
                release.await()
            }
            ArtPusher.Report(ArtPusher.Stop.DONE)
        }
        w.want()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        repeat(5) { w.want() }
        release.countDown()
        waitFor { c.done.get() == 2 }
        Thread.sleep(200)
        assertEquals(2, c.push.get())
        assertEquals(2, c.done.get())
        waitFor { artThreads() == 0 }
    }

    @Test
    fun gateStopsAreRetriedThreeTimesThenThePhoneFetches() {
        val c = Calls()
        worker(c, session()) { _, _ -> ArtPusher.Report(ArtPusher.Stop.GATE) }.want()
        waitFor { c.done.get() == 1 }
        assertEquals(4, c.push.get())
        assertEquals(1, c.fetch.get())
        assertEquals(1, c.finals.get())
    }

    @Test
    fun aCachedImageSkipsThePhoneFetchAndNoSessionStillShows() {
        val c = Calls()
        worker(c, null, shown = true).want()
        waitFor { c.done.get() == 1 }
        assertEquals(0, c.push.get())
        assertEquals(0, c.fetch.get())
        assertEquals(2, c.shows.get())
    }

    @Test
    fun stopDuringTheGateSleepEndsWithoutDone() {
        val c = Calls()
        val w = worker(c, session(), gateWaitMs = 30_000) { _, _ -> ArtPusher.Report(ArtPusher.Stop.GATE) }
        w.want()
        waitFor { c.push.get() == 1 }
        Thread.sleep(100)
        w.stop()
        waitFor { artThreads() == 0 }
        w.want()
        Thread.sleep(200)
        assertEquals(0, c.done.get())
        assertEquals(0, c.fetch.get())
        assertEquals(0, artThreads())
    }

    @Test
    fun stopDuringASessionCallEndsWithoutDone() {
        val c = Calls()
        val s = session("no-read")
        val w = worker(c, s) { it, _ -> ArtPusher(it, { n, _, w, h -> net.seilent.funecomp.artist.Art(ByteArray(1000), w, h, n, null, "test", null) }) { true }.run(listOf("Ado" to null)) }
        w.want()
        waitFor { c.push.get() == 1 }
        Thread.sleep(300)
        w.stop()
        waitFor(2000) { artThreads() == 0 }
        assertEquals(0, c.done.get())
        assertEquals(null, s.closedReason)
    }

    @Test
    fun stopDuringALookupEndsWithoutDone() {
        val c = Calls()
        val looking = CountDownLatch(1)
        val w = worker(c, session()) { it, _ ->
            ArtPusher(it, { _, _, _, _ ->
                looking.countDown()
                Thread.sleep(30_000)
                null
            }) { true }.run(listOf("Ado" to null))
        }
        w.want()
        assertTrue(looking.await(5, TimeUnit.SECONDS))
        w.stop()
        waitFor(2000) { artThreads() == 0 }
        assertEquals(0, c.done.get())
        assertEquals(0, c.fetch.get())
    }

    @Test
    fun cancelDuringABlockedHttpCallLetsTheNextRunStart() {
        val c = Calls()
        val blocked = CountDownLatch(1)
        val forever = CountDownLatch(1)
        val http = net.seilent.funecomp.artist.Http { _, _ ->
            blocked.countDown()
            forever.await()
            net.seilent.funecomp.artist.Response(500, ByteArray(0))
        }
        val api = net.seilent.funecomp.artist.Api(http, "ua", net.seilent.funecomp.artist.RateLimiter(0) { 0 }, {})
        val w = worker(c, session()) { _, run ->
            if (c.push.get() == 1) api.get("https://example.org/a")
            ArtPusher.Report(if (run.current) ArtPusher.Stop.DONE else ArtPusher.Stop.CLOSED)
        }
        w.want()
        assertTrue(blocked.await(5, TimeUnit.SECONDS))
        w.cancel()
        val at = System.nanoTime()
        w.want()
        waitFor(100) { c.push.get() == 2 }
        assertTrue((System.nanoTime() - at) / 1_000_000 <= 100)
        waitFor { c.done.get() == 1 }
        forever.countDown()
        Thread.sleep(200)
        assertEquals(1, c.done.get())
        assertEquals(2, c.push.get())
    }

    @Test
    fun aCancelledRunMakesNoMoreHttpCalls() {
        val c = Calls()
        val calls = AtomicInteger()
        val first = CountDownLatch(1)
        val http = net.seilent.funecomp.artist.Http { _, _ ->
            calls.incrementAndGet()
            first.countDown()
            Thread.sleep(300)
            net.seilent.funecomp.artist.Response(404, ByteArray(0))
        }
        val api = net.seilent.funecomp.artist.Api(http, "ua", net.seilent.funecomp.artist.RateLimiter(0) { 0 }, {})
        val w = worker(c, session()) { _, _ ->
            try {
                repeat(10) { api.get("https://example.org/$it") }
            } catch (_: java.io.IOException) {
            }
            ArtPusher.Report(ArtPusher.Stop.DONE)
        }
        w.want()
        assertTrue(first.await(5, TimeUnit.SECONDS))
        w.cancel()
        Thread.sleep(800)
        assertEquals(1, calls.get())
        assertEquals(0, c.done.get())
    }

    @Test
    fun aSameTriggerKeepsTheRunAndANewOnePreemptsIt() {
        val c = Calls()
        val trigger = java.util.concurrent.atomic.AtomicInteger()
        val preempted = AtomicInteger()
        val w = worker(c, session(), trigger = { trigger.get() }) { _, run ->
            if (c.push.get() == 1) {
                val end = System.nanoTime() + 5_000_000_000L
                while (run.live() && System.nanoTime() < end) Thread.sleep(10)
                if (!run.live()) preempted.incrementAndGet()
                ArtPusher.Report(ArtPusher.Stop.PREEMPTED)
            } else {
                ArtPusher.Report(ArtPusher.Stop.DONE)
            }
        }
        w.want()
        waitFor { c.push.get() == 1 }
        Thread.sleep(200)
        assertEquals(0, preempted.get())
        trigger.incrementAndGet()
        w.want()
        waitFor { c.push.get() == 2 }
        waitFor { c.done.get() == 2 }
        assertEquals(1, preempted.get())
    }
}
