package net.seilent.funecomp.state

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class LrcDiffTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun u(s: String) = s.toByteArray(Charsets.UTF_8)

    private fun text(h: String): String {
        val b = unhex(h)
        return Lrc.decode(b, 0, b.size)
    }

    private fun corpus(): List<ByteArray> {
        val cases = File("../app/src/androidTest/resources/lrc-cases.tsv").readLines().filter { it.startsWith("parse\t") }.map { unhex(it.split('\t')[1]) }
        return cases + LrcCases.inputs.map { u(it) } + LrcFuzz.cases().map { it.input }
    }

    @Test
    fun theDeviceParseMatchesTheJvmOnTheCorpus() {
        val dir = System.getenv("LRC_DIFF_DIR")
        assumeTrue(dir != null)
        val inputs = corpus()
        val cpp = File(dir, "cpp.tsv")
        if (!cpp.exists()) {
            File(dir).mkdirs()
            File(dir, "corpus.hex").writeText(inputs.joinToString("") { hex(it) + "\n" })
            return
        }
        val rows = cpp.readLines()
        assertEquals(inputs.size, rows.size)
        for ((i, row) in rows.withIndex()) {
            val c = row.split('\t')
            val p = Lrc.parse(inputs[i])
            val at = "row $i"
            assertEquals(at, "parse", c[0])
            assertEquals(at, hex(inputs[i]), c[1])
            assertEquals(at, p.have.name, c[2])
            val lines = if (c[3].isEmpty()) emptyList() else c[3].split(';')
            assertEquals(at, p.lines.size, lines.size)
            for ((k, line) in lines.withIndex()) {
                val colon = line.indexOf(':')
                assertEquals(at, p.lines[k].ms, line.substring(0, colon).toLong())
                assertEquals(at, p.lines[k].text, text(line.substring(colon + 1)))
            }
            val plain = if (c[4].isEmpty()) emptyList() else c[4].split(';')
            assertEquals(at, p.plain, plain.map { text(it) })
        }
    }
}
