package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.Band
import net.seilent.funecomp.proto.Eq
import net.seilent.funecomp.proto.ImgAbort
import net.seilent.funecomp.proto.ImgBeginReply
import net.seilent.funecomp.proto.ImgListReply
import net.seilent.funecomp.proto.Lib
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Profile
import net.seilent.funecomp.proto.Queue
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.Schema
import net.seilent.funecomp.proto.SetReply
import net.seilent.funecomp.proto.Settings
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32

class DeviceTest {
    private class Sink : Out {
        val frames = ArrayList<Pair<Int, ByteArray>>()
        override fun push(type: Int, payload: ByteArray) {
            frames += type to payload
        }

        fun of(type: Int) = frames.filter { it.first == type }.map { Node.decode(Msg.info.getValue(type).name, it.second) }
    }

    private var t = 1000L
    private fun device(board: String = "ap80", vararg faults: String) =
        Device(Options(Board.of(board), Faults(faults.toList())), Library.synthetic()) { t }

    private fun req(d: Device, type: Int, payload: ByteArray, out: Sink = Sink()) =
        d.request(type, Node.decode(Msg.info.getValue(type).name, payload), out)

    private fun ok(a: Answer) = (a as? Answer.Ok ?: throw AssertionError("failed: ${(a as Answer.Fail).code} ${a.text}")).payload

    private fun fail(a: Answer) = a as Answer.Fail

    @Test
    fun schemaFilesParseForBothBoards() {
        for (b in listOf("ap80", "v1")) {
            val d = device(b)
            assertTrue(d.rows.all { it.kind in Enums.RowKind.names })
            val out = Sink()
            d.greet(out)
            val pages = out.of(Msg.SCHEMA).map { Schema.from(it) }
            assertEquals(5, pages.last().pivotCount)
            assertTrue(pages.last().last)
            assertEquals(d.rows.size, pages.sumOf { it.rows.size })
            val text = Settings.from(out.of(Msg.SETTINGS).single()).text
            val keys = text.lines().filter { it.isNotEmpty() }.map { it.substringBefore('=') }.toSet()
            val rowKeys = d.rows.mapNotNull { it.key }.toSet()
            assertTrue((keys - rowKeys) == setOf("btCodecHeadphones"))
        }
        assertEquals(8, device("ap80").rows.first { it.key == "digitalFilter" }.values.size)
        assertEquals(4, device("v1").rows.first { it.key == "digitalFilter" }.values.size)
        assertEquals(listOf("auto", "aptx", "aac", "sbc"), device("v1").rows.first { it.key == "btCodec" }.values)
        assertEquals(listOf(Enums.RowFlag.readOnly, Enums.RowFlag.boardOnly), device("v1").rows.first { it.key == "companion" }.flags)
    }

    @Test
    fun setMergesPlainRowsOnly() {
        val d = device()
        val out = Sink()
        val r = SetReply.from(Node.decode("SET.reply", ok(req(d, Msg.SET, Req.set("balance=99\nvolumeStep=4\nclock=13h\nusbMode=dock\nbtRole=headphones\nsampleRate=1\n"), out))))
        assertEquals(listOf("balance", "volumeStep"), r.kept)
        assertEquals(listOf("clock", "usbMode", "btRole", "sampleRate"), r.refused)
        val s = Settings.from(out.of(Msg.SETTINGS).single())
        assertEquals(r.rev, s.rev)
        assertTrue("balance=20" in s.text.lines())
        assertTrue("volumeStep=5" in s.text.lines())
        assertTrue("clock=24h" in s.text.lines())
        assertTrue("usbMode=storage" in s.text.lines())
        val hue = Sink()
        ok(req(d, Msg.SET, Req.set("accentHue=237\n"), hue))
        assertTrue("accentHue=240" in Settings.from(hue.of(Msg.SETTINGS).single()).text.lines())
        val top = Sink()
        ok(req(d, Msg.SET, Req.set("accentHue=359\n"), top))
        assertTrue("accentHue=0" in Settings.from(top.of(Msg.SETTINGS).single()).text.lines())
        val none = fail(req(d, Msg.SET, Req.set("companion=on\n")))
        assertEquals(Err.refused, none.code)
        assertEquals(listOf("companion"), none.refused)
    }

