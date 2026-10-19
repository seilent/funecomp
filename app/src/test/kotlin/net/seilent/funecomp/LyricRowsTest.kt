package net.seilent.funecomp

import net.seilent.funecomp.ui.LyricRows
import net.seilent.funecomp.ui.LyricRows.Kind
import net.seilent.funecomp.ui.LyricRows.Row
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricRowsTest {
    private val wrap: (String, Boolean) -> List<String> = { t, big -> if (t.isEmpty()) emptyList() else t.chunked(if (big) 4 else 8) }

    private fun rows(texts: List<String>?, index: Int, room: Float = 100f, title: String = "Title", next: String? = "Next") =
        LyricRows.synced(texts, index, title, next, wrap, room, 20f, 10f)

    @Test
    fun previousCurrentThenUpcomingWhileAWholeRowFits() {
        val texts = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
        assertEquals(
            listOf(Row(Kind.PREVIOUS, "one", 0f), Row(Kind.CURRENT, "two", 10f)) + (2..8).map { Row(Kind.UPCOMING, texts[it], 30f + 10f * (it - 2)) },
            rows(texts, 1),
        )
    }

    @Test
    fun thePreviousLineGivesWayFirstThenUpcoming() {
        val texts = listOf("prev", "abcdefghijklmnop", "up one", "up two")
        assertEquals(
            listOf(Row(Kind.CURRENT, "abcd", 0f), Row(Kind.CURRENT, "efgh", 20f), Row(Kind.CURRENT, "ijkl", 40f), Row(Kind.CURRENT, "mnop", 60f), Row(Kind.UPCOMING, "up one", 80f)),
            rows(texts, 1, room = 95f),
        )
        assertEquals(
            listOf(Row(Kind.PREVIOUS, "prev", 0f), Row(Kind.CURRENT, "abcd", 10f), Row(Kind.CURRENT, "efgh", 30f), Row(Kind.CURRENT, "ijkl", 50f), Row(Kind.CURRENT, "mnop", 70f)),
            rows(texts.take(2), 1),
        )
        assertEquals(listOf(Row(Kind.CURRENT, "abcd", 0f), Row(Kind.CURRENT, "efgh", 20f), Row(Kind.CURRENT, "ijkl", 40f), Row(Kind.CURRENT, "mnop", 60f)), rows(texts.take(2), 1, room = 85f))
    }

    @Test
    fun aCurrentLineThatAloneOverflowsClipsAtItsLastWholeRow() {
        val long = "x".repeat(40)
        assertEquals((0 until 5).map { Row(Kind.CURRENT, "xxxx", 20f * it) }, rows(listOf(long, "after"), 0))
    }

    @Test
    fun emptyStampedLinesAreSkippedUpcomingAndAnEmptyCurrentKeepsItsRow() {
        val texts = listOf("a", "", "b", "", "", "c")
        assertEquals(listOf(Row(Kind.CURRENT, "a", 0f), Row(Kind.UPCOMING, "b", 20f), Row(Kind.UPCOMING, "c", 30f)), rows(texts, 0))
        assertEquals(listOf(Row(Kind.PREVIOUS, "a", 0f), Row(Kind.UPCOMING, "b", 30f), Row(Kind.UPCOMING, "c", 40f)), rows(texts, 1))
        assertEquals(listOf(Row(Kind.PREVIOUS, "b", 0f), Row(Kind.UPCOMING, "c", 30f)), rows(texts, 3))
        assertEquals(listOf(Row(Kind.PREVIOUS, "prev", 0f)) + listOf("abcd", "efgh", "ijkl", "mnop").mapIndexed { i, t -> Row(Kind.CURRENT, t, 10f + 20f * i) }, rows(listOf("prev", "abcdefghijklmnop", "", ""), 1, room = 95f))
    }

    @Test
    fun beforeTheFirstStampTheColumnStartsWithTheUpcomingLines() {
        assertEquals(listOf(Row(Kind.UPCOMING, "a", 0f), Row(Kind.UPCOMING, "b", 10f)), rows(listOf("a", "b"), -1))
    }

    @Test
    fun noneShowsTheTitleAndTheNextTitle() {
        assertEquals(listOf(Row(Kind.CURRENT, "Firs", 0f), Row(Kind.CURRENT, "t Lo", 20f), Row(Kind.CURRENT, "ve", 40f), Row(Kind.UPCOMING, "Automati", 60f), Row(Kind.UPCOMING, "c", 70f)), rows(null, -1, title = "First Love", next = "Automatic"))
        assertEquals(listOf(Row(Kind.CURRENT, "Firs", 0f), Row(Kind.CURRENT, "t Lo", 20f), Row(Kind.CURRENT, "ve", 40f)), rows(null, -1, title = "First Love", next = null))
    }

    @Test
    fun plainKeepsBlankLinesAsOneRowAndTheRoomSnapsToWholeRows() {
        assertEquals(listOf("abcdefgh", "ij", "", "k"), LyricRows.plain(listOf("abcdefghij", "", "k")) { wrap(it, false) })
        assertEquals(168f, LyricRows.plainRoom(170f, 24f))
        assertEquals(298f - 298f % 24f, LyricRows.plainRoom(298f, 24f))
    }
}
