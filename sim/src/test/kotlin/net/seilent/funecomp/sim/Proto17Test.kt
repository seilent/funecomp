package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.LyrGetReply
import net.seilent.funecomp.proto.LyrLine
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.CRC32

class Proto17Test {
    @get:Rule
    val tmp = TemporaryFolder()

    private class Sink : Out {
        override fun push(type: Int, payload: ByteArray) {}
    }

    private val lib = Library.synthetic()
    private val track = lib.tracks.first().id
    private val dir get() = File(tmp.root, "lrc")

    private fun device(minor: Int = Proto.minor, vararg faults: String) =
        Device(Options(Board.of("v1"), Faults(faults.toList()), minor = minor, lrcDir = dir), lib) { 1000L }

    private fun file(text: String, id: Long = track) = File(dir.apply { mkdirs() }, "${java.lang.Long.toUnsignedString(id)}.lrc").writeText(text)

    private fun get(d: Device, offset: Long = 0, have: Long? = null, id: Long = track) = d.request(Msg.LYR_GET, Node.decode("LYR_GET", Req.lyrGet(id, offset, have)), Sink())

    private fun page(a: Answer) = LyrGetReply.from(Node.decode("LYR_GET.reply", (a as Answer.Ok).payload))

    private fun code(a: Answer) = (a as Answer.Fail).code

    private fun state(d: Device) = State.from(Node.decode("STATE", d.state()))

    private fun all(d: Device): List<Pair<Long, Int>> {
        val first = page(get(d))
        val out = ArrayList<Pair<Long, Int>>()
        var p = first
        while (true) {
            out += (p.offset ?: -1) to p.lines.size
            if (p.more != true) break
            p = page(get(d, out.sumOf { it.second }.toLong(), first.crc))
        }
        return out
    }

    @Test
    fun syncedFilesParseSortedWithTheOffsetAndOneLinePerStamp() {
        file("[ar:Utada Hikaru]\n[offset:500]\n[00:03.00]Third\n[00:01.00][00:05.25]Twice\n[00:00.20]\n")
        val d = device()
        val p = page(get(d))
        val lines = listOf(LyrLine(0, ""), LyrLine(500, "Twice"), LyrLine(2500, "Third"), LyrLine(4750, "Twice"))
        assertEquals(LyrGetReply(lyricsCrc(Enums.LyricsKind.synced, lines), Enums.LyricsKind.synced, 4, 0, false, lines), p)
        val s = state(d)
        assertEquals(true, s.lyrics)
        assertEquals(false, s.lyricsPlain)
        assertEquals(p.crc, s.lyricsCrc)
    }

    @Test
    fun plainFilesKeepEmptyLinesAndDropIdTags() {
        file("[ti:First Love]\nLight on the water\n\n[Chorus]\nEvery road leads back home\n")
        val p = page(get(device()))
        val lines = listOf(LyrLine(null, "Light on the water"), LyrLine(null, ""), LyrLine(null, "[Chorus]"), LyrLine(null, "Every road leads back home"))
        assertEquals(LyrGetReply(lyricsCrc(Enums.LyricsKind.plain, lines), Enums.LyricsKind.plain, 4, 0, false, lines), p)
    }

    @Test
    fun pagesHaveRestartAndErrors() {
        file((0 until 1024).joinToString("") { "[%02d:%02d.00]%s\n".format(it / 60, it % 60, "x".repeat(64)) })
        val d = device()
        val pages = all(d)
        assertEquals(7, pages.size)
        assertEquals(1024, pages.sumOf { it.second })
        val crc = page(get(d)).crc!!
        assertEquals(LyrGetReply(crc, Enums.LyricsKind.synced, 1024, 0, false, emptyList()), page(get(d, 0, crc)))
        val restart = page(get(d, 300, crc + 1))
        assertEquals(0L, restart.offset)
        assertEquals(crc, restart.crc)
        assertEquals(1024L, restart.total)
        val later = page(get(d, 300, crc))
        assertNull(later.crc)
        assertEquals(300L, later.offset)
        assertEquals(Err.badValue, code(get(d, 1024, crc)))
        assertEquals(Err.staleRev, code(get(d, id = 12345)))
        assertEquals(Err.notFound, code(get(d, id = lib.tracks[1].id)))
        assertEquals(Err.badValue, code(d.request(Msg.LYR_GET, Node.decode("LYR_GET", ByteArray(0)), Sink())))
    }

    @Test
    fun longLinesAreCutAt4096BytesAndTakeTwoAPage() {
        file((0 until 16).joinToString("") { "[00:%02d.00]%s\n".format(it, "あ".repeat(1400)) })
        val d = device()
        assertEquals(8, all(d).size)
        val l = page(get(d)).lines.first().text
        assertEquals(4095, l.toByteArray().size)
    }

    @Test
    fun lrcPutMovesTheCrcAndLrcDelDropsIt() {
        val d = device()
        assertNull(state(d).lyricsCrc)
        val text = "[00:01.00]One\n".toByteArray()
        assertTrue(d.request(Msg.LRC_PUT, Node.decode("LRC_PUT", Req.lrcPut(track, lib.rev, text, CRC32().apply { update(text) }.value)), Sink()) is Answer.Ok)
        assertEquals(lyricsCrc(Enums.LyricsKind.synced, listOf(LyrLine(1000, "One"))), state(d).lyricsCrc)
        assertTrue(d.request(Msg.LRC_DEL, Node.decode("LRC_DEL", Req.lrcDel(track)), Sink()) is Answer.Ok)
        assertNull(state(d).lyricsCrc)
        assertEquals(Err.notFound, code(get(d)))
    }

    @Test
    fun aMinor6SimHasNoBit11AndNoStateCrc() {
        file("[00:01.00]One\n")
        val d = device(6)
        assertFalse(Groups.allowed(Msg.LYR_GET, d.helloReply().features))
        assertEquals(true, state(d).lyrics)
        assertNull(state(d).lyricsCrc)
        assertTrue(Groups.allowed(Msg.LYR_GET, device().helloReply().features))
        val masked = device(Proto.minor, "features=${Groups.all and Groups.mask("lyricsGet").inv()}")
        assertNull(state(masked).lyricsCrc)
    }
}
