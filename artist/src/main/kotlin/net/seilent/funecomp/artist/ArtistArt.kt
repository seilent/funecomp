package net.seilent.funecomp.artist

import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32

class Art(val jpeg: ByteArray, val w: Int, val h: Int, val name: String, val mbid: String?, val source: String, val credit: Credit?, val wrongArtist: Boolean = false, val key: String? = null)

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
    private val youtube = YouTube(api, log)
    private val deezer = Deezer(api, placeholders)
    private var scanned = false

    class Mark(val sources: String, val until: Long, val pending: Boolean = false)

    @Volatile private var missDue = Long.MAX_VALUE
    @Volatile private var pending = false
    private val pruneSafe = picks.skipped == 0 && (picks.read || gallery?.any() != true)
    private val missingLogged = HashSet<String>()

    private class Found(val c: Candidate, val url: String, val src: ByteArray, val size: Size, val jpeg: ByteArray, val mbid: String?, val by: String, val base: String, val bare: Boolean = false, val channel: String = "")

    private class Pass(val channel: Boolean) {
        val skipped = AtomicBoolean(false)
        var answered: Boolean? = null
    }

    private class Hit(val c: Candidate, val url: String, val src: ByteArray, val size: Size, val jpeg: ByteArray, val base: String = "")

    @Throws(IOException::class)
    fun get(name: String, mbid: String?, w: Int, h: Int, search: Boolean = true): Art? {
        val p = picks.of(mbid, name)
        when (p?.kind) {
            ArtPicks.IMAGE -> return picked(p, name, mbid, w, h)
            ArtPicks.NONE -> return null
        }
        val on = sources().enabled
        if (on.isEmpty()) return null
        val now = clock()
        val tag = tag(p, mbid)
        val id = tag ?: identify(name, now)
        val key = id ?: "name:${Names.fold(name)}"
        val hit = cache.hit(key)
        if (hit != null && hit.source !in on && !blocked(hit)) return null
        val kept = if (hit != null && blocked(hit)) {
            cache.drop(key)
            false
        } else if (hit != null) {
            val wrong = tag == null && wrongArtistOffered(hit, name)
            render(hit, w, h)?.let { return Art(it, w, h, hit.name, hit.mbid, hit.source, hit.credit, wrong, key) }
            if (now - hit.failed < TTL_MS) {
                due(hit.failed)
                return null
            }
            true
        } else {
            false
        }
        val keyHash = keyHash(on)
        val step = tag == null && id == null && !kept && Sources.YOUTUBE in on
        val m = cache.miss(key)
        val searchDue = step && searchDue(m, now)
        if (searchDue && !search) pending = true
        if (m != null && now - m.at < TTL_MS && m.sources.containsAll(on) && m.keyHash == keyHash && m.cleaned && !(searchDue && search)) {
            due(m.at)
            return null
        }
        if (!online()) throw IOException("offline")
        val pass = Pass(searchDue && search)
        val found = find(name, tag, id, key, w, h, on, now, pass)
        if (found == null) {
            if (!kept) {
                when (pass.answered) {
                    true -> cache.putMiss(key, now, on, keyHash, now)
                    false -> cache.putMiss(key, now, on, keyHash, m?.yt, now)
                    null -> cache.putMiss(key, now, on, keyHash, m?.yt, m?.ytFailed)
                }
                if (pass.skipped.get() && blocks.drop(key, id, name.trim(), now)) blocks.save()
            } else if (hit != null) {
                cache.putFailed(key, hit.at, now)
            }
            due(now)
            return null
        }
        return keep(name, tag, key, found, w, h, now)
    }

    @Throws(IOException::class)
    fun refresh(name: String, mbid: String?, w: Int, h: Int, search: Boolean = true): Art? {
        val p = picks.of(mbid, name)
        if (p != null && p.kind != ArtPicks.ARTIST) return get(name, mbid, w, h, search)
        val on = sources().enabled
        if (on.isEmpty()) return null
        val now = clock()
        val tag = tag(p, mbid)
        val id = tag ?: identify(name, now)
        val key = id ?: "name:${Names.fold(name)}"
        if (cache.hit(key)?.let { it.source !in on && !blocked(it) } == true) return null
        val found = try {
            if (online()) find(name, tag, id, key, w, h, on, now, Pass(false)) else null
        } catch (e: IOException) {
            null
        }
        return if (found == null) get(name, mbid, w, h, search) else keep(name, tag, key, found, w, h, now)
    }

    private fun searchDue(m: ArtCache.Miss?, now: Long): Boolean {
        val failed = m?.ytFailed
        val ok = m?.yt
        return when {
            failed != null && (ok == null || failed > ok) -> now - failed >= TTL_MS
            ok != null -> now - ok >= YT_TTL_MS
            else -> true
        }
    }

    private fun identify(name: String, now: Long): String? = if (YouTube.channelId(name) != null) null else resolve(name, now)

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
        if (e != null) render(e, w, h)?.let { return Art(it, w, h, name.trim(), tag, e.source, e.credit, key = key) }
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
        return Art(jpeg, w, h, name.trim(), tag, p.source, p.credit, key = key)
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
        return Art(jpeg, w, h, name.trim(), tag, p.source, p.credit, key = p.cacheKey)
    }

    @Throws(IOException::class)
    fun previewArtist(name: String, mbid: String, w: Int, h: Int): ByteArray? {
        val on = sources().enabled
        if (on.isEmpty()) return null
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
            is PickLink.MusicBrainz -> if (!sources().anyOn) Offered.Ok(Offer(ArtPicks.ARTIST, Sources.MUSICBRAINZ, "https://musicbrainz.org/artist/${l.mbid}", l.mbid, null, l.mbid, l.mbid, null)) else mb.artist(l.mbid)?.let { a -> Offered.Ok(Offer(ArtPicks.ARTIST, Sources.MUSICBRAINZ, "https://musicbrainz.org/artist/${a.id}", a.id, null, a.id, a.name, null)) }
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
        if (on.isEmpty()) return
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
                        val first = attempt { youtube.search(name) } ?: YouTube.Search.Unavailable()
                        fun alias(): YouTube.Search? {
                            val id = Names.mbid(mbid) ?: feed.at(0, run)?.id ?: return null
                            val known = attempt { mb.aliases(id) } ?: return null
                            val alias = Aliases.native(known.name, known.aliases, name) ?: return null
                            val r = attempt { youtube.search(alias) } ?: YouTube.Search.Unavailable()
                            browseLog("art browse youtube search alias $alias ${if (r is YouTube.Search.Channels) "channels=${r.list.size}" else if (r == YouTube.Search.NoResults) "none" else "unavailable"}")
                            return r
                        }
                        val found = if ((first as? YouTube.Search.Unavailable)?.gated == true) alias() ?: first else first
                        (found as? YouTube.Search.Channels)?.list?.take(BROWSE_RESULTS)?.forEach { c -> tile(Sources.YOUTUBE, c.title, YouTube.sized(c.thumb, YT_PREVIEW), YouTube.sized(c.thumb, YT_FULL)) }
                        out.search(found)
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

    private fun find(name: String, tag: String?, id: String?, key: String, w: Int, h: Int, on: Set<String>, now: Long, pass: Pass): Found? {
        val channelTag = YouTube.channelId(name).takeIf { tag == null }
        if (channelTag == null) {
            lookup(name, name, id, w, h, on, key, pass.skipped)?.let { return Found(it.c, it.url, it.src, it.size, it.jpeg, id, if (tag == null && id != null) name.trim() else "", it.base) }
            if (tag != null) return null
            val bare = Names.bareOf(name)
            for (v in Names.variants(name)) {
                val isBare = v == bare
                val vid = if (isBare) resolveBare(v, name, now) ?: continue else resolve(v, now)
                val f = lookup(v, name, vid, w, h, on, key, pass.skipped) ?: continue
                return Found(f.c, f.url, f.src, f.size, f.jpeg, vid, if (isBare) name.trim() else if (vid != null) v else "", f.base, bare = isBare)
            }
        }
        if (!pass.channel) return null
        return channel(name, channelTag, key, w, h, on, pass)
    }

    private fun channel(name: String, channelTag: String?, key: String, w: Int, h: Int, on: Set<String>, pass: Pass): Found? {
        val skip = blocks.rejectedChannels(name)
        val m = if (channelTag != null) {
            if (channelTag in skip) {
                pass.answered = true
                return null
            }
            val url = YouTube.channelUrl(channelTag)
            val links = mb.byUrl(url)
            val img = YouTube.page(api, url)?.let { YouTube.ogImage(it) }
            pass.answered = img != null
            ChannelMatch.Match(YouTube.Channel(channelTag, name.trim(), img ?: ""), ChannelMatch.mbid(name, links)).takeIf { it.mbid != null || img != null } ?: return null
        } else {
            when (val r = youtube.search(name)) {
                is YouTube.Search.Unavailable -> {
                    log("art youtube search unavailable ${name.trim()}")
                    pass.answered = false
                    return null
                }
                YouTube.Search.NoResults -> {
                    pass.answered = true
                    return null
                }
                is YouTube.Search.Channels -> {
                    pass.answered = true
                    val e = ChannelMatch.equal(name, r.list.filter { it.id !in skip }) ?: return null
                    val links = ChannelMatch.lookups(e).associate { it.id to mb.byUrl(YouTube.channelUrl(it.id)) }
                    ChannelMatch.decide(name, e, links) ?: return null
                }
            }
        }
        val f = if (m.mbid != null) lookup(name, name, m.mbid, w, h, on, key, pass.skipped) else avatar(m.channel, name, key, w, h)
        f ?: return null
        return Found(f.c, f.url, f.src, f.size, f.jpeg, m.mbid, name.trim(), f.base, channel = m.channel.id)
    }

    private fun avatar(ch: YouTube.Channel, display: String, key: String, w: Int, h: Int): Hit? {
        val c = Candidate(if (YouTube.resizable(ch.thumb)) YouTube.sized(ch.thumb, YT_FULL) else ch.thumb, Sources.YOUTUBE)
        if (c.url.isEmpty() || blocks.blocked(key, c.source, c.id, name = display)) return null
        val (url, src) = fetch(c, targets().side) ?: return null
        if (blocks.blocked(key, c.source, "", ArtCache.sha256(src), display)) return null
        val size = codec.bounds(src)?.takeIf { Backdrop.fits(it, w, h) } ?: return null
        val jpeg = Backdrop.render(codec, src, w, h) ?: return null
        return Hit(c, url, src, size, jpeg)
    }

    private fun keep(name: String, tag: String?, key: String, f: Found, w: Int, h: Int, now: Long): Art {
        val e = ArtCache.Entry(key, name.trim(), f.mbid, f.c.source, f.c.credit, f.url, now, f.c.id, ArtCache.sha256(f.src), f.by, f.size.w, f.size.h, f.base, bare = f.bare, channel = f.channel)
        cache.putHit(e, f.src)
        cache.putRendered(key, w, h, f.jpeg)
        return Art(f.jpeg, w, h, name.trim(), f.mbid, f.c.source, f.c.credit, tag == null && wrongArtistOffered(e, name), key)
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

    fun hitAt(key: String): Long? = cache.hit(key)?.at

    fun known(name: String, mbid: String?): Boolean = keyOf(name, mbid).let { cache.hit(it) != null || cache.miss(it) != null }

    fun forget(key: String) = cache.drop(key)

    fun forget(e: ArtCache.Entry) = cache.drop(e.key, e.at)

    fun clearSource(source: String, seen: (ArtCache.Entry, Set<Long>) -> Unit = { _, _ -> }): List<ArtCache.Entry> = cache.clearSource(source, seen)

    fun clearAll(seen: (ArtCache.Entry, Set<Long>) -> Unit = { _, _ -> }): List<ArtCache.Entry> = cache.clearAll(seen)

    fun usage(): Map<String?, ArtCache.Usage> = cache.usage()

    fun resolvedBy(e: ArtCache.Entry): String =
        e.resolvedBy.ifEmpty { e.name.takeIf { e.mbid != null && cache.mbid(it)?.mbid == e.mbid } ?: "" }

    fun picked(e: ArtCache.Entry): Boolean = e.key.startsWith(ArtPicks.PREFIX)

    fun wrongArtistOffered(e: ArtCache.Entry): Boolean {
        if (picked(e)) return false
        if (e.channel.isNotEmpty()) return true
        val by = resolvedBy(e)
        if (by.isEmpty() || e.mbid == null) return false
        if (e.bare) return cache.bare(by)?.mbid == e.mbid
        return cache.mbid(by)?.mbid == e.mbid
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

    fun pushCrc(e: ArtCache.Entry): Long? = cache.full(e.key)?.crc

    val full: FullSource = FullSource(cache, codec, log = log)

    fun forgetName(name: String) = cache.forgetName(name)

    fun withSha(e: ArtCache.Entry): ArtCache.Entry =
        if (e.sha.isNotEmpty()) e else ArtCache.Entry(e.key, e.name, e.mbid, e.source, e.credit, e.url, e.at, e.id, cache.sha(e), e.resolvedBy, bare = e.bare, channel = e.channel)

    fun keyOf(name: String, mbid: String?): String = (tag(picks.of(mbid, name), mbid) ?: cache.mbid(name)?.mbid) ?: "name:${Names.fold(name)}"

    fun beginMark() {
        missDue = Long.MAX_VALUE
        pending = false
    }

    fun mark() = Mark(signature(), missDue, pending)

    fun holds(m: Mark, searchAllowed: Boolean = false) = m.sources == signature() && clock() < m.until && !(m.pending && searchAllowed)

    private fun signature(): String {
        val on = sources().enabled
        return on.sorted().joinToString(",") + " " + keyHash(on) + " " + targets().key
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
        return cache.hit(key)?.takeIf { cache.hasSource(key) && !blocked(it) }
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

    private fun resolveBare(name: String, tag: String, now: Long): String? {
        val skip = blocks.rejected(tag)
        cache.bare(tag)?.let { l ->
            if (l.mbid != null && l.mbid !in skip) return l.mbid
            if (l.mbid == null && now - l.at < TTL_MS) {
                due(l.at)
                return null
            }
        }
        if (!online()) throw IOException("offline")
        val id = mb.unique(name, skip)
        cache.putBare(tag, id, now)
        return id
    }

    @Throws(IOException::class)
    fun scanBare(): Int? {
        if (!sources().anyOn) return null
        var n = 0
        for (e in cache.entries()) {
            if (picked(e) || e.bare || e.mbid == null) continue
            val b = Names.bareOf(e.name) ?: continue
            if (Names.fold(e.resolvedBy) != Names.fold(b) || picks.of(null, e.name) != null || picks.of(e.mbid, e.name) != null) continue
            val now = clock()
            val skip = blocks.rejected(e.name)
            val known = cache.bare(e.name)
            val id = if (known != null) {
                known.mbid?.takeIf { it !in skip }
            } else {
                if (!online()) throw IOException("offline")
                mb.unique(b, skip).also { cache.putBare(e.name, it, now) }
            }
            if (id == e.mbid) continue
            cache.drop(e.key)
            blocks.drop(e.key, e.mbid, e.name, now)
            n++
        }
        if (n > 0) blocks.save()
        return n
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
        val steps = steps(name, id, a, w, h, on, t)
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

    private fun steps(name: String, id: String?, a: MbArtist?, w: Int, h: Int, on: Set<String>, t: Targets) = mapOf<String, (() -> Sequence<Candidate>)?>(
        Wikimedia.SOURCE to a?.takeIf { Wikimedia.SOURCE in on }?.let { { wikimedia.candidates(it, w, h, targets = t).asSequence() } },
        Sources.THE_AUDIO_DB to id?.takeIf { Sources.THE_AUDIO_DB in on }?.let { { audioDb.candidates(it).asSequence() } },
        Sources.FANART_TV to id?.takeIf { Sources.FANART_TV in on }?.let { { fanartTv.candidates(it).asSequence() } },
        Sources.DISCOGS to a?.takeIf { Sources.DISCOGS in on }?.let { { discogs.candidates(it).asSequence() } },
        Sources.YOUTUBE to a?.takeIf { Sources.YOUTUBE in on }?.let { { youtube.candidates(it) } },
        Sources.DEEZER to if (Sources.DEEZER in on) ({ deezer.candidates(name).asSequence() }) else null,
    )

    fun upgradable(e: ArtCache.Entry): Boolean {
        val t = targets()
        if (t.sizes.isEmpty() || picked(e) || e.source !in sources().enabled || done(e.sized, t)) return false
        val s = size(e) ?: return false
        return t.score(s.w, s.h) < 1.0 && !blocked(e)
    }

    fun upgrades(): List<ArtCache.Entry> = cache.entries().filter { upgradable(it) }

    @Throws(IOException::class)
    fun upgrade(e: ArtCache.Entry, w: Int, h: Int): Upgrade {
        if (!upgradable(e)) return Upgrade.SKIPPED
        if (!online()) throw IOException("offline")
        val t = targets()
        val have = size(e) ?: return Upgrade.SKIPPED
        var best: Hit? = null
        var top = t.score(have.w, have.h)
        var bytes = 0L
        fun offer(c: Candidate, url: String, src: ByteArray) {
            bytes += src.size
            if (blocks.blocked(e.key, c.source, "", ArtCache.sha256(src), e.name)) return
            if (c.source == Sources.DEEZER && deezer.placeholder(src)) return
            val s = codec.bounds(src)?.takeIf { Backdrop.fits(it, w, h) } ?: return
            val score = t.score(s.w, s.h)
            if (score <= top || (c.id != e.id && score < 1.0)) return
            val jpeg = Backdrop.render(codec, src, w, h) ?: return
            best = Hit(c, url, src, s, jpeg)
            top = score
        }
        same(e, t)?.let { (c, url, src) -> offer(c, url, src) }
        if (top < 1.0 && e.source in SIZED) {
            val a = e.mbid?.let { mb.artist(it) }
            val step = steps(e.name, e.mbid, a, w, h, setOf(e.source), t)[e.source]
            val others = step?.invoke()?.filter { it.id != e.id && !blocks.blocked(e.key, it.source, it.id, name = e.name) }?.take(MAX_TRIES)
            var tries = 0
            for (c in ranked(e.source, others ?: emptySequence(), t)) {
                if (top >= 1.0 || tries >= MAX_TRIES - 1) break
                if (c.w > 0 && c.h > 0 && t.score(c.w, c.h) < 1.0) continue
                tries++
                val (url, src) = optional { fetch(c, t.side) } ?: continue
                offer(c, url, src)
            }
        }
        val b = best
        if (b == null) {
            cache.putSized(e.key, e.at, sizedKey(t))
            return Upgrade(false, bytes)
        }
        val xl = if (b.c.source == Sources.DEEZER && b.c.id == e.id) e.base.ifEmpty { cache.sha(e) } else ""
        val n = ArtCache.Entry(e.key, e.name, e.mbid, b.c.source, b.c.credit ?: e.credit.takeIf { b.c.id == e.id }, b.url, clock(), b.c.id, ArtCache.sha256(b.src), e.resolvedBy, b.size.w, b.size.h, xl, bare = e.bare, channel = e.channel)
        if (!cache.replaceHit(e.at, n, b.src)) return Upgrade(false, bytes)
        cache.putRendered(e.key, w, h, b.jpeg)
        return Upgrade(true, bytes, n.at)
    }

    class Upgrade(val replaced: Boolean, val bytes: Long, val at: Long = 0) {
        companion object {
            val SKIPPED = Upgrade(false, 0)
        }
    }

    private fun same(e: ArtCache.Entry, t: Targets): Triple<Candidate, String, ByteArray>? {
        val c = Candidate(e.url, e.source, e.credit, e.id)
        return when {
            e.source == Sources.YOUTUBE && YouTube.resizable(e.url) && !e.url.contains("=s0") -> optional { fetch(c, t.side) }?.takeIf { it.first != e.url }?.let { Triple(c, it.first, it.second) }
            e.source == Sources.DEEZER -> {
                val src = cache.source(e.key) ?: return null
                larger(c, src, t.side)?.let { Triple(c, it.first, it.second) }
            }
            e.source == Wikimedia.SOURCE && e.id.startsWith("File:") -> {
                val f = optional { wikimedia.file(e.id.removePrefix("File:"), t) } ?: return null
                if (f.url == e.url) return null
                optional { fetch(f, t.side) }?.let { Triple(f, it.first, it.second) }
            }
            else -> null
        }
    }

    private fun sizedKey(t: Targets) = "$SIZED_RULES ${t.phone ?: Size(0, 0)} ${t.device}"

    private fun done(sized: String, t: Targets): Boolean {
        val p = sized.split(' ')
        if (p.size != 3 || p[0] != SIZED_RULES.toString()) return false
        val phone = Size.parse(p[1]) ?: Size(0, 0)
        val dev = Size.parse(p[2]) ?: Size(0, 0)
        val want = t.phone ?: Size(0, 0)
        return phone.w >= want.w && phone.h >= want.h && dev.w >= t.device.w && dev.h >= t.device.h
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
        const val YT_TTL_MS = 30L * 24 * 3600 * 1000
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
        const val SIZED_RULES = 1
    }
}
