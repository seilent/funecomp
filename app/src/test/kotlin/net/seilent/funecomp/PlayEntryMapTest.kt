package net.seilent.funecomp

import net.seilent.funecomp.proto.PlayEntry
import net.seilent.funekit.core.PlayEntry as LogEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayEntryMapTest {
    @Test
    fun everyFieldTheQueueReadsIsMapped() {
        val wire = PlayEntry(
            seq = 7,
            startLocalS = 1_700_000_000,
            playedMs = 180_000,
            durationMs = 200_000,
            track = 42,
            title = "Airbag",
            artist = "Radiohead",
            artists = listOf("Radiohead", "Nigel Godrich"),
            mbids = listOf("a74b1b7f-71a5-4011-9441-d0b5e4122711"),
            album = "OK Computer",
            albumArtist = "Radiohead",
            trackNo = 1,
            zoneS = 32_400,
            liveSeen = true,
            phoneFile = 3,
            service = "tidal",
            serviceKey = 9,
            exposed = true,
            sentTo = 2,
        )
        val want = LogEntry(
            seq = 7,
            startLocalS = 1_700_000_000,
            durationMs = 200_000,
            title = "Airbag",
            artist = "Radiohead",
            artists = listOf("Radiohead", "Nigel Godrich"),
            mbids = listOf("a74b1b7f-71a5-4011-9441-d0b5e4122711"),
            album = "OK Computer",
            albumArtist = "Radiohead",
            trackNo = 1,
            zoneS = 32_400,
            liveSeen = true,
            exposed = true,
        )
        assertEquals(want, wire.logged())
    }
}
