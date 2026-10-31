package net.seilent.funecomp.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PkcePolicyTest {
    private val prefix = PkcePolicy.prefix("https://svcdemo.test/a/auth")!!

    private fun seen(url: String) = PkcePolicy.seen(url, prefix)

    @Test
    fun theRedirectMatchesByParsedPartsAsTheDevice() {
        for (u in listOf("https://svcdemo.test/a/auth?code=1&state=s", "https://svcdemo.test/a/auth", "https://svcdemo.test/a/auth/x", "https://svcdemo.test/a/x/../auth/y", "https://SVCDEMO.test/a/auth?code=1", "https://svcdemo.test:443/a/auth#f")) {
            assertTrue(u, seen(u) is PkcePolicy.Seen.Caught)
            assertEquals(u, (seen(u) as PkcePolicy.Seen.Caught).url)
        }
        for (u in listOf("https://svcdemo.test/a/authx", "https://svcdemo.test/a", "https://svcdemo.test/a/aut", "https://evil.test/a/auth", "https://svcdemo.test:444/a/auth", "https://svcdemo.test.evil.test/a/auth", "https://login.svcdemo.test/a/auth")) {
            assertEquals(u, PkcePolicy.Seen.Pass, seen(u))
        }
    }

    @Test
    fun everyOtherSchemeIsBlocked() {
        for (u in listOf("HTTPS://svcdemo.test/a/auth?code=1", "Https://svcdemo.test/a/auth?code=1", "http://svcdemo.test/a/auth?code=1", "intent://x#Intent;scheme=https;end", "market://details?id=x", "tel:123", "javascript:alert(1)", "about:blank", "data:text/html,x", "file:///sdcard/x")) {
            assertEquals(u, PkcePolicy.Seen.Blocked, seen(u))
        }
    }

    @Test
    fun aMatchPast2048BytesIsRefused() {
        val base = "https://svcdemo.test/a/auth?code="
        assertTrue(seen(base + "x".repeat(PkcePolicy.MAX_BYTES - base.length)) is PkcePolicy.Seen.Caught)
        assertEquals(PkcePolicy.Seen.TooLong, seen(base + "x".repeat(PkcePolicy.MAX_BYTES - base.length + 1)))
        assertEquals(PkcePolicy.Seen.Pass, seen("https://other.test/?" + "x".repeat(4000)))
    }

    @Test
    fun thePrefixFollowsTheManifestRule() {
        assertNotNull(PkcePolicy.prefix("https://tidal.com/android/login/auth"))
        assertNotNull(PkcePolicy.prefix("https://Example.com/a/./b/../auth/"))
        for (p in listOf(null, "", "http://svcdemo.test/auth", "https://svcdemo.test/", "https://svcdemo.test", "https://svcdemo.test/auth?x=1", "https://svcdemo.test:444/auth", "https://u@svcdemo.test/auth", "https://1.2.3.4/auth")) {
            assertNull("$p", PkcePolicy.prefix(p))
        }
    }
}
