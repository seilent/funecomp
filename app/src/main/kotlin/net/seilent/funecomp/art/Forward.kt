package net.seilent.funecomp.art

import net.seilent.funecomp.artist.Art
import net.seilent.funecomp.artist.ArtPusher
import net.seilent.funecomp.artist.Sweep
import net.seilent.funecomp.link.Session
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.state.LibKey
import java.io.IOException

class Forward(
    private val device: () -> DeviceState,
    private val art: (String, String?, Int, Int) -> Art?,
    private val gate: () -> Boolean,
    private val sweeping: () -> Boolean,
    private val libGet: (Session, Long, Long?) -> Session.Result,
    private val breaker: Sweep.Breaker,
    private val online: () -> Boolean = { true },
    private val progress: () -> Unit = {},
    private val marks: Marks? = null,
    private val log: (String) -> Unit = {},
) {
    interface Marks {
        fun begin()
        fun mark(): Any
        fun holds(mark: Any): Boolean
    }

    private class Known(val session: Session, val libRev: Long?, val names: MutableSet<String>, val budget: Sweep.Budget) {
        var swept: Any? = null
    }

    private var known: Known? = null
    private val sizes = HashMap<String, Int>()

    fun run(s: Session, keep: () -> Boolean): ArtPusher.Report {
        val pusher = ArtPusher(s, ::lookup, keep, gate)
        val st = device().playback.state
        val now = pusher.run(ArtPusher.order(st).filter { real(it.first) })
        note(s, "current", now)
        if (now.stop != ArtPusher.Stop.DONE) return now
        val up = upcoming(s, st).filter { real(it.first) }
        if (up.isNotEmpty()) {
            val q = pusher.run(up)
            note(s, "queue", q)
            if (q.stop != ArtPusher.Stop.DONE) return q
        }
        if (!sweeping()) return now
        return sweep(s, pusher, keep)
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
        class Got(val rows: List<Sweep.Row>) : Rows()
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
            val rev = device().lib.rev
            val k = known?.takeIf { it.session === s && it.libRev == rev } ?: run {
                val l = pusher.listing() ?: return ArtPusher.Report(if (s.closedReason != null) ArtPusher.Stop.CLOSED else ArtPusher.Stop.PREEMPTED)
                log("art list names=${l.names.size} bytes=${l.bytes} cap=${l.cap}")
                val cap = l.cap ?: return ArtPusher.Report(ArtPusher.Stop.DONE)
                Known(s, rev, l.names, Sweep.Budget(l.bytes ?: 0, cap)).also { known = it }
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
            if (device().lib.rev != rev) return null
            if (!sweeping() || known !== k) {
                cut = true
                break
            }
            if (!k.budget.room) {
                log("art sweep budget ${k.budget.used} of ${k.budget.cap}")
                break
            }
            if (row.name in k.names) continue
            val r = pusher.run(listOf(row.name to row.mbid), k.names)
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
        if (!cut && !gated && failed.isEmpty() && known === k) k.swept = marks?.mark()
        return report(if (gated) ArtPusher.Stop.GATE else ArtPusher.Stop.DONE)
    }

    private fun rows(s: Session, rev: Long?, keep: () -> Boolean): Rows {
        val key = LibKey(Enums.View.artists, "")
        var asked: Long? = null
        while (true) {
            if (!keep() || s.closedReason != null) return Rows.Stop
            val lib = device().lib
            if (lib.rev != rev) return Rows.Restart
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
