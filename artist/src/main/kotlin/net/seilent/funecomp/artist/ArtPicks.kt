package net.seilent.funecomp.artist

import java.io.File

class ArtPicks(private val file: File? = null, private val log: (String) -> Unit = {}) {
    data class Pick(
        val key: String,
        val key2: String?,
        val name: String,
        val kind: String,
        val source: String,
        val url: String,
        val id: String,
        val sha: String,
        val credit: Credit?,
        val mbid: String?,
        val at: Long,
        val failedAt: Long? = null,
        val was: String = "",
    ) {
        val keys: List<String> get() = listOfNotNull(key, key2)
        val cacheKey: String get() = PREFIX + key
    }

    private val picks = ArrayList<Pick>()
    var skipped = 0
        private set
    var read = false
        private set

    init {
        load()
    }

    @Synchronized
    fun of(tag: String?, name: String): Pick? {
        val k = Names.mbid(tag) ?: "name:${Names.fold(name)}"
        return picks.firstOrNull { k in it.keys }
    }

    @Synchronized
    fun byCache(key: String): Pick? = picks.firstOrNull { it.cacheKey == key }

    @Synchronized
    fun add(p: Pick) {
        picks.removeAll { it.keys.any { k -> k in p.keys } }
        picks += p
    }

    @Synchronized
    fun remove(p: Pick) {
        picks.removeAll { it.key == p.key && it.at == p.at }
    }

    @Synchronized
    fun <T> ifCurrent(p: Pick, block: () -> T): T? = if (picks.any { it.key == p.key && it.at == p.at }) block() else null

    @Synchronized
    fun failed(p: Pick, at: Long) {
        log("art pick fetch failed ${p.name}")
        picks.replaceAll { if (it.key == p.key && it.at == p.at) it.copy(failedAt = at) else it }
    }

    @Synchronized
    fun retry(p: Pick) {
        picks.replaceAll { if (it.key == p.key && it.at == p.at) it.copy(failedAt = null) else it }
    }

    @Synchronized
    fun none(key: String): Pick? = picks.firstOrNull { it.kind == NONE && key in it.keys }

    @Synchronized
    fun list(): List<Pick> = picks.sortedByDescending { it.at }

    @Synchronized
    fun encode(): String = buildString {
        for (p in picks) {
            val c = p.credit
            val f = listOf(
                "pick", p.key, p.key2 ?: "", p.name, p.kind, p.source, p.url, p.id, p.sha,
                c?.author ?: "", c?.license ?: "", c?.page ?: "", c?.licenseUrl ?: "", if (c == null) "" else "1",
                p.mbid ?: "", p.at.toString(), p.failedAt?.toString() ?: "", p.was,
            )
            append(f.joinToString("\t") { ArtBlocks.escape(it) })
            append('\n')
        }
    }

    @Synchronized
    fun save() {
        val f = file ?: return
        val text = encode()
        f.parentFile?.mkdirs()
        val t = File(f.path + ".tmp")
        t.writeText(text, Charsets.UTF_8)
        if (!t.renameTo(f)) {
            f.delete()
            t.renameTo(f)
        }
    }

    @Synchronized
    private fun load() {
        val f = file ?: return
        if (!f.isFile) return
        read = true
        for (l in f.readLines(Charsets.UTF_8)) {
            if (l.isBlank()) continue
            val p = l.split('\t').map { ArtBlocks.unescape(it) }
            val ok = p[0] == "pick" && p.size == FIELDS && p[15].toLongOrNull() != null && p[4] in KINDS && picks.add(
                Pick(
                    p[1], p[2].ifEmpty { null }, p[3], p[4], p[5], p[6], p[7], p[8],
                    if (p[13].isEmpty()) null else Credit(p[9], p[10], p[11], p[12]),
                    p[14].ifEmpty { null }, p[15].toLong(), p[16].toLongOrNull(), p[17],
                ),
            )
            if (!ok) {
                skipped++
                log("artpick skipped line ${l.take(80)}")
            }
        }
    }

    companion object {
        const val IMAGE = "image"
        const val ARTIST = "artist"
        const val NONE = "none"
        const val PREFIX = "pick:"
        private val KINDS = setOf(IMAGE, ARTIST, NONE)
        private const val FIELDS = 18

        fun keys(name: String, mbid: String?, library: Boolean): Pair<String, String?> {
            val byName = "name:${Names.fold(name)}"
            val m = Names.mbid(mbid) ?: return byName to null
            return m to if (library) byName else null
        }
    }
}
