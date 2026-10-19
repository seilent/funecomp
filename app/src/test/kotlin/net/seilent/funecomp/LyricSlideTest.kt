package net.seilent.funecomp

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.ExpOut6
import net.seilent.funecomp.ui.LyricRows
import net.seilent.funecomp.ui.LyricSlide
import net.seilent.funecomp.ui.LyricSlide.Slide
import net.seilent.funecomp.ui.Ui
import net.seilent.funecomp.ui.npLyricBottom
import net.seilent.funecomp.ui.npLyricTop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

class LyricSlideTest {
    private val stamps = listOf(0L, 2000L, 3000L, 3200L, 3280L, 5000L)

    @Test
    fun theHoldCapsTheTimeAndAShortHoldSnaps() {
        assertEquals(Slide(0, 2000, 350), LyricSlide.start(0, 1, stamps, 2000, 2000))
        assertEquals(Slide(1, 3000, 200), LyricSlide.start(1, 2, stamps, 3000, 3000))
        assertNull(LyricSlide.start(2, 3, stamps, 3200, 3200))
    }

    @Test
    fun theClockIsAnchoredAtTheStamp() {
        val s = LyricSlide.start(4, 5, stamps, 5100, 9000)!!
        assertEquals(Slide(4, 8900, 350), s)
        assertFalse(LyricSlide.done(s, 9249))
        assertTrue(LyricSlide.done(s, 9250))
        assertNull(LyricSlide.start(0, 1, stamps, 2300, 9000))
        assertEquals(Slide(0, 8750, 350), LyricSlide.start(0, 1, stamps, 2250, 9000))
        assertNull(LyricSlide.start(0, 1, stamps, 2251, 9000))
    }

    @Test
    fun onlyTheNextLineSlides() {
        assertEquals(Slide(-1, 1000, 350), LyricSlide.start(-1, 0, stamps, 0, 1000))
        assertNull(LyricSlide.start(0, 2, stamps, 3000, 3000))
        assertNull(LyricSlide.start(2, 1, stamps, 2000, 3000))
        assertNull(LyricSlide.start(1, 1, stamps, 2000, 3000))
        assertNull(LyricSlide.start(-2, -1, stamps, 0, 3000))
    }

    @Test
    fun pFollowsExpOut6OnTheFrameClock() {
        val s = Slide(0, 1000, 350)
        assertEquals(0f, LyricSlide.p(s, 1000), 0f)
        assertEquals(ExpOut6.transform(0.5f), LyricSlide.p(s, 1175), 1e-6f)
        assertEquals(1f, LyricSlide.p(s, 1400), 0f)
        assertEquals(0f, LyricSlide.p(s, 900), 0f)
    }

    private fun cexp(x: Double): Double {
        val r = x / 1024
        var term = 1.0
        var sum = 1.0
        for (i in 1 until 12) {
            term *= r / i
            sum += term
        }
        repeat(10) { sum *= sum }
        return sum
    }

    private val table = IntArray(513) { i ->
        val t = i.toDouble() / 512
        val q = (1 - (cexp(6 * (1 - t)) - 1) / (cexp(6.0) - 1)) * 65536
        (if (q < 0) q - 0.5 else q + 0.5).toInt()
    }

    private fun lookup(t: Int): Int {
        val p = t.toLong() * 512
        val i = (p shr 16).toInt()
        if (i >= 512) return table[512]
        val f = p and 0xFFFF
        return table[i] + (((table[i + 1] - table[i]).toLong() * f) shr 16).toInt()
    }

    @Test
    fun expOut6MatchesTheDeviceTable() {
        for (i in 0..64) {
            val dev = lookup(i * 1024) / 65536f
            assertTrue("t $i/64: app ${ExpOut6.transform(i / 64f)} device $dev", abs(ExpOut6.transform(i / 64f) - dev) <= 1f / 256)
        }
    }

    private class Row(val line: Int, val y0: Int, val y1: Int, val top: Float, val h: Float, val a: Float, val b: Float, val change: Boolean, val side: Int)

    private class Frame(val p: Float, val bottom: Float, val rows: MutableList<Row> = ArrayList())

