package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.PlayLogReply
import net.seilent.funecomp.proto.Queue
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Feature
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.state.CardView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSimTest {
    private class Sink : Out {
        val frames = ArrayList<Pair<Int, ByteArray>>()
        override fun push(type: Int, payload: ByteArray) {
            frames += type to payload
        }

        fun queues() = frames.filter { it.first == Msg.QUEUE }.map { Queue.from(Node.decode("QUEUE", it.second)) }
    }

    private var t = 1000L
    private val noBit = Groups.all and Groups.mask("books").inv()

    private fun device(vararg faults: String, minor: Int = 21, lib: Library = Library.synthetic(), receiver: Boolean = false) =
        Device(Options(Board.of("v1"), Faults(faults.toList()), minor = minor, receiver = receiver), lib) { t }

    private fun peer(d: Device, mask: Long = Groups.all) = Sink().also {
        d.linked(it, mask)
        d.greet(it)
    }

    private fun req(d: Device, out: Out, type: Int, payload: ByteArray) = d.request(type, Node.decode(Msg.info.getValue(type).name, payload), out)

    private fun state(d: Device, out: Out) = State.from(Node.decode("STATE", d.state(out = out)))

    private fun skip(d: Device, out: Out, ms: Int?) = req(d, out, Msg.TRANSPORT, Req.transport(Enums.TransportCmd.skip, skipMs = ms))

    private fun speed(d: Device, out: Out, pct: Int?) = req(d, out, Msg.TRANSPORT, Req.transport(Enums.TransportCmd.speed, speedPct = pct))

    private fun end(d: Device, out: Out, sleepEnd: Boolean? = true, minutes: Long? = null) =
        req(d, out, Msg.DRAFT, Req.draft(Enums.DraftKind.sleep, minutes = minutes, sleepEnd = sleepEnd))

    private fun play(d: Device, out: Out, ids: List<Long>, start: Long? = null, shuffle: Boolean? = null) = req(d, out, Msg.PLAY, Req.play(start = start, shuffle = shuffle, tracks = ids))

    private fun chapters(n: IntProgression) = n.map { Library.BOOK_FIRST + it }

    private fun code(a: Answer) = (a as? Answer.Fail)?.code

    private fun spot(s: State) = s.slot to s.positionMs

    @Test
    fun aBit48PeerGetsTheChapterWithItsSpeedAndTheQueueTag() {
        val d = device("bookplay")
        val out = peer(d)
        val s = state(d, out)
        assertEquals(Library.BOOK_FIRST + 2, s.track)
        assertEquals("Chapter 3", s.title)
        assertEquals("Natsume Soseki", s.artist)
        assertEquals("Kokoro", s.album)
        assertEquals(1_800_000L, s.durationMs)
        assertEquals(900_000L, s.positionMs)
        assertEquals(Triple(true, 150, null), Triple(s.book, s.speedPct, s.sleepEnd))
        assertNull(s.lyrics)
        assertNull(s.favorite)
        assertTrue(req(d, out, Msg.QUEUE_OP, Req.queueOp(Enums.QueueCmd.page, offset = 0, count = 10)) is Answer.Ok)
        val items = out.queues().flatMap { it.items }
        assertEquals((0..4).map { Library.BOOK_FIRST + it }, items.map { it.track })
        assertTrue(items.all { it.book == true && it.lyrics == null })
    }

    @Test
    fun thePositionMovesAtTheBookSpeed() {
        val d = device("bookplay")
        val out = peer(d)
        t += 10_000
        assertEquals(915_000L, state(d, out).positionMs)
        assertTrue(speed(d, out, 200) is Answer.Ok)
        assertEquals(200, state(d, out).speedPct)
        t += 10_000
        assertEquals(935_000L, state(d, out).positionMs)
    }

    @Test
    fun skipCrossesChaptersClampsAtTheStartAndEndsTheBookPastTheLastChapter() {
        val d = device("bookplay")
        val out = peer(d)
        assertTrue(skip(d, out, -30_000) is Answer.Ok)
        assertEquals(2L to 870_000L, spot(state(d, out)))
        assertTrue(skip(d, out, 1_000_000) is Answer.Ok)
        assertEquals(3L to 70_000L, spot(state(d, out)))
        assertTrue(skip(d, out, -3_600_000) is Answer.Ok)
        assertEquals(1L to 70_000L, spot(state(d, out)))
        assertTrue(skip(d, out, -3_600_000) is Answer.Ok)
        assertEquals(0L to 0L, spot(state(d, out)))
        assertTrue(state(d, out).playing == true)
        assertTrue(skip(d, out, 3_600_000) is Answer.Ok)
        assertTrue(skip(d, out, 3_600_000) is Answer.Ok)
        assertEquals(4L to 0L, spot(state(d, out)))
        assertTrue(skip(d, out, 1_800_000) is Answer.Ok)
        assertFalse(state(d, out).playing == true)
    }

    @Test
    fun skipAndSpeedAnswerBadValueOffTheirRangeAndRefusedForMusic() {
        val d = device("bookplay")
        val out = peer(d)
        for (ms in listOf(3_600_001, -3_600_001, null)) assertEquals(Err.badValue, code(skip(d, out, ms)))
        for (pct in listOf(110, 0, 225, null)) assertEquals(Err.badValue, code(speed(d, out, pct)))
        assertEquals(2L to 900_000L, spot(state(d, out)))
        assertEquals(150, state(d, out).speedPct)

        val music = device("book")
        val m = peer(music)
        assertEquals(Err.refused, code(skip(music, m, 30_000)))
        assertEquals(Err.refused, code(speed(music, m, 125)))
        val s = state(music, m)
        assertNull(s.book)
        assertNull(s.speedPct)
    }

    @Test
    fun anUnstretchableBookSendsNoSpeedAndRefusesAPick() {
        val d = device("bookplay", "nostretch")
        val out = peer(d)
        val s = state(d, out)
        assertEquals(true, s.book)
        assertNull(s.speedPct)
        assertEquals(Err.refused, code(speed(d, out, 125)))
        t += 10_000
        assertEquals(910_000L, state(d, out).positionMs)
        assertTrue(skip(d, out, 30_000) is Answer.Ok)
    }

    @Test
    fun withoutBit48OnEitherSideTheBookCommandsAreRefusedBeforeAnythingActs() {
        for ((d, mask) in listOf(device("bookplay", "nobooks") to Groups.all, device("bookplay") to noBit)) {
            val out = peer(d, mask)
            val before = state(d, out)
            assertEquals(Err.refused, code(skip(d, out, 30_000)))
            assertEquals(Err.refused, code(speed(d, out, 125)))
            assertEquals(Err.refused, code(end(d, out)))
            assertEquals(Err.refused, code(end(d, out, minutes = 30)))
            assertEquals(Err.refused, code(end(d, out, sleepEnd = false, minutes = 30)))
            val s = state(d, out)
            assertEquals(before.slot to before.positionMs, spot(s))
            assertEquals(0L, s.sleepLeftS)
            assertNull(s.track)
            assertEquals(Triple(null, null, null), Triple(s.book, s.speedPct, s.sleepEnd))
            assertTrue(req(d, out, Msg.DRAFT, Req.draft(Enums.DraftKind.sleep, minutes = 30)) is Answer.Ok)
            assertEquals(1800L, state(d, out).sleepLeftS)
        }
        val d = device("bookplay")
        val old = peer(d, noBit)
        val now = peer(d)
        assertEquals(Err.refused, code(speed(d, old, 125)))
        assertEquals(Err.refused, code(end(d, old)))
        assertEquals(Triple(true, 150, null), state(d, now).let { Triple(it.book, it.speedPct, it.sleepEnd) })
    }

    @Test
    fun theSleepEndTagOnAnotherKindIsIgnored() {
        val d = device("bookplay")
        val out = peer(d, noBit)
        assertTrue(req(d, out, Msg.DRAFT, Req.draft(Enums.DraftKind.clock, unixS = 1767225600, zoneS = 0, sleepEnd = true)) is Answer.Ok)
    }

    @Test
    fun anOlderAppGetsAChapterStateWithoutTrackAndAQueueItemWithTrackAndNoBookTag() {
        val d = device("bookplay")
        val out = peer(d, noBit)
        val s = state(d, out)
        assertNull(s.track)
        assertEquals("Chapter 3", s.title)
        assertEquals(1_800_000L, s.durationMs)
        assertNull(s.book)
        assertTrue(req(d, out, Msg.QUEUE_OP, Req.queueOp(Enums.QueueCmd.page, offset = 0, count = 10)) is Answer.Ok)
        val items = out.queues().flatMap { it.items }
        assertEquals((0..4).map { Library.BOOK_FIRST + it }, items.map { it.track })
        assertTrue(items.all { it.book == null && it.title != null })
        t += 10_000
        assertEquals(915_000L, state(d, out).positionMs)
    }

    @Test
    fun bookleakSendsTheBookTagsWithoutBit48() {
        val d = device("bookplay", "nobooks", "bookleak")
        val out = peer(d)
        val s = state(d, out)
        assertEquals(Library.BOOK_FIRST + 2, s.track)
        assertEquals(true to 150, s.book to s.speedPct)
        assertNull(s.sleepEnd)
        assertEquals(Err.refused, code(skip(d, out, 30_000)))
    }

    @Test
    fun belowMinor21OrWithBooksOffTheDeviceHasNoBit48() {
        val d = device("bookplay", minor = 20)
        assertEquals(0L, d.helloReply().features and (1L shl Feature.books))
        assertEquals(0L, device("bookplay", "nobooks").helloReply().features and (1L shl Feature.books))
        assertEquals(1L shl Feature.books, device("bookplay").helloReply().features and (1L shl Feature.books))
        val out = peer(d)
        assertEquals(Err.refused, code(skip(d, out, 30_000)))
        assertNull(state(d, out).book)
    }

    @Test
    fun endOfTrackArmsCountsTheTrackWallTimeAndPausesAtItsEnd() {
        val d = device("bookplay")
        val out = peer(d)
        assertTrue(end(d, out) is Answer.Ok)
        val s = state(d, out)
        assertEquals(true, s.sleepEnd)
        assertEquals(600L, s.sleepLeftS)
        assertTrue(req(d, out, Msg.TRANSPORT, Req.transport(Enums.TransportCmd.pause)) is Answer.Ok)
        t += 60_000
        assertEquals(600L, state(d, out).sleepLeftS)
        assertTrue(req(d, out, Msg.TRANSPORT, Req.transport(Enums.TransportCmd.play)) is Answer.Ok)
        t += 600_000
        d.tick(out)
        val after = state(d, out)
        assertFalse(after.playing == true)
        assertNull(after.sleepEnd)
        assertEquals(3L to 0L, spot(after))
        assertEquals(0L, after.sleepLeftS)
    }

    @Test
    fun endOfTrackShapesAndTheMinutesThatReplaceIt() {
        val d = device("bookplay")
        val out = peer(d)
        assertEquals(Err.badValue, code(end(d, out, minutes = 30)))
        assertEquals(Err.badValue, code(end(d, out, sleepEnd = false)))
        assertNull(state(d, out).sleepEnd)
        assertTrue(req(d, out, Msg.DRAFT, Req.draft(Enums.DraftKind.sleep, minutes = 0)) is Answer.Ok)
        assertEquals(0L, state(d, out).sleepLeftS)
        assertTrue(end(d, out) is Answer.Ok)
        assertTrue(req(d, out, Msg.DRAFT, Req.draft(Enums.DraftKind.sleep, minutes = 30)) is Answer.Ok)
        val s = state(d, out)
        assertNull(s.sleepEnd)
        assertEquals(1800L, s.sleepLeftS)
        assertTrue(end(d, out) is Answer.Ok)
        assertTrue(end(d, out, sleepEnd = false, minutes = 45) is Answer.Ok)
        assertEquals(null to 2700L, state(d, out).let { it.sleepEnd to it.sleepLeftS })
        assertTrue(end(d, out) is Answer.Ok)
        assertTrue(req(d, out, Msg.DRAFT, Req.draft(Enums.DraftKind.sleep, minutes = 0)) is Answer.Ok)
        assertEquals(null to 0L, state(d, out).let { it.sleepEnd to it.sleepLeftS })

        val music = device()
        val m = peer(music)
        assertTrue(end(music, m) is Answer.Ok)
        assertEquals(true, state(music, m).sleepEnd)

        val empty = device(lib = Library(emptyList()))
        assertEquals(Err.refused, code(end(empty, peer(empty))))
    }

    @Test
    fun aBookQueueRefusesShuffleRepeatAndTheFavoriteOfAChapter() {
        val d = device("bookplay")
        val out = peer(d)
        assertEquals(Err.refused, code(req(d, out, Msg.TRANSPORT, Req.transport(Enums.TransportCmd.shuffle, on = true))))
        assertEquals(Err.refused, code(req(d, out, Msg.TRANSPORT, Req.transport(Enums.TransportCmd.repeat, repeat = Enums.Repeat.all))))
        assertEquals(Err.refused, code(req(d, out, Msg.ACTION, Req.action(Enums.ActionCmd.favorite, Library.BOOK_FIRST + 2))))
        val s = state(d, out)
        assertEquals(false to Enums.Repeat.off, s.shuffle to s.repeat)
    }

    @Test
    fun aSkipOnAChapterOutsideABookQueueStaysInItAndMovesOnAsNext() {
        val d = device("book")
        val out = peer(d)
        val music = d.lib.tracks
        assertTrue(req(d, out, Msg.QUEUE_OP, Req.queueOp(Enums.QueueCmd.add, tracks = chapters(2..3) + music[0].id)) is Answer.Ok)
        val at = music.size.toLong()
        assertTrue(req(d, out, Msg.QUEUE_OP, Req.queueOp(Enums.QueueCmd.playAt, state(d, out).queueRev, slot = at)) is Answer.Ok)
        assertEquals(true to 100, state(d, out).let { it.book to it.speedPct })
        t += 10_000
        assertTrue(skip(d, out, -30_000) is Answer.Ok)
        assertEquals(at to 0L, spot(state(d, out)))
        t += 10_000
        assertTrue(skip(d, out, 1_800_000) is Answer.Ok)
        assertEquals(at + 1 to 0L, spot(state(d, out)))
        t += 10_000
        assertTrue(skip(d, out, -30_000) is Answer.Ok)
        assertEquals(at + 1 to 0L, spot(state(d, out)))
        assertTrue(speed(d, out, 125) is Answer.Ok)
        assertEquals(125, state(d, out).speedPct)
        assertEquals(Err.refused, code(req(d, out, Msg.ACTION, Req.action(Enums.ActionCmd.favorite, Library.BOOK_FIRST + 3))))
        assertTrue(skip(d, out, 60_000) is Answer.Ok)
        assertEquals(at + 1 to 60_000L, spot(state(d, out)))
        assertTrue(req(d, out, Msg.TRANSPORT, Req.transport(Enums.TransportCmd.shuffle, on = false)) is Answer.Ok)
        assertTrue(end(d, out) is Answer.Ok)
        assertTrue(skip(d, out, 3_600_000) is Answer.Ok)
        val s = state(d, out)
        assertEquals(at + 2 to 0L, spot(s))
        assertEquals(true, s.playing)
        assertEquals(true, s.sleepEnd)
        assertEquals(music[0].durationMs / 1000, s.sleepLeftS)
        assertNull(s.book)
        assertTrue(play(d, out, chapters(0..4)) is Answer.Ok)
        assertEquals(3L to 60_000L, spot(state(d, out)))
    }

    @Test
    fun aClearEndsTheBookQueue() {
        val d = device("bookplay")
        val out = peer(d)
        assertTrue(req(d, out, Msg.QUEUE_OP, Req.queueOp(Enums.QueueCmd.clear, state(d, out).queueRev)) is Answer.Ok)
        assertTrue(req(d, out, Msg.QUEUE_OP, Req.queueOp(Enums.QueueCmd.playNext, state(d, out).queueRev, tracks = chapters(2..3))) is Answer.Ok)
        assertTrue(req(d, out, Msg.QUEUE_OP, Req.queueOp(Enums.QueueCmd.playAt, state(d, out).queueRev, slot = 0)) is Answer.Ok)
        assertTrue(req(d, out, Msg.TRANSPORT, Req.transport(Enums.TransportCmd.shuffle, on = true)) is Answer.Ok)
    }

    @Test
    fun aPlayAtKeepsEndOfTrackOnTheNewEntryAndAPlayClearsIt() {
        val d = device("bookplay")
        val out = peer(d)
        assertTrue(end(d, out) is Answer.Ok)
        assertTrue(req(d, out, Msg.QUEUE_OP, Req.queueOp(Enums.QueueCmd.playAt, state(d, out).queueRev, slot = 4)) is Answer.Ok)
        val s = state(d, out)
        assertEquals(4L to 0L, spot(s))
        assertEquals(true to 1200L, s.sleepEnd to s.sleepLeftS)
        for (go in listOf({ play(d, out, chapters(0..4)) }, { play(d, out, listOf(d.lib.tracks[0].id)) }, { req(d, out, Msg.ACTION, Req.action(Enums.ActionCmd.shuffleLibrary)) })) {
            assertTrue(end(d, out) is Answer.Ok)
            assertEquals(true, state(d, out).sleepEnd)
            assertTrue(go() is Answer.Ok)
            assertNull(state(d, out).sleepEnd)
        }
    }

    @Test
    fun aSkipPastTheLastChapterRunsTheArmedEndAsTheNaturalEndDoes() {
        val d = device("bookplay")
        val out = peer(d)
        assertTrue(play(d, out, chapters(4..4)) is Answer.Ok)
        assertTrue(end(d, out) is Answer.Ok)
        assertTrue(skip(d, out, 1_800_000) is Answer.Ok)
        val s = state(d, out)
        assertFalse(s.playing == true)
        assertNull(s.sleepEnd)
        assertEquals(0L, s.sleepLeftS)
    }

    @Test
    fun aChapterOpensNoPlayInEitherLog() {
        for (mode in listOf("phone", "card")) {
            val d = device("bookplay")
            val out = peer(d)
            assertTrue(req(d, out, Msg.SET, Req.set("scrobble=$mode\n")) is Answer.Ok)
            repeat(300) {
                t += 1000
                d.tick(out)
            }
            val log = PlayLogReply.from(Node.decode("PLAYLOG_GET.reply", (req(d, out, Msg.PLAYLOG_GET, Req.playLogGet(0, 50)) as Answer.Ok).payload))
            assertTrue(log.entries.isEmpty())
            assertTrue(d.plays.isEmpty())
            assertTrue(d.cardPlays.isEmpty())
        }
    }

    @Test
    fun aPlayOfEveryChapterResumesTheBookAtItsSpotWithShuffleAndRepeatOff() {
        val d = device("bookplay")
        val out = peer(d)
        t += 10_000
        assertTrue(play(d, out, listOf(d.lib.tracks[0].id)) is Answer.Ok)
        assertTrue(req(d, out, Msg.TRANSPORT, Req.transport(Enums.TransportCmd.repeat, repeat = Enums.Repeat.all)) is Answer.Ok)
        t += 60_000
        assertTrue(play(d, out, chapters(0..4), start = 4, shuffle = true) is Answer.Ok)
        val s = state(d, out)
        assertEquals(2L to 915_000L, spot(s))
        assertEquals(Library.BOOK_FIRST + 2, s.track)
        assertEquals(5L, s.slots)
        assertEquals(false to Enums.Repeat.off, s.shuffle to s.repeat)
        assertEquals(Err.refused, code(req(d, out, Msg.TRANSPORT, Req.transport(Enums.TransportCmd.shuffle, on = true))))
        assertEquals(Err.badValue, code(play(d, out, chapters(0..4), start = 5)))
    }

    @Test
    fun aPlayOfSomeChaptersPlaysTheBookFromTheFirstIdNamedAtItsStart() {
        val d = device("bookplay")
        val out = peer(d)
        assertTrue(play(d, out, chapters(4 downTo 3), start = 1) is Answer.Ok)
        val s = state(d, out)
        assertEquals(4L to 0L, spot(s))
        assertEquals(5L, s.slots)
        assertTrue(skip(d, out, -30_000) is Answer.Ok)
        assertEquals(3L to 1_770_000L, spot(state(d, out)))
        assertTrue(play(d, out, listOf(d.lib.tracks[0].id, Library.BOOK_FIRST)) is Answer.Ok)
        assertNull(state(d, out).book)
    }

    @Test
    fun theBookEndMovesItsSpotToTheFirstChapter() {
        val d = device("bookplay")
        val out = peer(d)
        assertTrue(skip(d, out, 3_600_000) is Answer.Ok)
        assertTrue(skip(d, out, 3_600_000) is Answer.Ok)
        assertFalse(state(d, out).playing == true)
        assertTrue(play(d, out, chapters(0..4)) is Answer.Ok)
        assertEquals(0L to 0L, spot(state(d, out)))
    }

    @Test
    fun skipMsIsBookTimeAtEverySpeed() {
        val d = device("bookplay")
        val out = peer(d)
        assertTrue(speed(d, out, 200) is Answer.Ok)
        assertTrue(skip(d, out, 30_000) is Answer.Ok)
        assertEquals(2L to 930_000L, spot(state(d, out)))
        t += 10_000
        assertEquals(950_000L, state(d, out).positionMs)
    }

    @Test
    fun speedWithNoCurrentEntryAndEndOfTrackWithNoLocalTrackAreRefused() {
        val empty = device(lib = Library(emptyList()))
        assertEquals(Err.refused, code(speed(empty, peer(empty), 125)))
        val dac = device("bookplay", "usbdac")
        val p = peer(dac)
        assertEquals(Err.refused, code(end(dac, p)))
        assertEquals(Err.refused, code(skip(dac, p, 30_000)))
        assertEquals(Err.refused, code(speed(dac, p, 125)))
        assertEquals(150, state(dac, p).speedPct)
        val rx = device("bookplay", receiver = true)
        val o = peer(rx)
        assertEquals(Err.refused, code(end(rx, o)))
        assertNull(state(rx, o).sleepEnd)
    }

    @Test
    fun withTheCardDownSkipAndSpeedAreRefusedAsSeekIs() {
        val d = device("bookplay", "card-seq=busy:60000,ready")
        val out = peer(d)
        for (a in listOf(skip(d, out, 30_000), speed(d, out, 125))) {
            val f = a as Answer.Fail
            assertEquals(Err.noCard, f.code)
            assertEquals(CardView.REFUSED, f.text)
        }
        assertEquals(2L to 900_000L, spot(state(d, out)))
    }
}
