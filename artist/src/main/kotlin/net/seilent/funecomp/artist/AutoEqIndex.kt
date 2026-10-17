package net.seilent.funecomp.artist

import java.io.File
import java.io.IOException

class AutoEqIndex(
    private val dir: File,
    http: Http,
    version: String,
    private val clock: () -> Long = { System.currentTimeMillis() },
    sleep: (Long) -> Unit = { Thread.sleep(it) },
    mono: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    class Entry(val name: String, val path: String) {
        val url: String get() = "$BASE/$path/${path.substringAfterLast('/')}%20ParametricEQ.txt"
    }

    private val api = Api(http, "funecomp/$version (https://github.com/seilent/funecomp)", RateLimiter(INTERVAL_MS, mono), sleep)
    private var entries: List<Entry>? = null
    private var loadedAt = 0L

    @Synchronized
    fun search(query: String): List<Entry> {
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return entries().filter { e -> e.name.lowercase().let { n -> words.all { it in n } } }.take(MAX_RESULTS)
    }

    @Synchronized
    fun profile(e: Entry): String? = api.get(e.url)?.text

    private fun entries(): List<Entry> {
        val now = clock()
        entries?.let { if (now - loadedAt < MAX_AGE_MS) return it }
        val f = File(dir, INDEX)
        val fresh = f.isFile && now - f.lastModified() in 0 until MAX_AGE_MS
        val text = if (fresh) f.readText() else try {
            api.get("$BASE/README.md")?.text?.also { save(f, it) } ?: throw IOException("no index")
        } catch (e: IOException) {
            if (!f.isFile) throw e
            f.readText()
        }
        return parse(text).also {
            entries = it
            loadedAt = now
        }
    }

    private fun save(f: File, text: String) {
        dir.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    companion object {
        const val BASE = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results"
        const val INDEX = "index.md"
        const val MAX_AGE_MS = 30L * 24 * 3600 * 1000
        const val MAX_RESULTS = 50
        private const val INTERVAL_MS = 1000L
        private val LINE = Regex("^- \\[(.+)]\\(\\./(.+)\\)$")

        fun parse(text: String): List<Entry> = text.lineSequence().mapNotNull { l ->
            LINE.find(l.trim())?.let { Entry(it.groupValues[1], it.groupValues[2]) }
        }.toList()
    }
}
