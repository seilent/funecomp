package net.seilent.funecomp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import net.seilent.funecomp.proto.gen.Tok
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

enum class GlyphName(val fill: Boolean, val d: List<Any>) {
    BACK(false, listOf("M", 28, 16, "L", 5, 16, "M", 14, 7, "L", 5, 16, "L", 14, 25)),
    PLAY(true, listOf("M", 9, 5, "L", 27, 16, "L", 9, 27, "Z")),
    PAUSE(true, listOf("M", 7, 5, "H", 13, "V", 27, "H", 7, "Z", "M", 19, 5, "H", 25, "V", 27, "H", 19, "Z")),
    PAUSE_WIDE(true, listOf("M", 6.43, 5, "H", 12.43, "V", 27, "H", 6.43, "Z", "M", 19.57, 5, "H", 25.57, "V", 27, "H", 19.57, "Z")),
    SKIP_NEXT(true, listOf("M", 1.7, 8, "L", 13.8, 16, "L", 1.7, 24, "Z", "M", 13.8, 8, "L", 25.9, 16, "L", 13.8, 24, "Z", "M", 27.35, 8, "H", 30.3, "V", 24, "H", 27.35, "Z")),
    SKIP_PREVIOUS(true, listOf("M", 30.3, 8, "L", 18.2, 16, "L", 30.3, 24, "Z", "M", 18.2, 8, "L", 6.1, 16, "L", 18.2, 24, "Z", "M", 4.65, 8, "H", 1.7, "V", 24, "H", 4.65, "Z")),
    NEXT(true, listOf("M", 5, 5, "L", 21, 16, "L", 5, 27, "Z", "M", 23, 5, "H", 27, "V", 27, "H", 23, "Z")),
    PREVIOUS(true, listOf("M", 27, 5, "L", 11, 16, "L", 27, 27, "Z", "M", 5, 5, "H", 9, "V", 27, "H", 5, "Z")),
    SHUFFLE(false, listOf("M", 4, 9, "H", 10, "L", 22, 23, "H", 28, "M", 4, 23, "H", 10, "L", 22, 9, "H", 28, "M", 24, 5, "L", 28, 9, "L", 24, 13, "M", 24, 19, "L", 28, 23, "L", 24, 27)),
    REPEAT(false, listOf("M", 13.92, 4.18, "A", 12, 12, 0, "1", "0", 22, 5.61, "M", 5.41, 2.45, "L", 13.92, 4.18, "L", 11.76, 10.58)),
    ONE(false, listOf("M", 18.2, 10.19, "V", 22.24, "M", 15.1, 12.24, "L", 16.3, 11.39, "M", 14.2, 20.24, "H", 22.2)),
    HEART(true, listOf("M", 16, 28, "L", 5.5, 17, "A", 7, 7, 0, "0", "1", 16, 9, "A", 7, 7, 0, "0", "1", 26.5, 17, "Z")),
    BLUETOOTH(false, listOf("M", 8, 10, "L", 24, 22, "L", 16, 29, "V", 3, "L", 24, 10, "L", 8, 22)),
    BATTERY(false, listOf("M", 3, 9, "H", 25, "V", 23, "H", 3, "Z", "M", 27, 13, "V", 19)),
    PLUS(false, listOf("M", 16, 4, "V", 28, "M", 4, 16, "H", 28)),
    MINUS(false, listOf("M", 4, 16, "H", 28)),
    MIC(true, listOf("M", 12, 7, "A", 4, 4, 0, "0", "1", 20, 7, "V", 17.5, "A", 4, 4, 0, "0", "1", 12, 17.5, "Z", "M", 8, 12.6, "V", 17.4, "A", 8, 8, 0, "0", "0", 24, 17.4, "V", 12.6, "H", 21.8, "V", 17.4, "A", 5.8, 5.8, 0, "0", "1", 10.2, 17.4, "V", 12.6, "Z", "M", 14.85, 25.3, "V", 27.6, "H", 17.15, "V", 25.3, "Z", "M", 11.5, 27.6, "V", 30, "H", 20.5, "V", 27.6, "Z")),
    SEARCH_RING(true, listOf("M", 9.31, 12.94, "A", 10, 10, 0, "1", "1", 29.31, 12.94, "A", 10, 10, 0, "1", "1", 9.31, 12.94, "Z", "M", 12.75, 12.94, "A", 6.56, 6.56, 0, "1", "0", 25.87, 12.94, "A", 6.56, 6.56, 0, "1", "0", 12.75, 12.94, "Z")),
    SEARCH_HANDLE(true, listOf("M", 15.63, 20.58, "L", 11.67, 16.62, "L", 3.4, 24.89, "A", 2.8, 2.8, 0, "0", "0", 7.36, 28.85, "Z")),
}

internal fun path(g: GlyphName, k: Float): Path {
    val arcs = g.d.indices.filter { g.d[it] == "A" }.flatMap { listOf(it + 3, it + 4, it + 5) }.toSet()
    val svg = g.d.mapIndexed { i, t -> if (t is Number && i !in arcs) (t.toFloat() * k).toString() else t.toString() }
    return PathParser().parsePathString(svg.joinToString(" ")).toPath()
}

