package net.seilent.funecomp.link

import net.seilent.funecomp.proto.Bye
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.SetReply
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.state.PushGate
import net.seilent.funecomp.state.Reconnect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32

class LinkTest {
    private val sims = ArrayList<Sim>()
    private val links = ArrayList<Link>()

    @After
    fun tearDown() {
        links.forEach { it.stop() }
        sims.forEach { it.stop() }
    }

    private fun now() = System.nanoTime() / 1_000_000

    private fun sim(vararg faults: String, board: String = "ap80", idle: Long = 30_000, busy: Long = 5_000, hello: Long = 5_000): Int {
        val s = Sim(Options(Board.of(board), Faults(faults.toList()), idle, busy, hello))
        sims += s
        return s.listen(0)
    }

    private class Recorder : Link.Listener {
        @Volatile var state = DeviceState()
        val hello = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val byes: MutableList<Bye> = Collections.synchronizedList(ArrayList())
        val attempts: MutableList<Long> = Collections.synchronizedList(ArrayList())
        val stops: MutableList<Reconnect.Stop> = Collections.synchronizedList(ArrayList())
        val pushes: MutableList<Int> = Collections.synchronizedList(ArrayList())
        @Volatile var reason: String? = null
        @Volatile var helloAt = 0L
        @Volatile var closedAt = 0L

        override fun onHello(hello: HelloReply) {
            synchronized(this) { state = state.withHello(hello) }
            helloAt = System.nanoTime() / 1_000_000
            this.hello.countDown()
        }

        override fun onPush(type: Int, body: Node) {
            pushes += type
            synchronized(this) { state = state.reduce(type, body, System.nanoTime() / 1_000_000) }
        }

        override fun onBye(bye: Bye) {
            byes += bye
        }

        override fun onClosed(reason: String) {
            this.reason = reason
            closedAt = System.nanoTime() / 1_000_000
            closed.countDown()
        }

        override fun onAttempt(at: Long) {
            attempts += at
        }

        override fun onStopped(stop: Reconnect.Stop) {
            stops += stop
        }
    }

    private class Slow(private val inner: Transport, private val bytesPerSecond: Long) : Transport by inner {
        override fun write(bytes: ByteArray) {
            inner.write(bytes)
            Thread.sleep(bytes.size * 1000L / bytesPerSecond)
        }
    }

    private fun session(port: Int, rec: Recorder, config: Session.Config = Session.Config(), bps: Long? = null): Session {
        val tcp = TcpTransport("127.0.0.1", port)
        val s = Session(bps?.let { Slow(tcp, it) } ?: tcp, rec, config)
        Thread { s.run() }.apply { isDaemon = true }.start()
        return s
    }

    private fun waitFor(ms: Long = 5000, cond: () -> Boolean) {
        val end = now() + ms
        while (!cond()) {
            if (now() > end) throw AssertionError("timed out")
            Thread.sleep(10)
        }
    }

    @Test
    fun handshakeMirrorsTheDeviceAndRunsCommands() {
        val rec = Recorder()
        val s = session(sim(), rec)
        assertTrue(rec.hello.await(5, TimeUnit.SECONDS))
        val h = s.hello!!
        assertEquals("ap80", h.board)
        assertEquals(384, h.backdropW)
        assertTrue(h.has("settings") && h.has("eq") && h.has("library") && h.has("queue") && h.has("image"))
        waitFor { rec.state.schema.rev != null && rec.state.eq.values.count { it.complete } == 2 && rec.state.settings.rev > 0 }
        val filter = rec.state.schema.rows.first { it.key == "digitalFilter" }
        assertEquals(8, filter.values.size)
        assertEquals("auto", rec.state.settings.values["output"])
        assertEquals(200, rec.state.eq.getValue(Enums.EqOutput.wired).names.size)
        assertTrue(rec.pushes.count { it == Msg.EQ } >= 4)

        val first = rec.state.playback.state.track
        assertTrue(s.call(Msg.TRANSPORT, Req.transport(Enums.TransportCmd.next)) is Session.Result.Ok)
        waitFor { rec.state.playback.state.track != first }
        val vol = rec.state.playback.state.volume!!
        assertTrue(s.call(Msg.VOLUME, Req.volume(step = 3)) is Session.Result.Ok)
        waitFor { rec.state.playback.state.volume == vol + 3 }
        assertTrue(s.call(Msg.VOLUME, Req.volume(set = 999)) is Session.Result.Ok)
        waitFor { rec.state.playback.state.volume == rec.state.playback.state.maxVolume }

        val set = s.call(Msg.SET, Req.set("balance=4\nvolumeStep=9\nbtCodec=ldac\ncompanion=on\n")) as Session.Result.Ok
        val r = SetReply.from(set.body)
        assertEquals(listOf("balance", "volumeStep"), r.kept)
        assertEquals(listOf("btCodec", "companion"), r.refused)
        waitFor { rec.state.settings.values["volumeStep"] == "10" && rec.state.settings.values["balance"] == "4" }
        val all = s.call(Msg.SET, Req.set("btLastPhone=00:11:22:33:44:55\n")) as Session.Result.Failed
        assertEquals(Err.refused, all.error.code)
        assertEquals(listOf("btLastPhone"), all.error.refused)

        assertTrue(s.call(Msg.LIB_GET, Req.libGet(Enums.View.artists, "", 0, 100, h.libRev)) is Session.Result.Ok)
        waitFor { rec.state.lib.pages.isNotEmpty() }
        val stale = s.call(Msg.LIB_GET, Req.libGet(Enums.View.artists, "", 0, 100, 999)) as Session.Result.Failed
        assertEquals(Err.staleRev, stale.error.code)
        s.close("done")
    }

