package net.seilent.funecomp.ui

import net.seilent.funecomp.proto.gen.Tok
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

object LyricRows {
    enum class Kind { PREVIOUS, CURRENT, UPCOMING }

    data class Block(val line: Int, val big: Boolean, val top: Float, val rows: Int, val pitch: Float, val alpha: Float) {
        val h get() = rows * pitch
    }

    class Blocks(val list: List<Block>, val vis: Float)

    data class Draw(val line: Int, val top: Float, val h: Float, val y0: Float, val y1: Float, val a: Block?, val b: Block?, val alphaA: Float, val alphaB: Float, val change: Boolean, val side: Int) {
        val forms: List<Pair<Block, Float>> get() = listOfNotNull(a?.takeIf { b == null || change || side != 0 }?.let { it to alphaA }, b?.let { it to alphaB })
    }

    private const val EPS = 0.001f

    fun blocks(texts: List<String>?, index: Int, title: String, next: String?, wrap: (String, Boolean) -> List<String>, room: Float, cp: Float, np: Float): Blocks {
        val none = texts == null
        val out = ArrayList<Block>()
        var curN = if (none || index >= 0) max(wrap(text(texts, if (none) -1 else index, true, title, next), true).size, 1) else 0
        curN = min(curN, floor((room + EPS) / cp).toInt())
        var from = if (none) 0 else index + 1
        fun nextIndex(): Int {
            if (texts == null) return if (from++ == 0 && !next.isNullOrEmpty()) -1 else -2
            while (from < texts.size) {
                val k = from++
                if (texts[k].isNotEmpty()) return k
            }
            return -2
        }
        val prevN = if (texts != null && index >= 1) wrap(texts[index - 1], false).size else 0
        val after = from
        val more = nextIndex() != -2
        from = after
        var slot = 0f
        if (prevN > 0 && curN * cp + prevN * np + (if (more) np else 0f) <= room + EPS) {
            out += Block(index - 1, false, slot, prevN, np, Tok.alpha_low)
            slot += prevN * np
        }
        if (curN > 0) {
            out += Block(if (none) -1 else index, true, slot, curN, cp, Roles.npTitle.alpha)
            slot += curN * cp
        }
        while (slot + np <= room + EPS) {
            val k = nextIndex()
            if (k == -2) break
            val b = Block(k, false, slot, wrap(text(texts, k, false, title, next), false).size, np, Roles.npNext.alpha)
            out += b
            val fit = min(b.rows, floor((room - slot + EPS) / np).toInt())
            if (fit < b.rows) return Blocks(out, slot + fit * np)
            slot += b.h
        }
        return Blocks(out, slot)
    }

    fun pair(a: Blocks, b: Blocks, rows: (Int) -> Int, np: Float): Pair<Blocks, Blocks> {
        val pa = Blocks(offColumn(a.list, b.list, rows, np), a.vis)
        return pa to Blocks(offColumn(b.list, pa.list, rows, np), b.vis)
    }

    private fun offColumn(a: List<Block>, b: List<Block>, rows: (Int) -> Int, np: Float): List<Block> {
        if (a.isEmpty()) return a
        val first = a.first().line
        val last = a.last().line
        val out = ArrayList(a)
        for (x in b.asReversed()) {
            if (x.line < first && out.none { it.line == x.line }) {
                val n = rows(x.line)
                out.add(0, Block(x.line, false, out.first().top - n * np, n, np, 0f))
            }
        }
        for (x in b) {
            if (x.line > last && out.none { it.line == x.line }) {
                out += Block(x.line, false, out.last().top + out.last().h, rows(x.line), np, 0f)
            }
        }
        return out
    }

    private class Span(val line: Int, val a: Block?, val b: Block?, var top: Float, var h: Float, var side: Int = 0)

