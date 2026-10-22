package net.seilent.funecomp.artist

import net.seilent.funecomp.proto.PlayEntry
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

data class Listen(
    val startS: Long,
    val artist: String,
    val title: String,
    val album: String? = null,
    val durationS: Long? = null,
    val id: Long = 0,
    val albumArtist: String? = null,
    val trackNo: Int? = null,
    val mbids: List<String> = emptyList(),
) {
    fun line() = listOf(
        V2, id.toString(), startS.toString(), durationS?.toString() ?: "", clean(artist), clean(title), clean(album ?: ""),
        clean(albumArtist ?: ""), trackNo?.toString() ?: "", mbids.joinToString(";") { clean(it) },
    ).joinToString("\t")

    companion object {
        private const val V2 = "v2"
        private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        const val FUTURE_S = 600L

        private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

        fun parse(line: String): Listen? {
            val f = line.split('\t')
            if (f.size == 6) {
                return Listen(
                    f[1].toLongOrNull() ?: return null,
                    f[3],
                    f[4],
                    f[5].ifEmpty { null },
                    f[2].toLongOrNull(),
                    f[0].toLongOrNull() ?: return null,
                )
            }
            if (f.size != 10 || f[0] != V2) return null
            return Listen(
                f[2].toLongOrNull() ?: return null,
                f[4],
                f[5],
                f[6].ifEmpty { null },
                f[3].toLongOrNull(),
                f[1].toLongOrNull() ?: return null,
                f[7].ifEmpty { null },
                f[8].toIntOrNull(),
                f[9].split(';').filter { it.isNotEmpty() },
            )
        }

        fun mbids(ids: List<String>): List<String> = if (ids.all { UUID.matches(it) }) ids else emptyList()

        fun startS(localS: Long, zoneS: Int?, zone: ZoneId): Long =
            if (zoneS != null) localS - zoneS else LocalDateTime.ofEpochSecond(localS, 0, ZoneOffset.UTC).atZone(zone).toEpochSecond()

        fun of(e: PlayEntry, zone: ZoneId): Listen? {
            if (e.liveSeen == true) return null
            val local = e.startLocalS ?: return null
            val artist = (e.artist ?: e.artists.joinToString(", ")).trim()
            val title = e.title?.trim().orEmpty()
            if (artist.isEmpty() || title.isEmpty()) return null
            return Listen(
                startS(local, e.zoneS, zone),
                artist,
                title,
                e.album?.trim()?.ifEmpty { null },
                e.durationMs?.let { it / 1000 },
                albumArtist = e.albumArtist?.trim()?.ifEmpty { null },
                trackNo = e.trackNo?.takeIf { it > 0 },
                mbids = mbids(e.mbids),
            )
        }
    }
}

class ScrobbleQueue(private val dir: File, private val dirSync: (File) -> Unit = {}) {
    private var next = 1L
    private val lines = ArrayList<Listen>()
    private val dap = LinkedHashMap<Long, Long>()

    init {
        dir.mkdirs()
        val f = File(dir, QUEUE)
        if (f.isFile) {
            val all = f.readLines()
            all.firstOrNull()?.takeIf { it.startsWith("next\t") }?.removePrefix("next\t")?.toLongOrNull()?.let { next = it }
            for (l in all.drop(1)) {
                if (l.startsWith("$DAP\t")) {
                    val p = l.split('\t')
                    val log = p.getOrNull(1)?.let { runCatching { java.lang.Long.parseUnsignedLong(it, 16) }.getOrNull() }
                    val seq = p.getOrNull(2)?.toLongOrNull()
                    if (p.size == 3 && log != null && seq != null) dap[log] = seq
                    continue
                }
                Listen.parse(l)?.let { lines += it }
            }
            lines.lastOrNull()?.let { next = maxOf(next, it.id + 1) }
        }
    }

    val size: Int @Synchronized get() = lines.size

    @Synchronized
    fun add(l: Listen, enabled: Set<String>): Listen? {
        if (enabled.isEmpty()) return null
        enabled.forEach { cursor(it) }
        val e = l.copy(id = next)
        commit {
            next++
            lines += e
        }
        return e
    }

    @Synchronized
    fun dap(log: Long): Long? = dap[log]

    @Synchronized
    fun addLog(log: Long, entries: List<PlayEntry>, enabled: Set<String>, zone: ZoneId, nowS: Long, note: (String) -> Unit = {}): Long? {
        if (enabled.isEmpty() || entries.isEmpty()) return null
        enabled.forEach { cursor(it) }
        val at = dap[log] ?: 0L
        val fresh = entries.filter { it.seq > at }
        val upTo = entries.maxOf { it.seq }
        if (fresh.isEmpty()) return upTo
        val add = ArrayList<Listen>()
        var id = next
        for (e in fresh.sortedBy { it.seq }) {
            val l = Listen.of(e, zone) ?: continue
            if (l.startS - nowS > Listen.FUTURE_S) {
                note("playlog: seq ${e.seq} starts ${l.startS - nowS} s ahead, skipped")
                continue
            }
            add += l.copy(id = id++)
        }
        commit {
            next = id
            lines += add
            dap.remove(log)
            dap[log] = maxOf(at, fresh.maxOf { it.seq })
            while (dap.size > DAP_KEEP) dap.remove(dap.keys.first())
        }
        return upTo
    }

    @Synchronized
    fun cursor(service: String): Long {
        val f = File(dir, "$SENT.$service")
        f.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()?.let { return it }
        return start().also { save(f, it.toString()) }
    }

    private fun start() = (lines.firstOrNull()?.id ?: next) - 1

    @Synchronized
    fun after(service: String): List<Listen> {
        val c = cursor(service)
        return lines.filter { it.id > c }
    }

    @Synchronized
    fun sent(service: String, id: Long) {
        save(File(dir, "$SENT.$service"), id.toString())
    }

    @Synchronized
    fun enable(service: String) {
        val stored = File(dir, "$SENT.$service").takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
        sent(service, maxOf(start(), stored ?: start()))
    }

    @Synchronized
    fun pending(enabled: Set<String>): Int {
        if (enabled.isEmpty()) return 0
        val low = enabled.minOf { cursor(it) }
        return lines.count { it.id > low }
    }

    @Synchronized
    fun compact(enabled: Set<String>) {
        if (enabled.isEmpty()) return
        val low = enabled.minOf { cursor(it) }
        if (lines.none { it.id <= low }) return
        val kept = lines.filter { it.id > low }
        commit {
            lines.clear()
            lines += kept
        }
    }

    private fun commit(change: () -> Unit) {
        val n = next
        val l = ArrayList(lines)
        val d = LinkedHashMap(dap)
        change()
        try {
            write()
        } catch (e: IOException) {
            next = n
            lines.clear()
            lines += l
            dap.clear()
            dap.putAll(d)
            throw e
        }
    }

    private fun write() = save(File(dir, QUEUE), buildString {
        append("next\t").append(next).append('\n')
        dap.forEach { (log, seq) -> append(DAP).append('\t').append(java.lang.Long.toHexString(log)).append('\t').append(seq).append('\n') }
        lines.forEach { append(it.line()).append('\n') }
    })

    private fun save(f: File, text: String) {
        val tmp = File(f.path + ".tmp")
        FileOutputStream(tmp).use { o ->
            o.write(text.toByteArray())
            o.fd.sync()
        }
        if (!tmp.renameTo(f)) throw IOException("rename ${tmp.name}")
        dirSync(dir)
    }

    companion object {
        const val QUEUE = "queue.tsv"
        const val SENT = "sent"
        const val DAP = "dap"
        const val DAP_KEEP = 8
    }
}
