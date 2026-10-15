package net.seilent.funecomp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import net.seilent.funecomp.proto.gen.Tok

enum class GlyphName(val fill: Boolean, val d: List<Any>) {
    PLAY(true, listOf("M", 9, 5, "L", 27, 16, "L", 9, 27, "Z")),
    PAUSE(true, listOf("M", 7, 5, "H", 13, "V", 27, "H", 7, "Z", "M", 19, 5, "H", 25, "V", 27, "H", 19, "Z")),
    NEXT(true, listOf("M", 5, 5, "L", 21, 16, "L", 5, 27, "Z", "M", 23, 5, "H", 27, "V", 27, "H", 23, "Z")),
    PREVIOUS(true, listOf("M", 27, 5, "L", 11, 16, "L", 27, 27, "Z", "M", 5, 5, "H", 9, "V", 27, "H", 5, "Z")),
    BATTERY(false, listOf("M", 3, 9, "H", 25, "V", 23, "H", 3, "Z", "M", 27, 13, "V", 19)),
    PLUS(false, listOf("M", 16, 4, "V", 28, "M", 4, 16, "H", 28)),
    MINUS(false, listOf("M", 4, 16, "H", 28)),
}

private fun path(g: GlyphName, k: Float): Path {
    val p = Path()
    var x = 0f
    var y = 0f
    var i = 0
    fun num() = (g.d[i++] as Number).toFloat() * k
    while (i < g.d.size) {
        when (g.d[i++]) {
            "M" -> { x = num(); y = num(); p.moveTo(x, y) }
            "L" -> { x = num(); y = num(); p.lineTo(x, y) }
            "H" -> { x = num(); p.lineTo(x, y) }
            "V" -> { y = num(); p.lineTo(x, y) }
            "Z" -> p.close()
        }
    }
    return p
}

@Composable
fun Glyph(name: GlyphName, size: Float, color: Color, modifier: Modifier = Modifier, level: Float = 1f) {
    val ui = LocalUi.current
    val side = ui.px(size)
    val dp = with(LocalDensity.current) { side.toDp() }
    Canvas(modifier.size(dp)) {
        val k = side / Tok.glyphBox
        val p = path(name, k)
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
fun Ring(size: Float, glyph: GlyphName, focused: Boolean, modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    val side = ui.px(size)
    val dp = with(LocalDensity.current) { side.toDp() }
    val stroke = ui.px(if (focused) Tok.ringStrokeFocused else Tok.ringStrokeIdle)
    val color = white(if (focused) Tok.alpha_full else Tok.alpha_mid)
    Box(modifier.size(dp)) {
        Canvas(Modifier.size(dp)) {
            drawCircle(color, radius = (side - stroke) / 2, style = Stroke(stroke))
        }
        Glyph(glyph, size / 2, color, Modifier.at(side / 2, side / 2, 0.5f, 0.5f))
    }
}
