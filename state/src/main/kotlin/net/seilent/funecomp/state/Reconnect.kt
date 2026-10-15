package net.seilent.funecomp.state

import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Proto

class Reconnect(
    private val steps: List<Long> = Proto.backoffMs,
    private val jitter: Double = 0.2,
    private val random: () -> Double = Math::random,
) {
    enum class Stop { VERSION, REFUSED, REPLACED, UNKNOWN }

    var stopped: Stop? = null
        private set
    var failures = 0
        private set
    var inFlight = false
        private set

    fun begin(): Boolean {
        if (inFlight || stopped != null) return false
        inFlight = true
        return true
    }

    fun ended(reachedHello: Boolean) {
        inFlight = false
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

    fun foreground() = resume()

    fun userAction() = resume()

    private fun resume() {
        stopped = null
        failures = 0
    }

    fun delayMs(): Long? {
        if (stopped != null) return null
        if (failures == 0) return 0
        val base = steps[minOf(failures, steps.size) - 1]
        return minOf((base * (1 - jitter + 2 * jitter * random())).toLong(), steps.last())
    }
}
