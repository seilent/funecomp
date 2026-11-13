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

    private fun device(seed: List<PlayEntry> = emptyList(), minor: Int = Proto.minor, faults: List<String> = emptyList(), cap: Int = PlayLog.CAP, card: List<PlayEntry> = emptyList(), companion: Boolean = false, bind: Boolean = true) =
        Device(Options(Board.of("v1"), Faults(faults), minor = minor, playLog = seed, playLogCap = cap, wall = { wall }, log = { logs += it }, companion = companion, cardLog = card, bind = bind), lib) { t }

    private val noCardBit = Groups.all and Groups.mask("cardLog").inv()

    private fun link(d: Device, features: Long = Groups.all, app: String? = "phone") = Sink().also { d.linked(it, features, app) }

    private fun cardGet(d: Device, out: Out, after: Long, count: Long = 50) = PlayLogReply.from(Node.decode("PLAYLOG_GET.reply", ok(d.request(Msg.PLAYLOG_GET, Node.decode("PLAYLOG_GET", Req.playLogGet(after, count, card = true)), out))))

    private fun cardAck(d: Device, out: Out, log: Long, upTo: Long) = d.request(Msg.PLAYLOG_ACK, Node.decode("PLAYLOG_ACK", Req.playLogAck(log, upTo, card = true)), out)

    private fun cardBit(d: Device, out: Out) = d.helloReply(out).features and Groups.mask("cardLog")

    private fun state(d: Device, out: Out) = State.from(Node.decode("STATE", d.state(out = out)))

    private fun cross(d: Device, start: Long = 0) {
        play(d, start)
        val track = lib.byId.getValue(state(d).track!!)
        run(d, minOf(track.durationMs / 2, 240_000) + 1000)
    }

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
        val d = device(minor = 17)
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
        val d = device(minor = 17)
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
        d.linked(Sink(), noCardBit)
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
            val d = device(seed(2), minor = 17)
            ok(set(d, "scrobble=$mode\n"))
            assertEquals(2, get(d, 0).entries.size)
            ok(ack(d, PlayLog.ID, 2))
            play(d)
            run(d, 240_000)
            assertTrue(mode, d.plays.isEmpty())
        }
    }

    @Test
    fun theScrobbleRowFollowsPhoneAppOnBothBoardsAndStartsAtCard() {
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
            assertTrue("scrobble=card" in text.lines())
        }
        val out = Sink()
        Device(Options(Board.of("v1"), minor = 17), lib) { t }.greet(out)
        assertTrue("scrobble=phone" in out.frames.filter { it.first == Msg.SETTINGS }.map { Settings.from(Node.decode("SETTINGS", it.second)).text }.single().lines())
    }

    @Test
    fun cardLogIsServedFromMinor18AndOnlyToTheBoundPeer() {
        assertEquals(Groups.mask("cardLog"), device().helloReply().features and Groups.mask("cardLog"))
        assertEquals(0L, device(minor = 17).helloReply().features and Groups.mask("cardLog"))
        val d = device()
        val bound = link(d)
        assertEquals(Groups.mask("cardLog"), cardBit(d, bound))
        val other = link(d, app = "other")
        assertEquals(0L, cardBit(d, other))
        d.unlinked(bound)
        assertEquals(Groups.mask("cardLog"), cardBit(d, link(d)))
        val none = device(bind = false)
        assertEquals(0L, cardBit(none, link(none)))
        val late = device(faults = listOf("bind-late"))
        assertEquals(0L, cardBit(late, link(late)))
        assertEquals(0L, cardBit(late, link(late, app = "other")))
        assertEquals(Groups.mask("cardLog"), cardBit(late, link(late)))
    }

    @Test
    fun aCrossingWhileUnlinkedIsPlacedOneWhileLinkedIsQueuedAndTheFlagIsComputedAtEveryAnswer() {
        val d = device()
        cross(d)
        assertTrue(d.plays.isEmpty())
        assertEquals(1, d.cardPlays.size)
        val out = link(d)
        val a = cardGet(d, out, 0)
        assertEquals(listOf(1L), a.entries.map { it.seq })
        assertNull(a.entries.single().exposed)
        assertEquals(1L to 1L, a.newest to a.held)
        assertNull(a.first)
        cross(d, 1)
        assertTrue(logs.any { it.startsWith("cardlog append seq 2 ") && it.endsWith(" queued") })
        assertNull(cardGet(d, out, 1).entries.single().exposed)
        d.unlinked(out)
        cross(d, 2)
        d.restart()
        val b = cardGet(d, link(d), 0)
        assertEquals(listOf(1L, 2L, 3L), b.entries.map { it.seq })
        assertEquals(listOf(true, null, true), b.entries.map { it.exposed })
        assertTrue(d.plays.isEmpty())
    }

    @Test
    fun cardPlaysGoToThePhoneLogBesideAnOldAppABit23OnlyPhoneOrACompanionAfterARestart() {
        val old = device()
        old.linked(Sink(), Groups.all and Groups.mask("playLog").inv(), "old")
        cross(old)
        assertEquals(true, old.plays.single().liveSeen)
        assertTrue(old.cardPlays.isEmpty())

        val plain = device()
        link(plain, noCardBit)
        cross(plain)
        assertNull(plain.plays.single().liveSeen)
        assertTrue(plain.cardPlays.isEmpty())

        val comp = device(companion = true)
        comp.unlinked(link(comp))
        cross(comp)
        assertEquals(1, comp.cardPlays.size)
        comp.restart()
        cross(comp, 1)
        assertEquals(1, comp.plays.size)
        assertEquals(1, comp.cardPlays.size)

        val phone = device()
        link(phone)
        ok(set(phone, "scrobble=phone\n"))
        cross(phone)
        assertEquals(1, phone.plays.size)
        assertTrue(phone.cardPlays.isEmpty())

        val below = device(minor = 17)
        ok(set(below, "scrobble=card\n"))
        cross(below)
        assertTrue(below.plays.isEmpty())
        assertTrue(below.cardPlays.isEmpty())
    }

    @Test
    fun aCardGetIsNoCardWhileTheCardIsNotReadyAndTag3IsIgnoredWithoutBit42() {
        val d = device(seed(2), faults = listOf("card-seq=busy:5000,ready"), card = seed(3))
        d.startCard()
        val out = link(d)
        assertEquals(Err.noCard, code(d.request(Msg.PLAYLOG_GET, Node.decode("PLAYLOG_GET", Req.playLogGet(0, 50, card = true)), out)))
        run(d, 6000)
        assertEquals(3, cardGet(d, out, 0).entries.size)
        val other = link(d, app = "other")
        val r = cardGet(d, other, 0)
        assertEquals(PlayLog.ID, r.log)
        assertEquals(2, r.entries.size)
        assertEquals(Err.badValue, code(cardAck(d, other, PlayLog.ID, 3)))
        ok(cardAck(d, other, PlayLog.ID, 2))
        assertTrue(d.plays.isEmpty())
        assertEquals(3, d.cardPlays.size)
    }

    @Test
    fun aGetStopsAtAnUnverifiedPlacedEntryUntilItsFindEndsAndALostEntryIsServed() {
        val d = device(faults = listOf("card-expose", "card-verify=2000"), card = seed(3))
        val out = link(d)
        val a = cardGet(d, out, 0)
        assertTrue(a.entries.isEmpty())
        assertEquals(0L to 1L, a.newest to a.held)
        assertNull(state(d, out).cardLogSeq)
        run(d, 3000)
        assertEquals(3L, state(d, out).cardLogSeq)
        assertTrue(logs.any { it.startsWith("cardlog find done") })
        val b = cardGet(d, out, 0)
        assertEquals(listOf(1L, 2L, 3L), b.entries.map { it.seq })
        assertTrue(b.entries.all { it.exposed == true })

        val never = device(faults = listOf("card-expose", "card-lost=1"), card = seed(3))
        val o = link(never)
        val c = cardGet(never, o, 0)
        assertEquals(listOf(1L), c.entries.map { it.seq })
        assertEquals(true, c.entries.single().exposed)
        assertEquals(1L, c.newest)
        run(never, 10_000)
        assertEquals(listOf(1L), cardGet(never, o, 0).entries.map { it.seq })
    }

    @Test
    fun newestIsAlwaysSentHeldIsTheLowestHeldSeqAndThereIsNoFirst() {
        val d = device(card = seed(3))
        val out = link(d)
        val a = cardGet(d, out, 3)
        assertTrue(a.entries.isEmpty())
        assertEquals(3L to 1L, a.newest to a.held)
        assertNull(a.first)
        assertEquals(true, cardGet(d, out, 0, count = 2).more)
        ok(cardAck(d, out, a.log!!, 2))
        assertEquals(3L to 3L, cardGet(d, out, 3).let { it.newest to it.held })
        ok(cardAck(d, out, a.log!!, 3))
        val b = cardGet(d, out, 0)
        assertEquals(0L, b.newest)
        assertNull(b.held)
        assertTrue(b.entries.isEmpty())
        assertNull(state(d, out).cardLogSeq)
    }

    @Test
    fun aCardAckIsSevenForAnUnknownSubLogAndTwoAboveTheSessionsNewest() {
        val d = device(card = seed(3))
        val out = link(d)
        val log = cardGet(d, out, 0).log!!
        assertEquals(Err.staleRev, code(cardAck(d, out, log + 1, 1)))
        assertEquals(Err.badValue, code(cardAck(d, out, log, 4)))
        d.unlinked(out)
        val next = link(d)
        assertEquals(Err.badValue, code(cardAck(d, next, log, 1)))
        cardGet(d, next, 0)
        ok(cardAck(d, next, log, 1))
        assertEquals(listOf(2L, 3L), d.cardPlays.map { it.seq })
    }

    @Test
    fun aCardRequestFromAPeerNoLongerBoundGetsTheLinkLogAnswer() {
        val d = device(seed(2), card = seed(3))
        val out = link(d)
        assertEquals(3, cardGet(d, out, 0).entries.size)
        d.rebind("other")
        val r = cardGet(d, out, 0)
        assertEquals(PlayLog.ID, r.log)
        assertEquals(2, r.entries.size)
        ok(cardAck(d, out, PlayLog.ID, 2))
        assertTrue(d.plays.isEmpty())
        assertEquals(3, d.cardPlays.size)
        assertEquals(3L, state(d, out).cardLogSeq)
    }

    @Test
    fun stateCarriesTheCardLogSeqForTheCardPeerOnly() {
        val d = device(card = seed(3))
        val out = link(d)
        assertEquals(3L, state(d, out).cardLogSeq)
        assertNull(state(d, link(d, app = "other")).cardLogSeq)
        assertNull(state(d, link(d, noCardBit)).cardLogSeq)
        assertNull(state(d).cardLogSeq)
    }

    @Test
    fun aCardSwapAtTheNextReadyEdgeServesTheNewCardsSubLogAndTheOldOneStillAcks() {
        val d = device(faults = listOf("card-run=1000+500@manual", "card-swap=b"), card = seed(2))
        val out = link(d)
        val a = cardGet(d, out, 0)
        assertEquals(2, a.entries.size)
        d.startCard()
        run(d, 2000)
        assertTrue(logs.contains("cardlog card b"))
        val b = cardGet(d, out, 0)
        assertFalse(a.log == b.log)
        assertTrue(b.entries.isEmpty())
        assertEquals(0L, b.newest)
        ok(cardAck(d, out, a.log!!, 2))
        assertTrue(d.cardPlays.isEmpty())
        d.unlinked(out)
        cross(d)
        val c = cardGet(d, link(d), 0)
        assertEquals(b.log, c.log)
        assertEquals(listOf(3L), c.entries.map { it.seq })
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
