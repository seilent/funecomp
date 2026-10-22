package net.seilent.funecomp.state

import net.seilent.funecomp.proto.Eq
import net.seilent.funecomp.proto.EqName
import net.seilent.funecomp.proto.Lib
import net.seilent.funecomp.proto.LibRow
import net.seilent.funecomp.proto.PageInfo
import net.seilent.funecomp.proto.Queue
import net.seilent.funecomp.proto.QueueItem
import net.seilent.funecomp.proto.Row
import net.seilent.funecomp.proto.Schema
import net.seilent.funecomp.proto.Settings
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StateTest {
    private fun playing(pos: Long, sample: Long, sent: Long, dur: Long = 200_000) =
        State(track = 1, durationMs = dur, positionMs = pos, sampleMs = sample, sentMs = sent, playing = true, output = Enums.Output.wired)

    @Test
    fun positionAddsLagHalfRttAndElapsed() {
        val d = DeviceState().withRtt(80).withRtt(40).withState(playing(10_000, 1_000, 1_030), now = 5_000)
        assertEquals(10_000 + 30 + 20, d.positionMs(5_000))
        assertEquals(10_000 + 30 + 20 + 2_500, d.positionMs(7_500))
    }

    @Test
    fun positionClampsAtDurationUntilNextState() {
        val d = DeviceState().withState(playing(199_000, 0, 0), now = 0)
        assertEquals(200_000, d.positionMs(5_000))
        val next = d.withState(playing(1_000, 6_000, 6_000).copy(track = 2), now = 6_000)
        assertEquals(1_500, next.positionMs(6_500))
    }

    @Test
    fun positionHoldsWhilePaused() {
        val d = DeviceState().withState(playing(5_000, 0, 50).copy(playing = false), now = 0)
        assertEquals(5_000, d.positionMs(60_000))
    }

    @Test
    fun positionFollowsDeviceAcrossDrift() {
        var d = DeviceState().withRtt(20)
        var phone = 100_000L
        var device = 7_000L
        var pos = 0L
        repeat(30) {
            d = d.withState(playing(pos, device, device + 15, dur = 1_000_000), phone)
            val shown = d.positionMs(phone + 10_000)
            phone += 10_000
            device += 10_010
            pos += 10_010
            assertTrue("step $it drift ${shown - pos}", kotlin.math.abs(shown - pos) <= 40)
        }
    }

    @Test
    fun newHelloStartsAFreshMirror() {
        val h = net.seilent.funecomp.proto.HelloReply(1, 0, "ap80", "x", 360, 480, 360, 480, 16384, 30000, 5000, 31, 7, 1)
        var d = DeviceState().withHello(h).withRtt(40).withSettings(Settings(5, "balance=2\n")).withSetReply(5)
        d = d.withSchema(Schema(3, "output", 0, 1, PageInfo(0, 1, false), listOf(row("a", Enums.RowKind.toggle))))
        d = d.withHello(h)
        assertNull(d.rttMs)
        assertNull(d.schema.rev)
        d = d.withSettings(Settings(1, "balance=4\n"))
        assertEquals("4", d.settings.values["balance"])
        d = d.withSchema(Schema(3, "output", 0, 1, PageInfo(0, 1, false), listOf(row("b", Enums.RowKind.toggle))))
        assertEquals(listOf("b"), d.schema.rows.map { it.key })
    }

    @Test
    fun settingsOlderThanSetReplyIsDropped() {
        var d = DeviceState().withSettings(Settings(3, "balance=0\n"))
        d = d.withSetReply(5)
        d = d.withSettings(Settings(4, "balance=2\n"))
        assertEquals("0", d.settings.values["balance"])
        d = d.withSettings(Settings(5, "balance=4\n"))
        assertEquals("4", d.settings.values["balance"])
        d = d.withSettings(Settings(2, "balance=9\n"))
        assertEquals("4", d.settings.values["balance"])
    }

    private fun row(key: String, kind: Int?) = Row(key, key.uppercase(), "output", kind)

    @Test
    fun schemaReplacesOnlyOnLastPivotPage() {
        val p0 = Schema(1, "output", 0, 2, PageInfo(0, 3, true), listOf(row("a", Enums.RowKind.choice), row("b", Enums.RowKind.toggle)))
        val p1 = Schema(1, "output", 0, 2, PageInfo(2, 3, false), listOf(row("c", Enums.RowKind.steps)))
        val p2 = Schema(1, "power", 1, 2, PageInfo(0, 1, false), listOf(row("d", 99)))
        var d = DeviceState().withSchema(p0).withSchema(p1)
        assertNull(d.schema.rev)
        d = d.withSchema(p2)
        assertEquals(1L, d.schema.rev)
        assertEquals(listOf("a", "b", "c", "d"), d.schema.rows.map { it.key })
        val unknown = d.schema.rows.last()
        assertEquals(Enums.RowKind.info, unknown.kind)
        assertTrue(Rows.readOnly(unknown))
        val again = d.withSchema(Schema(2, "output", 0, 1, PageInfo(0, 1, false), listOf(row("z", Enums.RowKind.toggle))))
        assertEquals(listOf("z"), again.schema.rows.map { it.key })
    }

    @Test
    fun eqNamesAccumulateAcrossPages() {
        val a = Eq(Enums.EqOutput.wired, "off", listOf(EqName("off", "OFF"), EqName("custom", "CUSTOM")), null, PageInfo(0, 3, true))
        val b = Eq(Enums.EqOutput.wired, "off", listOf(EqName("p/a.txt", "a")), null, PageInfo(2, 3, false))
        val d = DeviceState().withEq(a).withEq(b)
        val m = d.eq.getValue(Enums.EqOutput.wired)
        assertEquals(3, m.names.size)
        assertTrue(m.complete)
        val fresh = d.withEq(a)
        assertEquals(2, fresh.eq.getValue(Enums.EqOutput.wired).names.size)
        assertFalse(fresh.eq.getValue(Enums.EqOutput.wired).complete)
    }

    @Test
    fun queueRevChangeDropsItems() {
        var d = DeviceState().withState(State(queueRev = 4), 0)
        d = d.withQueue(Queue(4, PageInfo(0, 2, false), listOf(QueueItem(1, "a"), QueueItem(2, "b"))))
        assertEquals(2, d.queue.items.size)
        d = d.withState(State(queueRev = 5), 0)
        assertEquals(0, d.queue.items.size)
        assertEquals(5L, d.queue.rev)
    }

    @Test
    fun aQueueOpReplyRevDropsItemsOnlyWhenItIsNewer() {
        var d = DeviceState().withState(State(queueRev = 4), 0)
        d = d.withQueue(Queue(4, PageInfo(0, 2, false), listOf(QueueItem(1, "a"), QueueItem(2, "b"))))
        assertEquals(2, d.withQueueRev(4).queue.items.size)
        assertEquals(2, d.withQueueRev(3).queue.items.size)
        d = d.withQueueRev(6)
        assertEquals(0, d.queue.items.size)
        assertEquals(6L, d.queue.rev)
    }

    @Test
    fun libRevChangeDropsPages() {
        var d = DeviceState().withState(State(libRev = 7), 0)
        d = d.withLib(Lib(7, Enums.View.artists, "", PageInfo(0, 1, false), listOf(LibRow("x", 1, 1, "x"))))
        assertEquals(1, d.lib.pages.size)
        d = d.withState(State(libRev = 8), 0)
        assertEquals(0, d.lib.pages.size)
    }

    @Test
    fun libPagesOfOneRevMergeWhateverTheOrder() {
        var d = DeviceState().withState(State(libRev = 7), 0)
        val rows = (0 until 4).map { LibRow("a$it", 1, it.toLong(), "a$it") }
        d = d.withLib(Lib(7, Enums.View.artists, "", PageInfo(0, 4, true), rows.take(2)))
        d = d.withLib(Lib(7, Enums.View.artists, "", PageInfo(2, 4, false), rows.drop(2)))
        d = d.withLib(Lib(7, Enums.View.artists, "", PageInfo(0, 4, true), rows.take(2)))
        val list = d.lib.pages.getValue(LibKey(Enums.View.artists, ""))
        assertEquals(4L, list.total)
        assertEquals((0L until 4L).toList(), list.rows.keys.sorted())
        d = d.withLib(Lib(8, Enums.View.artists, "", PageInfo(0, 4, true), rows.take(1)))
        assertEquals(listOf(0L), d.lib.pages.getValue(LibKey(Enums.View.artists, "")).rows.keys.toList())
    }

    @Test
    fun theFirstStateIsNoOutputChange() {
        val d = DeviceState().withState(State(output = Enums.Output.wired, playing = false), 5_000)
        assertNull(d.playback.gateAt)
        assertTrue(PushGate.open(d, 5_000))
        val playing = DeviceState().withState(State(output = Enums.Output.wired, playing = true), 5_000)
        assertFalse(PushGate.open(playing, 5_500))
        assertTrue(PushGate.open(playing, 6_000))
    }

    @Test
    fun pushGateClosedForStreamsUnknownOutputAndJustAfterPlay() {
        val wired = State(output = Enums.Output.wired, playing = false)
        var d = DeviceState().withState(wired, 0).withState(wired.copy(output = Enums.Output.btHeadset), 0).withState(wired, 0)
        assertFalse(PushGate.open(d, 500))
        assertTrue(PushGate.open(d, 1_000))
        d = d.withState(wired.copy(playing = true), 10_000)
        assertFalse(PushGate.open(d, 10_500))
        assertTrue(PushGate.open(d, 11_000))
        assertFalse(PushGate.open(d.withState(wired.copy(output = Enums.Output.btHeadset), 20_000), 30_000))
        assertFalse(PushGate.open(d.withState(wired.copy(output = 9), 20_000), 30_000))
        val back = d.withState(wired.copy(output = Enums.Output.btHeadset, playing = true), 20_000)
            .withState(wired.copy(playing = true), 30_000)
        assertFalse(PushGate.open(back, 30_500))
        assertTrue(PushGate.open(back, 31_000))
    }

    @Test
    fun reconnectBackoffAndByeRules() {
        val r = Reconnect(random = { 0.5 })
        assertEquals(0L, r.delayMs())
        val seen = ArrayList<Long>()
        repeat(7) {
            assertTrue(r.begin())
            assertFalse(r.begin())
            r.ended(reachedHello = false)
            seen += r.delayMs()!!
        }
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L), seen)
        r.begin()
        r.ended(reachedHello = true)
        assertEquals(1000L, r.delayMs())

        val j = Reconnect(random = { 0.0 })
        j.begin(); j.ended(false)
        assertEquals(800L, j.delayMs())
        val top = Reconnect(random = { 1.0 })
        repeat(8) { top.begin(); top.ended(false) }
        assertEquals(30000L, top.delayMs())

        for ((reason, stop) in listOf(
            Enums.ByeReason.version to Reconnect.Stop.VERSION,
            Enums.ByeReason.refused to Reconnect.Stop.REFUSED,
            Enums.ByeReason.replaced to Reconnect.Stop.REPLACED,
            77 to Reconnect.Stop.UNKNOWN,
        )) {
            val b = Reconnect()
            b.bye(reason)
            assertEquals(stop, b.stopped)
            assertNull(b.delayMs())
            assertFalse(b.begin())
            b.foreground()
            assertEquals(0L, b.delayMs())
        }
        val s = Reconnect()
        s.bye(Enums.ByeReason.shutdown)
        assertNull(s.stopped)
    }

    @Test
    fun reconnectTailByClock() {
        var now = 0L
        val r = Reconnect(random = { 0.5 }, clock = { now })
        val seen = ArrayList<Long>()
        while (now < 10 * 60_000L) {
            assertTrue(r.begin())
            now += 100
            r.ended(reachedHello = false)
            val d = r.delayMs()!!
            seen += d
            now += d
        }
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L), seen.take(6))
        assertFalse(seen.takeWhile { it < Reconnect.TAIL_MS }.isEmpty())
        assertEquals(Reconnect.TAIL_MS, seen.last())
        val tailFrom = seen.indexOfFirst { it == Reconnect.TAIL_MS }
        assertTrue(seen.drop(tailFrom).all { it == Reconnect.TAIL_MS })

        r.begin()
        r.live()
        now += 10_000
        r.ended(reachedHello = true)
        assertEquals(Reconnect.TAIL_MS, r.delayMs())

        r.begin()
        r.live()
        now += Reconnect.GOOD_MS
        r.ended(reachedHello = true)
        assertEquals(1000L, r.delayMs())
        assertFalse(r.tail)

        r.kick()
        assertEquals(0L, r.delayMs())
        assertTrue(r.begin())
        r.ended(false)
        assertEquals(2000L, r.delayMs())
    }

    @Test
    fun reconnectPauseAndResetPaths() {
        var now = 0L
        val r = Reconnect(random = { 0.5 }, clock = { now })
        repeat(20) {
            r.begin()
            now += 60_000
            r.ended(false)
        }
        assertTrue(r.tail)
        r.pause()
        assertNull(r.delayMs())
        assertFalse(r.begin())
        r.kick()
        assertNull(r.delayMs())
        r.foreground()
        assertNull(r.delayMs())
        r.userAction()
        assertEquals(0L, r.delayMs())
        assertFalse(r.tail)

        repeat(20) {
            r.begin()
            now += 60_000
            r.ended(false)
        }
        r.foreground()
        assertEquals(0L, r.delayMs())
        assertFalse(r.tail)

        r.bye(Enums.ByeReason.replaced)
        assertEquals(Reconnect.Stop.REPLACED, r.stopped)
        r.kick()
        assertNull(r.delayMs())
        assertFalse(r.begin())
        r.userAction()
        assertNull(r.stopped)
        assertEquals(0L, r.delayMs())
    }

    @Test
    fun wakeHoldsUntilTheLastReasonDrops() {
        var now = 0L
        val log = ArrayList<String>()
        val w = Wake({ log += "acquire $it" }, { log += "release" }, { now })
        w.hold("attempt", 20_000)
        now += 5_000
        w.hold("run", 60_000)
        w.drop("attempt")
        assertEquals(setOf("run"), w.held)
        w.drop("run")
        assertEquals(emptySet<String>(), w.held)
        w.drop("run")
        w.hold("attempt", 20_000)
        now += 25_000
        assertEquals(emptySet<String>(), w.held)
        w.drop("attempt")
        assertEquals(listOf("acquire 20000", "acquire 60000", "acquire 60000", "release", "acquire 20000"), log)
    }
}