    @Test
    fun draftRowsGoThroughDraft() {
        val d = device()
        val out = Sink()
        ok(req(d, Msg.DRAFT, Req.draft(Enums.DraftKind.btCodec, "ldac", Enums.BtRole.headphones), out))
        assertTrue("btCodecHeadphones=ldac" in Settings.from(out.of(Msg.SETTINGS).last()).text.lines())
        assertEquals(Err.badValue, fail(req(d, Msg.DRAFT, Req.draft(Enums.DraftKind.btCodec, "ldac", Enums.BtRole.receiver))).code)
        assertEquals(Err.badValue, fail(req(d, Msg.DRAFT, Req.draft(Enums.DraftKind.clock, unixS = -86400, zoneS = -18000))).code)
        val sleep = Sink()
        ok(req(d, Msg.DRAFT, Req.draft(Enums.DraftKind.sleep, minutes = 30), sleep))
        assertEquals(1800L, State.from(sleep.of(Msg.STATE).last()).sleepLeftS)
    }

    @Test
    fun eqSetKeysAndRanges() {
        val d = device()
        assertEquals(Err.badValue, fail(req(d, Msg.EQ_SET, Req.eqSet(Enums.EqOutput.wired, "/etc/passwd"))).code)
        val out = Sink()
        ok(req(d, Msg.EQ_SET, Req.eqSet(Enums.EqOutput.bt, "autoeq/oratory1990/Headphone Model 007.txt"), out))
        val pages = out.of(Msg.EQ).map { Eq.from(it) }
        assertTrue(pages.size >= 2)
        assertTrue(pages.all { it.output == Enums.EqOutput.bt && it.key == "autoeq/oratory1990/Headphone Model 007.txt" })
        assertEquals(0L, pages.first().page.offset)
        assertFalse(pages.last().page.more)
        assertEquals(200, pages.sumOf { it.names.size })
        val bad = Profile(-10, listOf(Band(Enums.BandType.peak, -1, 1_000_000, 200, 141)))
        assertEquals(Err.badValue, fail(req(d, Msg.EQ_SET, Req.eqSet(Enums.EqOutput.wired, ints = bad))).code)
        val good = Profile(-35, listOf(Band(Enums.BandType.peak, -1, 3_150_000, -25, 200)))
        val o2 = Sink()
        ok(req(d, Msg.EQ_SET, Req.eqSet(Enums.EqOutput.wired, ints = good), o2))
        assertEquals(good, Eq.from(o2.of(Msg.EQ).first()).ints)
        assertEquals("custom", Eq.from(o2.of(Msg.EQ).first()).key)
    }

    @Test
    fun libraryKeysPerView() {
        val d = device()
        val out = Sink()
        ok(req(d, Msg.LIB_GET, Req.libGet(Enums.View.artists, "", 0, 500, d.lib.rev), out))
        val artists = out.frames.map { Lib.from(Node.decode("LIB", it.second)) }.flatMap { it.rows }
        assertTrue(artists.any { it.key == "宇多田ヒカル" })
        assertTrue(artists.any { it.key == "Skrillex" })
        assertTrue(artists.any { it.key == "n-buna" })
        val a = Sink()
        ok(req(d, Msg.LIB_GET, Req.libGet(Enums.View.artistAlbums, "Utada Hikaru", 0, 100, d.lib.rev), a))
        val albums = Lib.from(a.of(Msg.LIB).single()).rows
        assertTrue(albums.all { it.key == java.lang.Long.toUnsignedString(it.first!!) })
        assertEquals(setOf("First Love", "BADモード", "Hikari"), albums.map { it.name }.toSet())
        val tracks = Sink()
        val album = albums.first { it.name == "First Love" }
        ok(req(d, Msg.LIB_GET, Req.libGet(Enums.View.albumTracks, album.key, 0, 100, d.lib.rev), tracks))
        assertEquals(5, Lib.from(tracks.of(Msg.LIB).single()).rows.size)
        assertEquals(Err.staleRev, fail(req(d, Msg.LIB_GET, Req.libGet(Enums.View.albumTracks, "12345", 0, 100, d.lib.rev))).code)

        val play = Sink()
        ok(req(d, Msg.PLAY, Req.play(Enums.View.albumTracks, album.key, d.lib.rev, 2, false), play))
        val s = State.from(play.of(Msg.STATE).single())
        assertEquals("In My Room", s.title)
        assertEquals(5L, s.slots)
        assertEquals(Err.staleRev, fail(req(d, Msg.PLAY, Req.play(Enums.View.albumTracks, album.key, d.lib.rev + 1, 0, false))).code)
    }

