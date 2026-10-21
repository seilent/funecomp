package net.seilent.funecomp.artist

data class LyricSources(
    val lrclib: Boolean = true,
    val netease: Boolean = true,
    val order: List<String> = ORDER,
) {
    val ordered: List<String>
        get() = order.filter { on(it) }

    fun on(id: String): Boolean = when (id) {
        LRCLIB -> lrclib
        NETEASE -> netease
        else -> false
    }

    fun moved(id: String, by: Int): LyricSources {
        val i = order.indexOf(id)
        val j = i + by
        if (i < 0 || j !in order.indices) return this
        return copy(order = order.toMutableList().apply { add(j, removeAt(i)) })
    }

    companion object {
        const val LRCLIB = "lrclib"
        const val NETEASE = "netease"
        val ORDER = listOf(LRCLIB, NETEASE)

        fun order(s: String?): List<String> {
            val known = (s ?: "").split(',').map { it.trim() }.filter { it in ORDER }.distinct()
            return known + ORDER.filter { it !in known }
        }
    }
}
