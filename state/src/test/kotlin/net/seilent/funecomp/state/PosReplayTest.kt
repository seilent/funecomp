package net.seilent.funecomp.state

import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PosReplayTest {
    private val q = 23L

    private data class Step(val at: Long, val back: Long, val before: Long?, val after: Long?, val old: Long, val new: Long, val start: Boolean)

    private fun lines(name: String) = PosReplayTest::class.java.getResourceAsStream("/posclock/$name")!!.bufferedReader().readLines().filter { it.isNotBlank() }

    private fun n(v: String) = if (v == "-" || v == "None") null else v.toLong()

    private fun hello(clock: Boolean) = HelloReply(
        1, 10, "v1", "x", 240, 320, 240, 320, 16384, 30_000, 5_000,
        if (clock) Groups.all else Groups.all and Groups.mask("stateClock").inv(), 7, 1,
    )

    private fun replay(name: String, window: Long, clock: Boolean = true): List<Step> {
        var d = DeviceState()
        var track: String? = null
        var run = 0
        val out = ArrayList<Step>()
        for (l in lines(name)) {
            val p = l.split('\t')
            when (p[0]) {
                "hello" -> {
                    d = d.withHello(hello(clock)).copy(clock = DeviceClock(window))
                    track = null
                }
                "rtt" -> d = d.copy(rttMs = n(p[1]))
                else -> {
                    val s = State(positionMs = n(p[2]), sentMs = n(p[3]), sampleMs = n(p[4]), playing = p[1] == "1")
                    val at = p[6].toLong()
                    n(p[5])?.let { d = d.copy(rttMs = it) }
                    val before = d
                    d = d.withState(s, at)
                    val same = track == p[7] && before.playback.state.playing == true && s.playing == true
                    if (same && (!clock || before.clock.offset != null)) {
                        val old = before.positionMs(at)
                        val new = d.positionMs(at)
                        if (new < old) out += Step(at, old - new, before.clock.offset, d.clock.offset, old, new, run == 1)
                    }
                    run = if (same) run + 1 else if (s.playing == true) 1 else 0
                    track = p[7]
                }
            }
        }
        return out
    }

    private fun expected(name: String) = lines(name).map { l -> l.split('\t').let { listOf(it[0].toLong(), it[1].toLong(), n(it[2]), n(it[3])) } }

    private fun List<Step>.rows() = map { listOf(it.at, it.back, it.before, it.after) }

    private fun structural(steps: List<Step>): List<Long> {
        val broken = steps.filter { s ->
            val moved = (s.after ?: 0) - (s.before ?: 0)
            if (moved == 0L) s.back > q else abs(s.back - moved) > q
        }
        assertTrue(broken.all { it.start })
        return broken.map { it.back }
    }

    private fun stamps() = lines("stamps.txt").map { it.toLong() }

    @Test
    fun theLyrslideRunMatchesTheIndependentReplay() {
        val steps = replay("run.tsv", DeviceClock.WINDOW_MS)
        assertEquals(expected("run-back.tsv"), steps.rows())
        assertEquals(emptyList<Long>(), structural(steps))
        assertTrue(steps.none { s -> stamps().any { it > s.new && it <= s.old } })
    }

    @Test
    fun theDriftRunMatchesTheIndependentReplay() {
        val steps = replay("drift.tsv", DeviceClock.WINDOW_MS)
        assertEquals(expected("drift-back.tsv"), steps.rows())
        assertEquals(listOf(667L), structural(steps))
    }

    @Test
    fun anUnboundedWindowStepsBackAtTheOldImageStartStamp() {
        val steps = replay("run.tsv", Long.MAX_VALUE)
        assertEquals(expected("run-back-all.tsv"), steps.rows())
        assertEquals(listOf(589L), structural(steps))
        assertTrue(steps.none { s -> stamps().any { it > s.new && it <= s.old } })
    }

    @Test
    fun todaysFormulaStepsBack18Times() {
        val steps = replay("run.tsv", DeviceClock.WINDOW_MS, clock = false)
        assertEquals(18, steps.size)
        assertEquals(128L, steps.maxOf { it.back })
        assertTrue(steps.none { s -> stamps().any { it > s.new && it <= s.old } })
    }
}
