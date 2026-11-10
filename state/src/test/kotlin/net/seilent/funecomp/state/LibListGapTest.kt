package net.seilent.funecomp.state

import net.seilent.funecomp.proto.LibRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibListGapTest {
    private fun list(total: Long, have: LongRange) = LibList(total, have.associateWith { LibRow("row $it", null, it, "$it") })

    @Test
    fun firstMissingOnAFullATailCutAndAnEmptyList() {
        assertNull(list(300, 0L..299L).firstMissing(0, 300))
        assertEquals(42L, list(300, 0L..41L).firstMissing(0, 100))
        assertEquals(42L, list(300, 0L..41L).firstMissing(40, 46))
        assertNull(list(300, 0L..41L).firstMissing(0, 42))
        assertEquals(0L, list(300, LongRange.EMPTY).firstMissing(0, 100))
        assertNull(list(0, LongRange.EMPTY).firstMissing(0, 0))
    }

    @Test
    fun reAskGivesTheFirstMissingRowOnlyAfterProgress() {
        assertEquals(42L, list(300, 0L..41L).reAsk(0, 100))
        assertNull(list(300, 0L..99L).reAsk(0, 100))
        assertNull(list(300, 100L..199L).reAsk(0, 100))
        assertNull(list(300, LongRange.EMPTY).reAsk(0, 100))
        assertEquals(60L, list(300, 0L..59L).reAsk(42, 58))
    }

    @Test
    fun reAskEndsAtTheTotal() {
        assertNull(list(150, 0L..149L).reAsk(100, 100))
        assertEquals(120L, list(150, 0L..119L).reAsk(100, 100))
    }
}
