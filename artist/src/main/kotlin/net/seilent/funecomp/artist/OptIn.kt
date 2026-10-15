package net.seilent.funecomp.artist

data class Sources(
    val theAudioDb: Boolean = false,
    val discogs: Boolean = false,
    val fanartTv: Boolean = false,
    val fanartTvKey: String = "",
    val deezer: Boolean = false,
    val youtube: Boolean = false,
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

    companion object {
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
    fun candidates(a: MbArtist): List<Candidate> {
        val channels = a.rels("youtube").map { it.url }.filter { CHANNEL.containsMatchIn(it) }.sortedBy { if (it.contains("/channel/")) 0 else 1 }
        for (url in channels) {
            val page = api.get(url)?.text ?: continue
            val img = ogImage(page) ?: continue
            return listOf(Candidate(img, Sources.YOUTUBE))
        }
        return emptyList()
    }

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
            .sortedByDescending { it["nb_fan"].long ?: 0 }
            .mapNotNull { it["picture_xl"].str?.takeIf { u -> u.startsWith("http") && !u.contains("/artist//") } }
            .take(1)
            .map { Candidate(it, Sources.DEEZER) }
    }
}
