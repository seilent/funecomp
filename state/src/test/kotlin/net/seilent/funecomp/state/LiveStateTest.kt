package net.seilent.funecomp.state

import net.seilent.funecomp.proto.FrameReader
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.Live
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Schema
import net.seilent.funecomp.proto.Settings
import net.seilent.funecomp.proto.gen.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class LiveStateTest {
    private fun body(name: String): ByteArray {
        val hex = File("../third_party/funeos/vectors.txt").readLines().filter { it.startsWith("hex $name ") }.joinToString("") { it.substringAfter("hex $name ") }
        val b = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return FrameReader().feed(b, 0, b.size).single().payload
    }

    private fun live(rev: Long, text: String) = Node.decode("LIVE", Live(rev, text).encode())

    private val hello = HelloReply(1, 15, "v1", "x", 240, 320, 240, 320, 16384, 30000, 5000, 0, 1, 1)

    @Test
    fun theLiveVectorFillsTheMapByKey() {
        val d = DeviceState().reduce(Msg.LIVE, Node.decode("LIVE", body("live")), 0)
        assertEquals(2L, d.liveRows.rev)
        assertEquals(mapOf("dateTime" to "2026-10-16 23:59", "about" to "20261016.9d125d2"), d.liveRows.values)
        assertEquals("2026-10-16 23:59", d.liveWord("dateTime"))
        assertNull(d.liveWord("rescan"))
        assertNull(d.liveWord(null))
        assertEquals(mapOf("a" to "b=c"), keyLines("a=b=c\n"))
    }

    @Test
    fun aSchemaKeepsTheLiveMap() {
        val page = Schema.from(Node.decode("SCHEMA", body("schema_page_live")))
        val d = DeviceState().reduce(Msg.LIVE, Node.decode("LIVE", body("live")), 0)
            .reduce(Msg.SCHEMA, Node.decode("SCHEMA", body("schema_page_live")), 0)
            .withSchema(page.copy(pivotIndex = page.pivotCount!! - 1))
        assertEquals(page.rev, d.schema.rev)
        assertEquals("2026-10-16 23:59", d.liveWord("dateTime"))
    }

    @Test
    fun theSetFloorLeavesLiveAlone() {
        val d = DeviceState().withSetReply(50).reduce(Msg.LIVE, live(2, "dateTime=2026-10-16 23:59\n"), 0)
        assertEquals("2026-10-16 23:59", d.liveWord("dateTime"))
        val s = d.reduce(Msg.SETTINGS, Node.decode("SETTINGS", Settings(40, "navBar=on\n").encode()), 0)
        assertEquals(-1L, s.settings.rev)
        assertEquals(2L, s.liveRows.rev)
    }

    @Test
    fun anOlderRevIsDropped() {
        val d = DeviceState().reduce(Msg.LIVE, live(5, "rescan=12 SONGS\n"), 0).reduce(Msg.LIVE, live(3, "rescan=SCANNING\n"), 0)
        assertEquals(5L, d.liveRows.rev)
        assertEquals("12 SONGS", d.liveWord("rescan"))
    }

    @Test
    fun aHelloStartsTheRevsAgain() {
        val d = DeviceState().reduce(Msg.LIVE, live(9, "rescan=12 SONGS\n"), 0).withHello(hello).reduce(Msg.LIVE, live(1, "rescan=SCANNING\n"), 0)
        assertEquals(1L, d.liveRows.rev)
        assertEquals("SCANNING", d.liveWord("rescan"))
    }

    @Test
    fun eachLiveReplacesTheWholeMap() {
        val d = DeviceState().reduce(Msg.LIVE, live(1, "about=sim.v1\nrescan=12 SONGS\n"), 0).reduce(Msg.LIVE, live(2, "rescan=12 SONGS\n"), 0)
        assertNull(d.liveWord("about"))
        assertEquals("12 SONGS", d.liveWord("rescan"))
    }
}
