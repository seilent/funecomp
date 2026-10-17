package net.seilent.funecomp.state

import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Proto

class Reconnect(
    private val steps: List<Long> = Proto.backoffMs,
    private val jitter: Double = 0.2,
    private val random: () -> Double = Math::random,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val tailAfterMs: Long = TAIL_AFTER_MS,
    private val tailMs: Long = TAIL_MS,
    private val goodMs: Long = GOOD_MS,
) {
    enum class Stop { VERSION, REFUSED, REPLACED, UNKNOWN }

    var stopped: Stop? = null
        private set
    var failures = 0
        private set
    var inFlight = false
        private set
    var paused = false
        private set

    private var downSince: Long? = null
    private var liveAt: Long? = null
    private var kicked = false

    val tail: Boolean get() = downSince?.let { clock() - it >= tailAfterMs } ?: false

    fun begin(): Boolean {
        if (inFlight || stopped != null || paused) return false
        inFlight = true
        kicked = false
        liveAt = null
        if (downSince == null) downSince = clock()
        return true
    }

    fun live() {
        liveAt = clock()
    }

    fun ended(reachedHello: Boolean) {
        inFlight = false
        val now = clock()
        if (reachedHello && liveAt?.let { now - it >= goodMs } == true) downSince = now
        liveAt = null
        failures = if (reachedHello) 1 else failures + 1
    }

    fun bye(reason: Int) {
        stopped = when (reason) {
            Enums.ByeReason.version -> Stop.VERSION
            Enums.ByeReason.refused -> Stop.REFUSED
            Enums.ByeReason.replaced -> Stop.REPLACED
            Enums.ByeReason.companionOff, Enums.ByeReason.shutdown -> stopped
            else -> Stop.UNKNOWN
        }
    }

    fun pause() {
        paused = true
    }

    fun kick() {
        kicked = true
    }

    fun foreground() = resume()

    fun userAction() {
        paused = false
        resume()
    }

    private fun resume() {
        stopped = null
        failures = 0
        downSince = null
    }

    fun delayMs(): Long? {
        if (stopped != null || paused) return null
        if (failures == 0 || kicked) return 0
        if (tail) return tailMs
        val base = steps[minOf(failures, steps.size) - 1]
        return minOf((base * (1 - jitter + 2 * jitter * random())).toLong(), steps.last())
    }

    companion object {
        const val TAIL_AFTER_MS = 5 * 60_000L
        const val TAIL_MS = 5 * 60_000L
        const val GOOD_MS = 60_000L
    }
}
