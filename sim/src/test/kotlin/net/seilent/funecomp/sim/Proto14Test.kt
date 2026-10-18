package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Queue
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.CRC32

class Proto14Test {
    @get:Rule
    val tmp = TemporaryFolder()

    private class Sink : Out {
        val frames = ArrayList<Pair<Int, ByteArray>>()
        override fun push(type: Int, payload: ByteArray) {
            frames += type to payload
        }
    }

    private val lib = Library.synthetic()
    private val track = lib.tracks.first().id
    private val logs = ArrayList<String>()
    private val file get() = File(tmp.root, "lrc/${java.lang.Long.toUnsignedString(track)}.lrc")

    private fun device(minor: Int = Proto.minor) =
        Device(Options(Board.of("v1"), Faults(emptyList()), minor = minor, lrcDir = File(tmp.root, "lrc"), log = { logs += it }), lib) { 1000L }

    private fun put(d: Device, text: ByteArray) = d.request(Msg.LRC_PUT, Node.decode("LRC_PUT", Req.lrcPut(track, lib.rev, text, CRC32().apply { update(text) }.value)), Sink())

    private fun code(a: Answer) = (a as Answer.Fail).code

    private fun state(d: Device) = State.from(Node.decode("STATE", d.state()))

    private fun queue(d: Device): List<Pair<Boolean?, Boolean?>> {
        val out = Sink()
        d.request(Msg.QUEUE_OP, Node.decode("QUEUE_OP", Req.queueOp(Enums.QueueCmd.page, offset = 0, count = 2)), out)
        return Queue.from(Node.decode("QUEUE", out.frames.first { it.first == Msg.QUEUE }.second)).items.map { it.lyrics to it.lyricsPlain }
    }

    private val plain = "First line\nSecond line\n".toByteArray()
    private val synced = "[00:12.00]First line\n[00:17.20]Second line\n".toByteArray()

    @Test
    fun aPlainPutIsStoredAndShownAsPlain() {
        val d = device()
        assertEquals(false to false, state(d).let { it.lyrics to it.lyricsPlain })
        assertTrue(put(d, plain) is Answer.Ok)
        assertArrayEquals(plain, file.readBytes())
        assertEquals(false to true, state(d).let { it.lyrics to it.lyricsPlain })
        assertEquals(listOf(false to true, false to false), queue(d))
        assertTrue(logs.any { it.endsWith("bytes=${plain.size} kind=plain") })
    }

    @Test
    fun plainOverSyncedIsRefusedAndTheFileStays() {
        val d = device()
        assertTrue(put(d, synced) is Answer.Ok)
        assertEquals(Err.refused, code(put(d, plain)))
        assertArrayEquals(synced, file.readBytes())
        assertEquals(true to false, state(d).let { it.lyrics to it.lyricsPlain })
    }

    @Test
    fun syncedOverPlainIsStoredAndTheStateFlips() {
        val d = device()
        assertTrue(put(d, plain) is Answer.Ok)
        assertTrue(put(d, synced) is Answer.Ok)
        assertArrayEquals(synced, file.readBytes())
        assertEquals(true to false, state(d).let { it.lyrics to it.lyricsPlain })
        assertEquals(listOf(true to false, false to false), queue(d))
    }

    @Test
    fun blankAndOversizedTextAreRefused() {
        val d = device()
        assertEquals(Err.badValue, code(put(d, " \n\n".toByteArray())))
        assertEquals(Err.tooBig, code(put(d, ByteArray(Proto.lrcMaxBytes + 1) { 'a'.code.toByte() })))
        assertEquals(false to false, state(d).let { it.lyrics to it.lyricsPlain })
    }

    @Test
    fun aPlainLrcDirFileLoadsAsPlain() {
        file.parentFile.mkdirs()
        file.writeBytes(plain)
        assertEquals(false to true, state(device()).let { it.lyrics to it.lyricsPlain })
    }

    @Test
    fun minor3LeavesOutTheTagsAndRefusesPlain() {
        val d = device(3)
        assertNull(state(d).lyricsPlain)
        assertEquals(listOf(false to null, false to null), queue(d))
        assertEquals(Err.badValue, code(put(d, plain)))
        assertFalse(file.exists())
    }
}
