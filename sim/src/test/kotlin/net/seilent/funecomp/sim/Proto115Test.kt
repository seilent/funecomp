package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.Lib
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.QueueOpReply
import net.seilent.funecomp.proto.Queue
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class Proto115Test {
    @get:Rule
    val tmp = TemporaryFolder()

    private class Sink : Out {
        val frames = ArrayList<Pair<Int, ByteArray>>()
        override fun push(type: Int, payload: ByteArray) {
            frames += type to payload
        }

        fun libs() = frames.filter { it.first == Msg.LIB }.map { Lib.from(Node.decode("LIB", it.second)) }

        fun queues() = frames.filter { it.first == Msg.QUEUE }.map { Queue.from(Node.decode("QUEUE", it.second)) }
    }

    private val lib by lazy {
        val f = File(tmp.root, "lib.tsv")
        f.writeText(
            listOf(
                "Radiohead\tOK Computer\tAirbag\t284000\t\t\tRock",
                "Radiohead\tOK Computer\tLucky\t259000\t\t\tRock",
                "Utada Hikaru\tFirst Love\tAutomatic\t320000\t\t\tJ-Pop",
                "Ichiko Aoba\tWindswept Adan\tPorcelain\t240000\t\t\trock",
                "Ichiko Aoba\tWindswept Adan\tPrologue\t200000",
                "Sigur Rós\tÁgætis byrjun\tStarálfur\t407000\t\t\tAmbient",
            ).joinToString("\n"),
        )
        Library.load(f)
    }

    private fun device(minor: Int = Proto.minor) = Device(Options(Board.of("v1"), minor = minor), lib) { 1000L }

    private fun ok(a: Answer) = (a as? Answer.Ok ?: throw AssertionError("failed: ${(a as Answer.Fail).code} ${a.text}")).payload

    private fun code(a: Answer) = (a as Answer.Fail).code

    private fun libGet(d: Device, parent: String, out: Sink = Sink()) = d.request(Msg.LIB_GET, Node.decode("LIB_GET", Req.libGet(Enums.View.genres, parent, 0, 50, lib.rev)), out)

    private fun queueOp(d: Device, payload: ByteArray) = d.request(Msg.QUEUE_OP, Node.decode("QUEUE_OP", payload), Sink())

    private fun add(d: Device, parent: String, rev: Long? = null, view: Int = Enums.View.genres) =
        queueOp(d, Req.queueOp(Enums.QueueCmd.add, rev, view = view, parent = parent, libRev = lib.rev))

    private fun reply(a: Answer) = QueueOpReply.from(Node.decode("QUEUE_OP.reply", ok(a)))

    private fun state(d: Device) = State.from(Node.decode("STATE", d.state()))

    private fun queue(d: Device): List<Long> {
        val out = Sink()
        ok(d.request(Msg.QUEUE_OP, Node.decode("QUEUE_OP", Req.queueOp(Enums.QueueCmd.page, offset = 0, count = 100)), out))
        return out.queues().flatMap { it.items }.mapNotNull { it.track }
    }

    private fun ids(vararg titles: String) = titles.map { t -> lib.tracks.first { it.title == t }.id }

    @Test
    fun genreRowsAreFoldedNameOrderWithCaseVariantsAndNoUntagged() {
        val out = Sink()
        ok(libGet(device(), "", out))
        val rows = out.libs().single().rows
        assertEquals(listOf("Ambient", "J-Pop", "Rock", "rock"), rows.map { it.name })
        assertEquals(listOf(1L, 1L, 2L, 1L), rows.map { it.count })
        assertEquals(rows.map { it.name }, rows.map { it.key })
        assertEquals(ids("Airbag").single(), rows[2].first)
    }

    @Test
    fun genreTracksAreInSongOrderAndAnUnknownKeyIsSeven() {
        val out = Sink()
        ok(libGet(device(), "Rock", out))
        assertEquals(listOf("Airbag", "Lucky"), out.libs().single().rows.map { it.name })
        assertEquals(Err.staleRev, code(libGet(device(), "Jazz")))
    }

    @Test
    fun playGenrePlaysItsSongsFromStart() {
        val d = device()
        ok(d.request(Msg.PLAY, Node.decode("PLAY", Req.play(Enums.View.genres, "Rock", lib.rev, 1, false)), Sink()))
        assertEquals(ids("Airbag", "Lucky"), queue(d))
        assertEquals(1L, state(d).slot)
        assertEquals(Err.badValue, code(d.request(Msg.PLAY, Node.decode("PLAY", Req.play(Enums.View.genres, "", lib.rev, 0, false)), Sink())))
    }

    @Test
    fun addNeedsNoRevAndRepliesAddedAndARisingRev() {
        val d = device()
        val before = queue(d)
        val a = reply(add(d, "Rock"))
        val b = reply(add(d, "J-Pop", rev = 999))
        assertEquals(2L, a.added)
        assertEquals(1L, b.added)
        assertTrue(b.rev!! > a.rev!!)
        assertEquals(b.rev, state(d).queueRev)
        assertEquals(before + ids("Airbag", "Lucky", "Automatic"), queue(d))
    }

    @Test
    fun addOnAnEmptyQueueReplacesItPausedAtTheFirst() {
        val d = device()
        ok(queueOp(d, Req.queueOp(Enums.QueueCmd.clear, state(d).queueRev)))
        reply(add(d, "Rock"))
        assertEquals(ids("Airbag", "Lucky"), queue(d))
        val s = state(d)
        assertEquals(0L, s.slot)
        assertFalse(s.playing!!)
    }

    @Test
    fun addByTrackIdsAndOtherViews() {
        val d = device()
        val n = queue(d).size
        assertEquals(1L, reply(queueOp(d, Req.queueOp(Enums.QueueCmd.add, tracks = ids("Lucky")))).added)
        val album = lib.albumKey(ids("Porcelain").single())!!
        assertEquals(2L, reply(add(d, album, view = Enums.View.albumTracks)).added)
        assertEquals(n + 3, queue(d).size)
        assertEquals(Err.badValue, code(add(d, "")))
    }

    @Test
    fun playNextByViewGoesAfterTheSlot() {
        val d = device()
        val rev = state(d).queueRev
        val r = reply(queueOp(d, Req.queueOp(Enums.QueueCmd.playNext, rev, view = Enums.View.genres, parent = "Rock", libRev = lib.rev)))
        assertEquals(2L, r.added)
        assertEquals(ids("Airbag", "Lucky"), queue(d).subList(1, 3))
        assertEquals(Err.staleRev, code(queueOp(d, Req.queueOp(Enums.QueueCmd.playNext, rev, view = Enums.View.genres, parent = "Rock", libRev = lib.rev))))
    }

    @Test
    fun minor14RefusesAddAndViewSourcesAndRepliesEmptyToPlayNext() {
        val d = device(14)
        assertEquals(Err.badValue, code(add(d, "Rock")))
        assertEquals(Err.badValue, code(libGet(d, "")))
        val rev = state(d).queueRev
        assertEquals(Err.badValue, code(queueOp(d, Req.queueOp(Enums.QueueCmd.playNext, rev, view = Enums.View.genres, parent = "Rock", libRev = lib.rev))))
        assertEquals(0, ok(queueOp(d, Req.queueOp(Enums.QueueCmd.playNext, rev, tracks = ids("Lucky")))).size)
    }
}
