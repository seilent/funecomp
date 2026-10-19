package net.seilent.funecomp.ui

import net.seilent.funecomp.proto.gen.Tok

object LyricSlide {
    data class Slide(val from: Int, val at: Long, val d: Long)

    fun start(shown: Int, index: Int, stamps: List<Long>, positionMs: Long, now: Long): Slide? {
        if (index < 0 || index != shown + 1) return null
        val stamp = stamps[index]
        val late = maxOf(0L, positionMs - stamp)
        val full = Tok.motion_lyricSlide.toLong()
        val d = stamps.getOrNull(index + 1)?.let { minOf(full, it - stamp) } ?: full
        return if (d - late >= Tok.motion_scroll.toLong()) Slide(shown, now - late, d) else null
    }

    fun p(s: Slide, now: Long) = ExpOut6.transform(((now - s.at).toFloat() / s.d).coerceIn(0f, 1f))

    fun done(s: Slide, now: Long) = now - s.at >= s.d
}
