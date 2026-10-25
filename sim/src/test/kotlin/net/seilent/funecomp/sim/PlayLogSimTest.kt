package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.Frame
import net.seilent.funecomp.proto.FrameReader
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.Hello
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.PlayEntry
import net.seilent.funecomp.proto.PlayLogReply
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.Settings
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class PlayLogSimTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var t = 1000L
    private var wall = 1_800_000_000_000L
    private val lib = Library.synthetic()
    private val logs = ArrayList<String>()

    private class Sink : Out {
        val frames = ArrayList<Pair<Int, ByteArray>>()
        override fun push(type: Int, payload: ByteArray) {
            frames += type to payload
        }
    }

    private fun seed(n: Int) = (1..n).map { PlayEntry(0, 1_799_990_000L + it * 300, 120_000, 240_000, it.toLong(), "Track $it", "Radiohead", listOf("Radiohead")) }

    private fun device(seed: List<PlayEntry> = emptyList(), minor: Int = Proto.minor, faults: List<String> = emptyList(), cap: Int = PlayLog.CAP) =
        Device(Options(Board.of("v1"), Faults(faults), minor = minor, playLog = seed, playLogCap = cap, wall = { wall }, log = { logs += it }), lib) { t }

    private fun ok(a: Answer) = (a as? Answer.Ok ?: throw AssertionError("failed: ${(a as Answer.Fail).code} ${a.text}")).payload

    private fun code(a: Answer) = (a as Answer.Fail).code

    private fun get(d: Device, after: Long, count: Long = 50) = PlayLogReply.from(Node.decode("PLAYLOG_GET.reply", ok(d.request(Msg.PLAYLOG_GET, Node.decode("PLAYLOG_GET", Req.playLogGet(after, count)), Sink()))))

    private fun ack(d: Device, log: Long, upTo: Long) = d.request(Msg.PLAYLOG_ACK, Node.decode("PLAYLOG_ACK", Req.playLogAck(log, upTo)), Sink())

    private fun state(d: Device) = State.from(Node.decode("STATE", d.state()))

    private fun set(d: Device, text: String) = d.request(Msg.SET, Node.decode("SET", Req.set(text)), Sink())

    private fun play(d: Device, start: Long = 0) = ok(d.request(Msg.PLAY, Node.decode("PLAY", Req.play(Enums.View.songs, "", lib.rev, start, false)), Sink()))

    private fun run(d: Device, ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(1000L, left)
            t += step
            wall += step
            left -= step
            d.tick(Sink())
        }
    }

    @Test
    fun getPagesOverOneHundredTwentyEntries() {
        val d = device(seed(120))
        val a = get(d, 0)
        assertEquals((1L..50L).toList(), a.entries.map { it.seq })
        assertEquals(true, a.more)
        assertEquals(120L, a.newest)
        assertEquals(1L, a.first)
        assertEquals(PlayLog.ID, a.log)
        assertEquals((51L..100L).toList(), get(d, 50).entries.map { it.seq })
        val c = get(d, 100)
        assertEquals((101L..120L).toList(), c.entries.map { it.seq })
        assertEquals(false, c.more)
    }

    @Test
    fun ackDropsBelowFirstIsOkPastNewestIsTwoAndAnotherLogIsSeven() {
        val d = device(seed(10))
        ok(ack(d, PlayLog.ID, 5))
        assertEquals(6L, get(d, 0).first)
        ok(ack(d, PlayLog.ID, 3))
        assertEquals(Err.badValue, code(ack(d, PlayLog.ID, 11)))
        assertEquals(Err.staleRev, code(ack(d, PlayLog.ID + 1, 7)))
        assertEquals((6L..10L).toList(), get(d, 0).entries.map { it.seq })
    }

    @Test
    fun stateCarriesTheNewestHeldSeqAndNoneWhenEmpty() {
        val d = device(seed(3))
        assertEquals(3L, state(d).playLogSeq)
        ok(ack(d, PlayLog.ID, 3))
        assertNull(state(d).playLogSeq)
        assertNull(get(d, 0).newest)
        assertEquals(Err.badValue, code(ack(d, PlayLog.ID, 4)))
        ok(ack(d, PlayLog.ID, 2))
    }

    @Test
    fun aPlayPastTheRuleAppendsOneEntryAndASkipAt20SecondsNone() {
        val d = device()
        play(d)
        run(d, 20_000)
        ok(d.request(Msg.TRANSPORT, Node.decode("TRANSPORT", Req.transport(Enums.TransportCmd.next)), Sink()))
        run(d, 1000)
        assertTrue(d.plays.isEmpty())
        val track = lib.byId.getValue(state(d).track!!)
        run(d, minOf(track.durationMs / 2, 240_000) + 1000)
        val e = d.plays.single()
        assertEquals(track.title, e.title)
        assertEquals(track.id, e.track)
        assertEquals(track.durationMs, e.durationMs)
        assertNull(e.liveSeen)
        assertNull(e.zoneS)
        run(d, 10_000)
        assertEquals(1, d.plays.size)
        assertEquals(1L, state(d).playLogSeq)
    }

    @Test
    fun theStartIsLocalWithTheZoneOfTheLastAutomaticClock() {
        val d = device()
        ok(d.request(Msg.DRAFT, Node.decode("DRAFT", Req.draft(Enums.DraftKind.clock, unixS = wall / 1000, zoneS = 32400, automatic = true)), Sink()))
        val at = wall / 1000
        play(d)
        run(d, 240_000)
        val e = d.plays.single()
        assertEquals(32400, e.zoneS)
        assertEquals(at + 32400, e.startLocalS)
    }

    @Test
    fun aLinkedAppWithoutBit23GivesLiveSeen() {
        val d = device()
        d.linked(Sink(), Groups.all and Groups.mask("playLog").inv())
        play(d)
        run(d, 240_000)
        assertEquals(true, d.plays.single().liveSeen)
    }

    @Test
    fun aLinkedAppWithBit23GivesNoLiveSeen() {
        val d = device()
        d.linked(Sink(), Groups.all)
        play(d)
        run(d, 240_000)
        assertNull(d.plays.single().liveSeen)
    }

    @Test
    fun newlogRenumbersFromOneAtTheNextConnect() {
        val d = device(seed(4), faults = listOf("newlog"))
        d.linked(Sink(), Groups.all)
        ok(ack(d, PlayLog.ID, 1))
        d.linked(Sink(), Groups.all)
        val r = get(d, 0)
        assertEquals(PlayLog.ID + 1, r.log)
        assertEquals(listOf(1L, 2L, 3L), r.entries.map { it.seq })
        assertEquals(listOf("Track 2", "Track 3", "Track 4"), r.entries.map { it.title })
    }

    @Test
    fun theCapDropsTheOldestTenthAndFirstShowsIt() {
        val d = device(seed(11), cap = 10)
        val r = get(d, 0)
        assertEquals(2L, r.first)
        assertEquals((2L..11L).toList(), r.entries.map { it.seq })
    }

    @Test
    fun minor14ClaimsNoBit23To25AndRefusesGenres() {
        val d = device(minor = 14)
        val f = d.helloReply().features ?: 0
        for (b in listOf("playLog", "genres", "queueAdd")) assertEquals(0L, f and Groups.mask(b))
        assertNull(state(device(seed(2), minor = 14)).playLogSeq)
        assertEquals(Err.badValue, code(d.request(Msg.LIB_GET, Node.decode("LIB_GET", Req.libGet(Enums.View.genres, "", 0, 50, lib.rev)), Sink())))
        val all = device().helloReply().features ?: 0
        for (b in listOf("playLog", "genres", "queueAdd")) assertEquals(Groups.mask(b), all and Groups.mask(b))
    }

    @Test
    fun getAndAckAreServedInCardAndOffAndOnlyPhoneAppends() {
        for (mode in listOf("card", "off")) {
            val d = device(seed(2))
            ok(set(d, "scrobble=$mode\n"))
            assertEquals(2, get(d, 0).entries.size)
            ok(ack(d, PlayLog.ID, 2))
            play(d)
            run(d, 240_000)
            assertTrue(mode, d.plays.isEmpty())
        }
    }

    @Test
    fun theScrobbleRowFollowsPhoneAppOnBothBoardsAndStartsAtPhone() {
        for (b in listOf("v1", "ap80")) {
            val d = Device(Options(Board.of(b)), lib) { t }
            val keys = d.rows.map { it.key }
            assertEquals(keys.indexOf("companionPair") + 1, keys.indexOf("scrobble"))
            val row = d.rows.first { it.key == "scrobble" }
            assertEquals("system", row.pivot)
            assertEquals(Enums.RowKind.choice, row.kind)
            assertEquals("SCROBBLE", row.label)
            assertEquals(listOf("phone", "card", "off"), row.values)
            assertEquals(listOf("PHONE", "CARD LOG", "OFF"), row.words)
            val out = Sink()
            d.greet(out)
            val text = out.frames.filter { it.first == Msg.SETTINGS }.map { Settings.from(Node.decode("SETTINGS", it.second)).text }.single()
            assertTrue("scrobble=phone" in text.lines())
        }
    }

    @Test
    fun seededEntriesReadFromAFile() {
        val f = File(tmp.root, "log.tsv")
        f.writeText("1799990000\t120000\t240000\t7\tAirbag\tRadiohead\tRadiohead\ta74b1b7f-71a5-4011-9441-d0b5e4122711\tOK Computer\t\t1\t32400\t\n\t\t240000\t\tLucky\tRadiohead\t\t\t\t\t\t\t1\n")
        val e = PlayLog.parse(f)
        assertEquals(PlayEntry(0, 1799990000, 120000, 240000, 7, "Airbag", "Radiohead", listOf("Radiohead"), listOf("a74b1b7f-71a5-4011-9441-d0b5e4122711"), "OK Computer", null, 1, 32400), e[0])
        assertEquals(PlayEntry(0, null, null, 240000, null, "Lucky", "Radiohead", liveSeen = true), e[1])
    }

    @Test
    fun dropAckClosesOnTheFirstAckWithoutAnAnswerAndKeepsTheEntries() {
        val opts = Options(Board.of("v1"), Faults(listOf("drop-ack")), playLog = seed(2), log = { logs += it })
        val d = Device(opts, lib) { t }
        val input = ByteArrayOutputStream()
        input.write(Frame(Msg.HELLO, Proto.flagWant, 1, Hello(Proto.major, Proto.minor, "test", Groups.all).encode()).encode())
        input.write(Frame(Msg.PLAYLOG_ACK, Proto.flagWant, 2, Req.playLogAck(PlayLog.ID, 2)).encode())
        val output = ByteArrayOutputStream()
        Conn(1, ByteArrayInputStream(input.toByteArray()), output, {}, d, opts) { t }.run()
        val b = output.toByteArray()
        assertFalse(FrameReader().feed(b, 0, b.size).any { it.type == Msg.PLAYLOG_ACK })
        assertTrue(logs.any { it.endsWith("closed drop-ack") })
        assertEquals(2, d.plays.size)
        assertFalse(opts.faults.dropAck.get())
    }
}
