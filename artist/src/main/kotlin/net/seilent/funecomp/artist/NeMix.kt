package net.seilent.funecomp.artist

import net.seilent.funecomp.artist.gen.JaHan
import java.util.BitSet
import java.util.Locale

object NeMix {
    const val VERSION = 1
    const val TRANSLATION = "translation"
    const val FURIGANA = "furigana"
    const val HAN = "han"
    const val LANG = "lang"
    const val GATE_LINES = 3
    const val GATE_PCT = 30
    const val TRANSLATION_LINES = 4
    const val TRANSLATION_PCT = 10
    const val FURIGANA_GROUPS = 6
    const val FURIGANA_PCT = 25

    data class Counts(val cjk: Int, val kana: Int, val chinese: Int, val han: Int, val groups: Int, val groupLines: Int, val katakana: Int, val outside: Int) {
        override fun toString() = "cjk=$cjk kana=$kana chinese=$chinese han=$han groups=$groups/$groupLines katakana=$katakana outside=$outside"
    }

    sealed interface Verdict {
        data object Ok : Verdict

        data class Reject(val reason: String, val counts: Counts) : Verdict
    }

    private val ja: BitSet = BitSet().apply {
        for (r in JaHan.RANGES.split(',')) {
            val dash = r.indexOf('-')
            if (dash < 0) set(r.toInt(16)) else set(r.substring(0, dash).toInt(16), r.substring(dash + 1).toInt(16) + 1)
        }
    }

    private val colons = intArrayOf(':'.code, '：'.code, 0x2236, 0xFE30)
    private const val SEPARATORS = "/／、,，&＆・"
    private val labels = NetEase.LABELS + setOf(
        "翻唱", "时轴", "時軸", "校对", "校對", "编", "編", "by", "翻譯", "歌词", "歌詞", "后期", "混缩", "制作", "製作", "策划", "统筹", "文案",
        "美工", "曲绘", "调教", "调校", "pv", "视频", "封面", "演奏", "键盘", "原曲", "vocal", "vocals", "mix", "mixing", "master", "mastering",
        "illust", "movie", "music", "lyric", "arrange", "arrangement", "编写", "人声", "配唱", "填词", "填詞", "改词", "词作", "曲作", "出品人", "版权",
    )
    private val bareLabels = listOf("作词", "作詞", "作曲", "编曲", "編曲", "词", "曲", "词曲", "翻译", "翻唱", "混音", "和声", "演唱", "原唱", "填词")
    private val endings = setOf('词'.code, '詞'.code, '曲'.code)

    fun japanese(cp: Int): Boolean = ja.get(cp)

    fun kana(cp: Int): Boolean =
        cp in 0x3041..0x3096 || cp in 0x309D..0x309F || cp in 0x30A1..0x30FA || cp in 0x30FD..0x30FF || cp in 0x31F0..0x31FF ||
            cp in 0xFF66..0xFF6F || cp in 0xFF71..0xFF9F

    fun han(cp: Int): Boolean = cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF || cp in 0xF900..0xFAFF || cp in 0x20000..0x3FFFF

    private fun hiragana(cp: Int) = cp in 0x3041..0x3096 || cp == 0x309D || cp == 0x309E

    private fun katakana(cp: Int) = cp in 0x30A1..0x30FA || cp in 0x30FC..0x30FE || cp in 0x31F0..0x31FF

    private fun latin(cp: Int) = cp in 'A'.code..'Z'.code || cp in 'a'.code..'z'.code || cp in 0xFF21..0xFF3A || cp in 0xFF41..0xFF5A

    private fun space(cp: Int) = cp in 0x09..0x0D || cp == 0x20 || cp == 0x85 || cp == 0xA0 || cp == 0x1680 || cp in 0x2000..0x200A ||
        cp == 0x2028 || cp == 0x2029 || cp == 0x202F || cp == 0x205F || cp == 0x3000

    private fun digit(c: Char) = c in '0'..'9'

    private fun trim(s: String): String {
        var a = 0
        var b = s.length
        while (a < b && space(s.codePointAt(a))) a += Character.charCount(s.codePointAt(a))
        while (b > a && space(s.codePointBefore(b))) b -= Character.charCount(s.codePointBefore(b))
        return s.substring(a, b)
    }

    private fun stamp(s: String, from: Int): Int {
        if (from >= s.length || s[from] != '[') return -1
        var i = from + 1
        val m = i
        while (i < s.length && digit(s[i])) i++
        if (i == m || i >= s.length || s[i] != ':') return -1
        val sec = ++i
        while (i < s.length && digit(s[i])) i++
        if (i == sec || i >= s.length) return -1
        if (s[i] == '.' || s[i] == ':') {
            val f = ++i
            while (i < s.length && digit(s[i])) i++
            if (i == f || i >= s.length) return -1
        }
        return if (s[i] == ']') i + 1 else -1
    }

