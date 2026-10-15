package net.seilent.funecomp.artist

import java.net.URLDecoder

data class Credit(val author: String, val license: String, val page: String, val licenseUrl: String = "")

class Candidate(val url: String, val source: String, val credit: Credit? = null)

class Wikimedia(
    private val api: Api,
    private val wikidata: String = "https://www.wikidata.org",
    private val commons: String = "https://commons.wikimedia.org",
    private val wikipedia: (String) -> String = { "https://$it.wikipedia.org" },
) {
    fun candidates(a: MbArtist, minW: Int, minH: Int): List<Candidate> {
        val files = LinkedHashSet<String>()
        val qid = a.rels("wikidata").firstNotNullOfOrNull { Regex("/(Q\\d+)$").find(it.url)?.groupValues?.get(1) }
        if (qid != null) {
            files += p18(qid)
            val langs = listOf(lang(a.country), "en").distinct()
            val links = sitelinks(qid, langs)
            for (l in langs) links[l]?.let { t -> lead(l, t)?.let { files += it } }
        }
        a.rels("image").mapNotNullTo(files) { commonsFile(it.url) }
        if (files.isEmpty()) return emptyList()
        return info(files.toList()).filter { usable(it, minW, minH) }.map { Candidate(it.url, SOURCE, it.credit) }
    }

    private fun p18(qid: String): List<String> =
        api.json("$wikidata/w/api.php?action=wbgetclaims&entity=$qid&property=P18&format=json")
            ?.get("claims")?.get("P18")?.list?.mapNotNull { it["mainsnak"]["datavalue"]["value"].str }
            ?: emptyList()

    private fun sitelinks(qid: String, langs: List<String>): Map<String, String> {
        val filter = Api.enc(langs.joinToString("|") { "${it}wiki" })
        val r = api.json("$wikidata/w/api.php?action=wbgetentities&ids=$qid&props=sitelinks&sitefilter=$filter&format=json")
            ?: return emptyMap()
        val links = r["entities"][qid]["sitelinks"]
        return langs.mapNotNull { l -> links["${l}wiki"]["title"].str?.let { l to it } }.toMap()
    }

    private fun lead(lang: String, title: String): String? {
        val r = api.json("${wikipedia(lang)}/api/rest_v1/page/summary/${Api.enc(title.replace(' ', '_'))}") ?: return null
        val path = r["originalimage"]["source"].str?.substringBefore('?') ?: return null
        if (!path.contains("/wikipedia/commons/")) return null
        val parts = path.split('/')
        return decode(if (path.contains("/wikipedia/commons/thumb/")) parts[parts.size - 2] else parts.last())
    }

    private class Info(val url: String, val w: Int, val h: Int, val mime: String, val title: String, val credit: Credit)

    private fun info(files: List<String>): List<Info> {
        val titles = files.map { "File:" + it.replace('_', ' ') }
        val out = HashMap<String, Info>()
        for (batch in titles.chunked(50)) {
            val r = api.json(
                "$commons/w/api.php?action=query&format=json&prop=imageinfo" +
                    "&iiprop=${Api.enc("url|size|mime|extmetadata")}&iiurlwidth=$THUMB_W" +
                    "&iiextmetadatafilter=${Api.enc("LicenseShortName|LicenseUrl|Artist|Attribution|AttributionRequired")}" +
                    "&titles=${Api.enc(batch.joinToString("|"))}",
            ) ?: continue
            val q = r["query"]
            val names = q["normalized"].list.mapNotNull { n -> n["to"].str?.let { to -> n["from"].str?.let { it to to } } }.toMap()
            val pages = q["pages"].entries.map { it.second }
            for (t in batch) {
                val title = names[t] ?: t
                val p = pages.firstOrNull { it["title"].str == title } ?: continue
                val ii = p["imageinfo"][0]
                if (!ii.exists) continue
                val meta = ii["extmetadata"]
                val page = ii["descriptionurl"].str ?: ""
                val author = text(meta["Attribution"]["value"].str).ifEmpty { text(meta["Artist"]["value"].str) }
                out[t] = Info(
                    ii["thumburl"].str ?: ii["url"].str ?: continue,
                    ii["width"].long?.toInt() ?: 0,
                    ii["height"].long?.toInt() ?: 0,
                    ii["mime"].str ?: "",
                    title,
                    Credit(
                        author.ifEmpty { title.removePrefix("File:") },
                        text(meta["LicenseShortName"]["value"].str),
                        page,
                        meta["LicenseUrl"]["value"].str?.trim() ?: "",
                    ),
                )
            }
        }
        return titles.mapNotNull { out[it] }
    }

    private fun usable(i: Info, minW: Int, minH: Int): Boolean {
        if (i.mime == "image/svg+xml" || !i.mime.startsWith("image/")) return false
        val lower = i.title.lowercase()
        if (SKIP.any { lower.contains(it) }) return false
        if (i.w < minW || i.h < minH) return false
        return i.w <= i.h * MAX_ASPECT && i.h <= i.w * MAX_ASPECT
    }

    companion object {
        const val SOURCE = "commons"
        const val THUMB_W = 1280
        const val MAX_ASPECT = 2
        private val SKIP = listOf("logo", "signature", "wordmark", "autograph")
        private val LANGS = mapOf(
            "JP" to "ja", "KR" to "ko", "CN" to "zh", "TW" to "zh", "HK" to "zh", "FR" to "fr", "DE" to "de",
            "AT" to "de", "ES" to "es", "MX" to "es", "AR" to "es", "IT" to "it", "RU" to "ru", "BR" to "pt",
            "PT" to "pt", "SE" to "sv", "NO" to "no", "FI" to "fi", "DK" to "da", "NL" to "nl", "PL" to "pl",
            "IS" to "is", "UA" to "uk", "TR" to "tr", "ID" to "id", "TH" to "th", "VN" to "vi",
        )

        fun lang(country: String?) = LANGS[country] ?: "en"

        fun commonsFile(url: String): String? {
            val i = url.indexOf("commons.wikimedia.org/wiki/File:")
            if (i < 0) return null
            return decode(url.substring(i + "commons.wikimedia.org/wiki/File:".length))
        }

        private fun decode(s: String): String = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8").replace('_', ' ')

        private val ENTITY = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-z]+);")
        private val NAMED = mapOf("quot" to "\"", "apos" to "'", "lt" to "<", "gt" to ">", "amp" to "&", "nbsp" to " ")

        fun text(html: String?): String = (html ?: "")
            .replace(Regex("<[^>]*>"), "")
            .replace(ENTITY) { m ->
                val e = m.groupValues[1]
                val code = when {
                    e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)
                    e.startsWith("#") -> e.substring(1).toIntOrNull()
                    else -> null
                }
                when {
                    code != null && Character.isValidCodePoint(code) -> String(Character.toChars(code))
                    code == null -> NAMED[e] ?: m.value
                    else -> m.value
                }
            }
            .replace('\u00A0', ' ')
            .replace(Regex("\\s+"), " ").trim()
    }
}
