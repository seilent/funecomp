package net.seilent.funecomp.art

import java.io.File
import java.io.IOException

class Pending(private val file: File?, private val log: (String) -> Unit = {}) {
    data class Entry(val name: String, val mbid: String?)

    private val set = LinkedHashSet<Entry>()
    private var last: List<Pair<String, String?>> = emptyList()

    init {
        load()
    }

    @Synchronized
    fun take(artists: List<Pair<String, String?>>) {
        if (artists == last) return
        last = artists
        var changed = false
        for ((name, mbid) in artists) {
            if (name.isBlank() || name.any { it == '\t' || it == '\n' || it == '\r' }) continue
            if (set.add(Entry(name, mbid?.takeIf { it.isNotBlank() }))) changed = true
        }
        if (changed) save()
    }

    @Synchronized
    fun list(): List<Entry> = set.toList()

    @Synchronized
    fun remove(names: Collection<String>) {
        if (names.isEmpty()) return
        if (set.removeAll { it.name in names }) save()
    }

    private fun save() {
        val f = file ?: return
        try {
            f.parentFile?.mkdirs()
            val t = File(f.path + ".tmp")
            t.writeText(set.joinToString("") { "${it.name}\t${it.mbid ?: ""}\n" }, Charsets.UTF_8)
            if (!t.renameTo(f)) {
                f.delete()
                t.renameTo(f)
            }
        } catch (e: IOException) {
            log("art pending save: $e")
        }
    }

    private fun load() {
        val f = file ?: return
        if (!f.isFile) return
        try {
            for (line in f.readLines(Charsets.UTF_8)) {
                val name = line.substringBefore('\t')
                if (name.isBlank()) continue
                set += Entry(name, line.substringAfter('\t', "").takeIf { it.isNotBlank() })
            }
        } catch (e: IOException) {
            log("art pending load: $e")
        }
    }
}
