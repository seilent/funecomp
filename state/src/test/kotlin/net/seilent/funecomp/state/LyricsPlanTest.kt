package net.seilent.funecomp.state

import net.seilent.funecomp.proto.QueueItem
import net.seilent.funecomp.proto.State
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsPlanTest {
    private fun item(t: Long, lyrics: Boolean?, plain: Boolean? = null) = QueueItem(t, "T$t", listOf("A$t"), album = "Al", durationMs = 1000L * t, lyrics = lyrics, lyricsPlain = plain)

    private fun device(lyrics: Boolean?, items: Map<Long, QueueItem>, plain: Boolean? = null) = DeviceState(
        playback = Playback(State(track = 1, title = "One", artist = "Utada Hikaru", artists = listOf("Utada Hikaru"), album = "Bad Mode", durationMs = 261_000, slot = 0, lyrics = lyrics, lyricsPlain = plain)),
        queue = QueueMirror(1, 10, items),
    )

    @Test
    fun currentThenTheNextFiveWithLyricsFalse() {
        val items = (1L..8L).associate { it to item(it + 1, if (it == 3L) true else if (it == 4L) null else false) }
        val w = LyricsPlan.wants(device(false, items), emptySet())
        assertEquals(listOf(1L, 2L, 3L, 6L), w.map { it.track })
        assertEquals(LyricsPlan.Want(1, "One", "Utada Hikaru", "Bad Mode", 261_000, false, listOf("Utada Hikaru")), w[0])
        assertEquals(LyricsPlan.Want(2, "T2", "A2", "Al", 2000, false, listOf("A2")), w[1])
    }

    @Test
    fun absentNeverAsksAndDoneIsSkipped() {
        val items = mapOf(1L to item(2, null), 2L to item(3, false))
        assertEquals(listOf(3L), LyricsPlan.wants(device(null, items), emptySet()).map { it.track })
        assertEquals(listOf(3L), LyricsPlan.wants(device(false, items), setOf(1L)).map { it.track })
        assertEquals(emptyList<Long>(), LyricsPlan.wants(device(true, emptyMap()), emptySet()).map { it.track })
    }

    @Test
    fun plainOnlyWhenTheTagSaysTheDeviceHasNone() {
        assertEquals(listOf(false), LyricsPlan.wants(device(false, emptyMap()), emptySet()).map { it.plain })
        assertEquals(listOf(true), LyricsPlan.wants(device(false, emptyMap(), plain = false), emptySet()).map { it.plain })
        assertEquals(listOf(false), LyricsPlan.wants(device(false, emptyMap(), plain = true), emptySet()).map { it.plain })
        assertEquals(emptyList<Long>(), LyricsPlan.wants(device(true, emptyMap(), plain = false), emptySet()).map { it.track })
        assertEquals(emptyList<Long>(), LyricsPlan.wants(device(true, emptyMap(), plain = true), emptySet()).map { it.track })
    }

    @Test
    fun aMixedQueueWindowKeepsPerItemValues() {
        val items = mapOf(
            1L to item(2, false, false),
            2L to item(3, false, true),
            3L to item(4, false, null),
            4L to item(5, true, false),
            5L to item(6, true, true),
        )
        val w = LyricsPlan.wants(device(false, items, plain = true), emptySet())
        assertEquals(listOf(1L to false, 2L to true, 3L to false, 4L to false), w.map { it.track to it.plain })
    }

    @Test
    fun heldTakesTheWindowWithSyncedAndPlainCopies() {
        val items = mapOf(
            1L to item(2, true, false),
            2L to item(3, false, true),
            3L to item(4, false, false),
            4L to item(5, null, null),
            5L to item(6, true, null),
            6L to item(7, true, false),
        )
        val h = LyricsPlan.held(device(true, items, plain = false), emptySet())
        assertEquals(listOf(1L, 2L, 3L, 6L), h.map { it.track })
        assertEquals(LyricsPlan.Held(1, "One", "Utada Hikaru", listOf("Utada Hikaru"), "Bad Mode", 261_000, true, true, listOf("Utada Hikaru")), h[0])
        assertEquals(LyricsPlan.Held(2, "T2", "A2", listOf("A2"), "Al", 2000, true, false, listOf("A2")), h[1])
        assertEquals(listOf(true, false, true), h.drop(1).map { it.synced })
    }

    @Test
    fun heldCarriesBothArtistStringsOfThePlayingTrack() {
        val d = DeviceState(playback = Playback(State(track = 1, title = "One", artist = "A feat. B", artists = listOf("A", "B"), slot = 0, lyrics = true)))
        assertEquals(listOf("A feat. B", "A, B"), LyricsPlan.held(d, emptySet()).single().keys)
        val joined = DeviceState(playback = Playback(State(track = 1, title = "One", artists = listOf("A", "B"), slot = 0, lyricsPlain = true)))
        assertEquals(LyricsPlan.Held(1, "One", "A, B", listOf("A, B"), null, null, false, true, listOf("A", "B")), LyricsPlan.held(joined, emptySet()).single())
    }

    @Test
    fun heldNeverTakesAbsentOrFalseFlagsOrDoneTracks() {
        val items = mapOf(1L to item(2, null, null), 2L to item(3, false, false), 3L to item(4, true))
        assertEquals(emptyList<Long>(), LyricsPlan.held(device(null, items), setOf(4L)).map { it.track })
        assertEquals(emptyList<Long>(), LyricsPlan.held(device(false, emptyMap(), plain = false), emptySet()).map { it.track })
        assertEquals(listOf(4L), LyricsPlan.held(device(true, items), setOf(1L)).map { it.track })
    }

    @Test
    fun aMarkedQueueSlotPassedAsExcludedIsNoWant() {
        val items = mapOf(1L to item(2, false, true), 2L to item(3, false, false))
        val d = device(false, items, plain = false)
        assertEquals(listOf(1L, 2L, 3L), LyricsPlan.wants(d, emptySet()).map { it.track })
        assertEquals(listOf(2L), LyricsPlan.held(d, emptySet()).map { it.track })
        assertEquals(listOf(1L, 3L), LyricsPlan.wants(d, LyricsPlan.held(d, emptySet()).map { it.track }.toSet()).map { it.track })
    }
}
