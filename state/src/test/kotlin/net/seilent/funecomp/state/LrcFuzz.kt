package net.seilent.funecomp.state

object LrcFuzz {
    class Case(val input: ByteArray, val shift: Long?)

    fun cases(): List<Case> {
        var seed = 4242
        fun rnd(): Int {
            seed = seed * 1103515245 + 12345
            return seed ushr 8
        }
        val base = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "[ar:A]\n[offset:-120]\n[00:01.50]one\n[00:03.00][00:10.25]chorus\n[01:02]bare\n[00:07:40]colon é\n[00:08.00]\n".toByteArray(Charsets.ISO_8859_1)
        val alphabet = "[]<>:.+-0123456789 \n\roffsetÃéÿ".toByteArray(Charsets.ISO_8859_1)
        val out = ArrayList<Case>()
        for (n in 0..base.size) out += Case(base.copyOf(n), null)
        repeat(4000) {
            val m = base.toMutableList()
            repeat(1 + rnd() % 10) {
                val p = rnd() % (m.size + 1)
                val op = rnd() % 3
                val ch = if (rnd() % 2 != 0) alphabet[rnd() % alphabet.size] else rnd().toByte()
                if (op == 0 && p < m.size) m[p] = ch else if (op == 1) m.add(p, ch) else if (p < m.size) repeat(minOf(1 + rnd() % 8, m.size - p)) { m.removeAt(p) }
            }
            out += Case(m.toByteArray(), (rnd() % 120001).toLong() - 60000)
        }
        repeat(200) { out += Case(ByteArray(rnd() % 4096) { rnd().toByte() }, null) }
        return out
    }
}
