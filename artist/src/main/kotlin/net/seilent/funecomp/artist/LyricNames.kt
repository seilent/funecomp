package net.seilent.funecomp.artist

import java.text.Normalizer
import java.util.Locale

object LyricNames {
    internal const val SPACE = "\\t-\\r \\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000"
    internal const val S = "[$SPACE]"
    private const val END = "(?![\\p{L}\\p{M}\\p{N}\\p{Pc}])"
    private val tails = listOf(
        Regex("(?i)$S*[-(（﴾/]*$S*covered$S+by$END.*$"),
        Regex("(?i)$S*[(（]$S*cover$S*[)）]"),
        Regex("(?i)$S*[(（]?$S*(?:feat\\.?|ft\\.)$S*[^)）]*[)）]?$S*$"),
    )
    private val separator = Regex("$S+/$S+|／|$S+-$S+")
    private val bareCover = Regex("(?i)(cover|カバー|歌ってみた)($S.*)?")
    private val mark = Regex("[\\p{P}\\p{S}]")
    private val space = Regex("$S+")
    private const val AFTER = "(?=$|$S|[.。:：])"
    private const val HASHCHAR = "[^$SPACE#＃「」『』()（）]"
    private val hashRun = Regex("(?:$S+[#＃]$HASHCHAR+)+$S*$")
    private val hash = Regex("[#＃]($HASHCHAR+)")
    private val bar = Regex("[｜|│]")
    private val fullCovered = Regex("(?i)$S+(?:full|フル)(?=$S*covered$S+by)")
    private val version = Regex("(?i)$S*[(（\\[【]?$S*(?<![${Names.LATIN}])(?:full|フル|short|ショート|tv)$S*(?:ver(?:sion)?\\.?|size|サイズ|バージョン)$S*[)）\\]】]?")
    private val mvTail = Regex("(?i)$S+(?:mv|m/v|music$S+video)$S*$")
    private val coverBracket = Regex("(?i)[(（\\[【〖][^)）\\]】〗]*(?:カバー|cover|歌ってみた)[^)）\\]】〗]*[)）\\]】〗](?:$S+by$S.*$)?")
    private val brackets = Regex("[(（\\[【《〈<〖][^)）\\]】》〉>〗]*[)）\\]】》〉>〗]")
    private val kanaWord = Regex("(?:(?<![\\u30A0-\\u30FF])カバー|を歌ってみた|歌ってみた)$AFTER")
    private val coverWord = Regex("(?i)(?<![${Names.LATIN}])cover$AFTER")
    private val marks = setOf("cover", "covered", "カバー", "歌ってみた", "うたってみた", "弾き語り", "short", "shorts", "mv", "m/v", "music video", "full", "フル", "vtuber", "歌枠", "新人vtuber")
    private val quotes = listOf("‘" to "’", "“" to "”", "「" to "」", "『" to "』", "\"" to "\"", "'" to "'")

    data class Clean(val title: String, val artist: String?, val tags: List<String>)

    fun split(title: String, artist: String): Pair<String, String>? {
        val parts = separator.split(tails(title))
        if (parts.size < 2) return null
        val left = parts[0].trim(::edge)
        val right = tails(parts[1].trim(::edge))
        if (left.isEmpty() || right.isEmpty()) return null
        if (Names.fold(left).replace(" ", "") == Names.fold(artist).replace(" ", "")) return null
        if (bareCover.matches(Names.fold(right))) return null
        return left to right
    }

    fun clean(title: String, artist: String): Clean {
        var t = math(title)
        var found = emptyList<String>()
        hashRun.find(t)?.let { m ->
            found = hash.findAll(m.value).map { it.groupValues[1] }.toList()
            t = t.substring(0, m.range.first)
        }
        val parts = bar.split(t).map { it.trim() }
        if (parts.size > 1) t = parts.firstOrNull { !skipPart(it, artist) } ?: parts[0]
        t = tails(fullCovered.replace(t, ""))
        t = version.replace(t, " ").trim()
        t = mvTail.replace(t, "").trim().ifEmpty { t }
        t = coverBracket.replace(t, " ").trim().ifEmpty { t }
        val pair = split(t, artist)
        val left = unquote(space.replace(coverTail(pair?.first ?: t, true), " ").trim(::edge))
        val right = pair?.let { space.replace(coverTail(it.second, false), " ").trim(::edge) }?.takeIf { it.isNotEmpty() }
        val skip = setOfNotNull(artistKey(artist), artistKey(left), right?.let(::artistKey))
        val tags = ArrayList<String>()
        for (h in found) {
            if (h.codePoints().allMatch { Character.isDigit(it) } || marked(h) || artistKey(h) in skip || h in tags) continue
            tags += h
        }
        return Clean(left, right, tags.take(3))
    }

    fun artistKey(s: String): String = fold(Names.fold(s)).replace(" ", "")

    fun fold(s: String): String = s.lowercase(Locale.ROOT).replace("'", "").replace(mark, " ").replace(space, " ").trim()

    internal fun tails(s: String): String {
        var t = s
        for (r in tails) t = r.replace(t, " ").trim().trim(::edge).ifEmpty { t }
        return t.trim(::edge)
    }

    private fun edge(c: Char) = c in " -/／"

    private fun math(s: String) = buildString {
        var i = 0
        while (i < s.length) {
            val c = s.codePointAt(i)
            if (c in 0x1D400..0x1D7FF) append(Normalizer.normalize(String(Character.toChars(c)), Normalizer.Form.NFKC)) else appendCodePoint(c)
            i += Character.charCount(c)
        }
    }

    private fun marked(s: String) = space.replace(Normalizer.normalize(s, Normalizer.Form.NFKC), " ").trim(' ').lowercase(Locale.ROOT) in marks

    private fun titleCover(s: String, m: MatchResult): Boolean {
        val before = s.substring(0, m.range.first).trimEnd(' ', '\t', '\u3000')
        if (before.isEmpty() || !Names.latin(before.last())) return true
        val rest = s.substring(m.range.last + 1)
        if (rest.isNotEmpty() && rest[0] in ".。:：") return true
        val r = rest.trimStart(' ', '\t', '\u3000')
        if (r.isEmpty()) return false
        if (r.startsWith("by", ignoreCase = true) && (r.length == 2 || !Names.latin(r[2]))) return true
        return !Names.latin(r[0])
    }

    private fun coverTail(s: String, title: Boolean): String {
        val kana = kanaWord.find(s)?.range?.first
        val cover = coverWord.findAll(s).firstOrNull { !title || titleCover(s, it) }?.range?.first
        val cut = listOfNotNull(kana, cover).minOrNull() ?: return s
        return s.substring(0, cut).trim(' ', '\t', '\u3000', '-', '/', '／').ifEmpty { s }
    }

    private fun skipPart(p: String, artist: String): Boolean {
        if (p.isEmpty() || marked(p)) return true
        val cut = coverTail(p, true)
        if (cut != p && marked(cut)) return true
        val k = artistKey(brackets.replace(p, " "))
        return k.isNotEmpty() && (k == artistKey(artist) || Names.bare(artist)?.let { k == artistKey(it) } == true)
    }

    private fun unquote(s: String): String {
        for ((a, b) in quotes) {
            if (s.length <= 2 || !s.startsWith(a) || !s.endsWith(b)) continue
            val inner = s.substring(1, s.length - 1)
            if (a !in inner && b !in inner) return inner.trim()
        }
        return s
    }
}
