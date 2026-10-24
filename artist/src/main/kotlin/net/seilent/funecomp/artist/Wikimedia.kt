package net.seilent.funecomp.artist

import java.net.URLDecoder

data class Credit(val author: String, val license: String, val page: String, val licenseUrl: String = "")

class Candidate(val url: String, val source: String, val credit: Credit? = null, val id: String = ImageIds.of(source, url), val w: Int = 0, val h: Int = 0, val alt: String? = null)

object ImageIds {
    private val DEEZER = Regex("/images/artist/([0-9a-f]{32})/")

    fun of(source: String, url: String): String {
        val path = url.substringAfter("://").substringAfter('/', "").substringBefore('?')
        return when (source) {
            Wikimedia.SOURCE -> url
            Sources.DEEZER -> DEEZER.find(url)?.groupValues?.get(1) ?: path
            Sources.DISCOGS -> discogs(path)
            Sources.YOUTUBE -> url.substringBefore('=')
            else -> path
        }
    }

    fun of(e: ArtCache.Entry): String = e.id.ifEmpty {
        if (e.source == Wikimedia.SOURCE) e.credit?.page?.let { Wikimedia.commonsFile(it) }?.let { "File:$it" } ?: e.url else of(e.source, e.url)
    }

    private fun discogs(path: String): String {
        val parts = path.split('/').drop(1).filter { ':' !in it }
        val b64 = parts.joinToString("").substringBeforeLast('.')
        return try {
            String(java.util.Base64.getUrlDecoder().decode(b64.padEnd((b64.length + 3) / 4 * 4, '=')), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            path
        }
    }
}

class Wikimedia(
    private val api: Api,
    private val wikidata: String = "https://www.wikidata.org",
    private val commons: String = "https://commons.wikimedia.org",
    private val wikipedia: (String) -> String = { "https://$it.wikipedia.org" },
) {
    fun candidates(a: MbArtist, minW: Int, minH: Int, thumbW: Int = THUMB_W, targets: Targets = Targets.NONE): List<Candidate> {
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
        return info(files.toList(), thumbW).filter { usable(it, minW, minH) }.map { i ->
            val url = sized(i, targets)
            Candidate(url, SOURCE, i.credit, i.title, i.w, i.h, i.url.takeIf { it != url })
        }
    }

    fun file(name: String): Candidate? = info(listOf(name), THUMB_W).firstOrNull()?.let { Candidate(it.url, SOURCE, it.credit, it.title) }

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
        return uploadFile(r["originalimage"]["source"].str ?: return null)
    }

    private class Info(val url: String, val w: Int, val h: Int, val mime: String, val title: String, val credit: Credit, val original: String?, val bytes: Long)

    private fun sized(i: Info, t: Targets): String {
        val want = minOf(t.width(i.w, i.h), i.w)
        if (want <= THUMB_W) return i.url
        val step = STEPS.firstOrNull { it >= want } ?: STEPS.last()
        if (step < i.w) return i.url.replace(THUMB_PX, "/${step}px-")
        if (whole(i)) return i.original ?: i.url
        val below = STEPS.lastOrNull { it < i.w } ?: return i.url
        return i.url.replace(THUMB_PX, "/${below}px-")
    }

    private fun whole(i: Info): Boolean = when (i.mime) {
        "image/jpeg" -> true
        "image/png" -> i.bytes in 1..PNG_BYTES
        else -> false
    }

    private fun info(files: List<String>, thumbW: Int): List<Info> {
        val titles = files.map { "File:" + it.replace('_', ' ') }
        val out = HashMap<String, Info>()
        for (batch in titles.chunked(50)) {
            val r = api.json(
                "$commons/w/api.php?action=query&format=json&prop=imageinfo" +
                    "&iiprop=${Api.enc("url|size|mime|extmetadata")}&iiurlwidth=$thumbW" +
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
                    ii["url"].str,
                    ii["size"].long ?: 0,
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
        val STEPS = listOf(1920, 3840)
        const val PNG_BYTES = 4L shl 20
        private val THUMB_PX = Regex("/\\d+px-")
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

        fun uploadFile(url: String): String? {
            val path = url.substringBefore('?')
            if (!path.contains("/wikipedia/commons/")) return null
            val parts = path.split('/')
            return decode(if (path.contains("/wikipedia/commons/thumb/")) parts[parts.size - 2] else parts.last())
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
