package net.seilent.funecomp.state

import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaysTest {
    private val wall = 1_800_000_000_000L
    private val boot = 500_000L

    private fun st(pos: Long, sample: Long, playing: Boolean = true, track: Long = 7, dur: Long = 200_000, source: Int = Enums.Source.local) =
        State(source = source, track = track, title = "Airbag", artist = "Radiohead", durationMs = dur, positionMs = pos, sampleMs = boot + sample, playing = playing, libRev = 3)

    private class Run(val plays: Plays = Plays()) {
        val events = ArrayList<Plays.Event>()
        fun at(s: State, arrival: Long) = plays.frame(s, arrival).also { events += it }
        val scrobbles get() = events.filterIsInstance<Plays.Event.Scrobble>()
    }

    private fun Run.play(from: Long, to: Long, step: Long = 10_000, startAt: Long = 0, track: Long = 7, dur: Long = 200_000, lag: Long = 40) {
        var p = from
        while (p <= to) {
            at(st(p, startAt + p - from, track = track, dur = dur), wall + startAt + p - from + lag)
            p += step
        }
    }

    @Test
    fun shortTrackNeverScrobbles() {
        val r = Run()
        r.play(0, 29_000, step = 1_000, dur = 29_000)
        assertTrue(r.scrobbles.isEmpty())
        assertEquals(1, r.events.count { it is Plays.Event.NowPlaying })
    }

    @Test
    fun halfOfTheDurationScrobblesOnceWithTheStartTime() {
        val r = Run()
        r.play(0, 120_000)
        assertEquals(1, r.scrobbles.size)
        assertEquals(100_000, r.scrobbles[0].play.playedMs)
        assertEquals(wall + 40, r.scrobbles[0].play.startMs)
    }

    @Test
    fun fourMinutesScrobbleALongTrack() {
        val r = Run()
        r.play(0, 230_000, dur = 600_000)
        assertTrue(r.scrobbles.isEmpty())
        r.play(240_000, 250_000, startAt = 240_000, dur = 600_000)
        assertEquals(1, r.scrobbles.size)
    }

    @Test
    fun seekPastHalfCountsOnlyTimePlayed() {
        val r = Run()
        r.at(st(0, 0), wall)
        r.at(st(10_000, 10_000), wall + 10_000)
        r.at(st(150_000, 11_000), wall + 11_000)
        assertTrue(r.scrobbles.isEmpty())
        assertEquals(11_000, r.plays.open!!.playedMs)
    }

    @Test
    fun pausedTimeDoesNotCountAndResumeContinues() {
        val r = Run()
        r.at(st(0, 0), wall)
        r.at(st(50_000, 50_000, playing = false), wall + 50_000)
        r.at(st(50_000, 400_000, playing = false), wall + 400_000)
        r.at(st(50_000, 400_100), wall + 400_100)
        r.at(st(100_000, 450_100), wall + 450_100)
        assertEquals(100_000, r.plays.open!!.playedMs)
        assertEquals(1, r.scrobbles.size)
        assertEquals(wall, r.scrobbles[0].play.startMs)
    }

    @Test
    fun repeatOneStartsANewPlay() {
        val r = Run()
        r.play(0, 190_000, dur = 195_000)
        r.at(st(2_000, 197_000, dur = 195_000), wall + 197_040)
        r.play(2_000, 102_000, startAt = 197_000, dur = 195_000)
        assertEquals(2, r.scrobbles.size)
        assertEquals(wall + 195_000 + 40, r.scrobbles[1].play.startMs)
    }

    @Test
    fun seekBackToZeroContinuesThePlay() {
        val r = Run()
        r.play(0, 60_000)
        r.at(st(0, 61_000), wall + 61_040)
        r.play(0, 50_000, startAt = 61_000)
        assertEquals(1, r.scrobbles.size)
        assertEquals(wall + 40, r.scrobbles[0].play.startMs)
    }

    @Test
    fun bufferedFramesCountBySampleTime() {
        val r = Run()
        for (i in 0..6) r.at(st(i * 10_000L, i * 10_000L), wall + 60_000 + i * 8L)
        assertEquals(60_000, r.plays.open!!.playedMs)
        assertEquals(wall + 48, r.plays.open!!.startMs)
    }

    @Test
    fun dropAndReconnectAfterTheScrobbleKeepsOneEntry() {
        val r = Run()
        r.play(0, 300_000, dur = 600_000)
        assertEquals(1, r.scrobbles.size)
        r.plays.session()
        r.at(st(320_000, 320_000, dur = 600_000), wall + 320_000 + 900)
        r.play(330_000, 500_000, startAt = 330_000, dur = 600_000)
        assertEquals(1, r.scrobbles.size)
    }

    @Test
    fun processRestartMidPlayContinuesFromTheStoredPlay() {
        val r = Run()
        r.play(0, 60_000)
        val stored = Play.parse(r.plays.open!!.line())!!
        val again = Run(Plays(stored))
        again.play(70_000, 130_000, startAt = 70_000)
        assertEquals(1, again.scrobbles.size)
        assertEquals(wall + 40, again.scrobbles[0].play.startMs)
        assertEquals(1, r.scrobbles.size + again.scrobbles.size)
    }

    @Test
    fun aLaterPlayOfTheSameTrackAfterADropIsNew() {
        val r = Run()
        r.play(0, 120_000)
        r.plays.session()
        r.play(0, 120_000, startAt = 3_600_000)
        assertEquals(2, r.scrobbles.size)
    }

    @Test
    fun phoneSourceIsIgnored() {
        val r = Run()
        for (i in 0..20) r.at(st(i * 10_000L, i * 10_000L, source = Enums.Source.phone), wall + i * 10_000L)
        assertTrue(r.events.isEmpty())
        assertNull(r.plays.open)
    }

    @Test
    fun nextTrackStartsANewPlay() {
        val r = Run()
        r.play(0, 50_000)
        r.play(0, 110_000, startAt = 51_000, track = 8)
        assertEquals(1, r.scrobbles.size)
        assertEquals(8L, r.scrobbles[0].play.track)
        assertEquals(2, r.events.count { it is Plays.Event.NowPlaying })
    }
}
