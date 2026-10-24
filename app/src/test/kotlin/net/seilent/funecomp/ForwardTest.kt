package net.seilent.funecomp

import net.seilent.funecomp.art.ArtWorker
import net.seilent.funecomp.art.Forward
import net.seilent.funecomp.artist.Art
import net.seilent.funecomp.artist.ArtBlocks
import net.seilent.funecomp.artist.ArtPusher
import net.seilent.funecomp.artist.Names
import net.seilent.funecomp.artist.Sweep
import net.seilent.funecomp.link.ImagePush
import net.seilent.funecomp.link.Session
import net.seilent.funecomp.link.TcpTransport
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.ui.LIB_PAGE
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private val lines: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
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
        val names: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
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
        retagging: () -> Boolean = { true },
        breaker: Sweep.Breaker = Sweep.Breaker({ 0L }),
        fail: Boolean = false,
        missing: (String) -> Boolean = { false },
        marks: Forward.Marks? = null,
        blocks: Forward.Blocks? = null,
        log: (String) -> Unit = {},
        onLibGet: (Session) -> Unit = {},
        known: (String, String?) -> Boolean = { _, _ -> true },
        gate: () -> Boolean = { true },
        upgrades: Forward.Upgrades? = null,
        version: (String) -> Int = { 0 },
    ) = Forward(
        { device },
        { n, m, w, h ->
            looked.names += n
            if (fail) throw IOException("http 403: x")
            if (missing(n)) null else Art(jpeg(w, h, 2000 + version(n)), w, h, n, m, "test", null)
        },
        {
            looked.gates++
            gate()
        },
        { sweeping() && !breaker.open },
        retagging,
        { s, offset, rev ->
            onLibGet(s)
            val snap = device.lib.keyed(Enums.View.artists)
            s.call(Msg.LIB_GET, Req.libGet(Enums.View.artists, "", offset, LIB_PAGE, rev.takeUnless { snap }, rev.takeIf { snap }))
        },
        breaker,
        marks = marks,
        blocks = blocks,
        cached = known,
        upgrades = upgrades,
        log = log,
    )

    private class Ups(@Volatile var open: Boolean, val due: List<String>, val replace: Set<String> = emptySet()) : Forward.Upgrades {
        val ran: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
        val done = Collections.synchronizedSet(HashSet<String>())
        override fun due() = due.filter { it !in done }.map { it to null }
        val ats = java.util.concurrent.ConcurrentHashMap<String, Long>()
        override fun run(name: String, mbid: String?, w: Int, h: Int): Forward.Upgraded? {
            if (!done.add(name)) return null
            ran += name
            if (name in replace) ats[name] = 1
            return Forward.Upgraded(name in replace, 1000, name, 1)
        }
        override fun open() = open
        override fun at(key: String) = ats[key]
    }

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
    fun theCurrentAndQueueArtistsUpgradeOnAnyNetworkAndTheRestOnlyWhenOpen() {
        val (_, port) = sim(30)
        val s = session(port)
        val logged: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
        val ups = Ups(false, (1..30).map { "Artist %03d".format(it) })
        assertEquals(ArtPusher.Stop.DONE, forward(Lookups(), upgrades = ups, log = { logged += it }).run(s) { true }.stop)
        assertEquals((1..6).map { "Artist %03d".format(it) }, ups.ran.sorted())
        assertTrue(logged.contains("art upgrade 6 of 6 6000"))
        ups.open = true
        forward(Lookups(), upgrades = ups).run(s) { true }
        assertEquals((1..30).map { "Artist %03d".format(it) }, ups.ran.sorted())
    }

    @Test
    fun aReplacedPhotoIsPushedAgain() {
        val (sim, port) = sim(30)
        val s = session(port)
        val versions = HashMap<String, Int>()
        val ups = Ups(true, listOf("Artist 001", "Artist 020"), setOf("Artist 001", "Artist 020"))
        val f = forward(Lookups(), upgrades = object : Forward.Upgrades by ups {
            override fun run(name: String, mbid: String?, w: Int, h: Int) = ups.run(name, mbid, w, h)?.also { if (it.replaced) synchronized(versions) { versions[name] = 1 } }
        }, version = { synchronized(versions) { versions[it] ?: 0 } })
        f.run(s) { true }
        assertEquals((1..6).map { "Artist %03d".format(it) } + "Artist 020", ups.ran.sorted())
        assertEquals(2, stores().count { it == "Artist 001" })
        assertEquals(1, stores().count { it == "Artist 002" })
        assertEquals(1, stores().count { it == "Artist 020" })
        assertEquals(1, stores().count { it == "Artist 019" })
        assertEquals(30, sim.device.stored.size)
    }

    private class Versioned(private val ups: Ups) : Forward.Upgrades by ups {
        val versions = java.util.concurrent.ConcurrentHashMap<String, Int>()
        override fun run(name: String, mbid: String?, w: Int, h: Int) = ups.run(name, mbid, w, h)?.also { if (it.replaced) versions[name] = 1 }
    }

    @Test
    fun anInterruptedRestUpgradeStillReachesTheDeviceOnTheNextRun() {
        val (_, port) = sim(30)
        val s = session(port)
        val ups = Ups(false, listOf("Artist 020", "Artist 021"), setOf("Artist 020", "Artist 021"))
        val v = Versioned(ups)
        val f = forward(Lookups(), upgrades = v, version = { v.versions[it] ?: 0 }, blocks = blocks(ArtBlocks(), "tcp:A"))
        f.run(s) { true }
        assertEquals(1, stores().count { it == "Artist 020" })
        ups.open = true
        f.run(s) { "Artist 020" !in ups.ran }
        assertFalse("Artist 021" in ups.ran)
        f.run(s) { true }
        assertEquals(2, stores().count { it == "Artist 020" })
        assertEquals(2, stores().count { it == "Artist 021" })
    }

    @Test
    fun aReplacedPhotoIsQueuedForEveryOtherDevice() {
        val (_, port) = sim(30)
        val s = session(port)
        val records = ArtBlocks()
        records.upgrades("tcp:B")
        val ups = Ups(false, listOf("Artist 020"), setOf("Artist 020"))
        val v = Versioned(ups)
        val f = forward(Lookups(), upgrades = v, version = { v.versions[it] ?: 0 }, blocks = blocks(records, "tcp:A"))
        f.run(s) { true }
        ups.open = true
        f.run(s) { true }
        assertEquals(2, stores().count { it == "Artist 020" })
        assertEquals(listOf("Artist 020"), records.upgrades("tcp:B").map { it.name })
        assertTrue(records.upgrades("tcp:A").isEmpty())
        assertTrue(records.pushes("tcp:B").isEmpty())
    }

    private fun begins() = synchronized(lines) { lines.filter { it.startsWith("begin name=") } }.map { it.removePrefix("begin name=").substringBefore(" origin=") }

    @Test
    fun anotherDeviceGetsAPendingUpgradeOnlyAfterTheCurrentArtistAndOnlyWhenItHoldsTheName() {
        val records = ArtBlocks()
        var dev = "tcp:A"
        val ups = Ups(false, emptyList())
        val bd = Forward.Blocks(records, { dev }, { n, m -> m ?: "name:$n" })
        val (_, portB) = sim(30)
        val b = session(portB)
        dev = "tcp:B"
        forward(Lookups(), upgrades = ups, blocks = bd).run(b) { true }
        records.upgraded("Artist 020", "Artist 020", null, 1, "tcp:A")
        records.upgraded("Artist 025", "Artist 025", null, 1, "tcp:A")
        records.upgraded("Artist 026", "Artist 026", null, 1, "tcp:A")
        ups.ats["Artist 020"] = 1
        ups.ats["Artist 025"] = 2
        ups.open = true
        synchronized(lines) { lines.clear() }
        forward(Lookups(), upgrades = ups, blocks = bd, version = { if (it == "Artist 020") 1 else 0 }).run(b) { true }
        val order = begins()
        assertEquals("Artist 001", order.first())
        assertTrue(order.indexOf("Artist 020") > order.indexOf("Artist 006"))
        assertEquals(listOf("Artist 020"), stores().filter { it == "Artist 020" })
        assertTrue(records.upgrades("tcp:B").isEmpty())
        assertTrue(records.upgrades("tcp:C").none { it.name == "Artist 025" || it.name == "Artist 026" })

        val (_, portC) = sim(10)
        val c = session(portC)
        dev = "tcp:C"
        synchronized(lines) { lines.clear() }
        forward(Lookups(), sweeping = { false }, upgrades = ups, blocks = bd).run(c) { true }
        assertFalse("Artist 020" in begins())
        assertTrue(records.upgrades("tcp:C").isEmpty())
    }

    @Test
    fun aPendingUpgradeKeepsThePhoneOrigin() {
        val records = ArtBlocks()
        val ups = Ups(false, emptyList())
        val (_, port) = sim(30, minor = Proto.minor)
        val s = session(port)
        val bd = Forward.Blocks(records, { "tcp:B" }, { n, m -> m ?: "name:$n" })
        forward(Lookups(), upgrades = ups, blocks = bd).run(s) { true }
        records.upgraded("Artist 020", "Artist 020", null, 1, "tcp:A", Enums.ImgOrigin.phone)
        ups.ats["Artist 020"] = 1
        ups.open = true
        synchronized(lines) { lines.clear() }
        forward(Lookups(), upgrades = ups, blocks = bd, version = { if (it == "Artist 020") 1 else 0 }).run(s) { true }
        assertTrue(synchronized(lines) { lines.toList() }.any { it == "begin name=Artist 020 origin=phone" })
    }

    @Test
    fun pendingUpgradesStopWhenTheUnmeteredGateCloses() {
        val records = ArtBlocks()
        val ups = Ups(false, emptyList())
        val (_, port) = sim(30)
        val s = session(port)
        val bd = Forward.Blocks(records, { "tcp:B" }, { n, m -> m ?: "name:$n" })
        forward(Lookups(), upgrades = ups, blocks = bd).run(s) { true }
        records.upgraded("Artist 020", "Artist 020", null, 1, "tcp:A")
        records.upgraded("Artist 021", "Artist 021", null, 1, "tcp:A")
        ups.ats["Artist 020"] = 1
        ups.ats["Artist 021"] = 1
        val gated = object : Forward.Upgrades by ups {
            override fun open() = stores().count { it == "Artist 020" } < 2
        }
        forward(Lookups(), upgrades = gated, blocks = bd, version = { if (it == "Artist 020" || it == "Artist 021") 1 else 0 }).run(s) { true }
        assertEquals(2, stores().count { it == "Artist 020" })
        assertEquals(1, stores().count { it == "Artist 021" })
        assertEquals(listOf("Artist 021"), records.upgrades("tcp:B").map { it.name })
    }

    @Test
    fun theRestUpgradesRunWhenTheSweepIsKept() {
        val (_, port) = sim(30)
        val s = session(port)
        val logged: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
        val ups = Ups(false, listOf("Artist 025"))
        val f = forward(Lookups(), marks = Kept(), upgrades = ups, log = { logged += it })
        f.run(s) { true }
        f.run(s) { true }
        assertTrue(logged.contains("art sweep kept"))
        assertFalse("Artist 025" in ups.ran)
        logged.clear()
        ups.open = true
        f.run(s) { true }
        assertTrue(logged.contains("art sweep kept"))
        assertTrue("Artist 025" in ups.ran)
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
        val logged: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
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
        for (minor in listOf(Proto.minor, 3, 2)) {
            val (_, port) = sim(30, minor)
            val s = session(port)
            val logged: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
            val f = forward(Lookups(), marks = Kept(), log = { logged += it })
            assertEquals(ArtPusher.Stop.DONE, f.run(s) { true }.stop)
            favorite(s)
            val pages = pages()
            assertEquals(ArtPusher.Stop.DONE, f.run(s) { true }.stop)
            if (minor >= 3) {
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
        val logged: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
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
        assertEquals(setOf("tsuki", "ＴＳＵＫＩ"), s0.device.stored.keys)
        val records = ArtBlocks()
        val keyOf = { n: String, m: String? -> Names.mbid(m) ?: if (Names.fold(n) == "tsuki") dj else "name:${Names.fold(n)}" }
        records.add(ArtBlocks.Block("name:tsuki", null, "youtube", "x", "", "TSUKI", 1))
        records.addPush("TSUKI", null)
        val b = Forward.Blocks(records, { "tcp:A" }, keyOf)
        forward(Lookups(), missing = { it == "TSUKI" }, blocks = b).run(s) { true }
        assertEquals(listOf("TSUKI"), deleted())
        assertEquals(setOf("ＴＳＵＫＩ"), s0.device.stored.keys)
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
        assertEquals(setOf("tsuki", "ＴＳＵＫＩ"), s0.device.stored.keys)
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

    private fun origins(sim: Sim): Map<String, Int?> = sim.device.begins.toList().toMap()

    private fun firstOrigins(sim: Sim): Map<String, Int?> = sim.device.begins.toList().distinctBy { it.first }.toMap()

    private fun source(sim: Sim, v: Int) {
        sim.source(v)
        val end = System.nanoTime() + 5_000_000_000L
        while (device.playback.state.source != v && System.nanoTime() < end) Thread.sleep(10)
        assertEquals(v, device.playback.state.source)
    }

    private val library = Enums.ImgOrigin.library
    private val phone = Enums.ImgOrigin.phone

    @Test
    fun theCurrentStageTakesTheSourceAndTheQueueAndSweepTakeLibrary() {
        for (src in listOf(Enums.Source.phone, Enums.Source.local)) {
            val (sim, port) = sim(30, Proto.minor)
            val s = session(port)
            source(sim, src)
            assertEquals(ArtPusher.Stop.DONE, forward(Lookups()).run(s) { true }.stop)
            val o = firstOrigins(sim)
            assertEquals(30, o.size)
            assertEquals(if (src == Enums.Source.phone) phone else library, o.getValue("Artist 001"))
            assertTrue((2..30).all { o.getValue("Artist %03d".format(it)) == library })
        }
    }

    @Test
    fun anUnknownSourceSendsNoOriginAndAMinor8DeviceGetsNone() {
        val (sim, port) = sim(10, Proto.minor, Options.IMG_CAP, "enum=STATE.source=7")
        forward(Lookups()).run(session(port)) { true }
        val o = firstOrigins(sim)
        assertEquals(null, o.getValue("Artist 001"))
        assertTrue((2..10).all { o.getValue("Artist %03d".format(it)) == library })
        lines.clear()
        val (old, oldPort) = sim(30, 8)
        val r = forward(Lookups()).run(session(oldPort)) { true }
        assertEquals(ArtPusher.Stop.DONE, r.stop)
        assertEquals(30, old.device.stored.size)
        assertTrue(old.device.begins.all { it.second == null })
        assertTrue(lines.none { "rx IMG_TAG" in it })
    }

    @Test
    fun aRecordKeepsItsOriginAndAnOriginlessOneTakesTheRows() {
        val s0 = Sim(Options(Board.of("v1"), log = { lines += it }, imgDir = tmp.newFolder()), Library((1..12).map { i ->
            Library.track(if (i == 12) "BoA" else "Artist %03d".format(i), "Album $i", "Song $i", 1, 200_000)
        }))
        sims += s0
        val s = session(s0.listen(0))
        forward(Lookups()).run(s) { true }
        val records = ArtBlocks()
        records.addPush("Artist 010", null, phone)
        records.addPush("Artist 011", null)
        records.addPush("Nobody", null)
        records.addPush("ＢｏＡ", null)
        s0.device.begins.clear()
        forward(Lookups(), sweeping = { false }, blocks = blocks(records, "tcp:A")).run(s) { true }
        val o = firstOrigins(s0)
        assertEquals(phone, o.getValue("Artist 010"))
        assertEquals(library, o.getValue("Artist 011"))
        assertEquals(phone, o.getValue("Nobody"))
        assertEquals(phone, o.getValue("ＢｏＡ"))
        assertTrue(records.pushes("tcp:A").isEmpty())
        records.addPush("BOA", null)
        forward(Lookups(), sweeping = { false }, blocks = blocks(records, "tcp:A")).run(s) { true }
        assertEquals(library, firstOrigins(s0).getValue("BOA"))
    }

    @Test
    fun unreadableRowsLeaveAnOriginlessRecordPending() {
        val (sim, port) = sim(10, Proto.minor, Options.IMG_CAP, "busy=LIB_GET:1000")
        val s = session(port)
        val records = ArtBlocks()
        records.addPush("Nobody", null)
        records.addPush("Somebody", null, phone)
        val logs: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
        val r = forward(Lookups(), sweeping = { false }, blocks = blocks(records, "tcp:A"), log = { logs += it }).run(s) { true }
        assertEquals(ArtPusher.Stop.DONE, r.stop)
        assertTrue("art unblock rows failed" in logs)
        assertEquals(listOf("Nobody"), records.pushes("tcp:A").map { it.name })
        val o = firstOrigins(sim)
        assertFalse("Nobody" in o)
        assertEquals(phone, o.getValue("Somebody"))
        assertEquals(library, o.getValue("Artist 001"))
    }

    private fun tagLib(vararg names: String) = Library(
        (1..6).map { Library.track("Artist %03d".format(it), "Album $it", "Song $it", 1, 200_000) } +
            names.mapIndexed { i, n -> Library.track(n, "Other $i", "Tune $i", 1, 200_000) },
    )

    private fun tagSim(lib: Library, minor: Int = Proto.minor, vararg faults: String): Pair<Sim, Session> {
        val s = Sim(Options(Board.of("v1"), Faults(faults.toList()), log = { lines += it }, imgDir = tmp.newFolder(), minor = minor), lib)
        sims += s
        return s to session(s.listen(0))
    }

    private fun seed(s: Session, name: String, origin: Int? = null) =
        assertTrue(name, ImagePush(s, name, null, jpeg(240, 320), 240, 320, origin = origin).run() is ImagePush.Outcome.Done)

    private fun tags() = synchronized(lines) { lines.filter { it.startsWith("tag name=") }.map { it.removePrefix("tag name=") } }

    @Test
    fun theRetagTagsUntaggedLibraryNamesByTheDeviceFoldWithTheListedName() {
        val (sim, s) = tagSim(tagLib("Gemma", "むト", "BoA", "Lead", "Phoned", "Libbed"))
        listOf("gemma", "むﾄ", "ＢｏＡ", "Stranger", " Lead").forEach { seed(s, it) }
        seed(s, "Phoned", phone)
        seed(s, "Libbed", library)
        val logs: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
        assertEquals(ArtPusher.Stop.DONE, forward(Lookups(), log = { logs += it }).run(s) { true }.stop)
        assertEquals(setOf("gemma origin=library", " Lead origin=library"), tags().toSet())
        assertEquals(2, tags().size)
        assertTrue("art tag tagged=2 gone=0 of 5" in logs)
        assertEquals(1, sim.device.stored.getValue("gemma").origins)
        assertEquals(0, sim.device.stored.getValue("ＢｏＡ").origins)
    }

    @Test
    fun aGoneEntryIsPushedByTheSweepInTheSameRun() {
        val (sim, s) = tagSim(tagLib("Gemma"))
        seed(s, "Gemma")
        var deleted = false
        val logs: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
        val f = forward(Lookups(), log = { logs += it }, onLibGet = { ss ->
            if (!deleted) {
                deleted = true
                assertTrue(ss.call(Msg.IMG_DEL, Req.imgDel("Gemma")) is Session.Result.Ok)
            }
        })
        sim.device.begins.clear()
        f.run(s) { true }
        assertTrue("art tag tagged=0 gone=1 of 1" in logs)
        assertEquals(library, origins(sim).getValue("Gemma"))
        assertEquals(1, sim.device.stored.getValue("gemma").origins)
    }

    @Test
    fun aBusyTagEndsThePassWithoutTheKeptMarkAndTheNextRunTags() {
        val (_, s) = tagSim(tagLib("Gemma"), Proto.minor, "busy=IMG_TAG:1")
        seed(s, "gemma")
        val logs: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
        val f = forward(Lookups(), marks = Kept(), log = { logs += it })
        f.run(s) { true }
        assertTrue(logs.none { it.startsWith("art tag tagged") })
        assertTrue(tags().isEmpty())
        f.run(s) { true }
        assertTrue("art tag tagged=1 gone=0 of 1" in logs)
        assertTrue(logs.none { it == "art sweep kept" })
        f.run(s) { true }
        assertEquals(1, logs.count { it == "art sweep kept" })
        assertEquals(1, tags().size)
    }

    @Test
    fun theRetagStopsWhenTheAppComesOnScreenAndRunsWithTheSweepOff() {
        val (_, s) = tagSim(tagLib("Gemma", "Hal"))
        seed(s, "gemma")
        seed(s, "hal")
        var checks = 0
        forward(Lookups(), retagging = { checks++ < 2 }).run(s) { true }
        assertEquals(1, tags().size)
        val (_, shown) = tagSim(tagLib("Gemma"))
        seed(shown, "gemma")
        lines.clear()
        forward(Lookups(), sweeping = { false }, retagging = { false }).run(shown) { true }
        assertTrue(tags().isEmpty())
        forward(Lookups(), sweeping = { false }).run(shown) { true }
        assertEquals(listOf("gemma origin=library"), tags())
    }

    @Test
    fun aChangedRescanPrunesTheJoinedPhotoAndTheSweepPushesTheSplitNames() {
        val fill = (1..6).map { Library.track("Artist %03d".format(it), "Album $it", "Song $it", 1, 200_000) }
        val (sim, s) = tagSim(Library(fill + Library.track("Gemma, Hal", "Duets", "One", 1, 200_000)))
        val f = forward(Lookups(), marks = Kept())
        f.run(s) { true }
        assertTrue("gemma, hal" in sim.device.stored.keys)
        val rev = device.lib.dataRev
        sim.rescan(Library(fill + Library.track("Gemma, Hal", "Duets", "One", 1, 200_000, albumArtist = "")))
        val end = System.nanoTime() + 5_000_000_000L
        while (device.lib.dataRev == rev && System.nanoTime() < end) Thread.sleep(10)
        assertTrue("pruned name=Gemma, Hal" in lines)
        sim.device.begins.clear()
        f.run(s) { true }
        assertEquals(mapOf("Gemma" to library, "Hal" to library), origins(sim).filterKeys { !it.startsWith("Artist") })
        assertTrue(listOf("gemma", "hal").all { it in sim.device.stored.keys })
        assertFalse("gemma, hal" in sim.device.stored.keys)
    }

    private fun dropSim(vararg extra: String): Pair<Sim, Session> {
        val s0 = Sim(Options(Board.of("v1"), log = { lines += it }, imgDir = tmp.newFolder()), Library(
            (1..7).map { Library.track("Artist %03d".format(it), "Album $it", "Song $it", 1, 200_000) } +
                extra.mapIndexed { i, n -> Library.track(n, "Other $i", "Tune $i", 1, 200_000) },
        ))
        sims += s0
        return s0 to session(s0.listen(0))
    }

    @Test
    fun aDropDeletesLibraryPhoneAndUntaggedCopiesAlike() {
        val (sim, s) = dropSim()
        seed(s, "Lib", library)
        seed(s, "Ph", phone)
        seed(s, "Bare")
        val records = ArtBlocks()
        for (n in listOf("Lib", "Ph", "Bare")) records.drop("name:$n", null, n, 1)
        forward(Lookups(), sweeping = { false }, blocks = blocks(records, "tcp:A")).run(s) { true }
        assertEquals(setOf("Lib", "Ph", "Bare"), deleted().toSet())
        assertTrue(listOf("lib", "ph", "bare").none { it in sim.device.stored.keys })
        assertTrue(records.pending("tcp:A").isEmpty())
        assertTrue(records.records().isEmpty())
    }

    @Test
    fun aDropWithNoListedMatchIsAppliedAndAFailedDeleteStaysPending() {
        val (_, port) = sim(10, 1, Options.IMG_CAP, "busy=IMG_DEL:1")
        val s = session(port)
        seed(s, "Gone")
        val records = ArtBlocks()
        records.drop("name:Nobody", null, "Nobody", 1)
        records.drop("name:Gone", null, "Gone", 1)
        forward(Lookups(), sweeping = { false }, blocks = blocks(records, "tcp:A")).run(s) { true }
        assertEquals(listOf("name:Gone"), records.pending("tcp:A").map { it.key })
        assertTrue(deleted().isEmpty())
        forward(Lookups(), sweeping = { false }, blocks = blocks(records, "tcp:A")).run(s) { true }
        assertEquals(listOf("Gone"), deleted())
        assertTrue(records.pending("tcp:A").isEmpty())
    }

    @Test
    fun aDropOnAKeyThePhoneHoldsIsAppliedWithNoDelete() {
        val (sim, s) = dropSim()
        seed(s, "Held")
        val records = ArtBlocks()
        records.drop("name:Held", null, "Held", 1)
        val b = Forward.Blocks(records, { "tcp:A" }, { n, m -> m ?: "name:$n" }, { it == "name:Held" })
        forward(Lookups(), sweeping = { false }, blocks = b).run(s) { true }
        assertTrue(deleted().isEmpty())
        assertTrue("held" in sim.device.stored.keys)
        assertTrue(records.pending("tcp:A").isEmpty())
    }

    @Test
    fun aDropDeletesBeforeTheSameRunPushes() {
        val (_, s) = dropSim()
        forward(Lookups()).run(s) { true }
        val now = device.playback.state.artists.first()
        val records = ArtBlocks()
        records.drop("name:$now", null, now, 1)
        val before = synchronized(lines) { lines.size }
        forward(Lookups(), blocks = blocks(records, "tcp:A")).run(s) { true }
        val after = synchronized(lines) { lines.drop(before) }
        val del = after.indexOfFirst { it == "deleted name=$now" }
        val put = after.indexOfLast { it.startsWith("stored name=$now ") }
        assertTrue("$del $put", del >= 0 && put > del)
    }

    @Test
    fun anOrphanIsLookedUpOnceAndAHitReplacesTheCopy() {
        val (sim, s) = dropSim("Orphan", "Kept")
        assertTrue(ImagePush(s, "Orphan", null, jpeg(240, 320, 3000), 240, 320).run() is ImagePush.Outcome.Done)
        assertTrue(ImagePush(s, "Kept", null, jpeg(240, 320, 3000), 240, 320).run() is ImagePush.Outcome.Done)
        val looked = Lookups()
        val known: (String, String?) -> Boolean = { n, _ -> n != "Orphan" || "Orphan" in looked.names }
        forward(looked, known = known).run(s) { true }
        assertEquals(1, looked.names.count { it == "Orphan" })
        assertFalse("Kept" in looked.names)
        assertEquals(2, stores().count { it == "Orphan" })
        assertEquals(1, stores().count { it == "Kept" })
        assertTrue("orphan" in sim.device.stored.keys)
        forward(looked, known = known).run(s) { true }
        assertEquals(1, looked.names.count { it == "Orphan" })
    }

    @Test
    fun anOrphanMissWithAPlaceholderDropsTheCopyAndAPlainMissKeepsIt() {
        val (sim, s) = dropSim("Silhouette", "Plain")
        seed(s, "Silhouette")
        seed(s, "Plain")
        val records = ArtBlocks()
        val looked = Lookups()
        val f = Forward(
            { device },
            { n, m, w, h ->
                looked.names += n
                when (n) {
                    "Silhouette" -> null.also { records.drop("name:$n", null, n, 1) }
                    "Plain" -> null
                    else -> Art(jpeg(w, h), w, h, n, m, "test", null)
                }
            },
            { true },
            { true },
            { true },
            { s2, offset, rev ->
                val snap = device.lib.keyed(Enums.View.artists)
                s2.call(Msg.LIB_GET, Req.libGet(Enums.View.artists, "", offset, LIB_PAGE, rev.takeUnless { snap }, rev.takeIf { snap }))
            },
            Sweep.Breaker({ 0L }),
            blocks = blocks(records, "tcp:A"),
            cached = { n, _ -> n !in setOf("Silhouette", "Plain") || n in looked.names },
        )
        f.run(s) { true }
        assertTrue(listOf("silhouette", "plain").all { it in sim.device.stored.keys })
        f.run(s) { true }
        assertEquals(listOf("Silhouette"), deleted())
        assertTrue("plain" in sim.device.stored.keys)
        assertTrue(records.pending("tcp:A").isEmpty())
    }

    @Test
    fun aGatedOrphanHitIsPushedOverTheCopyOnceTheGateOpens() {
        val (sim, s) = dropSim("Orphan")
        assertTrue(ImagePush(s, "Orphan", null, jpeg(240, 320, 3000), 240, 320).run() is ImagePush.Outcome.Done)
        val records = ArtBlocks()
        val looked = Lookups()
        val known: (String, String?) -> Boolean = { n, _ -> n != "Orphan" || "Orphan" in looked.names }
        forward(looked, blocks = blocks(records, "tcp:A"), known = known, gate = { looked.names.lastOrNull() != "Orphan" }).run(s) { true }
        assertEquals(1, looked.names.count { it == "Orphan" })
        assertEquals(1, stores().count { it == "Orphan" })
        assertEquals(listOf("Orphan"), records.held("tcp:A").map { it.name })
        assertTrue(records.pushes("tcp:A").isEmpty())
        forward(looked, blocks = blocks(records, "tcp:A"), known = known).run(s) { true }
        assertEquals(2, stores().count { it == "Orphan" })
        assertTrue("orphan" in sim.device.stored.keys)
        assertTrue(records.held("tcp:A").isEmpty())
    }

    @Test
    fun anOrphanReplaceDoesNotGrowTheBudget() {
        val orphans = listOf("Oa", "Ob", "Oc", "Od")
        val s0 = Sim(Options(Board.of("v1"), log = { lines += it }, imgDir = tmp.newFolder(), imgCap = 20_000), Library(
            (1..7).map { Library.track("Artist %03d".format(it), "Album $it", "Song $it", 1, 200_000) } +
                orphans.flatMap { n -> (1..2).map { i -> Library.track(n, "Two $n", "Tune $i", i, 200_000) } } +
                Library.track("Fresh", "Last", "Once", 1, 200_000),
        ))
        sims += s0
        val s = session(s0.listen(0))
        for (n in orphans) assertTrue(ImagePush(s, n, null, jpeg(240, 320, 3000), 240, 320).run() is ImagePush.Outcome.Done)
        val looked = Lookups()
        forward(looked, missing = { it.startsWith("Artist") }, known = { n, _ -> n !in orphans || n in looked.names }).run(s) { true }
        assertTrue(orphans.all { n -> looked.names.count { it == n } == 1 })
        assertEquals(1, stores().count { it == "Fresh" })
    }

    @Test
    fun heldOrphanPushesWaitBehindTheCurrentArtist() {
        val held = listOf("Ha", "Hb", "Hc")
        val (_, s) = dropSim(*held.toTypedArray())
        for (n in held) assertTrue(ImagePush(s, n, null, jpeg(240, 320, 3000), 240, 320).run() is ImagePush.Outcome.Done)
        val records = ArtBlocks()
        for (n in held) records.hold(n, null, "tcp:A")
        val now = device.playback.state.artists.first()
        val before = synchronized(lines) { lines.size }
        forward(Lookups(), blocks = blocks(records, "tcp:A")).run(s) { true }
        val after = synchronized(lines) { lines.drop(before) }
        val current = after.indexOfFirst { it.startsWith("stored name=$now ") }
        val first = after.indexOfFirst { l -> held.any { l.startsWith("stored name=$it ") } }
        assertTrue("$current $first", current >= 0 && first > current)
        assertTrue(held.all { n -> after.any { it.startsWith("stored name=$n ") } })
        assertTrue(records.held("tcp:A").isEmpty())
    }

    @Test
    fun anotherDeviceGetsNoneOfTheHeldPushes() {
        val (_, s) = dropSim("Ha")
        seed(s, "Ha")
        val records = ArtBlocks()
        records.hold("Ha", null, "tcp:A")
        forward(Lookups(), blocks = blocks(records, "tcp:B")).run(s) { true }
        assertEquals(1, stores().count { it == "Ha" })
        assertEquals(listOf("Ha"), records.held("tcp:A").map { it.name })
    }

    @Test
    fun aListedNameCachedUnderItsLibraryMbidIsNoOrphan() {
        val s0 = Sim(Options(Board.of("v1"), log = { lines += it }, imgDir = tmp.newFolder()), Library(
            (1..7).map { Library.track("Artist %03d".format(it), "Album $it", "Song $it", 1, 200_000) } +
                Library.track("Tagged", "Tags", "One", 1, 200_000, mbids = listOf("mb-t")),
        ))
        sims += s0
        val s = session(s0.listen(0))
        seed(s, "Tagged")
        val looked = Lookups()
        forward(looked, known = { n, m -> n != "Tagged" || m == "mb-t" }).run(s) { true }
        assertFalse("Tagged" in looked.names)
    }

    @Test
    fun aHeldPushGoesOutWithTheSweepOff() {
        val (sim, s) = dropSim("Ha")
        assertTrue(ImagePush(s, "Ha", null, jpeg(240, 320, 3000), 240, 320).run() is ImagePush.Outcome.Done)
        val records = ArtBlocks()
        records.hold("Ha", null, "tcp:A")
        val looked = Lookups()
        forward(looked, sweeping = { false }, blocks = blocks(records, "tcp:A")).run(s) { true }
        assertEquals(2, stores().count { it == "Ha" })
        assertTrue("ha" in sim.device.stored.keys)
        assertTrue(records.held("tcp:A").isEmpty())
    }
}

