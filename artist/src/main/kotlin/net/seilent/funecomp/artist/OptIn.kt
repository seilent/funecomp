package net.seilent.funecomp.artist

data class Sources(
    val theAudioDb: Boolean = true,
    val discogs: Boolean = true,
    val fanartTv: Boolean = true,
    val fanartTvKey: String = "",
    val deezer: Boolean = true,
    val youtube: Boolean = true,
    val order: List<String> = ORDER,
) {
    val enabled: Set<String>
        get() = buildSet {
            add(Wikimedia.SOURCE)
            if (theAudioDb) add(THE_AUDIO_DB)
            if (fanartTv && fanartTvKey.isNotBlank()) add(FANART_TV)
            if (discogs) add(DISCOGS)
            if (youtube) add(YOUTUBE)
            if (deezer) add(DEEZER)
        }

    val ordered: List<String>
        get() = enabled.let { on -> order.filter { it in on } }

    fun moved(source: String, by: Int): Sources {
        val i = order.indexOf(source)
        val j = i + by
        if (i < 0 || j !in order.indices) return this
        return copy(order = order.toMutableList().apply { add(j, removeAt(i)) })
    }

    class Edit(val remove: Set<String>, val write: Boolean)

    companion object {
        val ORDER = listOf("theaudiodb", "fanarttv", "youtube", "discogs", Wikimedia.SOURCE, "deezer")
        private val OLD_ORDER = listOf(Wikimedia.SOURCE, "theaudiodb", "fanarttv", "discogs", "youtube", "deezer")
        val NONE = Sources(theAudioDb = false, discogs = false, fanartTv = false, deezer = false, youtube = false, order = OLD_ORDER)
        const val DEFAULTS = "defaults"
        const val DEFAULTS_REV = 2

        fun order(s: String?): List<String> {
            val known = (s ?: "").split(',').map { it.trim() }.filter { it in ORDER }.distinct()
            return known + ORDER.filter { it !in known }
        }

        fun upgrade(stored: Map<String, Any?>): Edit {
            if (((stored[DEFAULTS] as? Int) ?: 0) >= DEFAULTS_REV) return Edit(emptySet(), false)
            val keyed = (stored["fanarttvKey"] as? String)?.isNotBlank() == true
            val remove = listOf(THE_AUDIO_DB, DISCOGS, FANART_TV, DEEZER, YOUTUBE).filter { stored[it] == false && !(it == FANART_TV && keyed) }.toMutableSet()
            if (stored.containsKey("order") && order(stored["order"] as? String) == OLD_ORDER) remove += "order"
            return Edit(remove, true)
        }

        const val THE_AUDIO_DB = "theaudiodb"
        const val FANART_TV = "fanarttv"
        const val DISCOGS = "discogs"
        const val YOUTUBE = "youtube"
        const val DEEZER = "deezer"
        const val LINK = "link"
        const val MUSICBRAINZ = "musicbrainz"
        const val GALLERY = "gallery"

        private val LABELS = mapOf(
            Wikimedia.SOURCE to "commons",
            THE_AUDIO_DB to "theaudiodb",
            DISCOGS to "discogs",
            FANART_TV to "fanart.tv",
            YOUTUBE to "youtube",
            DEEZER to "deezer",
            LINK to "link",
            MUSICBRAINZ to "musicbrainz",
            GALLERY to "gallery",
        )

        fun label(id: String) = LABELS[id] ?: id
    }
}

class TheAudioDb(private val api: Api, private val base: String = "https://www.theaudiodb.com/api/v1/json/123") {
    companion object {
        fun small(url: String) = if (url.contains("://r2.theaudiodb.com/")) "$url/small" else url
    }

    fun candidates(mbid: String): List<Candidate> {
        val a = api.json("$base/artist-mb.php?i=$mbid")?.get("artists")?.get(0) ?: return emptyList()
        return listOf("strArtistThumb", "strArtistFanart", "strArtistFanart2", "strArtistFanart3")
            .mapNotNull { a[it].str?.takeIf { u -> u.startsWith("http") } }
            .map { Candidate(it, Sources.THE_AUDIO_DB) }
    }
}

class FanartTv(private val api: Api, private val key: () -> String, private val base: String = "https://webservice.fanart.tv/v3/music") {
    fun candidates(mbid: String): List<Candidate> {
        val r = api.json("$base/$mbid?api_key=${Api.enc(key())}") ?: return emptyList()
        return listOf("artistthumb", "artistbackground").flatMap { kind ->
            r[kind].list.sortedByDescending { it["likes"].str?.toIntOrNull() ?: it["likes"].long?.toInt() ?: 0 }
                .mapNotNull { it["url"].str }
        }.map { Candidate(it, Sources.FANART_TV) }
    }
}

