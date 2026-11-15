package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.BindAskReply
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.Lib
import net.seilent.funecomp.proto.LibDeleteReply
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Queue
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Feature
import net.seilent.funecomp.proto.gen.Msg
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibDeleteSimTest {
    private class Sink : Out {
        val frames = ArrayList<Pair<Int, ByteArray>>()
        override fun push(type: Int, payload: ByteArray) {
            frames += type to payload
        }

        fun of(type: Int) = frames.filter { it.first == type }.map { Node.decode(Msg.info.getValue(type).name, it.second) }
    }

    private var t = 1000L
    private val lib = Library.synthetic()
    private val album = lib.tracks.filter { it.album == lib.tracks[0].album && it.albumArtist == lib.tracks[0].albumArtist }
    private val first = album[0].id
    private val song = lib.tracks.last().id
    private val noBit = Groups.all and Groups.mask("libDelete").inv()

    private fun device(vararg faults: String, minor: Int? = null, bootId: Long = Options.BOOT_ID) =
        Device(if (minor == null) Options(Board.of("v1"), Faults(faults.toList()), bootId = bootId) else Options(Board.of("v1"), Faults(faults.toList()), minor = minor, bootId = bootId), Library.synthetic()) { t }

    private fun link(d: Device, features: Long = Groups.all, app: String = "phone") = Sink().also { d.linked(it, features, app) }

    private fun window(d: Device, out: Out): ByteArray = d.helloReply(out).bindToken!!

    private fun req(d: Device, type: Int, payload: ByteArray, out: Out) = d.request(type, Node.decode(Msg.info.getValue(type).name, payload), out)

    private fun snap(d: Device) = d.helloReply().snapRev

    private fun songAsk(d: Device, token: ByteArray?, track: Long = song, snapRev: Long? = snap(d), bootId: Long? = Options.BOOT_ID) =
        Req.libDelete(track, null, null, snapRev, token, bootId)

    private fun albumAsk(d: Device, token: ByteArray?, count: Long? = album.size.toLong(), bootId: Long? = Options.BOOT_ID) =
        Req.libDelete(null, first, count, snap(d), token, bootId)

    private fun fail(a: Answer): Pair<Int, String> = (a as Answer.Fail).let { it.code to it.text }

    private fun later(a: Answer) = a as Answer.Later

    private fun reply(d: Device, a: Answer) = LibDeleteReply.from(Node.decode("LIB_DELETE.reply", (d.resolve(later(a)) as Answer.Ok).payload))

    private fun bound(vararg faults: String): Triple<Device, Sink, ByteArray> {
        val d = device("bind-window", *faults)
        val out = link(d)
        return Triple(d, out, window(d, out))
    }

    private fun queue(d: Device, out: Sink): List<Long> {
        out.frames.clear()
        req(d, Msg.QUEUE_OP, Req.queueOp(Enums.QueueCmd.page, offset = 0, count = 500), out)
        return out.of(Msg.QUEUE).flatMap { Queue.from(it).items.mapNotNull { i -> i.track } }
    }

    @Test
    fun aRefusedPeerIsRefusedBeforeTheToken() {
        val (d, _, token) = bound()
        val other = link(d, app = "other")
        assertEquals(Err.refused to "refused", fail(req(d, Msg.LIB_DELETE, songAsk(d, token), other)))
        assertEquals(Err.refused to "refused", fail(req(d, Msg.BIND_ASK, Req.bindAsk(), other)))
        val (r, out, rt) = bound("refuse=LIB_DELETE")
        assertEquals(Err.refused to "refused", fail(req(r, Msg.LIB_DELETE, songAsk(r, rt), out)))
    }

    @Test
    fun theTokenIsCheckedBeforeEverythingElse() {
        val d = device()
        val out = link(d)
        assertEquals(Err.refused to "not allowed", fail(req(d, Msg.LIB_DELETE, songAsk(d, ByteArray(16)), out)))
        val (b, o, token) = bound()
        assertEquals(Err.refused to "not allowed", fail(req(b, Msg.LIB_DELETE, songAsk(b, null), o)))
        assertEquals(Err.badValue, fail(req(b, Msg.LIB_DELETE, songAsk(b, ByteArray(15)), o)).first)
        assertEquals(Err.badValue, fail(req(b, Msg.LIB_DELETE, songAsk(b, ByteArray(17)), o)).first)
        assertEquals(Err.refused to "not allowed", fail(req(b, Msg.LIB_DELETE, songAsk(b, token.copyOf().also { it[0] = (it[0] + 1).toByte() }), o)))
        assertEquals(Err.refused to "not allowed", fail(req(b, Msg.LIB_DELETE, songAsk(b, null, snapRev = null), o)))
    }

    @Test
    fun aTokenlessDeleteWhileAnotherWaitsAnswersNotAllowed() {
        val (d, out, token) = bound()
        val held = req(d, Msg.LIB_DELETE, songAsk(d, token), out)
        assertTrue(held is Answer.Later)
        assertEquals(Err.refused to "not allowed", fail(req(d, Msg.LIB_DELETE, albumAsk(d, null), out)))
        assertEquals(Err.busy to "delete waiting", fail(req(d, Msg.LIB_DELETE, albumAsk(d, token), out)))
        reply(d, held)
        assertTrue(req(d, Msg.LIB_DELETE, albumAsk(d, token), out) is Answer.Later)
    }

    @Test
    fun aTokenlessDeleteUnderACardFaultAnswersNotAllowedAndOneWithTheTokenNoCard() {
        val (d, out, token) = bound("card-seq=busy:5000,ready")
        d.startCard()
        assertEquals(Err.refused to "not allowed", fail(req(d, Msg.LIB_DELETE, songAsk(d, null), out)))
        assertEquals(Err.noCard to "card not available", fail(req(d, Msg.LIB_DELETE, songAsk(d, token), out)))
        assertEquals(Err.noCard to "card not available", fail(req(d, Msg.LIB_DELETE, Req.libDelete(null, null, null, null, token, null), out)))
        t += 6000
        assertTrue(req(d, Msg.LIB_DELETE, songAsk(d, token), out) is Answer.Later)
    }

    @Test
    fun theFieldsThenTheBootThenTheSnapThenTheId() {
        val (d, out, token) = bound()
        val s = snap(d)!!
        assertEquals(Err.badValue, fail(req(d, Msg.LIB_DELETE, Req.libDelete(song, null, null, null, token, Options.BOOT_ID), out)).first)
        assertEquals(Err.badValue, fail(req(d, Msg.LIB_DELETE, Req.libDelete(song, null, null, s, token, null), out)).first)
        assertEquals(Err.badValue, fail(req(d, Msg.LIB_DELETE, Req.libDelete(song, first, 5, s, token, Options.BOOT_ID), out)).first)
        assertEquals(Err.badValue, fail(req(d, Msg.LIB_DELETE, Req.libDelete(null, null, null, s, token, Options.BOOT_ID), out)).first)
        assertEquals(Err.badValue, fail(req(d, Msg.LIB_DELETE, albumAsk(d, token, count = null), out)).first)
        assertEquals(Err.staleRev to "library changed", fail(req(d, Msg.LIB_DELETE, Req.libDelete(12345, null, null, s, token, Options.BOOT_ID + 1), out)))
        assertEquals(Err.staleRev to "library changed", fail(req(d, Msg.LIB_DELETE, songAsk(d, token, snapRev = s + 1), out)))
        assertEquals(Err.staleRev to "library changed", fail(req(d, Msg.LIB_DELETE, songAsk(d, token, track = 12345, snapRev = s + 1), out)))
        assertEquals(Err.notFound, fail(req(d, Msg.LIB_DELETE, songAsk(d, token, track = 12345), out)).first)
        assertEquals(Err.notFound, fail(req(d, Msg.LIB_DELETE, Req.libDelete(null, album[1].id, album.size.toLong(), s, token, Options.BOOT_ID), out)).first)
    }

    @Test
    fun theDeviceChecksThenTheRunChecks() {
        val (c, co, ct) = bound("del-changed", "del-readonly")
        assertEquals(Err.staleRev to "library changed", fail(req(c, Msg.LIB_DELETE, albumAsk(c, ct, count = 99), co)))
        val (r, ro, rt) = bound("del-readonly")
        assertEquals(Err.readOnly to "card is read only", fail(req(r, Msg.LIB_DELETE, albumAsk(r, rt, count = 99), ro)))
        val (n, no, nt) = bound()
        assertEquals(Err.staleRev to "count changed", fail(req(n, Msg.LIB_DELETE, albumAsk(n, nt, count = 99), no)))
        val (q, qo, qt) = bound("del-cue=${java.lang.Long.toUnsignedString(album[2].id)}")
        assertEquals(Err.refused to "part of a cue image", fail(req(q, Msg.LIB_DELETE, albumAsk(q, qt), qo)))
        assertTrue(req(q, Msg.LIB_DELETE, songAsk(q, qt), qo) is Answer.Later)
        val (p, po, pt) = bound("del-path")
        assertEquals(Err.refused to "refused", fail(req(p, Msg.LIB_DELETE, songAsk(p, pt), po)))
    }

    @Test
    fun anAcceptedAlbumLeavesTheQueueAtOnceRepliesLaterThenTheRescanMovesTheSnap() {
        val (d, out, token) = bound("del-failed=1")
        val before = State.from(Node.decode("STATE", d.state(out = out)))
        val queued = queue(d, out)
        assertTrue(album.all { it.id in queued })
        out.frames.clear()
        val a = req(d, Msg.LIB_DELETE, albumAsk(d, token), out)
        assertEquals(200L, later(a).ms)
        val pushed = State.from(out.of(Msg.STATE).single())
        assertEquals(before.queueRev!! + 1, pushed.queueRev)
        assertEquals(before.slots!! - album.size, pushed.slots)
        val left = queue(d, out)
        assertTrue(album.none { it.id in left })
        assertEquals(LibDeleteReply(album.size.toLong() - 1, 1), reply(d, a))
        out.frames.clear()
        t += 1999
        d.tick(out)
        assertTrue(out.of(Msg.STATE).isEmpty())
        t += 1
        d.tick(out)
        val moved = State.from(out.of(Msg.STATE).single())
        assertEquals(before.snapRev!! + 1, moved.snapRev)
        assertEquals(before.libRev!! + 1, moved.libRev)
        assertEquals(pushed.queueRev, moved.queueRev)
        assertEquals(left, queue(d, out))
        assertEquals(setOf(album.last().id), album.map { it.id }.filter { it in d.lib.byId }.toSet())
    }

    @Test
    fun anAlbumDeleteUnderTheBookFaultKeepsTheBookAndItsQueuedChapters() {
        val (d, out, token) = bound("bookplay")
        val chapters = (0..4).map { Library.BOOK_FIRST + it }
        assertEquals(LibDeleteReply(album.size.toLong(), 0), reply(d, req(d, Msg.LIB_DELETE, albumAsk(d, token), out)))
        t += 2000
        d.tick(out)
        assertTrue(album.none { it.id in d.lib.byId })
        assertTrue(chapters.all { d.lib.byId[it]?.book == true })
        out.frames.clear()
        req(d, Msg.QUEUE_OP, Req.queueOp(Enums.QueueCmd.page, offset = 0, count = 500), out)
        val items = out.of(Msg.QUEUE).flatMap { Queue.from(it).items }
        assertEquals(chapters, items.map { it.track })
        assertTrue(items.all { it.book == true && it.title != null })
        val s = State.from(Node.decode("STATE", d.state(out = out)))
        assertEquals(Library.BOOK_FIRST + 2 to true, s.track to s.book)
        assertEquals("Chapter 3", s.title)
    }

    @Test
    fun aSongWithAFailureOwesNoRescanAndOneWithoutDoes() {
        val (d, out, token) = bound("del-failed=1", "del-ms=50", "del-rescan-ms=10")
        val a = req(d, Msg.LIB_DELETE, songAsk(d, token), out)
        assertEquals(50L, later(a).ms)
        assertEquals(LibDeleteReply(0, 1), reply(d, a))
        val s = snap(d)
        t += 100
        d.tick(out)
        assertEquals(s, snap(d))
        assertTrue(song in d.lib.byId)
        val (e, eo, et) = bound()
        val b = req(e, Msg.LIB_DELETE, songAsk(e, et), eo)
        assertEquals(LibDeleteReply(1, 0), reply(e, b))
        t += 2000
        e.tick(eo)
        assertFalse(song in e.lib.byId)
    }

    @Test
    fun theBootIdGoesOnlyToAnAppWithTheBitAndMovesAtARestart() {
        val d = device()
        assertEquals(Options.BOOT_ID, d.helloReply(link(d)).bootId)
        assertTrue(Options.BOOT_ID < 0)
        assertNull(d.helloReply(link(d, noBit)).bootId)
        assertNull(d.helloReply().bootId)
        val set = device(bootId = 77)
        val out = link(set)
        assertEquals(77L, set.helloReply(out).bootId)
        set.restart()
        assertEquals(78L, set.helloReply(out).bootId)
    }

    @Test
    fun theWindowTokenGoesOnceToTheBoundPeerWithBothBits() {
        val d = device("bind-window")
        val other = Sink().also { d.linked(it, Groups.all, "phone") }
        assertNull(d.helloReply(link(d, app = "other")).bindToken)
        assertNull(d.helloReply(link(d, noBit)).bindToken)
        val token = d.helloReply(other).bindToken
        assertEquals(16, token!!.size)
        assertArrayEquals(token, d.bindToken)
        assertNull(d.helloReply(other).bindToken)
        val plain = device()
        assertNull(plain.helloReply(link(plain)).bindToken)
        val off = device("bind-window", "features=$noBit")
        assertNull(off.helloReply(link(off)).bindToken)
    }

    @Test
    fun bindAskAllowGivesANewTokenThatDeletes() {
        val d = device()
        val out = link(d)
        val a = req(d, Msg.BIND_ASK, Req.bindAsk(), out)
        assertEquals(300L, later(a).ms)
        assertEquals(Err.busy to "busy", fail(req(d, Msg.BIND_ASK, Req.bindAsk(), out)))
        val token = BindAskReply.from(Node.decode("BIND_ASK.reply", (d.resolve(later(a)) as Answer.Ok).payload)).bindToken!!
        assertEquals(16, token.size)
        assertArrayEquals(token, d.bindToken)
        assertTrue(req(d, Msg.LIB_DELETE, songAsk(d, token), out) is Answer.Later)
    }

    @Test
    fun bindAskAllowFailDropsTheOldToken() {
        val (d, out, token) = bound("bind=allowfail")
        assertEquals(Err.busy to "busy", fail(d.resolve(later(req(d, Msg.BIND_ASK, Req.bindAsk(), out)))))
        assertNull(d.bindToken)
        assertEquals(Err.refused to "not allowed", fail(req(d, Msg.LIB_DELETE, songAsk(d, token), out)))
    }

    @Test
    fun bindAskKeepDeclinesAndTheCoolDownDoubles() {
        val d = device("bind=keep")
        val out = link(d)
        assertEquals(Err.refused to "declined", fail(d.resolve(later(req(d, Msg.BIND_ASK, Req.bindAsk(), out)))))
        assertEquals(Err.refused to "declined", fail(req(d, Msg.BIND_ASK, Req.bindAsk(), out)))
        t += 60_000
        assertEquals(Err.refused to "declined", fail(d.resolve(later(req(d, Msg.BIND_ASK, Req.bindAsk(), out)))))
        t += 60_000
        assertEquals(Err.refused to "declined", fail(req(d, Msg.BIND_ASK, Req.bindAsk(), out)))
        t += 60_000
        assertTrue(req(d, Msg.BIND_ASK, Req.bindAsk(), out) is Answer.Later)
        assertNull(d.bindToken)
    }

    @Test
    fun bindAskFaultsAnswerAtOnce() {
        for ((fault, answer) in listOf(
            "screen" to (Err.busy to "screen off"),
            "busy" to (Err.busy to "busy"),
            "cool" to (Err.refused to "declined"),
            "refused" to (Err.refused to "refused"),
        )) {
            val d = device("bind=$fault")
            assertEquals(fault, answer, fail(req(d, Msg.BIND_ASK, Req.bindAsk(), link(d))))
        }
        val none = Device(Options(Board.of("v1"), bind = false), Library.synthetic()) { t }
        assertEquals(Err.refused to "refused", fail(req(none, Msg.BIND_ASK, Req.bindAsk(), link(none))))
    }

    @Test
    fun aSessionCloseDuringTheAskDeclinesAndStartsTheCoolDown() {
        val d = device()
        val out = link(d)
        val a = req(d, Msg.BIND_ASK, Req.bindAsk(), out)
        d.unlinked(out)
        assertEquals(Err.refused to "declined", fail(d.resolve(later(a))))
        assertNull(d.bindToken)
        val again = link(d)
        assertEquals(Err.refused to "declined", fail(req(d, Msg.BIND_ASK, Req.bindAsk(), again)))
        t += 60_000
        assertTrue(req(d, Msg.BIND_ASK, Req.bindAsk(), again) is Answer.Later)
    }

    @Test
    fun anOpenAskAnswersBusyBeforeTheScreenCheck() {
        val f = Faults(emptyList())
        val d = Device(Options(Board.of("v1"), f), Library.synthetic()) { t }
        val out = link(d)
        assertTrue(req(d, Msg.BIND_ASK, Req.bindAsk(), out) is Answer.Later)
        f.bindAnswer = "screen"
        assertEquals(Err.busy to "busy", fail(req(d, Msg.BIND_ASK, Req.bindAsk(), out)))
    }

    @Test
    fun anAllowResetsTheCoolDown() {
        val f = Faults(listOf("bind=keep"))
        val d = Device(Options(Board.of("v1"), f), Library.synthetic()) { t }
        val out = link(d)
        assertEquals(Err.refused to "declined", fail(d.resolve(later(req(d, Msg.BIND_ASK, Req.bindAsk(), out)))))
        t += 60_000
        f.bindAnswer = "allow"
        assertTrue(d.resolve(later(req(d, Msg.BIND_ASK, Req.bindAsk(), out))) is Answer.Ok)
        f.bindAnswer = "keep"
        assertEquals(Err.refused to "declined", fail(d.resolve(later(req(d, Msg.BIND_ASK, Req.bindAsk(), out)))))
        t += 60_000
        assertTrue(req(d, Msg.BIND_ASK, Req.bindAsk(), out) is Answer.Later)
    }

    @Test
    fun bothDeferredAnswersFallDueOnTheSimClock() {
        val (d, out, token) = bound("del-ms=500", "bind-ms=700")
        val del = later(req(d, Msg.LIB_DELETE, songAsk(d, token), out))
        val ask = later(req(d, Msg.BIND_ASK, Req.bindAsk(), out))
        assertEquals(t + 500, del.at)
        assertEquals(t + 700, ask.at)
        assertFalse(d.due(del))
        t += 500
        assertTrue(d.due(del))
        assertFalse(d.due(ask))
        t += 200
        assertTrue(d.due(ask))
    }

    @Test
    fun aNoDeleteAlbumIsRefusedAtThePathCheck() {
        val (d, out, token) = bound("no-delete=${java.lang.Long.toUnsignedString(first)}")
        assertEquals(Err.refused to "refused", fail(req(d, Msg.LIB_DELETE, albumAsk(d, token), out)))
        assertTrue(req(d, Msg.LIB_DELETE, songAsk(d, token), out) is Answer.Later)
    }

    @Test
    fun anEmptyLibraryAnswersNotFound() {
        val d = Device(Options(Board.of("v1"), Faults(listOf("bind-window"))), Library(emptyList())) { t }
        val out = link(d)
        assertEquals(Err.notFound to "not found", fail(req(d, Msg.LIB_DELETE, songAsk(d, window(d, out)), out)))
    }

    @Test
    fun aRebindDropsTheToken() {
        val (d, _, _) = bound()
        assertNotNull(d.bindToken)
        d.rebind("other")
        assertNull(d.bindToken)
    }

    @Test
    fun aRestartClearsTheWaitTheRescanAndTheCoolDown() {
        val (d, out, token) = bound("bind=keep")
        assertEquals(1L, reply(d, req(d, Msg.LIB_DELETE, songAsk(d, token), out)).deleted)
        val del = req(d, Msg.LIB_DELETE, songAsk(d, token), out)
        assertEquals(Err.refused to "declined", fail(d.resolve(later(req(d, Msg.BIND_ASK, Req.bindAsk(), out)))))
        val before = snap(d)
        d.restart()
        assertEquals(Err.staleRev to "library changed", fail(d.resolve(later(del))))
        t += 5_000
        d.tick(out)
        assertEquals(before, snap(d))
        assertTrue(req(d, Msg.BIND_ASK, Req.bindAsk(), out) is Answer.Later)
        assertTrue(req(d, Msg.LIB_DELETE, songAsk(d, token, bootId = Options.BOOT_ID + 1), out) is Answer.Later)
    }

    @Test
    fun noDeleteGoesOnAlbumRowsToABit41PeerOnly() {
        val d = device("no-delete=${java.lang.Long.toUnsignedString(first)}")
        fun rows(features: Long, view: Int, parent: String = ""): List<net.seilent.funecomp.proto.LibRow> {
            val out = link(d, features)
            req(d, Msg.LIB_GET, Req.libGet(view, parent, 0, 100, null, snap(d)), out)
            return out.of(Msg.LIB).flatMap { Lib.from(it).rows }
        }
        val albums = rows(Groups.all, Enums.View.albums)
        assertEquals(true, albums.single { it.first == first }.noDelete)
        assertTrue(albums.filter { it.first != first }.all { it.noDelete == null })
        assertEquals(true, rows(Groups.all, Enums.View.artistAlbums, album[0].artists[0]).single { it.first == first }.noDelete)
        assertTrue(rows(noBit, Enums.View.albums).all { it.noDelete == null })
        assertTrue(rows(Groups.all, Enums.View.songs).all { it.noDelete == null })
    }

    @Test
    fun albumRowsCarryTheAlbumArtist() {
        fun rows(d: Device, view: Int, parent: String = ""): List<net.seilent.funecomp.proto.LibRow> {
            val out = link(d)
            req(d, Msg.LIB_GET, Req.libGet(view, parent, 0, 100, null, snap(d)), out)
            return out.of(Msg.LIB).flatMap { Lib.from(it).rows }
        }
        val d = device()
        assertEquals(album[0].albumArtist, rows(d, Enums.View.albums).single { it.first == first }.artist)
        assertEquals(album[0].albumArtist, rows(d, Enums.View.artistAlbums, album[0].artists[0]).single { it.first == first }.artist)
        assertTrue(rows(d, Enums.View.albums).all { it.artist != null })
        assertEquals("Various Artists", rows(d, Enums.View.albums).single { it.name == "Black Panther" }.artist)
    }

    @Test
    fun minor17ServesNoBit41() {
        val d = device("bind-window", minor = 17)
        val out = link(d)
        val h = d.helloReply(out)
        assertEquals(0L, h.features and (1L shl Feature.libDelete))
        assertNull(h.bootId)
        assertNull(h.bindToken)
        assertEquals(1L shl Feature.libDelete, device().helloReply().features and (1L shl Feature.libDelete))
    }
}
