package net.seilent.funecomp.ui

import net.seilent.funecomp.proto.gen.Tok

object LyricFade {
    enum class Dir { Enter, Close }

    data class Frame(val lyrics: Boolean, val alpha: Float, val shade: Float)

    data class Back(val atMs: Long, val eB: Float)

    const val F = Tok.motion_variantFade
    const val T = 2 * F

    fun frame(dir: Dir, e: Float): Frame {
        val at = e.coerceIn(0f, T)
        val q = Decel.transform(minOf(at, F) / F)
        val enter = dir == Dir.Enter
        val shade = if (enter) q else 1f - q
        return if (at < F) Frame(!enter, 1f - q, shade) else Frame(enter, Decel.transform((at - F) / F), shade)
    }

    fun forward(startMs: Long, frameMs: Long, scale: Float = 1f): Float = ((frameMs - startMs) / scale).coerceIn(0f, T)

    fun back(startMs: Long?, atMs: Long, scale: Float = 1f): Back = Back(atMs, if (startMs == null) 0f else forward(startMs, atMs, scale))

    fun reverse(b: Back, frameMs: Long, scale: Float = 1f): Float = (b.eB - (frameMs - b.atMs) / scale).coerceIn(0f, b.eB)
}
