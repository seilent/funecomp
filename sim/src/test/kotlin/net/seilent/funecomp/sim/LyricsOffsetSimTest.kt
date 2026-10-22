package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.ErrorReply
import net.seilent.funecomp.proto.Frame
import net.seilent.funecomp.proto.FrameReader
import net.seilent.funecomp.proto.Hello
import net.seilent.funecomp.proto.LyrGetReply
import net.seilent.funecomp.proto.LyrLine
import net.seilent.funecomp.proto.LyrOffsetReply
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.proto.lyricsCrc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32

class LyricsOffsetSimTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class Sink : Out {
        override fun push(type: Int, payload: ByteArray) {}
    }

    private val lib = Library.synthetic()
    private val track = lib.tracks.first().id
    private val queued = lib.tracks[1].id
    private val dir get() = File(tmp.root, "lrc")
    private val logs = ArrayList<String>()
    private val body = "[00:01.00]one\n[00:03.00]two\n[00:05.00]three\n"

    private fun device(minor: Int = Proto.minor, library: Library = lib, offsets: Map<Long, Int> = emptyMap()) =
        Device(Options(Board.of("v1"), minor = minor, lrcDir = dir, lyrOffsets = offsets, log = { logs += it }), library) { 1000L }

    private fun file(text: String, id: Long = track) = File(dir.apply { mkdirs() }, "${java.lang.Long.toUnsignedString(id)}.lrc").writeText(text)

    private fun offset(d: Device, ms: Int? = null, id: Long = track) = d.request(Msg.LYR_OFFSET, Node.decode("LYR_OFFSET", Req.lyrOffset(id, ms)), Sink())

    private fun ms(a: Answer) = LyrOffsetReply.from(Node.decode("LYR_OFFSET.reply", (a as Answer.Ok).payload)).ms

    private fun code(a: Answer) = (a as Answer.Fail).code

    private fun state(d: Device) = State.from(Node.decode("STATE", d.state()))

    private fun page(d: Device, have: Long? = null, id: Long = track) =
        LyrGetReply.from(Node.decode("LYR_GET.reply", (d.request(Msg.LYR_GET, Node.decode("LYR_GET", Req.lyrGet(id, 0, have)), Sink()) as Answer.Ok).payload))

    private fun put(d: Device, text: String, crcOff: Long = 0) = text.toByteArray().let {
        d.request(Msg.LRC_PUT, Node.decode("LRC_PUT", Req.lrcPut(track, lib.rev, it, CRC32().apply { update(it) }.value + crcOff)), Sink())
    }

    private fun del(d: Device) = d.request(Msg.LRC_DEL, Node.decode("LRC_DEL", Req.lrcDel(track)), Sink())

    private fun synced(vararg l: Pair<Long, String>) = l.map { LyrLine(it.first, it.second) }

    private fun conn(minor: Int, faults: List<String>): Int {
        val input = ByteArrayOutputStream()
        input.write(Frame(Msg.HELLO, Proto.flagWant, 1, Hello(Proto.major, Proto.minor, "test").encode()).encode())
        input.write(Frame(Msg.LYR_OFFSET, Proto.flagWant, 2, Req.lyrOffset(track, 500)).encode())
        val output = ByteArrayOutputStream()
        val opts = Options(Board.of("v1"), Faults(faults), minor = minor)
        Conn(1, ByteArrayInputStream(input.toByteArray()), output, {}, Device(opts, lib) { 1000L }, opts) { 1000L }.run()
        val b = output.toByteArray()
        val reply = FrameReader().feed(b, 0, b.size).first { it.type == Msg.LYR_OFFSET && it.isReply }
        return if (reply.isError) ErrorReply.from(Node.decode("error", reply.payload)).code else 0
    }

    @Test
    fun errorsInSpecOrder() {
        assertFalse(device(14).helloReply().has("lyricsOffset"))
        assertTrue(device().helloReply().has("lyricsOffset"))
        assertEquals(Err.unknown, conn(14, emptyList()))
        assertEquals(Err.refused, conn(15, listOf("refuse=LYR_OFFSET")))
        assertEquals(0, conn(15, emptyList()))
        val d = device()
        assertEquals(Err.badValue, code(d.request(Msg.LYR_OFFSET, Node.decode("LYR_OFFSET", ByteArray(0)), Sink())))
        assertEquals(Err.badValue, code(offset(d, 60_001)))
        assertEquals(Err.badValue, code(offset(d, -60_001)))
        assertEquals(Err.badValue, code(device(library = Library(emptyList())).let { offset(it, 60_001) }))
        assertEquals(Err.noLibrary, code(offset(device(library = Library(emptyList())), 500)))
        assertEquals(Err.staleRev, code(offset(d, 500, 0)))
        assertEquals(Err.staleRev, code(offset(d, 500, 424242)))
    }

    @Test
    fun readSetAndClear() {
        val d = device()
        assertEquals(0, ms(offset(d)))
        assertTrue((offset(d) as Answer.Ok).payload.isEmpty())
        assertEquals(60_000, ms(offset(d, 60_000)))
        assertEquals(60_000, ms(offset(d)))
        assertEquals(-1500, ms(offset(d, -1500)))
        assertEquals(0, ms(offset(d, 0)))
        assertEquals(0, ms(offset(d)))
        assertEquals(500, ms(offset(d, 500, queued)))
        assertFalse((offset(d, 500, queued) as Answer.Ok).state)
    }

    @Test
    fun setsWorkOnPlainAndOnNoLyrics() {
        file("just text\nmore text\n")
        val d = device()
        assertEquals(700, ms(offset(d, 700)))
        assertNull(state(d).lyricsOffsetMs)
        val p = page(d)
        assertEquals(700, p.offsetMs)
        assertEquals(LyrLine(null, "just text"), p.lines.first())
        assertEquals(300, ms(offset(d, 300, queued)))
    }

    @Test
    fun aSetOnThePlayingSyncedTrackMovesTheCrcAndState38AfterTheReply() {
        file(body)
        val d = device()
        val before = state(d)
        assertNull(before.lyricsOffsetMs)
        val a = offset(d, 1500) as Answer.Ok
        assertTrue(a.state)
        val s = state(d)
        assertEquals(1500, s.lyricsOffsetMs)
        val p = page(d)
        assertEquals(1500, p.offsetMs)
        assertEquals(s.lyricsCrc, p.crc)
        assertNotEquals(before.lyricsCrc, s.lyricsCrc)
        assertEquals(synced(0L to "one", 1500L to "two", 3500L to "three"), p.lines)
        assertEquals(lyricsCrc(Enums.LyricsKind.synced, p.lines), p.crc)
        val have = page(d, have = p.crc)
        assertEquals(1500, have.offsetMs)
        assertTrue(have.lines.isEmpty())
        assertFalse((offset(d, 1500) as Answer.Ok).state)
    }

    @Test
    fun clampedStampsGiveOneCrcForTwoOffsetsAndState38StillMoves() {
        file(body)
        val d = device()
        offset(d, 6000)
        val a = state(d)
        val pa = page(d)
        assertTrue((offset(d, 7000) as Answer.Ok).state)
        val b = state(d)
        val pb = page(d)
        assertEquals(a.lyricsCrc, b.lyricsCrc)
        assertEquals(6000, a.lyricsOffsetMs)
        assertEquals(7000, b.lyricsOffsetMs)
        assertEquals(6000, pa.offsetMs)
        assertEquals(7000, pb.offsetMs)
    }

    @Test
    fun theFileOffsetAndTheUserOffsetAddWithNoFloorInTheSource() {
        file("[offset:+3000]\n[00:01.00]one\n[00:04.00]two\n")
        val d = device()
        assertEquals(synced(0L to "one", 1000L to "two"), page(d).lines)
        offset(d, -3000)
        assertEquals(synced(1000L to "one", 4000L to "two"), page(d).lines)
    }

    @Test
    fun aDifferentBodyResetsAndTheSameBodyKeeps() {
        val d = device()
        assertTrue(put(d, body) is Answer.Ok)
        offset(d, 1200)
        assertTrue((put(d, body) as Answer.Ok).state)
        assertEquals(1200, state(d).lyricsOffsetMs)
        assertEquals(1200, page(d).offsetMs)
        assertTrue(put(d, "$body ") is Answer.Ok)
        assertNull(state(d).lyricsOffsetMs)
        assertNull(page(d).offsetMs)
        assertEquals(0, ms(offset(d)))
        assertTrue(logs.any { it.startsWith("lyr offset reset") })
    }

    @Test
    fun aFailedPutAndLrcDelKeepAndAPutAfterDelResets() {
        val d = device()
        put(d, body)
        offset(d, 800)
        assertEquals(Err.crc, code(put(d, "[00:01.00]other\n", 1)))
        assertEquals(800, ms(offset(d)))
        del(d)
        assertEquals(800, ms(offset(d)))
        put(d, body)
        assertEquals(0, ms(offset(d)))
    }

    @Test
    fun aSeededLrcDirBodyCountsAsStoredAndTheSeedSetsOffsets() {
        file(body)
        val d = device(offsets = mapOf(track to 900))
        assertEquals(900, ms(offset(d)))
        assertEquals(900, state(d).lyricsOffsetMs)
        put(d, body)
        assertEquals(900, ms(offset(d)))
        put(d, "[00:02.00]new\n")
        assertEquals(0, ms(offset(d)))
    }

    @Test
    fun minor14SendsNoTag38AndNoOffsetMs() {
        file(body)
        val d = device(14, offsets = mapOf(track to 900))
        assertNull(state(d).lyricsOffsetMs)
        val p = page(d)
        assertNull(p.offsetMs)
        assertEquals(synced(1000L to "one", 3000L to "two", 5000L to "three"), p.lines)
    }
}
