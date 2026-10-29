package net.seilent.funecomp.state

import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.PageInfo
import net.seilent.funecomp.proto.Queue
import net.seilent.funecomp.proto.QueueItem
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlayingTest {
    private val hello = HelloReply(1, 16, "v1", "t", 240, 320, 240, 320, 16384, 30_000, 5_000, Groups.all, 1, 1, 4)

    private fun svc(key: Long = 167, track: Long? = null) =
        State(source = Enums.Source.local, track = track, title = "Song", artist = "Band", artists = listOf("Band"), durationMs = 200_000, positionMs = 5_000, playing = true, service = "tidal", serviceKey = key, lyrics = false, lyricsPlain = false, slot = 0, slots = 3)

    private fun device(s: State) = DeviceState().withHello(hello).withState(s, 1_000)

    @Test
    fun aServiceRowPlaysAsServiceWhateverItsTrack() {
        assertEquals(Playing.Service("tidal", 167), Playing.of(svc()))
        assertEquals(Playing.Service("tidal", 167), Playing.of(svc(track = 9)))
        assertEquals(Playing.Service("tidal", 167), Playing.of(svc(track = 0)))
        assertEquals(Playing.Service("tidal", 167), device(svc()).playing)
    }

    @Test
    fun aTrackOfZeroIsNoLibraryTrack() {
        assertEquals(Playing.None, Playing.of(State(source = Enums.Source.local, track = 0, title = "x")))
        assertEquals(Playing.None, Playing.of(State(source = Enums.Source.local, title = "x")))
        assertEquals(Playing.Library(9), Playing.of(State(source = Enums.Source.local, track = 9)))
    }

    @Test
    fun thePhoneSourceReadsAsPhone() {
        val s = State(source = Enums.Source.phone, track = 0, title = "Song", artist = "Band")
        assertEquals(Playing.Phone(PhoneKey("Song", "Band")), Playing.of(s))
        assertEquals(Playing.Phone(null), Playing.of(State(source = Enums.Source.phone, track = 0)))
    }

    @Test
    fun theMediaSessionTakesAServiceRowAndNotATrackZero() {
        assertTrue(MediaGate.wanted(svc(), setting = true, sinkToDap = false))
        assertFalse(MediaGate.wanted(State(source = Enums.Source.local, track = 0, title = "x"), setting = true, sinkToDap = false))
        assertTrue(MediaGate.wanted(State(source = Enums.Source.local, track = 9), setting = true, sinkToDap = false))
    }

    @Test
    fun aServiceRowAsksNoLyricsAndHasNoHeart() {
        val d = device(svc(track = 9))
        assertEquals(LyricsFetch.Show.Off, LyricsFetch().decide(d, 2_000).show)
        assertNull(LyricsFetch().decide(d, 2_000).want)
        assertFalse(LyricsFetch.has(d))
        assertEquals(false, LyricsFetch.follow(d))
        assertFalse(Heart.shown(hello, d.playback.state))
        assertNull(Heart().tap(d.playback.state))
        assertTrue(LyricsPlan.wants(d, emptySet()).isEmpty())
    }

    @Test
    fun noLyricsPutForServiceRowsOrATrackZeroRow() {
        val rows = listOf(
            QueueItem(null, "Song", listOf("Band"), lyrics = false, lyricsPlain = false, service = "tidal", serviceKey = 1),
            QueueItem(0, "Zero", listOf("Band"), lyrics = false, lyricsPlain = false),
            QueueItem(9, "Next", listOf("Band"), lyrics = false, service = "tidal", serviceKey = 2),
            QueueItem(12, "Local", listOf("Band"), lyrics = false),
        )
        val d = device(svc()).withQueue(Queue(1, PageInfo(0, 5, false), listOf(QueueItem(null, "Song", service = "tidal", serviceKey = 167)) + rows))
        assertEquals(listOf(12L), LyricsPlan.wants(d, emptySet()).map { it.track })
    }

    @Test
    fun twoServiceRowsAreTwoPlaysAndKeepTheirServiceOnDisk() {
        val p = Plays()
        val wall = 1_800_000_000_000L
        val first = p.frame(svc(1).copy(positionMs = 0, sampleMs = 0), wall)
        assertEquals(1, first.count { it is Plays.Event.NowPlaying })
        p.frame(svc(1).copy(positionMs = 10_000, sampleMs = 10_000), wall + 10_000)
        val second = p.frame(svc(2).copy(positionMs = 0, sampleMs = 11_000), wall + 11_000)
        assertEquals(1, second.count { it is Plays.Event.NowPlaying })
        val open = p.open!!
        assertEquals(2L to "tidal", open.track to open.service)
        assertEquals(open, Play.parse(open.line()))
        val lib = Play(9, 3, wall)
        assertEquals(lib, Play.parse(lib.line()))
        assertEquals(5, lib.line().split('\t').size)
    }

    @Test
    fun aServiceRowWithNoKeyAndATrackZeroCountNoPlay() {
        val p = Plays()
        assertTrue(p.frame(svc().copy(serviceKey = null), 1_000).isEmpty())
        assertTrue(p.frame(State(source = Enums.Source.local, track = 0, title = "x", playing = true, durationMs = 200_000), 2_000).isEmpty())
        assertNull(p.open)
    }

    @Test
    fun theStateVectorReadsAsAServiceRow() {
        val hex = File("../third_party/funeos/vectors.txt").readLines().filter { it.startsWith("hex state_service ") }.joinToString("") { it.substringAfter("hex state_service ") }
        val b = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val s = State.from(Node.decode("STATE", b.copyOfRange(6, b.size)))
        assertEquals(Playing.Service("tidal", 167), Playing.of(s))
        assertTrue(MediaGate.wanted(s, setting = true, sinkToDap = false))
    }
}
