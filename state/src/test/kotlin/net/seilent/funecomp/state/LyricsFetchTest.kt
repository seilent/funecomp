package net.seilent.funecomp.state

import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.LyrLine
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Feature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsFetchTest {
    private fun hello(features: Long = Groups.all, minor: Int = 7) = HelloReply(1, minor, "v1", "t", 240, 320, 240, 320, 16384, 30_000, 5_000, features, 1, 1, 4)

    private fun device(lyrics: Boolean? = true, plain: Boolean? = false, crc: Long? = 100, track: Long? = 7, snap: Long = 4, features: Long = Groups.all) =
        DeviceState(hello(features)).withState(State(track = track, lyrics = lyrics, lyricsPlain = plain, lyricsCrc = crc, snapRev = snap), 0)

    private val copy = LyricsFetch.Copy(100, Enums.LyricsKind.synced, listOf(LyrLine(0, "a")))

    @Test
    fun aKindAndANewCrcFetchesThenTheCacheShows() {
        val f = LyricsFetch()
        val d = f.decide(device(), 0)
        assertEquals(LyricsFetch.Show.Blank, d.show)
        assertEquals(LyricsFetch.Want(7, 100, 4, null), d.want)
        assertNull(f.done(d.want!!, LyricsFetch.Got.Lines(copy), 0))
        assertEquals(LyricsFetch.Decision(LyricsFetch.Show.Lines(7, copy), null), f.decide(device(), 0))
        assertEquals(LyricsFetch.Want(7, 101, 4, 100), f.decide(device(crc = 101), 0).want)
        assertEquals(LyricsFetch.Want(7, 102, 4, 100), f.decide(device(lyrics = false, plain = true, crc = 102), 0).want)
    }

    @Test
    fun bothFalseDropsAndNoFlagsKeeps() {
        val f = LyricsFetch()
        f.done(f.decide(device(), 0).want!!, LyricsFetch.Got.Lines(copy), 0)
        assertEquals(LyricsFetch.Decision(LyricsFetch.Show.Blank, null), f.decide(device(lyrics = null, plain = null, crc = null), 0))
        assertEquals(LyricsFetch.Show.Lines(7, copy), f.decide(device(), 0).show)
        assertEquals(LyricsFetch.Decision(LyricsFetch.Show.None, null), f.decide(device(lyrics = false, plain = false, crc = null), 0))
        assertEquals(LyricsFetch.Want(7, 100, 4, null), f.decide(device(), 0).want)
        assertEquals(LyricsFetch.Decision(LyricsFetch.Show.Blank, null), f.decide(device(crc = null), 0))
    }

    @Test
    fun noBit11IsOffAtAnyMinor() {
        val f = LyricsFetch()
        val off = Groups.all and (1L shl Feature.lyricsGet).inv()
        assertEquals(LyricsFetch.Decision(LyricsFetch.Show.Off, null), f.decide(device(features = off), 0))
        assertFalse(LyricsFetch.has(device(features = off)))
        assertEquals(LyricsFetch.Decision(LyricsFetch.Show.Off, null), f.decide(device(track = null), 0))
        val old = DeviceState(hello(Groups.all, minor = 6)).withState(State(track = 7, lyrics = true, lyricsPlain = false, lyricsCrc = 100), 0)
        assertEquals(LyricsFetch.Want(7, 100, null, null), f.decide(old, 0).want)
        assertTrue(LyricsFetch.has(old))
        assertTrue(LyricsFetch.has(device(lyrics = false, plain = true)))
        assertFalse(LyricsFetch.has(device(lyrics = null, plain = null, crc = null)))
        assertFalse(LyricsFetch.has(device(lyrics = false, plain = false, crc = null)))
    }

    @Test
    fun aFailedKeyAsksNothingUntilTrackCrcOrSnapMoves() {
        for (code in listOf(Err.staleRev, Err.noLibrary, Err.badValue, Err.refused, Err.unknown, null)) {
            val f = LyricsFetch()
            val w = f.decide(device(), 0).want!!
            assertNull(f.done(w, LyricsFetch.Got.Failed(code), 0))
            assertEquals("$code", LyricsFetch.Decision(LyricsFetch.Show.Blank, null), f.decide(device(), 0))
            assertEquals(LyricsFetch.Want(8, 100, 4, null), f.decide(device(track = 8), 0).want)
            assertEquals(LyricsFetch.Want(7, 101, 4, null), f.decide(device(crc = 101), 0).want)
            assertEquals(LyricsFetch.Want(7, 100, 5, null), f.decide(device(snap = 5), 0).want)
            assertNull(f.decide(device(snap = 4), 0).want)
            f.opened()
            assertEquals(w, f.decide(device(snap = 4), 0).want)
        }
    }

    @Test
    fun notFoundAndNoneShowNoneAndSurviveAnOpen() {
        val f = LyricsFetch()
        val w = f.decide(device(), 0).want!!
        f.done(w, LyricsFetch.Got.Failed(Err.notFound), 0)
        f.opened()
        assertEquals(LyricsFetch.Decision(LyricsFetch.Show.None, null), f.decide(device(), 0))
        f.done(f.decide(device(crc = 101), 0).want!!, LyricsFetch.Got.None(101), 0)
        assertEquals(LyricsFetch.Decision(LyricsFetch.Show.None, null), f.decide(device(crc = 101), 0))
        f.session()
        assertEquals(w, f.decide(device(), 0).want)
    }

    @Test
    fun busyWaitsOnTheBackoffThenFails() {
        val f = LyricsFetch()
        val w = f.decide(device(), 0).want!!
        var now = 0L
        for (ms in listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L)) {
            assertEquals(ms, f.done(w, LyricsFetch.Got.Failed(Err.busy), now))
            assertNull(f.decide(device(), now + ms - 1).want)
            now += ms
            assertEquals(w, f.decide(device(), now).want)
        }
        assertNull(f.done(w, LyricsFetch.Got.Failed(Err.busy), now))
        assertNull(f.decide(device(), now + 100_000).want)
    }

    @Test
    fun aResultForAnOlderCrcIsCachedButNotShownAndFetchesAgain() {
        val f = LyricsFetch()
        val w = f.decide(device(), 0).want!!
        f.done(w, LyricsFetch.Got.Lines(copy), 0)
        val d = f.decide(device(crc = 200), 0)
        assertEquals(LyricsFetch.Show.Blank, d.show)
        assertEquals(LyricsFetch.Want(7, 200, 4, 100), d.want)
        val newer = copy.copy(crc = 300)
        assertNull(f.done(d.want!!, LyricsFetch.Got.Lines(newer), 0))
        assertEquals(LyricsFetch.Decision(LyricsFetch.Show.Blank, null), f.decide(device(crc = 200), 0))
        assertEquals(LyricsFetch.Show.Lines(7, newer), f.decide(device(crc = 300), 0).show)
        f.done(f.decide(device(crc = 400), 0).want!!, LyricsFetch.Got.Have(300), 0)
        assertEquals(LyricsFetch.Decision(LyricsFetch.Show.Blank, null), f.decide(device(crc = 400), 0))
        f.done(f.decide(device(crc = 500), 0).want!!, LyricsFetch.Got.None(600), 0)
        assertEquals(LyricsFetch.Decision(LyricsFetch.Show.Blank, null), f.decide(device(crc = 500), 0))
        assertEquals(LyricsFetch.Decision(LyricsFetch.Show.None, null), f.decide(device(crc = 600), 0))
    }

    @Test
    fun aSnapRevChangeOrASessionOrADropClearsTheCache() {
        val f = LyricsFetch()
        f.done(f.decide(device(), 0).want!!, LyricsFetch.Got.Lines(copy), 0)
        assertEquals(LyricsFetch.Want(7, 100, 5, null), f.decide(device(snap = 5), 0).want)
        f.done(f.decide(device(snap = 5), 0).want!!, LyricsFetch.Got.Lines(copy), 0)
        f.session()
        assertEquals(LyricsFetch.Want(7, 100, 5, null), f.decide(device(snap = 5), 0).want)
        f.done(f.decide(device(snap = 5), 0).want!!, LyricsFetch.Got.Lines(copy), 0)
        f.dropped(7)
        assertEquals(LyricsFetch.Want(7, 100, 5, null), f.decide(device(snap = 5), 0).want)
    }

    @Test
    fun marksKeepTheLast64Keys() {
        val f = LyricsFetch()
        for (c in 1L..LyricsFetch.MARKS + 1L) f.done(f.decide(device(crc = c), 0).want!!, LyricsFetch.Got.Failed(Err.staleRev), 0)
        assertEquals(LyricsFetch.Want(7, 1, 4, null), f.decide(device(crc = 1), 0).want)
        assertNull(f.decide(device(crc = LyricsFetch.MARKS + 1L), 0).want)
        assertNull(f.decide(device(crc = 3), 0).want)
    }

    @Test
    fun theCacheKeepsTheLastEightTracks() {
        val f = LyricsFetch()
        for (t in 1L..9L) f.done(f.decide(device(track = t), 0).want!!, LyricsFetch.Got.Lines(copy), 0)
        assertEquals(LyricsFetch.Want(1, 100, 4, null), f.decide(device(track = 1), 0).want)
        for (t in 3L..9L) assertEquals(LyricsFetch.Show.Lines(t, copy), f.decide(device(track = t), 0).show)
    }

    @Test
    fun lineIndexAndDue() {
        val lines = listOf(LyrLine(1000, "a"), LyrLine(2000, "b"), LyrLine(2000, "c"), LyrLine(5000, "d"))
        assertEquals(-1, LyricsFetch.index(lines, 999))
        assertEquals(0, LyricsFetch.index(lines, 1000))
        assertEquals(0, LyricsFetch.index(lines, 1999))
        assertEquals(2, LyricsFetch.index(lines, 2000))
        assertEquals(3, LyricsFetch.index(lines, 5000))
        assertEquals(3, LyricsFetch.index(lines, 99_000))
        assertEquals(-1, LyricsFetch.index(emptyList(), 0))
        assertEquals(1L, LyricsFetch.dueMs(lines, -1, 999))
        assertEquals(1000L, LyricsFetch.dueMs(lines, -1, 0))
        assertEquals(1L, LyricsFetch.dueMs(lines, 0, 2500))
        assertEquals(3000L, LyricsFetch.dueMs(lines, 2, 2000))
        assertNull(LyricsFetch.dueMs(lines, 3, 6000))
    }
}
