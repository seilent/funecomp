package net.seilent.funecomp.artist

import java.text.Normalizer
import java.util.Locale

object Names {
    private val space = Regex("\\s+")
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun fold(s: String?): String = Normalizer.normalize(s ?: "", Normalizer.Form.NFKC).trim().replace(space, " ").lowercase(Locale.ROOT)

    fun mbid(s: String?): String? = s?.trim()?.lowercase(Locale.ROOT)?.takeIf { uuid.matches(it) }
}

class Rel(val type: String, val url: String, val ended: Boolean)

class MbArtist(val id: String, val name: String, val country: String?, val rels: List<Rel>) {
    fun rels(type: String) = rels.filter { it.type == type && !it.ended }
}

class MusicBrainz(private val api: Api, private val base: String = "https://musicbrainz.org/ws/2") {
    fun search(name: String): String? {
        val want = Names.fold(name)
        if (want.isEmpty()) return null
        val r = api.json("$base/artist/?query=${Api.enc(escape(name.trim()))}&fmt=json&limit=10") ?: return null
        return r["artists"].list.firstOrNull { a ->
            Names.fold(a["name"].str) == want || Names.fold(a["sort-name"].str) == want ||
                a["aliases"].list.any { Names.fold(it["name"].str) == want }
        }?.get("id")?.str?.let { Names.mbid(it) }
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
