package net.seilent.funecomp.state

object SleepLeft {
    fun leftMs(p: Playback, rttMs: Long?, now: Long): Long {
        val s = p.state.sleepLeftS ?: 0
        if (s <= 0) return 0
        return (s * 1000 - (rttMs ?: 0) / 2 - p.lateMs - (now - p.receivedAt)).coerceAtLeast(0)
    }
}
