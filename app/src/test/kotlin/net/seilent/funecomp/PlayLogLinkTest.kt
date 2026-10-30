package net.seilent.funecomp

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Looper
import net.seilent.funecomp.artist.AppPair
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.Response
import net.seilent.funecomp.link.Session
import net.seilent.funecomp.link.TcpTransport
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.PlayEntry
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import net.seilent.funecomp.sim.Track
import net.seilent.funecomp.ui.Phase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayLogLinkTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val sims = ArrayList<Sim>()
    private val peers = ArrayList<Session>()
    private val skew = AtomicLong()
    private val logs = CopyOnWriteArrayList<String>()
    private val bodies = CopyOnWriteArrayList<String>()

    private val net = object : Http {
        override fun get(url: String, headers: Map<String, String>) = Response(200, "{\"valid\":true,\"user_name\":\"fake\"}".toByteArray())

        override fun post(url: String, headers: Map<String, String>, body: ByteArray): Response {
            check(url.startsWith("https://api.listenbrainz.org/")) { "sent to $url" }
            check(headers["Authorization"] == "Token fake-token") { "real credentials" }
            bodies += String(body)
            return Response(200, "{\"status\":\"ok\"}".toByteArray())
        }
    }

    private val short = Track(11, "Radiohead", listOf("Radiohead"), "OK Computer", "Radiohead", "Lucky", 31_000, "Radiohead/OK Computer", "01 Lucky.flac")
    private val other = Track(12, "Radiohead", listOf("Radiohead"), "OK Computer", "Radiohead", "Airbag", 300_000, "Radiohead/OK Computer", "02 Airbag.flac")

    @Before
    fun setUp() {
        app.getSharedPreferences("scrobble", Context.MODE_PRIVATE).edit().putBoolean("listenbrainz", true).putString("token", "fake-token").commit()
        Scrobbles.init(app, AppPair.EMPTY, net) {}
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        MediaLink.controls(app, false)
        Remote.artist(app, Http { _, _ -> Response(404, ByteArray(0)) })
        Lyrics.init(app, Http { _, _ -> Response(404, ByteArray(0)) })
    }

    @After
    fun tearDown() {
        Remote.stop()
        peers.forEach { it.close("test") }
        sims.forEach { it.stop() }
        Scrobbles.reset()
    }

    private fun seed(n: Int, from: Int = 1, liveSeen: Boolean? = null) = (from until from + n).map {
        PlayEntry(0, System.currentTimeMillis() / 1000 - 7200 + it * 60, 150_000, 300_000, 12, "Seed $it", "Radiohead", listOf("Radiohead"), liveSeen = liveSeen, zoneS = 0)
    }

    private var fx = Faults(emptyList())

    private fun sim(minor: Int = 15, faults: List<String> = emptyList(), seed: List<PlayEntry> = emptyList()): Pair<Sim, Int> {
        fx = Faults(faults)
        val opts = Options(Board.of("v1"), fx, minor = minor, playLog = seed, log = { logs += it }, deviceClock = { System.nanoTime() / 1_000_000 + skew.get() })
        val s = Sim(opts, Library(listOf(short, other)))
        sims += s
        return s to s.listen(0)
    }

    private fun link(port: Int, live: Boolean = true) {
        Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java).putExtra(Main.EXTRA_LINK, "tcp:127.0.0.1:$port")).create().startCommand(0, 1)
        if (live) waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.track != null }
    }

    private fun start(minor: Int = 15, faults: List<String> = emptyList(), seed: List<PlayEntry> = emptyList(), live: Boolean = true): Sim {
        val (s, port) = sim(minor, faults, seed)
        link(port, live)
        return s
    }

    private fun oldPeer(port: Int): Session {
        val ready = CountDownLatch(1)
        val p = Session(TcpTransport("127.0.0.1", port), object : Session.Listener {
            override fun onHello(hello: HelloReply) = ready.countDown()
        }, Session.Config("test"))
        peers += p
        Thread { p.run() }.apply { isDaemon = true }.start()
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        return p
    }

    private fun cross() {
        waitFor { Remote.device.playback.state.let { it.track == short.id && it.playing == true } }
        skew.addAndGet(CROSS_MS)
    }

    private fun seenPastTheCrossing() {
        waitFor { Remote.device.playback.state.let { it.track != short.id || (it.positionMs ?: 0) >= CROSS_MS } }
        Remote.transport(Enums.TransportCmd.pause, null, null, null)
        waitFor { Remote.device.playback.state.playing != true }
        Scrobbles.idle()
    }

    private fun waitFor(ms: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun reconnect(hellos: Int) {
        val end = System.currentTimeMillis() + 30_000
        while (logs.count { "rx HELLO" in it } <= hellos || Remote.phase != Phase.LIVE) {
            check(System.currentTimeMillis() < end) { "no second session" }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
            Thread.sleep(100)
        }
    }

    private fun listens() = bodies.filter { "playing_now" !in it }.flatMap { b -> Regex("\"track_name\":\"([^\"]+)\"").findAll(b).map { it.groupValues[1] } }

    private fun nowPlaying() = bodies.count { "playing_now" in it }

    private fun playShort() = Remote.play(Enums.View.songs, "", 0, false, listOf(short.id))

    @Test
    fun aPlayPastTheRuleWithBit23GivesOneLineFromTheLogAndNoneFromLive() {
        val s = start()
        playShort()
        cross()
        waitFor(20_000) { listens().isNotEmpty() && s.device.plays.isEmpty() }
        seenPastTheCrossing()
        assertEquals(listOf("Lucky"), listens())
        assertTrue(nowPlaying() >= 1)
        assertTrue(logs.any { it.startsWith("playlog append seq 1 Lucky") })
    }

    @Test
    fun belowMinor15ThePlayGoesOutLive() {
        start(minor = 14)
        playShort()
        cross()
        waitFor(20_000) { listens().isNotEmpty() }
        seenPastTheCrossing()
        assertEquals(listOf("Lucky"), listens())
        assertTrue(logs.none { "rx PLAYLOG" in it })
    }

    @Test
    fun aDropMidPlayAndAReconnectBeforeTheCrossingGivesOne() {
        val s = start()
        playShort()
        waitFor { Remote.device.playback.state.let { it.track == short.id && it.playing == true } }
        skew.addAndGet(5_000)
        waitFor { (Remote.device.playback.state.positionMs ?: 0) >= 5_000 }
        s.current!!.close("test")
        reconnect(1)
        skew.addAndGet(CROSS_MS - 5_000)
        waitFor(20_000) { listens().isNotEmpty() && s.device.plays.isEmpty() }
        seenPastTheCrossing()
        assertEquals(listOf("Lucky"), listens())
    }

    @Test
    fun aLostAckGivesOneLineAfterTheReReadAndTheNextAckEmptiesTheLog() {
        val s = start(faults = listOf("drop-ack"), seed = seed(1), live = false)
        waitFor(20_000) { logs.any { it.endsWith("closed drop-ack") } }
        reconnect(1)
        waitFor(20_000) { s.device.plays.isEmpty() }
        Scrobbles.idle()
        assertEquals(listOf("Seed 1"), listens())
        assertEquals(2, logs.count { it.startsWith("playlog get after 0") })
    }

    @Test
    fun aGetRefusedForTheCardAtHelloIsAskedAgainAtTheReadyEdgeAndNotWhileDown() {
        val s = start(faults = listOf("card-run=3000+0@hello", "fail=PLAYLOG_GET:14:1"), seed = seed(1), live = false)
        waitFor(20_000) { s.device.plays.isEmpty() }
        Scrobbles.idle()
        assertEquals(listOf("Seed 1"), listens())
        val all = logs.toList()
        val ready = all.indexOfFirst { it.startsWith("card state ready") }
        assertTrue(ready > 0)
        assertEquals(1, all.subList(0, ready).count { "rx PLAYLOG_GET" in it })
        assertEquals(1, all.subList(ready, all.size).count { it.startsWith("playlog get after 0") })
    }

    @Test
    fun aGetRefusedWithNoBusyStateIsAskedAgainAtTheFirstReadyState() {
        val s = start(faults = listOf("card-run=3000+0@manual", "card-coalesce"), live = false)
        waitFor { Remote.device.card.epoch >= 1 && Remote.device.playback.state.track != null }
        Thread.sleep(500)
        fx.fail[Msg.PLAYLOG_GET] = Err.noCard
        fx.failLeft[Msg.PLAYLOG_GET] = java.util.concurrent.atomic.AtomicInteger(1)
        s.device.startCard()
        s.device.logPlay(seed(1).single())
        s.current!!.push(Msg.STATE, s.device.state())
        waitFor { logs.count { " tx PLAYLOG_GET flags=0x03 " in it } == 1 }
        Thread.sleep(500)
        assertEquals(1, s.device.plays.size)
        waitFor(20_000) { s.device.plays.isEmpty() }
        Scrobbles.idle()
        assertEquals(listOf("Seed 1"), listens())
        val all = logs.toList()
        val ready = all.indexOfFirst { it.startsWith("card state ready") }
        assertTrue(ready > 0)
        assertEquals(0, all.subList(0, ready).count { it.startsWith("card state busy") })
        assertEquals(1, all.subList(0, ready).count { " tx PLAYLOG_GET flags=0x03 " in it })
        assertEquals(1, all.subList(ready, all.size).count { it.startsWith("playlog get after 0") })
    }

    @Test
    fun anAckRefusedForTheCardThenAGetOfTheSameEntriesGivesOneLine() {
        val s = start(faults = listOf("fail=PLAYLOG_ACK:14:1"), seed = seed(1), live = false)
        waitFor(20_000) { logs.any { "rx PLAYLOG_ACK" in it } }
        waitFor(20_000) { listens().isNotEmpty() }
        assertEquals(1, s.device.plays.size)
        s.current!!.close("test")
        reconnect(1)
        waitFor(20_000) { s.device.plays.isEmpty() }
        Scrobbles.idle()
        assertEquals(listOf("Seed 1"), listens())
        assertEquals(2, logs.count { it.startsWith("playlog get after 0") })
    }

    @Test
    fun aNewLogIdAfterACursorOf5StoresAllThirty() {
        val s = start(faults = listOf("newlog"), seed = seed(5))
        waitFor { s.device.plays.isEmpty() && listens().size == 5 }
        seed(30, from = 6).forEach { s.device.logPlay(it) }
        s.current!!.close("test")
        reconnect(1)
        waitFor(20_000) { logs.any { it.startsWith("playlog new log") } && s.device.plays.isEmpty() }
        waitFor { listens().size == 35 }
        assertEquals((1..35).map { "Seed $it" }, listens())
    }

    @Test
    fun withCardLogTheLogIsStillReadAndThePlayAddsNothing() {
        val s = start()
        Remote.set("scrobble", "card")
        waitFor { Remote.device.settings.values["scrobble"] == "card" }
        s.device.logPlay(seed(1).single())
        playShort()
        cross()
        waitFor(20_000) { listens().isNotEmpty() && s.device.plays.isEmpty() }
        seenPastTheCrossing()
        assertEquals(listOf("Seed 1"), listens())
        assertTrue(s.device.plays.isEmpty())
        assertTrue(logs.none { it.startsWith("playlog append") })
    }

    @Test
    fun aLiveSeenEntryIsAckedWithNoLine() {
        val s = start(seed = seed(1, liveSeen = true) + seed(1, from = 2))
        waitFor { s.device.plays.isEmpty() && listens().isNotEmpty() }
        Scrobbles.idle()
        assertEquals(listOf("Seed 2"), listens())
    }

    @Test
    fun aCrossingWhileAnOldAppIsLinkedIsAckedByTheNewAppWithNoLine() {
        val (s, port) = sim()
        val old = oldPeer(port)
        assertTrue(old.call(Msg.PLAY, Req.play(Enums.View.songs, "", null, 0, false, listOf(short.id), null)) is Session.Result.Ok)
        skew.addAndGet(CROSS_MS)
        waitFor { s.device.plays.isNotEmpty() }
        assertEquals(true, s.device.plays.single().liveSeen)
        old.close("test")
        link(port, live = false)
        waitFor(20_000) { Remote.phase == Phase.LIVE && s.device.plays.isEmpty() }
        Scrobbles.idle()
        assertEquals(emptyList<String>(), listens())
        assertTrue(logs.any { it.startsWith("playlog append seq 1 Lucky liveSeen") })
    }

    companion object {
        const val CROSS_MS = 16_000L
    }
}
