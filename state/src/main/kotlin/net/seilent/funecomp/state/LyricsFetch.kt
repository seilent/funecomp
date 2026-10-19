package net.seilent.funecomp.state

import net.seilent.funecomp.proto.LyrLine
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Proto

class LyricsFetch {
    data class Copy(val crc: Long, val kind: Int, val lines: List<LyrLine>)

    data class Want(val track: Long, val crc: Long, val snapRev: Long?, val have: Long?) {
        val key: Key get() = Key(track, crc, snapRev)
    }

    data class Key(val track: Long, val crc: Long, val snapRev: Long?)

    sealed interface Show {
        data object Off : Show
        data object Blank : Show
        data object None : Show
        data class Lines(val track: Long, val copy: Copy) : Show
    }

    data class Decision(val show: Show, val want: Want?)

    sealed interface Got {
        data class Lines(val copy: Copy) : Got
        data class None(val crc: Long) : Got
        data class Have(val crc: Long) : Got
        data class Failed(val code: Int?) : Got
    }

    private enum class Mark { NONE, FAILED }

    private fun <K, V> bounded(n: Int) = object : LinkedHashMap<K, V>(n, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > n
    }

    private val cache = bounded<Long, Copy>(KEEP)
    private val marks = bounded<Key, Mark>(MARKS)
    private val busy = bounded<Key, Int>(MARKS)
    private val retryAt = bounded<Key, Long>(MARKS)
    private var snap: Long? = null

    fun session() {
        cache.clear()
        marks.clear()
        busy.clear()
        retryAt.clear()
    }

    fun opened() {
        marks.values.removeAll { it == Mark.FAILED }
    }

    fun dropped(track: Long) {
        cache.remove(track)
    }

    fun decide(d: DeviceState, now: Long): Decision {
        val s = d.playback.state
        val track = s.track
        if (!d.has(GROUP) || track == null) return Decision(Show.Off, null)
        if (d.lib.snap != snap) {
            snap = d.lib.snap
            cache.clear()
        }
        if (s.lyrics == false && s.lyricsPlain == false) {
            cache.remove(track)
            return Decision(Show.None, null)
        }
        val crc = s.lyricsCrc
        if ((s.lyrics != true && s.lyricsPlain != true) || crc == null) return Decision(Show.Blank, null)
        val copy = cache[track]
        if (copy?.crc == crc) return Decision(Show.Lines(track, copy), null)
        val want = Want(track, crc, snap, copy?.crc)
        return when (marks[want.key]) {
            Mark.NONE -> Decision(Show.None, null)
            Mark.FAILED -> Decision(Show.Blank, null)
            null -> Decision(Show.Blank, want.takeIf { (retryAt[want.key] ?: 0L) <= now })
        }
    }

    fun done(w: Want, got: Got, now: Long): Long? {
        val key = w.key
        if (got !is Got.Failed || got.code != Err.busy) {
            busy.remove(key)
            retryAt.remove(key)
        }
        val crc = when (got) {
            is Got.Lines -> got.copy.crc
            is Got.Have -> got.crc
            is Got.None -> got.crc
            is Got.Failed -> null
        }
        if (crc != null && crc != w.crc) marks[key] = Mark.FAILED
        when (got) {
            is Got.Lines -> cache[w.track] = got.copy
            is Got.Have -> {}
            is Got.None -> marks[Key(w.track, got.crc, w.snapRev)] = Mark.NONE
            is Got.Failed -> when (got.code) {
                Err.notFound -> marks[key] = Mark.NONE
                Err.busy -> {
                    val n = busy[key] ?: 0
                    if (n >= Proto.backoffMs.size) {
                        busy.remove(key)
                        retryAt.remove(key)
                        marks[key] = Mark.FAILED
                        return null
                    }
                    busy[key] = n + 1
                    val ms = Proto.backoffMs[n]
                    retryAt[key] = now + ms
                    return ms
                }
                else -> marks[key] = Mark.FAILED
            }
        }
        return null
    }

    companion object {
        const val GROUP = "lyricsGet"
        private const val KEEP = 8
        const val MARKS = 64

        fun has(d: DeviceState): Boolean {
            val s = d.playback.state
            return d.has(GROUP) && s.track != null && (s.lyrics == true || s.lyricsPlain == true)
        }

        fun index(lines: List<LyrLine>, ms: Long): Int {
            var lo = 0
            var hi = lines.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if ((lines[mid].ms ?: 0) <= ms) lo = mid + 1 else hi = mid
            }
            return lo - 1
        }

        fun dueMs(lines: List<LyrLine>, index: Int, ms: Long): Long? {
            val next = lines.getOrNull(index + 1)?.ms ?: return null
            return maxOf(1L, next - ms)
        }
    }
}
