package net.seilent.funecomp.state

object Position {
    fun at(p: Playback, rttMs: Long?, now: Long): Long {
        val s = p.state
        val base = s.positionMs ?: return 0
        val duration = s.durationMs
        if (s.playing != true) return clamp(base, duration)
        val sent = s.sentMs
        val sample = s.sampleMs
        val lag = (if (sent != null && sample != null) sent - sample else 0L) + (rttMs ?: 0L) / 2
        return clamp(base + lag + (now - p.receivedAt), duration)
    }

    private fun clamp(v: Long, duration: Long?) = v.coerceIn(0L, duration?.takeIf { it > 0 } ?: Long.MAX_VALUE)
}
