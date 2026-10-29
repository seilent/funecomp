package net.seilent.funecomp.art

import net.seilent.funecomp.artist.Art
import net.seilent.funecomp.artist.ArtBlocks
import net.seilent.funecomp.artist.ArtPusher
import net.seilent.funecomp.artist.FullPush
import net.seilent.funecomp.artist.Names
import net.seilent.funecomp.artist.Sweep
import net.seilent.funecomp.link.Session
import net.seilent.funecomp.proto.ImgEntry
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.state.ArtKey
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.state.LibKey
import java.io.IOException

class Forward(
    private val device: () -> DeviceState,
    private val art: (String, String?, Int, Int, Boolean) -> Art?,
    private val gate: () -> Boolean,
    private val sweeping: () -> Boolean,
    private val retagging: () -> Boolean,
    private val libGet: (Session, Long, Long?) -> Session.Result,
    private val breaker: Sweep.Breaker,
    private val online: () -> Boolean = { true },
    private val progress: () -> Unit = {},
    private val marks: Marks? = null,
    private val blocks: Blocks? = null,
    private val cached: (String, String?) -> Boolean = { _, _ -> true },
    private val holding: () -> Boolean = { true },
    private val cardDown: () -> Boolean = { false },
    private val pending: Pending? = null,
    private val phoneGate: () -> Boolean = { false },
    private val upgrades: Upgrades? = null,
    private val looking: () -> Boolean = { true },
    private val full: FullPush? = null,
    private val refetch: Refetch? = null,
    private val log: (String) -> Unit = {},
) {
    interface Upgrades {
        fun due(): List<Pair<String, String?>>
        fun run(name: String, mbid: String?, w: Int, h: Int): Upgraded?
        fun open(): Boolean
        fun at(key: String): Long?
    }

    class Upgraded(val replaced: Boolean, val bytes: Long, val key: String = "", val at: Long = 0)

    class Refetch(
        val data: () -> Boolean,
        val rearm: () -> Boolean,
        val unmetered: () -> Boolean,
        val epoch: () -> Int,
        val playing: () -> Boolean,
        val gapMs: Long = Proto.imgRefetchGapMs.toLong(),
        val clock: () -> Long = { System.nanoTime() / 1_000_000 },
        val sleep: (Long) -> Unit = { Thread.sleep(it) },
    )

    private class Item(val name: String, val mbid: String?, val entry: ImgEntry)

    private class Queue(val session: Session, val epoch: Int, val items: MutableList<Item>, val room: Long?) {
        val total = items.size
        var done = 0
        var bytes = 0L
        var lastAt: Long? = null
        var lastLen = 0L
        var ladder = 0
        val tries = HashMap<String, Int>()
    }

    class Blocks(
        val records: ArtBlocks,
        val device: () -> String?,
        val keyOf: (String, String?) -> String,
        val hit: (String) -> Boolean = { false },
        val open: () -> Boolean = { true },
    )

    interface Marks {
        fun begin()
        fun mark(): Any
        fun holds(mark: Any): Boolean
    }

    private class Known(val session: Session, val rev: Long?, val names: MutableSet<String>, val budget: Sweep.Budget, val untagged: MutableSet<String>, val orphans: MutableSet<String>, val libOnly: MutableSet<String>) {
        var swept: Any? = null
        var tagged = false
    }

    private class Listed(val session: Session, val listing: ArtPusher.Listing, val records: Set<Pair<String, Long>>)

    private var known: Known? = null
    private var queue: Queue? = null
    private var listed: Listed? = null
    private var libSet: Triple<Session, Long?, Set<String>?>? = null
    private var absent: Pair<Session, MutableSet<String>>? = null
    private val sizes = HashMap<String, Int>()

    fun run(s: Session, keep: () -> Boolean): ArtPusher.Report {
        libSet = null
        if (cardDown()) return ArtPusher.Report(ArtPusher.Stop.GATE)
        pending?.let { p -> phone(s, keep, p)?.let { return it } }
        val pusher = ArtPusher(s, { n, m, w, h -> lookup(n, m, w, h, false) }, full, keep, gate)
        val library = ArtPusher(s, { n, m, w, h -> lookup(n, m, w, h, true) }, full, keep, gate)
        blocks?.let { b -> b.device()?.let { dev -> unblocked(s, library, keep, b, dev)?.let { return it } } }
        val st = device().playback.state
        val listed = cachedLib(s)
        val front = { n: String -> listed?.let { if (ArtKey.fold(n) in it) Enums.ImgOrigin.library else Enums.ImgOrigin.phone } }
        val curOrigin = { n: String -> if (st.source == Enums.Source.local) front(n) else ArtPusher.origin(st.source) }
        var now: ArtPusher.Report? = null
        val stop = run {
            val cur = pusher.run(ArtPusher.order(st).filter { real(it.first) }, originOf = curOrigin)
            claim(s, cur, curOrigin)
            note(s, "current", cur)
            now = cur
            if (cur.stop != ArtPusher.Stop.DONE) return@run cur
            val up = upcoming(s, st, keep).filter { real(it.first) }
            if (up.isNotEmpty()) {
                val q = pusher.run(up, originOf = front)
                claim(s, q, front)
                note(s, "queue", q)
                if (q.stop != ArtPusher.Stop.DONE) return@run q
            }
            val playing = ArtPusher.order(st).filter { real(it.first) }
            upgrade(s, pusher, keep, playing.map { Triple(it.first, it.second, curOrigin(it.first)) } + up.map { Triple(it.first, it.second, front(it.first)) }, null)?.let { return@run it }
            if (retagging() && s.hello?.has("imgOrigin") == true) retag(s, library, keep)?.let { return@run it }
            if (holding()) held(s, library, keep)?.let { return@run it }
            if (!looking()) return now!!
            upgrades?.takeIf { it.open() }?.let { u ->
                val seen = (playing + up).mapTo(HashSet()) { it.first }
                pending(s, library, keep, u, seen)?.let { return@run it }
                upgrade(s, library, keep, u.due().filter { real(it.first) && it.first !in seen }.map { Triple(it.first, it.second, libOrigin(s, keep, it.first)) }, u::open)?.let { return@run it }
            }
            null
        }
        if (stop != null && stop.stop != ArtPusher.Stop.GATE) return stop
        repush(s, library, keep)?.let { return it }
        if (stop != null) return stop
        if (!sweeping()) return now!!
        return sweep(s, library, keep)
    }

    private fun phone(s: Session, keep: () -> Boolean, p: Pending): ArtPusher.Report? {
        val now = phoneArtists(device().playback.state)
        p.take(now)
        val b = blocks
        val dev = b?.device()
        val held = if (b == null || dev == null) emptySet() else b.records.pending(dev).mapTo(HashSet()) { it.key }
        val first = now.mapTo(HashSet()) { it.first }
        val list = p.list().filter { b == null || b.keyOf(it.name, it.mbid) !in held }.sortedBy { it.name !in first }
        if (list.isEmpty()) return null
        val r = ArtPusher(s, { n, m, w, h -> lookup(n, m, w, h, false) }, full, keep) { gate() || phoneGate() }.run(list.map { it.name to it.mbid }, origin = Enums.ImgOrigin.phone)
        note(s, "phone", r)
        p.remove(r.pushed.keys + r.stored + r.missing)
        return r.takeIf { it.stop == ArtPusher.Stop.CLOSED || it.stop == ArtPusher.Stop.PREEMPTED || it.stop == ArtPusher.Stop.UNSUPPORTED }
    }

    private fun unblocked(s: Session, pusher: ArtPusher, keep: () -> Boolean, b: Blocks, dev: String): ArtPusher.Report? {
        val todo = b.records.pending(dev)
        val ids = todo.mapTo(HashSet()) { it.key to it.at }
        val l = if (todo.isEmpty()) null else listed?.takeIf { it.session === s && it.records.containsAll(ids) }?.listing ?: pusher.listing()?.also { l ->
            listed = Listed(s, l, ids)
            val cap = l.cap
            if (cap != null && known?.session !== s) known = Known(s, device().lib.dataRev, HashSet(l.names), budget(pusher, l, cap), untagged(l), orphans(l), libOnly(l))
        }
        if (todo.isNotEmpty() && l == null) {
            if (s.closedReason != null) return ArtPusher.Report(ArtPusher.Stop.CLOSED)
            if (carded(pusher)) return ArtPusher.Report(ArtPusher.Stop.GATE)
            log("art del list failed")
        }
        if (l != null) {
            val k = known?.takeIf { it.session === s }
            for (r in todo) {
                if (r is ArtBlocks.Drop && b.hit(r.key)) {
                    b.records.applied(r, dev)
                    continue
                }
                val names = l.entries.filter { e -> matches(r, e, b) }.map { it.name }.filter { it in l.names }
                var ok = true
                for (n in names) {
                    if (!keep()) return ArtPusher.Report(ArtPusher.Stop.PREEMPTED)
                    if (!b.open()) return ArtPusher.Report(ArtPusher.Stop.GATE)
                    val res = s.call(Msg.IMG_DEL, Req.imgDel(n))
                    log("art del $n -> ${if (res is Session.Result.Failed) "error ${res.error.code}" else res.javaClass.simpleName.lowercase()}")
                    if (res is Session.Result.Ok) {
                        k?.names?.remove(n)
                        k?.orphans?.remove(n)
                        k?.untagged?.remove(n)
                        k?.libOnly?.remove(n)
                        l.names.remove(n)
                    } else {
                        ok = false
                    }
                }
                if (ok) b.records.applied(r, dev)
            }
            b.records.save()
        }
        val pushes = b.records.pushes(dev)
        val origins = s.hello?.has("imgOrigin") == true
        var lib: Set<String>? = null
        var skip = false
        for (p in pushes) {
            if (origins && p.origin == null && lib == null) {
                if (skip) continue
                val got = rows(s, device().lib.dataRev, keep)
                if (got !is Rows.Got) {
                    if (s.closedReason != null) return ArtPusher.Report(ArtPusher.Stop.CLOSED)
                    if (!keep()) return ArtPusher.Report(ArtPusher.Stop.PREEMPTED)
                    log("art unblock rows failed")
                    skip = true
                    continue
                }
                lib = got.names()
            }
            val origin = p.origin ?: lib?.let { if (ArtKey.fold(p.name) in it) Enums.ImgOrigin.library else Enums.ImgOrigin.phone }
            val r = pusher.run(listOf(p.name to p.mbid), origin = origin)
            note(s, "unblock", r)
            if (p.name in r.pushed || p.name in r.stored || p.name in r.missing) b.records.pushed(p, dev)
            if (r.stop != ArtPusher.Stop.DONE) {
                b.records.save()
                return r
            }
        }
        if (pushes.isNotEmpty()) b.records.save()
        return null
    }

    private fun upgrade(s: Session, pusher: ArtPusher, keep: () -> Boolean, names: List<Triple<String, String?, Int?>>, gate: (() -> Boolean)?): ArtPusher.Report? {
        val u = upgrades ?: return null
        val w = s.hello?.backdropW ?: return null
        val h = s.hello?.backdropH ?: return null
        val todo = names.distinctBy { it.first }
        if (todo.isEmpty() || !online()) return null
        var n = 0
        var bytes = 0L
        var have: Set<String>? = null
        try {
            for ((name, mbid, origin) in todo) {
                if (!keep()) return ArtPusher.Report(ArtPusher.Stop.PREEMPTED)
                if (gate?.invoke() == false || breaker.open) break
                val r = try {
                    u.run(name, mbid, w, h).also { if (it != null) breaker.result(false) }
                } catch (e: IOException) {
                    if (online()) breaker.result(true)
                    log("art upgrade $name: $e")
                    null
                } ?: continue
                n++
                bytes += r.bytes
                if (!r.replaced) continue
                val dev = blocks?.device()
                blocks?.records?.let { b ->
                    b.upgraded(r.key, name, mbid, r.at, null, kept(origin))
                    b.save()
                }
                if (gate != null) {
                    val names = have ?: known?.takeIf { it.session === s }?.names ?: pusher.listing()?.names ?: return stopped(s)
                    have = names
                    if (name !in names) continue
                }
                val p = pusher.run(listOf(name to mbid), origin = origin)
                claim(s, p) { origin }
                note(s, "upgrade", p)
                if (p.stop != ArtPusher.Stop.DONE) return p
                if (dev != null && (name in p.pushed || name in p.stored)) blocks?.records?.let { b ->
                    b.upgrades(dev).firstOrNull { it.key == r.key }?.let { b.upgradeDone(it, dev) }
                    b.save()
                }
            }
        } finally {
            if (n > 0) log("art upgrade $n of ${todo.size} $bytes")
        }
        return null
    }

    private fun pending(s: Session, pusher: ArtPusher, keep: () -> Boolean, u: Upgrades, seen: Set<String>): ArtPusher.Report? {
        val b = blocks ?: return null
        val dev = b.device() ?: return null
        val list = b.records.upgrades(dev)
        if (list.isEmpty()) return null
        val rev = device().lib.dataRev
        val k = known?.takeIf { it.session === s && it.rev == rev } ?: run {
            val l = pusher.listing() ?: return stopped(s)
            val cap = l.cap ?: return null
            Known(s, rev, l.names, budget(pusher, l, cap), untagged(l), orphans(l), libOnly(l)).also { known = it }
        }
        var dirty = false
        try {
            for (r in list) {
                if (!keep()) return ArtPusher.Report(ArtPusher.Stop.PREEMPTED)
                if (!u.open()) break
                if (u.at(r.key) != r.at) {
                    b.records.dropUpgrade(r)
                    dirty = true
                    continue
                }
                if (r.name in seen || r.name !in k.names) {
                    b.records.upgradeDone(r, dev)
                    dirty = true
                    continue
                }
                if (!k.budget.room) break
                val origin = kept(r.origin) ?: libOrigin(s, keep, r.name)
                val p = pusher.run(listOf(r.name to r.mbid), origin = origin)
                claim(s, p) { origin }
                note(s, "upgrade", p)
                if (r.name in p.pushed || r.name in p.stored) {
                    b.records.upgradeDone(r, dev)
                    dirty = true
                }
                if (p.stop != ArtPusher.Stop.DONE) return p
            }
        } finally {
            if (dirty) b.records.save()
        }
        return null
    }

    private fun orphans(l: ArtPusher.Listing): MutableSet<String> =
        l.entries.filter { it.name in l.names && !cached(it.name, it.mbid) }.mapTo(HashSet()) { it.name }

    private fun held(s: Session, pusher: ArtPusher, keep: () -> Boolean): ArtPusher.Report? {
        val b = blocks ?: return null
        val dev = b.device() ?: return null
        val list = b.records.held(dev)
        if (list.isEmpty()) return null
        val rev = device().lib.dataRev
        val k = known?.takeIf { it.session === s && it.rev == rev } ?: run {
            val l = pusher.listing() ?: return if (s.closedReason != null || !keep()) stopped(s) else if (carded(pusher)) ArtPusher.Report(ArtPusher.Stop.GATE) else null
            log("art list names=${l.names.size} bytes=${l.bytes} cap=${l.cap}")
            val cap = l.cap ?: return null
            Known(s, rev, l.names, budget(pusher, l, cap), untagged(l), orphans(l), libOnly(l)).also { known = it }
        }
        val pushed = LinkedHashMap<String, Long>()
        val stored = ArrayList<String>()
        val missing = ArrayList<String>()
        val failed = ArrayList<String>()
        var dirty = false
        var count = 0
        fun report(stop: ArtPusher.Stop) = ArtPusher.Report(stop, pushed, stored, missing, failed).also {
            if (dirty) b.records.save()
            say("held", it)
        }
        for (h in list) {
            if (!keep()) return report(ArtPusher.Stop.PREEMPTED)
            if (!holding()) break
            if (device().lib.dataRev != rev) {
                if (dirty) b.records.save()
                return null
            }
            if (!k.budget.room) break
            if (h.name !in k.names) {
                b.records.released(h)
                dirty = true
                continue
            }
            val origin = libOrigin(s, keep, h.name)
            val r = pusher.run(listOf(h.name to h.mbid), origin = origin)
            claim(s, r) { origin }
            pushed += r.pushed
            stored += r.stored
            missing += r.missing
            failed += r.failed
            k.names += r.stored + r.pushed.keys
            if (h.name in r.pushed || h.name in r.stored || h.name in r.missing) {
                b.records.released(h)
                dirty = true
            }
            if (++count % CREDITS_EVERY == 0) progress()
            if (r.stop == ArtPusher.Stop.GATE) break
            if (r.stop != ArtPusher.Stop.DONE) return report(r.stop)
        }
        report(ArtPusher.Stop.DONE)
        return null
    }

    private fun repush(s: Session, pusher: ArtPusher, keep: () -> Boolean): ArtPusher.Report? {
        if (!looking()) return null
        val rf = refetch ?: return null
        val fp = full ?: return null
        if (!pusher.sources) return null
        val epoch = rf.epoch()
        val q = queue?.takeIf { it.session === s && it.epoch == epoch } ?: run {
            val l = pusher.listing() ?: return if (s.closedReason != null || !keep()) stopped(s) else if (carded(pusher)) ArtPusher.Report(ArtPusher.Stop.GATE) else null
            val blocked = deleted(s, l, keep) ?: return stopped(s)
            failed(fp, l)
            Queue(s, epoch, order(s, l, blocked, keep), l.srcRoom).also {
                queue = it
                log("art repush list ${it.total} of ${l.entries.size} room=${l.srcRoom}")
            }
        }
        var i = 0
        while (i < q.items.size) {
            if (!keep()) return ArtPusher.Report(ArtPusher.Stop.PREEMPTED)
            if (s.closedReason != null) return ArtPusher.Report(ArtPusher.Stop.CLOSED)
            if (cardDown()) return ArtPusher.Report(ArtPusher.Stop.GATE)
            val it = q.items[i]
            val noData = (q.room ?: 0) <= 0 || fp.low(s)
            if (!cached(it.name, it.mbid) && (!rf.unmetered() || breaker.open || !online() || noData || !rf.data())) {
                i++
                continue
            }
            val a = try {
                lookup(it.name, it.mbid, ArtPusher.FIT_W, ArtPusher.FIT_H, true)
            } catch (e: IOException) {
                null
            }
            val f = a?.key?.let { k -> fp.of(k) }
            if (f == null) {
                q.items.removeAt(i)
                continue
            }
            val rearm = it.entry.srcCrc != null && it.entry.srcCrc == f.crc
            if (rearm && !rf.rearm()) return ArtPusher.Report(ArtPusher.Stop.GATE)
            if (!rearm && noData) {
                i++
                continue
            }
            if (!paced(q, rf, keep)) return ArtPusher.Report(ArtPusher.Stop.PREEMPTED)
            val t0 = rf.clock()
            val one = ArtPusher(s, { n, m, w, h -> lookup(n, m, w, h, true) }, fp, keep) { if (rearm) rf.rearm() else rf.data() }
            val origin = it.entry.origins.firstOrNull() ?: if (holding()) libOrigin(s, keep, it.name) else cachedLib(s)?.let { lib -> if (ArtKey.fold(it.name) in lib) Enums.ImgOrigin.library else Enums.ImgOrigin.phone }
            val r = one.run(listOf(it.name to it.mbid), origin = origin, rearm = rearm)
            claim(s, r) { origin }
            q.lastAt = rf.clock()
            q.lastLen = q.lastAt!! - t0
            when {
                it.name in r.pushed || it.name in r.stored || it.name in r.missing -> {
                    q.items.removeAt(i)
                    q.done++
                    q.ladder = 0
                    if (it.name in r.pushed) q.bytes += f.bytes.size
                    log("art repush ${q.done} of ${q.total} ${q.bytes}")
                }
                r.stop == ArtPusher.Stop.ABORTED && r.code == Err.busy && !r.aborted -> {
                    val wait = Proto.backoffMs[minOf(q.ladder++, Proto.backoffMs.size - 1)]
                    if (!pause(wait, rf, keep)) return ArtPusher.Report(ArtPusher.Stop.PREEMPTED)
                }
                r.stop != ArtPusher.Stop.DONE -> return r
                else -> {
                    val n = (q.tries[it.name] ?: 0) + 1
                    q.tries[it.name] = n
                    if (n >= Proto.imgRefetchTries) q.items.removeAt(i)
                }
            }
        }
        return null
    }

    private fun paced(q: Queue, rf: Refetch, keep: () -> Boolean): Boolean {
        val last = q.lastAt ?: return true
        val gap = if (rf.playing()) maxOf(rf.gapMs, q.lastLen) else rf.gapMs
        return pause(last + gap - rf.clock(), rf, keep)
    }

    private fun pause(ms: Long, rf: Refetch, keep: () -> Boolean): Boolean {
        val end = rf.clock() + ms
        while (rf.clock() < end) {
            if (!keep()) return false
            rf.sleep(minOf(PAUSE_STEP_MS, end - rf.clock()).coerceAtLeast(1))
        }
        return true
    }

    private fun failed(fp: FullPush, l: ArtPusher.Listing) {
        for (e in l.entries) {
            if (e.full == true) continue
            val key = e.srcCrc?.let { fp.sentAs(e.name, it) } ?: continue
            fp.refused(key)
            log("art repush render failed or set aside ${e.name}")
        }
    }

    private fun order(s: Session, l: ArtPusher.Listing, blocked: Set<Long>, keep: () -> Boolean): MutableList<Item> {
        val recorded = { e: ImgEntry -> blocks?.let { b -> b.records.records().any { r -> matches(r, e, b) } } == true }
        val open = l.entries.filter { it.full != true && it.name in l.names && it.name.isNotBlank() && real(it.name) && !recorded(it) && it.srcCrc !in blocked }
        val st = device().playback.state
        val first = ArtPusher.order(st).map { it.first } + upcoming(s, st, keep).map { it.first }
        val rank = HashMap<String, Int>()
        first.forEachIndexed { i, n -> rank.putIfAbsent(Names.fold(n), i) }
        return open.sortedWith(compareBy<ImgEntry> { rank[Names.fold(it.name)] ?: Int.MAX_VALUE }.thenByDescending { it.shownS ?: 0 })
            .mapTo(ArrayList()) { Item(it.name, it.mbid, it) }
    }

    private fun deleted(s: Session, l: ArtPusher.Listing, keep: () -> Boolean): Set<Long>? {
        val b = blocks ?: return emptySet()
        val crcs = b.records.records().mapNotNull {
            when (it) {
                is ArtBlocks.Block -> it.crc
                is ArtBlocks.Reject -> it.crc
                else -> null
            }
        }.toSet()
        if (crcs.isEmpty()) return crcs
        for (e in l.entries) {
            if (e.crc !in crcs) continue
            if (!keep()) return null
            if (!b.open()) return crcs
            val r = s.call(Msg.IMG_DEL, Req.imgDel(e.name))
            log("art repush del ${e.name} -> ${if (r is Session.Result.Failed) "error ${r.error.code}" else r.javaClass.simpleName.lowercase()}")
            if (r is Session.Result.Closed) return null
            if (r is Session.Result.Ok) l.names.remove(e.name)
        }
        return crcs
    }

    private fun untagged(l: ArtPusher.Listing): MutableSet<String> =
        l.entries.filter { it.origins.isEmpty() && it.name.isNotBlank() }.mapTo(LinkedHashSet()) { it.name }

    private fun libOnly(l: ArtPusher.Listing): MutableSet<String> =
        l.entries.filter { it.origins == listOf(Enums.ImgOrigin.library) && it.name.isNotBlank() }.mapTo(LinkedHashSet()) { it.name }

    private fun budget(pusher: ArtPusher, l: ArtPusher.Listing, cap: Long) = if (pusher.sources) Sweep.Budget(0, Long.MAX_VALUE / 10) else Sweep.Budget(l.bytes ?: 0, cap)

    private fun carded(pusher: ArtPusher) = pusher.listFailed == Err.noCard || cardDown()

    private fun stopped(s: Session) = ArtPusher.Report(if (s.closedReason != null) ArtPusher.Stop.CLOSED else ArtPusher.Stop.PREEMPTED)

    private fun retag(s: Session, pusher: ArtPusher, keep: () -> Boolean): ArtPusher.Report? {
        val rev = device().lib.dataRev
        val k = known?.takeIf { it.session === s && it.rev == rev } ?: run {
            val l = pusher.listing() ?: return if (s.closedReason != null || !keep()) stopped(s) else if (carded(pusher)) ArtPusher.Report(ArtPusher.Stop.GATE) else null
            log("art list names=${l.names.size} bytes=${l.bytes} cap=${l.cap}")
            val cap = l.cap ?: return null
            Known(s, rev, l.names, budget(pusher, l, cap), untagged(l), orphans(l), libOnly(l)).also { known = it }
        }
        if (k.tagged) return null
        val lib = when (val got = rows(s, rev, keep)) {
            is Rows.Got -> got.names()
            is Rows.Stop -> return if (s.closedReason != null || !keep()) stopped(s) else null
            is Rows.Restart -> return null
        }
        val mine = absent?.takeIf { it.first === s }?.second.orEmpty()
        val todo = k.untagged.mapNotNull { n ->
            when {
                ArtKey.fold(n) in lib -> n to Enums.ImgOrigin.library
                n in mine -> n to Enums.ImgOrigin.phone
                else -> null
            }
        } + k.libOnly.filter { ArtKey.fold(it) !in lib }.map { it to Enums.ImgOrigin.phone }
        val total = todo.size
        var tagged = 0
        var gone = 0
        for ((name, origin) in todo) {
            if (!keep()) return ArtPusher.Report(ArtPusher.Stop.PREEMPTED)
            if (!retagging() || known !== k || device().lib.dataRev != rev) return null
            val r = s.call(Msg.IMG_TAG, Req.imgTag(name, origin))
            when {
                r is Session.Result.Ok -> tagged++
                r is Session.Result.Closed -> return ArtPusher.Report(ArtPusher.Stop.CLOSED)
                r is Session.Result.Failed && r.error.code == Err.notFound -> {
                    k.names.remove(name)
                    gone++
                }
                r is Session.Result.Failed && (r.error.code == Err.busy || r.error.code == Err.noCard) -> {
                    log("art tag $name -> error ${r.error.code}")
                    return null
                }
                else -> log("art tag $name -> ${if (r is Session.Result.Failed) "error ${r.error.code}" else r.javaClass.simpleName.lowercase()}")
            }
            k.untagged.remove(name)
            k.libOnly.remove(name)
        }
        k.untagged.clear()
        k.libOnly.clear()
        k.tagged = true
        log("art tag tagged=$tagged gone=$gone of $total")
        return null
    }

    private fun matches(r: ArtBlocks.Record, e: ImgEntry, b: Blocks): Boolean {
        val on = (r.mbid != null && e.mbid?.trim()?.lowercase() == r.mbid) || b.keyOf(e.name, e.mbid) == r.key ||
            (Names.mbid(e.mbid) == null && "name:" + Names.fold(e.name) == r.key)
        if (r is ArtBlocks.Drop && r.cleared && r.crcs.isNotEmpty() && (e.crc != null || e.srcCrc != null)) return on && (e.crc in r.crcs || e.srcCrc in r.crcs)
        return on && (r !is ArtBlocks.Reject || Names.fold(e.name) == Names.fold(r.name))
    }

    private fun lookup(name: String, mbid: String?, w: Int, h: Int, library: Boolean): Art? {
        val counted = online()
        try {
            val a = art(name, mbid, w, h, library)
            if (counted) breaker.result(false)
            if (a != null) synchronized(sizes) { sizes[name] = a.jpeg.size }
            return a
        } catch (e: IOException) {
            if (counted) breaker.result(true)
            throw e
        }
    }

    private sealed class Rows {
        class Got(val rows: List<Sweep.Row>) : Rows() {
            fun names(): Set<String> = rows.mapTo(HashSet()) { ArtKey.fold(it.name) }
        }
        object Restart : Rows()
        object Stop : Rows()
    }

    private fun say(stage: String, r: ArtPusher.Report) =
        log("art $stage ${r.stop} ${r.counts()}")

    private fun note(s: Session, stage: String, r: ArtPusher.Report) {
        say(stage, r)
        known?.takeIf { it.session === s }?.let { account(it, r) }
    }

    private fun account(k: Known, r: ArtPusher.Report) {
        k.orphans -= r.stored.toSet()
        k.orphans -= r.pushed.keys
        k.names += r.stored
        for (name in r.pushed.keys) {
            k.names += name
            val size = synchronized(sizes) { sizes[name] } ?: continue
            if (k.budget.add(size.toLong()) && known === k) known = null
        }
    }

    private fun upcoming(s: Session, st: State, keep: () -> Boolean): List<Pair<String, String?>> {
        val slot = st.slot ?: return emptyList()
        val slots = st.slots ?: return emptyList()
        val out = ArrayList<Pair<String, String?>>()
        var retries = 0
        fun page(offset: Long, count: Long): Boolean {
            if (count <= 0) return true
            var tries = 0
            while (true) {
                val r = s.call(Msg.QUEUE_OP, Req.queueOp(Enums.QueueCmd.page, offset = offset, count = count))
                tries++
                val retry = r is Session.Result.Timeout || (r is Session.Result.Failed && r.error.code == Err.busy)
                log("art queue page $offset $count try $tries -> ${pageResult(r)}")
                if (r is Session.Result.Ok) break
                if (!retry || retries >= ArtWorker.GATE_WAITS || !keep() || s.closedReason != null) return false
                retries++
                Thread.sleep(Proto.pushGateMs.toLong())
                if (!keep() || s.closedReason != null) return false
            }
            val items = device().queue.items
            for (i in offset until offset + count) {
                val item = items[i] ?: continue
                item.artists.forEachIndexed { j, a -> out += a to item.mbids.getOrNull(j)?.takeIf { it.isNotBlank() } }
            }
            return true
        }
        val from = slot + 1
        val n = minOf(QUEUE_AHEAD, slots - from).coerceAtLeast(0)
        if (!page(from, n)) return out
        if (st.repeat == Enums.Repeat.all && n < QUEUE_AHEAD) page(0, minOf(QUEUE_AHEAD - n, slot))
        return out
    }

    private fun pageResult(r: Session.Result) = when (r) {
        is Session.Result.Ok -> "ok"
        is Session.Result.Timeout -> "timeout"
        is Session.Result.Failed -> if (r.error.code == Err.busy) "busy" else "error ${r.error.code}"
        else -> r.javaClass.simpleName.lowercase()
    }

    private fun sweep(s: Session, pusher: ArtPusher, keep: () -> Boolean): ArtPusher.Report {
        var restarts = 0
        while (true) {
            val rev = device().lib.dataRev
            val k = known?.takeIf { it.session === s && it.rev == rev } ?: run {
                val l = pusher.listing() ?: return if (s.closedReason == null && keep() && carded(pusher)) ArtPusher.Report(ArtPusher.Stop.GATE) else ArtPusher.Report(if (s.closedReason != null) ArtPusher.Stop.CLOSED else ArtPusher.Stop.PREEMPTED)
                log("art list names=${l.names.size} bytes=${l.bytes} cap=${l.cap}")
                val cap = l.cap ?: return ArtPusher.Report(ArtPusher.Stop.DONE)
                Known(s, rev, l.names, budget(pusher, l, cap), untagged(l), orphans(l), libOnly(l)).also { known = it }
            }
            if (k.swept?.let { marks?.holds(it) } == true) {
                log("art sweep kept")
                return ArtPusher.Report(ArtPusher.Stop.DONE)
            }
            marks?.begin()
            val got = rows(s, rev, keep)
            if (got is Rows.Stop) return ArtPusher.Report(if (s.closedReason != null) ArtPusher.Stop.CLOSED else ArtPusher.Stop.PREEMPTED)
            if (got is Rows.Got) sweepRows(s, pusher, keep, k, rev, got.rows)?.let { return it }
            known = null
            if (++restarts > MAX_RESTARTS) return ArtPusher.Report(ArtPusher.Stop.ABORTED)
        }
    }

    private fun sweepRows(s: Session, pusher: ArtPusher, keep: () -> Boolean, k: Known, rev: Long?, rows: List<Sweep.Row>): ArtPusher.Report? {
        val pushed = LinkedHashMap<String, Long>()
        val stored = ArrayList<String>()
        val missing = ArrayList<String>()
        val failed = ArrayList<String>()
        var gated = false
        var cut = false
        var count = 0
        val dev = blocks?.device()
        var dirty = false
        fun saved() {
            if (dirty) blocks?.records?.save()
            dirty = false
        }
        fun report(stop: ArtPusher.Stop, complete: Boolean = false) = ArtPusher.Report(stop, pushed, stored, missing, failed, complete).also {
            saved()
            say("sweep", it)
        }
        for (row in Sweep.order(rows.filter { real(it.name) })) {
            if (!keep()) return report(ArtPusher.Stop.PREEMPTED)
            if (device().lib.dataRev != rev) {
                saved()
                return null
            }
            if (!sweeping() || known !== k) {
                cut = true
                break
            }
            if (!k.budget.room) {
                log("art sweep budget ${k.budget.used} of ${k.budget.cap}")
                break
            }
            if (row.name in k.orphans && cached(row.name, row.mbid)) k.orphans -= row.name
            val orphan = row.name in k.orphans
            if (row.name in k.names && !orphan) continue
            val r = pusher.run(listOf(row.name to row.mbid), if (orphan) emptySet() else k.names, Enums.ImgOrigin.library)
            pushed += r.pushed
            stored += r.stored
            missing += r.missing
            failed += r.failed
            if (orphan) {
                k.names += r.stored + r.pushed.keys
                val done = row.name in r.pushed || row.name in r.stored || row.name in r.missing
                val held = if (done || dev == null) null else blocks?.records?.takeIf { cached(row.name, row.mbid) }
                if (held != null && dev != null) {
                    held.hold(row.name, row.mbid, dev)
                    dirty = true
                }
                if (done || held != null) k.orphans -= row.name
            } else {
                account(k, r)
            }
            when (r.stop) {
                ArtPusher.Stop.GATE -> gated = true
                ArtPusher.Stop.DONE -> {}
                else -> return report(r.stop)
            }
            if (++count % CREDITS_EVERY == 0) progress()
        }
        val complete = !cut && !gated && failed.isEmpty() && known === k
        if (complete && (k.tagged || s.hello?.has("imgOrigin") != true)) k.swept = marks?.mark()
        return report(if (gated) ArtPusher.Stop.GATE else ArtPusher.Stop.DONE, complete)
    }

    private fun cachedLib(s: Session): Set<String>? {
        if (s.hello?.has("imgOrigin") != true) return null
        val list = device().lib.pages[LibKey(Enums.View.artists, "")] ?: return null
        if ((0L until list.total).any { it !in list.rows }) return null
        return list.rows.values.mapTo(HashSet()) { ArtKey.fold(it.name ?: "") }
    }

    private fun claim(s: Session, r: ArtPusher.Report, origin: (String) -> Int?) {
        if (s.hello?.has("imgOrigin") != true) return
        val names = r.pushed.keys.filter { origin(it) == null }
        if (names.isEmpty()) return
        val own = absent?.takeIf { it.first === s } ?: (s to HashSet<String>()).also { absent = it }
        own.second += names
        val k = known?.takeIf { it.session === s } ?: return
        k.untagged += names
        k.tagged = false
    }

    private fun kept(origin: Int?) = origin.takeIf { it == Enums.ImgOrigin.phone }

    private fun libOrigin(s: Session, keep: () -> Boolean, name: String): Int? {
        if (s.hello?.has("imgOrigin") != true) return null
        val rev = device().lib.dataRev
        val m = libSet?.takeIf { it.first === s && it.second == rev } ?: Triple(s, rev, (rows(s, rev, keep) as? Rows.Got)?.names()).also { libSet = it }
        val names = m.third ?: return null
        return if (ArtKey.fold(name) in names) Enums.ImgOrigin.library else Enums.ImgOrigin.phone
    }

    private fun rows(s: Session, rev: Long?, keep: () -> Boolean): Rows {
        val key = LibKey(Enums.View.artists, "")
        var asked: Long? = null
        while (true) {
            if (!keep() || s.closedReason != null) return Rows.Stop
            val lib = device().lib
            if (lib.dataRev != rev) return Rows.Restart
            val list = lib.pages[key]
            val missing = if (list == null) 0L else (0L until list.total).firstOrNull { it !in list.rows }
            if (list != null && missing == null) return Rows.Got(list.rows.values.map { Sweep.Row(it.name ?: "", it.mbid, it.count ?: 0) })
            if (missing == asked) return Rows.Stop
            asked = missing ?: 0L
            when (val r = libGet(s, missing ?: 0L, rev)) {
                is Session.Result.Ok -> {}
                is Session.Result.Failed -> return if (r.error.code == Err.staleRev) Rows.Restart else Rows.Stop
                else -> return Rows.Stop
            }
        }
    }

    companion object {
        const val QUEUE_AHEAD = 5L
        val PLACEHOLDERS = setOf("unknown artist", "null")
        const val CREDITS_EVERY = 20
        private const val MAX_RESTARTS = 3
        private const val PAUSE_STEP_MS = 100L

        private fun real(name: String) = name.trim().lowercase() !in PLACEHOLDERS

        fun phoneArtists(st: State): List<Pair<String, String?>> =
            if (st.source != Enums.Source.phone) emptyList() else ArtPusher.order(st).filter { real(it.first) }
    }
}
