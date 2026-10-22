package net.seilent.funecomp.state

import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.LyrLine
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Feature
import net.seilent.funecomp.proto.gen.Proto
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
            assertNull(f.decide(device(), 0).want)
            assertEquals(LyricsFetch.Want(7, 100, 5, null), f.decide(device(snap = 5), 0).want)
            assertNull(f.decide(device(snap = 4), 0).want)
            f.opened()
            assertEquals(w, f.decide(device(snap = 4), 0).want)
            f.done(w, LyricsFetch.Got.Failed(code), 0)
            assertEquals(LyricsFetch.Want(7, 101, 4, null), f.decide(device(crc = 101), 0).want)
            assertEquals(w, f.decide(device(), 0).want)
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
        for (t in 1L..LyricsFetch.MARKS + 1L) f.done(f.decide(device(track = t), 0).want!!, LyricsFetch.Got.Failed(Err.staleRev), 0)
        assertEquals(LyricsFetch.Want(1, 100, 4, null), f.decide(device(track = 1), 0).want)
        assertNull(f.decide(device(track = LyricsFetch.MARKS + 1L), 0).want)
        assertNull(f.decide(device(track = 3), 0).want)
    }

    @Test
    fun theCacheKeepsTheLastEightTracks() {
        val f = LyricsFetch()
        for (t in 1L..9L) f.done(f.decide(device(track = t), 0).want!!, LyricsFetch.Got.Lines(copy), 0)
        assertEquals(LyricsFetch.Want(1, 100, 4, null), f.decide(device(track = 1), 0).want)
        for (t in 3L..9L) assertEquals(LyricsFetch.Show.Lines(t, copy), f.decide(device(track = t), 0).show)
    }

    private fun at(crc: Long?, ms: Long, lyrics: Boolean? = true, plain: Boolean? = false, track: Long = 7) =
        DeviceState(hello()).withState(State(track = track, lyrics = lyrics, lyricsPlain = plain, lyricsCrc = crc, snapRev = 4), ms)

    private fun lines(crc: Long) = LyricsFetch.Copy(crc, Enums.LyricsKind.synced, listOf(LyrLine(crc, "l$crc")))

    @Test
    fun aRevisitedCrcRefetchesAfterAMismatchedReply() {
        val f = LyricsFetch()
        f.done(f.decide(device(crc = 0), 0).want!!, LyricsFetch.Got.Lines(lines(0)), 0)
        val c1 = f.decide(device(crc = 1), 0).want!!
        f.done(c1, LyricsFetch.Got.Lines(lines(2)), 0)
        assertNull(f.decide(device(crc = 1), 0).want)
        assertEquals(LyricsFetch.Show.Lines(7, lines(2)), f.decide(device(crc = 2), 0).show)
        val again = f.decide(device(crc = 1), 0)
        assertEquals(LyricsFetch.Want(7, 1, 4, 2), again.want)
        f.done(again.want!!, LyricsFetch.Got.Lines(lines(1)), 0)
        assertEquals(LyricsFetch.Show.Lines(7, lines(1)), f.decide(device(crc = 1), 0).show)
    }

    @Test
    fun aStepAndRestoreBeforeTheRefetchThenTheSameStepRefetches() {
        val f = LyricsFetch()
        f.done(f.decide(device(crc = 0), 0).want!!, LyricsFetch.Got.Lines(lines(0)), 0)
        val c1 = f.decide(device(crc = 1), 0).want!!
        assertEquals(0L, c1.have)
        f.done(c1, LyricsFetch.Got.Have(0), 0)
        assertEquals(LyricsFetch.Show.Lines(7, lines(0)), f.decide(device(crc = 0), 0).show)
        assertEquals(c1, f.decide(device(crc = 1), 0).want)
    }

    @Test
    fun aRestartOverflowRefetchesAtTheNextCrc() {
        val f = LyricsFetch()
        val c1 = f.decide(device(crc = 1), 0).want!!
        f.done(c1, LyricsFetch.Got.Failed(null), 0)
        assertNull(f.decide(device(crc = 1), 0).want)
        assertEquals(LyricsFetch.Want(7, 2, 4, null), f.decide(device(crc = 2), 0).want)
    }

    @Test
    fun aSteadyCrcAnsweredWithAnotherAsksOnce() {
        val f = LyricsFetch()
        val c1 = f.decide(device(crc = 1), 0).want!!
        f.done(c1, LyricsFetch.Got.Lines(lines(2)), 0)
        for (i in 0 until 3) assertNull(f.decide(device(crc = 1), 0).want)
    }

    @Test
    fun noneMarksSurviveACrcMove() {
        val f = LyricsFetch()
        f.done(f.decide(device(crc = 1), 0).want!!, LyricsFetch.Got.None(1), 0)
        f.decide(device(crc = 2), 0)
        assertEquals(LyricsFetch.Decision(LyricsFetch.Show.None, null), f.decide(device(crc = 1), 0))
    }

    private fun held(): LyricsFetch {
        val f = LyricsFetch()
        f.done(f.decide(at(0, 10), 0).want!!, LyricsFetch.Got.Lines(lines(0)), 0)
        f.hold(7, 10)
        return f
    }

    @Test
    fun aHoldKeepsTheOldCopyAcrossAMovedCrcForThatTrackOnly() {
        val f = held()
        val d = f.decide(at(1, 20), 0)
        assertEquals(LyricsFetch.Show.Lines(7, lines(0), held = true), d.show)
        assertEquals(LyricsFetch.Want(7, 1, 4, 0), d.want)
        f.done(d.want!!, LyricsFetch.Got.Lines(lines(1)), 0)
        assertEquals(LyricsFetch.Show.Lines(7, lines(1)), f.decide(at(1, 20), 0).show)
        assertEquals(LyricsFetch.Show.Blank, f.decide(at(2, 30), 0).show)
        val other = held()
        assertEquals(LyricsFetch.Show.Blank, other.decide(at(1, 20, track = 8), 0).show)
        assertEquals(LyricsFetch.Show.Blank, other.decide(at(1, 30), 0).show)
        val none = LyricsFetch()
        none.hold(7, 0)
        assertEquals(LyricsFetch.Show.Blank, none.decide(at(1, 20), 0).show)
    }

    @Test
    fun aHoldEndsOnTheCachedCrcFromANewerState() {
        val f = held()
        assertEquals(LyricsFetch.Show.Lines(7, lines(0), held = true), f.decide(at(0, 10), 0).show)
        assertEquals(LyricsFetch.Show.Lines(7, lines(0), held = true), f.decide(at(1, 20), 0).show)
        assertEquals(LyricsFetch.Show.Lines(7, lines(0)), f.decide(at(0, 30), 0).show)
        assertEquals(LyricsFetch.Show.Blank, f.decide(at(1, 40), 0).show)
    }

    @Test
    fun aRefetchForAnOlderCrcKeepsTheHoldOfTheNextStep() {
        val f = held()
        val w1 = f.decide(at(1, 20), 0).want!!
        f.hold(7, 20)
        f.done(w1, LyricsFetch.Got.Lines(lines(1)), 0)
        assertEquals(LyricsFetch.Show.Lines(7, lines(1), held = true), f.decide(at(1, 20), 0).show)
        val d = f.decide(at(2, 30), 0)
        assertEquals(LyricsFetch.Show.Lines(7, lines(1), held = true), d.show)
        assertEquals(LyricsFetch.Want(7, 2, 4, 1), d.want)
        f.done(d.want!!, LyricsFetch.Got.Lines(lines(2)), 0)
        assertEquals(LyricsFetch.Show.Lines(7, lines(2)), f.decide(at(2, 30), 0).show)
        assertEquals(LyricsFetch.Show.Blank, f.decide(at(3, 40), 0).show)
    }

    @Test
    fun aHoldEndsOnFailedTrackChangeOrUnholdButNotOnAMismatch() {
        val f = held()
        val w = f.decide(at(1, 20), 0).want!!
        f.done(w, LyricsFetch.Got.Lines(lines(2)), 0)
        assertEquals(LyricsFetch.Show.Lines(7, lines(2), held = true), f.decide(at(1, 20), 0).show)
        val w3 = f.decide(at(3, 30), 0).want!!
        f.done(w3, LyricsFetch.Got.Failed(null), 0)
        assertEquals(LyricsFetch.Show.Blank, f.decide(at(3, 30), 0).show)
        val t = held()
        t.decide(at(5, 20, track = 8), 0)
        assertEquals(LyricsFetch.Show.Blank, t.decide(at(1, 30), 0).show)
        val u = held()
        u.unhold()
        assertEquals(LyricsFetch.Show.Blank, u.decide(at(1, 20), 0).show)
        val b = held()
        val wb = b.decide(at(1, 20), 0).want!!
        b.done(wb, LyricsFetch.Got.Failed(Err.busy), 0)
        assertEquals(LyricsFetch.Show.Lines(7, lines(0), held = true), b.decide(at(1, 20), 0).show)
    }

    @Test
    fun aBusyLimitOnTheRefetchEndsTheHold() {
        val f = held()
        val w = f.decide(at(1, 20), 0).want!!
        repeat(Proto.backoffMs.size) {
            f.done(w, LyricsFetch.Got.Failed(Err.busy), 0)
            assertEquals(LyricsFetch.Show.Lines(7, lines(0), held = true), f.decide(at(1, 20), 0).show)
        }
        assertNull(f.done(w, LyricsFetch.Got.Failed(Err.busy), 0))
        assertEquals(LyricsFetch.Decision(LyricsFetch.Show.Blank, null), f.decide(at(1, 20), 0))
    }

    @Test
    fun anEmptiedCueSpanEndsTheHoldAndAStepBackRefetches() {
        val blank = held()
        assertEquals(LyricsFetch.Decision(LyricsFetch.Show.Blank, null), blank.decide(at(null, 20, lyrics = null, plain = null), 0))
        assertEquals(LyricsFetch.Show.Blank, blank.decide(at(1, 30), 0).show)
        val none = held()
        assertEquals(LyricsFetch.Decision(LyricsFetch.Show.None, null), none.decide(at(null, 20, lyrics = false, plain = false), 0))
        val back = none.decide(at(1, 30), 0)
        assertEquals(LyricsFetch.Want(7, 1, 4, null), back.want)
        none.done(back.want!!, LyricsFetch.Got.Lines(lines(1)), 0)
        assertEquals(LyricsFetch.Show.Lines(7, lines(1)), none.decide(at(1, 30), 0).show)
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

    private fun at(lyrics: Boolean? = true, plain: Boolean? = false, track: Long? = 7, source: Int? = Enums.Source.local, features: Long = Groups.all) =
        DeviceState(hello(features)).withState(State(source = source, track = track, lyrics = lyrics, lyricsPlain = plain), 5)

    @Test
    fun followCountsTimedLyricsOnly() {
        assertNull(LyricsFetch.follow(DeviceState()))
        assertNull(LyricsFetch.follow(DeviceState().withHello(hello())))
        assertNull(LyricsFetch.follow(at(lyrics = null, plain = null)))
        val off = Groups.all and (1L shl Feature.lyricsGet).inv()
        assertEquals(false, LyricsFetch.follow(at(features = off)))
        assertEquals(false, LyricsFetch.follow(at(track = null)))
        assertEquals(false, LyricsFetch.follow(at(lyrics = false, plain = true)))
        assertEquals(false, LyricsFetch.follow(at(lyrics = false, plain = false)))
        assertEquals(false, LyricsFetch.follow(at(source = Enums.Source.phone)))
        assertEquals(true, LyricsFetch.follow(at()))
        assertEquals(true, LyricsFetch.follow(at(plain = true)))
    }

    @Test
    fun lyrflagsIsMinor15OrAbove() {
        assertFalse(LyricsFetch.lyrflags(DeviceState()))
        assertFalse(LyricsFetch.lyrflags(DeviceState(hello(minor = 14))))
        assertTrue(LyricsFetch.lyrflags(DeviceState(hello(minor = 15))))
        assertTrue(LyricsFetch.lyrflags(DeviceState(hello(minor = 16))))
    }

    @Test
    fun pendingResolvesAfterOneStateIntervalBelowMinor15Only() {
        val ms = Proto.stateIntervalMs.toLong()
        assertNull(LyricsFetch.resolve(null, 1000, 1000 + ms - 1, false))
        assertEquals(false, LyricsFetch.resolve(null, 1000, 1000 + ms, false))
        assertNull(LyricsFetch.resolve(null, 1000, 1000 + ms, true))
        assertNull(LyricsFetch.resolve(null, 1000, 1000 + 100 * ms, true))
        for (lyrflags in listOf(false, true)) {
            assertEquals(true, LyricsFetch.resolve(true, 1000, 1000, lyrflags))
            assertEquals(true, LyricsFetch.resolve(true, 1000, 1000 + ms, lyrflags))
            assertEquals(false, LyricsFetch.resolve(false, 1000, 1000, lyrflags))
        }
    }
}
