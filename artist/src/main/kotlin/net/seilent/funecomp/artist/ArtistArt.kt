package net.seilent.funecomp.artist

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
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
    val picks: ArtPicks = ArtPicks(),
    private val placeholders: Set<String> = Deezer.PLACEHOLDERS,
    val gallery: GalleryStore? = null,
    private val log: (String) -> Unit = {},
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
    private val deezer = Deezer(api(otherLimiter), placeholders)
    private var scanned = false
    private val images = mapOf(
        Wikimedia.SOURCE to api(wikimediaLimiter),
        Sources.THE_AUDIO_DB to api(audioDbLimiter),
        Sources.DISCOGS to api(discogsLimiter),
    )
    private val otherImages = api(otherLimiter)

    class Mark(val sources: String, val until: Long)

    @Volatile private var missDue = Long.MAX_VALUE
    private val pruneSafe = picks.skipped == 0 && (picks.read || gallery?.any() != true)
    private val missingLogged = HashSet<String>()

    private class Found(val c: Candidate, val src: ByteArray, val jpeg: ByteArray, val mbid: String?, val by: String)

    @Throws(IOException::class)
    fun get(name: String, mbid: String?, w: Int, h: Int): Art? {
        val p = picks.of(mbid, name)
        when (p?.kind) {
            ArtPicks.IMAGE -> return picked(p, name, mbid, w, h)
            ArtPicks.NONE -> return null
        }
        val on = sources().enabled
        val now = clock()
        val tag = tag(p, mbid)
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
        val skipped = AtomicBoolean(false)
        val found = find(name, tag, id, key, w, h, on, now, skipped)
        if (found == null) {
            cache.putMiss(key, now, on, keyHash)
            if (skipped.get() && blocks.drop(key, id, name.trim(), now)) blocks.save()
            due(now)
            return null
        }
        return keep(name, tag, key, found, w, h, now)
    }

    @Throws(IOException::class)
    fun refresh(name: String, mbid: String?, w: Int, h: Int): Art? {
        val p = picks.of(mbid, name)
        if (p != null && p.kind != ArtPicks.ARTIST) return get(name, mbid, w, h)
        val on = sources().enabled
        val now = clock()
        val tag = tag(p, mbid)
        val id = tag ?: resolve(name, now)
        val key = id ?: "name:${Names.fold(name)}"
        val found = try {
            if (online()) find(name, tag, id, key, w, h, on, now, AtomicBoolean(false)) else null
        } catch (e: IOException) {
            null
        }
        return if (found == null) get(name, mbid, w, h) else keep(name, tag, key, found, w, h, now)
    }

    private fun tag(p: ArtPicks.Pick?, mbid: String?): String? = p?.takeIf { it.kind == ArtPicks.ARTIST }?.mbid ?: Names.mbid(mbid)

    private fun picked(p: ArtPicks.Pick, name: String, mbid: String?, w: Int, h: Int): Art? {
        val key = p.cacheKey
        val tag = Names.mbid(mbid)
        val e = cache.hit(key)
        if (e != null) {
            cache.rendered(key, w, h)?.let { return Art(it, w, h, name.trim(), tag, e.source, e.credit) }
            val jpeg = cache.source(key)?.let { Backdrop.render(codec, it, w, h) }
            if (jpeg != null) {
                cache.putRendered(key, w, h, jpeg)
                return Art(jpeg, w, h, name.trim(), tag, e.source, e.credit)
            }
        }
        if (p.source == Sources.GALLERY) return gallery(p, name, tag, w, h)
        val now = clock()
        p.failedAt?.let { at ->
            if (now - at < TTL_MS) {
                due(at)
                return null
            }
        }
        if (!online()) throw IOException("offline")
        val src = (images[p.source] ?: otherImages).get(p.url)?.body
        val jpeg = src?.takeIf { check(it) == null }?.let { Backdrop.render(codec, it, w, h) }
        if (src == null || jpeg == null) {
            picks.failed(p, now)
            picks.save()
            due(now)
            return null
        }
        picks.ifCurrent(p) {
            store(p, src)
            cache.putRendered(key, w, h, jpeg)
        } ?: return null
        if (p.failedAt != null) {
            picks.retry(p)
            picks.save()
        }
        return Art(jpeg, w, h, name.trim(), tag, p.source, p.credit)
    }

    private fun gallery(p: ArtPicks.Pick, name: String, tag: String?, w: Int, h: Int): Art? {
        val src = gallery?.get(p.sha)
        val jpeg = src?.let { Backdrop.render(codec, it, w, h) }
        if (src == null || jpeg == null) {
            if (synchronized(missingLogged) { missingLogged.add(p.sha) }) log("art pick gallery missing ${p.name}")
            return null
        }
        picks.ifCurrent(p) {
            store(p, src)
            cache.putRendered(p.cacheKey, w, h, jpeg)
        } ?: return null
        return Art(jpeg, w, h, name.trim(), tag, p.source, p.credit)
    }

    @Throws(IOException::class)
    fun previewArtist(name: String, mbid: String, w: Int, h: Int): ByteArray? {
        val on = sources().enabled
        val hit = cache.hit(mbid)?.takeIf { it.source in on && !blocked(it) }
        if (hit != null) cache.source(mbid)?.let { return it }
        cache.miss(mbid)?.let { m ->
            if (clock() - m.at < TTL_MS && m.sources.containsAll(on) && m.keyHash == keyHash(on) && m.cleaned) return null
        }
        if (!online()) throw IOException("offline")
        return lookup(name, name, mbid, w, h, on, mbid, AtomicBoolean())?.second
    }

    fun galleryOffer(src: ByteArray, title: String): Offered {
        check(src)?.let { return Offered.Refused(it) }
        return Offered.Ok(Offer(ArtPicks.IMAGE, Sources.GALLERY, "", GalleryStore.ID_PREFIX + ArtCache.sha256(src), null, null, title, src))
    }

    fun pruneGallery(): Int? {
        val g = gallery ?: return 0
        if (!pruneSafe) {
            log("art pick prune skipped")
            return null
        }
        return g.prune(GalleryStore.shas(picks.list()))
    }

    fun galleryMissing(): Set<String> = picks.list()
        .filter { it.source == Sources.GALLERY && it.kind == ArtPicks.IMAGE && gallery?.has(it.sha) != true && !cache.hasSource(it.cacheKey) }
        .map { it.sha }.toSet()

    fun store(p: ArtPicks.Pick, src: ByteArray) = picks.ifCurrent(p) {
        cache.putHit(ArtCache.Entry(p.cacheKey, p.name, p.mbid, p.source, p.credit, p.url, clock(), p.id, ArtCache.sha256(src)), src)
    }

    fun check(src: ByteArray): String? {
        val p = codec.decode(src, maxOf(PICK_W, PICK_H) * 4) ?: return PickLink.NOT_IMAGE
        try {
            val r = Crop.rect(p.w, p.h, PICK_W, PICK_H)
            return if (r.w < PICK_W || r.h < PICK_H) PickLink.TOO_SMALL else null
        } finally {
            p.release()
        }
    }

    fun offer(text: String): Offered = try {
        when (val l = PickLink.parse(text)) {
            PickLink.Unsupported -> Offered.Refused(PickLink.UNSUPPORTED)
            is PickLink.MusicBrainz -> mb.artist(l.mbid)?.let { a -> Offered.Ok(Offer(ArtPicks.ARTIST, Sources.MUSICBRAINZ, "https://musicbrainz.org/artist/${a.id}", a.id, null, a.id, a.name, null)) }
                ?: Offered.Refused(PickLink.NOT_FOUND)
            is PickLink.YouTube -> youTube(l.url)
            is PickLink.YouTubeMusic -> youTubeMusic(l)
            is PickLink.Commons -> commons(l.file)
            is PickLink.Direct -> image(Sources.LINK, l.url, l.url, null, l.url.substringAfterLast('/').substringBefore('?'))
        }
    } catch (e: IOException) {
        Offered.Refused(PickLink.UNREACHABLE)
    }

    private fun youTube(url: String): Offered = YouTube.page(otherImages, url)?.let { html ->
        val img = YouTube.ogImage(html) ?: return Offered.Refused(PickLink.NOT_IMAGE)
        image(Sources.YOUTUBE, img, ImageIds.of(Sources.YOUTUBE, img), null, YouTube.ogTitle(html) ?: "")
    } ?: Offered.Refused(PickLink.UNREACHABLE)

    private fun youTubeMusic(l: PickLink.YouTubeMusic): Offered {
        val music = reached {
            YouTube.page(otherImages, l.url)?.let { html ->
                YouTube.ogImage(html)?.let { YouTube.full(it) }?.let { img ->
                    image(Sources.YOUTUBE, img, ImageIds.of(Sources.YOUTUBE, img), null, YouTube.ogTitle(html) ?: "", YouTube.MUSIC_LABEL)
                } ?: Offered.Refused(PickLink.NOT_FOUND)
            } ?: Offered.Refused(PickLink.UNREACHABLE)
        }
        if ((music as? Offered.Refused)?.reason == PickLink.NOT_FOUND) return music
        val www = reached { youTube(l.www) }
        val ok = listOf(music, www).filterIsInstance<Offered.Ok>().flatMap { it.offers }.distinctBy { it.id }
        return if (ok.isEmpty()) music else Offered.Ok(ok)
    }

    private inline fun reached(get: () -> Offered): Offered = try {
        get()
    } catch (e: IOException) {
        if (HttpRun.current.get()?.cancelled == true) throw e
        Offered.Refused(PickLink.UNREACHABLE)
    }

    fun take(t: Tile): Offered = try {
        if (t.source == Wikimedia.SOURCE) commons(t.id.removePrefix("File:")) else image(t.source, t.full, t.id, t.credit, t.caption)
    } catch (e: IOException) {
        Offered.Refused(PickLink.UNREACHABLE)
    }

    private fun commons(file: String): Offered {
        val c = wikimedia.file(file) ?: return Offered.Refused(PickLink.UNREACHABLE)
        return image(Wikimedia.SOURCE, c.url, c.id, c.credit, c.id.removePrefix("File:"))
    }

    private fun image(source: String, url: String, id: String, credit: Credit?, title: String, label: String = Sources.label(source)): Offered {
        val src = (images[source] ?: otherImages).get(url)?.body ?: return Offered.Refused(PickLink.UNREACHABLE)
        if (source == Sources.DEEZER && deezer.placeholder(src)) return Offered.Refused(PickLink.NOT_IMAGE)
        check(src)?.let { return Offered.Refused(it) }
        return Offered.Ok(Offer(ArtPicks.IMAGE, source, url, id, credit, null, title, src, label))
    }

    fun browse(name: String, mbid: String?, px: Int, sink: (Tile) -> Unit): Boolean {
        val on = sources().enabled
        val run = HttpRun.current.get()
        fun <T> attempt(block: () -> T): T? = try {
            block()
        } catch (e: IOException) {
            if (run?.cancelled == true) throw e
            null
        } catch (e: RuntimeException) {
            null
        }
        fun tile(source: String, caption: String, preview: String, full: String, id: String = ImageIds.of(source, full), credit: Credit? = null) {
            val b = attempt { (images[source] ?: otherImages).get(preview) }?.body ?: return
            sink(Tile(source, caption, b, full, id, credit))
        }
        val ids = ((attempt { mb.searchAll(name) } ?: emptyList()).take(BROWSE_MBIDS) + listOfNotNull(Names.mbid(mbid))).distinct()
        val artists = ids.mapNotNull { attempt { mb.artist(it) } }
        fun caption(a: MbArtist) = a.disambiguation.ifEmpty { a.name }
        for (source in sources().order.filter { it in on }) {
            when (source) {
                Wikimedia.SOURCE -> for (a in artists) attempt { wikimedia.candidates(a, PICK_W, PICK_H, px) }?.take(MAX_TRIES)?.forEach { tile(source, caption(a), it.url, it.id, it.id, it.credit) }
                Sources.THE_AUDIO_DB -> for (a in artists) attempt { audioDb.candidates(a.id) }?.take(MAX_TRIES)?.forEach { tile(source, caption(a), it.url, it.url) }
                Sources.FANART_TV -> for (a in artists) attempt { fanartTv.candidates(a.id) }?.take(MAX_TRIES)?.forEach { tile(source, caption(a), it.url, it.url) }
                Sources.DISCOGS -> for (a in artists) attempt { discogs.previews(a) }?.take(MAX_TRIES)?.forEach { (small, full) -> tile(source, caption(a), small, full) }
                Sources.YOUTUBE -> for (a in artists) attempt { youtube.candidates(a).take(MAX_TRIES).toList() }?.forEach { tile(source, caption(a), YouTube.sized(it.url, YT_PREVIEW), it.url) }
                Sources.DEEZER -> attempt { deezer.search(name) }?.take(BROWSE_RESULTS)?.forEach { d ->
                    val small = Deezer.picture(d, "picture_medium") ?: return@forEach
                    val full = Deezer.picture(d, "picture_xl") ?: return@forEach
                    tile(source, d["name"].str ?: "", small, full)
                }
            }
        }
        if (Sources.YOUTUBE !in on) return true
        val found = attempt { youtube.search(name) }?.takeIf { it.isNotEmpty() } ?: return false
        found.take(BROWSE_RESULTS).forEach { c -> tile(Sources.YOUTUBE, c.title, YouTube.sized(c.thumb, YT_PREVIEW), YouTube.sized(c.thumb, YT_FULL)) }
        return true
    }

    private fun find(name: String, tag: String?, id: String?, key: String, w: Int, h: Int, on: Set<String>, now: Long, skipped: AtomicBoolean): Found? {
        lookup(name, name, id, w, h, on, key, skipped)?.let { (c, src, jpeg) -> return Found(c, src, jpeg, id, if (tag == null && id != null) name.trim() else "") }
        if (tag != null) return null
        for (v in Names.variants(name)) {
            val vid = resolve(v, now)
            val (c, src, jpeg) = lookup(v, name, vid, w, h, on, key, skipped) ?: continue
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

    fun blocked(e: ArtCache.Entry): Boolean = !picked(e) && (blocks.blocked(e.key, e.source, ImageIds.of(e), cache.sha(e), e.name) || placeholder(e))

    private fun placeholder(e: ArtCache.Entry) = e.source == Sources.DEEZER && cache.sha(e) in placeholders

    @Synchronized
    fun scanPlaceholders(): Int {
        if (scanned) return 0
        var n = 0
        for (e in cache.entries()) {
            try {
                if (!placeholder(e)) continue
                cache.drop(e.key)
                blocks.drop(e.key, e.mbid, e.name, clock())
                n++
            } catch (_: IOException) {
            }
        }
        if (n > 0) blocks.save()
        scanned = true
        return n
    }

    fun hasHit(key: String): Boolean = cache.hit(key) != null

    fun known(name: String, mbid: String?): Boolean = keyOf(name, mbid).let { cache.hit(it) != null || cache.miss(it) != null }

    fun forget(key: String) = cache.drop(key)

    fun forget(e: ArtCache.Entry) = cache.drop(e.key, e.at)

    fun resolvedBy(e: ArtCache.Entry): String =
        e.resolvedBy.ifEmpty { e.name.takeIf { e.mbid != null && cache.mbid(it)?.mbid == e.mbid } ?: "" }

    fun picked(e: ArtCache.Entry): Boolean = e.key.startsWith(ArtPicks.PREFIX)

    fun wrongArtistOffered(e: ArtCache.Entry): Boolean {
        if (picked(e)) return false
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
        val all = cache.entries().filter { Names.fold(it.name) == f || (m != null && it.mbid == m) } +
            listOfNotNull(cache.hit(keyOf(name, mbid)), picks.of(mbid, name)?.let { cache.hit(it.cacheKey) })
        return all.distinctBy { it.key }
    }

    fun renderedCrc(e: ArtCache.Entry, w: Int, h: Int): Long? = cache.rendered(e.key, w, h)?.let { CRC32().apply { update(it) }.value }

    fun forgetName(name: String) = cache.forgetName(name)

    fun withSha(e: ArtCache.Entry): ArtCache.Entry =
        if (e.sha.isNotEmpty()) e else ArtCache.Entry(e.key, e.name, e.mbid, e.source, e.credit, e.url, e.at, e.id, cache.sha(e), e.resolvedBy)

    fun keyOf(name: String, mbid: String?): String = (tag(picks.of(mbid, name), mbid) ?: cache.mbid(name)?.mbid) ?: "name:${Names.fold(name)}"

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
        val p = picks.of(mbid, name)
        when (p?.kind) {
            ArtPicks.IMAGE -> return cache.hit(p.cacheKey)
            ArtPicks.NONE -> return null
        }
        val key = (tag(p, mbid) ?: cache.mbid(name)?.mbid?.takeIf { it !in blocks.rejected(name) }) ?: "name:${Names.fold(name)}"
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

    private fun lookup(name: String, display: String, id: String?, w: Int, h: Int, on: Set<String>, key: String, skipped: AtomicBoolean): Triple<Candidate, ByteArray, ByteArray>? {
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
                if (c.source == Sources.DEEZER && deezer.placeholder(src)) {
                    skipped.set(true)
                    continue
                }
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
        const val PICK_W = 360
        const val PICK_H = 480
        const val BROWSE_MBIDS = 5
        const val BROWSE_RESULTS = 10
        const val YT_PREVIEW = 176
        const val YT_FULL = 900
    }
}
