package net.seilent.funecomp

import net.seilent.funecomp.state.Lrc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcUnicodeTest {
    private fun rows() = javaClass.getResourceAsStream("/lrc-cases.tsv")!!.use { String(it.readBytes(), Charsets.UTF_8) }
        .lineSequence().filter { it.isNotBlank() }.map { it.split('\t') }.toList()

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun bytes(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun u(s: String) = s.toByteArray(Charsets.UTF_8)

    @Test
    fun theParserMatchesTheJvmOnTheDevice() {
        val parse = rows().filter { it[0] == "parse" }
        assertTrue(parse.size > 10)
        for (r in parse) {
            val p = Lrc.parse(bytes(r[1]))
            assertEquals(r[1], r[2], p.have.name)
            assertEquals(r[1], r.getOrElse(3) { "" }, p.lines.joinToString(";") { "${it.ms}:${hex(u(it.text))}" })
            assertEquals(r[1], r.getOrElse(4) { "" }, p.plain.joinToString(";") { hex(u(it)) })
        }
    }

    @Test
    fun theCutMatchesTheJvmOnTheDevice() {
        val cut = rows().filter { it[0] == "cut" }
        assertEquals(4, cut.size)
        for (r in cut) assertEquals(r[1], r[2], hex(u(Lrc.cut(String(bytes(r[1]), Charsets.UTF_8)))))
    }
}
