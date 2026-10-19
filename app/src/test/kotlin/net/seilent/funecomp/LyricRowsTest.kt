package net.seilent.funecomp

import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.ExpOut6
import net.seilent.funecomp.ui.LyricRows
import net.seilent.funecomp.ui.LyricRows.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.roundToInt

class LyricRowsTest {
    data class Row(val kind: Kind, val text: String, val slot: Float)

    private val wrap: (String, Boolean) -> List<String> = { t, big -> if (t.isEmpty()) emptyList() else t.chunked(if (big) 4 else 8) }

    private fun blocks(texts: List<String>?, index: Int, room: Float = 100f, title: String = "Title", next: String? = "Next", w: (String, Boolean) -> List<String> = wrap, cp: Float = 20f, np: Float = 10f) =
        LyricRows.blocks(texts, index, title, next, w, room, cp, np)

    private fun rows(texts: List<String>?, index: Int, room: Float = 100f, title: String = "Title", next: String? = "Next"): List<Row> {
        val out = ArrayList<Row>()
        for (d in LyricRows.column(null, blocks(texts, index, room, title, next), 1f) { it }) {
            for ((k, _) in d.forms) {
                LyricRows.lines(k, texts, title, next, wrap).forEachIndexed { i, t ->
                    val slot = d.top + i * k.pitch
                    if (slot + k.pitch <= d.y1 + 0.001f) out += Row(LyricRows.kind(k, index), t, slot)
                }
            }
        }
        return out
    }

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

    private val four = "We kept on running through the rain until the city lights went out"
    private val five = "$four and the morning came slow over the quiet field where nothing was left"
    private val device = listOf(
        "Light on the water", "Every road leads back home", four, "Under a paper moon", five, "Say my name again", "Lights out",
        "Across the quiet field", "Nothing left to lose", "", "Hold on until the morning", "Under the last light",
    )
    private val words: (String, Boolean) -> List<String> = { t, big ->
        if (t.isEmpty()) emptyList() else t.split(" ").chunked(if (big) 3 else 5).map { it.joinToString(" ") }
    }
    private val cp = Tok.npLyricPitch
    private val np = Tok.npNextPitch

    private class Shown(val text: String, val big: Boolean, val top: Float, val alpha: Float)

    private fun slide(from: Int, to: Int, room: Float): Pair<LyricRows.Blocks, LyricRows.Blocks> =
        LyricRows.pair(blocks(device, from, room, w = words, cp = cp, np = np), blocks(device, to, room, w = words, cp = cp, np = np), { words(device[it], false).size }, np)

    private fun shown(draws: List<LyricRows.Draw>): Set<Shown> {
        val out = HashSet<Shown>()
        for (d in draws) {
            for ((k, a) in d.forms) {
                if (a <= 0f || d.y1 <= d.y0) continue
                LyricRows.lines(k, device, "", null, words).forEachIndexed { i, t ->
                    val slot = d.top + i * k.pitch
                    if (slot >= d.y0 - 0.001f && slot + k.pitch <= d.y1 + 0.001f) out += Shown(t, k.big, (slot * 64).roundToInt() / 64f, a)
                }
            }
        }
        return out
    }

    @Test
    fun theSlideStartsOnAAndEndsOnB() {
        for (room in listOf(298f, 176f, 170f, 60f)) {
            for (to in 0 until device.size) {
                val (a, b) = slide(to - 1, to, room)
                val staticA = shown(LyricRows.column(null, blocks(device, to - 1, room, w = words, cp = cp, np = np), 1f) { it })
                val staticB = shown(LyricRows.column(null, blocks(device, to, room, w = words, cp = cp, np = np), 1f) { it })
                assertEquals("room $room to $to at 0", staticA.map { listOf(it.text, it.big, it.top, it.alpha) }.toSet(), shown(LyricRows.column(a, b, 0f) { it }).map { listOf(it.text, it.big, it.top, it.alpha) }.toSet())
                assertEquals("room $room to $to at 1", staticB.map { listOf(it.text, it.big, it.top, it.alpha) }.toSet(), shown(LyricRows.column(a, b, 1f) { it }).map { listOf(it.text, it.big, it.top, it.alpha) }.toSet())
            }
        }
    }

