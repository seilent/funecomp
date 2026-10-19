package net.seilent.funecomp.state

object SleepLeft {
    private const val MINUTE_MS = 60_000L

    fun leftMs(p: Playback, rttMs: Long?, now: Long): Long {
        val s = p.state.sleepLeftS ?: 0
        if (s <= 0) return 0
        return (s * 1000 - (rttMs ?: 0) / 2 - p.lateMs - (now - p.receivedAt)).coerceAtLeast(0)
    }

    fun minutes(leftMs: Long): Int = ((leftMs + MINUTE_MS - 1) / MINUTE_MS).toInt()

    fun nextDropMs(leftMs: Long): Long = (leftMs - 1) % MINUTE_MS + 1
}
