package net.seilent.funecomp.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import net.seilent.funecomp.proto.gen.Tok
import kotlin.coroutines.CoroutineContext
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

object CoverBlur {
    class Grid(val w: Int, val h: Int, val px: IntArray)

    @Volatile var context: CoroutineContext = Dispatchers.Default

    private val fxGrid = (Tok.backdrop_coverGrid * 65536.0).roundToLong()

    fun sigma(u: Float): Double = Tok.backdrop_coverSigma.toDouble() * u * Tok.backdrop_coverGrid

    fun width(q: Double): Int {
        val lo = max(1, floor((q - 1) / 2).toInt() * 2 + 1)
        val hi = lo + 2
        return if (q - lo <= hi - q) lo else hi
    }

    fun radius(s: Double): Int = width(sqrt(4 * s * s + 1)) / 2

    fun crop(w: Int, h: Int, bw: Int, bh: Int): IntArray =
        if (w.toLong() * bh >= h.toLong() * bw) intArrayOf((h.toLong() * bw / bh).toInt(), h) else intArrayOf(w, (w.toLong() * bh / bw).toInt())

    fun fits(w: Int, h: Int, gw: Int, gh: Int): Boolean {
        val c = crop(w, h, gw, gh)
        return c[0] >= gw && c[1] >= gh
    }

    fun grid(bw: Int, bh: Int): IntArray = intArrayOf((bw * fxGrid shr 16).toInt(), (bh * fxGrid shr 16).toInt())

    fun area(src: IntArray, w: Int, h: Int, ow: Int, oh: Int): IntArray {
        val c = crop(w, h, ow, oh)
        val cw = c[0]
        val ch = c[1]
        val x0 = (w - cw) / 2
        val y0 = (h - ch) / 2
        val cx = IntArray(ow)
        for (x in 0 until cw) cx[(x.toLong() * ow / cw).toInt()]++
        val out = IntArray(ow * oh)
        val sum = IntArray(ow * 3)
        var cur = -1
        var n = 0
        fun flush() {
            for (x in 0 until ow) {
                val k = cx[x] * n
                val r = (sum[x * 3] + k / 2) / k
                val g = (sum[x * 3 + 1] + k / 2) / k
                val b = (sum[x * 3 + 2] + k / 2) / k
                out[cur * ow + x] = r shl 16 or (g shl 8) or b
            }
        }
        for (y in y0 until y0 + ch) {
            val by = ((y - y0).toLong() * oh / ch).toInt()
            if (by != cur) {
                if (cur >= 0) flush()
                cur = by
                n = 0
                sum.fill(0)
            }
            n++
            val row = y * w + x0
            for (x in 0 until cw) {
                val v = src[row + x]
                val d = (x.toLong() * ow / cw).toInt() * 3
                sum[d] += v shr 16 and 0xFF
                sum[d + 1] += v shr 8 and 0xFF
                sum[d + 2] += v and 0xFF
            }
        }
        if (cur >= 0) flush()
        return out
    }

    private fun axis(src: Int, dst: Int, i: Int, out: IntArray, at: Int) {
        val pos = (((2L * i + 1) * src * 256) / (2L * dst) - 128).coerceIn(0L, (src - 1).toLong() * 256)
        val a = (pos shr 8).toInt()
        out[at] = a
        out[at + 1] = min(a + 1, src - 1)
        out[at + 2] = (pos and 255).toInt()
    }

    private fun mix(a: Int, b: Int, c: Int, d: Int, s: Int, wx: Int, wy: Int): Int {
        val top = (a shr s and 0xFF) * (256 - wx) + (b shr s and 0xFF) * wx
        val bot = (c shr s and 0xFF) * (256 - wx) + (d shr s and 0xFF) * wx
        return (top * (256 - wy) + bot * wy + 32768) shr 16
    }

