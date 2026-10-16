package net.seilent.funecomp.artist

import net.seilent.funecomp.link.ImagePush
import net.seilent.funecomp.link.Session
import net.seilent.funecomp.link.TcpTransport
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32

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

    private fun sim(vararg faults: String, board: String = "ap80"): Pair<Sim, Int> {
        val s = Sim(Options(Board.of(board), Faults(faults.toList()), 30_000, 5_000, 5_000))
        sims += s
        return s to s.listen(0)
    }

    private fun session(port: Int): Session {
        val ready = CountDownLatch(1)
        val s = Session(TcpTransport("127.0.0.1", port), object : Session.Listener {
            override fun onHello(hello: HelloReply) = ready.countDown()
        })
        sessions += s
        Thread { s.run() }.apply { isDaemon = true }.start()
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        return s
    }

    private fun noise(w: Int, h: Int, seed: Long) = Backdrop.render(AwtCodec(), Images.png(Images.noise(w, h, seed)), w, h)!!

    private fun art(name: String, jpeg: ByteArray, w: Int, h: Int) = Art(jpeg, w, h, name, null, "test", null)

    @Test
    fun threeArtistsFreeYoutubeAndNothingFound() {
        val time = FakeTime()
        val http = Fixtures()
        val lookup = ArtistArt(http, AwtCodec(), ArtCache(tmp.newFolder()), { Sources(youtube = true) }, "0.1.0", { time.now }, time.sleep, { time.now })
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

        val again = ArtPusher(s, { _, _, _, _ -> throw AssertionError("lookup for a stored artist") }) { true }.run(order.take(2))
        assertEquals(listOf("宇多田ヒカル", "コバソロ"), again.stored)
        assertEquals(setOf("宇多田ヒカル", "コバソロ"), ArtPusher(s, { _, _, _, _ -> null }) { true }.stored()!!.toSet())
    }

    @Test
    fun v1GetsItsOwnSize() {
        val (sim, port) = sim(board = "v1")
        val s = session(port)
        val sizes = ArrayList<Pair<Int, Int>>()
        val r = ArtPusher(s, { n, _, w, h ->
            sizes += w to h
            art(n, noise(w, h, 3), w, h)
        }) { true }.run(listOf("Mili" to null))
        assertEquals(ArtPusher.Stop.DONE, r.stop)
        assertEquals(listOf(240 to 320), sizes)
        assertTrue(sim.device.stored.containsKey("mili"))
    }

    @Test
    fun closedGateStopsBeforeAnyLookup() {
        val (sim, port) = sim()
        val s = session(port)
        val r = ArtPusher(s, { _, _, _, _ -> throw AssertionError("lookup while gated") }) { false }.run(listOf("Ado" to null))
        assertEquals(ArtPusher.Stop.GATE, r.stop)
        assertTrue(sim.device.stored.isEmpty())
    }

    @Test
    fun gateClosingDuringTheLookupStopsBeforeThePush() {
        val (sim, port) = sim()
        val s = session(port)
        var open = true
        val r = ArtPusher(s, { n, _, w, h ->
            open = false
            art(n, noise(w, h, 4), w, h)
        }) { open }.run(listOf("Ado" to null, "Mili" to null))
        assertEquals(ArtPusher.Stop.GATE, r.stop)
        assertTrue(r.pushed.isEmpty())
        assertTrue(sim.device.stored.isEmpty())
    }

    @Test
    fun gateClosingMidPushStopsAndThePartialResumesLater() {
        val (sim, port) = sim()
        val s = session(port)
        val img = noise(360, 480, 6)
        var chunks = 0
        val lookup = { n: String, _: String?, w: Int, h: Int -> art(n, img, w, h) }
        val r = ArtPusher(s, lookup) { chunks++ < 4 }.run(listOf("Ado" to null))
        assertEquals(ArtPusher.Stop.GATE, r.stop)
        assertTrue(sim.device.stored.isEmpty())
        val again = ArtPusher(s, lookup) { true }.run(listOf("Ado" to null))
        assertEquals(ArtPusher.Stop.DONE, again.stop)
        assertTrue("from ${again.pushed["Ado"]}", again.pushed.getValue("Ado") > 0)
        assertEquals(CRC32().apply { update(img) }.value, sim.device.stored.getValue("ado").crc)
    }

    @Test
    fun rejectedImageSkipsThatArtistOnly() {
        val (sim, port) = sim()
        val s = session(port)
        val r = ArtPusher(s, { n, _, w, h ->
            if (n == "Bad") art(n, noise(256, 320, 1), w, h) else art(n, noise(w, h, 2), w, h)
        }) { true }.run(listOf("Bad" to null, "Good" to null))
        assertEquals(ArtPusher.Stop.DONE, r.stop)
        assertEquals(listOf("Bad"), r.failed)
        assertEquals(listOf("Good"), r.pushed.keys.toList())
        assertEquals(setOf("good"), sim.device.stored.keys)
    }

    @Test
    fun lookupCrashSkipsThatArtistOnly() {
        val (_, port) = sim()
        val s = session(port)
        val r = ArtPusher(s, { n, _, w, h ->
            if (n == "Bad") throw IllegalStateException("decoder") else art(n, noise(w, h, 2), w, h)
        }) { true }.run(listOf("Bad" to null, "Good" to null))
        assertEquals(listOf("Bad"), r.failed)
        assertEquals(listOf("Good"), r.pushed.keys.toList())
    }

    @Test
    fun interruptDuringTheLookupStopsTheRun() {
        val (sim, port) = sim()
        val s = session(port)
        val looked = ArrayList<String>()
        val time = FakeTime()
        val lookup = ArtistArt(Fixtures(), AwtCodec(), ArtCache(tmp.newFolder()), { Sources() }, "0.1.0", { time.now }, { throw InterruptedException("sleep") }, { time.now })
        var thrown: Throwable? = null
        val t = Thread {
            try {
                ArtPusher(s, { n, m, w, h ->
                    looked += n
                    lookup.get(n, m, w, h)
                }) { true }.run(listOf("宇多田ヒカル" to null, "Radiohead" to null))
            } catch (e: Throwable) {
                thrown = e
            }
        }
        t.start()
        t.join(10_000)
        assertTrue(thrown is InterruptedException)
        assertEquals(listOf("宇多田ヒカル"), looked)
        assertTrue(sim.device.stored.isEmpty())
    }

    @Test
    fun storedSkipIsByExactNameAndTheDeviceAnswersCaseVariants() {
        val (_, port) = sim()
        val s = session(port)
        val img = noise(360, 480, 8)
        var lookups = 0
        val lookup = { n: String, _: String?, w: Int, h: Int ->
            lookups++
            art(n, img, w, h)
        }
        ArtPusher(s, lookup) { true }.run(listOf("Sigur Rós" to null))
        val r = ArtPusher(s, lookup) { true }.run(listOf("Sigur Rós" to null, "SIGUR RÓS" to null))
        assertEquals(listOf("Sigur Rós", "SIGUR RÓS"), r.stored)
        assertEquals(2, lookups)
    }

    @Test
    fun pushResumesAfterTheLinkDropsMidImage() {
        val (sim, port) = sim("drop-after-bytes=131072")
        val img = noise(360, 480, 5)
        assertTrue("${img.size}", img.size > 140_000)
        val lookup = { n: String, _: String?, w: Int, h: Int -> art(n, img, w, h) }
        val first = ArtPusher(session(port), lookup) { true }.run(listOf("Sigur Rós" to null))
        assertEquals(ArtPusher.Stop.CLOSED, first.stop)
        assertTrue(sim.device.stored.isEmpty())
        var from = 0L
        var stop = ArtPusher.Stop.CLOSED
        repeat(4) {
            if (stop == ArtPusher.Stop.DONE) return@repeat
            val r = ArtPusher(session(port), lookup) { true }.run(listOf("Sigur Rós" to null))
            stop = r.stop
            r.pushed["Sigur Rós"]?.let { from = it }
        }
        assertEquals(ArtPusher.Stop.DONE, stop)
        assertTrue("resumed from $from", from > 0)
        assertEquals(CRC32().apply { update(img) }.value, sim.device.stored.getValue("sigur rós").crc)
    }

    @Test
    fun prefixCrcMismatchRestartsAtZero() {
        val (sim, port) = sim()
        val img = noise(360, 480, 9)
        val crc = CRC32().apply { update(img) }.value
        val s = session(port)
        val begin = s.call(Msg.IMG_BEGIN, Req.imgBegin(Enums.ImgKind.artist, "Ichiko Aoba", null, img.size.toLong(), crc, 360, 480))
        assertTrue(begin is Session.Result.Ok)
        val chunk = s.hello!!.maxPayload - 16
        val junk = ByteArray(chunk * 3) { 0x55 }
        for (i in 0 until 3) assertTrue(s.push(Msg.IMG_DATA, Req.imgData(i.toLong() * chunk, junk.copyOfRange(i * chunk, (i + 1) * chunk))))
        assertTrue(s.call(Msg.IMG_LIST, Req.imgList(0, 10)) is Session.Result.Ok)
        val r = ImagePush(s, "Ichiko Aoba", null, img, 360, 480).run()
        assertTrue("got $r", r is ImagePush.Outcome.Done)
        assertEquals(0L, (r as ImagePush.Outcome.Done).from)
        assertEquals(crc, sim.device.stored.getValue("ichiko aoba").crc)
    }
}
