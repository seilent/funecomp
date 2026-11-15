package net.seilent.funecomp.state

import net.seilent.funecomp.proto.FrameReader
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.LibDeleteReply
import net.seilent.funecomp.proto.LibRow
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Err
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LibDeleteTest {
    private val boot = 6840316954749546503L
    private val token = ByteArray(16) { (0xa0 + it).toByte() }
    private val store = "rfcomm:AA:BB:CC:00:00:01"

    private fun hello(bootId: Long? = boot, snap: Long? = 4) = HelloReply(1, 18, "v1", "t", 240, 320, 240, 320, 16384, 30_000, 5_000, Groups.all, 1, 1, snap, bootId = bootId)

    private fun device(h: HelloReply? = hello()) = if (h == null) DeviceState() else DeviceState().withHello(h, Groups.all)

    private val songRow = LibRow("Raspberry Beret", 1, 201, "201")
    private val albumRow = LibRow("Around the World in a Day", 12, 201, "201", artist = "Prince")

    private fun song(d: DeviceState = device()) = LibDelete.ask(d, songRow, false, store)!!

    private fun album(d: DeviceState = device()) = LibDelete.ask(d, albumRow, true, store)!!

    private fun body(vec: String): ByteArray {
        val hex = File("../third_party/funeos/vectors.txt").readLines().filter { it.startsWith("hex $vec ") }.joinToString("") { it.substringAfter("hex $vec ") }
        val b = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return FrameReader().feed(b, 0, b.size).single().payload
    }

    private fun toast(o: LibDelete.Outcome) = (o as LibDelete.Outcome.Toast).let { it.line to it.title }

    @Test
    fun noAskWithoutABootIdASnapAFirstAnAlbumCountOrWithNoDeleteOrOneWaiting() {
        assertNotNull(LibDelete.ask(device(), songRow, false, store))
        assertNotNull(LibDelete.ask(device(), albumRow, true, store))
        assertNull(LibDelete.ask(device(hello(bootId = null)), songRow, false, store))
        assertNull(LibDelete.ask(device(hello(snap = null)), songRow, false, store))
        assertNull(LibDelete.ask(device(null), songRow, false, store))
        assertNull(LibDelete.ask(device(), songRow.copy(first = null), false, store))
        assertNull(LibDelete.ask(device(), albumRow.copy(count = null), true, store))
        assertNotNull(LibDelete.ask(device(), songRow.copy(count = null), false, store))
        assertNull(LibDelete.ask(device(), albumRow.copy(noDelete = true), true, store))
        assertNotNull(LibDelete.ask(device(), albumRow.copy(noDelete = false), true, store))
        assertNull(LibDelete.ask(device(), songRow, false, store, waiting = true))
        assertNull(LibDelete.ask(device(), albumRow, true, store, waiting = true))
    }

    @Test
    fun theAskCapturesTheRowAndTheMirror() {
        val s = Any()
        val d = device()
        val a = LibDelete.ask(d, albumRow, true, store, s)!!
        assertEquals(store, a.store)
        assertTrue(a.session === s)
        assertTrue(a.hello === d.hello)
        assertEquals(201L, a.first)
        assertEquals(12L, a.count)
        assertEquals("Around the World in a Day", a.name)
        assertEquals("201", a.key)
        assertEquals("Prince", a.artist)
        assertEquals(4L, a.snap)
        assertEquals(boot, a.bootId)
        assertEquals("album", a.what)
        val b = song(d)
        assertNull(b.count)
        assertNull(b.artist)
        assertEquals("song", b.what)
    }

    @Test
    fun staleOnANewHelloANullHelloOrAMovedSnapNotOnALibRevAlone() {
        val d = device()
        val a = song(d)
        assertFalse(LibDelete.stale(a, d))
        assertTrue(LibDelete.stale(a, d.withHello(hello(), Groups.all)))
        assertTrue(LibDelete.stale(a, d.unlinked()))
        assertTrue(LibDelete.stale(a, d.withState(State(snapRev = 5, libRev = 1), 0)))
        val rev = d.withState(State(snapRev = 4, libRev = 9), 0)
        assertEquals(9L, rev.lib.rev)
        assertFalse(LibDelete.stale(a, rev))
    }

    @Test
    fun theRequestMatchesTheVectors() {
        assertArrayEquals(body("lib_delete_song"), LibDelete.request(song(), token))
        assertArrayEquals(body("lib_delete_album"), LibDelete.request(album(), token))
        val top = java.lang.Long.parseUnsignedLong("fedcba9876543210", 16)
        val r = LibDelete.request(song(device(hello(bootId = top))), token)
        assertArrayEquals(byteArrayOf(6, 8, 0, 0x10, 0x32, 0x54, 0x76, 0x98.toByte(), 0xba.toByte(), 0xdc.toByte(), 0xfe.toByte()), r.copyOfRange(r.size - 11, r.size))
    }

    @Test
    fun theDeleteAnswers() {
        val s = song()
        val a = album()
        assertTrue(LibDelete.outcome(a, LibDeleteReply(12, 0), null, null) is LibDelete.Outcome.Quiet)
        assertTrue(LibDelete.outcome(s, LibDeleteReply(1, 0), null, null) is LibDelete.Outcome.Quiet)
        assertEquals("3 songs not deleted" to a.name, toast(LibDelete.outcome(a, LibDeleteReply(9, 3), null, null)))
        assertEquals("1 song not deleted" to a.name, toast(LibDelete.outcome(a, LibDeleteReply(11, 1), null, null)))
        assertEquals("album not deleted" to a.name, toast(LibDelete.outcome(a, LibDeleteReply(0, 12), null, null)))
        assertEquals("song not deleted" to s.name, toast(LibDelete.outcome(s, LibDeleteReply(0, 1), null, null)))
        assertTrue(LibDelete.outcome(a, null, Err.refused, "not allowed") is LibDelete.Outcome.NotAllowed)
        assertEquals("album not deleted" to "part of a cue image", toast(LibDelete.outcome(a, null, Err.refused, "part of a cue image")))
        assertEquals("song not deleted" to s.name, toast(LibDelete.outcome(s, null, Err.refused, "refused")))
        assertEquals("song not deleted" to s.name, toast(LibDelete.outcome(s, null, Err.refused, null)))
        assertEquals("album not deleted" to "card is read only", toast(LibDelete.outcome(a, null, Err.readOnly, "card is read only")))
        assertEquals("album not deleted" to a.name, toast(LibDelete.outcome(a, null, Err.staleRev, "library changed")))
        assertEquals("album not deleted" to a.name, toast(LibDelete.outcome(a, null, Err.staleRev, "count changed")))
        assertTrue(LibDelete.outcome(s, null, Err.notFound, null) is LibDelete.Outcome.Quiet)
        assertEquals("album not deleted" to a.name, toast(LibDelete.outcome(a, null, Err.notFound, null)))
        assertTrue(LibDelete.outcome(a, null, Err.noCard, "card not available") is LibDelete.Outcome.Card)
        for ((code, text) in listOf(Err.busy to "delete waiting", Err.busy to "busy", Err.badValue to null, Err.noLibrary to null, Err.unknown to "unknown")) {
            assertTrue("$code $text", LibDelete.outcome(a, null, code, text) is LibDelete.Outcome.Quiet)
        }
        for (u in LibDelete.Unanswered.entries) assertTrue(u.name, LibDelete.outcome(a, null, null, null, u) is LibDelete.Outcome.Quiet)
        assertTrue(LibDelete.outcome(a, LibDeleteReply(12, 3), null, null).log.contains("12 deleted, 3 failed"))
    }

    @Test
    fun theBindAskAnswers() {
        val s = song()
        assertTrue(LibDelete.bindOutcome(s, token, null, null) is LibDelete.Outcome.Send)
        assertEquals("song not deleted" to s.name, toast(LibDelete.bindOutcome(s, null, null, null)))
        assertEquals("song not deleted" to s.name, toast(LibDelete.bindOutcome(s, ByteArray(15), null, null)))
        assertTrue(LibDelete.bindOutcome(s, ByteArray(15), null, null).log.endsWith("token 15 bytes"))
        assertEquals(LibDelete.Why.SCREEN_OFF, (LibDelete.bindOutcome(s, null, Err.busy, "screen off") as LibDelete.Outcome.Wait).why)
        assertEquals(LibDelete.Why.DECLINED, (LibDelete.bindOutcome(s, null, Err.refused, "declined") as LibDelete.Outcome.Wait).why)
        assertEquals(LibDelete.Why.BUSY, (LibDelete.bindOutcome(s, null, Err.busy, "busy") as LibDelete.Outcome.Wait).why)
        assertEquals(LibDelete.Why.NO_ANSWER, (LibDelete.bindOutcome(s, null, null, null, LibDelete.Unanswered.TIMEOUT) as LibDelete.Outcome.Wait).why)
        assertEquals(LibDelete.Why.NO_ANSWER, (LibDelete.bindOutcome(s, null, null, null, LibDelete.Unanswered.CLOSED) as LibDelete.Outcome.Wait).why)
        assertEquals("song not deleted" to s.name, toast(LibDelete.bindOutcome(s, null, Err.refused, "refused")))
        assertEquals("song not deleted" to s.name, toast(LibDelete.bindOutcome(s, null, Err.refused, "no tap")))
        assertEquals("song not deleted" to s.name, toast(LibDelete.bindOutcome(s, null, Err.badValue, null)))
        assertEquals("song not deleted" to s.name, toast(LibDelete.bindOutcome(s, null, null, null, LibDelete.Unanswered.NOT_READY)))
        assertEquals("song not deleted" to s.name, toast(LibDelete.bindOutcome(s, null, null, null, LibDelete.Unanswered.UNSUPPORTED)))
    }

    @Test
    fun onlyAWhollyDeletedAlbumOwesItsExits() {
        val o = LibDelete.owe(album(), LibDeleteReply(12, 0), 4)!!
        assertEquals(store, o.store)
        assertEquals(boot, o.bootId)
        assertEquals("201", o.album)
        assertEquals("Prince", o.artist)
        assertEquals(4L, o.base)
        assertNull(LibDelete.owe(album(), LibDeleteReply(9, 3), 4))
        assertNull(LibDelete.owe(song(), LibDeleteReply(1, 0), 4))
    }

    @Test
    fun theExitsAreDueOnAMovedSnapUnderTheSameBootAndKey() {
        val o = LibDelete.owe(album(), LibDeleteReply(12, 0), 4)!!
        val d = device()
        assertEquals(LibDelete.Due.WAIT, LibDelete.due(o, d, store))
        assertEquals(LibDelete.Due.DUE, LibDelete.due(o, d.withState(State(snapRev = 5, libRev = 2), 0), store))
        assertEquals(LibDelete.Due.WAIT, LibDelete.due(o, d.unlinked(), store))
        assertEquals(LibDelete.Due.DUE, LibDelete.due(o, device(hello(snap = 5)), store))
        assertEquals(LibDelete.Due.WAIT, LibDelete.due(o, device(hello(snap = 4)), store))
        assertEquals(LibDelete.Due.DROP, LibDelete.due(o, device(hello(bootId = boot + 1, snap = 5)), store))
        assertEquals(LibDelete.Due.DROP, LibDelete.due(o, device(hello(bootId = null, snap = 5)), store))
        assertEquals(LibDelete.Due.DROP, LibDelete.due(o, device(hello(snap = 5)), "rfcomm:AA:BB:CC:00:00:02"))
    }

    @Test
    fun theNextExitReadsTheTopPage() {
        val o = LibDelete.owe(album(), LibDeleteReply(12, 0), 4)!!
        assertEquals(LibDelete.Exit.Pop, LibDelete.next(o, listOf("home", "music", LibDelete.artistPage("Prince"), LibDelete.albumPage("201"))))
        assertEquals(LibDelete.Exit.Artist("Prince"), LibDelete.next(o, listOf("home", "music", LibDelete.artistPage("Prince"))))
        assertEquals(LibDelete.Exit.End, LibDelete.next(o, listOf("home", LibDelete.albumPage("201"), "nowplaying")))
        assertEquals(LibDelete.Exit.End, LibDelete.next(o, listOf("home", LibDelete.artistPage("Other"))))
        assertEquals(LibDelete.Exit.End, LibDelete.next(o, listOf("home", LibDelete.albumPage("202"))))
        assertEquals(LibDelete.Exit.End, LibDelete.next(o, emptyList()))
        assertEquals(LibDelete.Exit.End, LibDelete.next(Owed(store, boot, "201", null, 4), listOf(LibDelete.artistPage("Prince"))))
    }
}
