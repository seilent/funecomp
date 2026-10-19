package net.seilent.funecomp.proto

import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Feature
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.proto.gen.Tags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InboundTest {
    private fun close(f: Frame) = (Inbound.classify(f) as Inbound.Close).reason

    @Test
    fun oneTagTwiceCloses() {
        val body = tlv { u16(Tags.Volume.set, 1); u16(Tags.Volume.set, 2) }
        assertEquals("duplicate", close(Frame(Msg.VOLUME, Proto.flagWant, 1, body)))
    }

    @Test
    fun listTagRepeats() {
        val body = tlv { u64(Tags.Play.track, 1); u64(Tags.Play.track, 2) }
        val a = Inbound.classify(Frame(Msg.PLAY, Proto.flagWant, 1, body)) as Inbound.Accept
        assertEquals(listOf(1L, 2L), a.body.longs(Tags.Play.track))
    }

    @Test
    fun wrongWidthCloses() {
        val body = tlv { raw(Tags.Volume.set, byteArrayOf(1, 2, 3)) }
        assertEquals("badValue", close(Frame(Msg.VOLUME, Proto.flagWant, 1, body)))
    }

    @Test
    fun boolOutOfRangeCloses() {
        val body = tlv { raw(Tags.State.playing, byteArrayOf(2)) }
        assertEquals("badValue", close(Frame(Msg.STATE, 0, 1, body)))
    }

    @Test
    fun errorWithoutReplyCloses() {
        assertEquals("errorWithoutReply", close(Frame(Msg.PING, Proto.flagError, 1)))
    }

    @Test
    fun unknownEnumInRequestGetsBadValue() {
        val r = Inbound.classify(Frame(Msg.TRANSPORT, Proto.flagWant, 1, tlv { enum(Tags.Transport.cmd, 200) }))
        assertEquals(Err.badValue, (r as Inbound.Reply).code)
    }

    @Test
    fun unknownEnumInPushIsKept() {
        val r = Inbound.classify(Frame(Msg.STATE, 0, 1, tlv { enum(Tags.State.output, 200); u64(Tags.State.track, 5) }))
        val s = State.from((r as Inbound.Accept).body)
        assertEquals(200, s.output)
        assertEquals(5L, s.track)
        assertFalse(Enums.Output.names.containsKey(s.output))
    }

    @Test
    fun nestedUnknownEnumInRequestGetsBadValue() {
        val p = Profile(0, listOf(Band(77, -1, 1000000, 0, 141)))
        val r = Inbound.classify(Frame(Msg.EQ_SET, Proto.flagWant, 1, Req.eqSet(Enums.EqOutput.wired, ints = p)))
        assertEquals(Err.badValue, (r as Inbound.Reply).code)
    }

    @Test
    fun featureGroups() {
        assertTrue(Groups.allowed(Msg.TRANSPORT, 0))
        assertFalse(Groups.allowed(Msg.SET, 0))
        assertTrue(Groups.allowed(Msg.SET, Groups.mask("settings")))
        assertFalse(Groups.allowed(Msg.EQ_SET, Groups.mask("settings")))
        assertTrue(Groups.allowed(Msg.IMG_DATA, Groups.all))
        assertEquals(4095L, Groups.all)
    }

    @Test
    fun everyMessageIsCoreOrInAFeatureGroup() {
        for ((id, info) in Msg.info) {
            val g = Groups.group(id)
            assertTrue("${info.name} is in no group", id in Groups.core || g != null)
            assertFalse("${info.name} is core and in $g", id in Groups.core && g != null)
            if (g != null) assertTrue("group $g has no feature bit", Groups.bit(g) != null)
        }
        assertEquals(Feature.names.values.toSet(), Groups.members.keys)
    }

    @Test
    fun encodeRejectsOversizeAndReservedFlags() {
        assertThrows(IllegalArgumentException::class.java) { Frame(Msg.SETTINGS, 0, 1, ByteArray(Proto.maxPayload + 1)).encode() }
        assertThrows(IllegalArgumentException::class.java) { Frame(Msg.PING, 0x08, 1).encode() }
    }

    @Test
    fun pageFillStopsAtBudget() {
        val sizes = List(100) { 200 }
        val n = PageInfo.fill(sizes, 0, 40)
        assertTrue(40 + n * 200 <= PageInfo.budget)
        assertTrue(40 + (n + 1) * 200 > PageInfo.budget)
        assertEquals(1, PageInfo.fill(listOf(20000), 0, 0))
    }
}