    fun bilinear(src: IntArray, stride: Int, x0: Int, y0: Int, sw: Int, sh: Int, ow: Int, oh: Int): IntArray {
        val xs = IntArray(ow * 3)
        for (x in 0 until ow) axis(sw, ow, x, xs, x * 3)
        val ys = IntArray(3)
        val out = IntArray(ow * oh)
        for (y in 0 until oh) {
            axis(sh, oh, y, ys, 0)
            val r0 = (y0 + ys[0]) * stride + x0
            val r1 = (y0 + ys[1]) * stride + x0
            val wy = ys[2]
            for (x in 0 until ow) {
                val wx = xs[x * 3 + 2]
                val a = src[r0 + xs[x * 3]]
                val b = src[r0 + xs[x * 3 + 1]]
                val c = src[r1 + xs[x * 3]]
                val d = src[r1 + xs[x * 3 + 1]]
                out[y * ow + x] = mix(a, b, c, d, 16, wx, wy) shl 16 or (mix(a, b, c, d, 8, wx, wy) shl 8) or mix(a, b, c, d, 0, wx, wy)
            }
        }
        return out
    }

    fun boxPass(p: IntArray, off: Int, n: Int, step: Int, r: Int, tmp: IntArray) {
        for (i in 0 until n) tmp[i] = p[off + i * step]
        val w = 2 * r + 1
        var s0 = 0
        var s1 = 0
        var s2 = 0
        for (i in -r..r) {
            val v = tmp[i.coerceIn(0, n - 1)]
            s0 += v shr 16 and 0xFF
            s1 += v shr 8 and 0xFF
            s2 += v and 0xFF
        }
        for (i in 0 until n) {
            p[off + i * step] = (s0 + w / 2) / w shl 16 or ((s1 + w / 2) / w shl 8) or ((s2 + w / 2) / w)
            val a = tmp[(i + r + 1).coerceIn(0, n - 1)]
            val b = tmp[(i - r).coerceIn(0, n - 1)]
            s0 += (a shr 16 and 0xFF) - (b shr 16 and 0xFF)
            s1 += (a shr 8 and 0xFF) - (b shr 8 and 0xFF)
            s2 += (a and 0xFF) - (b and 0xFF)
        }
    }

    fun blur(px: IntArray, w: Int, h: Int, r: Int) = blur(px, w, h, intArrayOf(r, r, r))

    fun blur(px: IntArray, w: Int, h: Int, radii: IntArray) {
        val tmp = IntArray(max(w, h))
        for (r in radii) {
            if (r <= 0) continue
            for (y in 0 until h) boxPass(px, y * w, w, 1, r, tmp)
            for (x in 0 until w) boxPass(px, x, h, w, r, tmp)
        }
    }

    fun cover(src: IntArray, w: Int, h: Int, bw: Int, bh: Int, u: Float): Grid? {
        if (w <= 0 || h <= 0 || bw < 4 || bh < 4) return null
        val g = grid(bw, bh)
        val gw = g[0]
        val gh = g[1]
        if (gw < 1 || gh < 1) return null
        val px = if (fits(w, h, gw, gh)) {
            area(src, w, h, gw, gh)
        } else {
            val c = crop(w, h, bw, bh)
            bilinear(src, w, (w - c[0]) / 2, (h - c[1]) / 2, c[0], c[1], gw, gh)
        }
        blur(px, gw, gh, radius(sigma(u)))
        return Grid(gw, gh, px)
    }
}

fun coverImage(image: ImageBitmap, bw: Int, bh: Int, u: Float): ImageBitmap? {
    val src = IntArray(image.width * image.height)
    image.readPixels(src)
    val g = CoverBlur.cover(src, image.width, image.height, bw, bh, u) ?: return null
    for (i in g.px.indices) g.px[i] = g.px[i] or (0xFF shl 24)
    return Bitmap.createBitmap(g.px, g.w, g.h, Bitmap.Config.ARGB_8888).asImageBitmap()
}
