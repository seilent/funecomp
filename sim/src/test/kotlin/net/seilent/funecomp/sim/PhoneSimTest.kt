package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class PhoneSimTest {
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

    private val script = listOf(
        PhoneLine("Idol", "YOASOBI", "THE BOOK 3", 213_000, 0, true, "Pixel", 1, 12),
        PhoneLine("Bling-Bang-Bang-Born", "Creepy Nuts", "Bling-Bang-Bang-Born", 0, 5_000, true),
        PhoneLine("Plain Song", "Someone, Other", "", 180_000, 0, false),
    )

    private fun device(lines: List<PhoneLine> = script, receiver: Boolean = true, vararg faults: String) =
        Device(Options(Board.of("v1"), Faults(faults.toList()), phone = lines, receiver = receiver), lib) { t }

    private fun state(d: Device) = State.from(Node.decode("STATE", d.state()))

    private fun req(d: Device, type: Int, payload: ByteArray) = d.request(type, Node.decode(Msg.info.getValue(type).name, payload), Sink())

    private fun transport(d: Device, cmd: Int, seekMs: Long? = null, on: Boolean? = null) = req(d, Msg.TRANSPORT, Req.transport(cmd, seekMs, on))

    private fun refused(a: Answer, text: String) {
        val f = a as? Answer.Fail ?: throw AssertionError("not refused")
        assertEquals(Err.refused, f.code)
        assertEquals(text, f.text)
    }

    private fun tick(d: Device): List<State> = Sink().also { d.tick(it) }.states

    @Test
    fun aPhoneLineSendsSourcePhoneOnTheReceiverOutputAsTrackZero() {
        val s = state(device())
        assertEquals(Enums.Source.phone, s.source)
        assertEquals(Enums.Output.phoneReceiver, s.output)
        assertEquals(0L, s.track)
        assertEquals("Idol", s.title)
        assertEquals("YOASOBI", s.artist)
        assertEquals(listOf("YOASOBI"), s.artists)
        assertEquals("THE BOOK 3", s.album)
        assertEquals(213_000L, s.durationMs)
        assertEquals(true, s.playing)
        assertTrue(s.mbids.isEmpty())
        assertNull(s.lyrics)
        assertNull(s.lyricsPlain)
        assertNull(s.lyricsCrc)
        assertNull(s.favorite)
        assertEquals(false, s.positionHeld)
    }

    @Test
    fun theOutputIsWiredOnceNoPhoneLineIsCurrent() {
        val d = device(listOf(script[0]))
        assertEquals(Enums.Output.phoneReceiver, state(d).output)
        tick(d)
        t += 213_000
        val pushed = tick(d)
        assertEquals(Enums.Source.local, pushed.single().source)
        assertEquals(Enums.Output.wired, pushed.single().output)
        assertEquals(lib.tracks.first().id, pushed.single().track)
        assertEquals(Enums.Output.wired, state(device(emptyList())).output)
    }

    @Test
    fun nextAndPrevStepTheListAndPrevRestartsPastThreeSeconds() {
        val d = device()
        assertTrue((transport(d, Enums.TransportCmd.next) as Answer.Ok).state)
        val b = state(d)
        assertEquals("Bling-Bang-Bang-Born", b.title)
        assertEquals(listOf("Creepy Nuts"), b.artists)
        assertEquals(0L, b.durationMs)
        assertEquals(5_000L, b.positionMs)
        transport(d, Enums.TransportCmd.prev)
        assertEquals("Bling-Bang-Bang-Born", state(d).title)
        assertEquals(0L, state(d).positionMs)
        transport(d, Enums.TransportCmd.prev)
        assertEquals("Idol", state(d).title)
        transport(d, Enums.TransportCmd.prev)
        assertEquals("Idol", state(d).title)
        transport(d, Enums.TransportCmd.next)
        transport(d, Enums.TransportCmd.next)
        val c = state(d)
        assertEquals("Plain Song", c.title)
        assertEquals(listOf("Someone", "Other"), c.artists)
        assertEquals(false, c.playing)
        transport(d, Enums.TransportCmd.next)
        assertEquals("Plain Song", state(d).title)
    }

    @Test
    fun pauseAndPlayFlipPlayingWhileSeekShuffleAndRepeatAreRefusedWithNoText() {
        val d = device()
        transport(d, Enums.TransportCmd.pause)
        assertEquals(false, state(d).playing)
        t += 5_000
        assertEquals(0L, state(d).positionMs)
        transport(d, Enums.TransportCmd.toggle)
        assertEquals(true, state(d).playing)
        t += 2_000
        assertEquals(2_000L, state(d).positionMs)
        refused(transport(d, Enums.TransportCmd.seek, seekMs = 10_000), "")
        refused(transport(d, Enums.TransportCmd.shuffle, on = true), "")
        refused(req(d, Msg.TRANSPORT, Req.transport(Enums.TransportCmd.repeat, repeat = Enums.Repeat.all)), "")
        assertEquals(2_000L, state(d).positionMs)
    }

    @Test
    fun theReceiverRefusesLibraryPlayWithItsText() {
        val d = device()
        val rev = state(d).libRev
        refused(req(d, Msg.PLAY, Req.play(Enums.View.songs, "", rev, 0)), RECEIVER_TEXT)
        refused(req(d, Msg.PLAY, Req.play(tracks = listOf(lib.tracks.first().id))), RECEIVER_TEXT)
        refused(req(d, Msg.QUEUE_OP, Req.queueOp(Enums.QueueCmd.playAt, rev = state(d).queueRev, slot = 1)), RECEIVER_TEXT)
        refused(req(d, Msg.ACTION, Req.action(Enums.ActionCmd.shuffleLibrary)), RECEIVER_TEXT)
        assertTrue(req(d, Msg.QUEUE_OP, Req.queueOp(Enums.QueueCmd.page, offset = 0, count = 10)) is Answer.Ok)
    }

    @Test
    fun toggleAndPlayWithNoPhoneLineAreRefusedUnderTheReceiver() {
        val d = device(emptyList())
        refused(transport(d, Enums.TransportCmd.toggle), RECEIVER_TEXT)
        refused(transport(d, Enums.TransportCmd.play), RECEIVER_TEXT)
        assertTrue(transport(d, Enums.TransportCmd.next) is Answer.Ok)
    }

    @Test
    fun withoutTheReceiverLibraryPlayPlaysAndAPausedLineIsNotHeld() {
        val d = device(receiver = false)
        val rev = state(d).libRev
        assertTrue(req(d, Msg.PLAY, Req.play(Enums.View.songs, "", rev, 2)) is Answer.Ok)
        assertEquals(Enums.Source.phone, state(d).source)
        transport(d, Enums.TransportCmd.pause)
        val s = state(d)
        assertEquals(Enums.Source.local, s.source)
        assertEquals(Enums.Output.wired, s.output)
        assertEquals(lib.tracks[2].id, s.track)
    }

    @Test
    fun aPausedLineStaysCurrentUnderTheReceiver() {
        val d = device()
        transport(d, Enums.TransportCmd.pause)
        tick(d)
        t += 600_000
        assertTrue(tick(d).isEmpty())
        val s = state(d)
        assertEquals(Enums.Source.phone, s.source)
        assertEquals(Enums.Output.phoneReceiver, s.output)
        assertEquals(false, s.playing)
        assertEquals("Idol", s.title)
        transport(d, Enums.TransportCmd.play)
        assertEquals(true, state(d).playing)
    }

    @Test
    fun phoneSuspendFallsBackToTheLocalTrackAndAPlayResumesTheLine() {
        val d = device(faults = arrayOf("phone-suspend=2960"))
        transport(d, Enums.TransportCmd.pause)
        state(d)
        t += 2_959
        assertTrue(tick(d).isEmpty())
        t += 1
        val back = tick(d).single()
        assertEquals(Enums.Source.local, back.source)
        assertEquals(Enums.Output.wired, back.output)
        transport(d, Enums.TransportCmd.toggle)
        val s = state(d)
        assertEquals(Enums.Source.phone, s.source)
        assertEquals("Idol", s.title)
        assertEquals(true, s.playing)
    }

    @Test
    fun phoneExitGivesTheLocalTrackAndEndsTheRefusals() {
        val d = device(faults = arrayOf("phone-exit=4000"))
        val rev = state(d).libRev
        refused(req(d, Msg.PLAY, Req.play(Enums.View.songs, "", rev, 0)), RECEIVER_TEXT)
        t += 4_000
        val back = tick(d).single()
        assertEquals(Enums.Source.local, back.source)
        assertEquals(Enums.Output.wired, back.output)
        assertTrue(req(d, Msg.PLAY, Req.play(Enums.View.songs, "", rev, 0)) is Answer.Ok)
        assertTrue(req(d, Msg.ACTION, Req.action(Enums.ActionCmd.shuffleLibrary)) is Answer.Ok)
        assertEquals(Enums.Source.local, state(d).source)
    }

    @Test
    fun lateMetaKeepsThePreviousArtistAlbumAndDurationWithNoExtraState() {
        val d = device(faults = arrayOf("phone-late-meta=400"))
        assertEquals("", state(d).artist)
        assertEquals(0L, state(d).durationMs)
        t += 400
        assertEquals("YOASOBI", state(d).artist)
        transport(d, Enums.TransportCmd.next)
        val early = state(d)
        assertEquals("Bling-Bang-Bang-Born", early.title)
        assertEquals("YOASOBI", early.artist)
        assertEquals("THE BOOK 3", early.album)
        assertEquals(213_000L, early.durationMs)
        t += 400
        assertTrue(tick(d).isEmpty())
        assertEquals("Creepy Nuts", state(d).artist)
        assertEquals(0L, state(d).durationMs)
    }

    @Test
    fun staleDurationCarriesThePreviousLineDurationOnAZeroLine() {
        val d = device(faults = arrayOf("phone-stale-duration"))
        transport(d, Enums.TransportCmd.next)
        assertEquals(213_000L, state(d).durationMs)
        transport(d, Enums.TransportCmd.next)
        assertEquals(180_000L, state(d).durationMs)
    }

    @Test
    fun phoneSeekPushesTheJumpWithinOneTick() {
        val d = device(faults = arrayOf("phone-seek=3000:90000"))
        state(d)
        t += 2_000
        assertTrue(tick(d).isEmpty())
        t += 1_000
        assertEquals(90_000L, tick(d).single().positionMs)
        t += 1_000
        assertTrue(tick(d).isEmpty())
    }

    @Test
    fun aPlayingLineIsPushedEveryStateInterval() {
        val d = device()
        state(d)
        t += Proto.stateIntervalMs.toLong() - 1
        assertTrue(tick(d).isEmpty())
        t += 1
        assertEquals("Idol", tick(d).single().title)
    }

    @Test
    fun theCoverIsHeldBackForTheArtLateDelay() {
        ImageIO.write(BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB), "jpeg", File(tmp.root, "idol.jpg"))
        val jpeg = File(tmp.root, "idol.jpg").readBytes()
        val f = File(tmp.root, "phone.tsv")
        f.writeText("Idol\tYOASOBI\tTHE BOOK 3\t213000\t0\t1\tPixel\t1\t12\tidol.jpg\nNo Cover\tX\t\t0\t0\t1\n")
        val lines = PhoneLine.load(f)
        assertEquals("Pixel", lines[0].name)
        assertEquals(1, lines[0].trackNo)
        assertEquals(12, lines[0].count)
        assertNull(lines[1].name)
        assertNull(lines[1].cover)
        val p = Phone(Options(Board.of("v1"), Faults(listOf("phone-art-late=2000")), phone = lines, receiver = true)) { t }
        assertNull(p.cover())
        t += 2_000
        assertArrayEquals(jpeg, p.cover()?.bytes)
        assertEquals(200, p.cover()?.w)
    }
}
