package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.ArtGetReply
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Feature
import net.seilent.funecomp.proto.gen.Msg
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.File
import java.util.zip.CRC32
import javax.imageio.ImageIO

class PhoneFieldsSimTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class Sink : Out {
        val states = ArrayList<State>()
        override fun push(type: Int, payload: ByteArray) {
            if (type == Msg.STATE) states += State.from(Node.decode("STATE", payload))
        }
    }

    private val lib = Library.synthetic()
    private var t = 1000L

    private fun jpeg(name: String, w: Int, h: Int): File {
        val f = tmp.newFile(name)
        ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "jpeg", f)
        return f
    }

    private fun options(lines: List<PhoneLine>, receiver: Boolean = true, minor: Int? = null, vararg faults: String) =
        if (minor == null) Options(Board.of("v1"), Faults(faults.toList()), phone = lines, receiver = receiver)
        else Options(Board.of("v1"), Faults(faults.toList()), minor = minor, phone = lines, receiver = receiver)

    private fun device(lines: List<PhoneLine>, receiver: Boolean = true, minor: Int? = null, vararg faults: String) =
        Device(options(lines, receiver, minor, *faults), lib) { t }

    private fun bit(o: Options) = o.faults.features and (1L shl Feature.receiver)

    private fun state(d: Device) = State.from(Node.decode("STATE", d.state()))

    private fun artGet(d: Device, track: Long, px: Int = 256, offset: Long = 0, have: Long? = null) =
        d.request(Msg.ART_GET, Node.decode("ART_GET", Req.artGet(track, px, offset, have)), Sink())

    private fun crc(b: ByteArray) = CRC32().apply { update(b) }.value

    @Test
    fun aPhoneLineSendsItsNameTrackNumberCountAndCoverCrc() {
        val cover = jpeg("a.jpg", 200, 200)
        val s = state(device(listOf(PhoneLine("Idol", "YOASOBI", "THE BOOK 3", 213_000, 0, true, "Pixel", 3, 12, cover))))
        assertEquals(true, s.receiver)
        assertEquals("Pixel", s.phoneName)
        assertEquals(3L, s.phoneTrackNo)
        assertEquals(12L, s.phoneTracks)
        assertEquals(crc(cover.readBytes()), s.phoneArtCrc)
        assertNull(s.lyricsOffsetMs)
    }

    @Test
    fun aLineWithoutNameCountOrCoverLeavesThemOut() {
        val s = state(device(listOf(PhoneLine("Idol", "YOASOBI", "", 0, 0, true))))
        assertNull(s.phoneName)
        assertNull(s.phoneTrackNo)
        assertNull(s.phoneTracks)
        assertNull(s.phoneArtCrc)
    }

    @Test
    fun theReceiverBitAndStateComeOnlyWithTheReceiver() {
        val line = listOf(PhoneLine("Idol", "YOASOBI", "", 0, 0, true))
        val on = options(line)
        assertEquals(1L shl Feature.receiver, bit(on))
        assertEquals(true, state(Device(on, lib) { t }).receiver)
        val off = options(line, receiver = false)
        assertEquals(0L, bit(off))
        assertNull(state(Device(off, lib) { t }).receiver)
        val old = options(line, minor = 14)
        assertEquals(0L, bit(old))
        assertNull(state(Device(old, lib) { t }).receiver)
        assertTrue("receiver" in Groups.unserved)
    }

    @Test
    fun theReceiverStateStaysWithNoPhoneLine() {
        val s = state(device(emptyList()))
        assertEquals(true, s.receiver)
        assertNull(s.phoneName)
    }

    @Test
    fun aLateCoverArrivesWithItsCrcAndPushesAState() {
        val cover = jpeg("a.jpg", 200, 200)
        val d = device(listOf(PhoneLine("Idol", "YOASOBI", "", 213_000, 0, true, cover = cover)), true, null, "phone-art-late=2000")
        assertNull(state(d).phoneArtCrc)
        assertEquals(Err.notFound, (artGet(d, 0) as Answer.Fail).code)
        t += 2000
        val pushed = Sink().also { d.tick(it) }.states
        assertEquals(crc(cover.readBytes()), pushed.single().phoneArtCrc)
    }

    @Test
    fun artGetTrackZeroServesTheCoverAndAHaveHit() {
        val cover = jpeg("a.jpg", 200, 200)
        val d = device(listOf(PhoneLine("Idol", "YOASOBI", "", 213_000, 0, true, cover = cover)))
        val r = ArtGetReply.from(Node.decode("ART_GET.reply", (artGet(d, 0) as Answer.Ok).payload))
        assertEquals(crc(cover.readBytes()), r.crc)
        assertEquals(200, r.w)
        assertEquals(200, r.h)
        assertEquals(cover.length(), r.total)
        val bytes = cover.readBytes()
        assertArrayEquals(bytes.copyOfRange(0, r.data!!.size), r.data)
        val have = ArtGetReply.from(Node.decode("ART_GET.reply", (artGet(d, 0, have = r.crc) as Answer.Ok).payload))
        assertNull(have.data)
    }

    @Test
    fun artGetTrackZeroWithNoPhoneLineIsStale() {
        assertEquals(Err.staleRev, (artGet(device(emptyList()), 0) as Answer.Fail).code)
        assertEquals(Err.staleRev, (artGet(device(emptyList(), receiver = false), 0) as Answer.Fail).code)
    }
}
