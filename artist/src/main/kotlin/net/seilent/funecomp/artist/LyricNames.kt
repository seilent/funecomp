package net.seilent.funecomp.artist

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

    fun fold(s: String): String = s.lowercase(Locale.ROOT).replace("'", "").replace(mark, " ").replace(space, " ").trim()

    internal fun tails(s: String): String {
        var t = s
        for (r in tails) t = r.replace(t, " ").trim().trim(::edge).ifEmpty { t }
        return t.trim(::edge)
    }

    private fun edge(c: Char) = c in " -/／"
}
