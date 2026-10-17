package net.seilent.funecomp

import net.seilent.funecomp.art.ArtWorker
import net.seilent.funecomp.art.Forward
import net.seilent.funecomp.artist.Art
import net.seilent.funecomp.artist.ArtPusher
import net.seilent.funecomp.artist.Sweep
import net.seilent.funecomp.link.Session
import net.seilent.funecomp.link.TcpTransport
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.ui.LIB_PAGE
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ForwardTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val sims = ArrayList<Sim>()
    private val sessions = ArrayList<Session>()
    private val lines: MutableList<String> = Collections.synchronizedList(ArrayList())
    @Volatile private var device = DeviceState()

    @After
    fun tearDown() {
        sessions.forEach { it.close("done") }
        sims.forEach { it.stop() }
    }

    private fun lib(n: Int) = Library((1..n).map { i ->
        Library.track("Artist %03d".format(i), "Album $i", "Song $i", 1, 200_000, mbids = listOf("mb-%03d".format(i)))
    })

    private fun sim(n: Int = 200, minor: Int = 1, cap: Long = Options.IMG_CAP, vararg faults: String): Pair<Sim, Int> {
        val s = Sim(
            Options(Board.of("v1"), Faults(faults.toList()), log = { lines += it }, imgDir = tmp.newFolder(), minor = minor, imgCap = cap),
            lib(n),
        )
        sims += s
        return s to s.listen(0)
    }

    private fun session(port: Int, closed: CountDownLatch = CountDownLatch(1)): Session {
        val ready = CountDownLatch(1)
        device = DeviceState()
        val s = Session(TcpTransport("127.0.0.1", port), object : Session.Listener {
            override fun onHello(hello: HelloReply) {
                synchronized(this@ForwardTest) { device = device.withHello(hello) }
                ready.countDown()
            }

            override fun onPush(type: Int, body: Node) {
                synchronized(this@ForwardTest) { device = device.reduce(type, body, System.nanoTime() / 1_000_000) }
            }

            override fun onClosed(reason: String) = closed.countDown()
        })
        sessions += s
        Thread { s.run() }.apply { isDaemon = true }.start()
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        val end = System.nanoTime() + 5_000_000_000L
        while (device.playback.state.slots == null && System.nanoTime() < end) Thread.sleep(10)
        return s
    }

    private fun jpeg(w: Int, h: Int, size: Int = 2000): ByteArray {
        val head = byteArrayOf(-1, -40, -1, -64, 0, 17, 8, (h shr 8).toByte(), h.toByte(), (w shr 8).toByte(), w.toByte(), 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1)
        return ByteArray(size).also { head.copyInto(it); it[size - 2] = -1; it[size - 1] = -39 }
    }

    private class Lookups {
        val names: MutableList<String> = Collections.synchronizedList(ArrayList())
        var gates = 0
    }

    private class Kept(@Volatile var holds: Boolean = true) : Forward.Marks {
        override fun begin() {}
        override fun mark(): Any = Unit
        override fun holds(mark: Any) = holds
    }

    private fun forward(
        looked: Lookups,
        sweeping: () -> Boolean = { true },
        breaker: Sweep.Breaker = Sweep.Breaker({ 0L }),
        fail: Boolean = false,
        missing: (String) -> Boolean = { false },
        marks: Forward.Marks? = null,
        log: (String) -> Unit = {},
    ) = Forward(
        { device },
        { n, m, w, h ->
            looked.names += n
            if (fail) throw IOException("http 403: x")
            if (missing(n)) null else Art(jpeg(w, h), w, h, n, m, "test", null)
        },
        {
            looked.gates++
            true
        },
        { sweeping() && !breaker.open },
        { s, offset, rev -> s.call(Msg.LIB_GET, Req.libGet(Enums.View.artists, "", offset, LIB_PAGE, rev)) },
        breaker,
        marks = marks,
        log = log,
    )

    private fun stores() = synchronized(lines) { lines.filter { it.startsWith("stored name=") } }.map { it.removePrefix("stored name=").substringBefore(" mbid=") }

    @Test
    fun currentThenQueueThenTheLibraryEachPushedOnce() {
        val (sim, port) = sim()
        val looked = Lookups()
        val r = forward(looked).run(session(port)) { true }
        assertEquals(ArtPusher.Stop.DONE, r.stop)
        assertEquals((1..6).map { "Artist %03d".format(it) }, looked.names.take(6))
        assertEquals(200, sim.device.stored.size)
        assertEquals(200, stores().size)
        assertEquals(200, stores().toSet().size)
        assertTrue(lines.any { it.startsWith("stored name=Artist 002 mbid=mb-002") })
    }

    @Test
    fun placeholderNamesAreNeverLookedUp() {
        val names = listOf("unknown artist", "Artist 002", "null", "Artist 004", "NULL", "Artist 006", "Unknown Artist", "Artist 008")
        val s = Sim(Options(Board.of("v1"), log = { lines += it }, imgDir = tmp.newFolder()), Library(names.mapIndexed { i, n ->
            Library.track(n, "Album $i", "Song $i", 1, 200_000)
        }))
        sims += s
        val looked = Lookups()
        forward(looked).run(session(s.listen(0))) { true }
        assertEquals(listOf("Artist 002", "Artist 004", "Artist 006", "Artist 008"), looked.names.sorted())
        assertEquals(4, s.device.stored.size)
    }

    @Test
    fun aSecondRunPushesNothingNew() {
        val (_, port) = sim(30)
        val s = session(port)
        val f = forward(Lookups())
        f.run(s) { true }
        val again = Lookups()
        val r = forward(again).run(s) { true }
        assertEquals(ArtPusher.Stop.DONE, r.stop)
        assertEquals(30, stores().size)
        assertEquals((1..6).map { "Artist %03d".format(it) }, again.names)
    }

    private fun pages() = synchronized(lines) { lines.count { "rx LIB_GET" in it || "rx IMG_LIST" in it } }

    private fun next(s: Session) {
        val title = device.playback.state.title
        assertTrue(s.call(Msg.TRANSPORT, Req.transport(Enums.TransportCmd.next)) is Session.Result.Ok)
        val end = System.nanoTime() + 5_000_000_000L
        while (device.playback.state.title == title && System.nanoTime() < end) Thread.sleep(10)
    }

    @Test
    fun trackChangesAfterAFinishedSweepDoNoSweepWork() {
        val (_, port) = sim(30)
        val s = session(port)
        val logged: MutableList<String> = Collections.synchronizedList(ArrayList())
        val looked = Lookups()
        val f = forward(looked, missing = { it > "Artist 020" }, marks = Kept(), log = { logged += it })
        assertEquals(ArtPusher.Stop.DONE, f.run(s) { true }.stop)
        assertEquals(20, stores().size)
        repeat(3) {
            next(s)
            val names = looked.names.size
            val gates = looked.gates
            val pages = pages()
            assertEquals(ArtPusher.Stop.DONE, f.run(s) { true }.stop)
            assertEquals(6, looked.names.size - names)
            assertEquals(6, looked.gates - gates)
            assertEquals(pages, pages())
        }
        assertEquals(3, logged.count { it == "art sweep kept" })
        assertEquals(20, stores().size)
    }

    @Test
    fun aMarkThatNoLongerHoldsSweepsAgain() {
        val (_, port) = sim(30)
        val s = session(port)
        val kept = Kept()
        val logged: MutableList<String> = Collections.synchronizedList(ArrayList())
        val f = forward(Lookups(), marks = kept, log = { logged += it })
        f.run(s) { true }
        next(s)
        kept.holds = false
        f.run(s) { true }
        assertEquals(2, logged.count { it.startsWith("art sweep DONE") })
        assertTrue(logged.none { it == "art sweep kept" })
    }

    @Test
    fun queueWrapsWithRepeatAll() {
        val (_, port) = sim()
        val s = session(port)
        assertTrue(s.call(Msg.PLAY, Req.play(Enums.View.songs, "", device.lib.rev, 197, false)) is Session.Result.Ok)
        assertTrue(s.call(Msg.TRANSPORT, Req.transport(Enums.TransportCmd.repeat, repeat = Enums.Repeat.all)) is Session.Result.Ok)
        Thread.sleep(200)
        val looked = Lookups()
        forward(looked, sweeping = { false }).run(s) { true }
        assertEquals(listOf(198, 199, 200, 1, 2, 3).map { "Artist %03d".format(it) }, looked.names)
    }

    @Test
    fun aProtocol10DeviceGetsTheCurrentArtistsOnly() {
        val (sim, port) = sim(minor = 0)
        val looked = Lookups()
        val r = forward(looked).run(session(port)) { true }
        assertEquals(ArtPusher.Stop.DONE, r.stop)
        assertEquals(listOf("Artist 001"), looked.names)
        assertEquals(setOf("artist 001"), sim.device.stored.keys)
    }

    @Test
    fun theSweepStopsBeforeNinetyPercentOfTheCap() {
        val (sim, port) = sim(cap = 50L * 2000)
        forward(Lookups()).run(session(port)) { true }
        assertEquals(45, sim.device.stored.size)
        assertTrue(lines.none { it.startsWith("evicted") })
    }

    @Test
    fun anEvictedArtistIsPushedAgainAsCurrent() {
        val (sim, port) = sim(10)
        val s = session(port)
        forward(Lookups(), sweeping = { false }).run(s) { true }
        assertTrue(s.call(Msg.IMG_DEL, Req.imgDel("Artist 001")) is Session.Result.Ok)
        forward(Lookups(), sweeping = { false }).run(s) { true }
        assertEquals(2, stores().count { it == "Artist 001" })
        assertTrue(sim.device.stored.containsKey("artist 001"))
    }

    @Test
    fun theSweepWaitsWhileTheAppIsShown() {
        val (sim, port) = sim()
        val looked = Lookups()
        forward(looked, sweeping = { false }).run(session(port)) { true }
        assertEquals(6, looked.names.size)
        assertEquals(6, sim.device.stored.size)
    }

    @Test
    fun aBurstOfForbiddenRepliesTripsTheBreaker() {
        val (sim, port) = sim()
        val looked = Lookups()
        val breaker = Sweep.Breaker({ 0L })
        forward(looked, breaker = breaker, fail = true).run(session(port)) { true }
        assertTrue(breaker.open)
        assertEquals(Sweep.TRIP, looked.names.size)
        assertTrue(sim.device.stored.isEmpty())
    }

    @Test
    fun aDropMidSweepStopsTheRunAndResumingPushesEachArtistOnce() {
        val (sim, port) = sim(120, faults = arrayOf("drop-after-bytes=60000"))
        val looked = Lookups()
        var sessionsUsed = 0
        while (sim.device.stored.size < 120 && sessionsUsed++ < 20) {
            val closed = CountDownLatch(1)
            val s = session(port, closed)
            val worker = ArtWorker({ s }, { ss, run -> forward(looked).run(ss, run::live) }, { true }, {}) { _, _ -> }
            worker.want()
            val end = System.nanoTime() + 30_000_000_000L
            while (closed.count > 0 && sim.device.stored.size < 120 && System.nanoTime() < end) Thread.sleep(20)
            worker.cancel()
            val at = looked.names.size
            Thread.sleep(300)
            assertTrue("lookups after the drop ${looked.names.size - at}", looked.names.size - at <= 1)
            worker.stop()
        }
        assertEquals(120, sim.device.stored.size)
        assertTrue(sessionsUsed > 1)
        assertEquals(stores().size, stores().toSet().size)
    }
}
