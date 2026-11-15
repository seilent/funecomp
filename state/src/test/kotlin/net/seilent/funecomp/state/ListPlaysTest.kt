package net.seilent.funecomp.state

import net.seilent.funecomp.proto.LibRow
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ListPlaysTest {
    private val changes = ArrayList<ListPlays.Tap?>()
    private val lists = ListPlays(100) { changes += it }
    private val ready = Enums.Card.ready
    private val road = "/Playlists/road.m3u8"
    private val tracks = (1L..250L).toList()
    private val np = "nowplaying"
    private val rootKey = LibKey(Enums.View.playlists, "")
    private val roadKey = LibKey(Enums.View.playlists, road)
    private val favKey = LibKey(Enums.View.favorites, "")

    private fun row(id: Long) = LibRow("t$id", 1, id, id.toString())

    private fun root(vararg keys: Pair<String, Long>) = LibList(keys.size.toLong(), keys.withIndex().associate { (i, k) -> i.toLong() to LibRow(k.first, k.second, null, k.first) })

    private fun page(ids: List<Long>, read: LongRange = 0L until ids.size) = LibList(ids.size.toLong(), read.associateWith { row(ids[it.toInt()]) })

    private fun mirror(rev: Long, vararg pages: Pair<LibKey, LibList>) = LibMirror(rev, mapOf(*pages), snap = 1)

    private fun tap(first: Long? = null, start: Long = 0, view: Int = Enums.View.playlists, key: String = road, add: Int? = null) =
        lists.tap(ListPlays.Want(view, key, "road", first, start, rev = 5, add = add), 10L, true, np, 3)

    private fun stale(t: ListPlays.Tap) {
        assertEquals(ListPlays.Step.Stay, lists.replied(t, Err.staleRev, 10L))
        assertSame(t, lists.deciding)
    }

    private fun back(s: ListPlays.Step, gone: String? = null) {
        assertTrue("$s", s is ListPlays.Step.Back)
        assertEquals(gone, (s as ListPlays.Step.Back).gone)
    }

    private fun again(s: ListPlays.Step): ListPlays.Step.Again {
        assertTrue("$s", s is ListPlays.Step.Again)
        return s as ListPlays.Step.Again
    }

    @Test
    fun anOkReplyKeepsNowPlayingAndEndsTheToken() {
        val t = tap()
        assertEquals(ListPlays.Step.Stay, lists.replied(t, null, 10L))
        assertEquals(ListPlays.Step.Stay, lists.replied(t, Err.refused, 10L))
    }

    @Test
    fun eachFailureReturnsOnce() {
        for (code in listOf(Err.badValue, Err.busy, Err.refused, Err.noLibrary, Err.noCard, ListPlays.LOST)) {
            val t = tap()
            back(lists.replied(t, code, 10L))
            assertEquals("$code", ListPlays.Step.Stay, lists.replied(t, code, 10L))
        }
    }

    @Test
    fun aLostReplyAfterTheQueueMovedKeepsNowPlaying() {
        val t = tap()
        assertEquals(ListPlays.Step.Stay, lists.replied(t, ListPlays.LOST, 11L))
        assertEquals(ListPlays.Step.Stay, lists.replied(t, Err.refused, 11L))
    }

    @Test
    fun aNewerTapSupersedesTheOlder() {
        val old = tap()
        val t = tap()
        assertEquals(ListPlays.Step.Stay, lists.replied(old, Err.refused, 10L))
        back(lists.replied(t, Err.refused, 10L))
    }

    @Test
    fun aChangedStackOrASheetEndsTheToken() {
        var t = tap()
        lists.page(np, 3, false)
        back(lists.replied(t, Err.refused, 10L))
        for ((top, depth, sheet) in listOf(Triple("playlists", 2, false), Triple(np, 4, false), Triple(np, 3, true))) {
            t = tap()
            lists.page(top, depth, sheet)
            assertEquals("$top $depth $sheet", ListPlays.Step.Stay, lists.replied(t, Err.refused, 10L))
        }
    }

    @Test
    fun anAddOutlivesADownAndAPageChangeButNotAStop() {
        val t = tap(add = Enums.QueueCmd.add)
        lists.end()
        lists.page("music", 2, true)
        stale(t)
        lists.stop()
        assertNull(lists.deciding)
        assertEquals(ListPlays.Step.Stay, lists.replied(t, null, 10L))
    }

    @Test
    fun anEndSiteEndsTheTokenWithNoMove() {
        val t = tap()
        lists.end()
        assertEquals(ListPlays.Step.Stay, lists.replied(t, Err.refused, 10L))
        val d = tap()
        stale(d)
        lists.end()
        assertNull(lists.deciding)
        assertEquals(ListPlays.Step.Stay, lists.decide(mirror(6, rootKey to root()), ready))
    }

    @Test
    fun theLinkLeavingLiveBeforeTheReplyReturnsOnce() {
        val t = tap()
        assertEquals(ListPlays.Step.Stay, lists.link(true))
        back(lists.link(false))
        assertEquals(ListPlays.Step.Stay, lists.link(false))
        assertEquals(ListPlays.Step.Stay, lists.replied(t, ListPlays.LOST, 10L))
    }

    @Test
    fun theLinkLeavingLiveDuringTheDecisionReturnsOnce() {
        val t = tap()
        stale(t)
        back(lists.link(false))
        assertNull(lists.deciding)
        assertEquals(ListPlays.Step.Stay, lists.link(false))
        assertEquals(ListPlays.Step.Stay, lists.timeout(t))
    }

    @Test
    fun aListGoneAfterAMoveReturnsWithItsName() {
        val t = tap()
        stale(t)
        assertEquals(ListPlays.Step.Stay, lists.decide(mirror(5, rootKey to root("/Playlists/trip.m3u8" to 12)), ready))
        assertEquals(ListPlays.Step.Stay, lists.decide(mirror(6), ready))
        assertEquals(ListPlays.Step.Stay, lists.decide(mirror(6, rootKey to root("/Playlists/trip.m3u8" to 12)), Enums.Card.busy))
        back(lists.decide(mirror(6, rootKey to root("/Playlists/trip.m3u8" to 12)), ready), "road")
        assertNull(lists.deciding)
    }

    @Test
    fun theTrackFoundAtANewIndexIsSentOnceMoreAndKeepsTheToken() {
        val t = tap(tracks[5], 5)
        stale(t)
        val a = again(lists.decide(mirror(6, roadKey to page(tracks.take(12) - tracks[2])), ready))
        assertEquals(4L, a.at)
        assertSame(t, a.tap)
        assertNull(lists.deciding)
        assertEquals(ListPlays.Step.Stay, lists.replied(t, null, 10L))
        assertEquals(ListPlays.Step.Stay, lists.replied(t, Err.refused, 10L))
    }

    @Test
    fun aTrackListedTwiceIsSentAgainAtTheTappedCopy() {
        val t = tap(tracks[100], 40)
        stale(t)
        val list = listOf(tracks[100]) + tracks.take(38) + tracks[100] + tracks.drop(38).take(10)
        assertEquals(39L, again(lists.decide(mirror(6, roadKey to page(list)), ready)).at)
    }

    @Test
    fun aStaleReplyToTheResendReturnsSilently() {
        val t = tap(tracks[5], 5)
        stale(t)
        again(lists.decide(mirror(6, roadKey to page(tracks.take(12))), ready))
        back(lists.replied(t, Err.staleRev, 10L))
        assertNull(lists.deciding)
    }

    @Test
    fun theTrackNotFoundInTheReadBlocksReturnsSilently() {
        val t = tap(tracks[150], 150)
        stale(t)
        val others = tracks - tracks[150]
        assertEquals(ListPlays.Step.Stay, lists.decide(mirror(6, roadKey to page(others, 100L until 200L)), ready))
        back(lists.decide(mirror(6, roadKey to page(others)), ready))
    }

    @Test
    fun theRootIsAskedPastBlockZeroOncePerRev() {
        val t = tap()
        stale(t)
        val keys = (0 until 250).map { "/Playlists/$it.m3u8" to 3L }.toTypedArray()
        val full = root(*keys)
        val first = LibList(250, full.rows.filterKeys { it < 100 })
        assertEquals(emptyList<ListPlays.Ask>(), lists.asks(mirror(5, rootKey to first)))
        assertEquals(listOf(ListPlays.Ask(Enums.View.playlists, "", 100)), lists.asks(mirror(6, rootKey to first)))
        assertEquals(emptyList<ListPlays.Ask>(), lists.asks(mirror(6, rootKey to first)))
        assertEquals(ListPlays.Step.Stay, lists.decide(mirror(6, rootKey to first), ready))
        val two = LibList(250, full.rows.filterKeys { it < 200 })
        assertEquals(listOf(ListPlays.Ask(Enums.View.playlists, "", 200)), lists.asks(mirror(6, rootKey to two)))
        back(lists.decide(mirror(6, rootKey to full), ready), "road")
    }

    @Test
    fun theTappedBlockAndItsNeighboursAreAsked() {
        val t = tap(tracks[150], 150)
        stale(t)
        val list = tracks.reversed()
        assertEquals(listOf(ListPlays.Ask(Enums.View.playlists, road, 0)), lists.asks(mirror(6)))
        assertEquals(listOf(ListPlays.Ask(Enums.View.playlists, road, 100)), lists.asks(mirror(6, roadKey to page(list, 0L until 100L))))
        assertEquals(listOf(ListPlays.Ask(Enums.View.playlists, road, 200)), lists.asks(mirror(6, roadKey to page(list, 0L until 200L))))
        assertEquals(99L, again(lists.decide(mirror(6, roadKey to page(list, 0L until 200L)), ready)).at)
    }

    @Test
    fun aListShrunkBelowTheTappedBlockIsSearchedAtItsEnd() {
        val t = tap(tracks[150], 150)
        stale(t)
        val list = tracks.take(80) + tracks[150] + tracks.drop(200).take(9)
        assertEquals(listOf(ListPlays.Ask(Enums.View.playlists, road, 0)), lists.asks(mirror(6)))
        assertEquals(emptyList<ListPlays.Ask>(), lists.asks(mirror(6, roadKey to page(list))))
        assertEquals(80L, again(lists.decide(mirror(6, roadKey to page(list)), ready)).at)
        val gone = tap(tracks[150], 150)
        stale(gone)
        back(lists.decide(mirror(6, roadKey to page(tracks.take(90))), ready))
    }

    @Test
    fun theBoundWithTheListPresentReturnsSilently() {
        val t = tap(tracks[5], 5)
        stale(t)
        assertEquals(ListPlays.Step.Stay, lists.decide(mirror(5, rootKey to root(road to 12), roadKey to page(tracks.take(12))), ready))
        back(lists.timeout(t))
        assertNull(lists.deciding)
    }

    @Test
    fun withNoStateTheBoundDecides() {
        val t = tap()
        assertEquals(ListPlays.Step.Stay, lists.timeout(t))
        stale(t)
        back(lists.timeout(t))
        assertEquals(ListPlays.Step.Stay, lists.timeout(t))
    }

    @Test
    fun favoritesFindTheTrackInView7() {
        val t = tap(tracks[3], 3, Enums.View.favorites, "")
        stale(t)
        assertEquals(listOf(ListPlays.Ask(Enums.View.favorites, "", 0)), lists.asks(mirror(6)))
        assertEquals(0L, again(lists.decide(mirror(6, favKey to page(listOf(tracks[3]) + tracks.take(3))), ready)).at)
    }

    @Test
    fun aSheetPlayIsSentAgainWithTheRereadCount() {
        val t = tap()
        stale(t)
        assertEquals(ListPlays.Step.Stay, lists.decide(mirror(6, rootKey to LibList(2, mapOf(1L to LibRow("x", 3, null, "x")))), ready))
        val a = again(lists.decide(mirror(6, rootKey to root("x" to 3, road to 9)), ready))
        assertNull(a.at)
        assertEquals(9L, a.count)
        val f = tap(view = Enums.View.favorites, key = "")
        stale(f)
        assertEquals(listOf(ListPlays.Ask(Enums.View.favorites, "", 0)), lists.asks(mirror(6)))
        assertEquals(4L, again(lists.decide(mirror(6, favKey to page(tracks.take(4))), ready)).count)
    }

    @Test
    fun aSheetPlayOfAListNowEmptyReturnsSilently() {
        val t = tap()
        stale(t)
        back(lists.decide(mirror(6, rootKey to root(road to 0)), ready))
    }

    @Test
    fun aListAnsweringStaleToEveryPlayIsSentTwiceAtMost() {
        val t = tap(tracks[5], 5)
        var plays = 1
        var rev = 6L
        while (plays < 5) {
            lists.replied(t, Err.staleRev, 10L)
            val s = lists.decide(mirror(rev++, roadKey to page(tracks.take(12))), ready)
            if (s !is ListPlays.Step.Again) break
            plays++
        }
        assertEquals(2, plays)
    }

    @Test
    fun anAddTakesTheSameRuleWithNoReturn() {
        val gone = tap(add = Enums.QueueCmd.add)
        stale(gone)
        back(lists.decide(mirror(6, rootKey to root()), ready), "road")
        val present = tap(add = Enums.QueueCmd.playNext)
        stale(present)
        assertNull(again(lists.decide(mirror(6, rootKey to root(road to 12)), ready)).at)
        back(lists.replied(present, Err.staleRev, 10L))
        val lost = tap(add = Enums.QueueCmd.add)
        back(lists.replied(lost, ListPlays.LOST, 11L))
    }

    @Test
    fun theDecidingTokenIsReported() {
        val t = tap(tracks[5], 5)
        stale(t)
        again(lists.decide(mirror(6, roadKey to page(tracks.take(12))), ready))
        assertEquals(listOf<ListPlays.Tap?>(t, null), changes)
    }
}
