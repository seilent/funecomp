package net.seilent.funecomp.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkipQueueTest {
    @Test
    fun oneSkipFliesAndTheTapsAfterItGoAsOneSum() {
        val q = SkipQueue()
        assertEquals(30_000, q.tap(1, 30_000))
        assertNull(q.tap(1, 30_000))
        assertNull(q.tap(1, 30_000))
        assertEquals(60_000, q.replied(1))
        assertNull(q.tap(1, -30_000))
        assertEquals(-30_000, q.replied(1))
        assertNull(q.replied(1))
        assertEquals(30_000, q.tap(1, 30_000))
    }

    @Test
    fun aTimeoutReleasesThePendingSumTheSameWay() {
        val q = SkipQueue()
        q.tap(1, -30_000)
        q.tap(1, -30_000)
        assertEquals(-30_000, q.replied(1))
        assertNull(q.replied(1))
    }

    @Test
    fun thePendingSumClampsAtTheDeviceRange() {
        val q = SkipQueue()
        q.tap(1, 30_000)
        repeat(119) { q.tap(1, 30_000) }
        assertNull(q.tap(1, 30_000))
        assertEquals(3_600_000, q.replied(1))
        val back = SkipQueue()
        back.tap(1, -30_000)
        repeat(121) { back.tap(1, -30_000) }
        assertEquals(-3_600_000, back.replied(1))
        val edge = SkipQueue()
        edge.tap(1, 30_000)
        repeat(119) { edge.tap(1, 30_000) }
        edge.tap(1, 30_000)
        edge.tap(1, 30_000)
        assertEquals(3_600_000, edge.replied(1))
    }

    @Test
    fun aSumOfZeroSendsNothing() {
        val q = SkipQueue()
        q.tap(1, 30_000)
        q.tap(1, 30_000)
        q.tap(1, -30_000)
        assertNull(q.replied(1))
        assertEquals(30_000, q.tap(1, 30_000))
    }

    @Test
    fun closedDropsTheFlyingAndThePending() {
        val q = SkipQueue()
        q.tap(1, 30_000)
        q.tap(1, 30_000)
        q.closed()
        assertNull(q.replied(1))
        assertEquals(-30_000, q.tap(1, -30_000))
    }

    @Test
    fun anOlderSessionsReplySendsNothingAndANewerSessionsTapSendsAtOnce() {
        val q = SkipQueue()
        q.tap(1, 30_000)
        q.tap(1, 30_000)
        assertEquals(30_000, q.tap(2, 30_000))
        assertNull(q.replied(1))
        assertNull(q.tap(2, 30_000))
        assertEquals(30_000, q.replied(2))
    }
}
