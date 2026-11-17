package net.seilent.funecomp.sources

import net.seilent.funecomp.link.Session
import net.seilent.funecomp.link.TcpTransport
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.push.ArtPusher
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import net.seilent.funekit.artist.ArtCache
import net.seilent.funekit.artist.ArtistArt
import net.seilent.funekit.artist.AwtCodec
import net.seilent.funekit.artist.FakeTime
import net.seilent.funekit.artist.Fixtures
import net.seilent.funekit.artist.TEST_AGENT
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PushTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val sims = ArrayList<Sim>()
    private val sessions = ArrayList<Session>()

    @After
    fun tearDown() {
        sessions.forEach { it.close("done") }
        sims.forEach { it.stop() }
    }

    private fun sim(vararg faults: String, board: String = "ap80", minor: Int = Proto.minor): Pair<Sim, Int> {
        val s = Sim(Options(Board.of(board), Faults(faults.toList()), 30_000, 5_000, 5_000, minor = minor))
        sims += s
        return s to s.listen(0)
    }

    private fun session(port: Int): Session {
        val ready = CountDownLatch(1)
        val s = Session(TcpTransport("127.0.0.1", port), object : Session.Listener {
            override fun onHello(hello: HelloReply) = ready.countDown()
        }, Session.Config("test"))
        sessions += s
        Thread { s.run() }.apply { isDaemon = true }.start()
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        return s
    }

    @Test
    fun threeArtistsFreeYoutubeAndNothingFound() {
        val time = FakeTime()
        val http = Fixtures()
        val lookup = ArtistArt(http, AwtCodec(), ArtCache(tmp.newFolder()), { YouTubeArt.with(ArtSources.COMMONS, true) }, TEST_AGENT, { time.now }, time, extensions = listOf(YouTubeArt {}))
        val (sim, port) = sim()
        val s = session(port)
        val state = State(artists = listOf("宇多田ヒカル", "コバソロ"), mbids = listOf("", "4981caf4-856a-4a2f-bdf1-3cfd65c6a532"))
        val order = ArtPusher.order(state, listOf("Radiohead" to null))
        assertEquals(listOf("宇多田ヒカル" to null, "コバソロ" to "4981caf4-856a-4a2f-bdf1-3cfd65c6a532", "Radiohead" to null), order)
        val r = ArtPusher(s, lookup::get) { true }.run(order)
        assertEquals(ArtPusher.Stop.DONE, r.stop)
        assertEquals(listOf("宇多田ヒカル", "コバソロ"), r.pushed.keys.toList())
        assertEquals(listOf("Radiohead"), r.missing)
        val stored = sim.device.stored
        assertEquals(setOf("宇多田ヒカル", "コバソロ"), stored.keys)
        assertEquals("4981caf4-856a-4a2f-bdf1-3cfd65c6a532", stored.getValue("コバソロ").mbid)

        val have = ArtPusher(s, { _, _, _, _ -> null }) { true }.stored()!!
        assertEquals(setOf("宇多田ヒカル", "コバソロ"), have)
        val again = ArtPusher(s, { _, _, _, _ -> throw AssertionError("lookup for a stored artist") }) { true }.run(order.take(2), have)
        assertEquals(listOf("宇多田ヒカル", "コバソロ"), again.stored)
        val current = ArtPusher(s, lookup::get) { true }.run(order.take(2))
        assertEquals(listOf("宇多田ヒカル", "コバソロ"), current.stored)
        assertTrue(current.pushed.isEmpty())
    }
}
