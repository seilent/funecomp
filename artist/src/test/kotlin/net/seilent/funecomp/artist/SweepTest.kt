package net.seilent.funecomp.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SweepTest {
    @Test
    fun orderIsBySongCountDescending() {
        val rows = listOf(Sweep.Row("a", null, 3), Sweep.Row("", null, 99), Sweep.Row("b", "x", 10), Sweep.Row("c", null, 3))
        assertEquals(listOf("b", "a", "c"), Sweep.order(rows).map { it.name })
    }

    @Test
    fun budgetStopsBeforeNinetyPercentAndFlagsPastCap() {
        val b = Sweep.Budget(800, 1000)
        assertTrue(b.room)
        assertFalse(b.add(99))
        assertTrue(b.room)
        assertFalse(b.add(1))
        assertFalse(b.room)
        assertTrue(b.add(101))
    }

    @Test
    fun breakerTripsAfterTenHttpErrorsInARowForAnHourOrANetwork() {
        var now = 0L
        val br = Sweep.Breaker({ now })
        repeat(9) { br.result(true) }
        br.result(false)
        repeat(9) { br.result(true) }
        assertFalse(br.open)
        br.result(true)
        assertTrue(br.open)
        now += Sweep.PAUSE_MS - 1
        assertTrue(br.open)
        now += 1
        assertFalse(br.open)
        repeat(10) { br.result(true) }
        assertTrue(br.open)
        br.network()
        assertFalse(br.open)
    }
}
