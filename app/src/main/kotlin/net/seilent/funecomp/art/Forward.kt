package net.seilent.funecomp.art

import net.seilent.funecomp.artist.Art
import net.seilent.funecomp.artist.ArtBlocks
import net.seilent.funecomp.artist.ArtPusher
import net.seilent.funecomp.artist.Names
import net.seilent.funecomp.artist.Sweep
import net.seilent.funecomp.link.Session
import net.seilent.funecomp.proto.ImgEntry
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.state.ArtKey
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.state.LibKey
import java.io.IOException

class Forward(
    private val device: () -> DeviceState,
    private val art: (String, String?, Int, Int) -> Art?,
    private val gate: () -> Boolean,
    private val sweeping: () -> Boolean,
    private val retagging: () -> Boolean,
    private val libGet: (Session, Long, Long?) -> Session.Result,
    private val breaker: Sweep.Breaker,
    private val online: () -> Boolean = { true },
    private val progress: () -> Unit = {},
    private val marks: Marks? = null,
    private val blocks: Blocks? = null,
    private val log: (String) -> Unit = {},
) {
    class Blocks(
        val records: ArtBlocks,
        val device: () -> String?,
        val keyOf: (String, String?) -> String,
        val open: () -> Boolean = { true },
    )

    interface Marks {
        fun begin()
        fun mark(): Any
        fun holds(mark: Any): Boolean
    }

    private class Known(val session: Session, val rev: Long?, val names: MutableSet<String>, val budget: Sweep.Budget, val untagged: MutableSet<String>) {
        var swept: Any? = null
        var tagged = false
    }

    private class Listed(val session: Session, val listing: ArtPusher.Listing, val records: Set<Pair<String, Long>>)

    private var known: Known? = null
    private var listed: Listed? = null
    private val sizes = HashMap<String, Int>()

    fun run(s: Session, keep: () -> Boolean): ArtPusher.Report {
        val pusher = ArtPusher(s, ::lookup, keep, gate)
        blocks?.let { b -> b.device()?.let { dev -> unblocked(s, pusher, keep, b, dev)?.let { return it } } }
        val st = device().playback.state
        val now = pusher.run(ArtPusher.order(st).filter { real(it.first) }, origin = ArtPusher.origin(st.source))
        note(s, "current", now)
        if (now.stop != ArtPusher.Stop.DONE) return now
        val up = upcoming(s, st).filter { real(it.first) }
        if (up.isNotEmpty()) {
            val q = pusher.run(up, origin = Enums.ImgOrigin.library)
            note(s, "queue", q)
            if (q.stop != ArtPusher.Stop.DONE) return q
        }
        if (retagging() && s.hello?.has("imgOrigin") == true) retag(s, pusher, keep)?.let { return it }
        if (!sweeping()) return now
        return sweep(s, pusher, keep)
    }

    private fun unblocked(s: Session, pusher: ArtPusher, keep: () -> Boolean, b: Blocks, dev: String): ArtPusher.Report? {
        val todo = b.records.pending(dev)
        val ids = todo.mapTo(HashSet()) { it.key to it.at }
        val l = if (todo.isEmpty()) null else listed?.takeIf { it.session === s && it.records.containsAll(ids) }?.listing ?: pusher.listing()?.also { l ->
            listed = Listed(s, l, ids)
            val cap = l.cap
            if (cap != null && known?.session !== s) known = Known(s, device().lib.dataRev, HashSet(l.names), Sweep.Budget(l.bytes ?: 0, cap), untagged(l))
        }
        if (todo.isNotEmpty() && l == null) {
            if (s.closedReason != null) return ArtPusher.Report(ArtPusher.Stop.CLOSED)
            log("art del list failed")
        }
        if (l != null) {
            val k = known?.takeIf { it.session === s }
            for (r in todo) {
                val names = l.entries.filter { e -> matches(r, e, b) }.map { it.name }.filter { it in l.names }
                var ok = true
                for (n in names) {
                    if (!keep()) return ArtPusher.Report(ArtPusher.Stop.PREEMPTED)
                    if (!b.open()) return ArtPusher.Report(ArtPusher.Stop.GATE)
                    val res = s.call(Msg.IMG_DEL, Req.imgDel(n))
                    log("art del $n -> ${if (res is Session.Result.Failed) "error ${res.error.code}" else res.javaClass.simpleName.lowercase()}")
                    if (res is Session.Result.Ok) {
                        k?.names?.remove(n)
                        k?.untagged?.remove(n)
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

    private fun untagged(l: ArtPusher.Listing): MutableSet<String> =
        l.entries.filter { it.origins.isEmpty() && it.name.isNotBlank() }.mapTo(LinkedHashSet()) { it.name }

    private fun stopped(s: Session) = ArtPusher.Report(if (s.closedReason != null) ArtPusher.Stop.CLOSED else ArtPusher.Stop.PREEMPTED)

    private fun retag(s: Session, pusher: ArtPusher, keep: () -> Boolean): ArtPusher.Report? {
        val rev = device().lib.dataRev
        val k = known?.takeIf { it.session === s && it.rev == rev } ?: run {
            val l = pusher.listing() ?: return if (s.closedReason != null || !keep()) stopped(s) else null
            log("art list names=${l.names.size} bytes=${l.bytes} cap=${l.cap}")
            val cap = l.cap ?: return null
            Known(s, rev, l.names, Sweep.Budget(l.bytes ?: 0, cap), untagged(l)).also { known = it }
        }
        if (k.tagged) return null
        val lib = when (val got = rows(s, rev, keep)) {
            is Rows.Got -> got.names()
            is Rows.Stop -> return if (s.closedReason != null || !keep()) stopped(s) else null
            is Rows.Restart -> return null
        }
        val total = k.untagged.size
        var tagged = 0
        var gone = 0
        for (name in k.untagged.toList()) {
            if (!keep()) return ArtPusher.Report(ArtPusher.Stop.PREEMPTED)
            if (!retagging() || known !== k || device().lib.dataRev != rev) return null
            if (ArtKey.fold(name) !in lib) continue
            val r = s.call(Msg.IMG_TAG, Req.imgTag(name, Enums.ImgOrigin.library))
            when {
                r is Session.Result.Ok -> tagged++
                r is Session.Result.Closed -> return ArtPusher.Report(ArtPusher.Stop.CLOSED)
                r is Session.Result.Failed && r.error.code == Err.notFound -> {
                    k.names.remove(name)
                    gone++
                }
                r is Session.Result.Failed && r.error.code == Err.busy -> {
                    log("art tag $name -> error ${Err.busy}")
                    return null
                }
                else -> log("art tag $name -> ${if (r is Session.Result.Failed) "error ${r.error.code}" else r.javaClass.simpleName.lowercase()}")
            }
            k.untagged.remove(name)
        }
        k.tagged = true
        log("art tag tagged=$tagged gone=$gone of $total")
        return null
    }

    private fun matches(r: ArtBlocks.Record, e: ImgEntry, b: Blocks): Boolean {
        val on = (r.mbid != null && e.mbid?.trim()?.lowercase() == r.mbid) || b.keyOf(e.name, e.mbid) == r.key ||
            (Names.mbid(e.mbid) == null && "name:" + Names.fold(e.name) == r.key)
        return on && (r !is ArtBlocks.Reject || Names.fold(e.name) == Names.fold(r.name))
    }

    private fun lookup(name: String, mbid: String?, w: Int, h: Int): Art? {
        val counted = online()
        try {
            val a = art(name, mbid, w, h)
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
        k.names += r.stored
        for (name in r.pushed.keys) {
            k.names += name
            val size = synchronized(sizes) { sizes[name] } ?: continue
            if (k.budget.add(size.toLong()) && known === k) known = null
        }
    }

    private fun upcoming(s: Session, st: State): List<Pair<String, String?>> {
        val slot = st.slot ?: return emptyList()
        val slots = st.slots ?: return emptyList()
        val out = ArrayList<Pair<String, String?>>()
        fun page(offset: Long, count: Long): Boolean {
            if (count <= 0) return true
            val r = s.call(Msg.QUEUE_OP, Req.queueOp(Enums.QueueCmd.page, offset = offset, count = count))
            if (r !is Session.Result.Ok) return false
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

    private fun sweep(s: Session, pusher: ArtPusher, keep: () -> Boolean): ArtPusher.Report {
        var restarts = 0
        while (true) {
            val rev = device().lib.dataRev
            val k = known?.takeIf { it.session === s && it.rev == rev } ?: run {
                val l = pusher.listing() ?: return ArtPusher.Report(if (s.closedReason != null) ArtPusher.Stop.CLOSED else ArtPusher.Stop.PREEMPTED)
                log("art list names=${l.names.size} bytes=${l.bytes} cap=${l.cap}")
                val cap = l.cap ?: return ArtPusher.Report(ArtPusher.Stop.DONE)
                Known(s, rev, l.names, Sweep.Budget(l.bytes ?: 0, cap), untagged(l)).also { known = it }
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
        fun report(stop: ArtPusher.Stop) = ArtPusher.Report(stop, pushed, stored, missing, failed).also { say("sweep", it) }
        for (row in Sweep.order(rows.filter { real(it.name) })) {
            if (!keep()) return report(ArtPusher.Stop.PREEMPTED)
            if (device().lib.dataRev != rev) return null
            if (!sweeping() || known !== k) {
                cut = true
                break
            }
            if (!k.budget.room) {
                log("art sweep budget ${k.budget.used} of ${k.budget.cap}")
                break
            }
            if (row.name in k.names) continue
            val r = pusher.run(listOf(row.name to row.mbid), k.names, Enums.ImgOrigin.library)
            pushed += r.pushed
            stored += r.stored
            missing += r.missing
            failed += r.failed
            account(k, r)
            when (r.stop) {
                ArtPusher.Stop.GATE -> gated = true
                ArtPusher.Stop.DONE -> {}
                else -> return report(r.stop)
            }
            if (++count % CREDITS_EVERY == 0) progress()
        }
        if (!cut && !gated && failed.isEmpty() && known === k && (k.tagged || s.hello?.has("imgOrigin") != true)) k.swept = marks?.mark()
        return report(if (gated) ArtPusher.Stop.GATE else ArtPusher.Stop.DONE)
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

    private fun real(name: String) = name.trim().lowercase() !in PLACEHOLDERS

    companion object {
        const val QUEUE_AHEAD = 5L
        val PLACEHOLDERS = setOf("unknown artist", "null")
        const val CREDITS_EVERY = 20
        private const val MAX_RESTARTS = 3
    }
}