    private class Case(val name: String, val top: Float, val room: Float, val cp: Float, val np: Float, val texts: List<String>, val counts: Map<String, Pair<Int, Int>>, val from: Int, val to: Int, val frames: List<Frame>) {
        val wrap: (String, Boolean) -> List<String> = { t, big -> List(counts[t]?.let { if (big) it.second else it.first } ?: 0) { t } }

        fun blocks(i: Int, room: Float = this.room) = LyricRows.blocks(texts, i, "", null, wrap, room, cp, np)

        fun pair(room: Float = this.room) = LyricRows.pair(blocks(from, room), blocks(to, room), { wrap(texts[it], false).size }, np)
    }

    private fun fx(s: String) = s.toInt() / 65536f

    private val names = listOf("short", "long4", "fromlong", "cjk", "first", "empty", "prevhide", "last", "oneto1", "oneto2", "line01", "twoto1", "twoto2", "first2", "partfit")

    private fun cases(file: String): List<Case> {
        val text = javaClass.getResource("/lyrhold/$file")!!.readText()
        assertTrue(file, text.lineSequence().first().startsWith("source funeos b96b878ce34a81115783432096a12f47fd333c56 board v1 ${if (file.contains("-large")) "large" else "standard"} "))
        val out = ArrayList<Case>()
        var meta = emptyList<String>()
        val texts = ArrayList<String>()
        val counts = HashMap<String, Pair<Int, Int>>()
        var slide = emptyList<String>()
        var frames = ArrayList<Frame>()
        fun flush() {
            if (slide.isEmpty()) return
            out += Case(file, fx(meta[7]), fx(meta[9]) - fx(meta[7]), fx(meta[3]), fx(meta[5]), texts.toList(), counts.toMap(), slide[2].toInt(), slide[3].toInt(), frames)
            slide = emptyList()
            frames = ArrayList()
            texts.clear()
        }
        for (l in text.lines().drop(1)) {
            val f = l.split(" ")
            when (f.getOrNull(1)) {
                "meta" -> {
                    flush()
                    meta = f
                    assertEquals("$file fade", "65536", f[19])
                }
                "line" -> {
                    val t = f.drop(6).joinToString(" ")
                    texts += t
                    counts[t] = f[4].toInt() to f[5].toInt()
                }
                "slide" -> slide = f
                "t" -> frames += Frame(fx(f[4]), fx(f[6]))
                "row" -> frames.last().rows += Row(f[2].toInt(), f[3].toInt(), f[4].toInt(), fx(f[5]), fx(f[6]), fx(f[7]), fx(f[8]), f[9] == "1", f[10].toInt())
            }
        }
        flush()
        return out
    }

    private val all by lazy { names.flatMap { n -> listOf("std", "large").flatMap { cases("$n-$it.txt") } } }

    private class Got(val line: Int, val side: Int, val d: LyricRows.Draw, val alpha: Float)

    private fun rows(draws: List<LyricRows.Draw>): List<Got> = draws.flatMap { d ->
        when {
            d.a != null && d.b != null && (d.change || d.side != 0) -> listOf(Got(d.line, 1, d, d.alphaA), Got(d.line, 2, d, d.alphaB))
            d.a != null && d.b != null -> listOf(Got(d.line, 0, d, d.alphaB))
            d.a != null -> listOf(Got(d.line, 1, d, d.alphaA))
            else -> listOf(Got(d.line, 2, d, d.alphaB))
        }
    }

    private fun listed(a: Float) = (a * 256).roundToInt() > 0

    private fun near(a: Float) = abs(a - 1f / 512) <= 1f / 256

