package net.seilent.funecomp.artist

import java.io.IOException
import java.util.zip.CRC32

class Art(val jpeg: ByteArray, val w: Int, val h: Int, val name: String, val mbid: String?, val source: String, val credit: Credit?, val wrongArtist: Boolean = false)

class ArtistArt(
    private val http: Http,
    private val codec: Codec,
    private val cache: ArtCache,
    private val sources: () -> Sources,
    version: String,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    mono: () -> Long = { System.nanoTime() / 1_000_000 },
    private val online: () -> Boolean = { true },
    val blocks: ArtBlocks = ArtBlocks(),
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

    class Mark(val sources: String, val until: Long)

    @Volatile private var missDue = Long.MAX_VALUE

    private class Found(val c: Candidate, val src: ByteArray, val jpeg: ByteArray, val mbid: String?, val by: String)

    @Throws(IOException::class)
    fun get(name: String, mbid: String?, w: Int, h: Int): Art? {
        val on = sources().enabled
        val now = clock()
        val tag = Names.mbid(mbid)
        val id = tag ?: resolve(name, now)
        val key = id ?: "name:${Names.fold(name)}"
        val hit = cache.hit(key)?.takeIf { it.source in on }
        if (hit != null && blocked(hit)) {
            cache.drop(key)
        } else if (hit != null) {
            val wrong = tag == null && wrongArtistOffered(hit, name)
            cache.rendered(key, w, h)?.let { return Art(it, w, h, hit.name, hit.mbid, hit.source, hit.credit, wrong) }
            val jpeg = cache.source(key)?.let { Backdrop.render(codec, it, w, h) }
            if (jpeg != null) {
                cache.putRendered(key, w, h, jpeg)
                return Art(jpeg, w, h, hit.name, hit.mbid, hit.source, hit.credit, wrong)
            }
        }
        val keyHash = keyHash(on)
        cache.miss(key)?.let { m ->
            if (now - m.at < TTL_MS && m.sources.containsAll(on) && m.keyHash == keyHash && m.cleaned) {
                due(m.at)
                return null
            }
        }
        if (!online()) throw IOException("offline")
        val found = find(name, tag, id, key, w, h, on, now)
        if (found == null) {
            cache.putMiss(key, now, on, keyHash)
            due(now)
            return null
        }
        return keep(name, tag, key, found, w, h, now)
    }

    @Throws(IOException::class)
    fun refresh(name: String, mbid: String?, w: Int, h: Int): Art? {
        val on = sources().enabled
        val now = clock()
        val tag = Names.mbid(mbid)
        val id = tag ?: resolve(name, now)
        val key = id ?: "name:${Names.fold(name)}"
        val found = try {
            if (online()) find(name, tag, id, key, w, h, on, now) else null
        } catch (e: IOException) {
            null
        }
        return if (found == null) get(name, mbid, w, h) else keep(name, tag, key, found, w, h, now)
    }

    private fun find(name: String, tag: String?, id: String?, key: String, w: Int, h: Int, on: Set<String>, now: Long): Found? {
        lookup(name, name, id, w, h, on, key)?.let { (c, src, jpeg) -> return Found(c, src, jpeg, id, if (tag == null && id != null) name.trim() else "") }
        if (tag != null) return null
        for (v in Names.variants(name)) {
            val vid = resolve(v, now)
            val (c, src, jpeg) = lookup(v, name, vid, w, h, on, key) ?: continue
            return Found(c, src, jpeg, vid, if (vid != null) v else "")
        }
        return null
    }

    private fun keep(name: String, tag: String?, key: String, f: Found, w: Int, h: Int, now: Long): Art {
        val e = ArtCache.Entry(key, name.trim(), f.mbid, f.c.source, f.c.credit, f.c.url, now, f.c.id, ArtCache.sha256(f.src), f.by)
        cache.putHit(e, f.src)
        cache.putRendered(key, w, h, f.jpeg)
        return Art(f.jpeg, w, h, name.trim(), f.mbid, f.c.source, f.c.credit, tag == null && wrongArtistOffered(e, name))
    }

    fun blocked(e: ArtCache.Entry): Boolean = blocks.blocked(e.key, e.source, ImageIds.of(e), cache.sha(e), e.name)

    fun forget(key: String) = cache.drop(key)

    fun forget(e: ArtCache.Entry) = cache.drop(e.key, e.at)

    fun resolvedBy(e: ArtCache.Entry): String =
        e.resolvedBy.ifEmpty { e.name.takeIf { e.mbid != null && cache.mbid(it)?.mbid == e.mbid } ?: "" }

    fun wrongArtistOffered(e: ArtCache.Entry): Boolean {
        val by = resolvedBy(e)
        return by.isNotEmpty() && e.mbid != null && cache.mbid(by)?.mbid == e.mbid
    }

    fun wrongArtistOffered(e: ArtCache.Entry, name: String): Boolean = wrongArtistOffered(e) && resolves(resolvedBy(e), name)

    fun resolves(by: String, name: String): Boolean {
        val f = Names.fold(by)
        return f == Names.fold(name) || Names.variants(name).any { Names.fold(it) == f }
    }

    fun candidates(name: String, mbid: String?): List<ArtCache.Entry> {
        val f = Names.fold(name)
        val m = Names.mbid(mbid)
        val all = cache.entries().filter { Names.fold(it.name) == f || (m != null && it.mbid == m) } + listOfNotNull(cache.hit(keyOf(name, mbid)))
        return all.distinctBy { it.key }
    }

    fun renderedCrc(e: ArtCache.Entry, w: Int, h: Int): Long? = cache.rendered(e.key, w, h)?.let { CRC32().apply { update(it) }.value }

    fun forgetName(name: String) = cache.forgetName(name)

    fun withSha(e: ArtCache.Entry): ArtCache.Entry =
        if (e.sha.isNotEmpty()) e else ArtCache.Entry(e.key, e.name, e.mbid, e.source, e.credit, e.url, e.at, e.id, cache.sha(e), e.resolvedBy)

    fun keyOf(name: String, mbid: String?): String = (Names.mbid(mbid) ?: cache.mbid(name)?.mbid) ?: "name:${Names.fold(name)}"

    fun beginMark() {
        missDue = Long.MAX_VALUE
    }

    fun mark() = Mark(signature(), missDue)

    fun holds(m: Mark) = m.sources == signature() && clock() < m.until

    private fun signature(): String {
        val on = sources().enabled
        return on.sorted().joinToString(",") + " " + keyHash(on)
    }

    private fun keyHash(on: Set<String>) = if (Sources.FANART_TV in on) cache.keyHash(sources().fanartTvKey.trim()) else ""

    private fun due(at: Long) {
        if (at + TTL_MS < missDue) missDue = at + TTL_MS
    }

    fun cached(name: String, mbid: String?): ByteArray? = cachedEntry(name, mbid)?.let { source(it) }

    fun cachedEntry(name: String, mbid: String?): ArtCache.Entry? {
        val key = (Names.mbid(mbid) ?: cache.mbid(name)?.mbid?.takeIf { it !in blocks.rejected(name) }) ?: "name:${Names.fold(name)}"
        return cache.hit(key)?.takeIf { it.source in sources().enabled && cache.hasSource(key) && !blocked(it) }
    }

    fun source(e: ArtCache.Entry): ByteArray? = cache.source(e.key)

    fun credits(): List<ArtCache.Entry> = cache.entries().filter { it.credit != null }

    private fun resolve(name: String, now: Long): String? {
        val skip = blocks.rejected(name)
        cache.mbid(name)?.let { l ->
            if (l.mbid != null && l.mbid !in skip) return l.mbid
            if (l.mbid == null && now - l.at < TTL_MS) {
                due(l.at)
                return null
            }
        }
        if (!online()) throw IOException("offline")
        val id = mb.search(name, skip)
        cache.putMbid(name, id, now)
        return id
    }

    private fun lookup(name: String, display: String, id: String?, w: Int, h: Int, on: Set<String>, key: String): Triple<Candidate, ByteArray, ByteArray>? {
        var error: IOException? = null
        fun <T> attempt(block: () -> T): T? = try {
            block()
        } catch (e: IOException) {
            error = error ?: e
            null
        }
        val a = id?.let { attempt { mb.artist(it) } }
        val steps = mapOf<String, (() -> Sequence<Candidate>)?>(
            Wikimedia.SOURCE to a?.let { { wikimedia.candidates(it, w, h).asSequence() } },
            Sources.THE_AUDIO_DB to id?.takeIf { Sources.THE_AUDIO_DB in on }?.let { { audioDb.candidates(it).asSequence() } },
            Sources.FANART_TV to id?.takeIf { Sources.FANART_TV in on }?.let { { fanartTv.candidates(it).asSequence() } },
            Sources.DISCOGS to a?.takeIf { Sources.DISCOGS in on }?.let { { discogs.candidates(it).asSequence() } },
            Sources.YOUTUBE to a?.takeIf { Sources.YOUTUBE in on }?.let { { youtube.candidates(it) } },
            Sources.DEEZER to if (Sources.DEEZER in on) ({ deezer.candidates(name).asSequence() }) else null,
        )
        for (step in sources().order.mapNotNull { steps[it] }) {
            val candidates = attempt { step().filterNot { blocks.blocked(key, it.source, it.id, name = display) }.take(MAX_TRIES).iterator() } ?: continue
            while (attempt { candidates.hasNext() } == true) {
                val c = candidates.next()
                val src = attempt { (images[c.source] ?: otherImages).get(c.url) }?.body ?: continue
                if (blocks.blocked(key, c.source, "", ArtCache.sha256(src), display)) continue
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
