package net.seilent.funecomp.state

import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.gen.Err
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ClockSyncTest {
    private fun hello(minor: Int, features: Long) = HelloReply(1, minor, "v1", "x", 240, 320, 240, 320, 16384, 30_000, 5_000, features, 1, 1)

    @Test
    fun onlyWithTheFeatureBitNeverByMinor() {
        val c = ClockSync()
        assertTrue(c.hello(hello(2, Groups.all)))
        assertFalse(c.hello(hello(1, Groups.mask("settings", "eq", "library", "queue", "image"))))
        assertFalse(c.hello(hello(2, Groups.all and Groups.mask("clockAuto").inv())))
        assertTrue(c.hello(hello(1, Groups.mask("clockAuto"))))
    }

    @Test
    fun busyAndRefusedStopTheSession() {
        for (code in listOf(Err.busy, Err.refused)) {
            val c = ClockSync()
            c.hello(hello(2, Groups.all))
            c.failed(code)
            assertFalse(c.armed)
            assertTrue(c.hello(hello(2, Groups.all)))
        }
        val c = ClockSync()
        c.hello(hello(2, Groups.all))
        c.failed(Err.badValue)
        assertTrue(c.armed)
        c.closed()
        assertFalse(c.armed)
    }

    @Test
    fun draftAndDstTransition() {
        val berlin = ZoneId.of("Europe/Berlin")
        val before = Instant.parse("2026-10-24T12:00:00Z").toEpochMilli()
        assertEquals(before / 1000 to 7200, ClockSync.draft(before, berlin))
        val next = ClockSync.next(before, berlin)!!
        assertEquals(Instant.parse("2026-10-25T01:00:00Z").toEpochMilli(), next)
        assertEquals(3600, ClockSync.draft(next, berlin).second)
        assertNull(ClockSync.next(before, ZoneId.of("UTC")))
        assertEquals(-1L to 0, ClockSync.draft(-1, ZoneId.of("UTC")))
    }
}
