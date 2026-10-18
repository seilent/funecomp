package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.Frame
import net.seilent.funecomp.proto.FrameReader
import net.seilent.funecomp.proto.Hello
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.Lib
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class Proto13Test {
    private class Sink : Out {
        val frames = ArrayList<Pair<Int, ByteArray>>()
        override fun push(type: Int, payload: ByteArray) {
            frames += type to payload
        }
    }

    private var t = 1000L
    private val lib = Library.synthetic()
    private val track = lib.tracks.first().id

    private fun device(minor: Int = Proto.minor, vararg faults: String) = Device(Options(Board.of("v1"), Faults(faults.toList()), minor = minor), lib) { t }

    private fun req(d: Device, type: Int, payload: ByteArray, out: Out = Sink()) =
        d.request(type, Node.decode(Msg.info.getValue(type).name, payload), out)

    private fun state(d: Device) = State.from(Node.decode("STATE", d.state()))

    private fun code(a: Answer) = (a as Answer.Fail).code

    private fun favorite(d: Device, out: Out = Sink()) = req(d, Msg.ACTION, Req.action(Enums.ActionCmd.favorite, track), out)

    @Test
    fun aToggleBumpsTheLibRevOnlyAndARescanBumpsBoth() {
        val d = device()
        val hello = d.helloReply()
        assertEquals(1L, hello.snapRev)
        val before = state(d)
        assertEquals(false, before.favorite)
        val out = Sink()
        val a = favorite(d, out) as Answer.Ok
        assertTrue(a.state)
        assertTrue(out.frames.none { it.first == Msg.STATE })
        val after = state(d)
        assertEquals(true, after.favorite)
        assertEquals(before.libRev!! + 1, after.libRev)
        assertEquals(before.snapRev, after.snapRev)
        req(d, Msg.ACTION, Req.action(Enums.ActionCmd.rescan))
        assertEquals(before.snapRev!! + 1, state(d).snapRev)
        assertEquals(before.libRev!! + 2, state(d).libRev)
    }

    @Test
    fun minor2LeavesOutTheTagsAndStillToggles() {
        val d = device(2)
        assertNull(d.helloReply().snapRev)
        val before = state(d)
        assertNull(before.favorite)
        assertNull(before.snapRev)
        favorite(d)
        assertEquals(before.libRev!! + 1, state(d).libRev)
        assertTrue(track in lib.favorites)
        val out = Sink()
        req(d, Msg.LIB_GET, Req.libGet(Enums.View.albums, "", 0, 10, d.lib.rev), out)
        assertNull(Lib.from(Node.decode("LIB", out.frames.first().second)).snapRev)
    }

    @Test
    fun favsBusyAnswersWithItsTextThenToggles() {
        val d = device(Proto.minor, "favs-busy=2:favorites not readable")
        assertNull(state(d).favorite)
        repeat(2) {
            val a = favorite(d) as Answer.Fail
            assertEquals(Err.busy, a.code)
            assertEquals("favorites not readable", a.text)
        }
        assertTrue(favorite(d) is Answer.Ok)
        assertEquals(true, state(d).favorite)
        val plain = device(Proto.minor, "favs-busy=1")
        assertEquals("busy", (favorite(plain) as Answer.Fail).text)
    }

    @Test
    fun theTagIsAbsentWithNoTrack() {
        val d = device()
        req(d, Msg.QUEUE_OP, Req.queueOp(Enums.QueueCmd.clear, state(d).queueRev))
        val s = state(d)
        assertNull(s.track)
        assertNull(s.favorite)
    }

    @Test
    fun revKindsPerView() {
        val d = device()
        val snap = d.lib.snapRev
        val old = d.lib.rev
        favorite(d)
        assertEquals(Err.staleRev, code(req(d, Msg.LIB_GET, Req.libGet(Enums.View.albums, "", 0, 10, null, snap + 1))))
        val out = Sink()
        assertTrue(req(d, Msg.LIB_GET, Req.libGet(Enums.View.albums, "", 0, 10, old, snap), out) is Answer.Ok)
        val page = Lib.from(Node.decode("LIB", out.frames.first().second))
        assertTrue(page.rows.isNotEmpty())
        assertEquals(snap, page.snapRev)
        assertEquals(Err.staleRev, code(req(d, Msg.LIB_GET, Req.libGet(Enums.View.favorites, "", 0, 10, old))))
        assertEquals(Err.badValue, code(req(d, Msg.LIB_GET, Req.libGet(Enums.View.favorites, "", 0, 10, null, snap))))
        assertTrue(req(d, Msg.LIB_GET, Req.libGet(Enums.View.favorites, "", 0, 10, d.lib.rev)) is Answer.Ok)
        assertTrue(req(d, Msg.PLAY, Req.play(Enums.View.albums, start = 0, snapRev = snap)) is Answer.Ok)
        assertEquals(Err.staleRev, code(req(d, Msg.PLAY, Req.play(Enums.View.albums, start = 0))))
        val text = "[00:01.00]a\n".toByteArray()
        val crc = java.util.zip.CRC32().apply { update(text) }.value
        assertTrue(req(d, Msg.LRC_PUT, Req.lrcPut(track, old, text, crc, snapRev = snap)) is Answer.Ok)
        assertEquals(Err.staleRev, code(req(d, Msg.LRC_PUT, Req.lrcPut(track, d.lib.rev, text, crc, snapRev = snap + 1))))
        assertEquals(Err.badValue, code(req(d, Msg.LRC_PUT, Req.lrcPut(track, null, text, crc))))
    }

    private fun frames(vararg faults: String): List<Frame> {
        val input = ByteArrayOutputStream()
        input.write(Frame(Msg.HELLO, Proto.flagWant, 1, Hello(Proto.major, Proto.minor, "test").encode()).encode())
        input.write(Frame(Msg.ACTION, Proto.flagWant, 2, Req.action(Enums.ActionCmd.favorite, track)).encode())
        val output = ByteArrayOutputStream()
        val d = device(Proto.minor, *faults)
        Conn(1, ByteArrayInputStream(input.toByteArray()), output, {}, d, Options(Board.of("v1"), Faults(faults.toList())), { t }).run()
        val b = output.toByteArray()
        return FrameReader().feed(b, 0, b.size)
    }

    private fun favoriteOf(f: Frame) = State.from(Node.decode("STATE", f.payload)).favorite

    @Test
    fun theStateOfACommandFollowsItsReply() {
        val f = frames()
        val reply = f.indexOfFirst { it.type == Msg.ACTION && it.isReply }
        assertEquals(1L, HelloReply.from(Node.decode("HELLO.reply", f.first().payload)).snapRev)
        assertEquals(listOf(false), f.subList(0, reply).filter { it.type == Msg.STATE }.map { favoriteOf(it) })
        assertEquals(Msg.STATE, f[reply + 1].type)
        assertEquals(true, favoriteOf(f[reply + 1]))
    }

    @Test
    fun staleStatePushesTheOldValueBeforeTheReply() {
        val f = frames("stale-state")
        val reply = f.indexOfFirst { it.type == Msg.ACTION && it.isReply }
        assertEquals(listOf(false, false), f.subList(0, reply).filter { it.type == Msg.STATE }.map { favoriteOf(it) })
        assertEquals(true, favoriteOf(f[reply + 1]))
    }
}
