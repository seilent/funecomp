package net.seilent.funecomp

import net.seilent.funecomp.artist.NetEase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class NetEaseNamesTest {
    private fun rows() = javaClass.getResourceAsStream("/ne-core.tsv")!!.use { String(it.readBytes(), Charsets.UTF_8) }
        .lineSequence().filter { it.isNotBlank() }.map { it.split('\t').map(::unesc) }.toList()

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

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    @Test
    fun theCoreFoldMatchesTheJvmOnTheDevice() {
        val core = rows().filter { it[0] == "core" }
        assertTrue(core.size > 2000)
        for (r in core) assertEquals(r[1], r[2], NetEase.core(r[1]))
    }

    @Test
    fun theLyricTextRulesMatchTheJvmOnTheDevice() {
        val text = rows().filter { it[0] == "text" }
        assertEquals(40, text.size)
        for (r in text) {
            val got = NetEase.text(r[2])
            assertEquals(r[1], r[3], if (got == null) "miss" else if (got.synced) "synced" else "plain")
            assertEquals(r[1], r[4], got?.let { sha(it.text) } ?: "")
        }
    }
}
