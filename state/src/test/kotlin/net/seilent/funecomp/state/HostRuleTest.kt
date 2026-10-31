package net.seilent.funecomp.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostRuleTest {
    @Test
    fun hostsFollowTheDeviceRule() {
        for (h in listOf("login.tidal.com", "link.svcdemo.test", "a.b", "xn--bcher-kva.example", "a-b.c-d.e", "1.2.3.4.5", "256.1.1.1")) assertTrue(h, HostRule.host(h))
        for (h in listOf(null, "", "Login.tidal.com", "localhost", "a..b", ".a.b", "a.b.", "-a.b", "a.b-", "1.2.3.4", "10.0.0.1", "a_b.c", "a b.c", "a%2eb.c", "café.fr", "a.b:443")) assertFalse("$h", HostRule.host(h))
    }

    @Test
    fun httpsNeedsTheLowerCasePrefixPort443AndAGoodHost() {
        assertEquals("login.tidal.com", HostRule.https("https://login.tidal.com/authorize?x=1")?.host)
        assertEquals("/", HostRule.https("https://login.tidal.com")?.target)
        assertEquals("login.tidal.com", HostRule.https("https://LOGIN.tidal.com:443/a")?.host)
        for (u in listOf(null, "http://login.tidal.com/", "HTTPS://login.tidal.com/", "https://login.tidal.com:8443/", "https://login.tidal.com@x.test/",
            "https://u@login.tidal.com/", "https://[::1]/", "https://1.2.3.4/", "https://login.tidal.com/a b", "https://login.tidal.com/\u0001", "https://login.tidal.com/é",
            "https://login%2etidal.com/", "https://login.tidal.com:/", "https://login.tidal.com:99999/", "login.tidal.com/x", "https:///x")) assertNull("$u", HostRule.https(u))
    }

    @Test
    fun aLoginLinkPassesTheDeviceRuleAndAStrictParseOnItsOwnHost() {
        assertTrue(HostRule.link("https://link.svcdemo.test/ABCDE", "link.svcdemo.test"))
        assertTrue(HostRule.link("https://login.tidal.com:443/authorize?client_id=x&state=s", "login.tidal.com"))
        assertTrue(HostRule.link("https://xn--bcher-kva.example/a", "xn--bcher-kva.example"))
        val refused = listOf(
            "https://link.svcdemo.test/ABCDE" to "Link.svcdemo.test",
            "https://LINK.svcdemo.test/ABCDE" to "link.svcdemo.test",
            "https://other.svcdemo.test/ABCDE" to "link.svcdemo.test",
            "https://link.svcdemo.test.evil.test/ABCDE" to "link.svcdemo.test",
            "https://login.tidal.com@evil.test/" to "evil.test",
            "https://login.tidal.com@evil.test/" to "login.tidal.com",
            "https://login.tidal.com:8443/" to "login.tidal.com",
            "https://login.tidal.com/a\\b" to "login.tidal.com",
            "https://login.tidal.com\\@evil.test/" to "login.tidal.com",
            "https://login%2Etidal.com/" to "login.tidal.com",
            "https://login.tidal.com/\u0007" to "login.tidal.com",
            "https://login.tidal.com/é" to "login.tidal.com",
            "https://login.tidal.com/a|b" to "login.tidal.com",
            "https://login.tidal.com/a b" to "login.tidal.com",
            "https://1.2.3.4/" to "1.2.3.4",
            "https://tidal.com-/" to "tidal.com-",
            "http://login.tidal.com/" to "login.tidal.com",
            "link.svcdemo.test/ABCDE" to "link.svcdemo.test",
            "https://login.tidal.com/" to "",
        )
        for ((u, h) in refused) assertFalse("$u $h", HostRule.link(u, h))
        assertFalse(HostRule.link(null, "login.tidal.com"))
        assertFalse(HostRule.link("https://login.tidal.com/", null))
    }

    @Test
    fun pathsAreNormalizedAndSplitAsTheDevice() {
        assertEquals("/a/auth/", HostRule.parse("https://Example.com/a/./b/../auth/")!!.target)
        assertEquals("/?q=1", HostRule.parse("https://example.com?q=1")!!.target)
        assertEquals("/", HostRule.parse("https://example.com#x")!!.target)
        assertEquals(listOf("a", "auth"), HostRule.segments("/a/auth/?x=1"))
        assertEquals(emptyList<String>(), HostRule.segments("/"))
        assertNotNull(HostRule.parse("http://example.com:8080/x"))
        assertEquals(8080, HostRule.parse("http://example.com:8080/x")!!.port)
    }
}