class Discogs(private val api: Api, private val base: String = "https://api.discogs.com") {
    fun candidates(a: MbArtist): List<Candidate> = images(a).mapNotNull { i ->
        i["uri"].str?.takeIf { it.startsWith("http") }?.let { Candidate(it, Sources.DISCOGS, w = i["width"].long?.toInt() ?: 0, h = i["height"].long?.toInt() ?: 0, group = if (i["type"].str == "primary") 0 else 1) }
    }

    fun previews(a: MbArtist): List<Pair<String, String>> = images(a).mapNotNull { i ->
        val full = i["uri"].str?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
        (i["uri150"].str?.takeIf { it.startsWith("http") } ?: full) to full
    }

    private fun images(a: MbArtist): List<J> {
        val id = a.rels("discogs").firstNotNullOfOrNull { Regex("/artist/(\\d+)").find(it.url)?.groupValues?.get(1) }
            ?: return emptyList()
        val r = api.json("$base/artists/$id") ?: return emptyList()
        return r["images"].list.sortedBy { if (it["type"].str == "primary") 0 else 1 }
    }
}

class YouTube(private val api: Api, private val log: (String) -> Unit = {}) {
    class Channel(val id: String, val title: String, val thumb: String, val verified: Boolean = false)

    sealed interface Search {
        class Channels(val list: List<Channel>) : Search

        object NoResults : Search

        data class Unavailable(val gated: Boolean = false) : Search
    }

    fun candidates(a: MbArtist): Sequence<Candidate> =
        channelRels(a).asSequence()
            .mapNotNull { url -> page(api, url)?.let { ogImage(it) } }
            .map { Candidate(it, Sources.YOUTUBE) }

    fun search(name: String): Search {
        val url = "https://www.youtube.com/results?search_query=${Api.enc(name.trim())}&sp=$CHANNELS"
        val first = read(api.get(url)?.text)
        if (first != Read.Empty && first != Read.Gated) return first.search
        val second = read(api.get(url + EN)?.text)
        if (second == Read.Gated) log("art browse youtube search gated")
        val out = second.search
        log("art browse youtube search retry en ${if (out is Search.Channels) "channels=${out.list.size}" else if (out == Search.NoResults) "none" else "unavailable"}")
        return out
    }

    private sealed class Read(val search: Search) {
        class Found(list: List<Channel>) : Read(Search.Channels(list))

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

class Deezer(private val api: Api, val placeholders: Set<String> = PLACEHOLDERS, private val base: String = "https://api.deezer.com") {
    fun placeholder(src: ByteArray): Boolean = ArtCache.sha256(src) in placeholders

    fun search(name: String): List<J> = api.json("$base/search/artist?q=${Api.enc(name.trim())}")?.get("data")?.list ?: emptyList()

    fun candidates(name: String): List<Candidate> {
        val want = Names.fold(name)
        return search(name).filter { Names.fold(it["name"].str) == want }
            .sortedWith(compareBy<J> { it["name"].str?.trim() != name.trim() }.thenByDescending { it["nb_fan"].long ?: 0 })
            .mapNotNull { picture(it, "picture_xl") }
            .map { Candidate(it, Sources.DEEZER) }
    }

    companion object {
        val PLACEHOLDERS = setOf(
            "0d659e802c2d9c13e8cf4e0fab3f85b176173a649b60d9ddc03af2397fb43275",
            "bd8dae144dc585a7eb090e2071fe386bbe0df6ebcc47ee0d96d5ec5c23274530",
            "5caf39f825879e7982065e4402d7dde41be7ffcee984a0e1d273a7efb2b78f00",
        )
        private const val EMPTY = "d41d8cd98f00b204e9800998ecf8427e"
        const val XL = 1000
        const val REAL = 1500
        private val SIDE = Regex("/\\d+x\\d+-")

        fun resizable(url: String): Boolean = SIDE.containsMatchIn(url)

        fun sized(url: String, side: Int): String = url.replace(SIDE, "/${side}x$side-")

        fun picture(a: J, size: String): String? = a[size].str?.takeIf { u -> u.startsWith("http") && !u.contains("/artist//") && !u.contains("/artist/$EMPTY/") }
    }
}
