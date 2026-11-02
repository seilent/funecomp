package net.seilent.funecomp

import net.seilent.funecomp.ui.Detected
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardsTest {
    private val r1 = "00:00:00:00:00:04"
    private val v1 = "00:00:00:00:00:05"

    private fun find(address: String = r1, name: String? = "fune r1", audioVideo: Boolean = true, companion: Boolean = false, bonded: Boolean = false, linked: Boolean = false) =
        Find(address, name, audioVideo, companion, bonded, linked)

    @Test
    fun aFuneNameIsQueriedAndTheCompanionUuidOffersIt() {
        val b = Boards()
        assertTrue(b.found(find(), 0))
        assertNull(b.waiting)
        assertEquals(listOf(r1), b.pending(1_000))
        b.sdp(r1, true, 2_000)
        assertEquals(Detected("fune r1", r1), b.waiting)
    }

    @Test
    fun theUuidInTheFindOffersWithNoQuery() {
        val b = Boards()
        assertFalse(b.found(find(companion = true), 0))
        assertEquals(Detected("fune r1", r1), b.waiting)
        assertEquals(emptyList<String>(), b.pending(0))
    }

    @Test
    fun aNameMatchAloneIsNotABoard() {
        val b = Boards()
        b.found(find(), 0)
        b.sdp(r1, false, 1_000)
        assertNull(b.waiting)
        assertFalse(b.found(find(), 30_000))
        assertEquals(emptyList<String>(), b.pending(30_000))
    }

    @Test
    fun aBondedOrLinkedBoardIsNeverOffered() {
        val b = Boards()
        assertFalse(b.found(find(companion = true, bonded = true), 0))
        assertFalse(b.found(find(v1, "fune v1", companion = true, linked = true), 0))
        assertNull(b.waiting)
    }

    @Test
    fun otherNamesAreNeverQueried() {
        val b = Boards()
        assertFalse(b.found(find("00:00:00:00:00:06", "KJ-49X9000F"), 0))
        assertFalse(b.found(find("00:00:00:00:00:07", "4E6EKH", audioVideo = false), 0))
        assertFalse(b.found(find("00:00:00:00:00:08", null, audioVideo = false), 0))
        assertEquals(emptyList<String>(), b.pending(0))
    }

    @Test
    fun anUnnamedAudioDeviceIsQueriedUntilItsNameShowsOtherwise() {
        val b = Boards()
        assertTrue(b.found(find("00:00:00:00:00:09", null), 0))
        assertEquals(listOf("00:00:00:00:00:09"), b.pending(0))
        assertFalse(b.found(find("00:00:00:00:00:09", "Smart TV"), 8_000))
        assertEquals(emptyList<String>(), b.pending(8_000))
    }

    @Test
    fun aSecondFindInTheWindowNeedsNoQuery() {
        val b = Boards()
        b.found(find(), 0)
        b.sdp(r1, true, 2_000)
        b.away()
        assertFalse(b.found(find(), 30_000))
        assertEquals(r1, b.waiting?.address)
        assertEquals(emptyList<String>(), b.pending(30_000))
    }

    @Test
    fun cancelHoldsUntilTheWindowEnds() {
        val b = Boards()
        b.found(find(companion = true), 0)
        b.cancel(r1)
        assertNull(b.waiting)
        b.found(find(companion = true), 60_000)
        assertNull(b.waiting)
        b.tick(150_000)
        assertNull(b.waiting)
        b.found(find(companion = true), 181_000)
        assertEquals(r1, b.waiting?.address)
    }

    @Test
    fun aRoundWithoutTheBoardKeepsTheWindow() {
        val b = Boards()
        b.found(find(companion = true), 0)
        b.cancel(r1)
        b.tick(100_000)
        b.found(find(companion = true), 110_000)
        assertNull(b.waiting)
    }

    @Test
    fun theWindowEndsAfter120sWithNoFindAndThePromptCloses() {
        val b = Boards()
        b.found(find(companion = true), 0)
        assertEquals(r1, b.waiting?.address)
        b.tick(120_000)
        assertEquals(r1, b.waiting?.address)
        b.tick(120_001)
        assertNull(b.waiting)
    }

    @Test
    fun oneBoardAtATime() {
        val b = Boards()
        b.found(find(companion = true), 0)
        b.found(find(v1, "fune v1", companion = true), 1_000)
        assertEquals(r1, b.waiting?.address)
        b.cancel(r1)
        assertEquals(v1, b.waiting?.address)
        b.taken(v1)
        assertNull(b.waiting)
    }

    @Test
    fun leavingTheFrontDropsTheWaitingBoardAndKeepsTheCancel() {
        val b = Boards()
        b.found(find(companion = true), 0)
        b.found(find(v1, "fune v1", companion = true), 0)
        b.cancel(v1)
        b.away()
        assertNull(b.waiting)
        b.found(find(v1, "fune v1", companion = true), 30_000)
        assertNull(b.waiting)
        b.found(find(companion = true), 30_000)
        assertEquals(r1, b.waiting?.address)
    }

    @Test
    fun aFlightEndsAfterItsLimit() {
        val b = Boards()
        b.pairing(r1, "fune r1", 0)
        b.tick(Boards.FLIGHT_MS)
        assertEquals(r1, b.flight?.address)
        b.tick(Boards.FLIGHT_MS + 1)
        assertNull(b.flight)
    }
}
