package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Queue
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Feature
import net.seilent.funecomp.proto.gen.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ServiceRowSimTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var t = 1000L
    private var wall = 1_800_000_000_000L
    private val lib = Library(
        listOf(Library.track("Band", "Local", "Here", 1, 200_000)),
        listOf(Library.track("Band", "Stream", "Song", 1, 200_000, service = "tidal", serviceKey = 167)),
    )
    private val svcApp = Groups.all or (1L shl Feature.svcLogin)

    private class Sink : Out {
        val frames = ArrayList<Pair<Int, ByteArray>>()
        override fun push(type: Int, payload: ByteArray) {
            frames += type to payload
        }
    }

    private val app = Sink()

    private fun device(vararg faults: String, features: Long = Groups.all) =
        Device(Options(Board.of("v1"), Faults(faults.toList()), wall = { wall }), lib) { t }.also { it.linked(app, features) }

    private fun req(d: Device, type: Int, payload: ByteArray, out: Out = Sink()) = d.request(type, Node.decode(Msg.info.getValue(type).name, payload), out)

    private fun queue(d: Device): Queue {
        val out = Sink()
        assertTrue(req(d, Msg.QUEUE_OP, Req.queueOp(Enums.QueueCmd.page, offset = 0, count = 10), out) is Answer.Ok)
        return Queue.from(Node.decode("QUEUE", out.frames.single { it.first == Msg.QUEUE }.second))
    }

    private fun state(d: Device, out: Out = app) = State.from(Node.decode("STATE", d.state(out = out)))

    private fun toService(d: Device) {
        req(d, Msg.TRANSPORT, Req.transport(Enums.TransportCmd.next))
        req(d, Msg.TRANSPORT, Req.transport(Enums.TransportCmd.play))
    }

    @Test
    fun queueRowsCarryTheServiceAndNoTrack() {
        val items = queue(device()).items
        assertEquals(lib.tracks[0].id, items[0].track)
        assertNull(items[0].service)
        assertNull(items[1].track)
        assertEquals("tidal" to 167L, items[1].service to items[1].serviceKey)
        assertEquals("Song", items[1].title)
        assertEquals(null to null, items[1].lyrics to items[1].lyricsPlain)
    }

    @Test
    fun theZeroFaultSendsATrackZeroOnServiceRows() {
        val d = device("svc-row-zero", features = svcApp)
        val items = queue(d).items
        assertEquals(0L, items[1].track)
        assertEquals(lib.tracks[0].id, items[0].track)
        toService(d)
        val s = state(d)
        assertEquals(0L, s.track)
        assertEquals("tidal", s.service)
    }

    @Test
    fun statePlayingAServiceRowHasNoTrackAndServiceTagsOnlyForABit32App() {
        val old = device()
        toService(old)
        val o = state(old)
        assertNull(o.track)
        assertNull(o.service)
        assertNull(o.serviceKey)
        assertEquals("Song", o.title)
        assertEquals(200_000L, o.durationMs)
        assertEquals(true, o.playing)
        assertNull(o.lyrics)
        assertNull(o.favorite)

        val d = device(features = svcApp)
        toService(d)
        val s = state(d)
        assertNull(s.track)
        assertEquals("tidal" to 167L, s.service to s.serviceKey)
        req(d, Msg.TRANSPORT, Req.transport(Enums.TransportCmd.prev))
        val back = state(d)
        assertEquals(lib.tracks[0].id, back.track)
        assertNull(back.service)
    }

    @Test
    fun serviceTagsFollowEachPeersOwnBit32() {
        val d = device()
        val svc = Sink()
        d.linked(svc, svcApp)
        toService(d)
        assertNull(state(d, app).service)
        assertEquals("tidal" to 167L, state(d, svc).let { it.service to it.serviceKey })
        assertNull(state(d, Sink()).service)
        d.greet(svc)
        d.greet(app)
        val pushed = svc.frames.filter { it.first == Msg.STATE }.map { State.from(Node.decode("STATE", it.second)) }
        val old = app.frames.filter { it.first == Msg.STATE }.map { State.from(Node.decode("STATE", it.second)) }
        assertTrue(pushed.isNotEmpty() && pushed.all { it.service == "tidal" && it.track == null })
        assertTrue(old.isNotEmpty() && old.all { it.service == null && it.track == null })
    }

    @Test
    fun aServicePlayIsLoggedWithItsServiceAndNoTrack() {
        val d = device()
        toService(d)
        repeat(130) {
            t += 1000
            wall += 1000
            d.tick(Sink())
        }
        val e = d.plays.single()
        assertNull(e.track)
        assertEquals("tidal" to 167L, e.service to e.serviceKey)
        assertEquals("Song", e.title)
    }

    @Test
    fun libRowsWithAServiceColumnLoadAsServiceRows() {
        val f = tmp.newFile("lib.tsv")
        f.writeText("Band\tLocal\tHere\t200000\n" + "Band\tStream\tSong\t180000\t\t\t\ttidal\t167\n")
        val l = Library.load(f)
        assertEquals(listOf("Here"), l.tracks.map { it.title })
        assertEquals(listOf("tidal" to 167L), l.services.map { it.service to it.serviceKey })
        assertTrue(l.rows(Enums.View.songs, "")!!.none { it.name == "Song" })
        assertEquals("Song", l.byId.getValue(l.services[0].id).title)
    }
}
