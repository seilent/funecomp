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
    private val channelTail = Regex("^(.*\\S)([\\s\u3000]+(official|Official|OFFICIAL|channel|Channel|公式|チャンネル)|/公式)$")

    private fun base(name: String): String {
        var base = name.trim()
        while (true) {
            val m = tagTail.find(base) ?: break
            val rest = base.substring(0, m.range.first).trim()
            if (rest.isEmpty()) break
            base = rest
        }
        return base
    }

    fun variants(name: String): List<String> {
        val plain = name.trim()
        val base = base(plain)
        val bare = bare(base)
        return (listOf(base) + listOfNotNull(bare) + split(bare ?: base)).map { it.trim() }.filter { it.isNotEmpty() && it != plain }.distinct()
    }

    fun bareOf(name: String): String? = bare(base(name))

    fun bare(s: String): String? = channelTail.find(s.trim())?.groupValues?.get(1)?.trim()

    internal const val LATIN = "A-Za-z0-9\\u00C0-\\u024F"
    private val latinChar = Regex("[$LATIN]")

    internal fun latin(c: Char) = latinChar.matches(c.toString())

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

class Linked(val id: String, val name: String, val sortName: String)

class Alias(val name: String, val locale: String?, val primary: Boolean, val type: String?)

class MbAliases(val name: String, val aliases: List<Alias>)

object Aliases {
    private val NATIVE = setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL)
    private val NAMES = setOf("Artist name", "Legal name")

    fun native(name: String, aliases: List<Alias>, query: String): String? {
        val q = Names.fold(query)
        fun ok(s: String) = Names.fold(s) != q && s.any { Character.isLetter(it) && Character.UnicodeScript.of(it.code) in NATIVE }
        val usable = aliases.filter { it.type != "Search hint" && ok(it.name) }
        fun lang(a: Alias) = a.locale?.substringBefore('_')
        return (usable.firstOrNull { it.primary && lang(it) == "ja" }
            ?: usable.firstOrNull { it.primary && lang(it) != "en" }
            ?: usable.firstOrNull { it.type in NAMES })?.name
            ?: name.takeIf { ok(it) }
    }
}

class MbArtist(val id: String, val name: String, val country: String?, val rels: List<Rel>, val disambiguation: String = "") {
    fun rels(type: String) = rels.filter { it.type == type && !it.ended }
}

class MusicBrainz(private val api: Api, private val base: String = "https://musicbrainz.org/ws/2") {
    fun search(name: String, skip: Set<String> = emptySet()): String? {
        val want = Names.fold(name)
        if (want.isEmpty()) return null
        val r = api.json(query(name)) ?: return null
        val all = r["artists"].list.filter { Names.mbid(it["id"].str) !in skip }
        val top = all.firstOrNull()?.takeIf { it["score"].long == 100L && Names.fold(it["sort-name"].str?.replace(", ", " ")) == want }
        val kana = Names.kana(name)
        val folded = all.filter { matches(it, Names::fold, want) }
        val same = folded.firstOrNull { matches(it, { s -> s?.trim() ?: "" }, name.trim()) }
        return (same ?: folded.firstOrNull() ?: top ?: all.filter { matches(it, Names::kana, kana) }.singleOrNull())
            ?.get("id")?.str?.let { Names.mbid(it) }
    }

    fun unique(name: String, skip: Set<String> = emptySet()): String? {
        val want = Names.fold(name)
        if (want.isEmpty()) return null
        val r = api.json(query(name)) ?: return null
        val all = r["artists"].list.filter { Names.mbid(it["id"].str) !in skip }
        val folded = all.filter { matches(it, Names::fold, want) }
        val one = if (folded.isNotEmpty()) folded.singleOrNull() else all.filter { matches(it, Names::kana, Names.kana(name)) }.singleOrNull()
        return one?.get("id")?.str?.let { Names.mbid(it) }
    }

    fun aliases(id: String): MbAliases? {
        val r = api.json("$base/artist/$id?inc=aliases&fmt=json") ?: return null
        return MbAliases(r["name"].str ?: "", r["aliases"].list.mapNotNull { x ->
            val n = x["name"].str ?: return@mapNotNull null
            Alias(n, x["locale"].str, x["primary"].bool == true, x["type"].str)
        })
    }

    fun byUrl(url: String): List<Linked> {
        val r = api.json("$base/url?resource=${Api.enc(url)}&inc=artist-rels&fmt=json") ?: return emptyList()
        return r["relations"].list.filter { it["ended"].bool != true }.mapNotNull { x ->
            val a = x["artist"]
            val id = Names.mbid(a["id"].str) ?: return@mapNotNull null
            Linked(id, a["name"].str ?: "", a["sort-name"].str ?: "")
        }.distinctBy { it.id }
    }

    fun searchAll(name: String): List<String> {
        val want = Names.fold(name)
        if (want.isEmpty()) return emptyList()
        val r = api.json(query(name)) ?: return emptyList()
        return r["artists"].list.filter { matches(it, Names::fold, want) }.mapNotNull { Names.mbid(it["id"].str) }
    }

    private fun query(name: String) = "$base/artist/?query=${Api.enc(escape(name.trim()))}&fmt=json&limit=10"

    private fun matches(a: J, f: (String?) -> String, w: String) =
        f(a["name"].str) == w || f(a["sort-name"].str) == w || a["aliases"].list.any { f(it["name"].str) == w }

    fun artist(id: String): MbArtist? {
        val r = api.json("$base/artist/$id?inc=url-rels&fmt=json") ?: return null
        val rels = r["relations"].list.mapNotNull { x ->
            val url = x["url"]["resource"].str ?: return@mapNotNull null
            Rel(x["type"].str ?: "", url, x["ended"].bool == true)
        }
        return MbArtist(id, r["name"].str ?: "", r["country"].str, rels, r["disambiguation"].str ?: "")
    }

    private fun escape(s: String) = buildString {
        for (c in s) {
            if (c in "+-&|!(){}[]^\"~*?:\\/") append('\\')
            append(c)
        }
    }
}
