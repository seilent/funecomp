package net.seilent.funecomp.link

import net.seilent.funecomp.proto.ErrorReply
import net.seilent.funecomp.proto.Frame
import net.seilent.funecomp.proto.FrameReader
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.ImgAbort
import net.seilent.funecomp.proto.ImgBeginReply
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ImagePushTest {
    private class Device(private val abortAfterBegin: Boolean) : Transport {
        private val inbound = LinkedBlockingQueue<ByteArray>()
        private val reader = FrameReader()
        private var pending: ByteArray? = null
        val got: MutableList<Int> = java.util.concurrent.CopyOnWriteArrayList()
        @Volatile private var closed = false
        @Volatile private var abortRead = false
        @Volatile private var abortBuf: ByteArray? = null
        private val abortHandled = CountDownLatch(1)

        override fun open() {}

        override fun read(buf: ByteArray): Int {
            if (abortRead && pending == null) abortHandled.countDown()
            var b = pending
            while (b == null) {
                if (closed) return -1
                b = inbound.poll(50, TimeUnit.MILLISECONDS)
            }
            val n = minOf(buf.size, b.size)
            b.copyInto(buf, 0, 0, n)
            pending = if (n < b.size) b.copyOfRange(n, b.size) else null
            if (b === abortBuf && pending == null) abortRead = true
            return n
        }

        override fun write(bytes: ByteArray) {
            if (closed) throw IOException("closed")
            for (f in reader.feed(bytes)) {
                if (f.type == Msg.IMG_DATA && abortAfterBegin) abortHandled.await(5, TimeUnit.SECONDS)
                got += f.type
                if (!f.wantsReply) continue
                val r = when (f.type) {
                    Msg.HELLO -> reply(f, HelloReply(Proto.major, Proto.minor, "ap80", "fake", 360, 480, 360, 480, Proto.maxPayload, 30_000, 5_000, Groups.all, 1, 1).encode())
                    Msg.IMG_BEGIN -> {
                        val out = reply(f, ImgBeginReply(0, 0).encode())
                        if (abortAfterBegin) (out + Frame(Msg.IMG_ABORT, 0, 1, ImgAbort("Mili", 0, Enums.AbortCode.noSpace).encode()).encode()).also { abortBuf = it } else out
                    }
                    else -> Frame(f.type, Proto.flagReply or Proto.flagError, f.seq, ErrorReply(Err.refused, "aborted").encode()).encode()
                }
                inbound.put(r)
            }
        }

        private fun reply(f: Frame, payload: ByteArray) = Frame(f.type, Proto.flagReply, f.seq, payload).encode()

        override fun close() {
            closed = true
        }
    }

    private fun session(d: Device): Session {
        val ready = CountDownLatch(1)
        val s = Session(d, object : Session.Listener {
            override fun onHello(hello: HelloReply) = ready.countDown()
        }, Session.Config("test"))
        Thread { s.run() }.apply { isDaemon = true }.start()
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        return s
    }

    @Test
    fun abortRightAfterTheBeginReplyIsKept() {
        repeat(20) {
            val d = Device(abortAfterBegin = true)
            val s = session(d)
            val o = ImagePush(s, "Mili", null, ByteArray(100_000) { 1 }, 360, 480).run()
            assertTrue("run $it got $o", o is ImagePush.Outcome.Aborted)
            assertEquals(Enums.AbortCode.noSpace, (o as ImagePush.Outcome.Aborted).abort.code)
            assertTrue(Msg.IMG_END !in d.got)
            s.close("done")
        }
    }

    @Test
    fun closedGateStopsBetweenChunks() {
        val d = Device(abortAfterBegin = false)
        val s = session(d)
        var open = 2
        val o = ImagePush(s, "Mili", null, ByteArray(100_000) { 1 }, 360, 480) { open-- > 0 }.run()
        assertTrue("got $o", o is ImagePush.Outcome.Gated)
        assertEquals(2, (o as ImagePush.Outcome.Gated).chunks)
        val end = System.nanoTime() + 2_000_000_000L
        while (d.got.count { it == Msg.IMG_DATA } < 2 && System.nanoTime() < end) Thread.sleep(10)
        Thread.sleep(100)
        assertEquals(2, d.got.count { it == Msg.IMG_DATA })
        assertTrue(Msg.IMG_END !in d.got)
        s.close("done")
    }
}