    @Test
    fun atTheMiddleTheIncomingLineSitsOnTheCurveAndTheFormsSplitAtHalf() {
        val (a, b) = slide(2, 3, 298f)
        val p = ExpOut6.transform(0.5f)
        val topA = a.list.first { it.line == 3 }.top
        val topB = b.list.first { it.line == 3 }.top
        val mid = LyricRows.column(a, b, p) { it }.first { it.line == 3 }
        assertEquals(topA + (topB - topA) * p, mid.top, 1f / 64)
        assertEquals(b.list.first { it.line == 3 }.alpha * (2 * p - 1), mid.alphaB, 1f / 256)
        assertTrue(mid.change)
        val before = LyricRows.column(a, b, 0.49f) { it }.first { it.line == 3 }
        val after = LyricRows.column(a, b, 0.51f) { it }.first { it.line == 3 }
        assertEquals(0f, before.alphaB, 0f)
        assertTrue(before.alphaA > 0f)
        assertEquals(0f, after.alphaA, 0f)
        assertTrue(after.alphaB > 0f)
    }

    @Test
    fun noTwoClipsOverlapOnAnyFrameOfAnyLineChange() {
        for (room in listOf(298f, 176f, 170f, 60f)) {
            for (scale in listOf(1f, 2.625f, 3.5f)) {
                for (to in 0 until device.size) {
                    val (a, b) = slide(to - 1, to, room)
                    var e = 0
                    while (e <= Tok.motion_lyricSlide + 16) {
                        val p = ExpOut6.transform((e / Tok.motion_lyricSlide).coerceIn(0f, 1f))
                        val r = LyricRows.column(a, b, p) { ceil(it * scale) }.filter { it.y1 > it.y0 }
                        for (i in r.indices) for (j in i + 1 until r.size) {
                            assertTrue("room $room scale $scale to $to e $e lines ${r[i].line} ${r[j].line}", minOf(r[i].y1, r[j].y1) <= maxOf(r[i].y0, r[j].y0))
                        }
                        e += 16
                    }
                }
            }
        }
    }

    @Test
    fun aCutCurrentFormDrawsOnlyItsRowsAndTheColumnEndsAtTheLastWholeRow() {
        val b = blocks(device, 4, 60f, w = words, cp = cp, np = np)
        val cur = b.list.first { it.big }
        assertEquals(1, cur.rows)
        assertEquals(1, LyricRows.lines(cur, device, "", null, words).size)
        val small = blocks(device, 3, 60f, w = words, cp = cp, np = np)
        assertEquals(cp + np, small.vis, 0.001f)
        val draws = LyricRows.column(null, small, 1f) { it }
        assertEquals(cp + np, draws.maxOf { it.y1 }, 0.001f)
    }

    @Test
    fun aBlockWithNoNeighbourKeepsItsOwnTop() {
        val empty = listOf("", "")
        val (a, b) = LyricRows.pair(blocks(empty, -1, 60f, cp = cp, np = np), blocks(empty, 0, 60f, cp = cp, np = np), { 0 }, np)
        assertTrue(a.list.isEmpty())
        val first = LyricRows.column(a, b, 0.5f) { it }.single()
        assertEquals(0f, first.top, 0f)
        assertEquals(cp / 2, first.h, 0.001f)
        val texts = listOf("", "x")
        val (c, d) = LyricRows.pair(blocks(texts, 0, 20f, cp = cp, np = np), blocks(texts, 1, 20f, cp = cp, np = np), { 1 }, np)
        assertTrue(d.list.isEmpty())
        val last = LyricRows.column(c, d, 0.5f) { it }.single()
        assertEquals(1, last.line)
        assertEquals(0f, last.top, 0f)
        assertEquals(np / 2, last.h, 0.001f)
    }
}
