package net.seilent.funecomp

import net.seilent.funecomp.state.HostRule
import net.seilent.funecomp.state.PkcePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SvcHostsTest {
    private fun rows() = javaClass.getResourceAsStream("/svc-cases.tsv")!!.use { String(it.readBytes(), Charsets.UTF_8) }
        .lineSequence().filter { it.isNotBlank() }.map { it.split('\t') }.toList()

    private fun text(s: String) = String(ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }, Charsets.UTF_8)

    @Test
    fun theLinkRuleMatchesTheJvmOnTheDevice() {
        val links = rows().filter { it[0] == "link" }
        assertTrue(links.size > 10)
        for (r in links) assertEquals(text(r[1]), r[3].toBoolean(), HostRule.link(text(r[1]), text(r[2])))
    }

    @Test
    fun theRedirectRuleMatchesTheJvmOnTheDevice() {
        val p = PkcePolicy.prefix("https://svcdemo.test/a/auth")!!
        val seen = rows().filter { it[0] == "seen" }
        assertTrue(seen.size > 5)
        for (r in seen) assertEquals(text(r[1]), r[2], PkcePolicy.seen(text(r[1]), p).let { if (it is PkcePolicy.Seen.Caught) "Caught" else it.toString() })
    }
}
