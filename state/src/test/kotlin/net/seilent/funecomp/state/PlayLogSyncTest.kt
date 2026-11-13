package net.seilent.funecomp.state

import net.seilent.funecomp.proto.PlayEntry
import net.seilent.funecomp.proto.PlayLogReply
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayLogSyncTest {
    private val cursors = HashMap<Long, Long>()
    private val logs = ArrayList<String>()
    private val sync = PlayLogSync({ cursors[it] }) { logs += it }

    private val a = 0xA1L
    private val b = 0xB2L

    private fun page(log: Long, seqs: LongRange, more: Boolean = false, first: Long? = seqs.firstOrNull()) =
        PlayLogReply(log, seqs.lastOrNull(), more, seqs.map { PlayEntry(it, 1_800_000_000L + it) }, first)

    private fun get(d: PlayLogSync.Do?) = d as? PlayLogSync.Get ?: throw AssertionError("not a get: $d")

    private fun store(d: PlayLogSync.Do?) = d as? PlayLogSync.Store ?: throw AssertionError("not a store: $d")

    private fun ack(d: PlayLogSync.Do?) = d as? PlayLogSync.Ack ?: throw AssertionError("not an ack: $d")

    private fun stored(s: PlayLogSync.Store, upTo: Long? = s.entries.maxOfOrNull { it.seq }, p: PlayLogSync = sync): PlayLogSync.Do? {
        if (upTo != null) cursors[s.log] = maxOf(cursors[s.log] ?: 0, upTo)
        return p.stored(s.gen, s.log, upTo, s.more)
    }

    private fun cycle(r: PlayLogReply, from: PlayLogSync.Get): PlayLogSync.Do? {
        val s = store(sync.reply(from.gen, r))
        val k = ack(stored(s))
        return sync.acked(k.gen, k, null, true)
    }

    @Test
    fun noGetWithoutBit23OrWithNoService() {
        assertNull(sync.hello(false, true))
        assertNull(sync.state(9))
        assertNull(sync.hello(true, false))
        assertNull(sync.state(9))
        assertTrue(sync.logged)
    }

    @Test
    fun theFirstGetIsAtHelloWithAfterZeroInEveryMode() {
        cursors[a] = 5
        val g = get(sync.hello(true, true))
        assertEquals(0L, g.after)
        assertEquals(PlayLogSync.PAGE, g.count)
        assertTrue(sync.logged)
    }

    @Test
    fun stateAboveTheCursorGetsOnceWithTheCursorAndEqualDoesNot() {
        val g = get(sync.hello(true, true))
        assertNull(cycle(page(a, 1L..3L), g))
        assertNull(sync.state(3))
        val n = get(sync.state(4))
        assertEquals(3L, n.after)
        assertNull(sync.state(5))
    }

    @Test
    fun stateBeforeAnyReplyWaits() {
        sync.hello(true, true)
        assertNull(sync.state(4))
    }

    @Test
    fun morePagesUntilDoneWithOneRequestInFlight() {
        val g = get(sync.hello(true, true))
        val s = store(sync.reply(g.gen, page(a, 1L..50L, more = true)))
        assertNull(sync.state(120))
        assertNull(sync.services(true, true))
        val k = ack(stored(s))
        assertEquals(50L, k.upTo)
        val g2 = get(sync.acked(k.gen, k, null, true))
        assertEquals(50L, g2.after)
        assertNull(cycle(page(a, 51L..120L), g2))
    }

    @Test
    fun theAckWaitsForTheStore() {
        val g = get(sync.hello(true, true))
        assertTrue(sync.reply(g.gen, page(a, 1L..2L)) is PlayLogSync.Store)
        assertTrue(cursors.isEmpty())
    }

    @Test
    fun aReReadAfterALostAckStillAcksThePage() {
        cursors[a] = 30
        val g = get(sync.hello(true, true))
        val s = store(sync.reply(g.gen, page(a, 1L..30L)))
        val k = ack(sync.stored(s.gen, s.log, 30, false))
        assertEquals(30L, k.upTo)
    }

    @Test
    fun busyAndRefusedOnGetOrAckLogOnceAndWaitForTheNextTrigger() {
        for (code in listOf(Err.busy, Err.refused)) {
            val g = get(sync.hello(true, true))
            logs.clear()
            sync.getFailed(g.gen, code)
            assertEquals(1, logs.size)
            val g2 = get(sync.services(true, true))
            val s = store(sync.reply(g2.gen, page(a, 1L..2L)))
            val k = ack(stored(s))
            logs.clear()
            assertNull(sync.acked(k.gen, k, code, true))
            assertEquals(1, logs.size)
            assertNull(sync.state(2))
            cursors.clear()
        }
    }

    @Test
    fun aNewLogIdIsReadFromTheStart() {
        cursors[a] = 5
        val g = get(sync.hello(true, true))
        assertEquals(0L, g.after)
        val s = store(sync.reply(g.gen, page(b, 1L..30L)))
        assertEquals(30, s.entries.size)
        assertEquals(30L, ack(stored(s)).upTo)
    }

    @Test
    fun aReplyForAnotherLogThanTheCursorIsNotStoredAndGetsAgainFromZero() {
        val g = get(sync.hello(true, true))
        assertNull(cycle(page(a, 1L..5L), g))
        val n = get(sync.state(8))
        assertEquals(5L, n.after)
        val again = get(sync.reply(n.gen, page(b, 1L..3L)))
        assertEquals(0L, again.after)
        val s = store(sync.reply(again.gen, page(b, 1L..3L)))
        assertEquals(b, s.log)
    }

    @Test
    fun ackSevenGetsAgainFromZero() {
        val g = get(sync.hello(true, true))
        assertNull(cycle(page(a, 1L..5L), g))
        val n = get(sync.state(8))
        val s = store(sync.reply(n.gen, page(a, 6L..8L)))
        val k = ack(stored(s))
        assertEquals(0L, get(sync.acked(k.gen, k, Err.staleRev, true)).after)
    }

    @Test
    fun ackTwoLogsAndWaits() {
        val g = get(sync.hello(true, true))
        val k = ack(stored(store(sync.reply(g.gen, page(a, 1L..2L)))))
        assertNull(sync.acked(k.gen, k, Err.badValue, true))
        assertTrue(logs.any { "error 2" in it })
    }

    @Test
    fun aGapAtFirstLogsOnce() {
        val g = get(sync.hello(true, true))
        assertNull(cycle(page(a, 1L..5L), g))
        val n = get(sync.state(20))
        assertNull(cycle(page(a, 11L..20L), n))
        assertEquals(listOf("playlog: 5 plays dropped on the DAP"), logs)
        sync.hello(true, true)
        cursors[a] = 5
        val m = get(sync.hello(true, true))
        sync.reply(m.gen, page(a, 11L..20L))
        assertEquals(1, logs.count { "dropped" in it })
    }

    @Test
    fun aStoreOfAnOlderSessionSendsNoAck() {
        val g = get(sync.hello(true, true))
        val s = store(sync.reply(g.gen, page(a, 1L..2L)))
        sync.closed()
        assertNull(stored(s))
        val g2 = get(sync.hello(true, true))
        assertNull(sync.stored(g.gen, a, 2, false))
        assertTrue(g2.gen > g.gen)
    }

    @Test
    fun turningAServiceOnGets() {
        assertNull(sync.hello(true, false))
        assertEquals(0L, get(sync.services(true, true)).after)
    }

    @Test
    fun aFailedStoreSendsNoAckAndFreesTheSync() {
        val g = get(sync.hello(true, true))
        val s = store(sync.reply(g.gen, page(a, 1L..2L)))
        assertNull(sync.stored(s.gen, s.log, null, false))
        assertTrue(sync.services(true, true) is PlayLogSync.Get)
    }

    @Test
    fun aTimeoutAcksNothingAndTheNextSessionReadsAgain() {
        val g = get(sync.hello(true, true))
        val k = ack(stored(store(sync.reply(g.gen, page(a, 1L..2L)))))
        assertNull(sync.acked(k.gen, k, null, false))
        assertEquals(0L, get(sync.hello(true, true)).after)
    }

    @Test
    fun theLiveSwitchStaysFromOneHelloToTheNext() {
        sync.hello(true, true)
        sync.closed()
        assertTrue(sync.logged)
        sync.hello(false, true)
        assertFalse(sync.logged)
    }

    @Test
    fun anEmptyReplyUnderANewerStateWaitsForAHigherSeq() {
        val g = get(sync.hello(true, true))
        assertNull(cycle(page(a, 1L..3L), g))
        val n = get(sync.state(5))
        assertNull(sync.reply(n.gen, page(a, LongRange.EMPTY, first = null)))
        assertNull(sync.state(5))
        assertEquals(3L, get(sync.state(6)).after)
    }

    private fun card(vararg v: Int) = v.fold(CardView()) { c, x -> c.with(x) }

    private val ready1 = card(Enums.Card.ready)
    private val busy1 = card(Enums.Card.ready, Enums.Card.busy)
    private val ready2 = card(Enums.Card.ready, Enums.Card.busy, Enums.Card.ready)

    @Test
    fun aGetRefusedForTheCardAtHelloIsAskedAgainOnlyAtTheReadyEdge() {
        val g = get(sync.hello(true, true))
        sync.getFailed(g.gen, Err.noCard, 1)
        assertNull(sync.state(3, ready1))
        assertNull(sync.state(3, busy1))
        assertNull(sync.state(3, ready1))
        val again = get(sync.state(3, ready2))
        assertEquals(0L, again.after)
        assertNull(sync.state(3, ready2))
    }

    @Test
    fun aCardRefusalHandledAfterTheEdgeIsAskedAtTheNextState() {
        val g = get(sync.hello(true, true))
        sync.getFailed(g.gen, Err.noCard, 0)
        get(sync.state(3, ready1))
    }

    @Test
    fun anyOtherGetFailureIsAskedAgainOnTheNextStateOnce() {
        for (code in listOf(Err.busy, null)) {
            val g = get(sync.hello(true, true))
            sync.getFailed(g.gen, code, 1)
            val again = get(sync.state(null, ready1))
            assertNull(sync.state(null, ready1))
            sync.getFailed(again.gen, code, 1)
            get(sync.state(null, ready1))
        }
    }

    @Test
    fun noGetGoesOutWhileTheCardIsDown() {
        val g = get(sync.hello(true, true))
        assertNull(cycle(page(a, 1L..3L), g))
        assertNull(sync.state(4, busy1))
        val n = get(sync.state(4, ready2))
        assertEquals(3L, n.after)
        sync.getFailed(n.gen, Err.busy, 2)
        assertNull(sync.state(4, busy1))
    }

    @Test
    fun aGetThatFailsAfterAGoodFirstIsAskedAgain() {
        val g = get(sync.hello(true, true))
        assertNull(cycle(page(a, 1L..3L), g))
        val n = get(sync.state(4, ready1))
        sync.getFailed(n.gen, Err.noCard, 1)
        assertNull(sync.state(5, ready1))
        assertEquals(3L, get(sync.state(5, ready2)).after)
        val m = PlayLogSync({ cursors[it] }) { logs += it }
        val h = get(m.hello(true, true))
        assertNull(m.reply(h.gen, page(a, 4L..3L)))
        val k = get(m.state(5, ready1))
        m.getFailed(k.gen, Err.busy, 1)
        assertEquals(3L, get(m.state(5, ready1)).after)
    }

    private val cards = PlayLogSync({ cursors[it] }, true) { logs += it }

    private fun cardPage(l: Long, seqs: LongRange, newest: Long? = seqs.lastOrNull() ?: 0, held: Long? = seqs.firstOrNull(), first: Long? = null) =
        PlayLogReply(l, newest, false, seqs.map { PlayEntry(it, 1_800_000_000L + it) }, first, held)

    private fun cardCycle(r: PlayLogReply, from: PlayLogSync.Get): PlayLogSync.Do? {
        val s = store(cards.reply(from.gen, r))
        val k = ack(stored(s, p = cards))
        return cards.acked(k.gen, k, null, true)
    }

    @Test
    fun theCardLogIsReadFromZeroAtHelloAndNotWithoutTheDeviceBit() {
        cursors[a] = 5
        assertEquals(0L, get(cards.hello(true, true)).after)
        assertTrue(cards.card)
        assertNull(cards.hello(false, true))
        assertNull(cards.state(9, ready1))
        assertNull(cards.hello(true, false))
        assertNull(cards.state(9, ready1))
    }

    @Test
    fun aNewCardLogSeqGetsEvenBelowTheCursorAndAnEqualZeroOrAbsentValueDoesNot() {
        val g = get(cards.hello(true, true))
        assertNull(cardCycle(cardPage(a, 1L..5L), g))
        assertNull(cards.state(5, ready1))
        assertNull(cards.state(null, ready1))
        assertNull(cards.state(0, ready1))
        get(cards.state(3, ready1))
        assertNull(cards.state(4, ready1))
    }

    @Test
    fun aChangeWhileBusyIsAskedAtTheCycleEnd() {
        val ok = PlayLogSync({ cursors[it] }, true) { logs += it }
        val g = get(ok.hello(true, true))
        assertNull(ok.state(7, ready1))
        val s = store(ok.reply(g.gen, cardPage(a, 1L..5L)))
        val k = ack(stored(s, p = ok))
        get(ok.acked(k.gen, k, null, true))

        val failed = PlayLogSync({ cursors[it] }, true) { logs += it }
        val f = get(failed.hello(true, true))
        assertNull(failed.state(7, ready1))
        get(failed.getFailed(f.gen, Err.busy, 1))

        val acked = PlayLogSync({ cursors[it] }, true) { logs += it }
        val h = get(acked.hello(true, true))
        val k2 = ack(stored(store(acked.reply(h.gen, cardPage(b, 1L..5L))), p = acked))
        assertNull(acked.state(7, ready1))
        get(acked.acked(k2.gen, k2, Err.badValue, true))

        val lost = PlayLogSync({ cursors[it] }, true) { logs += it }
        val m = get(lost.hello(true, true))
        val s3 = store(lost.reply(m.gen, cardPage(a, 1L..5L)))
        assertNull(lost.state(7, ready1))
        get(lost.stored(s3.gen, s3.log, null, false))
    }

    @Test
    fun anEpochMoveSetsAfterToZeroAndGetsNothingAlone() {
        val g = get(cards.hello(true, true))
        assertNull(cardCycle(cardPage(a, 1L..5L), g))
        assertNull(cards.state(5, ready1))
        assertNull(cards.state(5, ready2))
        val n = get(cards.state(6, ready2))
        assertEquals(0L, n.after)
        assertNull(cardCycle(cardPage(a, 1L..6L), n))
        assertEquals(6L, get(cards.state(7, ready2)).after)
    }

    @Test
    fun aNoCardWaitsForANewCardLogSeqAndNotForTheRefusalEpoch() {
        val g = get(cards.hello(true, true))
        assertNull(cards.state(4, ready1))
        assertNull(cards.getFailed(g.gen, Err.noCard, 1))
        assertNull(cards.state(4, ready1))
        assertNull(cards.state(4, ready2))
        get(cards.state(5, ready2))
    }

    @Test
    fun aCardReplyGivesNoDroppedNote() {
        cursors[a] = 2
        val g = get(cards.hello(true, true))
        assertNull(cardCycle(cardPage(a, 9L..9L, first = 5), g))
        assertTrue(logs.none { "dropped" in it })
    }

    @Test
    fun anEmptyReplyAcksUpToTheCursorWhenHeldIsAtOrBelowItAndNewestIsSent() {
        cursors[a] = 5
        val g = get(cards.hello(true, true))
        val k = ack(cards.reply(g.gen, PlayLogReply(a, 7, false, emptyList(), null, 3)))
        assertEquals(a to 5L, k.log to k.upTo)
        assertNull(cards.acked(k.gen, k, null, true))
        val above = PlayLogSync({ cursors[it] }, true) { logs += it }
        val h = get(above.hello(true, true))
        assertNull(above.reply(h.gen, PlayLogReply(a, 7, false, emptyList(), null, 6)))
        val none = PlayLogSync({ cursors[it] }, true) { logs += it }
        val n = get(none.hello(true, true))
        assertNull(none.reply(n.gen, PlayLogReply(a, null, false, emptyList(), null, 3)))
        val low = PlayLogSync({ cursors[it] }, true) { logs += it }
        val m = get(low.hello(true, true))
        assertEquals(2L, ack(low.reply(m.gen, PlayLogReply(a, 2, false, emptyList(), null, 1))).upTo)
    }

    @Test
    fun twoSubLogsKeepTwoCursors() {
        val g = get(cards.hello(true, true))
        assertNull(cardCycle(cardPage(a, 1L..3L), g))
        val n = get(cards.state(9, ready2))
        assertEquals(0L, n.after)
        assertNull(cardCycle(cardPage(b, 1L..2L), n))
        assertEquals(3L, cursors[a])
        assertEquals(2L, cursors[b])
        assertEquals(2L, get(cards.state(10, ready2)).after)
    }
}
