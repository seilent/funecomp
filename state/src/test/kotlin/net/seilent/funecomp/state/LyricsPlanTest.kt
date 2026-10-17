package net.seilent.funecomp.state

import net.seilent.funecomp.proto.QueueItem
import net.seilent.funecomp.proto.State
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsPlanTest {
    private fun item(t: Long, lyrics: Boolean?) = QueueItem(t, "T$t", listOf("A$t"), album = "Al", durationMs = 1000L * t, lyrics = lyrics)

    private fun device(lyrics: Boolean?, items: Map<Long, QueueItem>) = DeviceState(
        playback = Playback(State(track = 1, title = "One", artist = "Utada Hikaru", album = "Bad Mode", durationMs = 261_000, slot = 0, lyrics = lyrics)),
        queue = QueueMirror(1, 10, items),
    )

    @Test
    fun currentThenTheNextFiveWithLyricsFalse() {
        val items = (1L..8L).associate { it to item(it + 1, if (it == 3L) true else if (it == 4L) null else false) }
        val w = LyricsPlan.wants(device(false, items), emptySet())
        assertEquals(listOf(1L, 2L, 3L, 6L), w.map { it.track })
        assertEquals(LyricsPlan.Want(1, "One", "Utada Hikaru", "Bad Mode", 261_000), w[0])
        assertEquals(LyricsPlan.Want(2, "T2", "A2", "Al", 2000), w[1])
    }

    @Test
    fun absentNeverAsksAndDoneIsSkipped() {
        val items = mapOf(1L to item(2, null), 2L to item(3, false))
        assertEquals(listOf(3L), LyricsPlan.wants(device(null, items), emptySet()).map { it.track })
        assertEquals(listOf(3L), LyricsPlan.wants(device(false, items), setOf(1L)).map { it.track })
        assertEquals(emptyList<Long>(), LyricsPlan.wants(device(true, emptyMap()), emptySet()).map { it.track })
    }
}
