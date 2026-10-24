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
}

object Backdrop {
    const val MAX_BYTES = 262_144
    val qualities = listOf(90, 85, 80, 75, 70, 60, 50, 40)

    fun fits(s: Size, w: Int, h: Int): Boolean = crop(s.w, s.h, w, h, null) != null

    fun crop(srcW: Int, srcH: Int, w: Int, h: Int, face: Pair<Double, Double>?): Rect? =
        Crop.rect(srcW, srcH, w, h, face?.first, face?.second).takeIf { it.w >= w && it.h >= h }

    fun render(p: Picture, w: Int, h: Int, upscale: Boolean = false): ByteArray? {
        val face = p.face()
        val r = crop(p.w, p.h, w, h, face) ?: if (upscale) Crop.rect(p.w, p.h, w, h, face?.first, face?.second) else return null
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
