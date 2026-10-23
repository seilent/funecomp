package net.seilent.funecomp.state

object Lrc {
    const val CAP = 64 shl 10
    const val LINE_MAX = 4096
    const val MAX_STAMP_MS = 100L * 3600 * 1000
    private const val MAX_OFFSET_MS = 3600L * 1000
    private const val MAX_LINES = 1024
    private const val SPACE = ' '.code
    private const val TAB = '\t'.code
    private const val CR = '\r'.code
    private const val LF = '\n'.code
    private const val OPEN = '['.code
    private const val CLOSE = ']'.code
    private const val COLON = ':'.code
    private const val DOT = '.'.code
    private const val BAD = 0xFFFD

    enum class Have { None, Synced, Plain }

    data class Line(val ms: Long, val text: String)

    data class Parsed(val lines: List<Line>, val plain: List<String>) {
        val have: Have get() = if (lines.isNotEmpty()) Have.Synced else if (plain.isNotEmpty()) Have.Plain else Have.None
    }

    private class Bytes(val b: ByteArray, val from: Int, val to: Int) {
        val size: Int get() = to - from

        operator fun get(i: Int): Int = b[from + i].toInt() and 0xFF

        fun sub(a: Int, z: Int = size) = Bytes(b, from + a, from + minOf(z, size))

        fun indexOf(c: Int, start: Int = 0): Int {
            for (i in start until size) if (get(i) == c) return i
            return -1
        }

        fun text() = decode(b, from, to)

        fun trim(): Bytes {
            var a = 0
            var z = size
            while (a < z && get(a) <= SPACE) a++
            while (z > a && get(z - 1) <= SPACE) z--
            return sub(a, z)
        }
    }

    fun parse(p: ByteArray): Parsed {
        if (p.size > CAP) return Parsed(emptyList(), emptyList())
        val skip = if (p.size >= 3 && p[0] == 0xEF.toByte() && p[1] == 0xBB.toByte() && p[2] == 0xBF.toByte()) 3 else 0
        val raw = p.copyOfRange(skip, p.size)
        val bytes = if (utf8Valid(raw)) raw else latin1ToUtf8(raw)
        val text = Bytes(bytes, 0, bytes.size)
        val out = ArrayList<Line>()
        val plain = ArrayList<String>()
        var shift = 0L
        var a = 0
        var kept = 0
        while (a < text.size) {
            var b = a
            while (b < text.size && text[b] != CR && text[b] != LF) b++
            val line = text.sub(a, b)
            a = if (b >= text.size) text.size else b + 1 + if (text[b] == CR && b + 1 < text.size && text[b + 1] == LF) 1 else 0
            val stamps = ArrayList<Long>()
            var i = 0
            while (i < line.size && (line[i] == SPACE || line[i] == TAB)) i++
            var tags = 0
            var ids = 0
            while (i < line.size && line[i] == OPEN) {
                val e = line.indexOf(CLOSE, i)
                if (e < 0) break
                val tag = line.sub(i + 1, e)
                val st = stamp(tag)
                if (st != null) {
                    stamps += st
                } else {
                    offset(tag)?.let { shift = it }
                }
                i = e + 1
                tags++
                if (idTag(tag)) ids++
            }
            if (stamps.isEmpty()) {
                val rest = line.sub(i).trim()
                if ((rest.size > 0 || ids < tags || tags == 0) && plain.size < MAX_LINES) plain += (if (tags > 0) line.trim() else rest).text()
                continue
            }
            val words = line.sub(i).trim()
            val said = words.text()
            for (v in stamps) {
                if (out.size >= MAX_LINES || kept + words.size > CAP) break
                kept += words.size
                out += Line(v, said)
            }
        }
        if (out.isNotEmpty()) plain.clear()
        while (plain.isNotEmpty() && plain.last().isEmpty()) plain.removeAt(plain.size - 1)
        val first = plain.indexOfFirst { it.isNotEmpty() }
        val lines = out.map { it.copy(ms = (it.ms - shift).coerceAtMost(MAX_STAMP_MS)) }.sortedBy { it.ms }
        return Parsed(lines, if (first < 0) emptyList() else plain.subList(first, plain.size).toList())
    }

    fun shift(lines: List<Line>, ms: Long): List<Line> = lines.map { it.copy(ms = (it.ms - ms).coerceIn(0, MAX_STAMP_MS)) }

    fun cut(text: String): String {
        val b = text.toByteArray(Charsets.UTF_8)
        if (b.size <= LINE_MAX) return text
        var n = LINE_MAX
        while (n > 0 && (b[n].toInt() and 0xC0) == 0x80) n--
        return String(b, 0, if (n > 0) n else LINE_MAX, Charsets.UTF_8)
    }

