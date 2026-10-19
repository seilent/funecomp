package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.ArtBlock
import net.seilent.funecomp.proto.ErrorReply
import net.seilent.funecomp.proto.Frame
import net.seilent.funecomp.proto.FrameReader
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.Hello
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import javax.imageio.ImageIO

class Proto16Test {
    private val sims = ArrayList<Sim>()
    private val apps = ArrayList<App>()
    private val logs: MutableList<String> = Collections.synchronizedList(ArrayList())

    @After
    fun tearDown() {
        apps.forEach { it.sock.close() }
        sims.forEach { it.stop() }
    }

    private fun sim(minor: Int = Proto.minor, vararg faults: String) =
        Sim(Options(Board.of("v1"), Faults(faults.toList()), minor = minor, log = { logs += it })).also { sims += it }

    private class App(port: Int, features: Long?, val answer: (Frame) -> Frame?) {
        val sock = Socket("127.0.0.1", port)
        val replies = LinkedBlockingQueue<Frame>()
        val asks = LinkedBlockingQueue<Frame>()
        private var seq = 1
        lateinit var hello: HelloReply

        init {
            Thread {
                val r = FrameReader()
                val buf = ByteArray(8192)
                try {
                    while (true) {
                        val n = sock.getInputStream().read(buf)
                        if (n < 0) break
                        for (f in r.feed(buf, 0, n)) {
                            when {
                                f.type == Msg.ART_BLOCK && !f.isReply -> {
                                    asks += f
                                    answer(f)?.let { send(it) }
                                }
                                f.isReply -> replies += f
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }.apply { isDaemon = true }.start()
            hello = HelloReply.from(Node.decode("HELLO.reply", call(Msg.HELLO, Hello(Proto.major, Proto.minor, "t", features).encode()).payload))
        }

        @Synchronized
        fun send(f: Frame) {
            sock.getOutputStream().write(f.encode())
            sock.getOutputStream().flush()
        }

        fun call(type: Int, payload: ByteArray): Frame {
            val s = seq++
            send(Frame(type, Proto.flagWant, s, payload))
            while (true) {
                val f = replies.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no reply to ${Msg.info[type]?.name}")
                if (f.type == type && f.seq == s) return f
            }
        }

        fun push(name: String, jpeg: ByteArray, wrong: Boolean? = null, source: String? = null, credit: String? = null): Frame {
            val crc = CRC32().apply { update(jpeg) }.value
            val begin = call(Msg.IMG_BEGIN, Req.imgBegin(Enums.ImgKind.artist, name, null, jpeg.size.toLong(), crc, 240, 320, wrong, source, credit))
            if (Node.decode("IMG_BEGIN.reply", begin.payload).long(1) == jpeg.size.toLong()) return begin
            send(Frame(Msg.IMG_DATA, 0, seq++, Req.imgData(0, jpeg)))
            return call(Msg.IMG_END, Req.imgEnd(crc))
        }
    }

    private fun app(port: Int, features: Long?, answer: (Frame) -> Frame? = { Frame(it.type, Proto.flagReply, it.seq) }) =
        App(port, features, answer).also { apps += it }

    private fun jpeg(seed: Int): ByteArray {
        val img = java.awt.image.BufferedImage(240, 320, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = java.awt.Color(seed * 40 % 256, 90, 140)
        g.fillRect(0, 0, 240, 320)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
    }

    private fun waitFor(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(20)
        }
    }

    private val bit = Groups.mask("artBlock")

    private fun logged(p: (String) -> Boolean) = synchronized(logs) { logs.any(p) }

    @Test
    fun aMinor5DeviceHasNoArtBlockBitAndNeverAsks() {
        val s = sim(5, "ask-block=blockImage@0")
        val a = app(s.listen(0), bit)
        assertFalse(a.hello.has("artBlock"))
        assertFalse(a.push("Ado", jpeg(1)).isError)
        Thread.sleep(300)
        assertTrue(a.asks.isEmpty())
        assertTrue(runCatching { s.askBlock(Enums.BlockCmd.blockImage, "Ado") }.isFailure)
    }

    @Test
    fun anAppWithoutTheBitGetsNoAsk() {
        val s = sim(Proto.minor, "ask-block=blockImage@0")
        val a = app(s.listen(0), null)
        assertTrue(a.hello.has("artBlock"))
        assertFalse(a.push("Ado", jpeg(1)).isError)
        Thread.sleep(300)
        assertTrue(a.asks.isEmpty())
        assertTrue(logged { it.endsWith("features=0") })
    }

    @Test
    fun theFaultAsksOnceForThePushedImageAndLogsTheReply() {
        val s = sim(Proto.minor, "ask-block=wrongArtist@50")
        val a = app(s.listen(0), bit) { Frame(it.type, Proto.flagReply or Proto.flagError, it.seq, ErrorReply(Err.staleRev, "stale rev").encode()) }
        val img = jpeg(1)
        assertFalse(a.push("Ado", img, false, "commons", "Jane Doe").isError)
        assertFalse(a.push("Mili", jpeg(2)).isError)
        val f = a.asks.poll(3, TimeUnit.SECONDS)!!
        val q = ArtBlock.from(Node.decode("ART_BLOCK", f.payload))
        assertEquals(ArtBlock(Enums.BlockCmd.wrongArtist, "Ado", null, CRC32().apply { update(img) }.value), q)
        waitFor { logged { it.endsWith("ask ART_BLOCK seq=${f.seq} -> error 7 stale rev") } }
        assertFalse(a.push("Ado", jpeg(5)).isError)
        Thread.sleep(300)
        assertNull(a.asks.poll())
    }

    @Test
    fun askBlockSendsTheStoredEntryAndReturnsTheAnswer() {
        val s = sim()
        val a = app(s.listen(0), bit)
        val img = jpeg(3)
        assertFalse(a.push("Ado", img, true, "deezer", null).isError)
        assertEquals(Device.Meta(true, "deezer", null), s.device.stored.values.single().meta)
        assertEquals("ok", s.askBlock(Enums.BlockCmd.blockImage, "ado"))
        val q = ArtBlock.from(Node.decode("ART_BLOCK", a.asks.poll(1, TimeUnit.SECONDS)!!.payload))
        assertEquals(ArtBlock(Enums.BlockCmd.blockImage, "Ado", null, CRC32().apply { update(img) }.value), q)
        assertEquals("ok", s.askBlock(s.block(Enums.BlockCmd.blockImage, "Ado").copy(crc = 1)))
        assertEquals(1L, ArtBlock.from(Node.decode("ART_BLOCK", a.asks.poll(1, TimeUnit.SECONDS)!!.payload)).crc)
        assertTrue(logged { it.contains("ask ART_BLOCK") && it.endsWith("-> ok") })
    }

    @Test
    fun aRepeatedBeginRewritesTheStoredMeta() {
        val s = sim()
        val a = app(s.listen(0), bit)
        val img = jpeg(4)
        a.push("Ado", img, true, "commons", "Jane Doe")
        assertEquals(Device.Meta(true, "commons", "Jane Doe"), s.device.stored.values.single().meta)
        a.push("Ado", img, false, "deezer", null)
        assertEquals(Device.Meta(false, "deezer", null), s.device.stored.values.single().meta)
        assertTrue(logs.contains("meta name=Ado wrong=0 source=deezer credit="))
        a.push("Ado", img)
        assertEquals(Device.Meta(null, null, null), s.device.stored.values.single().meta)
    }
}