    private fun body(line: String): String {
        var i = 0
        while (true) {
            val next = stamp(line, i)
            if (next < 0) return line.substring(i)
            i = next
        }
    }

    internal fun lines(text: String): List<String> {
        val out = ArrayList<String>()
        for (raw in text.split('\n')) {
            var b = trim(body(raw.removeSuffix("\r")))
            if (b.isEmpty()) continue
            if (b.startsWith("【")) {
                val close = b.indexOf('】')
                if (close >= 0) {
                    b = trim(b.substring(close + 1))
                    if (b.isEmpty()) continue
                }
            }
            if (credit(b)) continue
            out += b
        }
        return out
    }

    private fun words(part: String): Boolean {
        val ok = BooleanArray(part.length + 1)
        ok[0] = true
        for (i in part.indices) {
            if (!ok[i]) continue
            for (w in labels) if (part.startsWith(w, i)) ok[i + w.length] = true
        }
        return ok[part.length]
    }

    private fun labelPart(part: String): Boolean {
        if (words(part.lowercase(Locale.ROOT))) return true
        val n = part.codePointCount(0, part.length)
        return n <= 4 && part.codePointBefore(part.length) in endings
    }

    internal fun credit(line: String): Boolean {
        val at = colons.map { line.indexOf(String(Character.toChars(it))) }.filter { it >= 0 }.minOrNull()
        if (at != null) {
            val label = line.substring(0, at)
            if (label.codePoints().noneMatch(::kana)) {
                val parts = ArrayList<String>()
                val cur = StringBuilder()
                var i = 0
                while (i < label.length) {
                    val cp = label.codePointAt(i)
                    if (space(cp) || cp < 0x10000 && cp.toChar() in SEPARATORS) {
                        parts += cur.toString()
                        cur.clear()
                    } else {
                        cur.appendCodePoint(cp)
                    }
                    i += Character.charCount(cp)
                }
                parts += cur.toString()
                val named = parts.filter { it.isNotEmpty() }
                if (named.isNotEmpty() && named.all(::labelPart)) return true
            }
        }
        return bareLabels.any { w -> line.length > w.length && line.startsWith(w) && space(line.codePointAt(w.length)) } && line.codePoints().noneMatch(::kana)
    }

    private fun groups(cps: IntArray, inside: (Int) -> Boolean): Int {
        var n = 0
        var i = 0
        while (i < cps.size - 1) {
            val c = cps[i]
            if ((han(c) || c == 0x3005) && (cps[i + 1] == '('.code || cps[i + 1] == '（'.code)) {
                var j = i + 2
                while (j < cps.size && inside(cps[j])) j++
                if (j > i + 2 && (j == cps.size || cps[j] == ')'.code || cps[j] == '）'.code)) {
                    n++
                    i = j + 1
                    continue
                }
            }
            i++
        }
        return n
    }

    fun counts(text: String): Counts {
        var cjk = 0
        var kana = 0
        var chinese = 0
        var han = 0
        var groups = 0
        var groupLines = 0
        var katakana = 0
        var outside = 0
        for (l in lines(text)) {
            val cps = l.codePoints().toArray()
            val hasKana = cps.any(::kana)
            val hans = cps.filter(::han)
            val out = hans.count { !japanese(it) }
            if (hasKana || hans.isNotEmpty()) cjk++
            if (hasKana) kana++
            if (hans.isNotEmpty()) han++
            if (!hasKana && hans.size >= 2 && out > 0 && cps.none(::latin)) chinese++
            val g = groups(cps, ::hiragana)
            groups += g
            if (g > 0) groupLines++
            katakana += groups(cps, ::katakana)
            outside += out
        }
        return Counts(cjk, kana, chinese, han, groups, groupLines, katakana, outside)
    }

    fun check(text: String, japanese: Boolean = false): Verdict {
        val c = counts(text)
        if (c.cjk < GATE_LINES || c.kana * 100 < GATE_PCT * c.cjk) return if (japanese) Verdict.Reject(LANG, c) else Verdict.Ok
        if (c.chinese >= TRANSLATION_LINES && c.chinese * 100 >= TRANSLATION_PCT * c.cjk) return Verdict.Reject(TRANSLATION, c)
        if (c.groups >= FURIGANA_GROUPS && c.groupLines * 100 >= FURIGANA_PCT * c.han) return Verdict.Reject(FURIGANA, c)
        if (c.outside > 0) return Verdict.Reject(HAN, c)
        return Verdict.Ok
    }
}
