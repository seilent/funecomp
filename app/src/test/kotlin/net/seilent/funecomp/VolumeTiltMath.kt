package net.seilent.funecomp

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import net.seilent.funecomp.proto.gen.Tok
import kotlin.math.cos
import kotlin.math.sin

internal fun tiltedPoint(p: Offset, touch: Offset, plate: Rect): Offset {
    val w = plate.width
    val h = plate.height
    val r = Math.toRadians(Tok.motion_volumeTiltRate.toDouble())
    val rx = r * (((touch.y - plate.top) / h).coerceIn(0f, 1f) - 0.5)
    val ry = -r * (((touch.x - plate.left) / w).coerceIn(0f, 1f) - 0.5)
    val d = Tok.motion_volumeTiltEye * w
    val x = (p.x - plate.center.x).toDouble()
    val y = (p.y - plate.center.y).toDouble()
    val y1 = y * cos(rx)
    val z1 = y * sin(rx)
    val x2 = x * cos(ry) + z1 * sin(ry)
    val z2 = -x * sin(ry) + z1 * cos(ry)
    val k = d / (d + z2)
    return Offset((plate.center.x + x2 * k).toFloat(), (plate.center.y + y1 * k).toFloat())
}

internal fun flatPoint(q: Offset, touch: Offset, plate: Rect): Offset {
    var p = q
    repeat(FLAT_STEPS) { p += q - tiltedPoint(p, touch, plate) }
    return p
}

private const val FLAT_STEPS = 20
