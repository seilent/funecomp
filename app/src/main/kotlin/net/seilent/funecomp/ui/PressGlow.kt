package net.seilent.funecomp.ui

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import net.seilent.funecomp.proto.gen.Tok
import kotlin.math.roundToInt

internal var glowsBuilt = 0
    private set

internal var glowMasksBuilt = 0
    private set

internal var glowMasksLive = 0
    private set

class GlowWord {
    internal var layout: TextLayoutResult? = null
    internal var at: LayoutCoordinates? = null
    internal var row: LayoutCoordinates? = null
}

internal data class GlowKey(val text: String, val size: TextUnit, val weight: FontWeight?, val tracking: TextUnit, val box: IntSize, val dilate: Int, val reach: Int)

internal class GlowMask(val key: GlowKey, val image: ImageBitmap, val pad: Int)

internal fun releaseGlow(p: PressState) {
    if (p.mask == null) return
    p.mask = null
    glowMasksLive--
}

private fun maskOf(p: PressState, layout: TextLayoutResult, d: Int, g: Int): GlowMask {
    val style = layout.layoutInput.style
    val key = GlowKey(layout.layoutInput.text.text, style.fontSize, style.fontWeight, style.letterSpacing, layout.size, d, g)
    p.mask?.let { if (it.key == key) return it }
    releaseGlow(p)
    val m = GlowMask(key, textGlow(layout, d, g), 1 + d + g)
    p.mask = m
    glowMasksBuilt++
    glowMasksLive++
    return m
}

internal fun textGlow(layout: TextLayoutResult, d: Int, g: Int): ImageBitmap {
    val w = layout.size.width
    val h = layout.size.height
    val pad = 1 + d + g
    val bw = w + 2 * pad
    val bh = h + 2 * pad
    val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(b)
    canvas.drawColor(android.graphics.Color.BLACK)
    canvas.translate(pad.toFloat(), pad.toFloat())
    canvas.clipRect(-1f, -1f, w + 1f, h + 1f)
    layout.multiParagraph.paint(Canvas(canvas), Color.White)
    val px = IntArray(bw * bh)
    b.getPixels(px, 0, bw, 0, 0, bw, bh)
    val cover = IntArray(bw * bh) { i -> maxOf(px[i] shr 16 and 0xFF, px[i] shr 8 and 0xFF, px[i] and 0xFF) }
    val wide = dilate(cover, bw, bh, d)
    for (i in px.indices) px[i] = wide[i] shl 16 or (wide[i] shl 8) or wide[i]
    CoverBlur.blur(px, bw, bh, IntArray(3) { k -> (g + 2 - k) / 3 })
    for (i in px.indices) px[i] = (px[i] shr 16 and 0xFF) shl 24 or 0xFFFFFF
    b.setPixels(px, 0, bw, 0, 0, bw, bh)
    return b.asImageBitmap()
}

private fun dilate(src: IntArray, w: Int, h: Int, r: Int): IntArray {
    if (r <= 0) return src
    val across = IntArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        var m = 0
        for (i in maxOf(0, x - r)..minOf(w - 1, x + r)) m = maxOf(m, src[y * w + i])
        across[y * w + x] = m
    }
    val out = IntArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        var m = 0
        for (i in maxOf(0, y - r)..minOf(h - 1, y + r)) m = maxOf(m, across[i * w + x])
        out[y * w + x] = m
    }
    return out
}

fun Modifier.pressGlow(ui: Ui, word: GlowWord, press: () -> PressState?): Modifier = onGloballyPositioned { word.row = it }.drawWithContent {
    val p = press()
    val look = p?.lit ?: 0f
    val text = word.layout
    val at = word.at
    val row = word.row
    if (p != null && look > 0f && text != null && at != null && row != null && at.isAttached && row.isAttached) {
        val m = maskOf(p, text, ui.px(Tok.pressGlowDilate).roundToInt(), ui.px(Tok.pressGlow).roundToInt())
        val o = row.localPositionOf(at, Offset.Zero)
        drawImage(m.image, Offset(o.x - m.pad, o.y - m.pad), alpha = Tok.pressGlowAlpha * look)
    }
    drawContent()
}

internal fun glyphGlow(name: GlyphName, side: Int, reach: Float) = glyphMask(name, side, (reach / 3).roundToInt().coerceAtLeast(1), 0)

internal fun pressGlyphGlow(name: GlyphName, side: Int, spread: Int, grow: Int) = glyphMask(name, side, maxOf(1, grow / 3), spread)

private fun glyphMask(name: GlyphName, side: Int, r: Int, spread: Int): ImageBitmap {
    val k = side / Tok.glyphBox
    val paint = Paint().apply {
        color = android.graphics.Color.WHITE
        isAntiAlias = true
        if (!name.fill) {
            style = Paint.Style.STROKE
            strokeWidth = Tok.glyphStroke * k
            strokeCap = Paint.Cap.BUTT
            strokeJoin = Paint.Join.MITER
        }
    }
    return glow(side, r, 0f, spread) { it.drawPath(path(name, k).asAndroidPath(), paint) }
}

internal fun ringGlow(side: Int, stroke: Float, reach: Float) = ringMask(side, stroke, (reach / 3).roundToInt().coerceAtLeast(1))

internal fun ringHalo(side: Int, stroke: Float, grow: Int) = ringMask(side, stroke, maxOf(1, grow / 3))

private fun ringMask(side: Int, stroke: Float, r: Int): ImageBitmap {
    val paint = Paint().apply {
        color = android.graphics.Color.WHITE
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = stroke
    }
    return glow(side, r, side / 2f, 0) { it.drawCircle(side / 2f, side / 2f, (side - stroke) / 2, paint) }
}

private fun glow(side: Int, r: Int, inside: Float, spread: Int, cover: (android.graphics.Canvas) -> Unit): ImageBitmap {
    val pad = spread + 3 * r
    val b = Bitmap.createBitmap(side + 2 * pad, side + 2 * pad, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(b)
    canvas.drawColor(android.graphics.Color.BLACK)
    canvas.translate(pad.toFloat(), pad.toFloat())
    cover(canvas)
    val px = IntArray(b.width * b.height)
    b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
    if (spread > 0) {
        val wide = dilate(IntArray(px.size) { px[it] shr 16 and 0xFF }, b.width, b.height, spread)
        for (i in px.indices) px[i] = wide[i] shl 16 or (wide[i] shl 8) or wide[i]
    }
    CoverBlur.blur(px, b.width, b.height, r)
    val c = b.width / 2f
    for (y in 0 until b.height) for (x in 0 until b.width) {
        val dx = x + 0.5f - c
        val dy = y + 0.5f - c
        val a = if (dx * dx + dy * dy < inside * inside) 0 else px[y * b.width + x] shr 16 and 0xFF
        px[y * b.width + x] = a shl 24 or 0xFFFFFF
    }
    b.setPixels(px, 0, b.width, 0, 0, b.width, b.height)
    glowsBuilt++
    return b.asImageBitmap()
}
