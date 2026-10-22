package net.seilent.funecomp.artist

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.random.Random

class Lrclib(
    private val dir: File,
    http: Http,
    version: String,
    private val enabled: () -> Boolean,
    private val clock: () -> Long = { System.currentTimeMillis() },
    sleep: (Long) -> Unit = { Thread.sleep(it) },
    mono: () -> Long = { System.nanoTime() / 1_000_000 },
    random: Random = Random.Default,
) {
    data class Found(val text: String, val synced: Boolean, val source: String = LyricSources.LRCLIB)

    data class Query(val track: String, val artist: String, val album: String?, val durationMs: Long?, val artists: List<String> = emptyList()) {
        val key: String get() = hash("$track\n$artist\n${album ?: ""}\n${durationMs?.let { seconds(it) } ?: ""}")
    }

    private val files = FileStore(dir)

    private val api = Api(http, "funecomp/$version (https://github.com/seilent/funecomp)", RateLimiter(INTERVAL_MS, mono), sleep, tries = TRIES, jitter = { random.nextDouble() * JITTER })

    @Synchronized
    fun get(q: Query, store: LyricStore = files): Found? {
        if (!enabled()) return null
        store.read("${q.key}.lrc")?.let { return Found(it, true) }
        val txt = "${q.key}.txt"
        val miss = "${q.key}.miss"
        store.read(miss)?.let { m ->
            val lines = m.lines()
            if (lines.getOrNull(1) == CLEANED && clock() - (lines[0].trim().toLongOrNull() ?: 0) in 0 until MISS_MS) {
                return store.read(txt)?.let { Found(it, false) }
            }
        }
        val j = api.json(url(q.track, q.artist, q.durationMs))
        val first = synced(j)
        val ask = q.durationMs?.takeIf { first == null && j?.get("instrumental")?.bool != true }
        val second = ask?.let { split(q, it) }
        val third = ask?.takeIf { second?.synced != true }?.let { cleaned(q, it) }
        val found = first ?: second?.takeIf { it.synced } ?: third?.takeIf { it.synced } ?: plain(j) ?: second ?: third
        when {
            found == null -> {
                store.delete(txt)
                store.save(miss, "${clock()}\n$CLEANED")
            }
            found.synced -> {
                store.save("${q.key}.lrc", found.text)
                store.delete(txt)
                store.delete(miss)
            }
            else -> {
                store.save(txt, found.text)
                store.save(miss, "${clock()}\n$CLEANED")
            }
        }
        return found
    }

    private fun synced(j: J?): Found? = j?.get("syncedLyrics")?.str?.takeIf { it.isNotBlank() && j["instrumental"].bool != true }?.let { Found(it, true) }

    private fun plain(j: J?): Found? = j?.get("plainLyrics")?.str?.takeIf { it.isNotBlank() && j["instrumental"].bool != true }?.let { Found(it, false) }

    private fun split(q: Query, durationMs: Long): Found? {
        val (track, artist) = LyricNames.split(q.track, q.artist) ?: return null
        val j = api.json(url(track, artist, durationMs)) ?: return null
        if (!matches(j, track, durationMs)) return null
        return synced(j) ?: plain(j)
    }

    private fun cleaned(q: Query, durationMs: Long): Found? {
        val c = LyricNames.clean(q.track, q.artist)
        val sent = hashSetOf(q.track to q.artist)
        LyricNames.split(q.track, q.artist)?.let { sent += it }
        val artists = listOfNotNull(c.artist) + c.tags + listOfNotNull(q.artist.takeIf { c.artist == null })
        val queries = artists.map { c.title to it }.filter { it.first.isNotEmpty() && sent.add(it) }.take(CLEAN_QUERIES)
        var plain: Found? = null
        for ((track, artist) in queries) {
            val j = api.json(url(track, artist, durationMs)) ?: continue
            if (!matches(j, track, durationMs) || !sameArtist(j["artistName"].str, artist)) continue
            synced(j)?.let { return it }
            plain = plain ?: plain(j)
        }
        return plain
    }

    private fun sameArtist(name: String?, artist: String): Boolean {
        val k = LyricNames.artistKey(artist)
        return if (k.isEmpty()) Names.fold(name) == Names.fold(artist) else LyricNames.artistKey(name ?: "") == k
    }

    private fun matches(j: J, track: String, durationMs: Long): Boolean {
        val d = j["duration"].double ?: return false
        return LyricNames.fold(j["trackName"].str ?: "") == LyricNames.fold(track) && abs(d - seconds(durationMs)) <= MATCH_S
    }

    private fun url(track: String, artist: String, durationMs: Long?): String = buildString {
        append(BASE).append("?track_name=").append(Api.enc(track)).append("&artist_name=").append(Api.enc(artist))
        durationMs?.let { append("&duration=").append(seconds(it)) }
    }

    companion object {
        const val BASE = "https://lrclib.net/api/get"
        const val MISS_MS = 7L * 24 * 3600 * 1000
        private const val INTERVAL_MS = 1000L
        private const val JITTER = 0.3
        private const val TRIES = 6
        private const val MATCH_S = 2.0
        private const val CLEANED = "cleaned=3"
        private const val CLEAN_QUERIES = 3

        private fun seconds(ms: Long) = (ms + 500) / 1000

        private fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    }
}

class LyricsFileException(message: String, cause: Throwable? = null) : IOException(message, cause)
