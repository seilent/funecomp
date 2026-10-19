package net.seilent.funecomp

import net.seilent.funecomp.ui.ExpOut6
import net.seilent.funecomp.ui.LyricRows
import net.seilent.funecomp.ui.LyricSlide
import net.seilent.funecomp.ui.LyricSlide.Slide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil

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

    private class Row(val line: Int, val y0: Int, val y1: Int, val top: Float, val h: Float, val a: Float, val b: Float, val change: Boolean)

    private class Frame(val p: Float, val rows: MutableList<Row> = ArrayList())

    private class Case(val name: String, val top: Float, val room: Float, val cp: Float, val np: Float, val texts: List<String>, val counts: Map<String, Pair<Int, Int>>, val from: Int, val to: Int, val frames: List<Frame>)

    private fun fx(s: String) = s.toInt() / 65536f

    private fun cases(file: String): List<Case> {
        val text = javaClass.getResource("/lyrslide/$file")!!.readText()
        assertTrue(text.lineSequence().first().startsWith("source funeos 486c89750b3238f761d7d53791341e6ed86e839c board v1"))
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
                }
                "line" -> {
                    val t = f.drop(6).joinToString(" ")
                    texts += t
                    counts[t] = f[4].toInt() to f[5].toInt()
                }
                "slide" -> slide = f
                "t" -> frames += Frame(fx(f[4]))
                "row" -> frames.last().rows += Row(f[2].toInt(), f[3].toInt(), f[4].toInt(), fx(f[5]), fx(f[6]), fx(f[7]), fx(f[8]), f[9] == "1")
            }
        }
        flush()
        return out
    }

    @Test
    fun theColumnMatchesTheDeviceRows() {
        val files = listOf("short-1000", "short-350", "short-200", "short-120", "long4-1000", "fromlong-1000", "first-1000", "empty-1000", "prevhide-1000", "last-1000")
        var checked = 0
        for (file in files) {
            for (c in cases("$file.txt")) {
                val wrap: (String, Boolean) -> List<String> = { t, big -> List(c.counts[t]?.let { if (big) it.second else it.first } ?: 0) { t } }
                val a = LyricRows.blocks(c.texts, c.from, "", null, wrap, c.room, c.cp, c.np)
                val b = LyricRows.blocks(c.texts, c.to, "", null, wrap, c.room, c.cp, c.np)
                val (pa, pb) = LyricRows.pair(a, b, { wrap(c.texts[it], false).size }, c.np)
                for (f in c.frames) {
                    val got = LyricRows.column(pa, pb, f.p) { ceil(c.top + it) }
                    val at = "${c.name} ${c.from} to ${c.to} p ${f.p}"
                    assertEquals("$at lines", f.rows.map { it.line }, got.map { it.line })
                    for ((r, g) in f.rows.zip(got)) {
                        val row = "$at line ${r.line}"
                        assertEquals("$row top", r.top, c.top + g.top, 1f / 64)
                        assertEquals("$row h", r.h, g.h, 1f / 64)
                        assertEquals("$row old alpha", r.a, g.alphaA, 1f / 256)
                        assertEquals("$row new alpha", r.b, g.alphaB, 1f / 256)
                        assertEquals("$row form change", r.change, g.change)
                        assertTrue("$row y0 ${r.y0} got ${g.y0}", abs(r.y0 - g.y0) <= 1f)
                        assertTrue("$row y1 ${r.y1} got ${g.y1}", abs(r.y1 - g.y1) <= 1f)
                        checked++
                    }
                }
            }
        }
        assertTrue(checked > 900)
    }
}
