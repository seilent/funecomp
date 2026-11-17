package net.seilent.funecomp.sources

import net.seilent.funekit.artist.Sources
import net.seilent.funekit.artist.Wikimedia

object ArtSources {
    val ORDER = listOf("theaudiodb", "fanarttv", "youtube", "discogs", Wikimedia.SOURCE, "deezer")
    private val OLD_ORDER = listOf(Wikimedia.SOURCE, "theaudiodb", "fanarttv", "discogs", "youtube", "deezer")
    val DEFAULT = Sources(theAudioDb = true, discogs = true, fanartTv = true, deezer = true, order = ORDER, commons = true, plugins = mapOf(YouTubeArt.SOURCE to true))
    val NONE = Sources(theAudioDb = false, discogs = false, fanartTv = false, deezer = false, order = OLD_ORDER, commons = false, plugins = mapOf(YouTubeArt.SOURCE to false))
    val COMMONS = NONE.copy(commons = true)
    const val DEFAULTS = "defaults"
    const val DEFAULTS_REV = 2

    class Edit(val remove: Set<String>, val write: Boolean)

    fun order(s: String?): List<String> = Sources.order(s, ORDER).filter { it in ORDER }

    fun upgrade(stored: Map<String, Any?>): Edit {
        if (((stored[DEFAULTS] as? Int) ?: 0) >= DEFAULTS_REV) return Edit(emptySet(), false)
        val keyed = (stored["fanarttvKey"] as? String)?.isNotBlank() == true
        val remove = listOf(Sources.THE_AUDIO_DB, Sources.DISCOGS, Sources.FANART_TV, Sources.DEEZER, YouTubeArt.SOURCE).filter { stored[it] == false && !(it == Sources.FANART_TV && keyed) }.toMutableSet()
        if (stored.containsKey("order") && order(stored["order"] as? String) == OLD_ORDER) remove += "order"
        return Edit(remove, true)
    }
}
