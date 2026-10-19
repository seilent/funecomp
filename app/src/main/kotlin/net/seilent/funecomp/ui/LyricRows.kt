package net.seilent.funecomp.ui

import kotlin.math.floor

object LyricRows {
    enum class Kind { PREVIOUS, CURRENT, UPCOMING }

    data class Row(val kind: Kind, val text: String, val slot: Float)

    private const val EPS = 0.001f

    fun synced(texts: List<String>?, index: Int, title: String, next: String?, wrap: (String, Boolean) -> List<String>, room: Float, cp: Float, np: Float): List<Row> {
        val none = texts == null
        val cur = when {
            texts == null -> wrap(title, true)
            index >= 0 -> wrap(texts[index], true)
            else -> emptyList()
        }
        val curN = minOf(maxOf(cur.size, if (none || index >= 0) 1 else 0), floor((room + EPS) / cp).toInt())
        val upcoming = if (texts == null) listOfNotNull(next?.takeIf { it.isNotEmpty() }) else texts.drop(index + 1).filter { it.isNotEmpty() }
        val prev = if (texts != null && index >= 1) wrap(texts[index - 1], false) else emptyList()
        val showPrev = prev.isNotEmpty() && curN * cp + prev.size * np + (if (upcoming.isNotEmpty()) np else 0f) <= room + EPS
        val out = ArrayList<Row>()
        var slot = 0f
        if (showPrev) {
            for (t in prev) {
                out += Row(Kind.PREVIOUS, t, slot)
                slot += np
            }
        }
        for (i in 0 until curN) {
            if (i < cur.size) out += Row(Kind.CURRENT, cur[i], slot)
            slot += cp
        }
        for (t in upcoming) {
            if (slot + np > room + EPS) break
            for (w in wrap(t, false)) {
                if (slot + np > room + EPS) break
                out += Row(Kind.UPCOMING, w, slot)
                slot += np
            }
        }
        return out
    }

    fun plain(texts: List<String>, wrap: (String) -> List<String>): List<String> = texts.flatMap { if (it.isEmpty()) listOf("") else wrap(it) }

    fun plainRoom(room: Float, pitch: Float) = floor((room + EPS) / pitch) * pitch
}
