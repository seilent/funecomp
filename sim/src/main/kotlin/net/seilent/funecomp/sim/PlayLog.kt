package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.PageInfo
import net.seilent.funecomp.proto.PlayEntry
import net.seilent.funecomp.proto.PlayLogReply
import net.seilent.funecomp.proto.gen.Err
import java.io.File

class PlayLog(id: Long, private val cap: Int, seed: List<PlayEntry> = emptyList()) {
    var id = id
        private set
    private val entries = ArrayList<PlayEntry>()
    private var next = 1L

    init {
        seed.forEach { append(it) }
    }

    val held: List<PlayEntry> get() = entries.toList()

    val newest: Long? get() = entries.lastOrNull()?.seq

    fun append(e: PlayEntry): PlayEntry {
        val s = e.copy(seq = next++)
        entries += s
        if (entries.size > cap) entries.subList(0, maxOf(1, cap / 10)).clear()
        return s
    }

    fun renew(to: Long) {
        id = to
        val kept = entries.toList()
        entries.clear()
        next = 1
        kept.forEach { append(it) }
    }

    fun page(after: Long, count: Long?): PlayLogReply {
        val above = entries.filter { it.seq > after }.let { l -> count?.takeIf { it > 0 }?.let { l.take(it.toInt().coerceAtMost(l.size)) } ?: l }
        val fixed = PlayLogReply(id, newest ?: 0, true, emptyList(), entries.firstOrNull()?.seq ?: 0).encode().size + 8
        val n = PageInfo.fill(above.map { it.size }, 0, fixed)
        val sent = above.take(n)
        val more = entries.any { it.seq > (sent.lastOrNull()?.seq ?: after) }
        return PlayLogReply(id, newest, more, sent, entries.firstOrNull()?.seq)
    }

    fun ack(log: Long?, upTo: Long?): Int? {
        if (log == null || upTo == null) return Err.badValue
        if (log != id) return Err.staleRev
        val top = newest ?: return if (upTo < next) null else Err.badValue
        if (upTo > top) return Err.badValue
        entries.removeAll { it.seq <= upTo }
        return null
    }

    companion object {
        const val ID = 0x51a0_0000_0000_0001L
        const val CAP = 1000

        fun parse(f: File): List<PlayEntry> = f.readLines().filter { it.isNotBlank() }.map { line ->
            val p = line.split('\t')
            require(p.size == 13) { "${f.name}: needs 13 fields (startLocalS, playedMs, durationMs, track, title, artist, artists, mbids, album, albumArtist, trackNo, zoneS, liveSeen): $line" }
            fun list(s: String) = if (s.isEmpty()) emptyList() else s.split(';')
            PlayEntry(
                0,
                p[0].toLongOrNull(),
                p[1].toLongOrNull(),
                p[2].toLongOrNull(),
                p[3].toULongOrNull()?.toLong(),
                p[4].ifEmpty { null },
                p[5].ifEmpty { null },
                list(p[6]),
                list(p[7]),
                p[8].ifEmpty { null },
                p[9].ifEmpty { null },
                p[10].toIntOrNull(),
                p[11].toIntOrNull(),
                p[12].takeIf { it == "1" }?.let { true },
            )
        }
    }
}
