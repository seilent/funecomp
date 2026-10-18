package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.ArtGetReply
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Queue
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.UpdAbort
import net.seilent.funecomp.proto.UpdBeginReply
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import javax.imageio.ImageIO

class Proto12Test {
    @get:Rule
    val tmp = TemporaryFolder()

    private class Sink : Out {
        val frames = ArrayList<Pair<Int, ByteArray>>()
        override fun push(type: Int, payload: ByteArray) {
            frames += type to payload
        }

        fun of(type: Int) = frames.filter { it.first == type }.map { Node.decode(Msg.info.getValue(type).name, it.second) }
    }

    private var t = 1000L
    private val lib = Library.synthetic()
    private val track = lib.tracks.first().id

    private fun device(opts: Options) = Device(opts, lib) { t }

    private fun opts(board: String = "v1", minor: Int = Proto.minor, battery: Battery = Battery(87, 87, 0), maxPct: Int? = null, freeMiB: Long = 158_234) =
        Options(Board.of(board), Faults(emptyList()), minor = minor, artDir = File(tmp.root, "art"), lrcDir = File(tmp.root, "lrc"),
            updDir = File(tmp.root, "upd"), battery = battery, cardFreeMiB = freeMiB, curve = board == "v1", maxPct = maxPct)

    private fun req(d: Device, type: Int, payload: ByteArray, out: Sink = Sink()) =
        d.request(type, Node.decode(Msg.info.getValue(type).name, payload), out).also { if (it is Answer.Ok && it.state) out.push(Msg.STATE, d.state()) }

    private fun ok(a: Answer) = (a as? Answer.Ok ?: throw AssertionError("failed: ${(a as Answer.Fail).code} ${a.text}")).payload

    private fun code(a: Answer) = (a as Answer.Fail).code

    private fun state(d: Device) = State.from(Node.decode("STATE", d.state()))

    private fun crc(b: ByteArray) = CRC32().apply { update(b) }.value

    private fun cover(w: Int, h: Int) {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.paint = java.awt.GradientPaint(0f, 0f, Color.RED, w.toFloat(), h.toFloat(), Color.BLUE)
        g.fillRect(0, 0, w, h)
        g.dispose()
        val dir = File(tmp.root, "art").apply { mkdirs() }
        ImageIO.write(img, "jpg", File(dir, "${lib.albumKey(track)}.jpg"))
    }

    @Test
    fun stateCarriesThe12FieldsAndMinor1LeavesThemOut() {
        val s = state(device(opts()))
        assertEquals(false, s.charging)
        assertEquals(158_234L, s.cardFreeMiB)
        assertEquals(233_440L, s.cardTotalMiB)
        assertEquals(5, s.volumeStepPct)
        assertEquals(100, s.maxVolumePct)
        assertEquals(false, s.lyrics)
        val old = state(device(opts(minor = 1)))
        assertNull(old.charging)
        assertNull(old.volumePct)
        assertNull(old.lyrics)
        assertEquals(0L, device(opts(minor = 1)).helloReply().features and Groups.mask("art", "lyrics", "update", "clockAuto"))
        assertEquals(Groups.all, device(opts()).helloReply().features)
    }

    @Test
    fun batteryMovesAndPushesState() {
        val d = device(opts(battery = Battery.parse("40:43@1000")))
        val out = Sink()
        assertEquals(40, state(d).battery)
        assertEquals(true, state(d).charging)
        t += 1000
        d.tick(out)
        assertEquals(41, State.from(out.of(Msg.STATE).single()).battery)
        t += 10_000
        assertEquals(43, state(d).battery)
    }

    @Test
    fun volumePercentOnTheV1CurveAndTheCap() {
        val d = device(opts(maxPct = 80))
        ok(req(d, Msg.VOLUME, Req.volume(percent = 34)))
        assertEquals(189, state(d).volume)
        assertEquals(34, state(d).volumePct)
        ok(req(d, Msg.VOLUME, Req.volume(percent = 1)))
        assertEquals(123, state(d).volume)
        ok(req(d, Msg.VOLUME, Req.volume(percent = 100)))
        assertEquals(80, state(d).volumePct)
        assertEquals(235, state(d).volume)
        ok(req(d, Msg.VOLUME, Req.volume(step = 0)))
        assertEquals(235, state(d).volume)
        val linear = device(opts(board = "ap80"))
        ok(req(linear, Msg.VOLUME, Req.volume(percent = 50)))
        assertEquals(128, state(linear).volume)
        assertEquals(50, state(linear).volumePct)
    }

