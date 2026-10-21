package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LyricsFaultTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class Sink : Out {
        val states = ArrayList<State>()
        override fun push(type: Int, payload: ByteArray) {
            if (type == Msg.STATE) states += State.from(Node.decode("STATE", payload))
        }
    }

    private val lib = Library.synthetic()
    private var t = 1000L

    private fun device(vararg faults: String): Device {
        val dir = File(tmp.root, "lrc").apply { mkdirs() }
        for (track in lib.tracks.take(2)) File(dir, "${java.lang.Long.toUnsignedString(track.id)}.lrc").writeText("[00:01.00]one\n[00:02.00]two\n")
        return Device(Options(Board.of("v1"), Faults(faults.toList()), lrcDir = dir), lib) { t }
    }

    private fun state(d: Device) = State.from(Node.decode("STATE", d.state()))

    private fun next(d: Device) = d.request(Msg.TRANSPORT, Node.decode("TRANSPORT", Req.transport(Enums.TransportCmd.next)), Sink())

    @Test
    fun lateFlagsAreLeftOutAfterEachTrackChangeThenPushed() {
        val d = device("lyrics-late=1500")
        val first = state(d)
        assertNull(first.lyrics)
        assertNull(first.lyricsPlain)
        assertNull(first.lyricsCrc)
        val out = Sink()
        t += 1499
        d.tick(out)
        assertTrue(out.states.isEmpty())
        t += 1
        d.tick(out)
        assertEquals(true, out.states.single().lyrics)
        assertEquals(false, out.states.single().lyricsPlain)
        assertNotNull(out.states.single().lyricsCrc)
        next(d)
        assertNull(state(d).lyrics)
        t += 1500
        assertEquals(true, state(d).lyrics)
    }

    @Test
    fun anAbsentTitleNeverSendsEitherFlag() {
        val title = lib.tracks.first().title
        val d = device("lyrics-absent=$title")
        t += 60_000
        assertNull(state(d).lyrics)
        assertNull(state(d).lyricsPlain)
        assertNull(state(d).lyricsCrc)
        next(d)
        assertEquals(true, state(d).lyrics)
    }
}
