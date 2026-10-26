package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.Frame
import net.seilent.funecomp.proto.FrameReader
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.Hello
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.Live
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.Schema
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class LiveSimTest {
    private class Sink : Out {
        val frames = ArrayList<Pair<Int, ByteArray>>()
        override fun push(type: Int, payload: ByteArray) {
            frames += type to payload
        }

        fun types() = frames.map { it.first }

        fun of(type: Int) = frames.filter { it.first == type }.map { Node.decode(Msg.info.getValue(type).name, it.second) }

        fun lives() = of(Msg.LIVE).map { Live.from(it) }

        fun pages() = of(Msg.SCHEMA).map { Schema.from(it) }

        fun rows() = pages().flatMap { it.rows }
    }

    private var t = 1000L
    private val bit = Groups.mask("settings", "liveRows")
    private val songs = "${Library.synthetic().tracks.size} SONGS"
    private val liveKeys = setOf("dateTime", "rescan", "about", "folders", "sampleRate", "eq", "scale")

    private fun device(board: String = "v1", faults: List<String> = emptyList(), minor: Int = Proto.minor, version: String? = null) =
        Device(Options(Board.of(board), Faults(faults), minor = minor, version = version), Library.synthetic()) { t }

    private fun peer(d: Device, mask: Long = bit) = Sink().also {
        d.linked(it, mask)
        d.greet(it)
    }

    private fun req(d: Device, type: Int, payload: ByteArray, out: Out) = d.request(type, Node.decode(Msg.info.getValue(type).name, payload), out)

    private fun clock(d: Device, out: Out) = req(d, Msg.DRAFT, Req.draft(Enums.DraftKind.clock, unixS = 1767225600, zoneS = 0), out)

    private fun lines(l: Live) = l.text.lines().filter { it.isNotEmpty() }.associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test
    fun aBit30PeerGetsLiveFirstAndRowsWithoutLiveWords() {
        val d = device("ap80")
        val s = peer(d)
        val types = s.types()
        assertTrue(types.indexOf(Msg.LIVE) in 0 until types.indexOf(Msg.SCHEMA))
        assertEquals(1, s.lives().size)
        val rows = s.rows()
        for (k in liveKeys) assertEquals(k, emptyList<String>(), rows.single { it.key == k }.words)
        val live = lines(s.lives().single())
        assertEquals("1.5", live["scale"])
        assertEquals(songs, live["rescan"])
        assertEquals("sim.ap80", live["about"])
        assertEquals("1 ON", live["folders"])
        assertEquals("AUTO", live["sampleRate"])
        assertEquals(listOf("1.5"), d.schemaRows().single { it.key == "scale" }.words)
        assertEquals(listOf("1.5"), Sink().also { d.greet(it) }.rows().single { it.key == "scale" }.words)
    }

    @Test
    fun aMinuteIsOneLiveAndNoSchema() {
        val d = device()
        val s = peer(d)
        clock(d, s)
        d.tick(s)
        val rev = s.lives().last().rev
        assertEquals("2026-01-01 00:00", lines(s.lives().last())["dateTime"])
        s.frames.clear()
        t += 59_000
        d.tick(s)
        assertTrue(s.frames.isEmpty())
        t += 1_000
        d.tick(s)
        assertEquals(listOf(Msg.LIVE), s.types())
        assertEquals(rev + 1, s.lives().single().rev)
        assertEquals("2026-01-01 00:01", lines(s.lives().single())["dateTime"])
    }

    @Test
    fun aRescanIsLiveOnly() {
        val d = device(faults = listOf("scan-ms=2000"))
        val s = peer(d)
        req(d, Msg.ACTION, Req.action(Enums.ActionCmd.rescan), s)
        s.frames.clear()
        d.tick(s)
        assertEquals(listOf(Msg.LIVE), s.types())
        assertEquals("SCANNING", lines(s.lives().single())["rescan"])
        s.frames.clear()
        t += 2_000
        d.tick(s)
        assertEquals(listOf(Msg.LIVE), s.types())
        assertEquals(songs, lines(s.lives().single())["rescan"])
    }

    @Test
    fun aStructureChangeIsSchemaOnlyAndLiveGoesFirst() {
        val d = device()
        val s = peer(d)
        clock(d, s)
        d.tick(s)
        val rev = s.pages().last().rev!!
        val liveRev = s.lives().last().rev
        req(d, Msg.SET, Req.set("navBar=on\n"), s)
        s.frames.clear()
        d.tick(s)
        assertTrue(s.lives().isEmpty())
        assertTrue(s.pages().isNotEmpty() && s.pages().all { it.rev == rev + 1 })
        req(d, Msg.SET, Req.set("navBar=off\n"), s)
        s.frames.clear()
        t += 60_000
        d.tick(s)
        val types = s.types()
        assertEquals(liveRev + 1, s.lives().single().rev)
        assertTrue(s.pages().all { it.rev == rev + 2 })
        assertTrue(types.indexOf(Msg.LIVE) in 0 until types.indexOf(Msg.SCHEMA))
    }

    @Test
    fun emptyValuesHaveNoLine() {
        val l = device("v1", version = "a\nb")
        val live = peer(l).lives().single()
        assertEquals(setOf("dateTime", "rescan", "about", "folders", "sampleRate", "eq"), lines(live).keys)
        assertTrue(live.text.lines().none { it.endsWith("=") })
        assertEquals("a b", lines(live)["about"])
        assertEquals(listOf("a\nb"), Sink().also { l.greet(it) }.rows().single { it.key == "about" }.words)
        assertTrue("about=" !in peer(device("v1", version = "")).lives().single().text)
    }

    @Test
    fun bothSidesNeedTheBit() {
        for ((d, mask) in listOf(device(faults = listOf("features=${Groups.all and Groups.mask("liveRows").inv()}")) to bit, device() to 0L)) {
            val s = peer(d, mask)
            assertTrue(s.lives().isEmpty())
            assertEquals(listOf(songs), s.rows().single { it.key == "rescan" }.words)
            clock(d, s)
            d.tick(s)
            val rev = s.pages().last().rev!!
            s.frames.clear()
            t += 60_000
            d.tick(s)
            assertTrue(s.lives().isEmpty())
            assertEquals(rev + 1, s.pages().last().rev)
            assertEquals(listOf("2026-01-01 00:01"), s.rows().single { it.key == "dateTime" }.words)
        }
    }

    @Test
    fun twoPeersEachKeepTheirOwnForm() {
        val d = device()
        val a = peer(d)
        val b = peer(d, 0L)
        clock(d, a)
        d.tick(a)
        d.tick(b)
        val legacy = b.pages().last().rev!!
        a.frames.clear()
        b.frames.clear()
        t += 60_000
        d.tick(a)
        d.tick(b)
        assertEquals(listOf(Msg.LIVE), a.types())
        assertTrue(b.lives().isEmpty())
        assertEquals(legacy + 1, b.pages().last().rev)
        a.frames.clear()
        d.tick(a)
        assertTrue(a.frames.isEmpty())
    }

    @Test
    fun minor14ClearsTheBit() {
        val d = device(minor = 14)
        assertEquals(0L, d.helloReply().features and Groups.mask("liveRows"))
        assertTrue(peer(d).lives().isEmpty())
        assertEquals(Groups.mask("liveRows"), device().helloReply().features and Groups.mask("liveRows"))
    }

    @Test
    fun aSocketPeerWithTheBitGetsLiveBeforeTheFirstSchema() {
        val opts = Options(Board.of("v1"))
        val d = Device(opts, Library.synthetic()) { t }
        val input = ByteArrayOutputStream()
        input.write(Frame(Msg.HELLO, Proto.flagWant, 1, Hello(Proto.major, Proto.minor, "test", bit).encode()).encode())
        val output = ByteArrayOutputStream()
        Conn(1, ByteArrayInputStream(input.toByteArray()), output, {}, d, opts) { t }.run()
        val b = output.toByteArray()
        val frames = FrameReader().feed(b, 0, b.size)
        val reply = HelloReply.from(Node.decode("HELLO.reply", frames.first { it.type == Msg.HELLO }.payload))
        assertEquals(Groups.mask("liveRows"), reply.features and Groups.mask("liveRows"))
        val types = frames.map { it.type }
        assertTrue(types.indexOf(Msg.LIVE) in 0 until types.indexOf(Msg.SCHEMA))
    }
}
