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
    data class Query(val track: String, val artist: String, val album: String?, val durationMs: Long?) {
        val key: String get() = hash("$track\n$artist\n${album ?: ""}\n${durationMs?.let { seconds(it) } ?: ""}")
    }

    private val api = Api(http, "funecomp/$version (https://github.com/seilent/funecomp)", RateLimiter(INTERVAL_MS, mono), sleep, tries = TRIES, jitter = { random.nextDouble() * JITTER })

    @Synchronized
    fun get(q: Query): String? {
        if (!enabled()) return null
        val hit = File(dir, "${q.key}.lrc")
        if (hit.isFile) return hit.readText()
        val miss = File(dir, "${q.key}.miss")
        if (miss.isFile) {
            val lines = miss.readText().lines()
            if (lines.getOrNull(1) == CLEANED && clock() - (lines[0].trim().toLongOrNull() ?: 0) in 0 until MISS_MS) return null
        }
        val j = api.json(url(q.track, q.artist, q.durationMs))
        var synced = synced(j)
        val d = q.durationMs
        if (synced == null && d != null && j?.get("instrumental")?.bool != true) synced = split(q, d)
        dir.mkdirs()
        if (synced == null) save(miss, "${clock()}\n$CLEANED") else save(hit, synced)
        return synced
    }

    private fun synced(j: J?): String? = j?.get("syncedLyrics")?.str?.takeIf { it.isNotBlank() && j["instrumental"].bool != true }

    private fun split(q: Query, durationMs: Long): String? {
        val (track, artist) = LyricNames.split(q.track, q.artist) ?: return null
        val j = api.json(url(track, artist, durationMs)) ?: return null
        val d = j["duration"].double ?: return null
        if (LyricNames.fold(j["trackName"].str ?: "") != LyricNames.fold(track) || abs(d - seconds(durationMs)) > MATCH_S) return null
        return synced(j)
    }

    private fun url(track: String, artist: String, durationMs: Long?): String = buildString {
        append(BASE).append("?track_name=").append(Api.enc(track)).append("&artist_name=").append(Api.enc(artist))
        durationMs?.let { append("&duration=").append(seconds(it)) }
    }

    private fun save(f: File, text: String) {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) throw IOException("rename ${f.name}")
        }
    }

    companion object {
        const val BASE = "https://lrclib.net/api/get"
        const val MISS_MS = 7L * 24 * 3600 * 1000
        private const val INTERVAL_MS = 1000L
        private const val JITTER = 0.3
        private const val TRIES = 6
        private const val MATCH_S = 2.0
        private const val CLEANED = "cleaned=1"

        private fun seconds(ms: Long) = (ms + 500) / 1000

        private fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    }
}
