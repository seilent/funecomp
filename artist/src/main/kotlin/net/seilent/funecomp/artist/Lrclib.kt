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

    private val api = Api(http, "funecomp/$version (https://github.com/seilent/funecomp)", RateLimiter(INTERVAL_MS, mono), sleep, tries = TRIES, jitter = { random.nextDouble() * JITTER })

    @Synchronized
    fun get(q: Query): Found? {
        if (!enabled()) return null
        val hit = File(dir, "${q.key}.lrc")
        if (hit.isFile) return Found(LyricFiles.read(hit), true)
        val txt = File(dir, "${q.key}.txt")
        val miss = File(dir, "${q.key}.miss")
        if (miss.isFile) {
            val lines = LyricFiles.read(miss).lines()
            if (lines.getOrNull(1) == CLEANED && clock() - (lines[0].trim().toLongOrNull() ?: 0) in 0 until MISS_MS) {
                return if (txt.isFile) Found(LyricFiles.read(txt), false) else null
            }
        }
        val j = api.json(url(q.track, q.artist, q.durationMs))
        val d = q.durationMs
        val first = synced(j)
        val second = if (first == null && d != null && j?.get("instrumental")?.bool != true) split(q, d) else null
        val found = first ?: second?.takeIf { it.synced } ?: plain(j) ?: second
        dir.mkdirs()
        when {
            found == null -> {
                LyricFiles.delete(txt)
                LyricFiles.save(miss, "${clock()}\n$CLEANED")
            }
            found.synced -> {
                LyricFiles.save(hit, found.text)
                LyricFiles.delete(txt)
                LyricFiles.delete(miss)
            }
            else -> {
                LyricFiles.save(txt, found.text)
                LyricFiles.save(miss, "${clock()}\n$CLEANED")
            }
        }
        return found
    }

    private fun synced(j: J?): Found? = j?.get("syncedLyrics")?.str?.takeIf { it.isNotBlank() && j["instrumental"].bool != true }?.let { Found(it, true) }

    private fun plain(j: J?): Found? = j?.get("plainLyrics")?.str?.takeIf { it.isNotBlank() && j["instrumental"].bool != true }?.let { Found(it, false) }

    private fun split(q: Query, durationMs: Long): Found? {
        val (track, artist) = LyricNames.split(q.track, q.artist) ?: return null
        val j = api.json(url(track, artist, durationMs)) ?: return null
        val d = j["duration"].double ?: return null
        if (LyricNames.fold(j["trackName"].str ?: "") != LyricNames.fold(track) || abs(d - seconds(durationMs)) > MATCH_S) return null
        return synced(j) ?: plain(j)
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
        private const val CLEANED = "cleaned=2"

        private fun seconds(ms: Long) = (ms + 500) / 1000

        private fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    }
}

class LyricsFileException(message: String, cause: Throwable? = null) : IOException(message, cause)
