package net.seilent.funecomp.artist

import net.seilent.funecomp.proto.gen.Enums
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
        val crc: Long? = null,
    ) : Record

    data class Reject(
        override val key: String,
        override val mbid: String?,
        val resolvedBy: String,
        override val name: String,
        override val at: Long,
        val applied: Set<String> = emptySet(),
        val crc: Long? = null,
    ) : Record

    data class Drop(
        override val key: String,
        override val mbid: String?,
        override val name: String,
        override val at: Long,
        val applied: Set<String> = emptySet(),
    ) : Record

    data class Held(val name: String, val mbid: String?, val device: String)

    data class Push(val name: String, val mbid: String?, val done: Set<String> = emptySet(), val origin: Int? = null)

    private val blocks = ArrayList<Block>()
    private val rejects = ArrayList<Reject>()
    private val pushes = ArrayList<Push>()
    private val drops = ArrayList<Drop>()
    private val held = ArrayList<Held>()

    init {
        load()
    }

    @Synchronized
    fun add(r: Record) {
        when (r) {
            is Block -> blocks += r
            is Reject -> rejects += r
            is Drop -> {
                drop(r.key, r.mbid, r.name, r.at)
                return
            }
        }
        pushes.removeAll { Names.fold(it.name) == Names.fold(r.name) }
        held.removeAll { Names.fold(it.name) == Names.fold(r.name) }
    }

    @Synchronized
    fun drop(key: String, mbid: String?, name: String, at: Long): Boolean {
        if (drops.any { it.key == key }) return false
        drops += Drop(key, Names.mbid(mbid), name, at)
        return true
    }

    @Synchronized
    fun drops(): List<Drop> = drops.toList()

    @Synchronized
    fun hold(name: String, mbid: String?, device: String) {
        held.removeAll { it.device == device && Names.fold(it.name) == Names.fold(name) }
        held += Held(name, Names.mbid(mbid), device)
    }

    @Synchronized
    fun held(device: String): List<Held> = held.filter { it.device == device }

    @Synchronized
    fun released(h: Held) {
        held.remove(h)
    }

    @Synchronized
    fun remove(r: Record) {
        when (r) {
            is Block -> blocks.removeAll { it.key == r.key && it.source == r.source && it.id == r.id && it.at == r.at }
            is Reject -> rejects.removeAll { it.resolvedBy == r.resolvedBy && it.mbid == r.mbid && it.at == r.at }
            is Drop -> {
                drops.removeAll { it.key == r.key }
                return
            }
        }
        pushes.removeAll { Names.fold(it.name) == Names.fold(r.name) }
        pushes += Push(r.name, if (r is Block) Names.mbid(r.key) else null)
    }

    @Synchronized
    fun addPush(name: String, mbid: String?, origin: Int? = null) {
        pushes.removeAll { Names.fold(it.name) == Names.fold(name) }
        pushes += Push(name, Names.mbid(mbid), origin = origin)
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
    fun pending(device: String): List<Record> = (records() + drops).filter {
        device !in when (it) {
            is Block -> it.applied
            is Reject -> it.applied
            is Drop -> it.applied
        }
    }

    @Synchronized
    fun covering(wrongArtist: Boolean, name: String, keys: Set<String>, mbid: String?, crc: Long, device: String?): Record? {
        val f = Names.fold(name)
        val m = Names.mbid(mbid)
        return records().firstOrNull { r ->
            val (rc, applied) = when (r) {
                is Block -> r.crc to r.applied
                is Reject -> r.crc to r.applied
                is Drop -> null to r.applied
            }
            (r is Reject) == wrongArtist && (r.key in keys || Names.fold(r.name) == f || (m != null && r.mbid == m)) &&
                (rc == crc || (rc == null && device != null && device !in applied))
        }
    }

    @Synchronized
    fun pushes(device: String): List<Push> = pushes.filter { device !in it.done }

    @Synchronized
    fun applied(r: Record, device: String) {
        when (r) {
            is Block -> blocks.replaceAll { if (it == r || (it.key == r.key && it.at == r.at && it.id == r.id)) it.copy(applied = it.applied + device) else it }
            is Reject -> rejects.replaceAll { if (it == r || (it.resolvedBy == r.resolvedBy && it.at == r.at)) it.copy(applied = it.applied + device) else it }
            is Drop -> drops.replaceAll { if (it.key == r.key) it.copy(applied = it.applied + device) else it }
        }
    }

    @Synchronized
    fun pushed(p: Push, device: String) {
        pushes.replaceAll { if (it.name == p.name && it.mbid == p.mbid) it.copy(done = it.done + device) else it }
    }

    @Synchronized
    fun encode(): String = buildString {
        for (b in blocks) line("block", b.key, b.mbid ?: "", b.source, b.id, b.sha, b.name, b.at.toString(), b.applied.joinToString(","), *listOfNotNull(b.crc?.toString()).toTypedArray())
        for (d in drops) line("drop", d.key, d.mbid ?: "", d.name, d.at.toString(), d.applied.joinToString(","))
        for (r in rejects) line("reject", r.key, r.mbid ?: "", r.resolvedBy, r.name, r.at.toString(), r.applied.joinToString(","), *listOfNotNull(r.crc?.toString()).toTypedArray())
        for (h in held) line("held", h.name, h.mbid ?: "", h.device)
        for (p in pushes) {
            line("push", p.name, p.mbid ?: "", p.done.joinToString(","))
            p.origin?.let { o -> Enums.ImgOrigin.names[o]?.let { line("origin", p.name, p.mbid ?: "", it) } }
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
            val p = l.split('\t').map { unescape(it) }
            val ok = when (p[0]) {
                "block" -> (p.size == 9 || p.size == 10) && p[7].toLongOrNull() != null && blocks.add(Block(p[1], p[2].ifEmpty { null }, p[3], p[4], p[5], p[6], p[7].toLong(), set(p[8]), p.getOrNull(9)?.toLongOrNull()))
                "reject" -> (p.size == 7 || p.size == 8) && p[5].toLongOrNull() != null && rejects.add(Reject(p[1], p[2].ifEmpty { null }, p[3], p[4], p[5].toLong(), set(p[6]), p.getOrNull(7)?.toLongOrNull()))
                "drop" -> p.size == 6 && p[4].toLongOrNull() != null && drops.none { it.key == p[1] } && drops.add(Drop(p[1], p[2].ifEmpty { null }, p[3], p[4].toLong(), set(p[5])))
                "held" -> p.size == 4 && held.add(Held(p[1], p[2].ifEmpty { null }, p[3]))
                "push" -> p.size == 4 && pushes.add(Push(p[1], p[2].ifEmpty { null }, set(p[3])))
                "origin" -> p.size == 4 && origin(p[1], p[2].ifEmpty { null }, p[3])
                else -> false
            }
            if (!ok) log("artblock skipped line ${l.take(80)}")
        }
    }

    private fun origin(name: String, mbid: String?, word: String): Boolean {
        val o = Enums.ImgOrigin.names.entries.firstOrNull { it.value == word }?.key ?: return false
        val i = pushes.indexOfLast { it.name == name && it.mbid == mbid }
        if (i < 0) return false
        pushes[i] = pushes[i].copy(origin = o)
        return true
    }

    private fun StringBuilder.line(vararg f: String) {
        append(f.joinToString("\t") { escape(it) })
        append('\n')
    }

    private fun set(s: String) = s.split(',').filter { it.isNotEmpty() }.toSet()

    internal companion object {
        fun escape(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

        fun unescape(s: String) = buildString {
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
}
