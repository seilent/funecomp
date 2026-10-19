package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.Frame
import net.seilent.funecomp.proto.FrameReader
import net.seilent.funecomp.proto.Hello
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class Proto110Test {
    private var t = 1000L
    private val lib = Library.synthetic()

    private fun device(minor: Int = Proto.minor) = Device(Options(Board.of("v1"), minor = minor), lib) { t }

    private fun state(d: Device, receipt: Long? = null) = State.from(Node.decode("STATE", d.state(receipt)))

    private fun clocked(h: HelloReply) = h.has("stateClock")

    @Test
    fun minor10AnnouncesTheClockAndStampsFresh() {
        val d = device()
        assertTrue(clocked(d.helloReply()))
        t = 5000
        val s = state(d, 4000)
        assertEquals(5000L, s.sampleMs)
        assertEquals(5000L, s.sentMs)
        assertEquals(false, s.positionHeld)
    }

    @Test
    fun minor9StampsTheReceiptTimeAndLeavesOutTheBitAndTheTag() {
        val d = device(9)
        assertFalse(clocked(d.helloReply()))
        t = 5000
        val s = state(d, 4000)
        assertEquals(4000L, s.sampleMs)
        assertEquals(4000L, s.sentMs)
        assertNull(s.positionHeld)
        val tick = state(d)
        assertEquals(5000L, tick.sampleMs)
        assertEquals(5000L, tick.sentMs)
    }

    private fun stamps(minor: Int, stallMs: Long): Pair<Long, State> {
        val input = ByteArrayOutputStream()
        input.write(Frame(Msg.HELLO, Proto.flagWant, 1, Hello(Proto.major, Proto.minor, "test").encode()).encode())
        input.write(Frame(Msg.VOLUME, Proto.flagWant, 2, Req.volume(step = 1)).encode())
        val output = ByteArrayOutputStream()
        val clock = { System.nanoTime() / 1_000_000 }
        var rx = 0L
        val opts = Options(Board.of("v1"), Faults(listOf("stall-ms=$stallMs")), minor = minor, log = { if (it.startsWith("conn 1 rx VOLUME")) rx = clock() })
        Conn(1, ByteArrayInputStream(input.toByteArray()), output, {}, Device(opts, lib, clock), opts, clock).run()
        val b = output.toByteArray()
        val f = FrameReader().feed(b, 0, b.size)
        val reply = f.indexOfFirst { it.type == Msg.VOLUME && it.isReply }
        assertEquals(Msg.STATE, f[reply + 1].type)
        return rx to State.from(Node.decode("STATE", f[reply + 1].payload))
    }

    @Test
    fun belowMinor10TheStateAfterARequestCarriesItsReceiptTimeBeforeTheStall() {
        val (rx, s) = stamps(9, 300)
        val sent = s.sentMs ?: error("no sentMs")
        assertEquals(sent, s.sampleMs)
        assertTrue("sent ${sent - rx}", sent - rx in 0L until 300L)
    }

    @Test
    fun atMinor10TheStateAfterARequestIsStampedAfterTheStall() {
        val (rx, s) = stamps(10, 300)
        val sent = s.sentMs ?: error("no sentMs")
        val sample = s.sampleMs ?: error("no sampleMs")
        assertTrue(sent >= sample)
        assertTrue("sample ${sample - rx}", sample - rx >= 300L)
    }
}
