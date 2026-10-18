package net.seilent.funecomp.artist

import net.seilent.funecomp.state.Section
import java.text.Normalizer
import java.util.Locale

class AutoEqList private constructor(val rows: List<Row>) {
    class Row(val entry: AutoEqIndex.Entry, val letter: Int, val match: String)

    sealed interface Item {
        val key: String
    }

    data class Divider(val letter: Int) : Item {
        override val key get() = "div:${Section.letter(letter)}"
    }

    data class Hit(val row: Row) : Item {
        override val key get() = row.entry.path
    }

    class View(val items: List<Item>) {
        val index = items.withIndex().associate { it.value.key to it.index }
        val letters = items.mapNotNull { (it as? Divider)?.letter }.toSet()
    }

    fun view(query: String) = View(items(query))

    fun filter(query: String): List<Row> {
        val words = fold(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return rows
        return rows.filter { r -> words.all { it in r.match } }
    }

    fun items(query: String): List<Item> {
        val out = ArrayList<Item>()
        var last = -1
        for (r in filter(query)) {
            if (r.letter != last) out += Divider(r.letter)
            last = r.letter
            out += Hit(r)
        }
        return out
    }

    companion object {
        private const val O_SLASH = 'ø'.code
        private const val O_SLASH_UPPER = 'Ø'.code

        fun build(entries: List<AutoEqIndex.Entry>) =
            AutoEqList(Section.sorted(entries) { it.name }.map { (e, k) -> Row(e, k.letter, fold("${e.name} ${e.source}")) })

        fun fold(s: String): String {
            val b = StringBuilder(s.length)
            Normalizer.normalize(s, Normalizer.Form.NFD).codePoints().forEach { c ->
                val t = Character.getType(c)
                if (t == Character.NON_SPACING_MARK.toInt() || t == Character.FORMAT.toInt()) return@forEach
                b.appendCodePoint(if (c == O_SLASH || c == O_SLASH_UPPER) 'o'.code else c)
            }
            return b.toString().lowercase(Locale.ROOT)
        }
    }
}