    fun column(a: Blocks?, b: Blocks, to: Int, p: Float, room: Float, edge: (Float) -> Float): List<Draw> {
        val spans = ArrayList<Span>()
        if (a == null) {
            for (y in b.list) spans += Span(y.line, y, y, y.top, y.h)
        } else {
            fun inB(line: Int) = b.list.firstOrNull { it.line == line }
            fun inA(line: Int) = a.list.firstOrNull { it.line == line }
            fun drawn(line: Int) = spans.first { it.a?.line == line || it.b?.line == line }
            val shared = a.list.mapNotNull { x -> inB(x.line)?.let { x to it } }
            for ((x, y) in shared) spans += Span(x.line, x, y, lerp(x.top, y.top, p), lerp(x.h, y.h, p))
            val inc = shared.indexOfFirst { it.first.line == to }
            if (inc >= 0) {
                val late = p >= 0.5f
                fun at(i: Int) = if (late) shared[i].second else shared[i].first
                val nx = shared.indexOfFirst { it.first.line > to }
                val held = nx >= 0 && shared[nx].second.top > shared[nx].first.top
                if (held) {
                    for (i in shared.indices) {
                        if (shared[i].first.line <= to) continue
                        spans[i].side = if (late) 2 else 1
                        spans[i].top = at(i).top
                        spans[i].h = at(i).h
                    }
                }
                val lim = when {
                    held -> at(nx).top
                    nx >= 0 -> lerp(shared[nx].first.top, shared[nx].second.top, p)
                    else -> room
                }
                val s = spans[inc]
                s.h = min(s.top + at(inc).h, lim) - s.top
            }
            for (i in a.list.indices.reversed()) {
                val x = a.list[i]
                if (inB(x.line) != null) continue
                val h = x.h * (1f - p)
                val top = if (i + 1 < a.list.size) drawn(a.list[i + 1].line).top - h else x.top
                spans += Span(x.line, x, null, top, h)
            }
            for (i in b.list.indices) {
                val y = b.list[i]
                if (inA(y.line) != null) continue
                val top = if (i > 0) {
                    val r = drawn(b.list[i - 1].line)
                    r.top + r.h + (y.top - (b.list[i - 1].top + b.list[i - 1].h))
                } else {
                    y.top
                }
                spans += Span(y.line, null, y, top, y.h * p)
            }
        }
        val colY0 = edge(0f)
        val colY1 = edge(bottom(a, b))
        return spans.map { d ->
            val y0 = max(colY0, edge(d.top))
            val y1 = max(y0, min(colY1, edge(d.top + d.h)))
            val fa = d.a
            val fb = d.b
            val change = fa != null && fb != null && fa.big != fb.big
            var alphaA = 0f
            var alphaB = 0f
            when {
                fa != null && fb != null && (change || d.side != 0) -> {
                    alphaA = if (p < 0.5f) fa.alpha * (1f - 2f * p) else 0f
                    alphaB = if (p < 0.5f) 0f else fb.alpha * (2f * p - 1f)
                }
                fa != null && fb != null -> alphaB = lerp(fa.alpha, fb.alpha, p)
                fa != null -> alphaA = fa.alpha * (1f - p)
                fb != null -> alphaB = fb.alpha * p
            }
            Draw(d.line, d.top, d.h, y0, y1, fa, fb, alphaA, alphaB, change, d.side)
        }
    }

    fun bottom(a: Blocks?, b: Blocks) = if (a == null) b.vis else max(a.vis, b.vis)

    private fun text(texts: List<String>?, line: Int, big: Boolean, title: String, next: String?) = if (line >= 0 && texts != null) texts[line] else if (big) title else next ?: ""

    fun lines(k: Block, texts: List<String>?, title: String, next: String?, wrap: (String, Boolean) -> List<String>): List<String> {
        val w = wrap(text(texts, k.line, k.big, title, next), k.big)
        return if (k.big) w.take(k.rows) else w
    }

    fun kind(k: Block, index: Int) = when {
        k.big -> Kind.CURRENT
        k.line < index -> Kind.PREVIOUS
        else -> Kind.UPCOMING
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    fun plain(texts: List<String>, wrap: (String) -> List<String>): List<String> = texts.flatMap { if (it.isEmpty()) listOf("") else wrap(it) }

    fun plainRoom(room: Float, pitch: Float) = floor((room + EPS) / pitch) * pitch
}
