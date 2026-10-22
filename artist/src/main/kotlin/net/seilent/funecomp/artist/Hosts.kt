package net.seilent.funecomp.artist

interface Gate {
    fun limiter(url: String): RateLimiter

    fun pause(ms: Long)

    fun <T> call(url: String, block: () -> T): T = block()
}

class PlainGate(private val limiter: RateLimiter, private val sleep: (Long) -> Unit) : Gate {
    override fun limiter(url: String) = limiter

    override fun pause(ms: Long) = sleep(ms)
}

class Hosts(private val clock: Clock) : Gate {
    private fun every(ms: Long) = RateLimiter(ms, clock::now)

    val musicBrainz = every(1000)
    val wikimedia = every(60_000L / 200)
    val audioDb = every(60_000L / 30)
    val audioDbImages = every(1000)
    val fanartTv = every(1000)
    val fanartTvImages = every(1000)
    val discogs = every(60_000L / 25)
    val discogsImages = every(1000)
    val youtube = every(1000)
    val youtubeImages = every(1000)
    val deezer = every(1000)
    val deezerImages = every(1000)
    private val other = object : LinkedHashMap<String, RateLimiter>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RateLimiter>) = size > OTHER_HOSTS
    }
    private val permitLock = Any()
    private var wikimediaFree = WIKIMEDIA_CONCURRENT
    private val wikimediaCond = clock.condition()

    override fun limiter(url: String): RateLimiter {
        val h = host(url)
        return when {
            h == "musicbrainz.org" -> musicBrainz
            wikimediaHost(h) -> wikimedia
            h == "www.theaudiodb.com" -> audioDb
            h == "r2.theaudiodb.com" -> audioDbImages
            h == "webservice.fanart.tv" -> fanartTv
            h == "assets.fanart.tv" -> fanartTvImages
            h == "api.discogs.com" -> discogs
            h == "i.discogs.com" -> discogsImages
            h == "www.youtube.com" || h == "music.youtube.com" -> youtube
            h == "yt3.ggpht.com" || h == "yt3.googleusercontent.com" || h == "i.ytimg.com" -> youtubeImages
            h == "api.deezer.com" -> deezer
            h.endsWith(".dzcdn.net") -> deezerImages
            else -> synchronized(other) { other.getOrPut(h) { every(1000) } }
        }
    }

    override fun pause(ms: Long) = clock.condition().await(HttpRun.current.get(), clock.now() + ms) { false }

    override fun <T> call(url: String, block: () -> T): T {
        if (!wikimediaHost(host(url))) return block()
        wikimediaCond.await(HttpRun.current.get(), null) {
            synchronized(permitLock) {
                if (wikimediaFree > 0) {
                    wikimediaFree--
                    true
                } else {
                    false
                }
            }
        }
        try {
            return block()
        } finally {
            synchronized(permitLock) { wikimediaFree++ }
            wikimediaCond.signalAll()
        }
    }

    companion object {
        const val WIKIMEDIA_CONCURRENT = 3
        const val OTHER_HOSTS = 32

        fun host(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':').lowercase()

        private fun wikimediaHost(h: String) = h.endsWith(".wikimedia.org") || h == "www.wikidata.org" || h.endsWith(".wikipedia.org")
    }
}
