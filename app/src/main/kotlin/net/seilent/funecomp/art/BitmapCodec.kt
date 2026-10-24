package net.seilent.funecomp.art

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.PointF
import android.media.FaceDetector
import net.seilent.funecomp.artist.Codec
import net.seilent.funecomp.artist.Picture
import net.seilent.funecomp.artist.Rect
import net.seilent.funecomp.artist.Size
import java.io.ByteArrayOutputStream

class BitmapCodec(private val log: (String) -> Unit = {}) : Codec {
    override fun bounds(bytes: ByteArray): Size? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return if (o.outWidth > 0 && o.outHeight > 0) Size(o.outWidth, o.outHeight) else null
    }

    override fun decode(bytes: ByteArray, maxSide: Int): Picture? = decode(bytes, maxSide, false)

    private fun decode(bytes: ByteArray, maxSide: Int, srgb: Boolean): Picture? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val budget = maxSide.toLong() * maxSide
        while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > budget) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            if (srgb) inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val side = maxOf(decoded.width, decoded.height)
        if (side <= maxSide) return BitmapPicture(decoded, log)
        val scaled = Bitmap.createScaledBitmap(decoded, maxOf(1, decoded.width * maxSide / side), maxOf(1, decoded.height * maxSide / side), true)
        if (scaled !== decoded) decoded.recycle()
        return BitmapPicture(scaled, log)
    }

    override fun upright(bytes: ByteArray, maxSide: Int, orientation: Int): Picture? {
        val p = decode(bytes, maxSide, true) as? BitmapPicture ?: return null
        if (orientation <= 1 || orientation > 8) return p
        val m = Matrix()
        when (orientation) {
            2 -> m.postScale(-1f, 1f)
            3 -> m.postRotate(180f)
            4 -> m.postScale(1f, -1f)
            5 -> {
                m.postRotate(90f)
                m.postScale(-1f, 1f)
            }
            6 -> m.postRotate(90f)
            7 -> {
                m.postRotate(-90f)
                m.postScale(-1f, 1f)
            }
            8 -> m.postRotate(-90f)
        }
        val turned = Bitmap.createBitmap(p.bmp, 0, 0, p.w, p.h, m, true)
        if (turned === p.bmp) return p
        p.release()
        return BitmapPicture(turned, log)
    }

    fun faced(bytes: ByteArray, maxSide: Int, face: (() -> Pair<Double, Double>?) -> Pair<Double, Double>? = { it() }): Pair<Bitmap, Pair<Double, Double>?>? {
        val p = decode(bytes, maxSide) as? BitmapPicture ?: return null
        return p.bmp to face { p.face() }
    }
}

private class BitmapPicture(val bmp: Bitmap, private val log: (String) -> Unit) : Picture {
    override val w: Int get() = bmp.width
    override val h: Int get() = bmp.height

    private var scaled: Triple<Rect, Int, Int>? = null
    private var out: Bitmap? = null

    override fun face(): Pair<Double, Double>? {
        val scale = minOf(1.0, DETECT_SIDE.toDouble() / maxOf(w, h))
        val dw = (w * scale).toInt() and 1.inv()
        val dh = (h * scale).toInt()
        if (dw < 2 || dh < 2) return null
        val small = Bitmap.createScaledBitmap(bmp, dw, dh, true)
        val rgb = small.copy(Bitmap.Config.RGB_565, false)
        if (small !== bmp) small.recycle()
        try {
            val faces = arrayOfNulls<FaceDetector.Face>(MAX_FACES)
            val n = FaceDetector(dw, dh, MAX_FACES).findFaces(rgb, faces)
            val best = faces.take(n).filterNotNull()
                .filter { it.confidence() >= FaceDetector.Face.CONFIDENCE_THRESHOLD }
                .maxByOrNull { it.eyesDistance() }
            if (best == null) {
                log("face none of $n in ${w}x$h")
                return null
            }
            val p = PointF()
            best.getMidPoint(p)
            val at = p.x / (dw / w.toDouble()) to p.y / (dh / h.toDouble())
            log("face ${at.first.toInt()},${at.second.toInt()} of $n in ${w}x$h")
            return at
        } finally {
            rgb.recycle()
        }
    }

    override fun jpeg(crop: Rect, w: Int, h: Int, quality: Int): ByteArray {
        val key = Triple(crop, w, h)
        val target = out?.takeIf { scaled == key } ?: run {
            out?.takeIf { it !== bmp }?.recycle()
            val part = Bitmap.createBitmap(bmp, crop.x, crop.y, crop.w, crop.h)
            val s = Bitmap.createScaledBitmap(part, w, h, true)
            if (part !== s && part !== bmp) part.recycle()
            scaled = key
            out = s
            s
        }
        val o = ByteArrayOutputStream()
        target.compress(Bitmap.CompressFormat.JPEG, quality, o)
        return o.toByteArray()
    }

    override fun release() {
        out?.takeIf { it !== bmp }?.recycle()
        out = null
        bmp.recycle()
    }

    companion object {
        const val DETECT_SIDE = 640
        const val MAX_FACES = 4
    }
}
