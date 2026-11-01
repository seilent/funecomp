package net.seilent.funecomp.artist

import java.io.File

class ArtCrops(private val file: File? = null, private val log: (String) -> Unit = {}) {
    private class Line(val key: String, val id: String, val at: Long, val x: Int, val y: Int) {
        fun of(p: ArtPicks.Pick) = key == p.key && id == p.id && at == p.at
    }

    private val lines = ArrayList<Line>()
    var skipped = 0
        private set

    init {
        load()
    }

    @Synchronized
    fun get(p: ArtPicks.Pick): Pair<Int, Int>? = if (p.kind != ArtPicks.IMAGE) null else lines.firstOrNull { it.of(p) }?.let { it.x to it.y }

    @Synchronized
    fun set(p: ArtPicks.Pick, point: Pair<Int, Int>?) {
        lines.removeAll { it.of(p) }
        if (point != null) lines += Line(p.key, p.id, p.at, point.first, point.second)
    }

    @Synchronized
    fun drop(p: ArtPicks.Pick) {
        lines.removeAll { it.of(p) }
    }

    @Synchronized
    fun encode(): String = buildString {
        for (l in lines) {
            append(listOf("crop", l.key, l.id, l.at.toString(), l.x.toString(), l.y.toString()).joinToString("\t") { ArtBlocks.escape(it) })
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
        for (l in f.readLines(Charsets.UTF_8)) {
            if (l.isBlank()) continue
            val p = l.split('\t').map { ArtBlocks.unescape(it) }
            val at = p.getOrNull(3)?.toLongOrNull()
            val x = p.getOrNull(4)?.toIntOrNull()?.takeIf { it in 0..PER_MILLE }
            val y = p.getOrNull(5)?.toIntOrNull()?.takeIf { it in 0..PER_MILLE }
            if (p[0] == "crop" && p.size == FIELDS && at != null && x != null && y != null) {
                lines += Line(p[1], p[2], at, x, y)
            } else {
                skipped++
                log("artcrop skipped line ${l.take(80)}")
            }
        }
    }

    private companion object {
        const val FIELDS = 6
        const val PER_MILLE = 1000
    }
}
