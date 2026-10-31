package net.seilent.funecomp

import net.seilent.funecomp.artist.NeMix
import net.seilent.funecomp.artist.gen.JaHan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeMixDeviceTest {
    private fun rows() = javaClass.getResourceAsStream("/nemix-cases.tsv")!!.use { String(it.readBytes(), Charsets.UTF_8) }
        .lineSequence().filter { it.isNotBlank() }.map { it.split('\t') }.toList()

    private fun unesc(s: String) = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                append(
                    when (s[i + 1]) {
                        't' -> '\t'
                        'n' -> '\n'
                        'r' -> '\r'
                        else -> s[i + 1]
                    },
                )
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }

    @Test
    fun theSharedCasesHoldOnTheDevice() {
        val rows = rows()
        assertTrue(rows.size >= 50)
        for (r in rows) {
            val v = NeMix.check(unesc(r[1]))
            assertEquals(r[0], r[2], if (v is NeMix.Verdict.Reject) v.reason else "ok")
        }
    }

    @Test
    fun theKanjiTableLoadsOnTheDevice() {
        assertEquals(JaHan.SIZE, (0..0x3FFFF).count(NeMix::japanese))
        for (s in listOf("視", "剝", "𠮟", "﨑", "你")) assertTrue(s, NeMix.japanese(s.codePointAt(0)))
        for (s in listOf("视", "爱", "她", "𠀀")) assertFalse(s, NeMix.japanese(s.codePointAt(0)))
    }
}
