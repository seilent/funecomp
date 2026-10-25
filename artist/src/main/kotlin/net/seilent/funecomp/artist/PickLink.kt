package net.seilent.funecomp.artist

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

class Offer(
    val kind: String,
    val source: String,
    val url: String,
    val id: String,
    val credit: Credit?,
    val mbid: String?,
    val title: String,
    val src: ByteArray?,
    val label: String = Sources.label(source),
)

class Tile(val source: String, val caption: String, val preview: ByteArray, val full: String, val id: String, val credit: Credit?)

sealed interface Offered {
    class Ok(val offers: List<Offer>) : Offered {
        constructor(offer: Offer) : this(listOf(offer))
    }
    class Refused(val reason: String) : Offered
}

object PickLink {
    sealed interface Link
    data class YouTube(val url: String) : Link
    data class YouTubeMusic(val url: String, val www: String) : Link
    data class MusicBrainz(val mbid: String) : Link
    data class Commons(val file: String) : Link
    data class Direct(val url: String) : Link
    data object Unsupported : Link

    const val UNSUPPORTED = "unsupported link"
    const val UNREACHABLE = "link not reachable"
    const val NOT_IMAGE = "not an image"
    const val TOO_SMALL = "image too small"
    const val NOT_FOUND = "artist not found"

    private val YOUTUBE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com")
    private val MB_HOSTS = setOf("musicbrainz.org", "beta.musicbrainz.org")
    private val PAGES = setOf("youtu.be", "open.spotify.com", "spotify.com", "www.spotify.com", "x.com", "www.x.com", "twitter.com", "www.twitter.com", "mobile.twitter.com")
    private val CHANNEL = Regex("^/(@[^/?#]+|channel/UC[0-9A-Za-z_-]{22}|user/[^/?#]+|c/[^/?#]+)(/.*)?$")
    private val MUSIC = Regex("^/(channel/UC[0-9A-Za-z_-]{22}|browse/UC[0-9A-Za-z_-]{22}|@[^/?#]+)(/.*)?$")
    private val ARTIST = Regex("^/artist/([0-9A-Fa-f-]{36})(/.*)?$")

    fun isLink(text: String): Boolean {
        val lower = text.trim().lowercase(Locale.ROOT)
        return lower.startsWith("https://") || lower.startsWith("http://")
    }

    fun parse(text: String): Link {
        if (!isLink(text)) return Unsupported
        val t = text.trim()
        val url = if (t.lowercase(Locale.ROOT).startsWith("http://")) "https://" + t.substring("http://".length) else t
        val u = try {
            URI(url)
        } catch (e: URISyntaxException) {
            return Unsupported
        }
        val host = u.host?.lowercase(Locale.ROOT) ?: return Unsupported
        val path = u.rawPath ?: ""
        return when {
            host in YOUTUBE_HOSTS -> CHANNEL.find(path)?.let { YouTube("https://www.youtube.com/" + it.groupValues[1]) } ?: Unsupported
            host == "music.youtube.com" -> MUSIC.find(path)?.let { m ->
                val p = m.groupValues[1].replaceFirst("browse/", "channel/")
                YouTubeMusic("https://music.youtube.com/$p", "https://www.youtube.com/$p")
            } ?: Unsupported
            host in MB_HOSTS -> ARTIST.find(path)?.let { m -> Names.mbid(m.groupValues[1])?.let { MusicBrainz(it) } } ?: Unsupported
            host == "commons.wikimedia.org" -> Wikimedia.commonsFile(url.substringBefore('?').substringBefore('#'))?.let { Commons(it) } ?: Unsupported
            host == "upload.wikimedia.org" -> Wikimedia.uploadFile(url)?.let { Commons(it) } ?: Unsupported
            host in PAGES -> Unsupported
            else -> Direct(url)
        }
    }
}
