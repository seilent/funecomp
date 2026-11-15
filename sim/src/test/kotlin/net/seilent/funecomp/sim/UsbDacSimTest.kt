package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.state.CardView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDacSimTest {
    private class Sink : Out {
        override fun push(type: Int, payload: ByteArray) {}
    }

    private val lib = Library.synthetic()
    private val track = lib.tracks[2].id
    private val idol = PhoneLine("Idol", "YOASOBI", "THE BOOK 3", 213_000, 0, true)

    private fun device(vararg faults: String, receiver: Boolean = false, phone: List<PhoneLine> = emptyList()) =
        Device(Options(Board.of("v1"), Faults(faults.toList()), phone = phone, receiver = receiver), lib) { 1000L }.also {
            val out = Sink()
            it.linked(out, 0)
            it.greet(out)
        }

    private fun req(d: Device, type: Int, payload: ByteArray) = d.request(type, Node.decode(Msg.info.getValue(type).name, payload), Sink())

    private fun rev(d: Device) = State.from(Node.decode("STATE", d.state())).queueRev

    private fun play(d: Device) = req(d, Msg.PLAY, Req.play(tracks = listOf(track)))

    private fun playAt(d: Device) = req(d, Msg.QUEUE_OP, Req.queueOp(Enums.QueueCmd.playAt, rev(d), slot = 1))

    private fun shuffleLibrary(d: Device) = req(d, Msg.ACTION, Req.action(Enums.ActionCmd.shuffleLibrary))

    private fun transport(d: Device, cmd: Int) = req(d, Msg.TRANSPORT, Req.transport(cmd, if (cmd == Enums.TransportCmd.seek) 1_000L else null, null))

    private fun taken(a: Answer) = assertTrue("not taken: ${(a as? Answer.Fail)?.let { "${it.code} ${it.text}" }}", a is Answer.Ok)

    private fun refused(a: Answer, text: String) {
        val f = a as? Answer.Fail ?: throw AssertionError("not refused: $a")
        assertEquals(Err.refused, f.code)
        assertEquals(text, f.text)
    }

    @Test
    fun playPlayAtShuffleAndThePlayTransportsAreRefusedWithTheUsbDacText() {
        val d = device("usbdac")
        refused(play(d), USB_DAC_TEXT)
        refused(playAt(d), USB_DAC_TEXT)
        refused(shuffleLibrary(d), USB_DAC_TEXT)
        for (cmd in listOf(Enums.TransportCmd.toggle, Enums.TransportCmd.play, Enums.TransportCmd.next, Enums.TransportCmd.prev, Enums.TransportCmd.seek)) {
            refused(transport(d, cmd), USB_DAC_TEXT)
        }
    }

    @Test
    fun addsPauseAndTheOtherCommandsAreAllowed() {
        val d = device("usbdac")
        assertTrue(req(d, Msg.QUEUE_OP, Req.queueOp(Enums.QueueCmd.add, tracks = listOf(track))) is Answer.Ok)
        assertTrue(req(d, Msg.QUEUE_OP, Req.queueOp(Enums.QueueCmd.playNext, rev(d), tracks = listOf(track))) is Answer.Ok)
        assertTrue(transport(d, Enums.TransportCmd.pause) is Answer.Ok)
        assertTrue(req(d, Msg.TRANSPORT, Req.transport(Enums.TransportCmd.shuffle, null, true)) is Answer.Ok)
        assertTrue(req(d, Msg.ACTION, Req.action(Enums.ActionCmd.favorite, track)) is Answer.Ok)
    }

    @Test
    fun withoutTheFaultEveryPlayIsTaken() {
        val d = device()
        taken(playAt(d))
        taken(play(d))
        taken(shuffleLibrary(d))
        taken(transport(d, Enums.TransportCmd.next))
    }

    @Test
    fun aBusyCardAnswersCardNotAvailableFirst() {
        val d = device("usbdac", "card-seq=busy:60000,ready")
        for (a in listOf(play(d), playAt(d), shuffleLibrary(d))) {
            val f = a as Answer.Fail
            assertEquals(Err.noCard, f.code)
            assertEquals(CardView.REFUSED, f.text)
        }
    }

    @Test
    fun theReceiverRefusalComesBeforeTheUsbDac() {
        val d = device("usbdac", receiver = true, phone = listOf(idol))
        refused(play(d), RECEIVER_TEXT)
        refused(playAt(d), RECEIVER_TEXT)
        refused(shuffleLibrary(d), RECEIVER_TEXT)
        refused(transport(device("usbdac", receiver = true), Enums.TransportCmd.toggle), RECEIVER_TEXT)
    }

    @Test
    fun aPlayingPhoneSourceTakesTheTransport() {
        val d = device("usbdac", receiver = true, phone = listOf(idol, idol))
        assertTrue(transport(d, Enums.TransportCmd.toggle) is Answer.Ok)
        assertTrue(transport(d, Enums.TransportCmd.toggle) is Answer.Ok)
        assertTrue(transport(d, Enums.TransportCmd.next) is Answer.Ok)
        assertTrue(transport(d, Enums.TransportCmd.prev) is Answer.Ok)
    }
}