    @Test
    fun queueOpsCheckTheRev() {
        val d = device()
        val first = Sink()
        d.greet(first)
        val rev = State.from(first.of(Msg.STATE).single()).queueRev!!
        assertEquals(Err.staleRev, fail(req(d, Msg.QUEUE_OP, Req.queueOp(Enums.QueueCmd.remove, rev + 5, slot = 0))).code)
        val out = Sink()
        ok(req(d, Msg.QUEUE_OP, Req.queueOp(Enums.QueueCmd.move, rev, slot = 0, to = 3), out))
        assertEquals(rev + 1, State.from(out.of(Msg.STATE).single()).queueRev)
        assertTrue(out.of(Msg.QUEUE).isEmpty())
        val page = Sink()
        ok(req(d, Msg.QUEUE_OP, Req.queueOp(Enums.QueueCmd.page, offset = 0, count = 10), page))
        val q = Queue.from(page.of(Msg.QUEUE).single())
        assertEquals(10, q.items.size)
        assertTrue(q.page.more)
        assertEquals(rev + 1, q.rev)
    }

    private fun jpeg(size: Int): ByteArray {
        val head = byteArrayOf(-1, -40, -1, -64, 0, 17, 8, 1, -32, 1, -128, 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1)
        return ByteArray(size).also { head.copyInto(it); it[size - 2] = -1; it[size - 1] = -39 }
    }

    private fun crc(b: ByteArray) = CRC32().apply { update(b) }.value

    @Test
    fun imagePushRules() {
        val d = device()
        val img = jpeg(50_000)
        val out = Sink()
        fun begin(name: String) = ImgBeginReply.from(Node.decode("IMG_BEGIN.reply",
            ok(req(d, Msg.IMG_BEGIN, Req.imgBegin(Enums.ImgKind.artist, name, null, img.size.toLong(), crc(img), 384, 480), out))))
        fun data(off: Int, n: Int) = d.imgData(Node.decode("IMG_DATA", Req.imgData(off.toLong(), img.copyOfRange(off, off + n))), out)
        assertEquals(0L, begin("Ichiko Aoba").offset)
        data(0, 16368)
        data(16368, 100)
        assertEquals(16368L, begin("Ichiko Aoba").offset)
        assertEquals(crc(img.copyOf(16368)), ImgBeginReply.from(Node.decode("IMG_BEGIN.reply",
            ok(req(d, Msg.IMG_BEGIN, Req.imgBegin(Enums.ImgKind.artist, "Ichiko Aoba", null, img.size.toLong(), crc(img), 384, 480), out)))).crc)
        begin("Sigur Rós")
        val superseded = out.of(Msg.IMG_ABORT).map { ImgAbort.from(it) }
        assertEquals(Enums.AbortCode.superseded, superseded.last().code)
        assertEquals("Ichiko Aoba", superseded.last().name)
        data(5, 10)
        assertEquals(Enums.AbortCode.offsetMismatch, ImgAbort.from(out.of(Msg.IMG_ABORT).last()).code)
        assertEquals(Err.refused, fail(req(d, Msg.IMG_END, Req.imgEnd(crc(img)))).code)

        begin("Sigur Rós")
        var off = 0
        while (off < img.size) {
            val n = minOf(16368, img.size - off)
            data(off, n)
            off += n
        }
        ok(req(d, Msg.IMG_END, Req.imgEnd(crc(img))))
        assertEquals(img.size.toLong(), begin("sigur rós").offset)
        val list = ImgListReply.from(Node.decode("IMG_LIST.reply", ok(req(d, Msg.IMG_LIST, Req.imgList(0, 10)))))
        assertEquals(listOf("Sigur Rós"), list.entries.map { it.name })
        ok(req(d, Msg.IMG_DEL, Req.imgDel("SIGUR RÓS")))
        assertEquals(0L, ImgListReply.from(Node.decode("IMG_LIST.reply", ok(req(d, Msg.IMG_LIST, Req.imgList(0, 10))))).page.total)
    }

    @Test
    fun faultsParse() {
        val f = Faults(listOf("drop-after-bytes=131072,stall-ms=10", "enum=STATE.output=9", "refuse=SET", "bye=companionOff"))
        assertEquals(131072L, f.dropAfterBytes)
        assertEquals(Msg.STATE, f.enums.single().type)
        assertEquals(setOf(Msg.SET), f.refuse)
        assertEquals(Enums.ByeReason.companionOff, f.bye)
        for (bad in listOf("nope=1", "enum=STATE.nope=1", "refuse=NOPE")) {
            try {
                Faults(listOf(bad))
                throw AssertionError("$bad parsed")
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}
