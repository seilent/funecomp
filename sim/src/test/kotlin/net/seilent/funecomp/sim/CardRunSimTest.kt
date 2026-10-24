package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.ImgAbort
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.UpdAbort
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CardRunSimTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class Sink : Out {
        val frames = ArrayList<Pair<Int, ByteArray>>()
        override fun push(type: Int, payload: ByteArray) {
            frames += type to payload
        }

        fun of(type: Int) = frames.filter { it.first == type }.map { Node.decode(Msg.info.getValue(type).name, it.second) }

        fun states() = of(Msg.STATE).map { State.from(it) }
    }

    private var t = 1000L
    private var creep = 0L
    private val lib = Library.synthetic()
    private val logs = ArrayList<String>()

    private fun device(minor: Int = Proto.minor, updDir: File? = null, vararg faults: String) =
        Device(Options(Board.of("v1"), Faults(faults.toList()), minor = minor, updDir = updDir, log = { logs += it }), lib) { t.also { t += creep } }

    private fun linked(d: Device, out: Sink = Sink()): Sink {
        d.linked(out, 0)
        d.greet(out)
        return out
    }

    private fun req(d: Device, type: Int, payload: ByteArray, out: Out = Sink()) = d.request(type, Node.decode(Msg.info.getValue(type).name, payload), out)

    private fun code(a: Answer) = (a as? Answer.Fail)?.code

    private fun text(a: Answer) = (a as? Answer.Fail)?.text

    private fun state(d: Device) = State.from(Node.decode("STATE", d.state()))

    private fun at(d: Device, out: Sink, ms: Long) {
        val end = t + ms
        while (t < end) {
            t = minOf(end, t + 50)
            d.tick(out)
        }
    }

    private val track get() = lib.tracks.first().id

    private val requests: Map<String, (Device) -> Answer> = mapOf(
        "PLAY" to { d -> req(d, Msg.PLAY, Req.play(tracks = listOf(track))) },
        "QUEUE_OP playAt" to { d -> req(d, Msg.QUEUE_OP, Req.queueOp(Enums.QueueCmd.playAt, rev = 1, slot = 1)) },
        "QUEUE_OP page" to { d -> req(d, Msg.QUEUE_OP, Req.queueOp(Enums.QueueCmd.page, offset = 0, count = 10)) },
        "ACTION shuffleLibrary" to { d -> req(d, Msg.ACTION, Req.action(Enums.ActionCmd.shuffleLibrary)) },
        "ACTION favorite" to { d -> req(d, Msg.ACTION, Req.action(Enums.ActionCmd.favorite, track)) },
        "LYR_GET" to { d -> req(d, Msg.LYR_GET, Req.lyrGet(lib.tracks[3].id, 0, null)) },
        "LRC_PUT" to { d -> "[00:01.00]a\n".toByteArray().let { b -> req(d, Msg.LRC_PUT, Req.lrcPut(track, lib.rev, b, java.util.zip.CRC32().apply { update(b) }.value)) } },
        "IMG_BEGIN" to { d -> req(d, Msg.IMG_BEGIN, Req.imgBegin(Enums.ImgKind.artist, "A", null, 100, 1, 240, 320)) },
        "IMG_DEL" to { d -> req(d, Msg.IMG_DEL, Req.imgDel("A")) },
        "IMG_TAG" to { d -> req(d, Msg.IMG_TAG, Req.imgTag("A", Enums.ImgOrigin.library)) },
        "IMG_LIST" to { d -> req(d, Msg.IMG_LIST, Req.imgList(0, 100)) },
        "LIB_GET" to { d -> req(d, Msg.LIB_GET, Req.libGet(Enums.View.artists, "", 0, 10, lib.rev)) },
        "ART_GET" to { d -> req(d, Msg.ART_GET, Req.artGet(track, 128)) },
        "UPD_BEGIN" to { d -> req(d, Msg.UPD_BEGIN, Req.updBegin("v1", 1000, "0".repeat(32))) },
        "TRANSPORT" to { d -> req(d, Msg.TRANSPORT, Req.transport(Enums.TransportCmd.pause)) },
        "TRANSPORT repeat" to { d -> req(d, Msg.TRANSPORT, Req.transport(Enums.TransportCmd.repeat, repeat = Enums.Repeat.all)) },
        "TRANSPORT play" to { d -> req(d, Msg.TRANSPORT, Req.transport(Enums.TransportCmd.play)) },
        "TRANSPORT toggle" to { d -> req(d, Msg.TRANSPORT, Req.transport(Enums.TransportCmd.toggle)) },
        "TRANSPORT next" to { d -> req(d, Msg.TRANSPORT, Req.transport(Enums.TransportCmd.next)) },
        "TRANSPORT prev" to { d -> req(d, Msg.TRANSPORT, Req.transport(Enums.TransportCmd.prev)) },
        "TRANSPORT seek" to { d -> req(d, Msg.TRANSPORT, Req.transport(Enums.TransportCmd.seek, seekMs = 1000)) },
        "TRANSPORT shuffle" to { d -> req(d, Msg.TRANSPORT, Req.transport(Enums.TransportCmd.shuffle, on = true)) },
        "ACTION rescan" to { d -> req(d, Msg.ACTION, Req.action(Enums.ActionCmd.rescan)) },
        "LRC_DEL" to { d -> req(d, Msg.LRC_DEL, Req.lrcDel(track)) },
        "IMG_END" to { d -> req(d, Msg.IMG_END, Req.imgEnd(1)) },
        "UPD_END" to { d -> req(d, Msg.UPD_END, Req.updEnd("0".repeat(32))) },
        "LYR_OFFSET" to { d -> req(d, Msg.LYR_OFFSET, Req.lyrOffset(track)) },
        "PLAYLOG_GET" to { d -> req(d, Msg.PLAYLOG_GET, Req.playLogGet(0, 10)) },
    )

    private val landedRefuses = setOf(
        "PLAY", "QUEUE_OP playAt", "ACTION shuffleLibrary", "ACTION favorite", "ACTION rescan", "LYR_GET", "LYR_OFFSET", "LRC_PUT", "LRC_DEL",
        "IMG_BEGIN", "IMG_END", "IMG_DEL", "IMG_TAG", "UPD_BEGIN", "UPD_END",
        "TRANSPORT play", "TRANSPORT toggle", "TRANSPORT next", "TRANSPORT prev", "TRANSPORT seek", "TRANSPORT shuffle",
    )

    private fun answers(minor: Int, vararg faults: String, phase: Long): Map<String, Answer> {
        val dir = tmp.newFolder()
        return requests.mapValues { (_, r) ->
            val d = device(minor, dir, *faults)
            val out = linked(d)
            at(d, out, phase)
            r(d)
        }
    }

    @Test
    fun stateEdgesComeAtTheGivenTimesAndFreeAndTotalAreLeftOutInTheHold() {
        val d = device(15, null, "card-run=5000+3000@1000")
        val out = linked(d)
        assertEquals(Enums.Card.ready, out.states().single().card)
        at(d, out, 999)
        assertEquals(1, out.states().size)
        at(d, out, 1)
        val busy = out.states().last()
        assertEquals(Enums.Card.busy, busy.card)
        assertNull(busy.cardFreeMiB)
        assertNull(busy.cardTotalMiB)
        at(d, out, 5000)
        val index = out.states().last()
        assertEquals(Enums.Card.busy, index.card)
        assertNotNull(index.cardFreeMiB)
        assertNotNull(index.cardTotalMiB)
        at(d, out, 3000)
        assertEquals(Enums.Card.ready, out.states().last().card)
        assertEquals(listOf(Enums.Card.ready, Enums.Card.busy, Enums.Card.busy, Enums.Card.ready), out.states().map { it.card })
    }

    @Test
    fun aStateBuiltWhileTheClockCrossesTheReadyEdgeNeverRunsAheadOfTheGate() {
        val d = device(Proto.minor, null, "card-run=2000+1000@hello")
        val out = linked(d)
        at(d, out, 2950)
        creep = 100
        val card = state(d).card
        creep = 0
        assertTrue(card != Enums.Card.ready || logs.contains("card gate ready"))
    }

    @Test
    fun aStatePastTheReadyEdgeMovesTheGateBeforeItIsBuilt() {
        val d = device(Proto.minor, null, "card-run=2000+1000@hello")
        val out = linked(d)
        at(d, out, 2950)
        assertEquals(listOf("card gate busy"), logs.filter { it.startsWith("card gate ") })
        t += 100
        assertEquals(Enums.Card.ready, state(d).card)
        assertEquals(listOf("card gate busy", "card gate ready"), logs.filter { it.startsWith("card gate ") })
    }

    @Test
    fun lateStatePushesEachEdgeAfterTheGatesMove() {
        val d = device(15, null, "card-run=2000+0@1000", "late-state=500")
        val out = linked(d)
        at(d, out, 1000)
        assertEquals(Err.noCard, code(requests.getValue("PLAY")(d)))
        assertEquals(Enums.Card.ready, out.states().last().card)
        at(d, out, 499)
        assertEquals(Enums.Card.busy, out.states().last().card)
        at(d, out, 1)
        assertEquals(Enums.Card.busy, out.states().last().card)
        at(d, out, 1500)
        assertEquals(null, code(requests.getValue("PLAY")(d)))
        assertEquals(Enums.Card.busy, out.states().last().card)
        at(d, out, 500)
        assertEquals(Enums.Card.ready, out.states().last().card)
    }

    @Test
    fun aRefusalOrAStopPushesTheStateFirstAlsoWithALateState() {
        val d = device(15, null, "card-run=2000+0@1000")
        val out = linked(d)
        t += 1000
        assertEquals(Err.noCard, code(req(d, Msg.PLAY, Req.play(tracks = listOf(track)), out)))
        assertEquals(Enums.Card.busy, out.states().last().card)
        val late = device(15, null, "card-run=2000+0@1000", "late-state=500")
        val lateOut = linked(late)
        t += 1000
        assertEquals(Err.noCard, code(req(late, Msg.PLAY, Req.play(tracks = listOf(track)), lateOut)))
        assertEquals(Enums.Card.busy, lateOut.states().last().card)
        val img = device(15, null, "card-run=2000+0@img:2000")
        val imgOut = linked(img)
        pushImg(img, imgOut, 3)
        val types = imgOut.frames.map { it.first }
        assertEquals(Msg.STATE, types[types.indexOf(Msg.IMG_ABORT) - 1])
        assertEquals(Enums.Card.busy, imgOut.states().last().card)
        val lateImg = device(15, null, "card-run=2000+0@img:2000", "late-state=500")
        val lateImgOut = linked(lateImg)
        pushImg(lateImg, lateImgOut, 3)
        val lateTypes = lateImgOut.frames.map { it.first }
        assertEquals(Msg.STATE, lateTypes[lateTypes.indexOf(Msg.IMG_ABORT) - 1])
        assertEquals(Enums.Card.busy, lateImgOut.states().last().card)
    }

    @Test
    fun aCoalescedRunSendsNoBusyStateAndOneReadyStateAtItsEnd() {
        val d = device(15, null, "card-run=2000+0@1000", "card-coalesce")
        val out = linked(d)
        at(d, out, 1500)
        assertEquals(Err.noCard, code(req(d, Msg.PLAY, Req.play(tracks = listOf(track)), out)))
        assertEquals(listOf(Enums.Card.ready), out.states().map { it.card })
        at(d, out, 2000)
        assertEquals(listOf(Enums.Card.ready, Enums.Card.ready), out.states().map { it.card })
        at(d, out, 2000)
        assertEquals(2, out.states().size)
    }

    @Test
    fun theOutputHookMovesTheStateOutput() {
        val d = device()
        d.output(Enums.Output.btHeadset)
        assertEquals(Enums.Output.btHeadset, state(d).output)
    }

    @Test
    fun landedRefusesTheCardRequestsWithCode14AndAnswersTheRest() {
        for (phase in listOf(1500L, 6000L)) {
            val got = answers(15, "card-run=5000+3000@1000", phase = phase)
            for ((k, a) in got) {
                if (k in landedRefuses || k == "IMG_LIST" && phase < 5000) {
                    assertEquals("$k at $phase", Err.noCard, code(a))
                    assertEquals("$k at $phase", "card not available", text(a))
                } else {
                    assertTrue("$k at $phase ${code(a)}", code(a) != Err.noCard && code(a) != Err.busy)
                }
            }
        }
    }

    @Test
    fun landedServesTheLyricsOfTheTrackPlayingAtTheRunStartOnly() {
        val d = device(15, tmp.newFolder(), "card-run=5000+3000@1000")
        val out = linked(d)
        val playing = state(d).track!!
        at(d, out, 1500)
        val mine = code(req(d, Msg.LYR_GET, Req.lyrGet(playing, 0, null)))
        assertTrue("$mine", mine != Err.noCard && mine != Err.busy)
        val other = lib.tracks.first { it.id != playing }.id
        assertEquals(Err.noCard, code(req(d, Msg.LYR_GET, Req.lyrGet(other, 0, null))))
    }

    @Test
    fun landedOnAMinor14SessionAnswers3WithTheText() {
        val got = answers(14, "card-run=5000+3000@1000", phase = 1500)
        for ((k, a) in got) {
            assertTrue("$k", code(a) != Err.noCard)
            if (k in landedRefuses) {
                assertEquals(k, Err.busy, code(a))
                assertEquals(k, "card not available", text(a))
            }
        }
    }

    @Test
    fun oldAnswersBusyInTheHoldAndTakesPlaysInTheIndex() {
        val hold = answers(15, "card-run=5000+3000@1000", "card-device=old", phase = 1500)
        for (k in listOf("PLAY", "QUEUE_OP playAt", "QUEUE_OP page", "IMG_BEGIN", "IMG_LIST", "IMG_DEL", "IMG_TAG", "LRC_PUT", "LYR_GET", "UPD_BEGIN")) {
            assertEquals(k, Err.busy, code(hold.getValue(k)))
            assertEquals(k, "busy", text(hold.getValue(k)))
        }
        for (k in listOf("ACTION shuffleLibrary", "ACTION favorite")) assertEquals(k, "card not available", text(hold.getValue(k)))
        for (k in listOf("LIB_GET", "TRANSPORT", "TRANSPORT next", "LYR_OFFSET", "PLAYLOG_GET")) assertEquals(k, null, code(hold.getValue(k)))
        val index = answers(15, "card-run=5000+3000@1000", "card-device=old", phase = 6500)
        for (k in listOf("PLAY", "QUEUE_OP playAt", "IMG_BEGIN", "IMG_LIST", "ACTION shuffleLibrary")) assertEquals(k, null, code(index.getValue(k)))
        assertEquals(Err.busy, code(index.getValue("UPD_BEGIN")))
        assertEquals("card not available", text(index.getValue("ACTION favorite")))
        for (a in hold.values + index.values) assertTrue(code(a) != Err.noCard)
    }

    @Test
    fun anEmptyLibraryAnswersTheCardFirstOnLandedAndNoLibraryOnOld() {
        for ((model, want) in listOf("landed" to Err.noCard, "old" to Err.noLibrary)) {
            val empty = Device(Options(Board.of("v1"), Faults(listOf("card-run=5000+0@0", "card-device=$model"))), Library(emptyList())) { t }
            val out = Sink()
            empty.linked(out, 0)
            empty.greet(out)
            assertEquals(model, want, code(req(empty, Msg.ACTION, Req.action(Enums.ActionCmd.shuffleLibrary))))
        }
    }

    @Test
    fun theLagPathRefusesAnEmptyLibraryShuffleOnLandedOnly() {
        for ((model, want) in listOf("landed" to Err.noCard, "old" to null)) {
            val empty = Device(Options(Board.of("v1"), Faults(listOf("card-run=500+0@0", "play-lag=2000", "card-device=$model"))), Library(emptyList())) { t }
            val out = Sink()
            empty.linked(out, 0)
            empty.greet(out)
            at(empty, out, 600)
            assertEquals(Enums.Card.ready, state(empty).card)
            val got = code(req(empty, Msg.ACTION, Req.action(Enums.ActionCmd.shuffleLibrary)))
            if (want == null) assertEquals(model, Err.noLibrary, got) else assertEquals(model, want, got)
        }
    }

    @Test
    fun aToggleOrPlayWhilePlayingPassesAndPauseAndRepeatAlwaysPass() {
        val d = device(15, null, "card-run=5000+3000@1000")
        val out = linked(d)
        assertEquals(null, code(requests.getValue("TRANSPORT play")(d)))
        at(d, out, 1500)
        assertEquals(Enums.Card.busy, state(d).card)
        assertEquals(null, code(requests.getValue("TRANSPORT play")(d)))
        assertEquals(null, code(requests.getValue("TRANSPORT toggle")(d)))
        assertEquals(false, state(d).playing)
        assertEquals(Err.noCard, code(requests.getValue("TRANSPORT toggle")(d)))
        assertEquals(null, code(requests.getValue("TRANSPORT repeat")(d)))
        assertEquals(null, code(requests.getValue("TRANSPORT")(d)))
        assertEquals(Err.noCard, code(requests.getValue("TRANSPORT next")(d)))
    }

    @Test
    fun playLagRefusesTheCardTransportCommandsAfterTheReadyState() {
        val d = device(15, null, "card-run=1000+0@0", "play-lag=1000")
        val out = linked(d)
        at(d, out, 1000)
        assertEquals(Enums.Card.ready, out.states().last().card)
        assertEquals(Err.noCard, code(requests.getValue("TRANSPORT next")(d)))
        assertEquals(Err.noCard, code(requests.getValue("ACTION rescan")(d)))
        assertEquals(null, code(requests.getValue("TRANSPORT")(d)))
        at(d, out, 1000)
        assertEquals(null, code(requests.getValue("TRANSPORT next")(d)))
    }

    @Test
    fun anArtHoldKeepsTheStateBusyAndRefusesTheListingUntilItEnds() {
        val d = device(15, null, "card-run=1000+1000@0", "art-hold=2000")
        val out = linked(d)
        at(d, out, 500)
        assertEquals(Err.noCard, code(requests.getValue("IMG_LIST")(d)))
        at(d, out, 1000)
        assertEquals(Enums.Card.busy, state(d).card)
        assertEquals(null, code(requests.getValue("IMG_LIST")(d)))
        at(d, out, 1000)
        assertEquals(Enums.Card.busy, state(d).card)
        assertEquals(Err.noCard, code(requests.getValue("IMG_LIST")(d)))
        assertEquals(Err.noCard, code(requests.getValue("IMG_BEGIN")(d)))
        at(d, out, 2000)
        assertEquals(Enums.Card.ready, out.states().last().card)
        assertEquals(null, code(requests.getValue("IMG_LIST")(d)))
    }

    @Test
    fun anEndStartRefusesTheImageEndWithNoAbort() {
        val d = device(15, null, "card-run=2000+1000@end")
        val out = linked(d)
        val bytes = ByteArray(2000)
        val crc = java.util.zip.CRC32().apply { update(bytes) }.value
        assertEquals(null, code(req(d, Msg.IMG_BEGIN, Req.imgBegin(Enums.ImgKind.artist, "A", null, 2000, crc, 240, 320), out)))
        d.imgData(Node.decode("IMG_DATA", Req.imgData(0, bytes)), out)
        val end = req(d, Msg.IMG_END, Req.imgEnd(crc), out)
        assertEquals(Err.noCard, code(end))
        assertTrue(out.of(Msg.IMG_ABORT).isEmpty())
        assertEquals(Enums.Card.busy, out.states().last().card)
    }

    @Test
    fun anEndStartRefusesTheUpdateEndAndKeepsThePart() {
        val dir = tmp.newFolder()
        val d = device(15, dir, "card-run=2000+1000@end")
        val out = linked(d)
        assertEquals(null, code(req(d, Msg.UPD_BEGIN, Req.updBegin("v1", 2000, "0".repeat(32)), out)))
        for (i in 0 until 2) d.updData(Node.decode("UPD_DATA", Req.updData(i * 1000L, ByteArray(1000))), out)
        assertEquals(Err.noCard, code(req(d, Msg.UPD_END, Req.updEnd("0".repeat(32)), out)))
        assertTrue(out.of(Msg.UPD_ABORT).isEmpty())
        assertEquals(2000L, File(dir, "v1.upt.part").length())
        at(d, out, 3000)
        assertEquals(Enums.Card.ready, out.states().last().card)
        assertEquals(null, code(req(d, Msg.UPD_BEGIN, Req.updBegin("v1", 2000, "0".repeat(32)), out)))
    }

    @Test
    fun aLateSnapshotMovesTheRevsAfterTheReadyState() {
        val d = device(15, null, "card-run=1000+1000@0", "snap-late=3000")
        val out = linked(d)
        val before = state(d)
        at(d, out, 2500)
        assertEquals(Enums.Card.ready, out.states().last().card)
        assertEquals(before.snapRev, out.states().last().snapRev)
        at(d, out, 3000)
        val after = out.states().last()
        assertEquals(before.snapRev!! + 1, after.snapRev)
        assertEquals(before.libRev!! + 1, after.libRev)
        assertTrue(logs.any { it.startsWith("snap late") })
    }

    private fun pushImg(d: Device, out: Sink, chunks: Int) {
        val name = "A"
        req(d, Msg.IMG_BEGIN, Req.imgBegin(Enums.ImgKind.artist, name, null, 64_000, 1, 240, 320), out)
        for (i in 0 until chunks) d.imgData(Node.decode("IMG_DATA", Req.imgData(i * 1000L, ByteArray(1000))), out)
    }

    @Test
    fun anImgStartStopsThePushAtTheByteOffset() {
        for ((minor, abort) in listOf(15 to Enums.AbortCode.noCard, 14 to Enums.AbortCode.busy)) {
            val d = device(minor, null, "card-run=2000+1000@img:3000")
            val out = linked(d)
            pushImg(d, out, 5)
            val a = ImgAbort.from(out.of(Msg.IMG_ABORT).single())
            assertEquals("minor $minor", abort, a.code)
            assertEquals(3000L, a.offset)
            assertEquals(Enums.Card.busy, state(d).card)
            at(d, out, 3000)
            assertEquals(Enums.Card.ready, out.states().last().card)
        }
        val old = device(15, null, "card-run=2000+1000@img:3000", "card-device=old")
        val out = linked(old)
        pushImg(old, out, 5)
        assertEquals(Enums.AbortCode.busy, ImgAbort.from(out.of(Msg.IMG_ABORT).single()).code)
    }

    @Test
    fun anUpdStartStopsThePushAndKeepsThePart() {
        for ((minor, abort) in listOf(15 to Enums.AbortCode.noCard, 14 to Enums.AbortCode.busy)) {
            val dir = tmp.newFolder()
            val d = device(minor, dir, "card-run=2000+1000@upd:3000")
            val out = linked(d)
            assertEquals(null, code(req(d, Msg.UPD_BEGIN, Req.updBegin("v1", 10_000, "0".repeat(32)), out)))
            for (i in 0 until 5) d.updData(Node.decode("UPD_DATA", Req.updData(i * 1000L, ByteArray(1000))), out)
            val a = UpdAbort.from(out.of(Msg.UPD_ABORT).single())
            assertEquals("minor $minor", abort, a.code)
            assertEquals(3000L, a.offset)
            assertEquals(3000L, File(dir, "v1.upt.part").length())
            assertEquals(if (minor >= 15) Err.noCard else Err.busy, code(req(d, Msg.UPD_BEGIN, Req.updBegin("v1", 10_000, "0".repeat(32)), out)))
            at(d, out, 3000)
            val again = req(d, Msg.UPD_BEGIN, Req.updBegin("v1", 10_000, "0".repeat(32)), out)
            assertEquals(null, code(again))
        }
    }

    @Test
    fun aTimedStartStopsAPushInFlight() {
        val d = device(15, null, "card-run=2000+1000@1000")
        val out = linked(d)
        pushImg(d, out, 2)
        at(d, out, 1000)
        assertEquals(Enums.AbortCode.noCard, ImgAbort.from(out.of(Msg.IMG_ABORT).single()).code)
    }

    @Test
    fun helloStartGivesABusyFirstStateAndASecondConnectionAfterTheRunSeesReady() {
        val d = device(15, null, "card-run=1000+1000@hello")
        val out = linked(d)
        assertEquals(Enums.Card.busy, out.states().single().card)
        at(d, out, 2000)
        assertEquals(Enums.Card.ready, out.states().last().card)
        d.unlinked(out)
        val second = linked(d)
        assertEquals(Enums.Card.ready, second.states().single().card)
        at(d, second, 1000)
        assertEquals(1, second.states().size)
    }

    @Test
    fun theRunHappensOncePerSimStart() {
        val d = device(15, null, "card-run=500+500@0")
        val out = linked(d)
        at(d, out, 1000)
        d.unlinked(out)
        val second = linked(d)
        at(d, second, 2000)
        assertEquals(listOf(Enums.Card.ready), second.states().map { it.card })
    }

    @Test
    fun minor13SendsNoCard() {
        val d = device(13, null, "card-run=1000+1000@0")
        assertNull(state(d).card)
        assertNull(device(13).let { state(it).card })
        assertEquals(Enums.Card.ready, state(device(14)).card)
    }

    @Test
    fun aSequenceWalksEveryValue() {
        val d = device(15, null, "card-seq=busy:2000,out:2000,busy:2000,ready")
        val out = linked(d)
        assertEquals(Enums.Card.busy, out.states().single().card)
        at(d, out, 2000)
        val outState = out.states().last()
        assertEquals(Enums.Card.out, outState.card)
        assertNull(outState.cardFreeMiB)
        assertEquals(Err.noCard, code(requests.getValue("PLAY")(d)))
        at(d, out, 4000)
        assertEquals(listOf(Enums.Card.busy, Enums.Card.out, Enums.Card.busy, Enums.Card.ready), out.states().map { it.card })
        assertNotNull(out.states().last().cardFreeMiB)
        assertEquals(null, code(requests.getValue("PLAY")(d)))
    }

    @Test
    fun playLagKeepsRefusingPlaysAfterTheReadyState() {
        val d = device(15, null, "card-run=1000+0@0", "play-lag=1000")
        val out = linked(d)
        at(d, out, 1000)
        assertEquals(Enums.Card.ready, out.states().last().card)
        assertEquals(Err.noCard, code(req(d, Msg.PLAY, Req.play(tracks = listOf(track)), out)))
        assertEquals(Enums.Card.busy, out.states().last().card)
        assertEquals(Err.noCard, code(requests.getValue("ACTION shuffleLibrary")(d)))
        assertEquals(null, code(requests.getValue("LYR_OFFSET")(d)))
        assertEquals(null, code(requests.getValue("IMG_LIST")(d)))
        at(d, out, 1000)
        assertEquals(Enums.Card.ready, out.states().last().card)
        assertEquals(null, code(requests.getValue("PLAY")(d)))
    }

    @Test
    fun cardPageRefusesTheQueuePageOnALandedRunOnly() {
        val page = requests.getValue("QUEUE_OP page")
        val d = device(15, null, "card-run=1000+1000@0", "card-page")
        val out = linked(d)
        at(d, out, 500)
        assertEquals(Err.noCard, code(page(d)))
        at(d, out, 2500)
        assertEquals(null, code(page(d)))
        val plain = device(15, null, "card-run=1000+1000@0")
        at(plain, linked(plain), 500)
        assertEquals(null, code(page(plain)))
    }

    @Test
    fun playDropAnswersOkAndStartsNothingInTheOldIndex() {
        val d = device(15, null, "card-run=1000+3000@0", "card-device=old", "play-drop")
        val out = linked(d)
        at(d, out, 1500)
        val before = state(d)
        val other = lib.tracks.last().id
        assertEquals(null, code(req(d, Msg.PLAY, Req.play(tracks = listOf(other)))))
        val after = state(d)
        assertEquals(before.track, after.track)
        assertEquals(before.queueRev, after.queueRev)
        at(d, out, 3000)
        assertEquals(null, code(req(d, Msg.PLAY, Req.play(tracks = listOf(other)))))
        assertEquals(other, state(d).track)
    }

    @Test
    fun faultsParse() {
        val f = Faults(listOf("card-seq=busy:2000,out:2000,busy:2000,ready,play-drop,late-state=500", "card-device=old,play-lag=1000"))
        assertEquals(listOf(Enums.Card.busy to 2000L, Enums.Card.out to 2000L, Enums.Card.busy to 2000L, Enums.Card.ready to 0L), f.cardSeq)
        assertEquals(500L, f.lateState)
        assertEquals(1000L, f.playLag)
        assertTrue(f.cardOld && f.playDrop)
        val r = Faults(listOf("card-run=5000+3000@img:4096")).cardRun!!
        assertEquals(5000L, r.holdMs)
        assertEquals(3000L, r.indexMs)
        assertEquals(4096L, (r.start as CardRun.Start.Img).bytes)
        assertTrue(Faults(listOf("card-run=1+2@hello")).cardRun!!.start == CardRun.Start.Hello)
        assertTrue(Faults(listOf("card-run=1+2@play")).cardRun!!.start == CardRun.Start.Play)
        assertTrue(Faults(listOf("card-run=1+2@manual")).cardRun!!.start == CardRun.Start.Manual)
        assertTrue(Faults(listOf("card-run=1+2@queue")).cardRun!!.start == CardRun.Start.Queue)
        assertEquals(7L, (Faults(listOf("card-run=1+2@upd:7")).cardRun!!.start as CardRun.Start.Upd).bytes)
        assertEquals(0L, (Faults(listOf("card-run=1+2")).cardRun!!.start as CardRun.Start.Delay).ms)
        assertTrue(Faults(listOf("card-run=1+2@end")).cardRun!!.start == CardRun.Start.End)
        val f2 = Faults(listOf("card-run=1+2,art-hold=300,snap-late=400"))
        assertEquals(300L, f2.artHold)
        assertEquals(400L, f2.snapLate)
        for (bad in listOf("card-run=5000", "card-run=a+1", "card-seq=nope:1,ready", "card-seq=busy,ready", "card-device=new", "card-run=1+1,card-seq=ready")) {
            try {
                Faults(listOf(bad))
                throw AssertionError("$bad parsed")
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}
