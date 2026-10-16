package net.seilent.funecomp.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import net.seilent.funecomp.proto.gen.Tok
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

private const val BACK_OVERSHOOT = 1.70158f
private const val EXP_SIX = 6f

val OutBack = Easing { t ->
    val x = t - 1f
    x * x * ((BACK_OVERSHOOT + 1f) * x + BACK_OVERSHOOT) + 1f
}

val Decel = CubicBezierEasing(Tok.motion_decel_x1, Tok.motion_decel_y1, Tok.motion_decel_x2, Tok.motion_decel_y2)

val Strip = CubicBezierEasing(Tok.motion_strip_x1, Tok.motion_strip_y1, Tok.motion_strip_x2, Tok.motion_strip_y2)

val ExpOut6 = Easing { t -> 1f - (exp(EXP_SIX * (1f - t)) - 1f) / (exp(EXP_SIX) - 1f) }

val ExpIn6 = Easing { t -> (exp(EXP_SIX * t) - 1f) / (exp(EXP_SIX) - 1f) }

val Pop = Easing { t -> if (t >= 1f) 1f else 1f - 2f.pow(-10f * t) }

val EaseIn = Easing { t -> t * t }

val EaseOut = Easing { t -> t * (2f - t) }

val Sine = Easing { t -> sin(t * PI.toFloat() / 2f) }
