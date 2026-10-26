package net.seilent.funecomp.link

import net.seilent.funecomp.proto.Frame
import net.seilent.funecomp.proto.FrameReader
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Tlv
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.proto.gen.Tags
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class RequestTest {
    private val sessions = ArrayList<Session>()

    @After
    fun tearDown() = sessions.forEach { it.close("done") }

    private fun vector(name: String): ByteArray {
        val hex = File("../third_party/funeos/vectors.txt").readLines().filter { it.startsWith("hex $name ") }.joinToString("") { it.substringAfter("hex $name ") }
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private class Peer : Transport {
        private val toApp = PipedOutputStream()
        private val input = PipedInputStream(toApp, 65536)
        private val reader = FrameReader()
        val frames = LinkedBlockingQueue<Frame>()
        val bytes = LinkedBlockingQueue<ByteArray>()

        override fun open() {}

        override fun read(buf: ByteArray): Int = try {
            input.read(buf)
        } catch (_: Exception) {
            -1
        }

        override fun write(bytes: ByteArray) {
            this.bytes += bytes
            reader.feed(bytes, 0, bytes.size).forEach { frames += it }
        }

        override fun close() {
            try {
                toApp.close()
            } catch (_: Exception) {
            }
        }

        fun send(b: ByteArray) {
            toApp.write(b)
            toApp.flush()
        }

        fun next(): Frame? = frames.poll(3, TimeUnit.SECONDS)
    }

    private class Asks(private val take: Boolean, private val answer: (Responder) -> Unit) : Session.Listener {
        val asked = LinkedBlockingQueue<Pair<Int, Node>>()
        val pushes = LinkedBlockingQueue<Int>()
        val hello = CountDownLatch(1)

        override fun onHello(hello: HelloReply) = this.hello.countDown()

        override fun onPush(type: Int, body: Node) {
            pushes += type
        }

        override fun onRequest(type: Int, body: Node, responder: Responder): Boolean {
            asked += type to body
            if (take) answer(responder)
            return take
        }
    }

    private fun reply(features: Long) = HelloReply(1, 6, "v1", "x", 240, 320, 240, 320, Proto.maxPayload, 30_000, 5_000, features, 1, 1)

    private val both = Groups.all

    private val app = Groups.mask("artBlock")

    private fun start(appFeatures: Long, devFeatures: Long?, listener: Asks): Pair<Session, Peer> {
        val p = Peer()
        val s = Session(p, listener, Session.Config("test", features = appFeatures))
        sessions += s
        Thread { s.run() }.apply { isDaemon = true }.start()
        val hello = p.next()!!
        assertEquals(Msg.HELLO, hello.type)
        assertEquals(appFeatures != 0L, Tags.Hello.features in Tlv.parse(hello.payload).map { it.tag })
        p.bytes.clear()
        if (devFeatures != null) {
            p.send(Frame(Msg.HELLO, Proto.flagReply, hello.seq, reply(devFeatures).encode()).encode())
            assertTrue(listener.hello.await(3, TimeUnit.SECONDS))
        }
        return s to p
    }

    @Test
    fun anArtBlockWithBothBitsReachesTheListenerAndRepliesOnce() {
        val held = LinkedBlockingQueue<Responder>()
        val l = Asks(true) { held += it }
        val (_, p) = start(app, both, l)
        p.send(vector("art_block"))
        val (type, body) = l.asked.poll(3, TimeUnit.SECONDS)!!
        assertEquals(Msg.ART_BLOCK, type)
        assertEquals("Utada Hikaru", body.str(Tags.ArtBlock.name))
        val r = held.poll(3, TimeUnit.SECONDS)!!
        Thread { r.ok() }.apply { start() }.join()
        assertArrayEquals(vector("art_block_reply"), p.bytes.poll(3, TimeUnit.SECONDS))
        r.ok()
        r.error(Err.staleRev, "stale rev")
        assertNull(p.bytes.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun aLiveReachesTheListenerOnlyWithTheDeviceBit() {
        val live = Groups.mask("settings", "liveRows")
        val on = Asks(false) {}
        val (_, p) = start(live, live, on)
        p.send(vector("live"))
        p.send(vector("state_comp2"))
        assertEquals(Msg.LIVE, on.pushes.poll(3, TimeUnit.SECONDS))
        assertEquals(Msg.STATE, on.pushes.poll(3, TimeUnit.SECONDS))
        val off = Asks(false) {}
        val (_, q) = start(live, Groups.mask("settings"), off)
        q.send(vector("live"))
        q.send(vector("state_comp2"))
        assertEquals(Msg.STATE, off.pushes.poll(3, TimeUnit.SECONDS))
        assertNull(off.pushes.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun anErrorAnswerMatchesTheVector() {
        val l = Asks(true) { it.error(Err.staleRev, "stale rev") }
        val (_, p) = start(app, both, l)
        p.send(vector("art_block"))
        assertArrayEquals(vector("art_block_error_stale"), p.bytes.poll(3, TimeUnit.SECONDS))
    }

    private fun unknown(appFeatures: Long, devFeatures: Long?, take: Boolean = true) {
        val l = Asks(take) { it.ok() }
        val (_, p) = start(appFeatures, devFeatures, l)
        p.send(vector("art_block"))
        val f = p.next()!!
        assertEquals(Msg.ART_BLOCK, f.type)
        assertTrue(f.isError)
        assertEquals(Err.unknown, Node.decode("error", f.payload).int(Tags.Error.code))
        assertEquals(take, l.asked.isEmpty())
    }

    @Test
    fun withoutTheAppBitTheDeviceBitOrAHelloTheAskIsUnknown() {
        unknown(0, both)
        unknown(app, both and app.inv())
        unknown(app, null)
        unknown(app, both, take = false)
    }

    @Test
    fun anAnswerAfterCloseSendsNothing() {
        val held = LinkedBlockingQueue<Responder>()
        val l = Asks(true) { held += it }
        val (s, p) = start(app, both, l)
        p.send(vector("art_block"))
        val r = held.poll(3, TimeUnit.SECONDS)!!
        s.close("test")
        r.ok()
        r.error(Err.busy, "busy")
        assertNull(p.bytes.poll(300, TimeUnit.MILLISECONDS))
        assertNotNull(s.closedReason)
    }
}
