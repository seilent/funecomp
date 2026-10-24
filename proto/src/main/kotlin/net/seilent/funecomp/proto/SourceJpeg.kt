package net.seilent.funecomp.proto

import net.seilent.funecomp.proto.gen.Proto

object SourceJpeg {
    class Info(
        val w: Int,
        val h: Int,
        val sof: Int,
        val precision: Int,
        val components: Int,
        val blocks: Int,
        val sampling: Boolean,
        val scans: Int,
        val firstScan: Int,
        val arithmetic: Boolean,
        val dnl: Boolean,
        val complete: Boolean,
        val orientation: Int,
    ) {
        val baseline: Boolean
            get() = (sof == 0xC0 || sof == 0xC1) && precision == 8 && (components == 1 || components == 3) && sampling &&
                blocks <= MAX_BLOCKS && scans == 1 && firstScan == components && !arithmetic && !dnl && h > 0 && complete

        val upright: Boolean get() = orientation <= 1
    }

    const val MAX_BLOCKS = 10

    fun read(b: ByteArray): Info? {
        if (b.size < 4 || u8(b, 0) != 0xFF || u8(b, 1) != 0xD8) return null
        var i = 2
        var sof = -1
        var precision = 0
        var w = 0
        var h = 0
        var components = 0
        var blocks = 0
        var sampling = true
        var scans = 0
        var firstScan = 0
        var arithmetic = false
        var dnl = false
        var orientation = 0
        while (i + 1 < b.size) {
            if (u8(b, i) != 0xFF) return null
            val m = u8(b, i + 1)
            if (m == 0xFF) {
                i++
                continue
            }
            if (m == 0xD9) return Info(w, h, sof, precision, components, blocks, sampling, scans, firstScan, arithmetic, dnl, true, orientation)
            if (m == 0x01 || m in 0xD0..0xD7) {
                i += 2
                continue
            }
            if (i + 4 > b.size) break
            val len = u16(b, i + 2)
            if (len < 2 || i + 2 + len > b.size) break
            val at = i + 4
            when {
                m == 0xE1 && orientation == 0 -> orientation = exifOrientation(b, at, len - 2)
                m == 0xCC -> arithmetic = true
                m == 0xDC -> dnl = true
                m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC && sof < 0 -> {
                    if (len < 8) return null
                    sof = m
                    if (m in 0xC9..0xCB || m in 0xCD..0xCF) arithmetic = true
                    precision = u8(b, at)
                    h = u16(b, at + 1)
                    w = u16(b, at + 3)
                    components = u8(b, at + 5)
                    if (len < 8 + components * 3) return null
                    for (c in 0 until components) {
                        val f = u8(b, at + 7 + c * 3)
                        val hs = f shr 4
                        val vs = f and 0x0F
                        if (hs !in 1..4 || vs !in 1..4) sampling = false
                        blocks += hs * vs
                    }
                    if (components == 1) blocks = 1
                }
                m == 0xDA -> {
                    scans++
                    if (scans == 1) firstScan = u8(b, at)
                    i = entropyEnd(b, i + 2 + len)
                    continue
                }
            }
            i += 2 + len
        }
        return Info(w, h, sof, precision, components, blocks, sampling, scans, firstScan, arithmetic, dnl, false, orientation)
    }

    fun sendable(b: ByteArray, maxBytes: Int = Proto.imgSrcMaxBytes, maxSide: Int = Proto.imgSrcMaxSide): Boolean {
        if (b.size > maxBytes) return false
        val i = read(b) ?: return false
        return i.baseline && i.upright && i.w in 1..maxSide && i.h in 1..maxSide
    }

    private fun entropyEnd(b: ByteArray, from: Int): Int {
        var i = from
        while (i + 1 < b.size) {
            if (u8(b, i) == 0xFF) {
                val n = u8(b, i + 1)
                if (n != 0 && n != 0xFF && n !in 0xD0..0xD7) return i
            }
            i++
        }
        return b.size
    }

    private fun exifOrientation(b: ByteArray, at: Int, n: Int): Int {
        if (n < 14 || u8(b, at) != 'E'.code || u8(b, at + 1) != 'x'.code || u8(b, at + 2) != 'i'.code || u8(b, at + 3) != 'f'.code) return 0
        val t = at + 6
        val end = at + n
        val le = when {
            u8(b, t) == 'I'.code && u8(b, t + 1) == 'I'.code -> true
            u8(b, t) == 'M'.code && u8(b, t + 1) == 'M'.code -> false
            else -> return 0
        }
        fun s(o: Int): Int = if (o + 2 > end) -1 else if (le) u8(b, o) or (u8(b, o + 1) shl 8) else u16(b, o)
        fun l(o: Int): Long = if (o + 4 > end) -1 else if (le) {
            (u8(b, o).toLong()) or (u8(b, o + 1).toLong() shl 8) or (u8(b, o + 2).toLong() shl 16) or (u8(b, o + 3).toLong() shl 24)
        } else {
            (u8(b, o).toLong() shl 24) or (u8(b, o + 1).toLong() shl 16) or (u8(b, o + 2).toLong() shl 8) or u8(b, o + 3).toLong()
        }
        val ifd = l(t + 4)
        if (ifd < 8 || t + ifd + 2 > end) return 0
        val count = s(t + ifd.toInt())
        for (k in 0 until count) {
            val e = t + ifd.toInt() + 2 + k * 12
            if (e + 12 > end) return 0
            if (s(e) == ORIENTATION_TAG) return s(e + 8).coerceAtLeast(0)
        }
        return 0
    }

    private const val ORIENTATION_TAG = 0x0112

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    private fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
}
