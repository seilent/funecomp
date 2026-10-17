package net.seilent.funecomp.state

import net.seilent.funecomp.state.BatteryAlert.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryAlertTest {
    @Test
    fun oneAlertPerDownCrossing() {
        val a = BatteryAlert()
        assertNull(a.update(40, false))
        assertNull(a.update(16, false))
        assertEquals(Event.Low(15, 15), a.update(15, false))
        assertNull(a.update(14, false))
        assertNull(a.update(15, false))
        assertNull(a.update(14, false))
        assertEquals(Event.Low(10, 10), a.update(10, false))
        assertEquals(Event.Low(4, 5), a.update(4, false))
        assertNull(a.update(3, false))
    }

    @Test
    fun chargingClearsAndAboveSixteenRearms() {
        val a = BatteryAlert()
        a.update(14, false)
        assertEquals(Event.Clear, a.update(14, true))
        assertNull(a.update(15, true))
        assertNull(a.update(14, false))
        assertEquals(Event.Low(9, 10), a.update(9, false))
        a.update(17, true)
        assertEquals(Event.Low(15, 15), a.update(15, false))
    }

    @Test
    fun firstReadingBelowALevelAlertsOnceAtTheLowestDue() {
        val a = BatteryAlert()
        assertEquals(Event.Low(8, 10), a.update(8, false))
        assertNull(a.update(7, null))
        assertNull(a.update(null, false))
    }
}
