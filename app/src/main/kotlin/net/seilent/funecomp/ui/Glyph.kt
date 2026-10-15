package net.seilent.funecomp.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import net.seilent.funecomp.proto.gen.Tok

enum class GlyphName(val fill: Boolean, val d: List<Any>) {
    BACK(false, listOf("M", 28, 16, "L", 5, 16, "M", 14, 7, "L", 5, 16, "L", 14, 25)),
    PLAY(true, listOf("M", 9, 5, "L", 27, 16, "L", 9, 27, "Z")),
    PAUSE(true, listOf("M", 7, 5, "H", 13, "V", 27, "H", 7, "Z", "M", 19, 5, "H", 25, "V", 27, "H", 19, "Z")),
    NEXT(true, listOf("M", 5, 5, "L", 21, 16, "L", 5, 27, "Z", "M", 23, 5, "H", 27, "V", 27, "H", 23, "Z")),
    PREVIOUS(true, listOf("M", 27, 5, "L", 11, 16, "L", 27, 27, "Z", "M", 5, 5, "H", 9, "V", 27, "H", 5, "Z")),
    SHUFFLE(false, listOf("M", 4, 9, "H", 10, "L", 22, 23, "H", 28, "M", 4, 23, "H", 10, "L", 22, 9, "H", 28, "M", 24, 5, "L", 28, 9, "L", 24, 13, "M", 24, 19, "L", 28, 23, "L", 24, 27)),
    REPEAT(false, listOf("M", 23, 9, "A", 10, 10, 0, "1", "0", 26, 16, "M", 18, 4, "L", 23, 9, "L", 18, 14)),
    REPEAT_ONE(false, listOf("M", 23, 9, "A", 10, 10, 0, "1", "0", 26, 16, "M", 18, 4, "L", 23, 9, "L", 18, 14, "M", 13, 13, "L", 16, 11, "V", 21)),
    HEART(true, listOf("M", 16, 28, "L", 5.5, 17, "A", 7, 7, 0, "0", "1", 16, 9, "A", 7, 7, 0, "0", "1", 26.5, 17, "Z")),
    BLUETOOTH(false, listOf("M", 8, 10, "L", 24, 22, "L", 16, 29, "V", 3, "L", 24, 10, "L", 8, 22)),
    BATTERY(false, listOf("M", 3, 9, "H", 25, "V", 23, "H", 3, "Z", "M", 27, 13, "V", 19)),
    PLUS(false, listOf("M", 16, 4, "V", 28, "M", 4, 16, "H", 28)),
    MINUS(false, listOf("M", 4, 16, "H", 28)),
}

private fun path(g: GlyphName, k: Float): Path {
    val arcs = g.d.indices.filter { g.d[it] == "A" }.flatMap { listOf(it + 3, it + 4, it + 5) }.toSet()
    val svg = g.d.mapIndexed { i, t -> if (t is Number && i !in arcs) (t.toFloat() * k).toString() else t.toString() }
    return PathParser().parsePathString(svg.joinToString(" ")).toPath()
}

@Composable
fun Glyph(name: GlyphName, size: Float, color: Color, modifier: Modifier = Modifier, level: Float = 1f) {
    val ui = LocalUi.current
    val side = ui.px(size)
    val dp = with(LocalDensity.current) { side.toDp() }
    val k = side / Tok.glyphBox
    val p = remember(name, k) { path(name, k) }
    Canvas(modifier.requiredSize(dp)) {
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

@Composable
fun Ring(size: Float, glyph: GlyphName, focused: Boolean, modifier: Modifier = Modifier, toggle: Boolean = false, on: Boolean = false, swaps: Int = 0, pressed: Boolean = false) {
    val ui = LocalUi.current
    val side = ui.px(size)
    val dp = with(LocalDensity.current) { side.toDp() }
    val stroke = ui.px(if (focused) Tok.ringStrokeFocused else Tok.ringStrokeIdle)
    val lit = if (toggle) on else focused
    val color = white(if (lit) Tok.alpha_full else Tok.alpha_mid)
    val scale = remember { Animatable(1f) }
    val alpha = remember { Animatable(1f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(glyph, swaps) {
        if (first[0]) {
            first[0] = false
            return@LaunchedEffect
        }
        launch {
            scale.snapTo(Tok.motion_toggleScale)
            scale.animateTo(1f, tween(Tok.motion_toggle.toInt(), easing = OutBack))
        }
        alpha.snapTo(0f)
        alpha.animateTo(1f, tween(Tok.motion_toggleSwap.toInt(), easing = LinearEasing))
    }
    val press by animateFloatAsState(if (pressed) Tok.motion_pressScale else 1f, tween(Tok.motion_press.toInt(), easing = LinearEasing), label = "ringPress")
    Box(modifier.requiredSize(dp).scale(press)) {
        Canvas(Modifier.requiredSize(dp)) {
            drawCircle(color, radius = (side - stroke) / 2, style = Stroke(stroke))
        }
        Glyph(glyph, size / 2, color, Modifier.at(side / 2, side / 2, 0.5f, 0.5f).scale(scale.value).alpha(alpha.value))
    }
}
