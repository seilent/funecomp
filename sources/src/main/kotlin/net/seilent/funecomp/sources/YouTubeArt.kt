package net.seilent.funecomp.sources

import net.seilent.funekit.artist.Aliases
import net.seilent.funekit.artist.ArtExtension
import net.seilent.funekit.artist.ArtistArt
import net.seilent.funekit.artist.BrowseFeed.Search
import net.seilent.funekit.artist.Candidate
import net.seilent.funekit.artist.HostLimit
import net.seilent.funekit.artist.HttpRun
import net.seilent.funekit.artist.MbArtist
import net.seilent.funekit.artist.Names
import net.seilent.funekit.artist.Offered
import net.seilent.funekit.artist.PickLink
import net.seilent.funekit.artist.Sources
import java.io.IOException
import java.net.URI

class YouTubeArt(private val log: (String) -> Unit) : ArtExtension {
    data class Channel(val url: String) : PickLink.Link

    data class Music(val url: String, val channel: String) : PickLink.Link

    private val asked = ThreadLocal<Long>()

    override val source = SOURCE

    override val sections = listOf(SOURCE, SEARCH)

    override val limits = listOf(
        HostLimit(SOURCE, INTERVAL_MS) { it == "www.youtube.com" || it == "music.youtube.com" },
        HostLimit(IMAGES, INTERVAL_MS) { it == "yt3.ggpht.com" || it == "yt3.googleusercontent.com" || it == "i.ytimg.com" },
    )

    override fun identify(name: String): Boolean = YouTube.channelId(name) == null

    override fun link(uri: URI, host: String): PickLink.Link? {
        val path = uri.rawPath ?: ""
        return when {
            host in HOSTS -> CHANNEL.find(path)?.let { Channel("https://www.youtube.com/" + it.groupValues[1]) }
            host == "music.youtube.com" -> MUSIC.find(path)?.let { m ->
                val p = m.groupValues[1].replaceFirst("browse/", "channel/")
                Music("https://music.youtube.com/$p", "https://www.youtube.com/$p")
            }
            else -> null
        }
    }

    override fun offer(link: PickLink.Link, host: ArtExtension.Host): Offered? = when (link) {
        is Channel -> channel(link.url, host)
        is Music -> music(link, host)
        else -> null
    }

    private fun channel(url: String, host: ArtExtension.Host): Offered = YouTube.page(host.api, url)?.let { html ->
        val img = YouTube.ogImage(html) ?: return Offered.Refused(PickLink.NOT_IMAGE)
        host.image(SOURCE, img, imageId(img), null, YouTube.ogTitle(html) ?: "")
    } ?: Offered.Refused(PickLink.UNREACHABLE)

    private fun music(l: Music, host: ArtExtension.Host): Offered {
        val music = reached {
            YouTube.page(host.api, l.url)?.let { html ->
                YouTube.ogImage(html)?.let { YouTube.full(it) }?.let { img ->
                    host.image(SOURCE, img, imageId(img), null, YouTube.ogTitle(html) ?: "", YouTube.MUSIC_LABEL)
                } ?: Offered.Refused(PickLink.NOT_FOUND)
            } ?: Offered.Refused(PickLink.UNREACHABLE)
        }
        if ((music as? Offered.Refused)?.reason == PickLink.NOT_FOUND) return music
        val www = reached { channel(l.channel, host) }
        val ok = listOf(music, www).filterIsInstance<Offered.Ok>().flatMap { it.offers }.distinctBy { it.id }
        return if (ok.isEmpty()) music else Offered.Ok(ok)
    }

    private inline fun reached(get: () -> Offered): Offered = try {
        get()
    } catch (e: IOException) {
        if (HttpRun.current()?.cancelled == true) throw e
        Offered.Refused(PickLink.UNREACHABLE)
    }

    override fun browse(section: String, b: ArtExtension.Browse) {
        val api = b.host.api
        when (section) {
            SOURCE -> b.each { a ->
                var n = 0
                for (u in YouTube.channelRels(a)) {
                    if (n >= ArtistArt.MAX_TRIES) break
                    val img = b.attempt { YouTube.page(api, u)?.let { YouTube.ogImage(it) } } ?: continue
                    n++
                    b.tile(SOURCE, b.caption(a), YouTube.sized(img, PREVIEW), img, imageId(img))
                }
            }
            SEARCH -> {
                val youtube = YouTube(api, log)
                val first = b.attempt { youtube.search(b.name) } ?: Search.Unavailable()
                fun alias(): Search? {
                    val id = Names.mbid(b.mbid) ?: b.first()?.id ?: return null
                    val known = b.attempt { b.host.mb.aliases(id) } ?: return null
                    val alias = Aliases.native(known.name, known.aliases, b.name) ?: return null
                    val r = b.attempt { youtube.search(alias) } ?: Search.Unavailable()
                    b.log("art browse youtube search alias $alias ${if (r is Search.Matches) "channels=${r.list.size}" else if (r == Search.NoResults) "none" else "unavailable"}")
                    return r
                }
                val found = if ((first as? Search.Unavailable)?.gated == true) alias() ?: first else first
                (found as? Search.Matches)?.list?.filterIsInstance<YouTube.Channel>()?.take(ArtistArt.BROWSE_RESULTS)?.forEach { c ->
                    val full = YouTube.sized(c.thumb, FULL)
                    b.tile(SOURCE, c.title, YouTube.sized(c.thumb, PREVIEW), full, imageId(full))
                }
                b.search(found)
            }
        }
    }

