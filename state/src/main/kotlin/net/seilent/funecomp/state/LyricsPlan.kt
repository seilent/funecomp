package net.seilent.funecomp.state

object LyricsPlan {
    data class Want(val track: Long, val title: String, val artist: String, val album: String?, val durationMs: Long?, val plain: Boolean, val artists: List<String> = emptyList())

    data class Held(val track: Long, val title: String, val artist: String, val keys: List<String>, val album: String?, val durationMs: Long?, val synced: Boolean, val playing: Boolean, val artists: List<String> = emptyList())

    const val AHEAD = 5

    fun wants(d: DeviceState, done: Set<Long>): List<Want> {
        val s = d.playback.state
        val out = ArrayList<Want>()
        val track = s.library
        val title = s.title
        val artist = s.artist ?: s.artists.joinToString(", ").ifEmpty { null }
        if (track != null && s.lyrics == false && title != null && artist != null && track !in done) out += Want(track, title, artist, s.album, s.durationMs, s.lyricsPlain == false, s.artists)
        val slot = s.slot ?: return out
        for (i in slot + 1..slot + AHEAD) {
            val q = d.queue.items[i] ?: continue
            val a = q.artists.joinToString(", ").ifEmpty { null } ?: continue
            val t = q.title ?: continue
            val id = q.library ?: continue
            if (q.lyrics != false || id in done || out.any { it.track == id }) continue
            out += Want(id, t, a, q.album, q.durationMs, q.lyricsPlain == false, q.artists)
        }
        return out
    }

    fun held(d: DeviceState, done: Set<Long>): List<Held> {
        val s = d.playback.state
        val out = ArrayList<Held>()
        val track = s.library
        val title = s.title
        val joined = s.artists.joinToString(", ").ifEmpty { null }
        val artist = s.artist ?: joined
        if (track != null && (s.lyrics == true || s.lyricsPlain == true) && title != null && artist != null && track !in done) {
            out += Held(track, title, artist, listOfNotNull(s.artist, joined).distinct(), s.album, s.durationMs, s.lyrics == true, true, s.artists)
        }
        val slot = s.slot ?: return out
        for (i in slot + 1..slot + AHEAD) {
            val q = d.queue.items[i] ?: continue
            val a = q.artists.joinToString(", ").ifEmpty { null } ?: continue
            val t = q.title ?: continue
            val id = q.library ?: continue
            if (q.lyrics != true && q.lyricsPlain != true || id in done || out.any { it.track == id }) continue
            out += Held(id, t, a, listOf(a), q.album, q.durationMs, q.lyrics == true, false, q.artists)
        }
        return out
    }
}
