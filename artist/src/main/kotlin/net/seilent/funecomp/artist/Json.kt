package net.seilent.funecomp.artist

class J(val v: Any?) {
    operator fun get(key: String) = J((v as? Map<*, *>)?.get(key))
    operator fun get(i: Int) = J((v as? List<*>)?.getOrNull(i))
    val list: List<J> get() = (v as? List<*>)?.map { J(it) } ?: emptyList()
    val entries: List<Pair<String, J>> get() = (v as? Map<*, *>)?.map { (k, x) -> k.toString() to J(x) } ?: emptyList()
    val str: String? get() = v as? String
    val long: Long? get() = (v as? Number)?.toLong()
    val double: Double? get() = (v as? Number)?.toDouble()
    val bool: Boolean? get() = v as? Boolean
    val exists: Boolean get() = v != null

    companion object {
        const val MAX_DEPTH = 256

        fun parse(s: String): J {
            val r = Reader(s)
            val v = r.value()
            r.ws()
            if (r.i != s.length) r.fail()
            return J(v)
        }
    }

    private class Reader(val s: String) {
        var i = 0

        fun fail(): Nothing = throw IllegalArgumentException("bad json at $i")

        fun ws() {
            while (i < s.length && s[i] <= ' ') i++
        }

        fun peek(): Char = if (i < s.length) s[i] else fail()

        var depth = 0

        fun value(): Any? {
            ws()
            return when (val c = peek()) {
                '{' -> nested { obj() }
                '[' -> nested { arr() }
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else fail()
            }
        }

        fun <T> nested(block: () -> T): T {
            if (++depth > MAX_DEPTH) fail()
            val v = block()
            depth--
            return v
        }

        fun lit(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) fail()
            i += w.length
            return v
        }

        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (peek() == '}') {
                i++
                return m
            }
            while (true) {
                ws()
                if (peek() != '"') fail()
                val k = str()
                ws()
                if (peek() != ':') fail()
                i++
                m[k] = value()
                ws()
                when (peek()) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return m
                    }
                    else -> fail()
                }
            }
        }

        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            ws()
            if (peek() == ']') {
                i++
                return l
            }
            while (true) {
                l += value()
                ws()
                when (peek()) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return l
                    }
                    else -> fail()
                }
            }
        }

        fun str(): String {
            i++
            val b = StringBuilder()
            while (true) {
                val c = peek()
                i++
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> {
                        val e = peek()
                        i++
                        when (e) {
                            '"', '\\', '/' -> b.append(e)
                            'b' -> b.append('\b')
                            'f' -> b.append('\u000C')
                            'n' -> b.append('\n')
                            'r' -> b.append('\r')
                            't' -> b.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail()
                                var u = 0
                                for (k in 0 until 4) u = u * 16 + hex(s[i + k])
                                b.append(u.toChar())
                                i += 4
                            }
                            else -> fail()
                        }
                    }
                    else -> b.append(c)
                }
            }
        }

        fun hex(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> fail()
        }

        fun num(): Any {
            val start = i
            if (peek() == '-') i++
            var frac = false
            while (i < s.length && (s[i] in '0'..'9' || s[i] in ".eE+-")) {
                if (s[i] in ".eE") frac = true
                i++
            }
            val t = s.substring(start, i)
            return (if (frac) t.toDoubleOrNull() else t.toLongOrNull() ?: t.toDoubleOrNull()) ?: fail()
        }
    }
}