    override fun due(kept: Map<String, String>, now: Long): Boolean {
        asked.set(now)
        val failed = kept[FAILED]?.toLongOrNull()
        val ok = kept[OK]?.toLongOrNull()
        return when {
            failed != null && (ok == null || failed > ok) -> now - failed >= ArtistArt.TTL_MS
            ok != null -> now - ok >= TTL_MS
            else -> true
        }
    }

    override fun resolve(name: String, skip: Set<String>, kept: MutableMap<String, String>, host: ArtExtension.Host): ArtExtension.Resolved? {
        val tag = YouTube.channelId(name)
        val m = if (tag != null) {
            if (tag in skip) {
                answered(kept, true)
                return null
            }
            val url = YouTube.channelUrl(tag)
            val links = host.mb.byUrl(url)
            val img = YouTube.page(host.api, url)?.let { YouTube.ogImage(it) }
            answered(kept, img != null)
            ChannelMatch.Match(YouTube.Channel(tag, name.trim(), img ?: ""), ChannelMatch.mbid(name, links)).takeIf { it.mbid != null || img != null } ?: return null
        } else {
            when (val r = YouTube(host.api, log).search(name)) {
                is Search.Unavailable -> {
                    log("art youtube search unavailable ${name.trim()}")
                    answered(kept, false)
                    return null
                }
                Search.NoResults -> {
                    answered(kept, true)
                    return null
                }
                is Search.Matches -> {
                    answered(kept, true)
                    val e = ChannelMatch.equal(name, r.list.filterIsInstance<YouTube.Channel>().filter { it.id !in skip }) ?: return null
                    val links = ChannelMatch.lookups(e).associate { it.id to host.mb.byUrl(YouTube.channelUrl(it.id)) }
                    ChannelMatch.decide(name, e, links) ?: return null
                }
            }
        }
        return ArtExtension.Resolved(m.channel.id, m.mbid, avatar(m.channel))
    }

    private fun answered(kept: MutableMap<String, String>, ok: Boolean) {
        val at = (asked.get() ?: System.currentTimeMillis()).toString()
        if (ok) {
            kept[OK] = at
            kept.remove(FAILED)
        } else {
            kept[FAILED] = at
        }
    }

    private fun avatar(ch: YouTube.Channel): Candidate {
        val url = if (YouTube.resizable(ch.thumb)) YouTube.sized(ch.thumb, FULL) else ch.thumb
        return Candidate(url, SOURCE, id = imageId(url))
    }

    override fun candidates(id: String?, artist: MbArtist?, host: ArtExtension.Host): Sequence<Candidate> =
        artist?.let { YouTube(host.api, log).candidates(it) } ?: emptySequence()

    override fun resize(c: Candidate, side: Int, host: ArtExtension.Host): Pair<String, ByteArray>? {
        if (side <= FULL || !YouTube.resizable(c.url) || c.url.contains("=s0")) return null
        val orig = YouTube.sized(c.url, 0)
        val s0 = optional { host.api.get(orig) }?.body ?: return null
        val b = host.codec.bounds(s0) ?: return null
        if (maxOf(b.w, b.h) <= side || s0.size <= KEEP_BYTES) return orig to s0
        val at = YouTube.sized(c.url, side)
        return optional { host.api.get(at) }?.body?.let { at to it } ?: (orig to s0)
    }

    override fun imageId(url: String): String = url.substringBefore('=')

    private inline fun <T> optional(get: () -> T): T? = try {
        get()
    } catch (e: IOException) {
        if (HttpRun.current()?.cancelled == true) throw e
        null
    }

    companion object {
        const val SOURCE = "youtube"
        const val SEARCH = "youtube search"
        const val PREVIEW = 176
        const val FULL = 900
        const val KEEP_BYTES = 4 shl 20
        const val TTL_MS = 30L * 24 * 3600 * 1000
        private const val IMAGES = "youtubeImages"
        private const val INTERVAL_MS = 1000L
        private const val OK = "yt"
        private const val FAILED = "ytFailed"
        private val HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com")
        private val CHANNEL = Regex("^/(@[^/?#]+|channel/UC[0-9A-Za-z_-]{22}|user/[^/?#]+|c/[^/?#]+)(/.*)?$")
        private val MUSIC = Regex("^/(channel/UC[0-9A-Za-z_-]{22}|browse/UC[0-9A-Za-z_-]{22}|@[^/?#]+)(/.*)?$")

        fun on(s: Sources): Boolean = s.plugins[SOURCE] == true

        fun with(s: Sources, on: Boolean): Sources = s.copy(plugins = s.plugins + (SOURCE to on))
    }
}
