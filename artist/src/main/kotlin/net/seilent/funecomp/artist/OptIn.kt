package net.seilent.funecomp.artist

data class Sources(
    val theAudioDb: Boolean = false,
    val discogs: Boolean = false,
    val fanartTv: Boolean = false,
    val fanartTvKey: String = "",
    val deezer: Boolean = false,
    val youtube: Boolean = false,
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

    companion object {
        val ORDER = listOf(Wikimedia.SOURCE, "theaudiodb", "fanarttv", "discogs", "youtube", "deezer")

        fun order(s: String?): List<String> {
            val known = (s ?: "").split(',').map { it.trim() }.filter { it in ORDER }.distinct()
            return known + ORDER.filter { it !in known }
        }

        const val THE_AUDIO_DB = "theaudiodb"
        const val FANART_TV = "fanarttv"
        const val DISCOGS = "discogs"
        const val YOUTUBE = "youtube"
        const val DEEZER = "deezer"
    }
}

class TheAudioDb(private val api: Api, private val base: String = "https://www.theaudiodb.com/api/v1/json/123") {
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
    fun candidates(a: MbArtist): List<Candidate> {
        val id = a.rels("discogs").firstNotNullOfOrNull { Regex("/artist/(\\d+)").find(it.url)?.groupValues?.get(1) }
            ?: return emptyList()
        val r = api.json("$base/artists/$id") ?: return emptyList()
        return r["images"].list.sortedBy { if (it["type"].str == "primary") 0 else 1 }
            .mapNotNull { it["uri"].str?.takeIf { u -> u.startsWith("http") } }
            .map { Candidate(it, Sources.DISCOGS) }
    }
}

class YouTube(private val api: Api) {
    fun candidates(a: MbArtist): Sequence<Candidate> =
        a.rels("youtube").map { it.url }.filter { CHANNEL.containsMatchIn(it) }.sortedBy { if (it.contains("/channel/")) 0 else 1 }
            .asSequence()
            .mapNotNull { url -> api.get(url)?.text?.let { ogImage(it) } }
            .map { Candidate(it, Sources.YOUTUBE) }

    companion object {
        private val CHANNEL = Regex("^https?://(www\\.|m\\.)?youtube\\.com/(channel/|user/|c/|@)")
        private val OG = Regex("<meta\\s+property=\"og:image\"\\s+content=\"([^\"]+)\"")

        fun ogImage(html: String): String? = OG.find(html)?.groupValues?.get(1)?.replace("&amp;", "&")
    }
}

class Deezer(private val api: Api, private val base: String = "https://api.deezer.com") {
    fun candidates(name: String): List<Candidate> {
        val want = Names.fold(name)
        val r = api.json("$base/search/artist?q=${Api.enc(name.trim())}") ?: return emptyList()
        return r["data"].list.filter { Names.fold(it["name"].str) == want }
            .sortedWith(compareBy<J> { it["name"].str?.trim() != name.trim() }.thenByDescending { it["nb_fan"].long ?: 0 })
            .mapNotNull { it["picture_xl"].str?.takeIf { u -> u.startsWith("http") && !u.contains("/artist//") } }
            .map { Candidate(it, Sources.DEEZER) }
    }
}
