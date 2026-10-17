package net.seilent.funecomp.state

import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.state.MediaGate.Mode
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaGateTest {
    private val local = State(source = Enums.Source.local, track = 7, title = "Airbag", playing = true)

    @Test
    fun activeRules() {
        val g = MediaGate()
        assertEquals(Mode.ACTIVE, g.live(local, setting = true, sinkToDap = false))
        assertEquals(Mode.OFF, g.live(local.copy(source = Enums.Source.phone), setting = true, sinkToDap = false))
        assertEquals(Mode.OFF, g.live(local.copy(track = null), setting = true, sinkToDap = false))
        assertEquals(Mode.OFF, g.live(local.copy(source = null), setting = true, sinkToDap = false))
        assertEquals(Mode.OFF, g.live(local, setting = false, sinkToDap = false))
        assertEquals(Mode.OFF, g.live(local, setting = true, sinkToDap = true))
        assertEquals(Mode.ACTIVE, g.live(local.copy(playing = false), setting = true, sinkToDap = false))
    }

    @Test
    fun sourceFlipMidPlay() {
        val g = MediaGate()
        g.live(local, true, false)
        assertEquals(Mode.OFF, g.live(local.copy(source = Enums.Source.phone), true, false))
        assertEquals(Mode.ACTIVE, g.live(local, true, false))
    }

    @Test
    fun dropHoldsThroughOneAttempt() {
        val g = MediaGate()
        g.live(local, true, false)
        assertEquals(Mode.HELD, g.closed())
        assertEquals(Mode.ACTIVE, g.live(local, true, false))
        assertEquals(Mode.HELD, g.closed())
        assertEquals(Mode.OFF, g.closed())
        assertEquals(Mode.OFF, g.closed())
    }

    @Test
    fun dropWhileOffStaysOff() {
        val g = MediaGate()
        g.live(local, false, false)
        assertEquals(Mode.OFF, g.closed())
        g.live(local, true, false)
        assertEquals(Mode.OFF, g.off())
    }
}
