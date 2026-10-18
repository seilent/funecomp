package net.seilent.funecomp.artist

import java.io.ByteArrayOutputStream
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
        val source = decode(path.substringBefore('/'))
        val rig = decode(path.split('/').getOrElse(1) { "" })
        val url: String get() = "$BASE/$path/${path.substringAfterLast('/')}%20ParametricEQ.txt"
    }

    private val api = Api(http, "funecomp/$version (https://github.com/seilent/funecomp)", RateLimiter(INTERVAL_MS, mono), sleep)
    private var list: AutoEqList? = null
    private var loadedAt = 0L

    @Synchronized
    fun profile(e: Entry): String? = api.get(e.url)?.text

    @Synchronized
    fun list(): AutoEqList {
        val now = clock()
        list?.let { if (now - loadedAt < MAX_AGE_MS) return it }
        val f = File(dir, INDEX)
        val fresh = f.isFile && now - f.lastModified() in 0 until MAX_AGE_MS
        val text = if (fresh) f.readText() else try {
            api.get("$BASE/README.md")?.text?.also { save(f, it) } ?: throw IOException("no index")
        } catch (e: IOException) {
            if (!f.isFile) throw e
            f.readText()
        }
        return AutoEqList.build(parse(text)).also {
            list = it
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
        private const val INTERVAL_MS = 1000L
        private val LINE = Regex("^- \\[(.+)]\\(\\./(.+)\\)$")

        fun parse(text: String): List<Entry> = text.lineSequence().mapNotNull { l ->
            LINE.find(l.trim())?.let { Entry(it.groupValues[1], it.groupValues[2]) }
        }.toList()

        fun decode(s: String): String {
            val out = ByteArrayOutputStream(s.length)
            var i = 0
            while (i < s.length) {
                val hi = if (s[i] == '%' && i + 2 < s.length) Character.digit(s[i + 1], 16) else -1
                val lo = if (hi >= 0) Character.digit(s[i + 2], 16) else -1
                if (lo >= 0) {
                    out.write(hi * 16 + lo)
                    i += 3
                } else {
                    val next = s.indexOf('%', i + 1).let { if (it < 0) s.length else it }
                    out.write(s.substring(i, next).toByteArray(Charsets.UTF_8))
                    i = next
                }
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }
    }
}
