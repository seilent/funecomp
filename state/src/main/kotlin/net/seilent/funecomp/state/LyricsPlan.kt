package net.seilent.funecomp.state

object LyricsPlan {
    data class Want(val track: Long, val title: String, val artist: String, val album: String?, val durationMs: Long?, val plain: Boolean, val artists: List<String> = emptyList())

    const val AHEAD = 5

    fun wants(d: DeviceState, done: Set<Long>): List<Want> {
        val s = d.playback.state
        val out = ArrayList<Want>()
        val track = s.track
        val title = s.title
        val artist = s.artist ?: s.artists.joinToString(", ").ifEmpty { null }
        if (track != null && s.lyrics == false && title != null && artist != null && track !in done) out += Want(track, title, artist, s.album, s.durationMs, s.lyricsPlain == false, s.artists)
        val slot = s.slot ?: return out
        for (i in slot + 1..slot + AHEAD) {
            val q = d.queue.items[i] ?: continue
            val a = q.artists.joinToString(", ").ifEmpty { null } ?: continue
            val t = q.title ?: continue
            if (q.lyrics != false || q.track in done || out.any { it.track == q.track }) continue
            out += Want(q.track, t, a, q.album, q.durationMs, q.lyricsPlain == false, q.artists)
        }
        return out
    }
}