    @Test
    fun theColumnMatchesTheDeviceRows() {
        var checked = 0
        var misses = 0
        assertEquals(30, all.map { it.name }.distinct().size)
        for (c in all) {
            val (pa, pb) = c.pair()
            assertEquals("${c.name} frames", 17, c.frames.size)
            for (f in c.frames) {
                val got = rows(LyricRows.column(pa, pb, c.to, f.p, c.room) { ceil(c.top + it) })
                val at = "${c.name} ${c.from} to ${c.to} p ${f.p}"
                assertEquals("$at bottom", f.bottom, c.top + LyricRows.bottom(pa, pb), 1f / 64)
                var i = 0
                var j = 0
                while (i < f.rows.size || j < got.size) {
                    val r = f.rows.getOrNull(i)
                    val g = got.getOrNull(j)
                    if (r != null && g != null && r.line == g.line && r.side == g.side) {
                        val row = "$at line ${r.line} side ${r.side}"
                        assertEquals("$row top", r.top, c.top + g.d.top, 1f / 64)
                        assertEquals("$row h", r.h, g.d.h, 1f / 64)
                        assertEquals("$row old alpha", r.a, g.d.alphaA, 1f / 256)
                        assertEquals("$row new alpha", r.b, g.d.alphaB, 1f / 256)
                        assertEquals("$row form change", r.change, g.d.change)
                        assertTrue("$row y0 ${r.y0} got ${g.d.y0}", abs(r.y0 - g.d.y0) <= 1f)
                        assertTrue("$row y1 ${r.y1} got ${g.d.y1}", abs(r.y1 - g.d.y1) <= 1f)
                        checked++
                        i++
                        j++
                    } else if (g != null && !listed(g.alpha)) {
                        j++
                    } else if (g != null && near(g.alpha)) {
                        misses++
                        j++
                    } else if (r != null && near(if (r.side == 1) r.a else r.b)) {
                        misses++
                        i++
                    } else {
                        fail("$at gold ${r?.line} side ${r?.side} app ${g?.line} side ${g?.side}")
                    }
                }
            }
        }
        assertEquals(CHECKED, checked)
        assertEquals(MISSES, misses)
    }

    private class Seen(val line: Int, val side: Int, val y0: Float, val y1: Float, val top: Float)

    private fun seen(draws: List<LyricRows.Draw>) = rows(draws).filter { listed(it.alpha) }.map { Seen(it.line, it.side, it.d.y0, it.d.y1, it.d.top) }

    private fun watch(c: Case, room: Float, scale: Float): List<String> {
        val bad = ArrayList<String>()
        val edge = { v: Float -> ceil(v * scale) }
        val (pa, pb) = c.pair(room)
        val d = Tok.motion_lyricSlide
        class F(val rows: List<Seen>, val on: Boolean, val bottom: Float)
        val frames = ArrayList<F>()
        val a = c.blocks(c.from, room)
        val b = c.blocks(c.to, room)
        frames += F(seen(LyricRows.column(null, a, c.from, 1f, room, edge)), false, LyricRows.bottom(null, a))
        var e = 0f
        while (e < d) {
            frames += F(seen(LyricRows.column(pa, pb, c.to, ExpOut6.transform(e / d), room, edge)), true, LyricRows.bottom(pa, pb))
            e += 16f
        }
        frames += F(seen(LyricRows.column(null, b, c.to, 1f, room, edge)), false, LyricRows.bottom(null, b))
        for ((k, fr) in frames.withIndex()) {
            val r = fr.rows
            val on = fr.on
            val at = "${c.name} ${c.from} to ${c.to} room $room scale $scale frame $k"
            for (i in r.indices) for (j in i + 1 until r.size) {
                if (r[i].y1 > r[i].y0 && r[j].y1 > r[j].y0 && minOf(r[i].y1, r[j].y1) > maxOf(r[i].y0, r[j].y0)) bad += "$at overlap ${r[i].line} ${r[j].line}"
            }
            if (k == 0) continue
            val last = frames[k - 1].rows
            val lastOn = frames[k - 1].on
            if (on && !lastOn) {
                for (x in r) for (y in last) if (x.line == y.line && y.side == 0 && x.side != 2 && x.top > y.top + EPS) bad += "$at start down ${x.line}"
                continue
            }
            if (on && lastOn && fr.bottom > frames[k - 1].bottom) bad += "$at bottom down"
            for (x in r) for (y in last) {
                if (x.line != y.line || x.side != y.side) continue
                if (x.top > y.top + EPS) bad += "$at down ${x.line} side ${x.side}"
                if (x.y1 > y.y1) bad += "$at clip down ${x.line} side ${x.side}"
            }
        }
        return bad
    }

    @Test
    fun noRowOrClipMovesDownAndNoClipsOverlapAtEveryRoom() {
        val density = Density(2.625f)
        val rooms = SIZES.map { s ->
            val l = s.bars.getLeft(density, LayoutDirection.Ltr) + s.bars.getRight(density, LayoutDirection.Ltr)
            val v = s.bars.getTop(density) + s.bars.getBottom(density)
            val ui = Ui.of(s.w * density.density - l, s.h * density.density - v)
            ui.npLyricBottom - ui.npLyricTop to ui.u
        }
        val bad = ArrayList<String>()
        for (c in all) {
            bad += watch(c, c.room, 1f)
            for ((room, u) in rooms) bad += watch(c, room, u)
        }
        assertEquals(bad.take(20).joinToString("\n"), 0, bad.size)
    }

