package net.seilent.funecomp

import net.seilent.funecomp.art.Forward
import net.seilent.funecomp.artist.Art
import net.seilent.funecomp.artist.ArtBlocks
import net.seilent.funecomp.artist.ArtPusher
import net.seilent.funecomp.artist.Full
import net.seilent.funecomp.artist.FullPush
import net.seilent.funecomp.artist.Sweep
import net.seilent.funecomp.link.Session
import net.seilent.funecomp.link.TcpTransport
import net.seilent.funecomp.proto.Groups
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
import net.seilent.funecomp.state.PushGate
import net.seilent.funecomp.ui.LIB_PAGE
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32

class RepushTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val sims = ArrayList<Sim>()
    private val sessions = ArrayList<Session>()
    private val lines: MutableList<Pair<Long, String>> = CopyOnWriteArrayList()
    private val logs: MutableList<String> = CopyOnWriteArrayList()
    private val looked: MutableList<String> = CopyOnWriteArrayList()
    @Volatile private var device = DeviceState()
    private val wall = AtomicLong(1_792_700_000_000L)
    private val epoch = AtomicInteger(0)

    @Volatile private var data = true
    @Volatile private var rearm = true
    @Volatile private var unmetered = true
    @Volatile private var bluetooth = false
    @Volatile private var cardDown = false
    private val cached = HashSet<String>()
    private val sources = HashMap<String, Full>()
    private val refusals: MutableList<String> = CopyOnWriteArrayList()

    @After
    fun tearDown() {
        sessions.forEach { it.close("done") }
        sims.forEach { it.stop() }
    }

    private fun name(i: Int) = "Artist %03d".format(i)

    private var faults = Faults(emptyList())

    private fun sim(vararg extra: String, source: Boolean = true): Pair<Sim, Int> {
        val lib = Library((1..12).map { i -> Library.track(name(i), "Album $i", "Song $i", 1, 200_000) })
        faults = Faults((if (source) listOf("img-source") else emptyList()) + extra)
        val s = Sim(Options(Board.of("v1"), faults, log = { lines += System.nanoTime() / 1_000_000 to it }, wall = { wall.addAndGet(1000) }), lib)
        sims += s
        return s to s.listen(0)
    }

    private fun session(port: Int, features: Long = Groups.mask("imgOrigin", "imgSource")): Session {
        val ready = CountDownLatch(1)
        device = DeviceState()
        val s = Session(TcpTransport("127.0.0.1", port), object : Session.Listener {
            override fun onHello(hello: HelloReply) {
                synchronized(this@RepushTest) { device = device.withHello(hello) }
                ready.countDown()
            }

            override fun onPush(type: Int, body: Node) {
                synchronized(this@RepushTest) { device = device.reduce(type, body, System.nanoTime() / 1_000_000) }
            }
        }, Session.Config("test", features = features))
        sessions += s
        Thread { s.run() }.apply { isDaemon = true }.start()
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        val end = System.nanoTime() + 5_000_000_000L
        while (device.playback.state.slots == null && System.nanoTime() < end) Thread.sleep(10)
        return s
    }

    private fun crop(w: Int, h: Int, seed: Int): ByteArray {
        val head = byteArrayOf(-1, -40, -1, -64, 0, 17, 8, (h shr 8).toByte(), h.toByte(), (w shr 8).toByte(), w.toByte(), 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1)
        return ByteArray(2000).also { head.copyInto(it); it[100] = seed.toByte(); it[1998] = -1; it[1999] = -39 }
    }

    private fun source(w: Int, h: Int, seed: Int): ByteArray {
        val sof = byteArrayOf(-1, -64, 0, 17, 8, (h shr 8).toByte(), h.toByte(), (w shr 8).toByte(), w.toByte(), 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1)
        val sos = byteArrayOf(-1, -38, 0, 12, 3, 1, 0, 2, 0x11, 3, 0x11, 0, 63, 0)
        val body = ByteArray(30_000) { ((it * 31 + seed) % 200).toByte() }
        return byteArrayOf(-1, -40) + sof + sos + body + byteArrayOf(-1, -39)
    }

    private fun full(i: Int) {
        val b = source(900, 600, i)
        sources["k:${name(i)}"] = Full(b, 900, 600, CRC32().apply { update(b) }.value, null)
    }

    private fun old(port: Int, vararg names: Int) = old(port, names.map { name(it) })

    private fun old(port: Int, names: List<String>, origin: Int? = null) {
        val s = session(port, Groups.mask("imgOrigin"))
        val r = ArtPusher(s, { n, m, w, h -> Art(crop(w, h, n.hashCode() + 1), w, h, n, m, "test", null) }) { true }.run(names.map { it to null }, origin = origin)
        assertEquals(names.size, r.pushed.size)
        s.close("old app")
    }

    private fun now() = System.nanoTime() / 1_000_000

    private fun forward(
        gapMs: Long = 50,
        blocks: Forward.Blocks? = null,
        production: Boolean = false,
        upgrades: Forward.Upgrades? = null,
        looking: () -> Boolean = { true },
        gate: () -> Boolean = { !production || PushGate.open(device, now()) },
        rows: Boolean = false,
        holding: () -> Boolean = { false },
    ) = Forward(
        { device },
        { n, m, w, h, _ ->
            looked += n
            if ("k:$n" !in sources) null else Art(crop(w, h, n.hashCode()), w, h, n, m, "test", null, key = "k:$n")
        },
        gate,
        { false },
        { false },
        { s, offset, rev ->
            if (!rows) {
                Session.Result.Closed
            } else {
                val snap = device.lib.keyed(Enums.View.artists)
                s.call(Msg.LIB_GET, Req.libGet(Enums.View.artists, "", offset, LIB_PAGE, rev.takeUnless { snap }, rev.takeIf { snap }))
            }
        },
        Sweep.Breaker({ 0L }),
        blocks = blocks,
        cached = { n, _ -> n in cached },
        holding = holding,
        cardDown = { cardDown },
        upgrades = upgrades,
        looking = looking,
        full = FullPush({ sources[it] }, { refusals += it; refusals.size }, { bluetooth }),
        refetch = if (production) {
            Forward.Refetch({ PushGate.refetch(device, now()) }, { PushGate.rearm(device) }, { unmetered }, { device.card.epoch }, { device.playback.state.playing == true }, gapMs)
        } else {
            Forward.Refetch({ data }, { rearm }, { unmetered }, { epoch.get() }, { false }, gapMs)
        },
        log = { logs += it },
    )

    private fun snap() = lines.toList()

    private fun stored() = lines.filter { it.second.startsWith("stored source name=") }.map { Regex("^stored source name=(.*) \\d+x\\d+ bytes=").find(it.second)!!.groupValues[1] }

    private fun storedAt() = lines.filter { it.second.startsWith("stored source name=") }.map { it.first }

    @Test
    fun cachedNamesGoFirstAndOrphansWaitForAnUnmeteredNetwork() {
        val (sim, port) = sim()
        old(port, 8, 9, 10)
        listOf(8, 9, 10).forEach { full(it) }
        cached += listOf(name(8), name(9))
        unmetered = false
        val f = forward()
        val s = session(port)
        assertEquals(ArtPusher.Stop.DONE, f.run(s) { true }.stop)
        assertEquals(setOf(name(8), name(9)), stored().toSet())
        assertFalse(name(10) in looked)
        unmetered = true
        f.run(s) { true }
        assertEquals(setOf(name(8), name(9), name(10)), stored().toSet())
        assertEquals(3, sim.device.sources.size)
        assertTrue(logs.any { it == "art repush 3 of 3 ${3 * sources.getValue("k:${name(8)}").bytes.size}" })
    }

    @Test
    fun everySourceOffSendsNoRepushWhenAPausedHeadsetGatesTheCurrentStage() {
        val (sim, port) = sim()
        old(port, 8, 9)
        listOf(8, 9).forEach { full(it); cached += name(it) }
        full(1)
        val s = session(port)
        output(sim, s, Enums.Output.btHeadset, false)
        assertEquals(name(1), device.playback.state.artists.firstOrNull())
        val r = forward(looking = { false }, gate = { PushGate.open(device, now()) }).run(s) { true }
        assertEquals(ArtPusher.Stop.GATE, r.stop)
        assertTrue(stored().isEmpty())
        assertTrue(sim.device.sources.isEmpty())
        assertFalse(logs.any { it.startsWith("art repush") })
    }

    @Test
    fun aRepushKeepsTheListedOriginAndTagsAnUnlistedOneByTheRows() {
        val (sim, port) = sim()
        old(port, 8)
        old(port, listOf(name(9)), Enums.ImgOrigin.phone)
        old(port, listOf("Outsider"))
        listOf(8, 9).forEach { full(it); cached += name(it) }
        val b = source(900, 600, 99)
        sources["k:Outsider"] = Full(b, 900, 600, CRC32().apply { update(b) }.value, null)
        cached += "Outsider"
        sim.device.begins.clear()
        forward(rows = true, holding = { true }).run(session(port)) { true }
        val o = sim.device.begins.toList().toMap()
        assertEquals(Enums.ImgOrigin.library, o.getValue(name(8)))
        assertEquals(Enums.ImgOrigin.phone, o.getValue(name(9)))
        assertEquals(Enums.ImgOrigin.phone, o.getValue("Outsider"))
    }

    @Test
    fun aRepushWithUnreadableRowsSendsAnUnlistedEntryWithNoOrigin() {
        val (sim, port) = sim()
        old(port, 8)
        full(8)
        cached += name(8)
        sim.device.begins.clear()
        forward().run(session(port)) { true }
        assertEquals(listOf(name(8) to null), sim.device.begins.toList())
    }

    @Test
    fun aRepushOnceTheAppIsShownTakesOnlyTheCachedRowsAndSendsNoLibGet() {
        val (sim, port) = sim()
        old(port, 8)
        full(8)
        cached += name(8)
        sim.device.begins.clear()
        var checks = 0
        forward(rows = true, holding = { checks++ < 1 }).run(session(port)) { true }
        assertEquals(listOf(name(8) to null), sim.device.begins.toList())
        assertTrue(lines.none { "rx LIB_GET" in it.second })
    }

    @Test
    fun onePushAtATimeWithTheGapAfterEachReply() {
        val (_, port) = sim()
        old(port, 8, 9, 10)
        listOf(8, 9, 10).forEach { full(it); cached += name(it) }
        val s = session(port)
        forward(gapMs = 400).run(s) { true }
        val at = storedAt()
        assertEquals(3, at.size)
        for (k in 1 until at.size) assertTrue("${at[k] - at[k - 1]}", at[k] - at[k - 1] >= 400)
    }

    @Test
    fun theQueueArtistsGoFirstThenTheNewestShown() {
        val (_, port) = sim()
        old(port, 8)
        old(port, 9)
        old(port, 3)
        listOf(3, 8, 9).forEach { full(it); cached += name(it) }
        val s = session(port)
        forward().run(s) { true }
        assertEquals(listOf(name(3), name(9), name(8)), stored())
    }

    @Test
    fun aClosedDataGatePausesInPlaceAndThePlayingArtistStillGoes() {
        val (sim, port) = sim()
        old(port, 8, 9)
        listOf(1, 8, 9).forEach { full(it); cached += name(it) }
        bluetooth = true
        data = false
        val f = forward()
        val s = session(port)
        assertEquals(ArtPusher.Stop.GATE, f.run(s) { true }.stop)
        assertTrue(sim.device.stored.containsKey("artist 001"))
        assertTrue(sim.device.sources.isEmpty())
        bluetooth = false
        data = true
        assertEquals(ArtPusher.Stop.DONE, f.run(s) { true }.stop)
        assertEquals(setOf("artist 001", "artist 008", "artist 009"), sim.device.sources.keys)
    }

    @Test
    fun theCardDownStopsTheStageAndTheReadyEdgeListsAgain() {
        val (sim, port) = sim()
        old(port, 8)
        full(8)
        cached += name(8)
        cardDown = true
        val f = forward()
        val s = session(port)
        assertEquals(ArtPusher.Stop.GATE, f.run(s) { true }.stop)
        assertTrue(sim.device.sources.isEmpty())
        cardDown = false
        epoch.incrementAndGet()
        f.run(s) { true }
        assertEquals(setOf("artist 008"), sim.device.sources.keys)
        val lists = logs.count { it.startsWith("art repush list") }
        f.run(s) { true }
        assertEquals(lists, logs.count { it.startsWith("art repush list") })
        epoch.incrementAndGet()
        f.run(s) { true }
        assertEquals(lists + 1, logs.count { it.startsWith("art repush list") })
    }

    @Test
    fun noSourceRoomStopsPushesWithDataButNotTheReArm() {
        val (sim, port) = sim("src-room=0")
        full(8)
        full(9)
        cached += listOf(name(8), name(9))
        val s = session(port)
        ArtPusher(s, { n, m, w, h -> Art(crop(w, h, 1), w, h, n, m, "test", null, key = "k:$n") }, FullPush({ sources[it] }, { 0 }), { true }) { true }.run(listOf(name(8) to null))
        s.close("first")
        old(port, 8, 9)
        assertTrue(sim.device.sources.getValue("artist 008").aside)
        data = false
        forward().run(session(port)) { true }
        assertFalse(sim.device.sources.getValue("artist 008").aside)
        assertTrue(snap().any { it.second == "source rearm name=${name(8)}" })
        assertFalse(sim.device.sources.containsKey("artist 009"))
    }

    @Test
    fun anOldDeviceNeverEntersTheStage() {
        val (sim, port) = sim(source = false)
        old(port, 8)
        full(8)
        cached += name(8)
        sim.device.fulls.clear()
        forward().run(session(port)) { true }
        assertTrue(sim.device.fulls.isEmpty())
        assertTrue(logs.none { it.startsWith("art repush") })
    }

    @Test
    fun anEntryWithABlockedCrcIsDeletedAndNotPushed() {
        val (sim, port) = sim()
        old(port, 7, 8)
        listOf(7, 8).forEach { full(it); cached += name(it) }
        val crc = sim.device.stored.getValue("artist 007").crc
        val records = ArtBlocks(File(tmp.newFolder(), "artblock.tsv")).apply {
            add(ArtBlocks.Block("other", null, "test", "id", "sha", "Other", 1L, crc = crc))
        }
        forward(blocks = Forward.Blocks(records, { "v1" }, { n, _ -> "name:$n" })).run(session(port)) { true }
        assertTrue(snap().any { it.second == "deleted name=${name(7)}" })
        assertEquals(setOf("artist 008"), sim.device.sources.keys)
    }

    @Test
    fun aRerunWithEverySourceShownQueuesNothing() {
        val (sim, port) = sim()
        old(port, 8)
        full(8)
        cached += name(8)
        val f = forward()
        val s = session(port)
        f.run(s) { true }
        val n = sim.device.fulls.size
        epoch.incrementAndGet()
        f.run(s) { true }
        assertEquals(n, sim.device.fulls.size)
        assertTrue(logs.last { it.startsWith("art repush list") }.startsWith("art repush list 0 of"))
    }

    @Test
    fun aBusyBeginIsNoAttemptAndTheSameArtistGoesNext() {
        val (sim, port) = sim()
        old(port, 8)
        full(8)
        cached += name(8)
        faults.busy[Msg.IMG_BEGIN] = AtomicInteger(2)
        forward().run(session(port)) { true }
        assertEquals(setOf("artist 008"), sim.device.sources.keys)
        assertEquals(3, snap().count { "rx IMG_BEGIN" in it.second } - 1)
    }

    @Test
    fun aPreemptionStopsAtTheArtistBoundaryAndTheNextRunGoesOn() {
        val (sim, port) = sim()
        old(port, 8, 9)
        listOf(8, 9).forEach { full(it); cached += name(it) }
        val f = forward()
        val s = session(port)
        var stores = 0
        val r = f.run(s) { stored().size.also { stores = it } < 1 }
        assertEquals(ArtPusher.Stop.PREEMPTED, r.stop)
        assertEquals(1, stores)
        f.run(s) { true }
        assertEquals(setOf("artist 008", "artist 009"), sim.device.sources.keys)
    }

    private fun output(sim: Sim, s: Session, out: Int, play: Boolean) {
        sim.device.output(out)
        assertTrue(s.call(Msg.TRANSPORT, Req.transport(if (play) Enums.TransportCmd.play else Enums.TransportCmd.pause)) is Session.Result.Ok)
        val end = System.nanoTime() + 5_000_000_000L
        while (!(device.playback.state.output == out && (device.playback.state.playing == true) == play) && System.nanoTime() < end) Thread.sleep(10)
    }

    private fun aside(port: Int, i: Int) {
        full(i)
        cached += name(i)
        val s = session(port)
        ArtPusher(s, { n, m, w, h -> Art(crop(w, h, 1), w, h, n, m, "test", null, key = "k:$n") }, FullPush({ sources[it] }, { 0 }), { true }) { true }.run(listOf(name(i) to null))
        s.close("first")
        old(port, i)
    }

    @Test
    fun withTheProductionGatesAPausedHeadsetPushesAfterTheIdleTime() {
        val (sim, port) = sim()
        old(port, 8)
        listOf(1, 8).forEach { full(it); cached += name(it) }
        val f = forward(production = true)
        val s = session(port)
        output(sim, s, Enums.Output.btHeadset, false)
        assertEquals(ArtPusher.Stop.GATE, f.run(s) { true }.stop)
        assertTrue(sim.device.sources.isEmpty())
        Thread.sleep(Proto.imgRefetchIdleMs + 300L)
        assertEquals(ArtPusher.Stop.GATE, f.run(s) { true }.stop)
        assertEquals(setOf("artist 008"), sim.device.sources.keys)
    }

    @Test
    fun withTheProductionGatesAReArmGoesWhileTheHeadsetPlays() {
        val (sim, port) = sim()
        full(1)
        cached += name(1)
        aside(port, 8)
        assertTrue(sim.device.sources.getValue("artist 008").aside)
        val f = forward(production = true)
        val s = session(port)
        output(sim, s, Enums.Output.btHeadset, true)
        f.run(s) { true }
        assertTrue(snap().any { it.second == "source rearm name=${name(8)}" })
        assertFalse(sim.device.sources.getValue("artist 008").aside)
    }

    @Test
    fun afterANoSpaceTheOtherDataItemsWaitAndKeepTheirCopy() {
        val (sim, port) = sim("src-nospace")
        old(port, 8, 9)
        listOf(8, 9).forEach { full(it); cached += name(it) }
        val before = listOf("artist 008", "artist 009").map { sim.device.stored.getValue(it).crc }
        forward().run(session(port)) { true }
        assertEquals(1, sim.device.fulls.size)
        val after = listOf("artist 008", "artist 009").map { sim.device.stored.getValue(it).crc }
        assertEquals(1, before.indices.count { before[it] != after[it] })
    }

    @Test
    fun aReArmedSourceListedAgainWithoutFullCountsAsARefusal() {
        val (sim, port) = sim("src-fail=artist 008")
        aside(port, 8)
        val f = forward()
        val s = session(port)
        f.run(s) { true }
        assertTrue(snap().any { it.second == "source rearm name=${name(8)}" })
        assertTrue(refusals.isEmpty())
        epoch.incrementAndGet()
        f.run(s) { true }
        assertEquals(listOf("k:${name(8)}"), refusals.toList())
        assertTrue(logs.contains("art repush render failed or set aside ${name(8)}"))
    }

    @Test
    fun aSourceTheCurrentStagePushedThatFailsToRenderCountsAsARefusal() {
        val (_, port) = sim("src-fail=artist 001")
        full(1)
        cached += name(1)
        forward().run(session(port)) { true }
        assertEquals(listOf("k:${name(1)}"), refusals.toList())
    }

    @Test
    fun anOrphanIsNotLookedUpWhileTheDataGateIsClosed() {
        val (_, port) = sim()
        old(port, 8)
        full(8)
        data = false
        forward().run(session(port)) { true }
        assertFalse(name(8) in looked)
    }

    @Test
    fun aBlockedSourceSetAsideIsNeitherDeletedNorReArmed() {
        val (sim, port) = sim()
        aside(port, 7)
        val crc = sim.device.sources.getValue("artist 007").crc
        val records = ArtBlocks(File(tmp.newFolder(), "artblock.tsv")).apply {
            add(ArtBlocks.Block("other", null, "test", "id", "sha", "Other", 1L, crc = crc))
        }
        forward(blocks = Forward.Blocks(records, { "v1" }, { n, _ -> "name:$n" })).run(session(port)) { true }
        assertFalse(snap().any { it.second == "deleted name=${name(7)}" })
        assertFalse(snap().any { it.second == "source rearm name=${name(7)}" })
        assertTrue(sim.device.sources.getValue("artist 007").aside)
    }

    @Test
    fun aPendingUpgradeGatedOnTheHeadsetStillLetsTheRepushRun() {
        val (sim, port) = sim()
        old(port, 7, 8)
        listOf(7, 8).forEach { full(it); cached += name(it) }
        val records = ArtBlocks(File(tmp.newFolder(), "artblock.tsv")).apply { upgraded("k:${name(7)}", name(7), null, 5L, "other") }
        val ups = object : Forward.Upgrades {
            override fun due() = emptyList<Pair<String, String?>>()
            override fun run(name: String, mbid: String?, w: Int, h: Int): Forward.Upgraded? = null
            override fun open() = true
            override fun at(key: String) = 5L
        }
        val f = forward(production = true, blocks = Forward.Blocks(records, { "v1" }, { n, _ -> "k:$n" }), upgrades = ups)
        val s = session(port)
        output(sim, s, Enums.Output.btHeadset, false)
        Thread.sleep(Proto.imgRefetchIdleMs + 300L)
        assertEquals(ArtPusher.Stop.GATE, f.run(s) { true }.stop)
        assertTrue(logs.any { it.startsWith("art upgrade GATE") })
        assertEquals(setOf("artist 007", "artist 008"), sim.device.sources.keys)
    }
}
