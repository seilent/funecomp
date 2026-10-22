package net.seilent.funecomp.state

import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.LyrLine
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.state.OffsetSend.Reply
import net.seilent.funecomp.state.OffsetSend.Send
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OffsetSendTest {
    private fun opened(stored: Int = 500, shown: Int = stored): OffsetSend {
        val o = OffsetSend(7, shown)
        assertEquals(Send(null), o.open().send)
        assertNull(o.replied(Reply.Ok(stored)).send)
        return o
    }

    @Test
    fun oneInFlightAndTheLastValueWins() {
        val o = opened()
        o.begin()
        assertEquals(Send(600), o.step(1).send)
        assertNull(o.step(1).send)
        assertNull(o.step(1).send)
        assertEquals(800, o.shown)
        val r = o.replied(Reply.Ok(600))
        assertEquals(Send(800), r.send)
        assertTrue(r.held)
        assertNull(o.replied(Reply.Ok(800)).send)
        assertEquals(800, o.stored)
        assertEquals(800, o.shown)
        assertTrue(o.editing)
    }

    @Test
    fun aPendingEqualToTheStoredValueIsDropped() {
        val o = opened()
        o.begin()
        assertEquals(Send(600), o.step(1).send)
        o.step(-1)
        assertEquals(Send(500), o.replied(Reply.Ok(600)).send)
        o.step(1)
        assertEquals(Send(600), o.replied(Reply.Ok(500)).send)
        o.step(-1)
        o.step(1)
        assertNull(o.replied(Reply.Ok(600)).send)
    }

    @Test
    fun stepsAndCoarseStopAtTheEnds() {
        val o = opened(59_500)
        o.begin()
        assertEquals(Send(60_000), o.coarse(1).send)
        o.replied(Reply.Ok(60_000))
        assertNull(o.step(1).send)
        assertNull(o.coarse(1).send)
        assertEquals(Send(59_000), o.coarse(-1).send)
        o.replied(Reply.Ok(59_000))
        assertEquals(Send(58_900), o.step(-1).send)
        val low = opened(-59_950)
        low.begin()
        assertEquals(Send(-60_000), low.step(-1).send)
        low.replied(Reply.Ok(-60_000))
        assertNull(low.coarse(-1).send)
        assertNull(opened().step(1).send)
    }

    @Test
    fun restoreSendsTheStartAndNothingWhenStoredEqualsIt() {
        val o = opened()
        o.begin()
        o.step(1)
        o.step(1)
        assertNull(o.restore().send)
        assertEquals(500, o.shown)
        assertFalse(o.editing)
        assertEquals(Send(500), o.replied(Reply.Ok(600)).send)
        assertNull(o.replied(Reply.Ok(500)).send)
        o.begin()
        assertNull(o.restore().send)
        val k = opened()
        k.begin()
        k.step(1)
        k.replied(Reply.Ok(600))
        k.keep()
        assertFalse(k.editing)
        assertNull(k.restore().send)
        assertEquals(600, k.shown)
    }

    @Test
    fun anErrorKeepsTheStoredValueAndNamesTheCode() {
        for (code in listOf(Err.unknown, Err.badValue, Err.refused, Err.staleRev, Err.noLibrary, Err.busy, 99)) {
            val o = opened()
            o.begin()
            o.step(1)
            o.step(1)
            val r = o.replied(Reply.Failed(code))
            assertEquals(code, r.toast)
            assertNull(r.send)
            assertEquals(500, o.shown)
            assertEquals(500, o.stored)
        }
        assertEquals("not supported", OffsetSend.toast(Err.unknown))
        assertEquals("-60000 to +60000 ms", OffsetSend.toast(Err.badValue))
        assertEquals("refused", OffsetSend.toast(Err.refused))
        assertEquals("song not in library", OffsetSend.toast(Err.staleRev))
        assertEquals("card not available", OffsetSend.toast(Err.noLibrary))
        assertEquals("error 3", OffsetSend.toast(Err.busy))
        assertEquals("error 99", OffsetSend.toast(99))
        assertEquals("dap did not answer", OffsetSend.toast(OffsetSend.LOST))
        assertTrue(OffsetSend.closes(Err.staleRev))
        assertTrue(OffsetSend.closes(Err.noLibrary))
        assertFalse(OffsetSend.closes(Err.busy))
    }

    @Test
    fun aTimeoutDropsPendingAndReadsOnceWithStepsBehindTheRead() {
        val o = opened()
        o.begin()
        o.step(1)
        o.step(1)
        val r = o.replied(Reply.Lost)
        assertEquals(OffsetSend.LOST, r.toast)
        assertEquals(Send(null), r.send)
        assertNull(o.stored)
        assertEquals(700, o.shown)
        assertNull(o.step(1).send)
        assertEquals(Send(800), o.replied(Reply.Ok(600)).send)
        assertEquals(800, o.shown)
        val gone = opened()
        gone.begin()
        gone.step(1)
        assertEquals(Send(null), gone.replied(Reply.Lost).send)
        val again = gone.replied(Reply.Lost)
        assertNull(again.send)
        assertNull(again.toast)
    }

    @Test
    fun beginWhileTheOpenReadIsInFlightTakesItsReplyAsStart() {
        val o = OffsetSend(7, 300)
        o.open()
        o.begin()
        assertNull(o.step(1).send)
        assertEquals(400, o.shown)
        assertEquals(Send(400), o.replied(Reply.Ok(300)).send)
        o.replied(Reply.Ok(400))
        assertEquals(Send(300), o.restore().send)
        val r = OffsetSend(7, 300)
        r.open()
        r.begin()
        r.step(1)
        assertNull(r.restore().send)
        assertNull(r.replied(Reply.Ok(300)).send)
        assertEquals(300, r.shown)
    }

    @Test
    fun beginBehindAKeptBurstTakesTheQueuedValueAsStart() {
        val o = opened()
        o.begin()
        assertEquals(Send(600), o.step(1).send)
        o.step(1)
        o.step(1)
        o.keep()
        o.begin()
        o.step(1)
        assertEquals(900, o.shown)
        assertEquals(Send(900), o.replied(Reply.Ok(600)).send)
        assertNull(o.replied(Reply.Ok(900)).send)
        assertEquals(Send(800), o.restore().send)
        assertEquals(800, o.shown)
        val f = opened()
        f.begin()
        f.step(1)
        f.step(1)
        f.keep()
        f.begin()
        assertEquals(Err.refused, f.replied(Reply.Failed(Err.refused)).toast)
        assertEquals(500, f.shown)
        assertEquals(Send(600), f.step(1).send)
        assertNull(f.restore().send)
        assertEquals(500, f.shown)
        assertEquals(Send(500), f.replied(Reply.Ok(600)).send)
    }

    @Test
    fun aBeginAfterARestoreOwedItsStartShowsOnlySentValues() {
        val o = opened()
        o.begin()
        assertEquals(Send(600), o.step(1).send)
        o.keep()
        o.begin()
        assertNull(o.step(1).send)
        assertEquals(700, o.shown)
        assertNull(o.restore().send)
        assertEquals(600, o.shown)
        o.begin()
        assertEquals(600, o.shown)
        assertNull(o.replied(Reply.Ok(600)).send)
        assertEquals(600, o.shown)
        assertTrue(o.editing)
        assertEquals(Send(700), o.step(1).send)
        assertNull(o.replied(Reply.Ok(700)).send)
        assertEquals(Send(600), o.restore().send)
        val r = OffsetSend(7, 300)
        r.open()
        r.begin()
        r.step(1)
        assertNull(r.restore().send)
        assertEquals(300, r.shown)
        r.begin()
        assertNull(r.replied(Reply.Ok(300)).send)
        assertEquals(300, r.shown)
        assertTrue(r.editing)
    }

    @Test
    fun aLostFlightABeginWaitedBehindReadsTheStart() {
        val o = opened()
        o.begin()
        assertEquals(Send(600), o.step(1).send)
        o.step(1)
        o.keep()
        o.begin()
        assertEquals(700, o.shown)
        val lost = o.replied(Reply.Lost)
        assertEquals(OffsetSend.LOST, lost.toast)
        assertEquals(Send(null), lost.send)
        assertNull(o.stored)
        assertNull(o.replied(Reply.Ok(650)).send)
        assertEquals(650, o.shown)
        assertTrue(o.editing)
        assertEquals(Send(750), o.step(1).send)
        assertNull(o.replied(Reply.Ok(750)).send)
        assertEquals(Send(650), o.restore().send)
        assertEquals(650, o.shown)
        val r = opened()
        r.begin()
        r.step(1)
        r.step(1)
        r.keep()
        r.begin()
        r.replied(Reply.Lost)
        assertNull(r.restore().send)
        assertEquals(600, r.shown)
        assertNull(r.replied(Reply.Ok(650)).send)
        assertEquals(650, r.shown)
        assertFalse(r.editing)
    }

    @Test
    fun aStateMarkedWhileARequestWasInFlightIsIgnored() {
        val o = opened(0)
        o.begin()
        assertEquals(Send(100), o.step(1).send)
        o.step(1)
        assertEquals(Send(200), o.replied(Reply.Ok(100)).send)
        o.step(1)
        val inFlight = o.mark()
        assertEquals(Send(300), o.replied(Reply.Ok(200)).send)
        assertFalse(o.observed(200, inFlight))
        val between = o.mark()
        assertFalse(between.idle)
        assertNull(o.replied(Reply.Ok(300)).send)
        assertFalse(o.observed(200, inFlight))
        assertEquals(300, o.shown)
        assertTrue(o.editing)
        val after = o.mark()
        assertTrue(after.idle)
        assertFalse(o.observed(300, after))
        assertEquals(300, o.shown)
        assertTrue(o.editing)
    }

    @Test
    fun aLateLyricsOutcomeReachesNoOffsetInput() {
        val o = opened(0)
        o.begin()
        o.step(1)
        o.step(1)
        o.replied(Reply.Ok(100))
        o.step(1)
        o.replied(Reply.Ok(200))
        o.replied(Reply.Ok(300))
        val f = LyricsFetch()
        val d = DeviceState(HelloReply(1, 15, "v1", "t", 240, 320, 240, 320, 16384, 30_000, 5_000, Groups.all, 1, 1, 4))
            .withState(State(track = 7, lyrics = true, lyricsPlain = false, lyricsCrc = 9, snapRev = 4), 0)
        val w = f.decide(d, 0).want!!
        f.done(w, LyricsFetch.Got.Lines(LyricsFetch.Copy(9, Enums.LyricsKind.synced, List(3) { LyrLine(it * 1000L, "p$it") })), 0)
        assertEquals(300, o.shown)
        assertEquals(300, o.stored)
        assertTrue(o.editing)
        assertTrue(o.mark().idle)
    }

    @Test
    fun aStateMarkedIdleThenASendIsIgnored() {
        val o = opened(0)
        o.begin()
        o.step(1)
        o.step(1)
        o.replied(Reply.Ok(100))
        o.replied(Reply.Ok(200))
        val idle = o.mark()
        assertTrue(idle.idle)
        assertEquals(Send(300), o.step(1).send)
        assertFalse(o.observed(200, idle))
        assertEquals(300, o.shown)
    }

    @Test
    fun aDraftFromTheDapShowsAndEndsTheEditWithoutASend() {
        val o = opened()
        o.begin()
        o.step(1)
        o.replied(Reply.Ok(600))
        assertTrue(o.observed(1200, o.mark()))
        assertEquals(1200, o.shown)
        assertEquals(600, o.stored)
        assertFalse(o.editing)
        assertNull(o.restore().send)
        assertTrue(o.observed(600, o.mark()))
        assertEquals(600, o.shown)
        assertFalse(o.observed(600, o.mark()))
    }

    @Test
    fun beginAfterADapDraftReadsFirstAndRestoresToTheRead() {
        val o = opened()
        assertTrue(o.observed(900, o.mark()))
        assertEquals(500, o.stored)
        assertEquals(Send(null), o.begin().send)
        assertNull(o.step(1).send)
        assertEquals(1000, o.shown)
        assertEquals(Send(1000), o.replied(Reply.Ok(500)).send)
        o.replied(Reply.Ok(1000))
        assertEquals(Send(500), o.restore().send)
        assertEquals(500, o.shown)
    }

    @Test
    fun aReplacementEndsTheEditAndReadsWithoutARestore() {
        val o = opened(0)
        o.begin()
        o.step(1)
        o.replied(Reply.Ok(100))
        assertEquals(Send(200), o.step(1).send)
        o.step(1)
        assertNull(o.replaced(7).send)
        assertFalse(o.editing)
        assertEquals(Send(null), o.replied(Reply.Ok(200)).send)
        assertNull(o.replied(Reply.Ok(0)).send)
        assertEquals(0, o.shown)
        assertEquals(0, o.stored)
        assertNull(o.close(true).send)
        val other = opened()
        other.begin()
        other.step(1)
        assertNull(other.replaced(8).send)
        assertTrue(other.editing)
    }

    @Test
    fun closersRestoreToTheOwnTrackAndASessionEndSendsNothing() {
        val o = opened()
        o.begin()
        o.step(1)
        o.replied(Reply.Ok(600))
        o.step(1)
        o.step(1)
        assertNull(o.close(true).send)
        assertTrue(o.closed)
        assertEquals(Send(500), o.replied(Reply.Ok(700)).send)
        assertNull(o.replied(Reply.Ok(500)).send)
        assertNull(o.step(1).send)
        assertNull(o.typed(100).send)
        val same = opened()
        same.begin()
        assertNull(same.close(true).send)
        val down = opened()
        down.begin()
        down.step(1)
        assertNull(down.close(false).send)
        assertNull(down.replied(Reply.Lost).send)
        assertNull(down.replied(Reply.Ok(600)).send)
    }

    @Test
    fun typedEndsTheEditAndSends() {
        val o = opened()
        o.begin()
        o.step(1)
        o.step(1)
        assertNull(o.typed(-1200).send)
        assertFalse(o.editing)
        assertEquals(-1200, o.shown)
        assertEquals(Send(-1200), o.replied(Reply.Ok(600)).send)
        assertNull(opened().typed(500).send)
    }

    @Test
    fun parseTakesTheKeyboardLimits() {
        assertEquals(500, OffsetSend.parse("500"))
        assertEquals(-1200, OffsetSend.parse("-1200"))
        assertEquals(0, OffsetSend.parse("0"))
        assertEquals(-60000, OffsetSend.parse("-60000"))
        assertEquals(60000, OffsetSend.parse("60000"))
        for (t in listOf("", "-", " 500", "500 ", "+500", "5-00", "70000", "-60001", "1234567", "-123456", "1.5", "ab")) assertNull(t, OffsetSend.parse(t))
        assertEquals("+500 ms", OffsetSend.text(500))
        assertEquals("0 ms", OffsetSend.text(0))
        assertEquals("-1200 ms", OffsetSend.text(-1200))
    }
}