    private fun case(name: String) = cases("$name.txt").first()

    private fun draws(c: Case, p: Float): List<LyricRows.Draw> {
        val (pa, pb) = c.pair()
        return LyricRows.column(pa, pb, c.to, p, c.room) { ceil(c.top + it) }
    }

    @Test
    fun aGrowingLineHoldsTheRowsBelowItThenShowsThemLower() {
        val c = case("oneto2-std")
        assertEquals(2 to 3, c.from to c.to)
        val below = Math.nextDown(0.5f)
        val held = draws(c, below)
        val four = held.single { it.line == 4 }
        assertEquals(1, four.side)
        assertEquals(67.2f, four.top, 1f / 64)
        val after = draws(c, 0.5f)
        val four2 = after.single { it.line == 4 }
        assertEquals(2, four2.side)
        assertEquals(80.4f, four2.top, 1f / 64)
        for ((p, d) in listOf(below to held, 0.5f to after)) {
            val three = d.single { it.line == 3 }
            val lim = d.single { it.line == 4 }.top
            val h = (if (p < 0.5f) three.a else three.b)?.h ?: 0f
            assertEquals("p $p", min(three.top + h, lim) - three.top, three.h, 1f / 64)
        }
    }

    @Test
    fun aChangeWhereNothingBelowMovesDownSlidesAsF13() {
        for (name in listOf("twoto2-std", "empty-std", "twoto2-large", "empty-large")) {
            val c = case(name)
            var e = 0f
            while (e <= Tok.motion_lyricSlide) {
                val p = ExpOut6.transform(e / Tok.motion_lyricSlide)
                val d = draws(c, p)
                assertTrue("$name p $p", d.none { it.a != null && it.b != null && it.side != 0 })
                if (name.startsWith("empty")) {
                    val inc = d.single { it.line == c.to }
                    assertNull(inc.a)
                    assertEquals("$name p $p", (inc.b?.h ?: 0f) * p, inc.h, 1e-4f)
                }
                e += 16f
            }
        }
        val one = case("oneto1-std")
        val (pa, pb) = LyricRows.pair(one.blocks(0), one.blocks(1), { one.wrap(one.texts[it], false).size }, one.np)
        assertEquals(pa.list.single { it.line == 2 }.top, pb.list.single { it.line == 2 }.top, 1e-4f)
        for (p in listOf(0.25f, 0.75f)) {
            assertTrue("line 0 to 1 p $p", LyricRows.column(pa, pb, 1, p, one.room) { it }.none { it.side != 0 })
        }
    }

    private fun look(draws: List<LyricRows.Draw>, to: Int, skip: Boolean) = draws.mapNotNull { d ->
        val forms = d.forms.filter { it.second > 0f }.map { it.first.big to it.second }
        if (forms.isEmpty()) null else listOf(d.line, (d.top * 64).roundToInt(), if (skip && d.line == to) null else (d.h * 64).roundToInt(), forms.map { (big, a) -> big to (a * 256).roundToInt() })
    }.toSet()

    @Test
    fun theSlideStartsOnAAndEndsOnB() {
        for (c in all) {
            val (pa, pb) = c.pair()
            val edge = { v: Float -> ceil(c.top + v) }
            val a = LyricRows.column(null, c.blocks(c.from), c.from, 1f, c.room, edge)
            val b = LyricRows.column(null, c.blocks(c.to), c.to, 1f, c.room, edge)
            assertEquals("${c.name} ${c.from} to ${c.to} at 0", look(a, c.to, true), look(LyricRows.column(pa, pb, c.to, 0f, c.room, edge), c.to, true))
            assertEquals("${c.name} ${c.from} to ${c.to} at 1", look(b, c.to, false), look(LyricRows.column(pa, pb, c.to, 1f, c.room, edge), c.to, false))
        }
    }

    private companion object {
        const val EPS = 1e-4f
        const val CHECKED = 10271
        const val MISSES = 0
    }
}
