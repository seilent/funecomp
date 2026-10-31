package net.seilent.funecomp.state

import net.seilent.funecomp.state.SvcOpens.Kind
import net.seilent.funecomp.state.SvcOpens.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SvcOpensTest {
    private val o = SvcOpens()

    private fun link(n: Int) = "https://login.svcdemo.test/auth?state=s$n"

    private fun take(seq: Long, kind: Kind, now: Long = 0, service: String = "tidal", link: String = link(seq.toInt()), listed: Boolean = true, known: Boolean = true, tap: Boolean = false) =
        o.take(service, seq, link, listed, known, kind, now, tap)

    @Test
    fun eachKeyGetsOneActOfEachKind() {
        assertEquals(Verdict.OPEN, take(1, Kind.OPEN))
        assertEquals(Verdict.NONE, take(1, Kind.OPEN))
        assertEquals(Verdict.OPEN, take(2, Kind.NOTICE))
        assertEquals(Verdict.OPEN, take(2, Kind.OPEN))
        assertEquals(Verdict.NONE, take(2, Kind.NOTICE))
        assertEquals(Verdict.OPEN, take(3, Kind.TOAST))
        assertEquals(Verdict.NONE, take(3, Kind.TOAST))
        assertTrue(o.opened("tidal", 2, link(2)))
        assertFalse(o.opened("tidal", 3, link(3)))
    }

    @Test
    fun theLinkIsPartOfTheKey() {
        assertEquals(Verdict.OPEN, take(1, Kind.OPEN, link = "https://login.svcdemo.test/auth?state=a"))
        assertEquals(Verdict.OPEN, take(1, Kind.OPEN, link = "https://login.svcdemo.test/auth?state=b"))
        assertEquals(Verdict.NONE, take(1, Kind.OPEN, link = "https://login.svcdemo.test/auth?state=a"))
    }

    @Test
    fun theFourthAskIn600sIsAskedAndAKeyCountsOnce() {
        for (seq in 1L..3L) {
            assertEquals(Verdict.OPEN, take(seq, Kind.NOTICE, now = seq * 1000))
            assertEquals(Verdict.OPEN, take(seq, Kind.OPEN, now = seq * 1000 + 500))
        }
        assertEquals(Verdict.ASK, take(4, Kind.OPEN, now = 10_000))
        assertEquals(Verdict.ASK, take(4, Kind.NOTICE, now = 10_000))
        assertEquals(Verdict.ASK, take(4, Kind.TOAST, now = 10_000))
        assertEquals(Verdict.NONE, take(4, Kind.NOTICE, now = 10_000))
        assertEquals(Verdict.ASK, take(4, Kind.OPEN, now = 600_000))
        assertEquals(Verdict.OPEN, take(4, Kind.OPEN, now = 601_001))
    }

    @Test
    fun theCapCountsEveryService() {
        assertEquals(Verdict.OPEN, take(1, Kind.OPEN))
        assertEquals(Verdict.OPEN, take(2, Kind.OPEN))
        assertEquals(Verdict.OPEN, take(1, Kind.OPEN, service = "svcdemo"))
        assertEquals(Verdict.ASK, take(2, Kind.OPEN, service = "svcdemo"))
        assertEquals(Verdict.ASK, take(3, Kind.OPEN, service = "other"))
    }

    @Test
    fun keysPastTheCapNeverPushTheCountedOnesOut() {
        for (seq in 1L..3L) assertEquals(Verdict.OPEN, take(seq, Kind.OPEN))
        for (seq in 4L..17L) assertEquals(Verdict.ASK, take(seq, Kind.TOAST))
        assertEquals(Verdict.ASK, take(18, Kind.OPEN))
        assertEquals(Verdict.OPEN, take(18, Kind.OPEN, now = 600_000))
    }

    @Test
    fun anUnlistedServiceOrAnUnknownHostIsAskedAndSpendsNothing() {
        assertEquals(Verdict.OPEN, take(1, Kind.OPEN))
        assertEquals(Verdict.OPEN, take(2, Kind.OPEN))
        assertEquals(Verdict.ASK, take(3, Kind.OPEN, listed = false))
        assertEquals(Verdict.ASK, take(4, Kind.OPEN, known = false))
        assertEquals(Verdict.ASK, take(4, Kind.OPEN, known = false, tap = true))
        assertFalse(o.opened("tidal", 4, link(4)))
        assertEquals(Verdict.OPEN, take(5, Kind.OPEN))
        assertEquals(Verdict.ASK, take(6, Kind.OPEN))
    }

    @Test
    fun aNoticeIgnoresTheHostAndCounts() {
        assertEquals(Verdict.OPEN, take(1, Kind.NOTICE, known = false, listed = false))
        assertEquals(Verdict.OPEN, take(2, Kind.OPEN))
        assertEquals(Verdict.OPEN, take(3, Kind.OPEN))
        assertEquals(Verdict.ASK, take(4, Kind.OPEN))
    }

    @Test
    fun aTapOpensPastTheCapAndTheKeyAndCountsNothing() {
        for (seq in 1L..3L) assertEquals(Verdict.OPEN, take(seq, Kind.OPEN))
        assertEquals(Verdict.OPEN, take(1, Kind.OPEN, tap = true))
        assertEquals(Verdict.OPEN, take(4, Kind.OPEN, now = 600_001, tap = true))
        assertTrue(o.opened("tidal", 4, link(4)))
        assertEquals(Verdict.NONE, take(4, Kind.OPEN, now = 600_001))
        assertEquals(Verdict.OPEN, take(5, Kind.OPEN, now = 600_001))
        assertEquals(Verdict.OPEN, take(6, Kind.OPEN, now = 600_001))
        assertEquals(Verdict.OPEN, take(7, Kind.OPEN, now = 600_001))
        assertEquals(Verdict.ASK, take(8, Kind.OPEN, now = 600_001))
    }

    @Test
    fun seventeenKeysForgetTheOldest() {
        for (seq in 1L..17L) assertEquals(Verdict.OPEN, take(seq, Kind.NOTICE, now = seq * 600_001))
        assertEquals(Verdict.NONE, take(2, Kind.NOTICE, now = 18 * 600_001))
        assertEquals(Verdict.OPEN, take(1, Kind.NOTICE, now = 18 * 600_001))
    }

    @Test
    fun knownHostsKeepTheLast32InOrderThroughTheStore() {
        var stored = ""
        val s = SvcOpens { stored = it }
        for (i in 0 until 33) s.remember("tidal", "h$i.svcdemo.test")
        s.remember("tidal", "h5.svcdemo.test")
        assertFalse(s.known("tidal", "h0.svcdemo.test"))
        assertTrue(s.known("tidal", "h1.svcdemo.test"))
        assertTrue(s.known("tidal", "h32.svcdemo.test"))
        assertFalse(s.known("svcdemo", "h1.svcdemo.test"))
        assertEquals((1 until 33).map { "tidal h$it.svcdemo.test" }, stored.lines())
        val back = SvcOpens(stored)
        assertEquals(stored, back.hosts)
        assertTrue(back.known("tidal", "h1.svcdemo.test"))
        back.remember("tidal", "h33.svcdemo.test")
        assertFalse(back.known("tidal", "h1.svcdemo.test"))
        assertTrue(back.known("tidal", "h2.svcdemo.test"))
    }

    @Test
    fun aLineBreakNeverEntersTheStore() {
        var stored = "x"
        val s = SvcOpens { stored = it }
        s.remember("tidal\nsvcdemo", "login.svcdemo.test")
        s.remember("tidal", "a\rb")
        assertEquals("x", stored)
        assertFalse(s.known("tidal\nsvcdemo", "login.svcdemo.test"))
    }
}