    private fun decode(b: ByteArray, from: Int, to: Int): String {
        val out = StringBuilder(to - from)
        var i = from
        while (i < to) {
            val c = b[i++].toInt() and 0xFF
            if (c < 0x80) {
                out.append(c.toChar())
                continue
            }
            var need: Int
            var cp: Int
            var lo = 0x80
            var hi = 0xBF
            when (c) {
                in 0xC2..0xDF -> {
                    need = 1
                    cp = c and 0x1F
                }
                in 0xE0..0xEF -> {
                    need = 2
                    cp = c and 0x0F
                    if (c == 0xE0) lo = 0xA0
                    if (c == 0xED) hi = 0x9F
                }
                in 0xF0..0xF4 -> {
                    need = 3
                    cp = c and 0x07
                    if (c == 0xF0) lo = 0x90
                    if (c == 0xF4) hi = 0x8F
                }
                else -> {
                    out.append(BAD.toChar())
                    continue
                }
            }
            while (need > 0) {
                val n = if (i < to) b[i].toInt() and 0xFF else -1
                if (n !in lo..hi) break
                i++
                cp = (cp shl 6) or (n and 0x3F)
                lo = 0x80
                hi = 0xBF
                need--
            }
            if (need > 0) out.append(BAD.toChar()) else out.appendCodePoint(cp)
        }
        return out.toString()
    }

    private fun digits(s: Bytes, lo: Int, hi: Int): Long? {
        if (s.size < lo || s.size > hi) return null
        var v = 0L
        for (i in 0 until s.size) {
            val c = s[i]
            if (c < '0'.code || c > '9'.code) return null
            v = v * 10 + (c - '0'.code)
        }
        return v
    }

    private fun stamp(t: Bytes): Long? {
        val c = t.indexOf(COLON)
        if (c < 0) return null
        val rest = t.sub(c + 1)
        var f = -1
        for (i in 0 until rest.size) if (rest[i] == DOT || rest[i] == COLON) {
            f = i
            break
        }
        val m = digits(t.sub(0, c), 1, 4) ?: return null
        val s = digits(if (f < 0) rest else rest.sub(0, f), 1, 2) ?: return null
        if (s >= 60) return null
        var frac = 0L
        if (f >= 0) {
            val fs = rest.sub(f + 1)
            frac = digits(fs, 1, 3) ?: return null
            for (k in fs.size until 3) frac *= 10
        }
        return (m * 60 + s) * 1000 + frac
    }

    private fun offset(t: Bytes): Long? {
        if (t.size < 7) return null
        val want = "offset:"
        for (i in 0 until 7) {
            val c = t[i]
            val low = if (c in 'A'.code..'Z'.code) c + 32 else c
            if (low != want[i].code) return null
        }
        var v = t.sub(7)
        while (v.size > 0 && v[0] == SPACE) v = v.sub(1)
        val neg = v.size > 0 && v[0] == '-'.code
        if (v.size > 0 && (v[0] == '-'.code || v[0] == '+'.code)) v = v.sub(1)
        val n = digits(v, 1, 7) ?: return null
        return (if (neg) -n else n).coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
    }

    private fun idTag(t: Bytes): Boolean {
        val c = t.indexOf(COLON)
        if (c <= 0) return false
        for (i in 0 until c) if (t[i] !in 'A'.code..'Z'.code && t[i] !in 'a'.code..'z'.code) return false
        return true
    }

    private fun next(b: ByteArray, at: Int): Pair<Int, Int> {
        var p = at
        var c = b[p++].toInt() and 0xFF
        if (c >= 0xF8 || c in 0x80 until 0xC0) return BAD to p
        var n = 0
        when {
            c >= 0xF0 -> {
                c = c and 0x07
                n = 3
            }
            c >= 0xE0 -> {
                c = c and 0x0F
                n = 2
            }
            c >= 0xC0 -> {
                c = c and 0x1F
                n = 1
            }
        }
        while (n > 0) {
            if (p >= b.size || (b[p].toInt() and 0xC0) != 0x80) {
                c = BAD
                break
            }
            c = (c shl 6) or (b[p++].toInt() and 0x3F)
            n--
        }
        return c to p
    }

    private fun utf8Valid(b: ByteArray): Boolean {
        var i = 0
        while (i < b.size) {
            if ((b[i].toInt() and 0x80) == 0) {
                i++
                continue
            }
            val before = i
            val (c, p) = next(b, i)
            i = p
            val replacement = b.size - before >= 3 && b[before] == 0xEF.toByte() && b[before + 1] == 0xBF.toByte() && b[before + 2] == 0xBD.toByte()
            if (c == BAD && !replacement) return false
        }
        return true
    }

    private fun latin1ToUtf8(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        for (x in b) {
            val c = x.toInt() and 0xFF
            if (c == 0) break
            if (c < 0x80) {
                out.write(c)
            } else {
                out.write(0xC0 or (c shr 6))
                out.write(0x80 or (c and 0x3F))
            }
        }
        return out.toByteArray()
    }
}
