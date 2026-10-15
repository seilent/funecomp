package net.seilent.funecomp.art

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.media.FaceDetector
import net.seilent.funecomp.artist.Codec
import net.seilent.funecomp.artist.Picture
import net.seilent.funecomp.artist.Rect
import java.io.ByteArrayOutputStream

class BitmapCodec(private val log: (String) -> Unit = {}) : Codec {
    override fun decode(bytes: ByteArray, maxSide: Int): Picture? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val side = maxOf(decoded.width, decoded.height)
        if (side <= maxSide) return BitmapPicture(decoded, log)
        val scaled = Bitmap.createScaledBitmap(decoded, maxOf(1, decoded.width * maxSide / side), maxOf(1, decoded.height * maxSide / side), true)
        if (scaled !== decoded) decoded.recycle()
        return BitmapPicture(scaled, log)
    }

    fun faced(bytes: ByteArray, maxSide: Int): Pair<Bitmap, Pair<Double, Double>?>? {
        val p = decode(bytes, maxSide) as? BitmapPicture ?: return null
        return p.bmp to p.face()
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
