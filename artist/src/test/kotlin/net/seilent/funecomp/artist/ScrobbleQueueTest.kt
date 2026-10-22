package net.seilent.funecomp.artist

import net.seilent.funecomp.proto.PlayEntry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.time.ZoneOffset

class ScrobbleQueueTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val lastFm = setOf(Accounts.LAST_FM)
    private val utc: ZoneId = ZoneOffset.UTC
    private val now = 1_800_000_000L

    private fun entry(seq: Long, title: String = "Track $seq", startLocalS: Long? = now - 86400 + seq * 60, zoneS: Int? = 0, artist: String? = "Radiohead", liveSeen: Boolean? = null) =
        PlayEntry(seq, startLocalS, 200_000, 240_000, 7, title, artist, listOfNotNull(artist), emptyList(), "OK Computer", zoneS = zoneS, liveSeen = liveSeen)

    private fun queue(dir: File = tmp.root, sync: (File) -> Unit = {}) = ScrobbleQueue(dir, sync)

    @Test
    fun oldSixFieldLinesStillRead() {
        val dir = tmp.newFolder("old")
        File(dir, ScrobbleQueue.QUEUE).writeBytes(Fixtures.load("queue-v1.tsv"))
        File(dir, "sent.lastfm").writeText("1")
        val q = queue(dir)
        assertEquals(listOf(Listen(1799996400, "Radiohead", "Airbag", "OK Computer", 240, 2), Listen(1799996700, "Radiohead", "Lucky", null, null, 3)), q.after(Accounts.LAST_FM))
        assertEquals(4L, q.add(Listen(now, "Radiohead", "Let Down"), lastFm)!!.id)
    }

    @Test
    fun v2LinesRoundTripWithTabsAndNewlinesCleaned() {
        val l = Listen(now, "Ra\tdio", "Air\nbag", "OK\rComputer", 240, 5, "Radio\thead", 3, listOf("a74b1b7f-71a5-4011-9441-d0b5e4122711", "b1"))
        val back = Listen.parse(l.line())!!
        assertEquals(Listen(now, "Ra dio", "Air bag", "OK Computer", 240, 5, "Radio head", 3, listOf("a74b1b7f-71a5-4011-9441-d0b5e4122711", "b1")), back)
        assertEquals(10, l.line().split('\t').size)
        assertEquals(Listen(now, "A", "T", id = 6), Listen.parse(Listen(now, "A", "T", id = 6).line()))
    }

    @Test
    fun linesOfOtherFieldCountsAreDropped() {
        val ten = Listen(now, "A", "T", id = 1).line()
        assertNull(Listen.parse(ten.split('\t').dropLast(1).joinToString("\t")))
        assertNull(Listen.parse("$ten\tx"))
        assertNull(Listen.parse("v3" + ten.removePrefix("v2")))
    }

    @Test
    fun cursorHeadersRoundTripAndKeepEightLogs() {
        val dir = tmp.newFolder("logs")
        val q = queue(dir)
        for (log in 1L..9L) q.addLog(log or Long.MIN_VALUE, listOf(entry(log * 10)), lastFm, utc, now)
        val again = queue(dir)
        assertNull(again.dap(1L or Long.MIN_VALUE))
        for (log in 2L..9L) assertEquals(log * 10, again.dap(log or Long.MIN_VALUE))
        assertEquals(9, again.size)
        assertTrue(File(dir, ScrobbleQueue.QUEUE).readLines().any { it == "dap\t8000000000000009\t90" })
    }

    @Test
    fun aPageQueuesTheScrobbledEntriesAndMovesTheCursorPastAll() {
        val q = queue()
        val page = listOf(entry(1, liveSeen = true), entry(2, startLocalS = null, zoneS = null), entry(3, artist = " "), entry(4), entry(5, title = ""))
        assertEquals(5L, q.addLog(0xA1, page, lastFm, utc, now))
        assertEquals(listOf("Track 4"), q.after(Accounts.LAST_FM).map { it.title })
        assertEquals(5L, q.dap(0xA1))
    }

    @Test
    fun aPageAtOrBelowTheCursorQueuesNothingAndStillGivesItsLastSeq() {
        val q = queue()
        q.addLog(0xA1, (1L..5L).map { entry(it) }, lastFm, utc, now)
        assertEquals(5, q.size)
        val bytes = File(tmp.root, ScrobbleQueue.QUEUE).readBytes()
        assertEquals(4L, q.addLog(0xA1, (3L..4L).map { entry(it) }, lastFm, utc, now))
        assertEquals(5L, q.addLog(0xA1, (1L..5L).map { entry(it) }, lastFm, utc, now))
        assertEquals(5, q.size)
        assertArrayEquals(bytes, File(tmp.root, ScrobbleQueue.QUEUE).readBytes())
    }

    @Test
    fun noServiceStoresNothing() {
        val q = queue()
        assertNull(q.addLog(0xA1, listOf(entry(1)), emptySet(), utc, now))
        assertEquals(0, q.size)
        assertNull(q.dap(0xA1))
        assertTrue(!File(tmp.root, ScrobbleQueue.QUEUE).exists())
    }

    @Test
    fun theSyncHookRunsBeforeAddLogReturns() {
        var syncs = 0
        val q = queue(sync = { syncs++ })
        q.cursor(Accounts.LAST_FM)
        val before = syncs
        q.addLog(0xA1, listOf(entry(1)), lastFm, utc, now)
        assertEquals(before + 1, syncs)
    }

    @Test
    fun aFailedWriteThrowsAndLeavesFileAndMemoryAsBefore() {
        val dir = tmp.newFolder("ro")
        val q = queue(dir)
        q.addLog(0xA1, listOf(entry(1)), lastFm, utc, now)
        val bytes = File(dir, ScrobbleQueue.QUEUE).readBytes()
        dir.setWritable(false)
        try {
            q.addLog(0xA1, listOf(entry(2), entry(3)), lastFm, utc, now)
            fail("no throw")
        } catch (_: IOException) {
        } finally {
            dir.setWritable(true)
        }
        assertArrayEquals(bytes, File(dir, ScrobbleQueue.QUEUE).readBytes())
        assertEquals(1, q.size)
        assertEquals(1L, q.dap(0xA1))
    }

    @Test
    fun aFailedSyncThrowsAndALaterAddWritesNoLineOfThePage() {
        var broken = false
        val dir = tmp.newFolder("sync")
        val q = queue(dir) { if (broken) throw IOException("sync") }
        q.addLog(0xA1, listOf(entry(1)), lastFm, utc, now)
        broken = true
        try {
            q.addLog(0xA1, listOf(entry(2), entry(3)), lastFm, utc, now)
            fail("no throw")
        } catch (_: IOException) {
        }
        assertEquals(1, q.size)
        assertEquals(1L, q.dap(0xA1))
        broken = false
        val l = q.add(Listen(now, "Radiohead", "Let Down"), lastFm)!!
        assertEquals(2L, l.id)
        val again = queue(dir)
        assertEquals(listOf("Track 1", "Let Down"), again.after(Accounts.LAST_FM).map { it.title })
        assertEquals(1L, again.dap(0xA1))
    }

    @Test
    fun startTimeIsUtcFromTheZoneOrThePhoneRules() {
        val berlin = ZoneId.of("Europe/Berlin")
        assertEquals(1_000_000L - 3600, Listen.startS(1_000_000, 3600, berlin))
        val beforeDst = 1774742400L
        assertEquals(beforeDst - 3600, Listen.startS(beforeDst, null, berlin))
        val afterDst = 1774756800L
        assertEquals(afterDst - 7200, Listen.startS(afterDst, null, berlin))
        val overlap = 1792895400L
        assertEquals(overlap - 7200, Listen.startS(overlap, null, berlin))
    }

    @Test
    fun anEntryOverTenMinutesAheadIsSkippedAndItsCursorMoves() {
        val q = queue()
        val notes = ArrayList<String>()
        assertEquals(2L, q.addLog(0xA1, listOf(entry(1, startLocalS = now + 660), entry(2, startLocalS = now + 540)), lastFm, utc, now) { notes += it })
        assertEquals(listOf("Track 2"), q.after(Accounts.LAST_FM).map { it.title })
        assertEquals(2L, q.dap(0xA1))
        assertEquals(1, notes.size)
    }

    @Test
    fun entryFieldsBecomeTheListen() {
        val e = PlayEntry(1, now, 1, 241_500, 7, " Airbag ", null, listOf("Radiohead", "Thom"), listOf("a74b1b7f-71a5-4011-9441-d0b5e4122711", "8bfac288-ccc5-448d-9573-c33ea2aa5c30"), " ", "Radiohead", 0, -3600)
        assertEquals(Listen(now + 3600, "Radiohead, Thom", "Airbag", null, 241, albumArtist = "Radiohead", mbids = listOf("a74b1b7f-71a5-4011-9441-d0b5e4122711", "8bfac288-ccc5-448d-9573-c33ea2aa5c30")), Listen.of(e, utc))
        assertEquals(emptyList<String>(), Listen.of(e.copy(mbids = listOf("a74b1b7f-71a5-4011-9441-d0b5e4122711", "A74B1B7F-71A5-4011-9441-D0B5E4122711")), utc)!!.mbids)
        assertEquals(3, Listen.of(e.copy(trackNo = 3), utc)!!.trackNo)
    }

    @Test
    fun enablingAServiceStartsAtTheOldestQueuedLine() {
        val q = queue()
        q.sent(Accounts.LAST_FM, 0)
        repeat(7) { q.add(Listen(now + it, "A", "T$it"), lastFm) }
        q.sent(Accounts.LAST_FM, 2)
        q.compact(lastFm)
        q.enable(Accounts.LISTEN_BRAINZ)
        assertEquals((3L..7L).toList(), q.after(Accounts.LISTEN_BRAINZ).map { it.id })
    }

    @Test
    fun reEnablingKeepsAHigherStoredCursor() {
        val q = queue()
        q.sent(Accounts.LAST_FM, 0)
        repeat(7) { q.add(Listen(now + it, "A", "T$it"), lastFm) }
        q.sent(Accounts.LISTEN_BRAINZ, 5)
        q.enable(Accounts.LISTEN_BRAINZ)
        assertEquals((6L..7L).toList(), q.after(Accounts.LISTEN_BRAINZ).map { it.id })
    }

    @Test
    fun aCursorCreatedBeforeEnableStartsAtTheOldestQueuedLine() {
        val q = queue()
        q.sent(Accounts.LAST_FM, 0)
        repeat(3) { q.add(Listen(now + it, "A", "T$it"), lastFm) }
        q.pending(setOf(Accounts.LAST_FM, Accounts.LISTEN_BRAINZ))
        q.enable(Accounts.LISTEN_BRAINZ)
        assertEquals((1L..3L).toList(), q.after(Accounts.LISTEN_BRAINZ).map { it.id })
    }

    @Test
    fun compactWithNoServiceKeepsEveryLine() {
        val q = queue()
        q.sent(Accounts.LAST_FM, 0)
        repeat(3) { q.add(Listen(now + it, "A", "T$it"), lastFm) }
        q.compact(emptySet())
        assertEquals(3, q.size)
        assertEquals(3, queue().size)
    }
}
