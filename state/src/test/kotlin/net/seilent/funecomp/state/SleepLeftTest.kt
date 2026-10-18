package net.seilent.funecomp.state

import net.seilent.funecomp.proto.State
import org.junit.Assert.assertEquals
import org.junit.Test

class SleepLeftTest {
    private fun timer(s: Long?, sample: Long = 1000, sent: Long = 1000) =
        Playback(State(sleepLeftS = s, sampleMs = sample, sentMs = sent, playing = false), receivedAt = 1000)

    @Test
    fun countsDownFromReceivedAtAndRoundsUp() {
        val p = timer(1800)
        assertEquals(1_800_000L, SleepLeft.leftMs(p, null, 1000))
        assertEquals(30, SleepLeft.minutes(SleepLeft.leftMs(p, null, 1000)))
        assertEquals(30, SleepLeft.minutes(SleepLeft.leftMs(p, null, 1001)))
        assertEquals(1_740_000L, SleepLeft.leftMs(p, null, 61_000))
        assertEquals(29, SleepLeft.minutes(SleepLeft.leftMs(p, null, 61_000)))
        assertEquals(29, SleepLeft.minutes(SleepLeft.leftMs(p, null, 61_001)))
        assertEquals(0L, SleepLeft.leftMs(p, null, 1_801_000))
        assertEquals(0L, SleepLeft.leftMs(p, null, 2_000_000))
        assertEquals(0, SleepLeft.minutes(0))
    }

    @Test
    fun takesHalfTheRttButNotTheSampleAge() {
        assertEquals(1_799_950L, SleepLeft.leftMs(timer(1800, sample = 800, sent = 1000), 100, 1000))
    }

    @Test
    fun noTimerIsZero() {
        assertEquals(0L, SleepLeft.leftMs(timer(null), null, 1000))
        assertEquals(0L, SleepLeft.leftMs(timer(0), null, 1000))
        assertEquals(0L, DeviceState().sleepLeftMs(1000))
    }

    @Test
    fun nextDropIsWhenTheRoundedMinuteChanges() {
        assertEquals(60_000L, SleepLeft.nextDropMs(1_800_000))
        assertEquals(1L, SleepLeft.nextDropMs(1_740_001))
        assertEquals(59_999L, SleepLeft.nextDropMs(59_999))
    }

    @Test
    fun deviceStateDelegates() {
        val d = DeviceState(playback = timer(1800), rttMs = 100)
        assertEquals(1_739_950L, d.sleepLeftMs(61_000))
    }
}
