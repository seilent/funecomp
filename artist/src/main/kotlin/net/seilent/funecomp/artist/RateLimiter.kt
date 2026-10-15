package net.seilent.funecomp.artist

class RateLimiter(private val intervalMs: Long, private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var next = Long.MIN_VALUE

    @Synchronized
    fun reserve(): Long {
        val now = clock()
        val at = maxOf(now, next)
        next = at + intervalMs
        return at - now
    }

    @Synchronized
    fun backoff(ms: Long) {
        next = maxOf(next, clock() + ms)
    }

    fun acquire() {
        val wait = reserve()
        if (wait > 0) Thread.sleep(wait)
    }

    companion object {
        fun perMinute(n: Int, clock: () -> Long = { System.nanoTime() / 1_000_000 }) = RateLimiter(60_000L / n, clock)
    }
}
