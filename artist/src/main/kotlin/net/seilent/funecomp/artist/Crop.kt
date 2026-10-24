package net.seilent.funecomp.artist

data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

object Crop {
    fun rect(srcW: Int, srcH: Int, dstW: Int, dstH: Int, faceY: Double? = null): Rect {
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
        val cx = srcW / 2.0
        val cy = faceY ?: (srcH / 3.0)
        val x = (cx - w / 2.0).toInt().coerceIn(0, srcW - w)
        val y = (cy - h / 2.0).toInt().coerceIn(0, srcH - h)
        return Rect(x, y, w, h)
    }
}
