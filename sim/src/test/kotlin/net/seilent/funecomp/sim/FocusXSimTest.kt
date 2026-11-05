package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.gen.Proto
import org.junit.Assert.assertEquals
import org.junit.Test

class FocusXSimTest {
    private fun device(minor: Int = Proto.minor) = Device(Options(Board.of("v1"), Faults(emptyList()), minor = minor), Library.synthetic()) { 1000L }

    @Test
    fun theFocusXBitIsServedFromMinor17() {
        assertEquals(17, Proto.minor)
        assertEquals(Groups.mask("imgFocusX"), device().helloReply().features and Groups.mask("imgFocusX"))
        assertEquals(0L, device(16).helloReply().features and Groups.mask("imgFocusX"))
    }
}
