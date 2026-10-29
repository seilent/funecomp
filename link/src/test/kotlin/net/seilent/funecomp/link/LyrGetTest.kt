package net.seilent.funecomp.link

import net.seilent.funecomp.proto.ErrorReply
import net.seilent.funecomp.proto.Frame
import net.seilent.funecomp.proto.FrameReader
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.LyrGetReply
import net.seilent.funecomp.proto.LyrLine
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Feature
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.proto.gen.Tags
import net.seilent.funecomp.proto.lyricsCrc
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class LyrGetTest {
    class Ask(val track: Long?, val offset: Long?, val have: Long?)

    private class Device(private val features: Long, private val answer: (Ask) -> Pair<Int, ByteArray>?) : Transport {
        private val inbound = LinkedBlockingQueue<ByteArray>()
        private val reader = FrameReader()
        private var pending: ByteArray? = null
        val asks: MutableList<Ask> = java.util.concurrent.CopyOnWriteArrayList()
        @Volatile private var closed = false

        override fun open() {}

        override fun read(buf: ByteArray): Int {
            var b = pending
            while (b == null) {
                if (closed) return -1
                b = inbound.poll(50, TimeUnit.MILLISECONDS)
            }
            val n = minOf(buf.size, b.size)
            b.copyInto(buf, 0, 0, n)
            pending = if (n < b.size) b.copyOfRange(n, b.size) else null
            return n
        }

        override fun write(bytes: ByteArray) {
            if (closed) throw IOException("closed")
            for (f in reader.feed(bytes)) {
                if (!f.wantsReply) continue
                when (f.type) {
                    Msg.HELLO -> inbound.put(Frame(f.type, Proto.flagReply, f.seq, HelloReply(Proto.major, Proto.minor, "v1", "fake", 240, 320, 240, 320, Proto.maxPayload, 30_000, 5_000, features, 1, 1).encode()).encode())
                    Msg.LYR_GET -> {
                        val n = Node.decode("LYR_GET", f.payload)
                        val a = Ask(n.long(Tags.LyrGet.track), n.long(Tags.LyrGet.offset), n.long(Tags.LyrGet.have))
                        asks += a
                        answer(a)?.let { (flags, body) -> inbound.put(Frame(f.type, flags, f.seq, body).encode()) }
                    }
                    else -> inbound.put(Frame(f.type, Proto.flagReply or Proto.flagError, f.seq, ErrorReply(Err.refused).encode()).encode())
                }
            }
        }

        override fun close() {
            closed = true
        }
    }

    private class Error(val code: Int) : RuntimeException()

    private val sessions = ArrayList<Session>()
    private lateinit var device: Device

    @After
    fun tearDown() = sessions.forEach { it.close("done") }

    private fun session(features: Long = Groups.all, timeoutMs: Long = Proto.requestTimeoutMs.toLong(), script: (Ask) -> ByteArray?): Session {
        device = Device(features) { a ->
            try {
                script(a)?.let { Proto.flagReply to it }
            } catch (e: Error) {
                (Proto.flagReply or Proto.flagError) to ErrorReply(e.code).encode()
            }
        }
        val ready = CountDownLatch(1)
        val s = Session(device, object : Session.Listener {
            override fun onHello(hello: HelloReply) = ready.countDown()
        }, Session.Config("test", requestTimeoutMs = timeoutMs))
        sessions += s
        Thread { s.run() }.apply { isDaemon = true }.start()
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        return s
    }

    private fun synced(n: Int, tag: String = "line") = List(n) { LyrLine(it * 1000L, "$tag $it") }

    private fun reply(crc: Long?, kind: Int?, total: Long?, offset: Long?, more: Boolean?, lines: List<LyrLine>) =
        LyrGetReply(crc, kind, total, offset, more, lines).encode()

    private fun pager(kind: Int, all: () -> List<LyrLine>, per: Int, crc: () -> Long = { lyricsCrc(kind, all()) }): (Ask) -> ByteArray? = { a ->
        val lines = all()
        val c = crc()
        val off = a.offset!!.toInt()
        if (a.have == c && off == 0) {
            reply(c, kind, lines.size.toLong(), 0, false, emptyList())
        } else if (off > 0 && a.have != c) {
            val page = lines.take(per)
            reply(c, kind, lines.size.toLong(), 0, page.size < lines.size, page)
        } else {
            val page = lines.drop(off).take(per)
            val more = off + page.size < lines.size
            if (off == 0) reply(c, kind, lines.size.toLong(), 0, more, page) else reply(null, null, null, off.toLong(), more, page)
        }
    }

    @Test
    fun threePagesAssembleWithLineOffsetsAndHave() {
        val lines = synced(7)
        val crc = lyricsCrc(Enums.LyricsKind.synced, lines)
        val s = session(script = pager(Enums.LyricsKind.synced, { lines }, 3))
        assertEquals(LyrGet.Outcome.Lines(crc, Enums.LyricsKind.synced, lines), LyrGet(s).run(103))
        assertEquals(listOf(0L, 3L, 6L), device.asks.map { it.offset })
        assertEquals(listOf(null, crc, crc), device.asks.map { it.have })
        assertEquals(listOf(103L, 103L, 103L), device.asks.map { it.track })
    }

    @Test
    fun aHeaderOnlyReplyIsHaveAndTotalZeroIsNone() {
        val lines = synced(2)
        val crc = lyricsCrc(Enums.LyricsKind.synced, lines)
        val s = session(script = pager(Enums.LyricsKind.synced, { lines }, 3))
        assertEquals(LyrGet.Outcome.Have(crc), LyrGet(s).run(103, have = crc))
        assertEquals(crc, device.asks.single().have)
        assertEquals(LyrGet.Outcome.Lines(crc, Enums.LyricsKind.synced, lines), LyrGet(s).run(103, have = crc + 1))
        val e = session { reply(7, Enums.LyricsKind.plain, 0, 0, false, emptyList()) }
        assertEquals(LyrGet.Outcome.None(7), LyrGet(e).run(103))
    }

    @Test
    fun plainLinesKeepEmptyLinesAndDropMs() {
        val lines = listOf(LyrLine(null, "a"), LyrLine(null, ""), LyrLine(null, "b"))
        val crc = lyricsCrc(Enums.LyricsKind.plain, lines)
        val s = session(script = pager(Enums.LyricsKind.plain, { lines.map { it.copy(ms = 5) } }, 2, { crc }))
        assertEquals(LyrGet.Outcome.Lines(crc, Enums.LyricsKind.plain, lines), LyrGet(s).run(103))
    }

    @Test
    fun aRestartAtPageTwoStartsOverAndThreeRestartsFail() {
        var gen = 0
        val s = session(script = pager(Enums.LyricsKind.synced, { synced(7, "g$gen") }, 3))
        var calls = 0
        val r = LyrGet(s) {
            if (++calls == 2) gen++
            false
        }.run(103) as LyrGet.Outcome.Lines
        assertEquals(synced(7, "g1"), r.lines)
        assertEquals(listOf(0L, 3L, 3L, 6L), device.asks.map { it.offset })
        device.asks.clear()
        val f = LyrGet(s) {
            gen++
            false
        }.run(103)
        assertEquals(LyrGet.Outcome.Failed(null), f)
        assertEquals(listOf(0L, 3L, 3L, 3L), device.asks.map { it.offset })
    }

    @Test
    fun aCrcOnALaterPageWithoutAZeroOffsetFails() {
        val lines = synced(6)
        val crc = lyricsCrc(Enums.LyricsKind.synced, lines)
        val s = session { a ->
            if (a.offset == 0L) reply(crc, Enums.LyricsKind.synced, 6, 0, true, lines.take(3))
            else reply(crc + 1, Enums.LyricsKind.synced, 6, 3, false, lines.drop(3))
        }
        assertEquals(LyrGet.Outcome.Failed(null), LyrGet(s).run(103))
        val tail = lines.drop(3)
        val whole = session { a ->
            if (a.offset == 0L) reply(crc, Enums.LyricsKind.synced, 6, 0, true, lines.take(3))
            else reply(lyricsCrc(Enums.LyricsKind.synced, tail), Enums.LyricsKind.synced, 3, 3, false, tail)
        }
        assertEquals(LyrGet.Outcome.Failed(null), LyrGet(whole).run(103))
    }

    private fun fails(script: (Ask) -> ByteArray?) = assertEquals(LyrGet.Outcome.Failed(null), LyrGet(session(script = script)).run(103))

    @Test
    fun malformedRepliesFail() {
        val two = synced(2)
        val crc = lyricsCrc(Enums.LyricsKind.synced, two)
        fails { reply(crc, 2, 2, 0, false, two) }
        fails { reply(crc, null, 2, 0, false, two) }
        fails { reply(null, Enums.LyricsKind.synced, 2, 0, false, two) }
        fails { reply(crc, Enums.LyricsKind.synced, null, 0, false, two) }
        fails { reply(crc, Enums.LyricsKind.synced, 2, 0, false, two.take(1)) }
        fails { reply(crc, Enums.LyricsKind.synced, 2, 0, true, two) }
        fails { reply(crc + 1, Enums.LyricsKind.synced, 2, 0, false, two) }
        fails { reply(crc xor 1, Enums.LyricsKind.synced, 2, 0, false, two) }
        fails { reply(crc, Enums.LyricsKind.synced, 2, 0, false, listOf(two[1], two[0])) }
        fails { reply(crc, Enums.LyricsKind.synced, 2, 0, false, listOf(LyrLine(null, "line 0"), two[1])) }
        val big = synced(1025)
        fails { a -> reply(lyricsCrc(Enums.LyricsKind.synced, big), Enums.LyricsKind.synced, 1025, a.offset, a.offset == 0L, if (a.offset == 0L) big.take(1024) else big.drop(1024)) }
        val long = listOf(LyrLine(0, "a".repeat(Proto.lyrLineMax + 1)))
        fails { reply(lyricsCrc(Enums.LyricsKind.synced, long), Enums.LyricsKind.synced, 1, 0, false, long) }
        val six = synced(6)
        val c6 = lyricsCrc(Enums.LyricsKind.synced, six)
        fails { a -> if (a.offset == 0L) reply(c6, Enums.LyricsKind.synced, 6, 0, true, six.take(3)) else reply(null, null, null, 4, false, six.drop(3)) }
        fails { a -> if (a.offset == 0L) reply(c6, Enums.LyricsKind.synced, 6, 0, true, six.take(3)) else reply(null, null, null, 3, true, emptyList()) }
        fails { a -> if (a.offset == 0L) reply(c6, Enums.LyricsKind.synced, 6, 0, true, six.take(3)) else reply(null, null, null, 3, false, six.drop(3).take(2)) }
        fails { a -> if (a.offset == 0L) reply(c6, Enums.LyricsKind.synced, 6, 0, true, six.take(3)) else reply(null, null, null, 3, true, six.drop(3)) }
    }

    @Test
    fun the1024LineLimitAndThe4096ByteLineAreTaken() {
        val many = synced(1024)
        val s = session(script = pager(Enums.LyricsKind.synced, { many }, 150))
        assertEquals(1024, (LyrGet(s).run(103) as LyrGet.Outcome.Lines).lines.size)
        val long = listOf(LyrLine(0, "あ".repeat(Proto.lyrLineMax / 3) + "a"))
        assertEquals(Proto.lyrLineMax, long[0].text.toByteArray().size)
        val l = session(script = pager(Enums.LyricsKind.synced, { long }, 1))
        assertEquals(long, (LyrGet(l).run(103) as LyrGet.Outcome.Lines).lines)
    }

    @Test
    fun errorCodesPassThroughAndTimeoutsAndOldPeersAreFailedNull() {
        for (code in listOf(Err.notFound, Err.busy, Err.staleRev, Err.noLibrary, Err.badValue, Err.refused, Err.unknown)) {
            assertEquals(LyrGet.Outcome.Failed(code), LyrGet(session { throw Error(code) }).run(103))
        }
        assertEquals(LyrGet.Outcome.Failed(null), LyrGet(session(timeoutMs = 200) { null }).run(103))
        val old = session(Groups.all and (1L shl Feature.lyricsGet).inv()) { throw Error(Err.refused) }
        assertEquals(LyrGet.Outcome.Failed(null), LyrGet(old).run(103))
        assertTrue(device.asks.isEmpty())
    }

    @Test
    fun cancelStopsBetweenPages() {
        val lines = synced(9)
        val s = session(script = pager(Enums.LyricsKind.synced, { lines }, 3))
        var calls = 0
        assertEquals(LyrGet.Outcome.Cancelled, LyrGet(s) { ++calls > 3 }.run(103))
        assertEquals(listOf(0L, 3L), device.asks.map { it.offset })
    }
}
