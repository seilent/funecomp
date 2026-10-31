package net.seilent.funecomp.state

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class SvcCaseFileTest {
    private val file = File("../app/src/androidTest/resources/svc-cases.tsv")

    private fun hex(s: String) = s.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

    private val links = listOf(
        "https://link.svcdemo.test/ABCDE" to "link.svcdemo.test",
        "https://login.tidal.com:443/authorize?client_id=x&state=s" to "login.tidal.com",
        "https://xn--bcher-kva.example/a" to "xn--bcher-kva.example",
        "https://login.tidal.com/a?b=%7C&c=~" to "login.tidal.com",
        "https://LINK.svcdemo.test/ABCDE" to "link.svcdemo.test",
        "https://login.tidal.com@evil.test/" to "evil.test",
        "https://login.tidal.com:8443/" to "login.tidal.com",
        "https://login.tidal.com/a\\b" to "login.tidal.com",
        "https://login%2Etidal.com/" to "login.tidal.com",
        "https://login.tidal.com/\u0007" to "login.tidal.com",
        "https://login.tidal.com/é" to "login.tidal.com",
        "https://login.tidal.com/a|b" to "login.tidal.com",
        "https://login.tidal.com/a{b}" to "login.tidal.com",
        "https://login.tidal.com/a^b" to "login.tidal.com",
        "https://login.tidal.com/a\"b" to "login.tidal.com",
        "https://login.tidal.com/a<b>" to "login.tidal.com",
        "https://login.tidal.com/a`b" to "login.tidal.com",
        "https://login.tidal.com/a%zzb" to "login.tidal.com",
        "https://login.tidal.com/a#b#c" to "login.tidal.com",
        "https://login.tidal.com/a[b]" to "login.tidal.com",
        "https://1.2.3.4/" to "1.2.3.4",
        "https://login.tidal.com/authorize?response_type=code&redirect_uri=https%3A%2F%2Ftidal.com%2Fandroid%2Flogin%2Fauth&client_id=x&lang=EN&appMode=android&client_unique_key=x&code_challenge=x&code_challenge_method=S256&restrict_signup=true&state=x" to "login.tidal.com",
        "https://link.tidal.com/ABCDE" to "link.tidal.com",
    )

    private val seen = listOf(
        "https://svcdemo.test/a/auth?code=1&state=s",
        "https://svcdemo.test/a/x/../auth/y",
        "https://SVCDEMO.test/a/auth?code=1",
        "https://svcdemo.test/a/authx",
        "https://svcdemo.test:444/a/auth",
        "HTTPS://svcdemo.test/a/auth?code=1",
        "http://svcdemo.test/a/auth?code=1",
        "intent://x#Intent;scheme=https;end",
    )

    private fun expected(): String = buildString {
        for ((u, h) in links) append("link\t").append(hex(u)).append('\t').append(hex(h)).append('\t').append(HostRule.link(u, h)).append('\n')
        val p = PkcePolicy.prefix("https://svcdemo.test/a/auth")!!
        for (u in seen) append("seen\t").append(hex(u)).append('\t').append(PkcePolicy.seen(u, p).let { if (it is PkcePolicy.Seen.Caught) "Caught" else it.toString() }).append('\n')
    }

    @Test
    fun theDeviceCaseFileMatchesTheJvm() {
        val want = expected()
        if (System.getenv("SVC_CASES_WRITE") == "1") file.writeText(want)
        assertEquals("rerun with SVC_CASES_WRITE=1 to rewrite ${file.path}", want, file.readText())
    }
}