@Composable
fun Glyph(name: GlyphName, size: Float, color: Color, modifier: Modifier = Modifier, level: Float = 1f, glow: (() -> PressState?)? = null) {
    val ui = LocalUi.current
    val side = ui.px(size)
    val dp = with(LocalDensity.current) { side.toDp() }
    val k = side / Tok.glyphBox
    val p = remember(name, k) { path(name, k) }
    val mask = remember(name, side, ui) { arrayOfNulls<ImageBitmap>(1) }
    Canvas(modifier.requiredSize(dp)) {
        val lit = glow?.invoke()?.lit ?: 0f
        if (lit > 0f) {
            val m = mask[0] ?: pressGlyphGlow(name, side.roundToInt(), ui.px(Tok.pressGlowDilate).roundToInt(), ui.px(Tok.pressGlow).roundToInt()).also { mask[0] = it }
            drawImage(m, Offset((this.size.width - m.width) / 2f, (this.size.height - m.height) / 2f), alpha = Tok.pressGlowAlpha * lit)
        }
        if (name.fill) {
            drawPath(p, color, style = Fill)
        } else {
            drawPath(p, color, style = Stroke(Tok.glyphStroke * k, cap = StrokeCap.Butt, join = StrokeJoin.Miter))
        }
        if (name == GlyphName.BATTERY) {
            drawRect(
                color,
                Offset(Tok.statusBatteryX * k, Tok.statusBatteryY * k),
                Size(Tok.statusBatteryW * k * level.coerceIn(0f, 1f), Tok.statusBatteryH * k),
            )
        }
    }
}

sealed interface Look {
    data object Touch : Look

    data class Toggle(val on: Boolean) : Look

    data class Focus(val focused: Boolean) : Look
}

class RingStyle(val alpha: Float, val stroke: Float)

fun ringLook(look: Look): RingStyle = when (look) {
    Look.Touch -> RingStyle(Tok.alpha_full, Tok.ringStrokeIdle)
    is Look.Toggle -> RingStyle(if (look.on) Tok.alpha_full else Tok.alpha_mid, Tok.ringStrokeIdle)
    is Look.Focus -> if (look.focused) RingStyle(Tok.alpha_full, Tok.ringStrokeFocused) else RingStyle(Tok.alpha_mid, Tok.ringStrokeIdle)
}

@Composable
fun Ring(size: Float, glyph: GlyphName, look: Look, modifier: Modifier = Modifier, press: PressState? = null, tag: String? = null, stroke: Float? = null, glyphSize: Float = size / 2, one: Boolean = false) {
    val ui = LocalUi.current
    val side = ui.px(size)
    val dp = with(LocalDensity.current) { side.toDp() }
    val style = ringLook(look).let { if (stroke != null) RingStyle(it.alpha, stroke) else it }
    val width = ui.px(style.stroke)
    val color = white(style.alpha)
    val halo = remember(side, width) { arrayOfNulls<ImageBitmap>(1) }
    Box(modifier.requiredSize(dp).pressScale(press)) {
        if (press != null && !press.scaled) {
            Canvas(Modifier.requiredSize(dp)) {
                val lit = press.lit
                if (lit > 0f) {
                    val d = side.roundToInt()
                    val m = halo[0] ?: ringHalo(d, width, maxOf(1, (Tok.vol_glowRing * d / Tok.vol_ring).roundToInt())).also { halo[0] = it }
                    drawImage(m, Offset((this.size.width - m.width) / 2f, (this.size.height - m.height) / 2f), alpha = lit)
                    drawCircle(Color.White, (side - 2 * width) / 2, center, alpha = Tok.vol_discAlpha * lit)
                }
            }
        }
        Canvas(Modifier.requiredSize(dp).then(if (tag != null) Modifier.testTag("$tag.ring") else Modifier)) {
            if (one) {
                drawArc(color, Tok.ringArcStart, Tok.npRepeatOneSweep, false, Offset(width / 2, width / 2), Size(side - width, side - width), style = Stroke(width))
            } else {
                drawCircle(color, radius = (side - width) / 2, style = Stroke(width))
            }
        }
        Glyph(glyph, glyphSize, color, Modifier.at(side / 2, side / 2, 0.5f, 0.5f).then(if (tag != null) Modifier.testTag("$tag.glyph") else Modifier))
        if (one) {
            val a = Math.toRadians(Tok.npRepeatOneAt.toDouble())
            val r = ui.px(LayoutTok.npRepeatOneR)
            Glyph(GlyphName.ONE, LayoutTok.npRepeatOne, color, Modifier.at(side / 2 + r * sin(a).toFloat(), side / 2 - r * cos(a).toFloat(), 0.5f, 0.5f).then(if (tag != null) Modifier.testTag("$tag.one") else Modifier))
        }
    }
}