    @Test
    fun v1BoardSendsItsOwnRows() {
        val rec = Recorder()
        val s = session(sim(board = "v1"), rec)
        assertTrue(rec.hello.await(5, TimeUnit.SECONDS))
        assertEquals("v1", s.hello!!.board)
        assertEquals(256, s.hello!!.backdropW)
        waitFor { rec.state.schema.rev != null }
        assertEquals(4, rec.state.schema.rows.first { it.key == "digitalFilter" }.values.size)
        s.close("done")
    }

    @Test
    fun helloTimeoutClosesTheApp() {
        val rec = Recorder()
        val t0 = now()
        session(sim("stall-ms=1500"), rec, Session.Config(helloTimeoutMs = 300))
        assertTrue(rec.closed.await(3, TimeUnit.SECONDS))
        assertEquals("helloTimeout", rec.reason)
        assertTrue(rec.closedAt - t0 < 3000)
        assertEquals(1L, rec.hello.count)
    }

    @Test
    fun helloTimeoutClosesTheDevice() {
        val port = sim(hello = 300)
        Socket().use { sock ->
            sock.connect(InetSocketAddress("127.0.0.1", port))
            sock.soTimeout = 3000
            val t0 = now()
            assertEquals(-1, sock.getInputStream().read())
            assertTrue(now() - t0 < 2500)
        }
    }

    @Test
    fun pingTimeoutClosesADeadLink() {
        val rec = Recorder()
        session(sim("no-read", idle = 200, busy = 100), rec)
        assertTrue(rec.hello.await(5, TimeUnit.SECONDS))
        assertTrue(rec.closed.await(5, TimeUnit.SECONDS))
        assertEquals("dead", rec.reason)
        val took = rec.closedAt - rec.helloAt
        assertTrue("took $took", took in 400..4000)
    }

    @Test
    fun fullSendBufferStillClosesAsDead() {
        val rec = Recorder()
        val s = session(sim("no-read", idle = 200, busy = 100), rec)
        assertTrue(rec.hello.await(5, TimeUnit.SECONDS))
        var sent = 0L
        val pusher = Thread {
            while (s.push(Msg.IMG_DATA, Req.imgData(0, ByteArray(16000)))) sent += 16000
        }.apply {
            isDaemon = true
            start()
        }
        assertTrue(rec.closed.await(5, TimeUnit.SECONDS))
        assertEquals("dead", rec.reason)
        val took = rec.closedAt - rec.helloAt
        assertTrue("took $took", took in 400..2500)
        pusher.join(2000)
        assertFalse(pusher.isAlive)
        assertTrue(sent > 0)
    }

    @Test
    fun requestsWaitForTheHelloReply() {
        val rec = Recorder()
        val s = session(sim("stall-ms=500"), rec)
        Thread.sleep(100)
        assertTrue(s.call(Msg.VOLUME, Req.volume(step = 1)) is Session.Result.NotReady)
        assertTrue(rec.hello.await(5, TimeUnit.SECONDS))
        assertTrue(s.call(Msg.VOLUME, Req.volume(step = 1)) is Session.Result.Ok)
        s.close("done")
    }