    @Test
    fun artGetChunksHaveAndNoArt() {
        val d = device(opts())
        assertEquals(Err.notFound, code(req(d, Msg.ART_GET, Req.artGet(track, 512))))
        cover(1400, 1000)
        val first = ArtGetReply.from(Node.decode("ART_GET.reply", ok(req(d, Msg.ART_GET, Req.artGet(track, 900)))))
        assertEquals(512, first.w)
        assertEquals(512, first.h)
        val total = first.total!!.toInt()
        val all = java.io.ByteArrayOutputStream().apply { write(first.data!!) }
        while (all.size() < total) {
            val r = ArtGetReply.from(Node.decode("ART_GET.reply", ok(req(d, Msg.ART_GET, Req.artGet(track, 512, all.size().toLong())))))
            assertNull(r.crc)
            assertEquals(all.size().toLong(), r.offset)
            assertTrue(r.data!!.size <= Proto.artChunk)
            all.write(r.data)
        }
        val jpeg = all.toByteArray()
        assertEquals(first.crc, crc(jpeg))
        assertEquals(512, ImageIO.read(ByteArrayInputStream(jpeg)).width)
        val have = ArtGetReply.from(Node.decode("ART_GET.reply", ok(req(d, Msg.ART_GET, Req.artGet(track, 512, have = first.crc)))))
        assertNull(have.data)
        assertEquals(first.crc, have.crc)
        val chunk = ArtGetReply.from(Node.decode("ART_GET.reply", ok(req(d, Msg.ART_GET, Req.artGet(track, 512, 100, first.crc)))))
        assertNull(chunk.crc)
        assertEquals(100L, chunk.offset)
        val restart = ArtGetReply.from(Node.decode("ART_GET.reply", ok(req(d, Msg.ART_GET, Req.artGet(track, 512, 100, first.crc!! + 1)))))
        assertEquals(first.crc, restart.crc)
        assertEquals(0L, restart.offset)
        assertArrayEquals(first.data, restart.data)
        val small = ArtGetReply.from(Node.decode("ART_GET.reply", ok(req(d, Msg.ART_GET, Req.artGet(track, 10)))))
        assertEquals(Proto.artMinPx, small.w)
    }

    @Test
    fun artNeverUpscales() {
        cover(200, 300)
        val r = ArtGetReply.from(Node.decode("ART_GET.reply", ok(req(device(opts()), Msg.ART_GET, Req.artGet(track, 512)))))
        assertEquals(200, r.w)
        assertEquals(200, r.h)
    }

    @Test
    fun lrcPutRules() {
        val d = device(opts())
        val text = "[00:12.00]First line\n[00:17.20]Second line\n".toByteArray()
        assertEquals(Err.staleRev, code(req(d, Msg.LRC_PUT, Req.lrcPut(track, lib.rev + 1, text, crc(text)))))
        assertEquals(Err.crc, code(req(d, Msg.LRC_PUT, Req.lrcPut(track, lib.rev, text, crc(text) + 1))))
        val plain = "just words\n".toByteArray()
        assertEquals(Err.badValue, code(req(d, Msg.LRC_PUT, Req.lrcPut(track, lib.rev, plain, crc(plain)))))
        val big = ByteArray(Proto.lrcMaxBytes + 1) { 'a'.code.toByte() }
        assertEquals(Err.tooBig, code(req(d, Msg.LRC_PUT, Req.lrcPut(track, lib.rev, big, crc(big)))))
        val out = Sink()
        ok(req(d, Msg.LRC_PUT, Req.lrcPut(track, lib.rev, text, crc(text)), out))
        assertArrayEquals(text, File(tmp.root, "lrc/${java.lang.Long.toUnsignedString(track)}.lrc").readBytes())
        assertEquals(true, State.from(out.of(Msg.STATE).single()).lyrics)
        val q = Sink()
        ok(req(d, Msg.QUEUE_OP, Req.queueOp(Enums.QueueCmd.page, offset = 0, count = 2), q))
        val items = Queue.from(q.of(Msg.QUEUE).first()).items
        assertEquals(true, items[0].lyrics)
        assertEquals(false, items[1].lyrics)
        assertEquals(lib.tracks.first().album, items[0].album)
        assertEquals(lib.tracks.first().durationMs, items[0].durationMs)
        ok(req(d, Msg.LRC_DEL, Req.lrcDel(track)))
        assertEquals(false, state(d).lyrics)
        assertFalse(File(tmp.root, "lrc/${java.lang.Long.toUnsignedString(track)}.lrc").exists())
    }

