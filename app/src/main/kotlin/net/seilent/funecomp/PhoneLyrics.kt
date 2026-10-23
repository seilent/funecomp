package net.seilent.funecomp

import net.seilent.funecomp.artist.Lrclib
import net.seilent.funecomp.artist.LyricChain
import net.seilent.funecomp.artist.LyricSources
import net.seilent.funecomp.proto.LyrLine
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.state.Lrc
import net.seilent.funecomp.state.LyricsFetch
import net.seilent.funecomp.state.PhoneKey
import java.util.zip.CRC32

class PhoneLyrics(
    private val run: (() -> Unit) -> Boolean,
    private val chain: (Ask) -> Found,
    private val poke: () -> Unit,
    private val log: (String) -> Unit = {},
) {
    data class Ask(val key: PhoneKey, val durationMs: Long, val artists: List<String>)

    sealed interface Found {
        data class Copy(val text: String, val source: String) : Found
        data object Miss : Found
        data object Unavailable : Found
    }

    enum class Kind { Pending, Copy, Miss, Unavailable }

    data class Entry(
        val kind: Kind,
        val copy: Lrc.Parsed? = null,
        val crc: Long? = null,
        val source: String? = null,
        val askedMs: Long = 0,
        val reasks: Int = 0,
    ) {
        val has: Boolean get() = kind == Kind.Copy

        val follow: Boolean? get() = when (kind) {
            Kind.Pending -> null
            Kind.Copy -> copy?.have == Lrc.Have.Synced
            else -> false
        }
    }

    private val lock = Any()
    private val entries = lru<PhoneKey, Entry>(COPIES)
    private val offsets = lru<PhoneKey, Int>(OFFSETS)
    private var seen: PhoneKey? = null
    private var seenAt = 0L
    private var queued: Ask? = null
    private var posted = false
    private var gen = 0
    private var served: Triple<PhoneKey, Int, LyricsFetch.Copy>? = null

    @Volatile var mode = false
    var settled: PhoneKey? = null
        get() = synchronized(lock) { field }
        private set

    fun entry(key: PhoneKey): Entry? = synchronized(lock) { entries[key] }

    fun offset(key: PhoneKey): Int = synchronized(lock) { offsets[key] ?: 0 }

    fun offset(key: PhoneKey, ms: Int) = synchronized(lock) {
        if (ms == 0) offsets.remove(key) else offsets[key] = ms
    }

    fun show(): LyricsFetch.Show = synchronized(lock) {
        val key = settled?.takeIf { it == seen } ?: return LyricsFetch.Show.Off
        val e = entries[key]
        LyricsFetch.Show.Phone(key, e?.follow, e?.takeIf { it.has }?.let { copy(key, it) })
    }

    private fun copy(key: PhoneKey, e: Entry): LyricsFetch.Copy {
        val shift = offsets[key] ?: 0
        served?.let { (k, o, c) -> if (k == key && o == shift && c.crc == e.crc) return c }
        val p = e.copy!!
        val c = if (p.have == Lrc.Have.Synced) {
            LyricsFetch.Copy(e.crc!!, Enums.LyricsKind.synced, Lrc.shift(p.lines, shift.toLong()).map { LyrLine(it.ms, Lrc.cut(it.text)) })
        } else {
            LyricsFetch.Copy(e.crc!!, Enums.LyricsKind.plain, p.plain.map { LyrLine(null, Lrc.cut(it)) })
        }
        served = Triple(key, shift, c)
        return c
    }

    fun see(s: State, now: Long, shown: Boolean): Long? = synchronized(lock) {
        val key = PhoneKey.of(s)
        if (key == null) {
            seen = null
            settled = null
            return null
        }
        if (key != seen) {
            seen = key
            seenAt = now
        }
        val wait = SETTLE_MS - (now - seenAt)
        if (wait > 0) return wait
        if (settled != key) {
            settled = key
            if (entries[key]?.kind == Kind.Unavailable) entries.remove(key)
        }
        if (shown) want(Ask(key, s.durationMs ?: 0, s.artists))
        return null
    }

    private fun want(a: Ask) {
        val e = entries[a.key]
        val again = e != null && e.kind == Kind.Miss && a.durationMs > 0 && a.durationMs != e.askedMs && e.reasks < REASKS
        if (e != null && !again) return
        queued?.let { entries.remove(it.key) }
        entries[a.key] = Entry(Kind.Pending, askedMs = a.durationMs, reasks = if (e == null) 0 else e.reasks + 1)
        queued = a
        if (posted) return
        posted = true
        if (!run { work() }) {
            posted = false
            queued = null
            entries[a.key] = Entry(Kind.Unavailable)
        }
    }

    private fun work() {
        val (a, g) = synchronized(lock) {
            posted = false
            (queued ?: return).also { queued = null } to gen
        }
        val got = try {
            chain(a)
        } catch (t: Throwable) {
            if (t is VirtualMachineError) throw t
            log("phone lyrics ${a.key.title}: $t")
            Found.Unavailable
        }
        val kept = synchronized(lock) {
            val prev = entries[a.key]
            if (g != gen) {
                if (prev?.kind == Kind.Pending && queued?.key != a.key) entries.remove(a.key)
                return@synchronized false
            }
            entries[a.key] = when (got) {
                is Found.Copy -> stored(got, a, prev)
                Found.Miss -> Entry(Kind.Miss, askedMs = a.durationMs, reasks = prev?.reasks ?: 0)
                Found.Unavailable -> Entry(Kind.Unavailable)
            }
            true
        }
        log("phone lyrics ${a.key.title} -> ${if (kept) entry(a.key)?.kind?.name?.lowercase() else "dropped"}")
        poke()
    }

    private fun stored(got: Found.Copy, a: Ask, prev: Entry?): Entry {
        val body = got.text.toByteArray(Charsets.UTF_8)
        val parsed = Lrc.parse(body)
        if (parsed.have == Lrc.Have.None) return Entry(Kind.Miss, askedMs = a.durationMs, reasks = prev?.reasks ?: 0)
        return Entry(Kind.Copy, parsed, CRC32().apply { update(body) }.value, got.source, a.durationMs, prev?.reasks ?: 0)
    }

    fun retry() {
        synchronized(lock) { entries.values.removeAll { it.kind == Kind.Unavailable } }
        poke()
    }

    fun sources(off: String?) {
        synchronized(lock) {
            gen++
            entries.values.removeAll { it.kind == Kind.Miss || it.kind == Kind.Unavailable || (it.kind == Kind.Copy && it.source == off) }
        }
        poke()
    }

    internal fun clear() = synchronized(lock) {
        gen++
        entries.clear()
        offsets.clear()
        served = null
        mode = false
        seen = null
        settled = null
        queued = null
        posted = false
    }

    companion object {
        const val SETTLE_MS = 1500L
        const val COPIES = 16
        const val OFFSETS = 64
        const val REASKS = 2

        private fun <K, V> lru(cap: Int) = object : LinkedHashMap<K, V>(cap, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>) = size > cap
        }

        fun ask(
            a: Ask,
            sources: LyricSources,
            lrclib: (Lrclib.Query) -> Lrclib.Found?,
            netease: (Lrclib.Query) -> Lrclib.Found?,
            open: (String) -> Boolean,
            result: (String, Boolean) -> Unit,
            log: (String) -> Unit = {},
        ): Found {
            if (sources.ordered.isEmpty() || sources.ordered.all(open)) return Found.Unavailable
            val known = a.durationMs.takeIf { it > 0 }
            val unknown = { id: String -> id == LyricSources.NETEASE && known == null }
            val fit = { f: Lrclib.Found? -> f?.takeIf { it.text.toByteArray(Charsets.UTF_8).size <= Lrc.CAP } }
            val c = LyricChain(sources, { fit(lrclib(it)) }, { fit(netease(it)) }) { open(it) || unknown(it) }
                .get(Lrclib.Query(a.key.title, a.key.artist, null, known, a.artists))
            for ((id, o) in c.outcome) {
                when (o) {
                    LyricChain.Outcome.Answered -> result(id, false)
                    is LyricChain.Outcome.Failed -> {
                        log("phone lyrics $id ${a.key.title}: ${o.e}")
                        result(id, true)
                    }
                    else -> {}
                }
            }
            c.found?.let { return Found.Copy(it.text, it.source) }
            val asked = c.outcome.all { (id, o) -> o == LyricChain.Outcome.Answered || (o == LyricChain.Outcome.Skipped && unknown(id)) }
            return if (asked) Found.Miss else Found.Unavailable
        }
    }
}
