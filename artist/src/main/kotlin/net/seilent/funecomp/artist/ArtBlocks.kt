package net.seilent.funecomp.artist

import java.io.File

class ArtBlocks(private val file: File? = null, private val log: (String) -> Unit = {}) {
    sealed interface Record {
        val key: String
        val mbid: String?
        val name: String
        val at: Long
    }

    data class Block(
        override val key: String,
        override val mbid: String?,
        val source: String,
        val id: String,
        val sha: String,
        override val name: String,
        override val at: Long,
        val applied: Set<String> = emptySet(),
    ) : Record

    data class Reject(
        override val key: String,
        override val mbid: String?,
        val resolvedBy: String,
        override val name: String,
        override val at: Long,
        val applied: Set<String> = emptySet(),
    ) : Record

    data class Push(val name: String, val mbid: String?, val done: Set<String> = emptySet())

    private val blocks = ArrayList<Block>()
    private val rejects = ArrayList<Reject>()
    private val pushes = ArrayList<Push>()

    init {
        load()
    }

    @Synchronized
    fun add(r: Record) {
        when (r) {
            is Block -> blocks += r
            is Reject -> rejects += r
        }
        pushes.removeAll { Names.fold(it.name) == Names.fold(r.name) }
    }

    @Synchronized
    fun remove(r: Record) {
        when (r) {
            is Block -> blocks.removeAll { it.key == r.key && it.source == r.source && it.id == r.id && it.at == r.at }
            is Reject -> rejects.removeAll { it.resolvedBy == r.resolvedBy && it.mbid == r.mbid && it.at == r.at }
        }
        pushes.removeAll { Names.fold(it.name) == Names.fold(r.name) }
        pushes += Push(r.name, if (r is Block) Names.mbid(r.key) else null)
    }

    @Synchronized
    fun blocked(key: String, source: String, id: String, sha: String = "", name: String = ""): Boolean {
        val byName = Names.fold(name).takeIf { it.isNotEmpty() }?.let { "name:$it" }
        return blocks.any { (it.key == key || it.key == byName) && ((it.source == source && it.id == id && id.isNotEmpty()) || (sha.isNotEmpty() && it.sha == sha)) }
    }

    @Synchronized
    fun rejected(name: String): Set<String> {
        val f = Names.fold(name)
        return rejects.filter { Names.fold(it.resolvedBy) == f }.mapNotNullTo(HashSet()) { it.mbid }
    }

    @Synchronized
    fun records(): List<Record> = (blocks + rejects).sortedByDescending { it.at }

    @Synchronized
    fun pending(device: String): List<Record> = records().filter {
        device !in when (it) {
            is Block -> it.applied
            is Reject -> it.applied
        }
    }

    @Synchronized
    fun pushes(device: String): List<Push> = pushes.filter { device !in it.done }

    @Synchronized
    fun applied(r: Record, device: String) {
        when (r) {
            is Block -> blocks.replaceAll { if (it == r || (it.key == r.key && it.at == r.at && it.id == r.id)) it.copy(applied = it.applied + device) else it }
            is Reject -> rejects.replaceAll { if (it == r || (it.resolvedBy == r.resolvedBy && it.at == r.at)) it.copy(applied = it.applied + device) else it }
        }
    }

    @Synchronized
    fun pushed(p: Push, device: String) {
        pushes.replaceAll { if (it.name == p.name && it.mbid == p.mbid) it.copy(done = it.done + device) else it }
    }

    @Synchronized
    fun encode(): String = buildString {
        for (b in blocks) line("block", b.key, b.mbid ?: "", b.source, b.id, b.sha, b.name, b.at.toString(), b.applied.joinToString(","))
        for (r in rejects) line("reject", r.key, r.mbid ?: "", r.resolvedBy, r.name, r.at.toString(), r.applied.joinToString(","))
        for (p in pushes) line("push", p.name, p.mbid ?: "", p.done.joinToString(","))
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
            val p = l.split('\t').map { unescape(it) }
            val ok = when (p[0]) {
                "block" -> p.size == 9 && p[7].toLongOrNull() != null && blocks.add(Block(p[1], p[2].ifEmpty { null }, p[3], p[4], p[5], p[6], p[7].toLong(), set(p[8])))
                "reject" -> p.size == 7 && p[5].toLongOrNull() != null && rejects.add(Reject(p[1], p[2].ifEmpty { null }, p[3], p[4], p[5].toLong(), set(p[6])))
                "push" -> p.size == 4 && pushes.add(Push(p[1], p[2].ifEmpty { null }, set(p[3])))
                else -> false
            }
            if (!ok) log("artblock skipped line ${l.take(80)}")
        }
    }

    private fun StringBuilder.line(vararg f: String) {
        append(f.joinToString("\t") { escape(it) })
        append('\n')
    }

    private fun set(s: String) = s.split(',').filter { it.isNotEmpty() }.toSet()

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unescape(s: String) = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                append(
                    when (s[i + 1]) {
                        't' -> '\t'
                        'n' -> '\n'
                        else -> s[i + 1]
                    },
                )
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }
}
