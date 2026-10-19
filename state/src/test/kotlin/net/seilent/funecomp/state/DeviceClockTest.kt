package net.seilent.funecomp.state

import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToLong

class DeviceClockTest {
    private fun hello(features: Long) = HelloReply(1, 10, "v1", "x", 240, 320, 240, 320, 16384, 30_000, 5_000, features, 7, 1)

    private val clocked = DeviceState().withHello(hello(Groups.all))
    private val plain = DeviceState().withHello(hello(Groups.all and Groups.mask("stateClock").inv()))

    private fun st(pos: Long, sample: Long?, sent: Long? = sample, playing: Boolean = true, held: Boolean? = null) =
        State(track = 1, positionMs = pos, sampleMs = sample, sentMs = sent, playing = playing, positionHeld = held)

    @Test
    fun aSyntheticRunStaysInsideTheErrorModel() {
        val rnd = java.util.Random(19)
        val r = -34.1e-6
        val q = 23L
        val w = DeviceClock.WINDOW_MS
        val round = 1L
        val start = 4_000_000L
        fun dev(t: Long) = 3_000_000L + (t * (1 - r)).roundToLong()
        val seen = ArrayList<Triple<Long, Long, Long>>()
        var d = clocked
        repeat(180) { k ->
            val ts = 1_000_000L + k * 10_000L
            val e = rnd.nextInt(501).toLong() + 80L * (k % 6)
            val at = ts + e
            val sample = dev(ts)
            val p = sample - start
            val before = d
            d = d.withState(st(p - p % q, sample), at)
            seen += Triple(at, at - sample, e)
            val window = seen.filter { it.first > at - w }
            val min = window.last { it.second == window.minOf { x -> x.second } }
            assertEquals(min.second, d.clock.offset)
            val err = d.positionMs(at) - (dev(at) - start)
            assertTrue("state $k error $err excess ${min.third}", abs(err) <= abs(r) * w + q + min.third + round)
            val old = before.clock.offset ?: return@repeat
            val step = before.positionMs(at) - d.positionMs(at)
            val moved = min.second - old
            if (moved == 0L) assertTrue("state $k step $step", step <= q) else assertTrue("state $k step $step moved $moved", abs(step - moved) <= q)
        }
    }

    @Test
    fun aFixedDelayGivesNoLateness() {
        var d = clocked
        for (k in 0 until 20) {
            d = d.withState(st(k * 10_000L, 50_000L + k * 10_000L), 900_000L + k * 10_000L + 120)
            assertEquals(0L, d.playback.lateMs)
        }
    }

    @Test
    fun theLatenessIsTheDeliveryAboveTheWindowMin() {
        val d = clocked.withState(st(0, 50_000), 100_100).withState(st(10_000, 60_000), 110_400)
        assertEquals(300L, d.playback.lateMs)
        assertEquals(10_000L + 300 + 500, d.positionMs(110_900))
    }

    @Test
    fun aDeviceSeekBackShowsAtTheNextState() {
        val d = clocked.withState(st(60_000, 50_000), 100_100).withState(st(30_000, 60_000), 110_300)
        assertEquals(30_000L + 200 + 1_000, d.positionMs(111_300))
    }

    @Test
    fun pausedStatesFeedTheOffset() {
        val d = clocked.withState(st(5_000, 50_000, playing = false), 100_050).withState(st(5_000, 60_000), 110_250)
        assertEquals(200L, d.playback.lateMs)
        assertEquals(5_000L + 200 + 750, d.positionMs(111_000))
    }

