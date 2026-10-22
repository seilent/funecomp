package net.seilent.funecomp.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcTest {
    private fun u(s: String) = s.toByteArray(Charsets.UTF_8)

    private fun latin(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    private fun parse(s: String) = Lrc.parse(u(s)).lines

    private fun plain(s: String) = Lrc.parse(u(s)).plain

    private fun l(ms: Long, text: String) = Lrc.Line(ms, text)

    @Test
    fun stampsGiveOneLineEachSortedByTime() {
        val l = parse("[ar:Someone]\n[ti:Song]\n[00:01.50]one\r\n[00:03.00][00:10.25]chorus  \n[00:05.123]five\n[01:02]bare\n[00:07:40]colon\nno stamp\n[00:08.00]\n")
        assertEquals(listOf(l(1500, "one"), l(3000, "chorus"), l(5123, "five"), l(7400, "colon"), l(8000, ""), l(10250, "chorus"), l(62000, "bare")), l)
    }

    @Test
    fun theOffsetTagShiftsKeepsStampsBelowZeroAndIsReadAnywhere() {
        assertEquals(listOf(l(-300, "b"), l(1500, "a")), parse("[offset:+500]\n[00:02.00]a\n[00:00.20]b\n"))
        assertEquals(listOf(l(2250, "a")), parse("[00:02.00]a\n[OFFSET: -250]\n"))
        assertEquals(listOf(l(2000, "a")), parse("[offset:abc]\n[00:02.00]a\n"))
        assertEquals(listOf(l(3_602_000, "a")), parse("[offset:-9999999]\n[00:02.00]a\n"))
        assertEquals(listOf(l(-3_598_000, "a")), parse("[offset:9999999]\n[00:02.00]a\n"))
        assertEquals(listOf(l(2000, "a")), parse("[offset:12345678]\n[00:02.00]a\n"))
        assertEquals(listOf(l(360_000_000, "a")), parse("[6000:00.00]a\n[offset:-1]\n"))
    }

    @Test
    fun theShiftFloorsAtZeroAndKeepsTheOrder() {
        val v = listOf(l(0, "a"), l(400, "b"), l(1000, "c"))
        assertEquals(listOf(l(0, "a"), l(0, "b"), l(500, "c")), Lrc.shift(v, 500))
        assertEquals(listOf(l(500, "a"), l(900, "b"), l(1500, "c")), Lrc.shift(v, -500))
        assertEquals(v, Lrc.shift(v, 0))
        assertEquals(listOf(l(0, "x"), l(0, "y"), l(300, "z")), Lrc.shift(listOf(l(-2000, "x"), l(-100, "y"), l(300, "z")), 0))
        val tag = parse("[offset:+3000]\n[00:00.50]a\n[00:01.50]b\n[00:02.50]c\n[00:05.00]d\n")
        assertEquals(listOf(l(-2500, "a"), l(-1500, "b"), l(-500, "c"), l(2000, "d")), tag)
        assertEquals(listOf(l(0, "a"), l(0, "b"), l(0, "c"), l(2000, "d")), Lrc.shift(tag, 0))
        assertEquals(listOf(l(500, "a"), l(1500, "b"), l(2500, "c"), l(5000, "d")), Lrc.shift(tag, -3000))
        assertEquals(Lrc.shift(parse("[offset:+500]\n[00:02.00]a\n[00:04.00]b\n"), 0), Lrc.shift(parse("[offset:+300]\n[00:02.00]a\n[00:04.00]b\n"), 200))
    }

    @Test
    fun lineEndsBomLatinOneAndCjk() {
        assertEquals(listOf(l(1000, "a"), l(2000, "b"), l(3000, "c")), parse("[00:01.00]a\r[00:02.00]b\r\r[00:03.00]c"))
        assertEquals(listOf(l(1000, "café")), Lrc.parse(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + u("[00:01.00]café\n")).lines)
        assertEquals(listOf(l(1000, "café crème")), Lrc.parse(latin("[00:01.00]café crème\n")).lines)
        assertEquals(listOf(l(1000, "旅の歌")), parse("[00:01.00]旅の歌\n"))
        assertEquals(listOf(l(1000, "caf")), Lrc.parse(latin("[00:01.00]caf\u0000é\n")).lines)
        assertEquals(listOf(l(1000, "\ufffd ok")), parse("[00:01.00]\ufffd ok\n"))
    }

    @Test
    fun onlyAsciiControlAndSpaceAreTrimmed() {
        assertEquals(listOf(l(1000, "\u3000歌\u3000")), parse("[00:01.00] \u3000歌\u3000\t\n"))
        assertEquals(listOf(l(1000, "\u00a0x\u00a0")), parse("[00:01.00]\u00a0x\u00a0 \n"))
        assertEquals(listOf("\u3000one\u3000"), plain("  \u3000one\u3000 \n"))
    }

    @Test
    fun badStampsAndStampsAfterTextGiveNothingTimed() {
        assertTrue(parse("").isEmpty() && parse("hello\nworld\n").isEmpty())
        assertTrue(parse("[00:61.00]x\n[aa:10.00]y\n[00:1x.00]z\n[00:01.0000]w\n[12345:00.00]v\n[00:01.00").isEmpty())
        assertEquals(listOf(l(1000, "[00:02.00]x")), parse("[00:01.00] [00:02.00]x\n"))
        assertEquals(listOf(l(1000, "x")), parse(" \t[00:01.00]x\n"))
        assertEquals(listOf(l(1050, "x")), parse("[0:01.05]x\n"))
        assertEquals(listOf(l(1005, "x")), parse("[0:01:005]x\n"))
    }

    @Test
    fun sizeAndLineCaps() {
        val big = ByteArray(Lrc.CAP + 1) { 'a'.code.toByte() }
        u("[00:01.00]x\n").copyInto(big)
        assertTrue(Lrc.parse(big).lines.isEmpty())
        val fit = ByteArray(Lrc.CAP) { ' '.code.toByte() }
        u("[00:01.00]x\n").copyInto(fit)
        assertEquals(1, Lrc.parse(fit).lines.size)
        val many = StringBuilder()
        while (many.length + 6 < Lrc.CAP) many.append("[0:01]")
        many.append("y")
        assertEquals(1024, parse(many.toString()).size)
        val wide = StringBuilder()
        repeat(2000) { wide.append("[0:01]") }
        wide.append("w".repeat(Lrc.CAP - wide.length))
        assertTrue(parse(wide.toString()).sumOf { u(it.text).size } <= Lrc.CAP)
        val rows = (0 until 1100).joinToString("\n") { "row$it" }
        assertEquals(1024, plain(rows).size)
    }

    @Test
    fun plainTextRules() {
        assertEquals(listOf("First line", "", "[Chorus]", "Second verse", "[x] kept"),
            plain("[ar:Someone]\n[ti:Song]\n[al:Album]\n[by:me]\n[offset:100]\n[length:03:00]\n\nFirst line  \n\n[Chorus]\nSecond verse\n[x] kept\n  \n"))
        val p = Lrc.parse(u("[ti:Song]\n[00:01.00]one\nloose line\n"))
        assertTrue(p.plain.isEmpty())
        assertEquals(listOf(l(1000, "one")), p.lines)
        assertTrue(plain("[ar:a]\n[ti:b]\n").isEmpty() && plain("").isEmpty())
        assertEquals(listOf("café"), Lrc.parse(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + u("café\n")).plain)
        assertEquals(listOf("café crème"), Lrc.parse(latin("café crème\n")).plain)
        assertEquals(listOf("one", "two", "", "three", "four"), plain("one\r\ntwo\r\n\r\nthree\rfour\n"))
        assertTrue(Lrc.parse(ByteArray(Lrc.CAP + 1) { 'a'.code.toByte() }).plain.isEmpty())
        assertTrue(parse("hello\nworld\n").isEmpty())
    }

    @Test
    fun haveIsSyncedOverPlainOverNone() {
        assertEquals(Lrc.Have.None, Lrc.parse(u("")).have)
        assertEquals(Lrc.Have.Synced, Lrc.parse(u("[00:00.00]\nx\n")).have)
        assertEquals(Lrc.Have.Plain, Lrc.parse(u("p\n")).have)
    }

    @Test
    fun bytesTheDeviceKeepsDecodeToOneReplacementPerMaximalSubpart() {
        fun line(vararg v: Int) = Lrc.parse(u("[00:01.00]") + ByteArray(v.size) { v[it].toByte() } + u("x\n")).lines.single().text
        val r = "\uFFFD"
        assertEquals(r.repeat(2) + "x", line(0xC0, 0x80))
        assertEquals(r.repeat(3) + "x", line(0xED, 0xA0, 0x80))
        assertEquals(r.repeat(4) + "x", line(0xF4, 0x90, 0x80, 0x80))
        assertEquals(r.repeat(4) + "x", line(0xF0, 0x80, 0x80, 0x80))
        assertEquals(r.repeat(3) + "x", line(0xE0, 0x80, 0x80))
        assertEquals(r + "x", line(0xEF, 0xBF, 0xBD))
        assertEquals("\uD55C\uD7FB\uE000\uD800\uDC00\uDBFF\uDFFFx", line(0xED, 0x95, 0x9C, 0xED, 0x9F, 0xBB, 0xEE, 0x80, 0x80, 0xF0, 0x90, 0x80, 0x80, 0xF4, 0x8F, 0xBF, 0xBF))
    }

    @Test
    fun cutKeepsWholeCodePointsAt4096Bytes() {
        val cut = "a".repeat(Lrc.LINE_MAX - 1) + "あ" + "tail"
        assertEquals("a".repeat(Lrc.LINE_MAX - 1), Lrc.cut(cut))
        assertEquals("short", Lrc.cut("short"))
        val exact = "a".repeat(Lrc.LINE_MAX - 3) + "あ" + "x"
        assertEquals(Lrc.LINE_MAX, u(Lrc.cut(exact)).size)
        assertEquals(Lrc.LINE_MAX - 1, u(Lrc.cut("あ".repeat(2000))).size)
    }

    @Test
    fun fuzzLinesStaySortedAndNotNegative() {
        var seed = 4242
        fun rnd(): Int {
            seed = seed * 1103515245 + 12345
            return seed ushr 8
        }
        val base = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + latin("[ar:A]\n[offset:-120]\n[00:01.50]one\n[00:03.00][00:10.25]chorus\n[01:02]bare\n[00:07:40]colon é\n[00:08.00]\n")
        val alphabet = latin("[]:.+-0123456789 \n\roffsetÃéÿ")
        var runs = 0
        for (n in 0..base.size) {
            Lrc.parse(base.copyOf(n))
            runs++
        }
        repeat(4000) {
            val m = base.toMutableList()
            repeat(1 + rnd() % 10) {
                val p = rnd() % (m.size + 1)
                val op = rnd() % 3
                val ch = if (rnd() % 2 != 0) alphabet[rnd() % alphabet.size] else rnd().toByte()
                if (op == 0 && p < m.size) m[p] = ch else if (op == 1) m.add(p, ch) else if (p < m.size) repeat(minOf(1 + rnd() % 8, m.size - p)) { m.removeAt(p) }
            }
            val got = Lrc.parse(m.toByteArray()).lines
            assertTrue(got.zipWithNext().all { (a, b) -> a.ms <= b.ms })
            val moved = Lrc.shift(got, (rnd() % 120001).toLong() - 60000)
            assertTrue(moved.zipWithNext().all { (a, b) -> a.ms <= b.ms } && moved.all { it.ms >= 0 })
            runs++
        }
        repeat(200) {
            Lrc.parse(ByteArray(rnd() % 4096) { rnd().toByte() })
            runs++
        }
        assertTrue(runs > 4000)
    }
}
