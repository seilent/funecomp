package net.seilent.funecomp.artist

import java.text.Normalizer
import java.util.Locale

object Names {
    private val space = Regex("\\s+")
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun fold(s: String?): String = Normalizer.normalize(s ?: "", Normalizer.Form.NFKC).trim().replace(space, " ").lowercase(Locale.ROOT)

    fun kana(s: String?): String {
        val f = fold(s).map { c -> if (c in '\u3041'..'\u3096' || c == '\u309D' || c == '\u309E') c + 0x60 else c }.joinToString("")
        return Normalizer.normalize(f, Normalizer.Form.NFC)
    }

    fun mbid(s: String?): String? = s?.trim()?.lowercase(Locale.ROOT)?.takeIf { uuid.matches(it) }

    private val tagTail = Regex("\\s*(【[^【】]*】|\\[[^\\[\\]]*])\\s*$")

    fun variants(name: String): List<String> {
        val plain = name.trim()
        var base = plain
        while (true) {
            val m = tagTail.find(base) ?: break
            val rest = base.substring(0, m.range.first).trim()
            if (rest.isEmpty()) break
            base = rest
        }
        return (listOf(base) + split(base)).map { it.trim() }.filter { it.isNotEmpty() && it != plain }.distinct()
    }

    private fun latin(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in '\u00C0'..'\u024F'

    private fun neighbor(s: String, from: Int, step: Int): Char? {
        var i = from
        while (i in s.indices && s[i].isWhitespace()) i += step
        return s.getOrNull(i)
    }

    private fun joint(s: String, i: Int): Boolean = when (s[i]) {
        '/' -> {
            val a = s.getOrNull(i - 1)
            val b = s.getOrNull(i + 1)
            a != null && b != null && !a.isWhitespace() && !b.isWhitespace() && !(latin(a) && latin(b))
        }
        '×', 'x', 'X' -> {
            val a = neighbor(s, i - 1, -1)
            val b = neighbor(s, i + 1, 1)
            a != null && b != null && !latin(a) && !latin(b) && a !in "×xX/" && b !in "×xX/"
        }
        else -> false
    }

    private fun split(s: String): List<String> {
        val parts = ArrayList<String>()
        var start = 0
        for (i in s.indices) {
            if (joint(s, i)) {
                parts += s.substring(start, i)
                start = i + 1
            }
        }
        if (parts.isEmpty()) return emptyList()
        parts += s.substring(start)
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }
}

class Rel(val type: String, val url: String, val ended: Boolean)

class MbArtist(val id: String, val name: String, val country: String?, val rels: List<Rel>) {
    fun rels(type: String) = rels.filter { it.type == type && !it.ended }
}

class MusicBrainz(private val api: Api, private val base: String = "https://musicbrainz.org/ws/2") {
    fun search(name: String, skip: Set<String> = emptySet()): String? {
        val want = Names.fold(name)
        if (want.isEmpty()) return null
        val r = api.json("$base/artist/?query=${Api.enc(escape(name.trim()))}&fmt=json&limit=10") ?: return null
        val all = r["artists"].list.filter { Names.mbid(it["id"].str) !in skip }
        val top = all.firstOrNull()?.takeIf { it["score"].long == 100L && Names.fold(it["sort-name"].str?.replace(", ", " ")) == want }
        fun matches(a: J, f: (String?) -> String, w: String) =
            f(a["name"].str) == w || f(a["sort-name"].str) == w || a["aliases"].list.any { f(it["name"].str) == w }
        val kana = Names.kana(name)
        return (all.firstOrNull { matches(it, Names::fold, want) } ?: top ?: all.filter { matches(it, Names::kana, kana) }.singleOrNull())
            ?.get("id")?.str?.let { Names.mbid(it) }
    }

    fun artist(id: String): MbArtist? {
        val r = api.json("$base/artist/$id?inc=url-rels&fmt=json") ?: return null
        val rels = r["relations"].list.mapNotNull { x ->
            val url = x["url"]["resource"].str ?: return@mapNotNull null
            Rel(x["type"].str ?: "", url, x["ended"].bool == true)
        }
        return MbArtist(id, r["name"].str ?: "", r["country"].str, rels)
    }

    private fun escape(s: String) = buildString {
        for (c in s) {
            if (c in "+-&|!(){}[]^\"~*?:\\/") append('\\')
            append(c)
        }
    }
}
