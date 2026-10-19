package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.ImgBeginReply
import net.seilent.funecomp.proto.ImgListReply
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.proto.gen.Tags
import net.seilent.funecomp.proto.tlv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.zip.CRC32
import javax.imageio.ImageIO

class Proto19Test {
    private class Sink : Out {
        override fun push(type: Int, payload: ByteArray) {}
    }

    private val logs: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val lib = Library(
        listOf(
            Library.track("Gemma, Hal", "Duets", "One", 1, 200_000, albumArtist = ""),
            Library.track("BoA", "Listen", "Two", 1, 200_000),
        ),
    )
    private val lib2 = Library(listOf(Library.track("BoA", "Listen", "Two", 1, 200_000)))

    private fun device(minor: Int = Proto.minor, l: Library = lib, cap: Long = Options.IMG_CAP, rescan: Library? = null) =
        Device(Options(Board.of("v1"), minor = minor, log = { logs += it }, imgCap = cap, rescanLib = rescan), l) { 1000L }

    private fun jpeg(seed: Int): ByteArray {
        val img = java.awt.image.BufferedImage(240, 320, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = java.awt.Color(seed * 40 % 256, 90, 140)
        g.fillRect(0, 0, 240, 320)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
    }

    private fun crc(b: ByteArray) = CRC32().apply { update(b) }.value

    private fun req(d: Device, type: Int, body: ByteArray) = d.request(type, Node.decode(Msg.info.getValue(type).name, body), Sink())

    private fun begin(d: Device, name: String, img: ByteArray, origin: Int?) =
        req(d, Msg.IMG_BEGIN, Req.imgBegin(Enums.ImgKind.artist, name, null, img.size.toLong(), crc(img), 240, 320, origin = origin))

    private fun push(d: Device, name: String, img: ByteArray, origin: Int?) {
        val b = begin(d, name, img, origin) as Answer.Ok
        if (ImgBeginReply.from(Node.decode("IMG_BEGIN.reply", b.payload)).offset == img.size.toLong()) return
        d.imgData(Node.decode("IMG_DATA", Req.imgData(0, img)), Sink())
        assertTrue(req(d, Msg.IMG_END, Req.imgEnd(crc(img))) is Answer.Ok)
    }

    private fun list(d: Device): Map<String, List<Int>> =
        ImgListReply.from(Node.decode("IMG_LIST.reply", (req(d, Msg.IMG_LIST, Req.imgList(0, 100)) as Answer.Ok).payload)).entries.associate { it.name to it.origins }

    private fun tag(d: Device, name: String, origin: Int = Enums.ImgOrigin.library) = req(d, Msg.IMG_TAG, Req.imgTag(name, origin))

    private fun code(a: Answer) = (a as Answer.Fail).code

    private val library = listOf(Enums.ImgOrigin.library)
    private val phone = listOf(Enums.ImgOrigin.phone)
    private val both = listOf(Enums.ImgOrigin.library, Enums.ImgOrigin.phone)

    @Test
    fun beginAddsItsOriginAndEndMergesTheOldSet() {
        val d = device()
        push(d, "BoA", jpeg(1), Enums.ImgOrigin.library)
        assertEquals(mapOf("BoA" to library), list(d))
        push(d, "BoA", jpeg(1), Enums.ImgOrigin.phone)
        assertEquals(both, list(d)["BoA"])
        push(d, "Hal", jpeg(2), null)
        assertEquals(emptyList<Int>(), list(d)["Hal"])
        assertTrue(begin(d, "Hal", jpeg(3), Enums.ImgOrigin.phone) is Answer.Ok)
        assertEquals(phone, list(d)["Hal"])
        push(d, "Gemma", jpeg(4), Enums.ImgOrigin.library)
        push(d, "gemma", jpeg(5), Enums.ImgOrigin.phone)
        assertEquals(mapOf("BoA" to both, "Hal" to phone, "gemma" to both), list(d))
        assertEquals(listOf("BoA" to 0, "BoA" to 1, "Hal" to null, "Hal" to 1, "Gemma" to 0, "gemma" to 1), d.begins.toList())
        assertTrue(logs.contains("begin name=Hal origin="))
        assertTrue(logs.contains("begin name=gemma origin=phone"))
    }

    @Test
    fun keysUseTheDeviceFold() {
        val d = device()
        push(d, " BoA ", jpeg(1), Enums.ImgOrigin.library)
        push(d, "ＢｏＡ", jpeg(2), Enums.ImgOrigin.library)
        assertEquals(setOf("boa", "ＢｏＡ"), d.stored.keys)
        assertTrue(tag(d, "BOA") is Answer.Ok)
    }

    @Test
    fun imgTagAnswersAndSetsTheBit() {
        val d = device()
        push(d, "BoA", jpeg(1), null)
        assertEquals(Err.badValue, code(req(d, Msg.IMG_TAG, tlv { str(Tags.ImgTag.name, "BoA") })))
        assertEquals(Err.badValue, code(req(d, Msg.IMG_TAG, tlv { enum(Tags.ImgTag.origin, Enums.ImgOrigin.library) })))
        assertEquals(Err.notFound, code(tag(d, "Hal")))
        assertTrue(tag(d, " boa") is Answer.Ok)
        assertEquals(library, list(d)["BoA"])
        assertTrue(tag(d, "BoA", Enums.ImgOrigin.phone) is Answer.Ok)
        assertEquals(both, list(d)["BoA"])
    }

    @Test
    fun aDeleteAndAnEvictionDropTheSet() {
        val img = jpeg(1)
        val d = device(cap = img.size * 2L + 10)
        push(d, "BoA", img, Enums.ImgOrigin.phone)
        assertTrue(req(d, Msg.IMG_DEL, Req.imgDel("BoA")) is Answer.Ok)
        push(d, "BoA", img, null)
        assertEquals(emptyList<Int>(), list(d)["BoA"])
        push(d, "Hal", jpeg(2), Enums.ImgOrigin.phone)
        push(d, "Gemma", jpeg(3), null)
        assertFalse("boa" in d.stored)
        push(d, "BoA", jpeg(4), null)
        assertEquals(emptyList<Int>(), list(d)["BoA"])
    }

    @Test
    fun aMinor8DeviceKeepsNoOriginsAndHasNoBit() {
        val d = device(8)
        push(d, "BoA", jpeg(1), Enums.ImgOrigin.phone)
        assertEquals(emptyList<Int>(), list(d)["BoA"])
        assertEquals(listOf("BoA" to Enums.ImgOrigin.phone), d.begins.toList())
        assertFalse(d.helloReply().has("imgOrigin"))
        assertTrue(device().helloReply().has("imgOrigin"))
        assertFalse(Groups.allowed(Msg.IMG_TAG, d.helloReply().features))
        d.request(Msg.ACTION, Node.decode("ACTION", Req.action(Enums.ActionCmd.rescan)), Sink())
        assertEquals(setOf("boa"), d.stored.keys)
    }

    @Test
    fun aRescanPrunesLibraryOnlyAndOldJoinedNames() {
        val d = device(rescan = lib2)
        push(d, "Gemma", jpeg(1), Enums.ImgOrigin.library)
        push(d, "Hal", jpeg(2), Enums.ImgOrigin.library)
        push(d, "Hal", jpeg(2), Enums.ImgOrigin.phone)
        push(d, "Gemma, Hal", jpeg(3), null)
        push(d, "Stranger", jpeg(4), null)
        push(d, "BoA", jpeg(5), Enums.ImgOrigin.library)
        val rev = d.lib.rev
        d.rescan(null)
        assertEquals(setOf("gemma", "hal", "stranger", "boa"), d.stored.keys)
        assertTrue(logs.contains("pruned name=Gemma, Hal"))
        assertEquals(rev + 1, d.lib.rev)
        assertTrue(d.request(Msg.ACTION, Node.decode("ACTION", Req.action(Enums.ActionCmd.rescan)), Sink()) is Answer.Ok)
        assertEquals(setOf("hal", "stranger", "boa"), d.stored.keys)
        assertTrue(logs.contains("pruned name=Gemma"))
        assertEquals(listOf("BoA"), d.lib.tracks.flatMap { it.artists })
        assertEquals(rev + 2, d.lib.rev)
        assertEquals(d.lib.tracks.single().id, State.from(Node.decode("STATE", d.state())).track)
    }

    @Test
    fun theSourceIsSetAtStartAndAtRuntime() {
        assertEquals(Enums.Source.local, State.from(Node.decode("STATE", device().state())).source)
        val d = Device(Options(Board.of("v1"), source = Enums.Source.phone), lib) { 1000L }
        assertEquals(Enums.Source.phone, State.from(Node.decode("STATE", d.state())).source)
        d.source(Enums.Source.local)
        assertEquals(Enums.Source.local, State.from(Node.decode("STATE", d.state())).source)
    }
}