    private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }

    private fun data(d: Device, out: Sink, offset: Long, b: ByteArray) =
        d.updData(Node.decode("UPD_DATA", Req.updData(offset, b)), out)

    @Test
    fun updateResumesAndStoresUnderTheBoardName() {
        val d = device(opts())
        val image = ByteArray(100_000) { (it * 7).toByte() }
        val sum = md5(image)
        assertEquals(Err.badValue, code(req(d, Msg.UPD_BEGIN, Req.updBegin("ap80", image.size.toLong(), sum))))
        val begin = UpdBeginReply.from(Node.decode("UPD_BEGIN.reply", ok(req(d, Msg.UPD_BEGIN, Req.updBegin("v1", image.size.toLong(), sum)))))
        assertEquals(0L, begin.offset)
        val out = Sink()
        data(d, out, 0, image.copyOfRange(0, Proto.updChunk))
        data(d, out, Proto.updChunk.toLong(), image.copyOfRange(Proto.updChunk, 2 * Proto.updChunk))
        val again = UpdBeginReply.from(Node.decode("UPD_BEGIN.reply", ok(req(d, Msg.UPD_BEGIN, Req.updBegin("v1", image.size.toLong(), sum)))))
        assertEquals(2L * Proto.updChunk, again.offset)
        data(d, out, 0, image.copyOfRange(0, 10))
        assertEquals(Enums.AbortCode.offsetMismatch, UpdAbort.from(out.of(Msg.UPD_ABORT).single()).code)
        val resumed = UpdBeginReply.from(Node.decode("UPD_BEGIN.reply", ok(req(d, Msg.UPD_BEGIN, Req.updBegin("v1", image.size.toLong(), sum))))).offset
        var at = resumed
        while (at < image.size) {
            val end = minOf(image.size.toLong(), at + Proto.updChunk)
            data(d, out, at, image.copyOfRange(at.toInt(), end.toInt()))
            at = end
        }
        ok(req(d, Msg.UPD_END, Req.updEnd(sum)))
        assertArrayEquals(image, File(tmp.root, "upd/v1.upt").readBytes())
        assertFalse(File(tmp.root, "upd/v1.upt.part").exists())
    }

    @Test
    fun updateRefusals() {
        val d = device(opts(battery = Battery(40, 40, 0)))
        val sum = md5(ByteArray(10))
        assertEquals(Err.refused, code(req(d, Msg.UPD_BEGIN, Req.updBegin("v1", 10, sum))))
        val e = device(opts())
        File(tmp.root, "upd").mkdirs()
        File(tmp.root, "upd/update.upt").writeText("x")
        val other = req(e, Msg.UPD_BEGIN, Req.updBegin("v1", 10, sum)) as Answer.Fail
        assertEquals("remove update.upt", other.text)
        File(tmp.root, "upd/update.upt").delete()
        assertEquals(Err.noSpace, code(req(device(opts(freeMiB = 100)), Msg.UPD_BEGIN, Req.updBegin("v1", 40L shl 20, sum))))
        ok(req(e, Msg.UPD_BEGIN, Req.updBegin("v1", 10, sum)))
        e.updData(Node.decode("UPD_DATA", Req.updData(0, ByteArray(10) { 1 })), Sink())
        assertEquals(Err.crc, code(req(e, Msg.UPD_END, Req.updEnd(sum))))
        assertFalse(File(tmp.root, "upd/v1.upt").exists())
    }

    @Test
    fun aSettingChangePushesStateAfterTheReply() {
        val d = device(opts())
        val during = Sink()
        val a = d.request(Msg.SET, Node.decode("SET", Req.set("maxVolume=80\n")), during) as Answer.Ok
        assertTrue(a.state)
        assertTrue(during.of(Msg.STATE).isEmpty())
        assertEquals(80, state(d).maxVolumePct)
        val same = d.request(Msg.SET, Node.decode("SET", Req.set("maxVolume=80\n")), Sink()) as Answer.Ok
        assertFalse(same.state)
    }

    @Test
    fun clockAutomaticIsAccepted() {
        val d = device(opts())
        ok(req(d, Msg.DRAFT, Req.draft(Enums.DraftKind.clock, unixS = 1_792_224_000, zoneS = 32_400, automatic = true)))
    }
}
