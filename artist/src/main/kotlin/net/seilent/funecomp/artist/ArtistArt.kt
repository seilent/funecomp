package net.seilent.funecomp.artist

import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
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
    private val time: Clock = Clock.SYSTEM,
    private val online: () -> Boolean = { true },
    val blocks: ArtBlocks = ArtBlocks(),
    val picks: ArtPicks = ArtPicks(),
    private val placeholders: Set<String> = Deezer.PLACEHOLDERS,
    val gallery: GalleryStore? = null,
    private val log: (String) -> Unit = {},
    private val browseLog: (String) -> Unit = {},
    private val targets: () -> Targets = { Targets.NONE },
) {
    private val ua = "funecomp/$version (github@seilent.net)"
    private val hosts = Hosts(time)
    private val api = Api(http, ua, hosts)

    private val mb = MusicBrainz(api)
    private val wikimedia = Wikimedia(api)
    private val audioDb = TheAudioDb(api)
    private val fanartTv = FanartTv(api, { sources().fanartTvKey.trim() })
    private val discogs = Discogs(api)
    private val youtube = YouTube(api)
    private val deezer = Deezer(api, placeholders)
    private var scanned = false

    class Mark(val sources: String, val until: Long)

    @Volatile private var missDue = Long.MAX_VALUE
    private val pruneSafe = picks.skipped == 0 && (picks.read || gallery?.any() != true)
    private val missingLogged = HashSet<String>()

    private class Found(val c: Candidate, val url: String, val src: ByteArray, val size: Size, val jpeg: ByteArray, val mbid: String?, val by: String, val base: String)

    private class Hit(val c: Candidate, val url: String, val src: ByteArray, val size: Size, val jpeg: ByteArray, val base: String = "")

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
        val kept = if (hit != null && blocked(hit)) {
            cache.drop(key)
            false
        } else if (hit != null) {
            val wrong = tag == null && wrongArtistOffered(hit, name)
            render(hit, w, h)?.let { return Art(it, w, h, hit.name, hit.mbid, hit.source, hit.credit, wrong) }
            if (now - hit.failed < TTL_MS) {
                due(hit.failed)
                return null
            }
            true
        } else {
            false
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
            if (!kept) {
                cache.putMiss(key, now, on, keyHash)
                if (skipped.get() && blocks.drop(key, id, name.trim(), now)) blocks.save()
            } else if (hit != null) {
                cache.putFailed(key, hit.at, now)
            }
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

    fun render(e: ArtCache.Entry, w: Int, h: Int): ByteArray? {
        cache.rendered(e.key, w, h)?.let { return it }
        val jpeg = cache.source(e.key)?.let { Backdrop.render(codec, it, w, h, upscale = true) } ?: return null
        cache.putRendered(e.key, w, h, jpeg)
        return jpeg
    }

    fun size(e: ArtCache.Entry): Size? {
        if (e.w > 0 && e.h > 0) return Size(e.w, e.h)
        val s = cache.source(e.key)?.let { codec.bounds(it) } ?: return null
        cache.putSize(e.key, e.at, s)
        return s
    }

    private fun tag(p: ArtPicks.Pick?, mbid: String?): String? = p?.takeIf { it.kind == ArtPicks.ARTIST }?.mbid ?: Names.mbid(mbid)

    private fun picked(p: ArtPicks.Pick, name: String, mbid: String?, w: Int, h: Int): Art? {
        val key = p.cacheKey
        val tag = Names.mbid(mbid)
        val e = cache.hit(key)
        if (e != null) render(e, w, h)?.let { return Art(it, w, h, name.trim(), tag, e.source, e.credit) }
        if (p.source == Sources.GALLERY) return gallery(p, name, tag, w, h)
        val now = clock()
        p.failedAt?.let { at ->
            if (now - at < TTL_MS) {
                due(at)
                return null
            }
        }
        if (!online()) throw IOException("offline")
        val src = api.get(p.url)?.body
        val jpeg = src?.takeIf { check(it) == null }?.let { Backdrop.render(codec, it, w, h, upscale = true) }
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
        val jpeg = src?.let { Backdrop.render(codec, it, w, h, upscale = true) }
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
        return lookup(name, name, mbid, w, h, on, mbid, AtomicBoolean())?.src
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

    private fun youTube(url: String): Offered = YouTube.page(api, url)?.let { html ->
        val img = YouTube.ogImage(html) ?: return Offered.Refused(PickLink.NOT_IMAGE)
        image(Sources.YOUTUBE, img, ImageIds.of(Sources.YOUTUBE, img), null, YouTube.ogTitle(html) ?: "")
    } ?: Offered.Refused(PickLink.UNREACHABLE)

    private fun youTubeMusic(l: PickLink.YouTubeMusic): Offered {
        val music = reached {
            YouTube.page(api, l.url)?.let { html ->
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
        val src = api.get(url)?.body ?: return Offered.Refused(PickLink.UNREACHABLE)
        if (source == Sources.DEEZER && deezer.placeholder(src)) return Offered.Refused(PickLink.NOT_IMAGE)
        check(src)?.let { return Offered.Refused(it) }
        return Offered.Ok(Offer(ArtPicks.IMAGE, source, url, id, credit, null, title, src, label))
    }

    fun browse(name: String, mbid: String?, px: Int, out: BrowseSink, exec: ExecutorService) {
        val s = sources()
        val on = s.enabled
        val order = s.order.filter { it in on }.flatMap { if (it == Sources.YOUTUBE) listOf(it, YOUTUBE_SEARCH) else listOf(it) }
        out.sections(order)
        val run = HttpRun.current.get()
        val feed = Feed(time.condition())
        val lock = Any()
        var left = order.size + 1
        val ended = time.condition()
        fun <T> attempt(block: () -> T): T? = try {
            block()
        } catch (e: IOException) {
            if (run?.cancelled == true) throw e
            null
        } catch (e: RuntimeException) {
            null
        }
        fun <T> keyed(block: () -> T): T? = try {
            block()
        } catch (e: HttpDenied) {
            throw e
        } catch (e: IOException) {
            if (run?.cancelled == true) throw e
            null
        } catch (e: RuntimeException) {
            null
        }
        fun finished(section: String?) {
            if (section != null) out.sectionDone(section) else feed.close()
            synchronized(lock) { left-- }
            ended.signalAll()
        }
        fun submit(section: String?, body: () -> Unit) = try {
            exec.execute {
                try {
                    if (run == null) body() else run.on(body)
                } catch (e: HttpDenied) {
                    browseLog("art browse denied $section")
                } catch (e: IOException) {
                } catch (e: InterruptedException) {
                } catch (e: Throwable) {
                    browseLog("art browse bug ${section ?: "musicbrainz"} $e")
                } finally {
                    finished(section)
                }
            }
        } catch (e: RejectedExecutionException) {
            finished(section)
        }
        submit(null) {
            val ids = ((attempt { mb.searchAll(name) } ?: emptyList()).take(BROWSE_MBIDS) + listOfNotNull(Names.mbid(mbid))).distinct()
            for (id in ids) attempt { mb.artist(id) }?.let { feed.add(it) }
        }
        fun caption(a: MbArtist) = a.disambiguation.ifEmpty { a.name }
        for (section in order) {
            submit(section) {
                val seen = HashSet<String>()
                fun tile(source: String, caption: String, preview: String, full: String, id: String = ImageIds.of(source, full), credit: Credit? = null) {
                    if (id in seen) return
                    out.permit()
                    val b = try {
                        attempt { api.get(preview) }?.body
                    } catch (e: Throwable) {
                        out.drop()
                        throw e
                    }
                    if (b == null) {
                        out.drop()
                        return
                    }
                    seen += id
                    out.tile(section, Tile(source, caption, b, full, id, credit))
                }
                fun each(f: (MbArtist) -> Unit) {
                    var i = 0
                    while (true) f(feed.at(i++, run) ?: return)
                }
                when (section) {
                    Wikimedia.SOURCE -> each { a -> attempt { wikimedia.candidates(a, PICK_W, PICK_H, px) }?.take(MAX_TRIES)?.forEach { tile(section, caption(a), it.url, it.id, it.id, it.credit) } }
                    Sources.THE_AUDIO_DB -> each { a -> attempt { audioDb.candidates(a.id) }?.take(MAX_TRIES)?.forEach { tile(section, caption(a), TheAudioDb.small(it.url), it.url) } }
                    Sources.FANART_TV -> each { a -> keyed { fanartTv.candidates(a.id) }?.take(MAX_TRIES)?.forEach { tile(section, caption(a), it.url, it.url) } }
                    Sources.DISCOGS -> each { a -> attempt { discogs.previews(a) }?.take(MAX_TRIES)?.forEach { (small, full) -> tile(section, caption(a), small, full) } }
                    Sources.YOUTUBE -> each { a ->
                        var n = 0
                        for (u in YouTube.channelRels(a)) {
                            if (n >= MAX_TRIES) break
                            val img = attempt { YouTube.page(api, u)?.let { YouTube.ogImage(it) } } ?: continue
                            n++
                            tile(Sources.YOUTUBE, caption(a), YouTube.sized(img, YT_PREVIEW), img)
                        }
                    }
                    Sources.DEEZER -> attempt { deezer.search(name) }?.take(BROWSE_RESULTS)?.forEach { d ->
                        val small = Deezer.picture(d, "picture_medium") ?: return@forEach
                        val full = Deezer.picture(d, "picture_xl") ?: return@forEach
                        tile(section, d["name"].str ?: "", small, full)
                    }
                    YOUTUBE_SEARCH -> {
                        val found = attempt { youtube.search(name) }?.takeIf { it.isNotEmpty() }
                        found?.take(BROWSE_RESULTS)?.forEach { c -> tile(Sources.YOUTUBE, c.title, YouTube.sized(c.thumb, YT_PREVIEW), YouTube.sized(c.thumb, YT_FULL)) }
                        out.youtubeSearch(found != null)
                    }
                }
            }
        }
        ended.await(run, null) { synchronized(lock) { left == 0 } }
        if (run?.cancelled == true) throw IOException("cancelled")
    }

    private class Feed(private val cond: Cond) {
        private val list = ArrayList<MbArtist>()
        private var closed = false

        fun add(a: MbArtist) {
            synchronized(this) { list += a }
            cond.signalAll()
        }

        fun close() {
            synchronized(this) { closed = true }
            cond.signalAll()
        }

        fun at(i: Int, run: HttpRun?): MbArtist? {
            cond.await(run, null) { synchronized(this) { list.size > i || closed } }
            return synchronized(this) { list.getOrNull(i) }
        }
    }

    private fun find(name: String, tag: String?, id: String?, key: String, w: Int, h: Int, on: Set<String>, now: Long, skipped: AtomicBoolean): Found? {
        lookup(name, name, id, w, h, on, key, skipped)?.let { return Found(it.c, it.url, it.src, it.size, it.jpeg, id, if (tag == null && id != null) name.trim() else "", it.base) }
        if (tag != null) return null
        for (v in Names.variants(name)) {
            val vid = resolve(v, now)
            val f = lookup(v, name, vid, w, h, on, key, skipped) ?: continue
            return Found(f.c, f.url, f.src, f.size, f.jpeg, vid, if (vid != null) v else "", f.base)
        }
        return null
    }

    private fun keep(name: String, tag: String?, key: String, f: Found, w: Int, h: Int, now: Long): Art {
        val e = ArtCache.Entry(key, name.trim(), f.mbid, f.c.source, f.c.credit, f.url, now, f.c.id, ArtCache.sha256(f.src), f.by, f.size.w, f.size.h, f.base)
        cache.putHit(e, f.src)
        cache.putRendered(key, w, h, f.jpeg)
        return Art(f.jpeg, w, h, name.trim(), f.mbid, f.c.source, f.c.credit, tag == null && wrongArtistOffered(e, name))
    }

    fun blocked(e: ArtCache.Entry): Boolean = !picked(e) && (blocks.blocked(e.key, e.source, ImageIds.of(e), cache.sha(e), e.name) || placeholder(e))

    private fun placeholder(e: ArtCache.Entry) = e.source == Sources.DEEZER && (cache.sha(e) in placeholders || e.base in placeholders)

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

    private fun lookup(name: String, display: String, id: String?, w: Int, h: Int, on: Set<String>, key: String, skipped: AtomicBoolean): Hit? {
        var error: IOException? = null
        fun <T> attempt(block: () -> T): T? = try {
            block()
        } catch (e: IOException) {
            error = error ?: e
            null
        }
        val t = targets()
        val a = id?.let { attempt { mb.artist(it) } }
        val steps = mapOf<String, (() -> Sequence<Candidate>)?>(
            Wikimedia.SOURCE to a?.let { { wikimedia.candidates(it, w, h, targets = t).asSequence() } },
            Sources.THE_AUDIO_DB to id?.takeIf { Sources.THE_AUDIO_DB in on }?.let { { audioDb.candidates(it).asSequence() } },
            Sources.FANART_TV to id?.takeIf { Sources.FANART_TV in on }?.let { { fanartTv.candidates(it).asSequence() } },
            Sources.DISCOGS to a?.takeIf { Sources.DISCOGS in on }?.let { { discogs.candidates(it).asSequence() } },
            Sources.YOUTUBE to a?.takeIf { Sources.YOUTUBE in on }?.let { { youtube.candidates(it) } },
            Sources.DEEZER to if (Sources.DEEZER in on) ({ deezer.candidates(name).asSequence() }) else null,
        )
        for ((source, step) in sources().order.mapNotNull { o -> steps[o]?.let { o to it } }) {
            val candidates = attempt { ranked(source, step().filterNot { blocks.blocked(key, it.source, it.id, name = display) }.take(MAX_TRIES), t).iterator() } ?: continue
            while (attempt { candidates.hasNext() } == true) {
                val c = candidates.next()
                var (url, src) = attempt { fetch(c, t.side) } ?: continue
                var base = ""
                if (c.source == Sources.DEEZER) {
                    if (deezer.placeholder(src)) {
                        skipped.set(true)
                        continue
                    }
                    val big = larger(c, src, t.side)
                    if (big != null && deezer.placeholder(big.second)) {
                        skipped.set(true)
                        continue
                    }
                    if (big != null) {
                        base = ArtCache.sha256(src)
                        url = big.first
                        src = big.second
                    }
                }
                if (blocks.blocked(key, c.source, "", ArtCache.sha256(src), display)) continue
                val size = codec.bounds(src)?.takeIf { Backdrop.fits(it, w, h) } ?: continue
                val jpeg = Backdrop.render(codec, src, w, h) ?: continue
                return Hit(c, url, src, size, jpeg, base)
            }
        }
        error?.let { throw it }
        return null
    }

    private fun ranked(source: String, c: Sequence<Candidate>, t: Targets): Sequence<Candidate> {
        if (t.sizes.isEmpty() || source !in SIZED) return c
        val list = c.toList()
        if (list.any { it.w <= 0 || it.h <= 0 }) return list.asSequence()
        return list.sortedWith(compareBy<Candidate> { it.group }.thenByDescending { t.score(it.w, it.h) }).asSequence()
    }

    private fun fetch(c: Candidate, side: Int): Pair<String, ByteArray>? {
        if (side > YT_FULL && c.source == Sources.YOUTUBE && YouTube.resizable(c.url)) {
            val orig = YouTube.sized(c.url, 0)
            val s0 = optional { api.get(orig) }?.body
            val b = s0?.let { codec.bounds(it) }
            if (s0 != null && b != null) {
                if (maxOf(b.w, b.h) <= side || s0.size <= KEEP_BYTES) return orig to s0
                val at = YouTube.sized(c.url, side)
                return optional { api.get(at) }?.body?.let { at to it } ?: (orig to s0)
            }
        }
        api.get(c.url)?.body?.let { return c.url to it }
        val alt = c.alt ?: return null
        return api.get(alt)?.body?.let { alt to it }
    }

    private fun larger(c: Candidate, src: ByteArray, side: Int): Pair<String, ByteArray>? {
        if (side <= Deezer.XL || !Deezer.resizable(c.url)) return null
        val at = Deezer.sized(c.url, maxOf(side, Deezer.REAL))
        val b = optional { api.get(at) }?.body ?: return null
        val big = codec.bounds(b) ?: return null
        val base = codec.bounds(src) ?: return null
        return if (minOf(big.w, big.h) >= Deezer.REAL && big.w > base.w && big.h > base.h) at to b else null
    }

    private inline fun <T> optional(get: () -> T): T? = try {
        get()
    } catch (e: IOException) {
        if (HttpRun.current.get()?.cancelled == true) throw e
        null
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
        const val KEEP_BYTES = 4 shl 20
        const val YOUTUBE_SEARCH = "youtube search"
        val BROWSE_WORKERS = Sources.ORDER.size + 2
        private val SIZED = setOf(Sources.DISCOGS, Wikimedia.SOURCE)
    }
}