    @Test
    fun aStepUpIsHeldForTheWindowThenAdopted() {
        val c = DeviceClock(60_000).add(0, 100).add(10_000, 5_100).add(50_000, 5_100)
        assertEquals(100L, c.offset)
        assertEquals(100L, c.add(59_999, 5_100).offset)
        assertEquals(5_100L, c.add(60_000, 5_100).offset)
        var d = clocked
        d = d.withState(st(0, 50_000), 100_000)
        d = d.withState(st(10_000, 60_000 - 5_000), 110_000)
        assertEquals(5_000L, d.playback.lateMs)
        d = d.withState(st(20_000, 50_000 + DeviceClock.WINDOW_MS - 10_000 - 5_000), 100_000 + DeviceClock.WINDOW_MS - 10_000)
        assertEquals(5_000L, d.playback.lateMs)
        d = d.withState(st(30_000, 50_000 + DeviceClock.WINDOW_MS - 5_000), 100_000 + DeviceClock.WINDOW_MS)
        assertEquals(0L, d.playback.lateMs)
    }

    @Test
    fun aLowerSampleIsAdoptedAtOnce() {
        val d = clocked.withState(st(0, 50_000), 100_400).withState(st(10_000, 60_000), 110_100)
        assertEquals(0L, d.playback.lateMs)
        assertEquals(50_100L, d.clock.offset)
        val next = d.withState(st(20_000, 70_000), 120_250)
        assertEquals(150L, next.playback.lateMs)
    }

    @Test
    fun aHelloResetsTheClock() {
        val d = clocked.withState(st(0, 50_000), 100_100)
        assertEquals(50_100L, d.clock.offset)
        val fresh = d.withHello(hello(Groups.all))
        assertNull(fresh.clock.offset)
        assertEquals(0L, fresh.withState(st(0, 900_000), 100_900).playback.lateMs)
    }

    @Test
    fun theOffsetComesFromSentMsAndTheAgeFromSampleMs() {
        val d = clocked.withState(st(0, 50_000, 50_000), 100_300).withState(st(10_000, 59_970, 60_000), 110_100)
        assertEquals(50_100L, d.clock.offset)
        assertEquals(0L, d.playback.lateMs)
        assertEquals(10_000L + 30 + 400, d.positionMs(110_500))
    }

    @Test
    fun aStateWithoutStampsOrAPeerWithoutTheBitGivesTodaysNumbers() {
        val d = clocked.withState(st(0, 50_000), 100_100).withState(st(10_000, 60_000), 110_400)
        assertEquals(300L, d.playback.lateMs)
        val noSent = d.withState(st(20_000, 70_000, null), 120_400)
        assertEquals(0L, noSent.playback.lateMs)
        assertEquals(noSent.clock, d.clock)
        assertEquals(20_000L + 100, noSent.positionMs(120_500))
        val noSample = d.withState(st(20_000, null, 70_000), 120_400)
        assertEquals(0L, noSample.playback.lateMs)
        assertEquals(20_000L + 100, noSample.positionMs(120_500))
        val old = plain.withState(st(0, 50_000), 100_100).withState(st(10_000, 59_970, 60_000), 110_400)
        assertEquals(0L, old.playback.lateMs)
        assertNull(old.clock.offset)
        assertEquals(10_000L + 30 + 100, old.positionMs(110_500))
    }

    @Test
    fun aHeldPositionIsNotAged() {
        val d = clocked.withRtt(40).withState(st(0, 50_000), 100_100).withState(st(30_000, 60_000, 60_003, held = true), 110_403)
        assertEquals(300L, d.playback.lateMs)
        assertEquals(30_000L, d.positionMs(110_400))
        assertEquals(30_000L, d.positionMs(200_000))
        val released = d.withState(st(30_020, 60_240, held = false), 110_650)
        assertEquals(50_100L, released.clock.offset)
        assertEquals(30_020L + 310 + 20 + 100, released.positionMs(110_750))
    }

    @Test
    fun aHeldStateFeedsTheOffset() {
        val d = clocked.withState(st(0, 50_000, held = true), 100_050).withState(st(40, 60_000, held = false), 110_350)
        assertEquals(50_050L, d.clock.offset)
        assertEquals(300L, d.playback.lateMs)
    }
}
