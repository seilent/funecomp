package net.seilent.funecomp.artist

import java.io.IOException

class Art(val jpeg: ByteArray, val w: Int, val h: Int, val name: String, val mbid: String?, val source: String, val credit: Credit?)

class ArtistArt(
    private val http: Http,
    private val codec: Codec,
    private val cache: ArtCache,
    private val sources: () -> Sources,
    version: String,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    mono: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val ua = "funecomp/$version (github@seilent.net)"
    private fun api(limiter: RateLimiter) = Api(http, ua, limiter, sleep)

    private val musicBrainzLimiter = RateLimiter(1000, mono)
    private val wikimediaLimiter = RateLimiter.perMinute(200, mono)
    private val audioDbLimiter = RateLimiter.perMinute(30, mono)
    private val discogsLimiter = RateLimiter.perMinute(25, mono)
    private val otherLimiter = RateLimiter(1000, mono)

    private val mb = MusicBrainz(api(musicBrainzLimiter))
    private val wikimedia = Wikimedia(api(wikimediaLimiter))
    private val audioDb = TheAudioDb(api(audioDbLimiter))
    private val fanartTv = FanartTv(api(otherLimiter), { sources().fanartTvKey.trim() })
    private val discogs = Discogs(api(discogsLimiter))
    private val youtube = YouTube(api(otherLimiter))
    private val deezer = Deezer(api(otherLimiter))
    private val images = mapOf(
        Wikimedia.SOURCE to api(wikimediaLimiter),
        Sources.THE_AUDIO_DB to api(audioDbLimiter),
        Sources.DISCOGS to api(discogsLimiter),
    )
    private val otherImages = api(otherLimiter)

    @Throws(IOException::class)
    fun get(name: String, mbid: String?, w: Int, h: Int): Art? {
        val on = sources().enabled
        val now = clock()
        val id = Names.mbid(mbid) ?: resolve(name, now)
        val key = id ?: "name:${Names.fold(name)}"
        cache.hit(key)?.takeIf { it.source in on }?.let { e ->
            cache.rendered(key, w, h)?.let { return Art(it, w, h, e.name, e.mbid, e.source, e.credit) }
            val src = cache.source(key)
            val jpeg = src?.let { Backdrop.render(codec, it, w, h) }
            if (jpeg != null) {
                cache.putRendered(key, w, h, jpeg)
                return Art(jpeg, w, h, e.name, e.mbid, e.source, e.credit)
            }
        }
        cache.miss(key)?.let { m -> if (now - m.at < TTL_MS && m.sources.containsAll(on)) return null }
        val found = lookup(name, id, w, h, on)
        if (found == null) {
            cache.putMiss(key, now, on)
            return null
        }
        val (c, src, jpeg) = found
        cache.putHit(ArtCache.Entry(key, name.trim(), id, c.source, c.credit, c.url, now), src)
        cache.putRendered(key, w, h, jpeg)
        return Art(jpeg, w, h, name.trim(), id, c.source, c.credit)
    }

    fun cached(name: String, mbid: String?): ByteArray? = cachedEntry(name, mbid)?.let { source(it) }

    fun cachedEntry(name: String, mbid: String?): ArtCache.Entry? {
        val key = (Names.mbid(mbid) ?: cache.mbid(name)?.mbid) ?: "name:${Names.fold(name)}"
        return cache.hit(key)?.takeIf { it.source in sources().enabled && cache.hasSource(key) }
    }

    fun source(e: ArtCache.Entry): ByteArray? = cache.source(e.key)

    fun credits(): List<ArtCache.Entry> = cache.entries().filter { it.credit != null }

    private fun resolve(name: String, now: Long): String? {
        cache.mbid(name)?.let { l -> if (l.mbid != null || now - l.at < TTL_MS) return l.mbid }
        val id = mb.search(name)
        cache.putMbid(name, id, now)
        return id
    }

    private fun lookup(name: String, id: String?, w: Int, h: Int, on: Set<String>): Triple<Candidate, ByteArray, ByteArray>? {
        var error: IOException? = null
        fun <T> attempt(block: () -> T): T? = try {
            block()
        } catch (e: IOException) {
            error = error ?: e
            null
        }
        val a = id?.let { attempt { mb.artist(it) } }
        val chain = buildList<() -> List<Candidate>> {
            if (a != null) add { wikimedia.candidates(a, w, h) }
            if (id != null && Sources.THE_AUDIO_DB in on) add { audioDb.candidates(id) }
            if (id != null && Sources.FANART_TV in on) add { fanartTv.candidates(id) }
            if (a != null && Sources.DISCOGS in on) add { discogs.candidates(a) }
            if (a != null && Sources.YOUTUBE in on) add { youtube.candidates(a) }
            if (Sources.DEEZER in on) add { deezer.candidates(name) }
        }
        for (step in chain) {
            for (c in attempt(step)?.take(MAX_TRIES) ?: continue) {
                val src = attempt { (images[c.source] ?: otherImages).get(c.url) }?.body ?: continue
                val jpeg = Backdrop.render(codec, src, w, h) ?: continue
                return Triple(c, src, jpeg)
            }
        }
        error?.let { throw it }
        return null
    }

    companion object {
        const val TTL_MS = 7L * 24 * 3600 * 1000
        const val MAX_TRIES = 4
    }
}
