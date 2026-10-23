package net.seilent.funecomp.ui

import net.seilent.funecomp.proto.gen.Tok

object VariantMotion {
    enum class Dir { In, Out }

    data class Frame(val from: Boolean, val alpha: Float, val scale: Float, val shade: Float)

    const val OUT = Tok.motion_nowPlayingFade
    const val IN = Tok.motion_nowPlayingIn
    const val BACK = Tok.motion_variantFade

    fun total(dir: Dir) = if (dir == Dir.In) OUT + IN else 2 * BACK

    fun rest(dir: Dir) = if (dir == Dir.In) IN else BACK

    fun frame(dir: Dir, e: Float): Frame {
        val at = e.coerceIn(0f, total(dir))
        val shade = 1f - Pop.transform(minOf(at / Tok.motion_nowPlayingFade, 1f))
        if (dir == Dir.Out) {
            return if (at < BACK) Frame(true, 1f - Decel.transform(at / BACK), 1f, shade) else Frame(false, Decel.transform((at - BACK) / BACK), 1f, shade)
        }
        if (at < OUT) return Frame(true, 1f - Pop.transform(at / OUT), 1f, shade)
        val t = EaseIn.transform((at - OUT) / IN)
        return Frame(false, t, Tok.motion_nowPlayingScale + (1f - Tok.motion_nowPlayingScale) * t, shade)
    }
}
