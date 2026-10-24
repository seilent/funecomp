package net.seilent.funecomp.artist

interface Picture {
    val w: Int
    val h: Int
    fun face(): Pair<Double, Double>?
    fun jpeg(crop: Rect, w: Int, h: Int, quality: Int): ByteArray
    fun release() {}
}

fun interface Codec {
    fun decode(bytes: ByteArray, maxSide: Int): Picture?

    fun bounds(bytes: ByteArray): Size? = decode(bytes, Int.MAX_VALUE)?.let { p ->
        try {
            Size(p.w, p.h)
        } finally {
            p.release()
        }
    }

    fun upright(bytes: ByteArray, maxSide: Int, orientation: Int): Picture? = if (orientation <= 1) decode(bytes, maxSide) else null
}

object Backdrop {
    const val MAX_BYTES = 262_144
    val qualities = listOf(90, 85, 80, 75, 70, 60, 50, 40)

    fun fits(s: Size, w: Int, h: Int): Boolean = crop(s.w, s.h, w, h, null) != null

    fun side(src: Size, window: Size, floor: Int, cap: Int): Int {
        val long = maxOf(src.w, src.h)
        val f = listOf(window, Size(window.h, window.w)).maxOf { t ->
            val r = Crop.rect(src.w, src.h, t.w, t.h)
            maxOf(t.w.toDouble() / r.w, t.h.toDouble() / r.h)
        }
        if (f >= 1.0) return minOf(long, cap)
        return minOf(long, maxOf(floor, minOf(cap, kotlin.math.ceil(long * f).toInt())))
    }

    fun crop(srcW: Int, srcH: Int, w: Int, h: Int, face: Pair<Double, Double>?): Rect? =
        Crop.rect(srcW, srcH, w, h, face?.second).takeIf { it.w >= w && it.h >= h }

    fun render(p: Picture, w: Int, h: Int, upscale: Boolean = false): ByteArray? {
        val face = p.face()
        val r = crop(p.w, p.h, w, h, face) ?: if (upscale) Crop.rect(p.w, p.h, w, h, face?.second) else return null
        for (q in qualities) {
            val b = p.jpeg(r, w, h, q)
            if (b.size <= MAX_BYTES) return b
        }
        return null
    }

    fun render(codec: Codec, bytes: ByteArray, w: Int, h: Int, upscale: Boolean = false): ByteArray? {
        val p = codec.decode(bytes, maxOf(w, h) * 4) ?: return null
        try {
            return render(p, w, h, upscale)
        } finally {
            p.release()
        }
    }
}
