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

    val device: Size = Size(devices.maxOfOrNull { it.w } ?: 0, devices.maxOfOrNull { it.h } ?: 0)

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
        const val STRETCH = 1.5

        fun phone(w: Int, h: Int, stretch: Double = STRETCH): Size =
            Size(Math.round(minOf(w, h) / stretch).toInt(), Math.round(maxOf(w, h) / stretch).toInt())

        fun grow(old: Size?, new: Size): Size {
            if (old == null) return new
            val a = maxOf(old.w, old.h) to minOf(old.w, old.h)
            val b = maxOf(new.w, new.h) to minOf(new.w, new.h)
            return if (b.first > a.first || (b.first == a.first && b.second > a.second)) new else old
        }

        fun devices(s: String?): Map<String, Size> = (s ?: "").split(',').mapNotNull { e ->
            val board = e.substringBefore('=', "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Size.parse(e.substringAfter('='))?.let { board to it }
        }.toMap()

        fun devices(m: Map<String, Size>): String = m.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" }

        fun of(phone: String?, devices: String?): Targets = Targets(Size.parse(phone), devices(devices).toSortedMap().values.toList())
    }
}
