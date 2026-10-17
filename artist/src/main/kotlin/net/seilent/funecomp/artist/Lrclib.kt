package net.seilent.funecomp.artist

import java.io.File
import java.io.IOException
import java.security.MessageDigest
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
        val key: String get() = hash("$track\n$artist\n${album ?: ""}\n${durationMs?.let { (it + 500) / 1000 } ?: ""}")
    }

    private val api = Api(http, "funecomp/$version (https://github.com/seilent/funecomp)", RateLimiter(INTERVAL_MS, mono), sleep, jitter = { random.nextDouble() * JITTER })

    @Synchronized
    fun get(q: Query): String? {
        if (!enabled()) return null
        val hit = File(dir, "${q.key}.lrc")
        if (hit.isFile) return hit.readText()
        val miss = File(dir, "${q.key}.miss")
        if (miss.isFile && clock() - (miss.readText().trim().toLongOrNull() ?: 0) in 0 until MISS_MS) return null
        val j = api.json(url(q))
        val synced = j?.get("syncedLyrics")?.str?.takeIf { it.isNotBlank() && j["instrumental"].bool != true }
        dir.mkdirs()
        if (synced == null) save(miss, clock().toString()) else save(hit, synced)
        return synced
    }

    private fun url(q: Query): String = buildString {
        append(BASE).append("?track_name=").append(Api.enc(q.track)).append("&artist_name=").append(Api.enc(q.artist))
        q.album?.takeIf { it.isNotBlank() }?.let { append("&album_name=").append(Api.enc(it)) }
        q.durationMs?.let { append("&duration=").append((it + 500) / 1000) }
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

        private fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    }
}
