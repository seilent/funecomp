package net.seilent.funecomp.artist

data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

object Crop {
    fun rect(srcW: Int, srcH: Int, dstW: Int, dstH: Int, faceY: Double? = null, point: Pair<Int, Int>? = null): Rect {
        require(srcW > 0 && srcH > 0 && dstW > 0 && dstH > 0)
        val w: Int
        val h: Int
        if (srcW.toLong() * dstH > srcH.toLong() * dstW) {
            h = srcH
            w = maxOf(1, (srcH.toLong() * dstW / dstH).toInt())
        } else {
            w = srcW
            h = maxOf(1, (srcW.toLong() * dstH / dstW).toInt())
        }
        val cx = point?.let { it.first * srcW / PER_MILLE } ?: (srcW / 2.0)
        val cy = point?.let { it.second * srcH / PER_MILLE } ?: faceY ?: (srcH / 3.0)
        val x = (cx - w / 2.0).toInt().coerceIn(0, srcW - w)
        val y = (cy - h / 2.0).toInt().coerceIn(0, srcH - h)
        return Rect(x, y, w, h)
    }

    fun point(srcW: Int, srcH: Int, window: Rect, faceY: Double?): Pair<Int, Int> {
        val x = if (window.w < srcW) window.x + window.w / 2.0 else srcW / 2.0
        val y = if (window.h < srcH) window.y + window.h / 2.0 else faceY ?: (srcH / 3.0)
        return (x / srcW * PER_MILLE).toInt().coerceIn(0, PER_MILLE.toInt()) to (y / srcH * PER_MILLE).toInt().coerceIn(0, PER_MILLE.toInt())
    }

    private const val PER_MILLE = 1000.0
}