    @Test
    fun badValueIsAFailureWithItsCode() {
        val rec = Recorder()
        val s = session(sim(), rec)
        assertTrue(rec.hello.await(5, TimeUnit.SECONDS))
        val eq = s.call(Msg.EQ_SET, Req.eqSet(Enums.EqOutput.wired, "nope"))
        assertEquals(Err.badValue, (eq as Session.Result.Failed).error.code)
        val seek = s.call(Msg.TRANSPORT, Req.transport(Enums.TransportCmd.seek, seekMs = 99_999_999))
        assertEquals(Err.badValue, (seek as Session.Result.Failed).error.code)
        s.close("done")
    }

    @Test
    fun callOnASessionThreadFailsFast() {
        val seen = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val got = CountDownLatch(1)
        lateinit var s: Session
        val rec = object : Session.Listener {
            override fun onHello(hello: HelloReply) {
                try {
                    s.call(Msg.VOLUME, Req.volume(step = 1))
                } catch (e: IllegalStateException) {
                    seen.set(e)
                }
                got.countDown()
            }
        }
        s = Session(TcpTransport("127.0.0.1", sim()), rec)
        Thread { s.run() }.apply { isDaemon = true }.start()
        assertTrue(got.await(5, TimeUnit.SECONDS))
        assertNotNull(seen.get())
        s.close("done")
    }

    @Test
    fun requestTimeoutPingsThenCloses() {
        val rec = Recorder()
        val s = session(sim("no-read", idle = 30_000, busy = 400), rec, Session.Config(requestTimeoutMs = 300))
        assertTrue(rec.hello.await(5, TimeUnit.SECONDS))
        val t0 = now()
        val r = s.call(Msg.VOLUME, Req.volume(step = 1))
        assertTrue(r is Session.Result.Timeout)
        assertTrue(rec.closed.await(5, TimeUnit.SECONDS))
        assertEquals("requestTimeout", rec.reason)
        val took = rec.closedAt - t0
        assertTrue("took $took", took in 500..3000)
    }

    private fun link(port: Int, rec: Recorder, steps: List<Long> = listOf(50, 100, 200)): Link {
        val l = Link({ TcpTransport("127.0.0.1", port) }, rec, Reconnect(steps, 0.0))
        links += l
        l.start()
        return l
    }

    @Test
    fun byeVersionStopsReconnect() {
        val rec = Recorder()
        val l = link(sim("major=2"), rec)
        waitFor { rec.stops.isNotEmpty() }
        assertEquals(Reconnect.Stop.VERSION, rec.stops.single())
        assertEquals(Enums.ByeReason.version, rec.byes.single().reason)
        assertEquals(2, rec.byes.single().major)
        Thread.sleep(500)
        assertEquals(1, rec.attempts.size)
        assertEquals(Reconnect.Stop.VERSION, l.stopped)
    }

    @Test
    fun byeReplacedStopsUntilForeground() {
        val rec = Recorder()
        val l = link(sim("bye=replaced"), rec)
        waitFor { rec.stops.size == 1 }
        Thread.sleep(400)
        assertEquals(1, rec.attempts.size)
        l.foreground()
        waitFor { rec.stops.size == 2 }
        assertEquals(2, rec.attempts.size)
        assertEquals(Enums.ByeReason.replaced, rec.byes.last().reason)
    }

    @Test
    fun byeShutdownKeepsReconnecting() {
        val rec = Recorder()
        link(sim("bye=shutdown"), rec)
        waitFor { rec.attempts.size >= 3 }
        assertTrue(rec.stops.isEmpty())
    }

    @Test
    fun backoffTimings() {
        val free = ServerSocket(0).use { it.localPort }
        val rec = Recorder()
        link(free, rec, steps = listOf(150, 300, 600))
        waitFor(4000) { rec.attempts.size >= 4 }
        val gaps = rec.attempts.zipWithNext { a, b -> b - a }
        for ((gap, want) in gaps.take(3).zip(listOf(150L, 300L, 600L))) {
            assertTrue("gap $gap want $want", gap in want - 30..want + 400)
        }
    }

    @Test
    fun anotherConnectionReplacesTheSession() {
        val port = sim()
        val a = Recorder()
        session(port, a)
        assertTrue(a.hello.await(5, TimeUnit.SECONDS))
        val b = Recorder()
        session(port, b)
        assertTrue(b.hello.await(5, TimeUnit.SECONDS))
        assertTrue(a.closed.await(5, TimeUnit.SECONDS))
        assertEquals(Enums.ByeReason.replaced, a.byes.single().reason)
    }

