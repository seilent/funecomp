package net.seilent.funecomp.state

import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import kotlin.math.abs

class ListPlays(private val block: Long, private val changed: (Tap?) -> Unit = {}) {
    data class Want(
        val view: Int,
        val key: String,
        val name: String,
        val first: Long? = null,
        val start: Long = 0,
        val shuffle: Boolean = false,
        val random: Boolean = false,
        val rev: Long? = null,
        val add: Int? = null,
    )

    class Tap internal constructor(val want: Want, val queueRev: Long?, val pushed: Boolean, val top: Any?, val depth: Int) {
        internal var stale = false
        internal var resent = false
        internal val asked = HashSet<Pair<Ask, Long?>>()
    }

    data class Ask(val view: Int, val parent: String, val offset: Long)

    sealed interface Step {
        data object Stay : Step
        class Back(val tap: Tap, val gone: String?) : Step
        class Again(val tap: Tap, val at: Long?, val count: Long?) : Step
    }

    private var armed: Tap? = null
    private var reported: Tap? = null

    val deciding: Tap? get() = armed?.takeIf { it.stale }

    @Synchronized
    fun tap(want: Want, queueRev: Long?, pushed: Boolean, top: Any?, depth: Int): Tap = Tap(want, queueRev, pushed, top, depth).also {
        armed = it
        tell()
    }

    @Synchronized
    fun replied(t: Tap, code: Int?, queueRev: Long?): Step = reply(t, code, queueRev).also { tell() }

    @Synchronized
    fun end() {
        if (armed?.want?.add == null) stop()
    }

    @Synchronized
    fun stop() {
        armed = null
        tell()
    }

    @Synchronized
    fun page(top: Any?, depth: Int, sheet: Boolean) {
        val t = armed ?: return
        if (sheet || top != t.top || depth != t.depth) end()
    }

    @Synchronized
    fun link(live: Boolean): Step {
        val t = armed ?: return Step.Stay
        return if (live) Step.Stay else back(t, null).also { tell() }
    }

    @Synchronized
    fun timeout(t: Tap): Step = if (deciding === t) back(t, null).also { tell() } else Step.Stay

    @Synchronized
    fun asks(lib: LibMirror): List<Ask> {
        val t = deciding ?: return emptyList()
        val w = t.want
        if (!moved(t, lib)) return emptyList()
        val out = ArrayList<Ask>()
        if (w.view == Enums.View.playlists) lib.pages[ROOT]?.let { r -> r.firstMissing(0, r.total)?.let { out += Ask(w.view, "", it) } }
        val p = lib.pages[LibKey(w.view, w.key)]
        when {
            p == null && (w.first != null || w.view != Enums.View.playlists) -> out += Ask(w.view, w.key, 0)
            p != null && w.first != null -> {
                val (lo, hi) = window(w.start, p.total)
                p.firstMissing(lo, hi)?.let { out += Ask(w.view, w.key, it) }
            }
        }
        val rev = lib.libRev(w.view)
        return out.filter { t.asked.add(it to rev) }
    }

    @Synchronized
    fun decide(lib: LibMirror, card: Int?): Step = judge(lib, card).also { tell() }

    private fun reply(t: Tap, code: Int?, queueRev: Long?): Step {
        if (armed !== t) return Step.Stay
        if (code == Err.staleRev && !t.resent) {
            t.stale = true
            return Step.Stay
        }
        armed = null
        return when {
            code == null -> Step.Stay
            code == LOST && t.want.add == null && queueRev != t.queueRev -> Step.Stay
            else -> Step.Back(t, null)
        }
    }

    private fun judge(lib: LibMirror, card: Int?): Step {
        val t = deciding ?: return Step.Stay
        val w = t.want
        if (!moved(t, lib)) return Step.Stay
        val gone = ListGone.gone(w.view, w.key, card, lib.pages[ROOT])
        if (gone == true) return back(t, w.name)
        val p = lib.pages[LibKey(w.view, w.key)]
        if (w.first == null) {
            val count = if (w.view == Enums.View.playlists) lib.pages[ROOT]?.rows?.values?.firstOrNull { it.key == w.key }?.count?.takeIf { gone == false } else p?.total
            return when (count) {
                null -> Step.Stay
                0L -> back(t, null)
                else -> again(t, null, count)
            }
        }
        val at = p?.rows?.entries?.filter { it.value.first == w.first }?.minByOrNull { abs(it.key - w.start) }?.key
        if (at != null) return again(t, at, null)
        if (p == null) return Step.Stay
        val (lo, hi) = window(w.start, p.total)
        return if (p.firstMissing(lo, hi) == null) back(t, null) else Step.Stay
    }

    private fun moved(t: Tap, lib: LibMirror) = lib.libRev(t.want.view) != t.want.rev

    private fun window(start: Long, total: Long): Pair<Long, Long> {
        val b = minOf(start, maxOf(total - 1, 0L)) / block * block
        return maxOf(0L, b - block) to minOf(total, b + 2 * block)
    }

    private fun back(t: Tap, gone: String?): Step {
        armed = null
        return Step.Back(t, gone)
    }

    private fun again(t: Tap, at: Long?, count: Long?): Step {
        t.stale = false
        t.resent = true
        return Step.Again(t, at, count)
    }

    private fun tell() {
        val d = deciding
        if (d === reported) return
        reported = d
        changed(d)
    }

    companion object {
        const val BOUND_MS = 2000L
        const val LOST = -1
        private val ROOT = LibKey(Enums.View.playlists, "")
    }
}
