package net.seilent.funecomp.state

import net.seilent.funecomp.proto.PlayEntry
import net.seilent.funecomp.proto.PlayLogReply
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

    private fun stored(s: PlayLogSync.Store, upTo: Long? = s.entries.maxOfOrNull { it.seq }): PlayLogSync.Do? {
        if (upTo != null) cursors[s.log] = maxOf(cursors[s.log] ?: 0, upTo)
        return sync.stored(s.gen, s.log, upTo, s.more)
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
}
