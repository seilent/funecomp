package net.seilent.funecomp.sources

import net.seilent.funekit.artist.Api
import net.seilent.funekit.artist.BrowseFeed.Search
import net.seilent.funekit.artist.Candidate
import net.seilent.funekit.artist.J
import net.seilent.funekit.artist.MbArtist
import net.seilent.funekit.artist.Wikimedia

class YouTube(private val api: Api, private val log: (String) -> Unit = {}) {
    class Channel(val id: String, val title: String, val thumb: String, val verified: Boolean = false)

    fun candidates(a: MbArtist): Sequence<Candidate> =
        channelRels(a).asSequence()
            .mapNotNull { url -> page(api, url)?.let { ogImage(it) } }
            .map { Candidate(it, YouTubeArt.SOURCE, id = it.substringBefore('=')) }

    fun search(name: String): Search {
        val url = "https://www.youtube.com/results?search_query=${Api.enc(name.trim())}&sp=$CHANNELS"
        val first = read(api.get(url)?.text)
        if (first != Read.Empty && first != Read.Gated) return first.search
        val second = read(api.get(url + EN)?.text)
        if (second == Read.Gated) log("art browse youtube search gated")
        val out = second.search
        log("art browse youtube search retry en ${if (out is Search.Matches) "channels=${out.list.size}" else if (out == Search.NoResults) "none" else "unavailable"}")
        return out
    }

    private sealed class Read(val search: Search) {
        class Found(list: List<Channel>) : Read(Search.Matches(list))

        object Empty : Read(Search.NoResults)

        object Gated : Read(Search.Unavailable(gated = true))

        object Failed : Read(Search.Unavailable())
    }

    private fun read(html: String?): Read {
        html ?: return Read.Failed
        val root = initialData(html) ?: return Read.Failed.also { log("art browse youtube search unparsed ${markers(html)}") }
        val list = channelsOf(root)
        if (list.isNotEmpty()) return Read.Found(list)
        if (promos(root).any { has(it, SIGN_IN) }) return Read.Gated
        val results = root["estimatedResults"].let { it.str ?: it.long?.toString() }
        if (results == "0" || has(root, PROMO)) return Read.Empty
        log("art browse youtube search drift results=${results ?: "-"}")
        return Read.Failed
    }

    companion object {
        private const val CHANNELS = "EgIQAg%253D%253D"
        private const val EN = "&hl=en&gl=US"
        private const val PROMO = "backgroundPromoRenderer"
        private const val SIGN_IN = "signInEndpoint"
        private val VERIFIED = setOf("BADGE_STYLE_TYPE_VERIFIED", "BADGE_STYLE_TYPE_VERIFIED_ARTIST")
        private val CHANNEL_ID = Regex("^UC[0-9A-Za-z_-]{22}$")

        fun channelId(tag: String): String? = tag.trim().takeIf { CHANNEL_ID.matches(it) }

        fun channelUrl(id: String) = "https://www.youtube.com/channel/$id"
        private const val DATA = "var ytInitialData = "
        private const val ELEMENT = "<script id=\"yt-initial-data\""
        private const val WINDOW = "window['ytInitialData']"
        private const val END = "</script>"
        private val CHANNEL = Regex("^https?://(www\\.|m\\.)?youtube\\.com/(channel/|user/|c/|@)")
        private val OG = Regex("<meta\\s+property=\"og:image\"\\s+content=\"([^\"]+)\"")
        private val TITLE = Regex("<meta\\s+property=\"og:title\"\\s+content=\"([^\"]*)\"")
        private val SIDE = Regex("=s\\d+")
        const val MUSIC_LABEL = "youtube music"
        private const val MUSIC_SIZE = "=s1600-rj"

        const val MAX_PAGE = 16 shl 20
        private val OG_MARK = "property=\"og:image\"".toByteArray()
        private val GT = '>'.code.toByte()

        fun channelRels(a: MbArtist): List<String> =
            a.rels("youtube").map { it.url }.filter { CHANNEL.containsMatchIn(it) }.sortedBy { if (it.contains("/channel/")) 0 else 1 }

        fun page(api: Api, url: String): String? {
            var at = -1
            var scanned = 0
            return api.download(url, MAX_PAGE) { b, n ->
                if (at < 0) at = find(b, n, maxOf(0, scanned - OG_MARK.size + 1))
                val end = at >= 0 && (maxOf(at, scanned) until n).any { b[it] == GT }
                scanned = n
                end
            }?.text
        }

        private fun find(b: ByteArray, n: Int, from: Int): Int {
            for (i in from..n - OG_MARK.size) if (OG_MARK.indices.all { b[i + it] == OG_MARK[it] }) return i
            return -1
        }

        fun ogImage(html: String): String? = OG.find(html)?.groupValues?.get(1)?.replace("&amp;", "&")

        fun ogTitle(html: String): String? = TITLE.find(html)?.groupValues?.get(1)?.let { Wikimedia.text(it) }

        fun markers(html: String): String =
            "chars=${html.length} var=${html.contains(DATA)} element=${html.contains(ELEMENT)} window=${html.contains(WINDOW)}"

        private fun embedded(html: String, start: Int): J? {
            if (start < 0) return null
            val end = html.indexOf(END, start).takeIf { it >= 0 } ?: return null
            return try {
                J.parse(html.substring(start, end).trimEnd().removeSuffix(";"))
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        private fun initialData(html: String): J? {
            val v = html.indexOf(DATA)
            val e = html.indexOf(ELEMENT).takeIf { it >= 0 }?.let { html.indexOf('>', it) }?.takeIf { it >= 0 }
            return embedded(html, if (v >= 0) v + DATA.length else -1) ?: embedded(html, e?.plus(1) ?: -1)
        }

        fun channels(html: String): List<Channel>? = initialData(html)?.let { channelsOf(it) }

        private fun promos(n: J): List<J> =
            n.entries.flatMap { (k, v) -> if (k == PROMO) listOf(v) else promos(v) } + n.list.flatMap { promos(it) }

        private fun has(n: J, key: String): Boolean = n.entries.any { (k, v) -> k == key || has(v, key) } || n.list.any { has(it, key) }

        private fun channelsOf(root: J): List<Channel> {
            val out = ArrayList<Channel>()
            fun walk(n: J) {
                n.entries.forEach { (k, v) ->
                    if (k == "channelRenderer") {
                        val id = v["channelId"].str
                        val title = v["title"]["simpleText"].str
                        val thumb = v["thumbnail"]["thumbnails"].list.lastOrNull()?.get("url")?.str
                        val verified = v["ownerBadges"].list.any { it["metadataBadgeRenderer"]["style"].str in VERIFIED }
                        if (id != null && title != null && thumb != null) out += Channel(id, title, if (thumb.startsWith("//")) "https:$thumb" else thumb, verified)
                    } else {
                        walk(v)
                    }
                }
                n.list.forEach { walk(it) }
            }
            walk(root)
            return out
        }

        fun sized(url: String, side: Int): String = url.replace(SIDE, "=s$side")

        fun resizable(url: String): Boolean = SIDE.containsMatchIn(url.substringAfterLast('/'))

        fun full(url: String): String {
            val i = url.lastIndexOf('=')
            return if (i > url.lastIndexOf('/')) url.substring(0, i) + MUSIC_SIZE else url + MUSIC_SIZE
        }
    }
}