    private fun jpeg(size: Int, w: Int = 384, h: Int = 480, seed: Int = 1): ByteArray {
        val head = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xC0.toByte(), 0, 17, 8,
            (h shr 8).toByte(), h.toByte(), (w shr 8).toByte(), w.toByte(), 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1,
        )
        val out = ByteArray(size)
        head.copyInto(out)
        for (i in head.size until size - 2) out[i] = ((i * 31 + seed) and 0x7F).toByte()
        out[size - 2] = 0xFF.toByte()
        out[size - 1] = 0xD9.toByte()
        return out
    }

    @Test
    fun imgAbortStopsDataThenPushResumes() {
        val port = sim("abort-at=40000")
        val rec = Recorder()
        val s = session(port, rec, bps = 400_000)
        assertTrue(rec.hello.await(5, TimeUnit.SECONDS))
        val img = jpeg(200_000)
        val first = ImagePush(s, "宇多田ヒカル", null, img, 384, 480).run()
        assertTrue("got $first", first is ImagePush.Outcome.Aborted)
        first as ImagePush.Outcome.Aborted
        assertEquals(Enums.AbortCode.writeError, first.abort.code)
        val chunk = s.hello!!.maxPayload - 16
        assertTrue(first.chunks.toLong() * chunk < img.size)
        val sim = sims.single()
        assertTrue("dropped ${sim.device.droppedData}", sim.device.droppedData < first.chunks)

        val second = ImagePush(s, "宇多田ヒカル", null, img, 384, 480).run()
        assertTrue("got $second", second is ImagePush.Outcome.Done)
        second as ImagePush.Outcome.Done
        assertTrue(second.from > 0 && second.from % chunk == 0L)
        val stored = sim.device.stored.getValue("宇多田ヒカル")
        assertEquals(CRC32().apply { update(img) }.value, stored.crc)

        val again = ImagePush(s, "宇多田ヒカル ", null, img, 384, 480).run()
        assertTrue(again is ImagePush.Outcome.Have)
        s.close("done")
    }

    @Test
    fun imgEndChecksBaselineAndSize() {
        val rec = Recorder()
        val s = session(sim(), rec)
        assertTrue(rec.hello.await(5, TimeUnit.SECONDS))
        val wrong = ImagePush(s, "Ichiko Aoba", null, jpeg(30_000, w = 360), 384, 480).run()
        assertEquals(Err.badValue, (wrong as ImagePush.Outcome.Failed).error!!.code)
        val big = ImagePush(s, "Ichiko Aoba", null, jpeg(300_000), 384, 480).run()
        assertEquals(Err.tooBig, (big as ImagePush.Outcome.Failed).error!!.code)
        s.close("done")
    }

    @Test
    fun restartDropsPartials() {
        val port = sim("abort-at=40000", "restart")
        val img = jpeg(120_000)
        val a = Recorder()
        val s1 = session(port, a)
        assertTrue(a.hello.await(5, TimeUnit.SECONDS))
        assertTrue(ImagePush(s1, "Sigur Rós", null, img, 384, 480).run() is ImagePush.Outcome.Aborted)
        s1.close("done")
        val b = Recorder()
        val s2 = session(port, b)
        assertTrue(b.hello.await(5, TimeUnit.SECONDS))
        val done = ImagePush(s2, "Sigur Rós", null, img, 384, 480).run() as ImagePush.Outcome.Done
        assertEquals(0L, done.from)
        s2.close("done")
    }

    @Test
    fun unknownEnumInAPushIsKept() {
        val rec = Recorder()
        val s = session(sim("enum=STATE.output=9"), rec)
        assertTrue(rec.hello.await(5, TimeUnit.SECONDS))
        waitFor { rec.state.playback.state.track != null }
        assertEquals(9, rec.state.playback.state.output)
        assertFalse(PushGate.open(rec.state, now()))
        assertTrue(s.call(Msg.VOLUME, Req.volume(step = 1)) is Session.Result.Ok)
        assertNull(s.closedReason)
        s.close("done")
    }

    @Test
    fun featureBitsGateRequestsAndPushes() {
        val rec = Recorder()
        val s = session(sim("features=0"), rec)
        assertTrue(rec.hello.await(5, TimeUnit.SECONDS))
        waitFor { rec.state.playback.state.track != null }
        assertTrue(s.call(Msg.SET, Req.set("balance=2\n")) is Session.Result.Unsupported)
        assertTrue(s.call(Msg.TRANSPORT, Req.transport(Enums.TransportCmd.play)) is Session.Result.Ok)
        Thread.sleep(200)
        assertTrue(rec.pushes.none { it == Msg.SCHEMA || it == Msg.SETTINGS || it == Msg.EQ })
        assertNotNull(s.hello)
        s.close("done")
    }
}
