package net.seilent.funecomp.state

import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Err
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartTest {
    private val a = State(track = 1, title = "One", favorite = false)
    private val b = State(track = 2, title = "Two", favorite = true)

    private fun hello(snapRev: Long?) = HelloReply(1, 3, "v1", null, null, null, null, null, 16384, 0, 0, 0, 7, 1, snapRev)

    @Test
    fun shownNeedsTheSnapRevAndATrack() {
        assertTrue(Heart.shown(hello(4), a))
        assertTrue(Heart.shown(hello(4), a.copy(favorite = null)))
        assertFalse(Heart.shown(hello(null), a))
        assertFalse(Heart.shown(hello(4), State()))
        assertFalse(Heart.shown(null, a))
        assertTrue(Heart.lit(b))
        assertFalse(Heart.lit(a))
        assertFalse(Heart.lit(a.copy(favorite = null)))
    }

    @Test
    fun theStateAfterTheReplyPicksTheToast() {
        val h = Heart()
        val t = h.tap(a)!!
        assertEquals(1L, t.track)
        assertNull(h.tap(a))
        assertNull(h.state(a))
        assertNull(h.replied(t, null, null, 0))
        assertNull(h.tap(a))
        assertEquals(Heart.Toast(Heart.ADDED, "One"), h.state(a.copy(favorite = true)))
        assertEquals(1L, h.tap(a.copy(favorite = true))?.track)
    }

    @Test
    fun aDifferingStateBeforeTheReplyIsTheResult() {
        val h = Heart()
        val t = h.tap(a)!!
        assertNull(h.state(a.copy(favorite = true)))
        assertEquals(Heart.Toast(Heart.ADDED, "One"), h.replied(t, null, null, 0))
    }

    @Test
    fun anotherTrackFirstTakesTheFlippedValue() {
        val h = Heart()
        val t = h.tap(a)!!
        assertNull(h.state(b))
        h.replied(t, null, null, 0)
        assertEquals(Heart.Toast(Heart.ADDED, "One"), h.state(b))
    }

    @Test
    fun theWindowEndsThePendingTap() {
        val h = Heart()
        val t = h.tap(a)!!
        h.replied(t, null, null, 100)
        assertNull(h.state(a.copy(favorite = null)))
        assertNull(h.tick(t, 100 + Heart.WINDOW_MS - 1))
        assertEquals(Heart.Toast(Heart.ADDED, "One"), h.tick(t, 100 + Heart.WINDOW_MS))
        val u = Heart()
        val v = u.tap(a.copy(favorite = null))!!
        u.replied(v, null, null, 0)
        assertNull(u.tick(v, Heart.WINDOW_MS))
        assertEquals(1L, u.tap(a)?.track)
    }

    @Test
    fun errorsClearThePendingTap() {
        val h = Heart()
        assertEquals(Heart.Toast(Heart.UNREADABLE, ""), h.replied(h.tap(a)!!, Err.busy, Heart.UNREADABLE, 0))
        assertEquals(Heart.Toast(Heart.CARD, ""), h.replied(h.tap(a)!!, Err.busy, "busy", 0))
        assertEquals(Heart.Toast(Heart.CARD, ""), h.replied(h.tap(a)!!, Err.busy, null, 0))
        for (code in listOf(Err.staleRev, Err.noLibrary, Err.badValue, -1)) assertNull(h.replied(h.tap(a)!!, code, null, 0))
        h.tap(a)
        h.down()
        assertEquals(1L, h.tap(a)?.track)
    }

    @Test
    fun aLateReplyOfAnOldTapIsDropped() {
        val h = Heart()
        val old = h.tap(a)!!
        h.down()
        val t = h.tap(a)!!
        assertNull(h.replied(old, -1, null, 0))
        assertNull(h.tap(a))
        assertNull(h.replied(t, null, null, 0))
        assertNull(h.tick(old, Heart.WINDOW_MS))
        assertNull(h.tap(a))
        assertEquals(Heart.Toast(Heart.ADDED, "One"), h.state(a.copy(favorite = true)))
    }

    @Test
    fun a14ToastsTheCardLineAtAnyMinorAndAt15ABusyToastsOnlyTheUnreadableText() {
        for (minor in listOf(0, 14, 15, 16)) assertEquals("$minor", Heart.Toast(Heart.CARD, ""), Heart().let { h -> h.replied(h.tap(a)!!, Err.noCard, null, 0, minor) })
        for (minor in listOf(15, 16)) {
            assertEquals(Heart.Toast(Heart.UNREADABLE, ""), Heart().let { h -> h.replied(h.tap(a)!!, Err.busy, Heart.UNREADABLE, 0, minor) })
            for (text in listOf(Heart.CARD, "busy", null)) assertNull("$minor $text", Heart().let { h -> h.replied(h.tap(a)!!, Err.busy, text, 0, minor) })
        }
        for (text in listOf(Heart.CARD, "busy", null)) assertEquals(Heart.Toast(Heart.CARD, ""), Heart().let { h -> h.replied(h.tap(a)!!, Err.busy, text, 0, 14) })
        assertNull(Heart().let { h -> h.replied(h.tap(a)!!, Err.staleRev, null, 0, 15) })
    }

    @Test
    fun theQueueAddCardToastIs14AtAnyMinorAndBusyBelow15() {
        assertTrue(Heart.cardToast(Err.noCard, 15))
        assertTrue(Heart.cardToast(Err.noCard, 14))
        assertTrue(Heart.cardToast(Err.busy, 14))
        assertFalse(Heart.cardToast(Err.busy, 15))
        assertFalse(Heart.cardToast(Err.staleRev, 14))
        assertFalse(Heart.cardToast(null, 14))
    }
}
