package net.seilent.funecomp.artist

data class Size(val w: Int, val h: Int) {
    override fun toString() = "${w}x$h"

    companion object {
        fun parse(s: String?): Size? {
            val (w, h) = s?.split('x')?.takeIf { it.size == 2 }?.map { it.toIntOrNull() ?: return null } ?: return null
            return if (w > 0 && h > 0) Size(w, h) else null
        }
    }
}

class Targets(val phone: Size? = null, val devices: List<Size> = emptyList()) {
    val sizes: List<Size> = listOfNotNull(phone, phone?.let { Size(it.h, it.w) }) + devices

    val side: Int = sizes.maxOfOrNull { maxOf(it.w, it.h) } ?: 0

    val key: String = "${phone ?: "-"} ${devices.joinToString(",").ifEmpty { "-" }}"

    fun score(w: Int, h: Int): Double {
        if (sizes.isEmpty()) return Double.MAX_VALUE
        if (w <= 0 || h <= 0) return 0.0
        return sizes.minOf { t ->
            val r = Crop.rect(w, h, t.w, t.h)
            minOf(r.w.toDouble() / t.w, r.h.toDouble() / t.h)
        }
    }

    fun width(w: Int, h: Int): Int = if (w <= 0 || h <= 0) 0 else sizes.maxOfOrNull { maxOf(it.w, ceil(it.h.toLong() * w, h.toLong())) } ?: 0

    private fun ceil(a: Long, b: Long) = ((a + b - 1) / b).toInt()

    companion object {
        val NONE = Targets()
    }
}
