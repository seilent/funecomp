package net.seilent.funecomp

import net.seilent.funecomp.art.ArtWorker
import net.seilent.funecomp.art.Forward
import net.seilent.funecomp.artist.Art
import net.seilent.funecomp.artist.ArtBlocks
import net.seilent.funecomp.artist.ArtPusher
import net.seilent.funecomp.artist.Names
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
        blocks: Forward.Blocks? = null,
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
        { s, offset, rev ->
            val snap = device.lib.keyed(Enums.View.artists)
            s.call(Msg.LIB_GET, Req.libGet(Enums.View.artists, "", offset, LIB_PAGE, rev.takeUnless { snap }, rev.takeIf { snap }))
        },
        breaker,
        marks = marks,
        blocks = blocks,
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

    private fun favorite(s: Session) {
        val rev = device.lib.rev
        assertTrue(s.call(Msg.ACTION, Req.action(Enums.ActionCmd.favorite, device.playback.state.track)) is Session.Result.Ok)
        val end = System.nanoTime() + 5_000_000_000L
        while (device.lib.rev == rev && System.nanoTime() < end) Thread.sleep(10)
    }

    @Test
    fun aHeartTapKeepsTheSweepAndAMinor2PeerRelists() {
        for (minor in listOf(3, 2)) {
            val (_, port) = sim(30, minor)
            val s = session(port)
            val logged: MutableList<String> = Collections.synchronizedList(ArrayList())
            val f = forward(Lookups(), marks = Kept(), log = { logged += it })
            assertEquals(ArtPusher.Stop.DONE, f.run(s) { true }.stop)
            favorite(s)
            val pages = pages()
            assertEquals(ArtPusher.Stop.DONE, f.run(s) { true }.stop)
            if (minor == 3) {
                assertEquals(pages, pages())
                assertEquals(1, logged.count { it == "art sweep kept" })
            } else {
                assertTrue(pages() > pages)
            }
            lines.clear()
        }
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

    private fun blocks(records: ArtBlocks, device: String) = Forward.Blocks(records, { device }, { n, m -> m ?: "name:$n" })

    private fun deleted() = synchronized(lines) { lines.filter { it.startsWith("deleted name=") }.map { it.removePrefix("deleted name=") } }

    private fun lists() = synchronized(lines) { lines.count { "rx IMG_LIST" in it } }

    @Test
    fun aBlockDeletesTheNameOnceAndTheNextRunLooksItUpAgain() {
        val (sim, port) = sim(30)
        val s = session(port)
        val records = ArtBlocks()
        forward(Lookups()).run(s) { true }
        records.add(ArtBlocks.Block("mb-002", "mb-002", "test", "x", "", "Artist 002", 1))
        val looked = Lookups()
        val f = forward(looked, missing = { it == "Artist 002" }, blocks = blocks(records, "tcp:A"))
        val r = f.run(s) { true }
        assertEquals(ArtPusher.Stop.DONE, r.stop)
        assertEquals(listOf("Artist 002"), deleted())
        assertTrue("Artist 002" in looked.names)
        assertTrue("artist 002" !in sim.device.stored.keys)
        assertTrue(records.pending("tcp:A").isEmpty())
        val n = lists()
        f.run(s) { true }
        assertEquals(1, deleted().size)
        assertEquals(n, lists())
    }

    @Test
    fun deletesRunWithTheSweepOffAndEveryDeviceGetsItsOwn() {
        val (simA, portA) = sim(10)
        val a = session(portA)
        forward(Lookups()).run(a) { true }
        val records = ArtBlocks()
        records.add(ArtBlocks.Block("mb-003", "mb-003", "test", "x", "", "Artist 003", 1))
        forward(Lookups(), sweeping = { false }, missing = { it == "Artist 003" }, blocks = blocks(records, "tcp:A")).run(a) { true }
        assertEquals(listOf("Artist 003"), deleted())
        assertTrue("artist 003" !in simA.device.stored.keys)
        val (simB, portB) = sim(10)
        val b = session(portB)
        forward(Lookups()).run(b) { true }
        assertTrue("artist 003" in simB.device.stored.keys)
        forward(Lookups(), missing = { it == "Artist 003" }, blocks = blocks(records, "tcp:B")).run(b) { true }
        assertEquals(listOf("Artist 003", "Artist 003"), deleted())
        assertTrue("artist 003" !in simB.device.stored.keys)
        forward(Lookups(), missing = { it == "Artist 003" }, blocks = blocks(records, "tcp:A")).run(session(portA)) { true }
        assertEquals(2, deleted().size)
    }

    @Test
    fun oneRecordDeletesEveryNameOnItsMbid() {
        val s0 = Sim(Options(Board.of("v1"), log = { lines += it }, imgDir = tmp.newFolder()), Library(listOf(
            Library.track("Muto", "A", "One", 1, 200_000, mbids = listOf("mb-m")),
            Library.track("むと", "B", "Two", 1, 200_000, mbids = listOf("mb-m")),
            Library.track("Other", "C", "Three", 1, 200_000, mbids = listOf("mb-o")),
        )))
        sims += s0
        val s = session(s0.listen(0))
        forward(Lookups()).run(s) { true }
        assertEquals(3, s0.device.stored.size)
        val records = ArtBlocks()
        records.add(ArtBlocks.Block("name:x", "mb-m", "test", "x", "", "むと", 1))
        forward(Lookups(), missing = { it != "Other" }, blocks = blocks(records, "tcp:A")).run(s) { true }
        assertEquals(setOf("Muto", "むと"), deleted().toSet())
        assertEquals(setOf("other"), s0.device.stored.keys)
        assertTrue(records.pending("tcp:A").isEmpty())
    }

    @Test
    fun anUndoPushesTheNameEvenWithAHeldMarkAndAMissIsDone() {
        val (sim, port) = sim(30)
        val s = session(port)
        val records = ArtBlocks()
        val kept = Kept()
        forward(Lookups(), marks = kept).run(s) { true }
        val block = ArtBlocks.Block("mb-020", "mb-020", "test", "x", "", "Artist 020", 1)
        records.add(block)
        forward(Lookups(), missing = { it == "Artist 020" }, marks = kept, blocks = blocks(records, "tcp:A")).run(s) { true }
        assertTrue("artist 020" !in sim.device.stored.keys)
        records.remove(block)
        val before = stores().count { it == "Artist 020" }
        forward(Lookups(), marks = kept, blocks = blocks(records, "tcp:A")).run(s) { true }
        assertEquals(before + 1, stores().count { it == "Artist 020" })
        assertTrue(records.pushes("tcp:A").isEmpty())
        records.add(ArtBlocks.Block("mb-021", "mb-021", "test", "x", "", "Artist 021", 2))
        records.remove(records.records().single())
        forward(Lookups(), missing = { it == "Artist 021" }, marks = kept, blocks = blocks(records, "tcp:A")).run(s) { true }
        assertTrue(records.pushes("tcp:A").isEmpty())
    }

    @Test
    fun aBusyDeleteRetriesOnTheSessionListing() {
        val (sim, port) = sim(30, 1, Options.IMG_CAP, "busy=IMG_DEL:2")
        val s = session(port)
        forward(Lookups()).run(s) { true }
        val records = ArtBlocks()
        records.add(ArtBlocks.Block("mb-004", "mb-004", "test", "x", "", "Artist 004", 1))
        val f = forward(Lookups(), missing = { it == "Artist 004" }, blocks = blocks(records, "tcp:A"))
        val n = lists()
        repeat(2) {
            f.run(s) { true }
            assertEquals(1, records.pending("tcp:A").size)
        }
        f.run(s) { true }
        assertTrue(records.pending("tcp:A").isEmpty())
        assertTrue("artist 004" !in sim.device.stored.keys)
        assertEquals(n + 1, lists())
    }

    @Test
    fun aFailedListingFallsThroughToThePushes() {
        val (_, port) = sim(30, 1, Options.IMG_CAP, "busy=IMG_LIST:1")
        val s = session(port)
        forward(Lookups(), sweeping = { false }).run(s) { true }
        val current = device.playback.state.artists.first()
        val records = ArtBlocks()
        records.add(ArtBlocks.Block("x", "x", "test", "x", "", "Nobody", 1))
        val logs = ArrayList<String>()
        val looked = Lookups()
        val r = forward(looked, sweeping = { false }, blocks = blocks(records, "tcp:A"), log = { logs += it }).run(s) { true }
        assertEquals(ArtPusher.Stop.DONE, r.stop)
        assertTrue("art del list failed" in logs)
        assertTrue(current in looked.names)
        assertEquals(1, records.pending("tcp:A").size)
        forward(Lookups(), sweeping = { false }, blocks = blocks(records, "tcp:A")).run(s) { true }
        assertTrue(records.pending("tcp:A").isEmpty())
    }

    @Test
    fun aRejectDeletesOnlyItsNameOnTheMbid() {
        val s0 = Sim(Options(Board.of("v1"), log = { lines += it }, imgDir = tmp.newFolder()), Library(listOf(
            Library.track("Muto", "A", "One", 1, 200_000, mbids = listOf("mb-m")),
            Library.track("むと", "B", "Two", 1, 200_000, mbids = listOf("mb-m")),
        )))
        sims += s0
        val s = session(s0.listen(0))
        forward(Lookups()).run(s) { true }
        val records = ArtBlocks()
        records.add(ArtBlocks.Reject("mb-m", "mb-m", "むと", "むと", 1))
        forward(Lookups(), missing = { true }, blocks = blocks(records, "tcp:A")).run(s) { true }
        assertEquals(listOf("むと"), deleted())
        assertEquals(setOf("muto"), s0.device.stored.keys)
    }

    @Test
    fun aNameRecordDeletesOnlyTheUntaggedEntryOfThatName() {
        val dj = "91352441-9f79-48c2-81ad-ccdd1e46915a"
        val s0 = Sim(Options(Board.of("v1"), log = { lines += it }, imgDir = tmp.newFolder()), Library(listOf(
            Library.track("TSUKI", "A", "One", 1, 200_000),
            Library.track("ＴＳＵＫＩ", "B", "Two", 1, 200_000, mbids = listOf(dj)),
        )))
        sims += s0
        val s = session(s0.listen(0))
        forward(Lookups()).run(s) { true }
        assertEquals(setOf("tsuki", "ｔｓｕｋｉ"), s0.device.stored.keys)
        val records = ArtBlocks()
        val keyOf = { n: String, m: String? -> Names.mbid(m) ?: if (Names.fold(n) == "tsuki") dj else "name:${Names.fold(n)}" }
        records.add(ArtBlocks.Block("name:tsuki", null, "youtube", "x", "", "TSUKI", 1))
        records.addPush("TSUKI", null)
        val b = Forward.Blocks(records, { "tcp:A" }, keyOf)
        forward(Lookups(), missing = { it == "TSUKI" }, blocks = b).run(s) { true }
        assertEquals(listOf("TSUKI"), deleted())
        assertEquals(setOf("ｔｓｕｋｉ"), s0.device.stored.keys)
        assertTrue(records.pending("tcp:A").isEmpty())
        assertTrue(records.pushes("tcp:A").isEmpty())
        val before = stores().count { it == "TSUKI" }
        records.addPush("TSUKI", null)
        forward(Lookups(), blocks = b).run(s) { true }
        assertEquals(before + 1, stores().count { it == "TSUKI" })
        assertEquals(1, deleted().size)
    }

    @Test
    fun aLibraryPickBlockedUnderBothKeysDeletesTheTaggedAndTheUntaggedEntry() {
        val dj = "91352441-9f79-48c2-81ad-ccdd1e46915a"
        val s0 = Sim(Options(Board.of("v1"), log = { lines += it }, imgDir = tmp.newFolder()), Library(listOf(
            Library.track("TSUKI", "A", "One", 1, 200_000, mbids = listOf(dj)),
            Library.track("ＴＳＵＫＩ", "B", "Two", 1, 200_000),
        )))
        sims += s0
        val s = session(s0.listen(0))
        forward(Lookups()).run(s) { true }
        assertEquals(setOf("tsuki", "ｔｓｕｋｉ"), s0.device.stored.keys)
        val keyOf = { n: String, m: String? -> Names.mbid(m) ?: "name:${Names.fold(n)}" }
        val only = ArtBlocks()
        only.add(ArtBlocks.Block(dj, dj, "youtube", "x", "", "TSUKI", 1))
        forward(Lookups(), missing = { true }, blocks = Forward.Blocks(only, { "tcp:A" }, keyOf)).run(s) { true }
        assertEquals(listOf("TSUKI"), deleted())
        forward(Lookups()).run(s) { true }
        val both = ArtBlocks()
        listOf(dj, "name:tsuki").forEach { both.add(ArtBlocks.Block(it, dj, "youtube", "x", "", "TSUKI", 2)) }
        forward(Lookups(), missing = { true }, blocks = Forward.Blocks(both, { "tcp:B" }, keyOf)).run(s) { true }
        assertEquals(setOf("TSUKI", "ＴＳＵＫＩ"), deleted().drop(1).toSet())
        assertTrue(both.pending("tcp:B").isEmpty())
    }
}
