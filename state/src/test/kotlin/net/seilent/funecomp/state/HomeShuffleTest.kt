package net.seilent.funecomp.state

import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Err
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeShuffleTest {
    private val h = HomeShuffle()

    private fun state(rev: Long, playing: Boolean = true, shuffle: Boolean = true) = State(queueRev = rev, playing = playing, shuffle = shuffle)

    @Test
    fun anOkReplyThenTheLandedStateOpens() {
        val t = h.tap(5)
        assertFalse(h.replied(t, null))
        assertTrue(h.state(state(6)))
    }

    @Test
    fun theLandedStateThenAnOkReplyOpensOnTheReply() {
        val t = h.tap(5)
        assertFalse(h.state(state(6)))
        assertTrue(h.replied(t, null))
    }

    @Test
    fun aSameRevPlayingShuffledStateDoesNotOpen() {
        val t = h.tap(5)
        assertFalse(h.replied(t, null))
        assertFalse(h.state(state(5)))
    }

    @Test
    fun aNewRevPausedEndsAndALaterPlayingStateDoesNotOpen() {
        val t = h.tap(5)
        assertFalse(h.replied(t, null))
        assertFalse(h.state(state(6, playing = false)))
        assertFalse(h.state(state(7)))
    }

    @Test
    fun aNewRevUnshuffledEnds() {
        val t = h.tap(5)
        assertFalse(h.replied(t, null))
        assertFalse(h.state(state(6, shuffle = false)))
        assertFalse(h.state(state(7)))
    }

    @Test
    fun aCodedReplyEnds() {
        val t = h.tap(5)
        assertFalse(h.replied(t, Err.refused))
        assertFalse(h.state(state(6)))
        assertFalse(h.replied(t, null))
    }

    @Test
    fun endEnds() {
        val t = h.tap(5)
        assertFalse(h.replied(t, null))
        h.end()
        assertFalse(h.state(state(6)))
    }

    @Test
    fun opensOnce() {
        val t = h.tap(5)
        assertFalse(h.replied(t, null))
        assertTrue(h.state(state(6)))
        assertFalse(h.replied(t, null))
        assertFalse(h.state(state(7)))
    }

    @Test
    fun notArmedNeverOpens() {
        assertFalse(h.state(state(6)))
        assertFalse(h.replied(HomeShuffle.Tap(5), null))
    }

    @Test
    fun aStaleReplyOfAFirstTapNeitherOpensNorEndsASecondTap() {
        val first = h.tap(5)
        val second = h.tap(5)
        assertFalse(h.replied(first, null))
        assertFalse(h.state(state(6)))
        assertFalse(h.replied(first, Err.refused))
        assertTrue(h.replied(second, null))
    }
}
