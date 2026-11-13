package net.seilent.funecomp.artist

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Collections
import java.util.IdentityHashMap
import java.util.TreeMap
import kotlin.math.abs

class Played(val s: Long, val artist: String, val title: String)

sealed class Read {
    class Page(val plays: List<Played>, val full: Boolean, val maxAgeS: Long? = null) : Read()

    class Retry(val counted: Boolean) : Read()

    class Off(val why: String) : Read()
}

fun same(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)

fun maxAge(headers: Map<String, String>): Long? = headers["cache-control"]?.let { Regex("max-age=(\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull() }

class OwnRecord(private val dir: File, private val service: String, private val dirSync: (File) -> Unit, private val log: (String) -> Unit) {
    class Line(val id: Long, val played: Played)

    private val file = File(dir, "$OWN.$service")
    private val keys = HashSet<Pair<Long, Long>>()
    var from = 0L
        private set
    val lines = ArrayList<Line>()
    private var cursor = 0L
    private var broken = false

    val shifts: Boolean get() = !broken

    fun load(sent: Long?, nowS: Long) {
        if (sent == null && !file.isFile) return
        var doubt = if (file.isFile) null else "no file"
        var seen = false
        if (doubt == null) {
            val all = try {
                file.readLines()
            } catch (e: IOException) {
                doubt = "unreadable"
                emptyList()
            }
            for ((i, l) in all.withIndex()) {
                val p = l.split('\t')
                val a = p.getOrNull(1)?.toLongOrNull()
                val b = p.getOrNull(2)?.toLongOrNull()
                when {
                    i == 0 && p.size == 2 && p[0] == "from" && a != null -> from = a
                    i > 0 && p.size == 5 && p[0] == "e" && a != null && b != null -> keep(Line(a, Played(b, p[3], p[4])))
                    i > 0 && p.size == 2 && p[0] == "c" && a != null -> {
                        cursor = a
                        seen = true
                    }
                    else -> if (doubt == null) doubt = "bad line"
                }
            }
            if (doubt == null && sent != null && (if (seen) cursor else 0L) < sent) doubt = "sent past the record"
        }
        if (doubt == null || sent == null) return
        from = maxOf(from, nowS)
        cursor = sent
        log("scrobble $service record: $doubt, shift from now")
        try {
            rewrite(nowS)
        } catch (e: IOException) {
            log("scrobble $service record: write failed, shift off until a write")
            broken = true
        }
    }

    fun sent(batch: List<Listen>, nowS: Long) = add(batch.map { Line(it.id, Played(it.startS, it.artist, it.title)) }, nowS)

    fun add(add: List<Line>, nowS: Long) {
        val fresh = add.filter { (it.id to it.played.s) !in keys }
        if (fresh.isEmpty()) return
        fresh.forEach { keep(it) }
        write(fresh.joinToString("") { line(it) + "\n" }, nowS)
    }

    fun moved(id: Long, nowS: Long) {
        cursor = id
        write("c\t$id\n", nowS)
    }

    private fun keep(l: Line) {
        lines += l
        keys += l.id to l.played.s
    }

    private fun line(l: Line) = "e\t${l.id}\t${l.played.s}\t${Listen.clean(l.played.artist)}\t${Listen.clean(l.played.title)}"

    private fun write(text: String, nowS: Long) {
        try {
            val floor = nowS - FLOOR_S
            if (broken || lines.size > CAP || lines.count { it.played.s < floor } * 2 > lines.size) {
                if (broken) from = maxOf(from, nowS)
                rewrite(nowS)
            } else {
                FileOutputStream(file, true).use { o ->
                    o.write(text.toByteArray())
                    o.fd.sync()
                }
            }
            broken = false
        } catch (e: IOException) {
            if (!broken) log("scrobble $service record: write failed, shift off until a write")
            broken = true
        }
    }

    private fun rewrite(nowS: Long) {
        val floor = nowS - FLOOR_S
        if (lines.any { it.played.s < floor }) from = maxOf(from, floor)
        val kept = lines.filter { it.played.s >= floor }.sortedBy { it.played.s }.takeLast(CAP)
        if (kept.size < lines.count { it.played.s >= floor }) from = maxOf(from, kept.first().played.s)
        lines.clear()
        keys.clear()
        kept.forEach { keep(it) }
        dir.mkdirs()
        syncedWrite(file, buildString {
            append("from\t").append(from).append('\n')
            lines.forEach { append(line(it)).append('\n') }
            append("c\t").append(cursor).append('\n')
        }, dir, dirSync)
    }

    companion object {
        const val OWN = "own"
        const val FLOOR_S = 14L * 24 * 3600 + 15 * 3600
        const val CAP = 20000
    }
}

class ReadBound(private val dir: File, service: String, private val dirSync: (File) -> Unit) {
    private val file = File(dir, "$CHECK.$service")
    private var mem: String? = null

    private fun line(): List<String>? = (mem ?: file.takeIf { it.isFile }?.readText())?.trim()?.split('\t')?.takeIf { it.size == 3 }

    fun failed(head: Long, nowMs: Long): Boolean {
        val p = line()?.takeIf { it[0].toLongOrNull() == head }
        val count = (p?.get(1)?.toIntOrNull() ?: 0) + 1
        val first = p?.get(2)?.toLongOrNull() ?: nowMs
        val text = "$head\t$count\t$first\n"
        mem = text
        try {
            syncedWrite(file, text, dir, dirSync)
        } catch (_: IOException) {
        }
        return count >= TRIES && nowMs - first >= SPAN_MS
    }

    fun passed(id: Long) {
        val head = line()?.get(0)?.toLongOrNull() ?: return
        if (head > id) return
        mem = null
        file.delete()
    }

    companion object {
        const val CHECK = "check"
        const val TRIES = 3
        const val SPAN_MS = 3600_000L
    }
}

class AccountCheck(private val service: String, private val log: (String) -> Unit) {
    class Verdict(val skip: Set<Long>, val cut: Int, val result: Sent?)

    private class Cluster(val listens: List<Listen>) {
        val lo = listens.first().startS - EDGE_S
        val hi = listens.last().startS + EDGE_S
    }

    private sealed class Fill {
        data object Done : Fill()

        data object Capped : Fill()

        data object Yield : Fill()

        class Retry(val counted: Boolean) : Fill()

        class Off(val why: String) : Fill()
    }

    private val covered = TreeMap<Long, Long>()
    private val reads = ArrayList<Played>()
    private var untilMs = Long.MAX_VALUE
    private var active = false
    private var resumed = false
    private val asked = HashMap<Pair<Long, Long>, Int>()
    private val claims = HashMap<Long, Played>()
    private val uncovered = HashMap<Long, Int>()
    var off = false
        private set

    fun session() {
        off = false
    }

    fun done(nowMs: Long) {
        active = false
        resumed = false
        asked.clear()
        claims.clear()
        expire(nowMs)
    }

    private fun expire(nowMs: Long) {
        if (untilMs > nowMs) return
        covered.clear()
        reads.clear()
        untilMs = Long.MAX_VALUE
    }

    fun check(
        window: List<Listen>,
        sendable: (Listen) -> Boolean,
        user: String,
        read: (Long, Long, Int) -> Read,
        own: OwnRecord,
        records: List<OwnRecord>,
        cursor: Long,
        zone: ZoneId,
        nowMs: Long,
        bound: ReadBound,
        stop: () -> Boolean,
    ): Verdict {
        if (!active) expire(nowMs)
        active = true
        if (!resumed) asked.clear()
        resumed = false
        val exposed = window.filter { it.exposed && sendable(it) }.sortedBy { it.startS }
        val all = Verdict(emptySet(), window.size, null)
        if (exposed.isEmpty() || off) return all
        if (user.isBlank()) return all.also { stopFor("no user") }
        val nowS = nowMs / 1000
        val clusters = clusters(exposed)
        val at = window.withIndex().associate { it.value.id to it.index }
        window.forEach { claims.remove(it.id) }
        val unchecked = HashSet<Long>()
        var fail = clusters.size
        var result: Sent? = null

        fun failed(i: Int, f: Fill): Verdict? {
            when (f) {
                Fill.Yield -> {
                    resumed = true
                    return Verdict(emptySet(), 0, Sent.YIELD)
                }
                is Fill.Off -> return all.also { stopFor(f.why) }
                is Fill.Retry -> {
                    val c = clusters[i]
                    if (f.counted && bound.failed(c.listens.minBy { at.getValue(it.id) }.id, nowMs)) {
                        unchecked += c.listens.map { it.id }
                        log("scrobble $service check ${c.listens.size} unchecked after read retries")
                    } else {
                        fail = i
                        result = Sent.RETRY
                    }
                }
                else -> {}
            }
            return null
        }

        for (i in clusters.indices) {
            failed(i, fill(clusters[i].lo, clusters[i].hi, read, asked, stop, nowMs))?.let { return it }
            if (fail < clusters.size) break
        }

        val decided = clusters.take(fail).filter { c -> c.listens.none { it.id in unchecked } }
        val records2 = records.flatMap { it.lines }
        val pool = reads + own.lines.filter { it.id <= cursor }.map { it.played }
        val taken = Collections.newSetFromMap(IdentityHashMap<Played, Boolean>()).apply { addAll(claims.values) }
        for (l in decided.flatMap { it.listens }.sortedBy { it.startS }) {
            val near = pool.filter { abs(it.s - l.startS) <= 1 && it !in taken }
            val e = near.firstOrNull { same(it.artist, l.artist) && same(it.title, l.title) } ?: near.firstOrNull { same(it.artist, l.artist) || same(it.title, l.title) } ?: continue
            claims[l.id] = e
            taken += e
        }

        val open = HashSet<Long>()
        for ((i, c) in clusters.withIndex()) {
            if (i >= fail || c !in decided) continue
            if (!covers(c.lo, c.hi)) {
                open += c.listens.filter { it.id !in claims }.map { it.id }
                continue
            }
            if (c.listens.any { it.id in claims }) continue
            val counted = c.listens.filter { l -> c.listens.count { same(it.artist, l.artist) && same(it.title, l.title) } == 1 }
            if (counted.size < 2 || !own.shifts) continue
            val utc = { l: Listen -> listOfNotNull(l.z?.toLong()?.takeIf { it != 0L }) }
            val local = { l: Listen -> zoneShifts(l, zone) }
            val ranges = counted.flatMap { utc(it) + local(it) }.distinct().mapNotNull { sh -> (c.lo + sh to minOf(c.hi + sh, nowS + EDGE_S)).takeIf { it.first <= it.second } }
            val from = maxOf(own.from, records.maxOfOrNull { it.from } ?: 0L)
            if (ranges.isEmpty() || ranges.any { it.first < from }) continue
            var short = false
            for ((lo, hi) in ranges) {
                val f = fill(lo, hi, read, asked, stop, nowMs)
                failed(i, f)?.let { return it }
                if (f is Fill.Retry) break
                if (f == Fill.Capped) short = true
            }
            if (fail <= i) break
            if (c.listens.any { it.id in unchecked }) continue
            if (short) {
                open += c.listens.map { it.id }
                continue
            }
            val a = shifted(counted, utc, records2, taken)
            val b = shifted(counted, local, records2, taken)
            val pick = if (a == b) a else if (a.size == b.size) null else if (a.size > b.size) a else b
            if (pick == null || pick.size < 2) continue
            pick.forEach { (id, e) ->
                claims[id] = e
                taken += e
            }
        }

        val late = HashSet<Long>()
        for (id in open) {
            if (id in claims) continue
            val n = (uncovered[id] ?: 0) + 1
            if (n >= WINDOWS) late += id else uncovered[id] = n
        }
        if (late.isNotEmpty()) {
            late.forEach { uncovered.remove(it) }
            log("scrobble $service check ${late.size} unchecked after $WINDOWS windows")
        }
        val stops = (open - late - claims.keys).map { at.getValue(it) } + clusters.drop(fail).flatMap { c -> c.listens.map { at.getValue(it.id) } }
        val cut = stops.minOrNull() ?: window.size
        val head = window.take(cut).map { it.id }.toSet()
        val skip = claims.keys.filter { it in head }.toSet()
        val checked = exposed.count { it.id in head && it.id !in unchecked && it.id !in late }
        head.forEach { uncovered.remove(it) }
        if (checked > 0) log("scrobble $service check ${skip.size} of $checked on the account")
        own.add(skip.map { OwnRecord.Line(it, claims.getValue(it)) }, nowS)
        return Verdict(skip, cut, result)
    }

    private fun shifted(counted: List<Listen>, shifts: (Listen) -> List<Long>, records: List<OwnRecord.Line>, taken: Set<Played>): Map<Long, Played> {
        val got = LinkedHashMap<Long, Played>()
        val used = Collections.newSetFromMap(IdentityHashMap<Played, Boolean>())
        for (l in counted) {
            for (sh in shifts(l)) {
                val e = reads.firstOrNull { e ->
                    e !in taken && e !in used && abs(e.s - (l.startS + sh)) <= 1 && same(e.artist, l.artist) && same(e.title, l.title) &&
                        records.none { it.id != l.id && abs(it.played.s - e.s) <= 1 && same(it.played.artist, e.artist) && same(it.played.title, e.title) }
                } ?: continue
                got[l.id] = e
                used += e
                break
            }
        }
        return got
    }

    private fun stopFor(why: String) {
        off = true
        log("scrobble $service check off this session: $why")
    }

    private fun clusters(exposed: List<Listen>): List<Cluster> {
        val out = ArrayList<Cluster>()
        var run = ArrayList<Listen>()
        for (l in exposed) {
            if (run.isNotEmpty() && l.startS - run.last().startS > GAP_S) {
                out += Cluster(run)
                run = ArrayList()
            }
            run += l
        }
        if (run.isNotEmpty()) out += Cluster(run)
        return out
    }

    private fun fill(lo: Long, hi: Long, read: (Long, Long, Int) -> Read, asked: HashMap<Pair<Long, Long>, Int>, stop: () -> Boolean, nowMs: Long): Fill {
        while (true) {
            val (a, b) = gap(lo, hi) ?: return Fill.Done
            val n = asked[lo to hi] ?: 0
            if (n >= PAGES) return Fill.Capped
            if (stop()) return Fill.Yield
            asked[lo to hi] = n + 1
            when (val r = read(a, b + 1, n)) {
                is Read.Retry -> return Fill.Retry(r.counted)
                is Read.Off -> return Fill.Off(r.why)
                is Read.Page -> {
                    val low = r.plays.minOfOrNull { it.s }
                    val end = if (!r.full || low == null || low < a) a else low + 1
                    if (end <= b) cover(end, b)
                    reads += r.plays.filter { it.s in end..b }
                    untilMs = minOf(untilMs, nowMs + (r.maxAgeS ?: 0L) * 1000)
                }
            }
        }
    }

    private fun covers(lo: Long, hi: Long) = gap(lo, hi) == null

    private fun gap(lo: Long, hi: Long): Pair<Long, Long>? {
        var top = hi
        while (top >= lo) {
            val e = covered.floorEntry(top)
            if (e == null || e.value < top) return maxOf(lo, (e?.value ?: (lo - 1)) + 1) to top
            top = e.key - 1
        }
        return null
    }

    private fun cover(a: Long, b: Long) {
        var lo = a
        var hi = b
        covered.floorEntry(lo)?.takeIf { it.value >= lo - 1 }?.let {
            lo = it.key
            hi = maxOf(hi, it.value)
            covered.remove(it.key)
        }
        while (true) {
            val e = covered.ceilingEntry(lo) ?: break
            if (e.key > hi + 1) break
            hi = maxOf(hi, e.value)
            covered.remove(e.key)
        }
        covered[lo] = hi
    }

    companion object {
        const val GAP_S = 3600L
        const val EDGE_S = 60L
        const val PAGES = 5
        const val WINDOWS = 3

        fun zoneShifts(l: Listen, zone: ZoneId): List<Long> {
            val z = l.z ?: return emptyList()
            val local = LocalDateTime.ofEpochSecond(l.startS + z, 0, ZoneOffset.UTC)
            return zone.rules.getValidOffsets(local).map { z.toLong() - it.totalSeconds }.filter { it != 0L }.distinct()
        }
    }
}
