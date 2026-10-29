package net.seilent.funecomp.artist

import net.seilent.funecomp.proto.SourceJpeg
import net.seilent.funecomp.proto.gen.Proto
import java.util.zip.CRC32

class Full(val bytes: ByteArray, val w: Int, val h: Int, val crc: Long, val focus: Pair<Int, Int>?)

class FullSource(
    private val cache: ArtCache,
    private val codec: Codec,
    private val maxBytes: Int = Proto.imgSrcMaxBytes,
    private val maxSide: Int = Proto.imgSrcMaxSide,
    private val log: (String) -> Unit = {},
) {
    fun of(key: String): Full? {
        val e = cache.hit(key) ?: return null
        val sha = cache.sha(e)
        val step = cache.refusals(key, sha)
        if (step >= REFUSALS) return null
        val enc = "$ENCODE:$step"
        cache.full(key)?.takeIf { it.encode == enc }?.let { m ->
            val b = cache.fullBytes(key)
            if (b != null && crc(b) == m.crc) return Full(b, m.w, m.h, m.crc, m.focus)
        }
        val src = cache.source(key) ?: return null
        val info = SourceJpeg.read(src)
        val copy = step == 0 && SourceJpeg.sendable(src, maxBytes, maxSide)
        val orientation = info?.orientation ?: 0
        val out = if (copy) Triple(src, info!!.w, info.h) else encode(src, orientation, step) ?: return null
        val focus = focus(src, orientation)
        val c = crc(out.first)
        cache.putFull(key, ArtCache.Full(c, out.second, out.third, enc, focus, copy), if (copy) null else out.first)
        log("art full ${e.name} ${if (copy) "copy" else "encode $step"} ${out.second}x${out.third} bytes=${out.first.size}")
        return Full(out.first, out.second, out.third, c, focus)
    }

    fun cropped(key: String, crc: Long) = cache.putCropCrc(key, crc)

    fun refused(key: String): Int {
        val e = cache.hit(key) ?: return REFUSALS
        return cache.refused(key, cache.sha(e))
    }

    private fun encode(src: ByteArray, orientation: Int, step: Int): Triple<ByteArray, Int, Int>? {
        val bounds = codec.bounds(src)?.let { maxOf(it.w, it.h) } ?: return null
        val floor = minOf(FLOOR_SIDE, bounds, maxSide)
        var side = if (step == 0) minOf(bounds, DECODE_SIDE, maxSide) else floor
        while (true) {
            val p = codec.upright(src, side, orientation) ?: return null
            try {
                for (q in Backdrop.qualities) {
                    val b = p.jpeg(Rect(0, 0, p.w, p.h), p.w, p.h, q)
                    if (b.size <= maxBytes && SourceJpeg.sendable(b, maxBytes, maxSide)) return Triple(b, p.w, p.h)
                }
            } finally {
                p.release()
            }
            if (side <= floor) return null
            side = maxOf(floor, side * 3 / 4)
        }
    }

    private fun focus(src: ByteArray, orientation: Int): Pair<Int, Int>? {
        val p = codec.upright(src, FOCUS_SIDE, orientation) ?: return null
        try {
            val f = p.face() ?: return null
            return PER_MILLE / 2 to (f.second / p.h * PER_MILLE).toInt().coerceIn(0, PER_MILLE)
        } finally {
            p.release()
        }
    }

    private fun crc(b: ByteArray) = CRC32().apply { update(b) }.value

    companion object {
        const val ENCODE = 1
        const val REFUSALS = 2
        const val FLOOR_SIDE = 2048
        const val DECODE_SIDE = 4096
        const val FOCUS_SIDE = 1024
        private const val PER_MILLE = 1000
    }
}
