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

    @Test
    fun remoteVolumeSpec() {
        val v1 = State(volume = 123, volumePct = 4, volumeStepPct = 2, maxVolumePct = 5)
        assertEquals(RemoteVolume.Spec(true, 3, 2), RemoteVolume.spec(v1))
        assertEquals(4, RemoteVolume.percent(2, v1))
        assertEquals(5, RemoteVolume.percent(3, v1))
        val ap80 = State(volume = 140, volumePct = 55, volumeStepPct = 10, maxVolumePct = 55)
        assertEquals(RemoteVolume.Spec(true, 6, 6), RemoteVolume.spec(ap80))
        assertEquals(55, RemoteVolume.percent(6, ap80))
        assertEquals(50, RemoteVolume.percent(5, ap80))
        assertEquals(0, RemoteVolume.percent(0, ap80))
        val old = State(volume = 128)
        assertEquals(RemoteVolume.Spec(false, 100, 50), RemoteVolume.spec(old))
        assertEquals(null, RemoteVolume.percent(3, old))
        assertEquals(RemoteVolume.Spec(false, 100, 50), RemoteVolume.spec(old.copy(volumeStepPct = 0, maxVolumePct = 80, volumePct = 50)))
    }
}
