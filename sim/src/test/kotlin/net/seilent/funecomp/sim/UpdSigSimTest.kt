package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.FwStatementBuilder
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.UpdBeginReply
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Feature
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class UpdSigSimTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class Sink : Out {
        override fun push(type: Int, payload: ByteArray) {}
    }

    private val image = ByteArray(100_000) { (it * 13 + 1).toByte() }
    private val md5 = MessageDigest.getInstance("MD5").digest(image).joinToString("") { "%02x".format(it) }
    private val sig = FwStatementBuilder.forBytes("v1", image)
    private val logs = ArrayList<String>()

    private fun device(vararg faults: String, minor: Int = Proto.minor, keys: Set<String>? = null, seq: Long = 0, battery: Battery = Battery(87, 87, 0)) =
        Device(Options(Board.of("v1"), Faults(faults.toList()), minor = minor, updDir = File(tmp.root, "upd"), log = { logs += it }, battery = battery, updKeys = keys, updSeq = seq), Library.synthetic()) { 1000L }

    private fun begin(d: Device, s: ByteArray?, size: Long = image.size.toLong()) =
        d.request(Msg.UPD_BEGIN, Node.decode("UPD_BEGIN", Req.updBegin("v1", size, md5, s)), Sink())

    private fun offset(a: Answer) = UpdBeginReply.from(Node.decode("UPD_BEGIN.reply", (a as? Answer.Ok ?: throw AssertionError("failed: ${(a as Answer.Fail).code} ${a.text}")).payload)).offset

    private fun refused(a: Answer): String {
        val f = a as Answer.Fail
        assertEquals(f.text, Err.badSignature, f.code)
        return f.text ?: ""
    }

    private fun send(d: Device, from: Long, to: Long = image.size.toLong()) {
        var at = from
        while (at < to) {
            val end = minOf(to, at + Proto.updChunk)
            d.updData(Node.decode("UPD_DATA", Req.updData(at, image.copyOfRange(at.toInt(), end.toInt()))), Sink())
            at = end
        }
    }

    private fun end(d: Device) = d.request(Msg.UPD_END, Node.decode("UPD_END", Req.updEnd(md5)), Sink())

    private val part get() = File(tmp.root, "upd/v1.upt.part")

    @Test
    fun eachStatementReason() {
        val d = device()
        assertEquals("unsigned", refused(begin(d, null)))
        assertEquals("signature", refused(begin(d, sig + byteArrayOf(0))))
        assertEquals("board", refused(begin(d, FwStatementBuilder.forBytes("r1", image))))
        assertEquals("size", refused(begin(d, sig, image.size + 1L)))
        assertEquals("key", refused(begin(device(keys = setOf("f35ad3afb9592032")), sig)))
        assertEquals(0L, offset(begin(device(keys = setOf("0000000000000000")), sig)))
        assertEquals("older", refused(begin(device(seq = 4000), FwStatementBuilder.forBytes("v1", image, seq = 3999))))
        assertEquals(0L, offset(begin(device(seq = 4000), FwStatementBuilder.forBytes("v1", image, seq = 4000))))
        assertFalse(part.exists() && part.length() > 0)
    }

    @Test
    fun batteryComesBeforeTheOtherBoardFile() {
        File(tmp.root, "upd").mkdirs()
        File(tmp.root, "upd/update.upt").writeText("x")
        val low = begin(device(battery = Battery(40, 40, 0)), sig) as Answer.Fail
        assertEquals(Err.refused, low.code)
        assertEquals("battery", low.text)
        assertEquals("remove update.upt", (begin(device(), sig) as Answer.Fail).text)
        assertEquals("unsigned", refused(begin(device(battery = Battery(40, 40, 0)), null)))
    }

    @Test
    fun theSigBadFaultAnswersItsTextForTheFirstBegins() {
        val d = device("sig-bad=key:2")
        assertEquals("key", refused(begin(d, sig)))
        assertEquals("key", refused(begin(d, sig)))
        assertEquals(0L, offset(begin(d, sig)))
        val one = device("sig-bad=signature")
        assertEquals("signature", refused(begin(one, sig)))
        assertEquals(0L, offset(begin(one, sig)))
    }

    @Test
    fun theSameStatementResumesAndAnotherRestarts() {
        val d = device()
        assertEquals(0L, offset(begin(d, sig)))
        send(d, 0, 3L * Proto.updChunk)
        assertEquals(3L * Proto.updChunk, offset(begin(d, sig)))
        assertTrue(File(tmp.root, "upd/v1.upt.inf").readText().split(' ').size == 3)
        assertEquals(0L, offset(begin(d, FwStatementBuilder.forBytes("v1", image, version = "v0.4.1"))))
        send(d, 0)
        assertTrue(end(d) is Answer.Ok)
        assertArrayEquals(image, File(tmp.root, "upd/v1.upt").readBytes())
        assertTrue(logs.any { it.startsWith("stored upd ") && it.endsWith(" sig=" + sha8(FwStatementBuilder.forBytes("v1", image, version = "v0.4.1"))) })
    }

    @Test
    fun anEndShaMismatchDropsThePart() {
        val d = device()
        val other = FwStatementBuilder.build(size = image.size.toLong(), sha256 = FwStatementBuilder.sha256(image.copyOf().also { it[0]++ }))
        assertEquals(0L, offset(begin(d, other)))
        send(d, 0)
        assertEquals("signature", refused(end(d)))
        assertFalse(part.exists())
        assertFalse(File(tmp.root, "upd/v1.upt.inf").exists())
        assertFalse(File(tmp.root, "upd/v1.upt").exists())
    }

    @Test
    fun withoutBit27TheTagIsIgnored() {
        for (d in listOf(device(minor = 14), device("features=${Groups.all and (1L shl Feature.updSig).inv()}"))) {
            assertEquals(0L, offset(begin(d, null)))
            assertEquals(0L, offset(begin(d, byteArrayOf(1, 2, 3))))
            send(d, 0)
            assertTrue(end(d) is Answer.Ok)
        }
        assertArrayEquals(image, File(tmp.root, "upd/v1.upt").readBytes())
    }

    private fun sha8(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }.take(8)
}
