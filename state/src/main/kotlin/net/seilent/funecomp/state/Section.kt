package net.seilent.funecomp.state

class SectionKey(val letter: Int, val sort: String)

object Section {
    private val LETTERS = listOf(
        "#", "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z",
        "あ", "か", "さ", "た", "な", "は", "ま", "や", "ら", "わ", "漢",
    )
    private val ROW_STARTS = intArrayOf(0x3041, 0x304B, 0x3055, 0x305F, 0x306A, 0x306F, 0x307E, 0x3083, 0x3089, 0x308E)
    private val SMALL_KATAKANA_EXT = intArrayOf(
        0x304F, 0x3057, 0x3059, 0x3068, 0x306C, 0x306F, 0x3072, 0x3075, 0x3078, 0x307B, 0x3080, 0x3089, 0x308A, 0x308B, 0x308C, 0x308D,
    )
    private val HALF_KANA = intArrayOf(
        0x30FB, 0x30F2, 0x30A1, 0x30A3, 0x30A5, 0x30A7, 0x30A9, 0x30E3, 0x30E5, 0x30E7, 0x30C3, 0x30FC, 0x30A2, 0x30A4, 0x30A6,
        0x30A8, 0x30AA, 0x30AB, 0x30AD, 0x30AF, 0x30B1, 0x30B3, 0x30B5, 0x30B7, 0x30B9, 0x30BB, 0x30BD, 0x30BF, 0x30C1, 0x30C4,
        0x30C6, 0x30C8, 0x30CA, 0x30CB, 0x30CC, 0x30CD, 0x30CE, 0x30CF, 0x30D2, 0x30D5, 0x30D8, 0x30DB, 0x30DE, 0x30DF, 0x30E0,
        0x30E1, 0x30E2, 0x30E4, 0x30E6, 0x30E8, 0x30E9, 0x30EA, 0x30EB, 0x30EC, 0x30ED, 0x30EF, 0x30F3, 0x3099, 0x309A,
    )
    private const val HASH_RANK = 1
    private const val LATIN_RANK = 2
    private const val KANA_RANK = 28
    private const val KANJI_RANK = 38

    fun letter(i: Int): String = LETTERS.getOrElse(i) { LETTERS[0] }

    private fun nfkc(c: Int): Int = when {
        c in 0xFF01..0xFF5E -> c - 0xFF01 + 0x21
        c in 0xFF65..0xFF9F -> HALF_KANA[c - 0xFF65]
        c == 0x3000 -> ' '.code
        else -> c
    }

    private fun kanaMark(c: Int) = c in 0x3099..0x309E || c in 0x30FB..0x30FE

    private fun hiragana(c: Int) = c in 0x3041..0x3096

    private fun toHiragana(c: Int): Int = when (c) {
        in 0x30A1..0x30F6 -> c - 0x60
        in 0x30F7..0x30FA -> 0x308F + (c - 0x30F7)
        in 0x31F0..0x31FF -> SMALL_KATAKANA_EXT[c - 0x31F0]
        else -> c
    }

    private fun baseHiragana(c: Int): Int = when {
        c <= 0x304A -> if ((c - 0x3041) % 2 == 0) c + 1 else c
        c <= 0x3062 -> if ((c - 0x304B) % 2 == 1) c - 1 else c
        c <= 0x3065 -> 0x3064
        c <= 0x3069 -> if ((c - 0x3066) % 2 == 1) c - 1 else c
        c <= 0x306E -> c
        c <= 0x307D -> 0x306F + (c - 0x306F) / 3 * 3
        c <= 0x3082 -> c
        c <= 0x3088 -> if ((c - 0x3083) % 2 == 0) c + 1 else c
        c == 0x308E -> 0x308F
        c == 0x3094 -> 0x3046
        c == 0x3095 -> 0x304B
        c == 0x3096 -> 0x3051
        else -> c
    }

    private fun kanaRow(base: Int): Int {
        for (i in 9 downTo 1) if (base >= ROW_STARTS[i]) return i
        return 0
    }

    private fun han(c: Int) = c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF || c in 0xF900..0xFAFF || c in 0x20000..0x3FFFF || c == 0x3005 || c == 0x3007

    private fun encode(v: IntArray, from: Int) = StringBuilder().apply { for (i in from until v.size) appendCodePoint(v[i]) }.toString()

    fun key(title: String, ignoreThe: Boolean = false): SectionKey {
        var key = ArtKey.fold(title)
        if (ignoreThe && key.startsWith("the ")) key = ArtKey.trim(key.substring(4))
        val first = if (key.isEmpty()) 0 else key[0].code
        if (first in 'a'.code..'z'.code) return SectionKey(1 + (first - 'a'.code), (LATIN_RANK + (first - 'a'.code)).toChar() + key)
        if (first < 0x80) return SectionKey(0, HASH_RANK.toChar() + key)
        val n = key.codePoints().map { ArtKey.lower(nfkc(it)) }.toArray()
        var i = 0
        while (i < n.size && kanaMark(n[i])) i++
        if (i < n.size) {
            val c = n[i]
            if (i == 0 && c in 'a'.code..'z'.code) return SectionKey(1 + (c - 'a'.code), (LATIN_RANK + (c - 'a'.code)).toChar() + encode(n, 0))
            val h = toHiragana(c)
            if (hiragana(h)) {
                val row = kanaRow(baseHiragana(h))
                val folded = StringBuilder()
                val hira = StringBuilder()
                for (k in i until n.size) {
                    val u = n[k]
                    val hu = toHiragana(u)
                    if (!kanaMark(u)) folded.appendCodePoint(if (hiragana(hu)) baseHiragana(hu) else hu)
                    hira.appendCodePoint(hu)
                }
                return SectionKey(27 + row, (KANA_RANK + row).toChar() + folded.toString() + '\u0001' + hira)
            }
            if (han(c)) return SectionKey(37, KANJI_RANK.toChar() + encode(n, i))
        }
        return SectionKey(0, HASH_RANK.toChar() + key)
    }

    fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val x = a.codePointAt(i)
            val y = b.codePointAt(j)
            if (x != y) return x.compareTo(y)
            i += Character.charCount(x)
            j += Character.charCount(y)
        }
        return (a.length - i).compareTo(b.length - j)
    }

    fun <T> sorted(items: List<T>, name: (T) -> String): List<Pair<T, SectionKey>> {
        val keys = items.map { key(name(it)) }
        return items.indices.sortedWith { a, b ->
            val c = compare(keys[a].sort, keys[b].sort)
            if (c != 0) c else compare(name(items[a]), name(items[b]))
        }.map { items[it] to keys[it] }
    }
}
