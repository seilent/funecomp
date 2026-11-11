package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.Ui
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.pow

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class VolumeLegsTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var c: SimControl
    private lateinit var shell: Shell

    private class Read(val t: Long, val y: Float, val w: Float, val group: Float, val number: Float, val exit: Float, val text: Float, val dx: Float, val num: String, val ring: Float)

    private lateinit var rest: Read
    private var frameMs = 0L

    private val Read.k get() = (y - ring) / (rest.y - rest.ring)

    private val Read.size get() = w / rest.w

    private fun start() {
        c = SimControl("v1")
        c.device = c.device.withState(c.device.playback.state.copy(artist = "Utada Hikaru", title = "First Love", volumePct = 40), 0)
        shell = Shell(Nav(listOf(Page.Home)))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LaunchedEffect(Unit) { while (true) withFrameMillis { frameMs = it } }
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, "V1", everLive = true), c, shell) }
        }
        settle(1000)
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun frame() {
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }

    private fun until(t: Long) {
        if (t > rule.mainClock.currentTime + 16) settle(t - 16 - rule.mainClock.currentTime)
        while (rule.mainClock.currentTime < t) frame()
    }

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun node(tag: String): SemanticsNode = rule.onAllNodes(hasTestTag(tag) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes().single()

    private fun alpha(tag: String): Float {
        val out = ArrayList<TextLayoutResult>()
        node(tag).config[SemanticsActions.GetTextLayoutResult].action?.invoke(out)
        return out.single().layoutInput.style.color.alpha
    }

    private fun text(tag: String) = node(tag).config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: ""

    private fun ui() = bounds("overlay").let { Ui.of(it.width, it.height) }

    private fun dx(tag: String): Float {
        val o = bounds("overlay")
        val u = ui().u
        return (node(tag).positionInRoot.x - (o.left + Tok.vol_textX * u)) / u
    }

    private fun ringY() = bounds("overlay.ring.ring").center.y

    private fun read(): Read {
        val plus = bounds("overlay.plus.glyph")
        return Read(
            frameMs, plus.center.y, plus.width, alpha("overlay.volume"),
            alpha("overlay.number"), alpha("overlay.exit"), alpha("overlay.artist"), dx("overlay.artist"), text("overlay.number"), ringY(),
        )
    }

    private fun pow17(t: Float) = if (t >= 1f) 1f else 1f - (1f - t.coerceIn(0f, 1f)).pow(1.7f)

    private fun cubicOut(t: Float) = 1f - (1f - t.coerceIn(0f, 1f)).pow(3)

    private fun easeOut(t: Float) = t.coerceIn(0f, 1f).let { it * (2f - it) }

    private fun easeIn(t: Float) = t.coerceIn(0f, 1f).let { it * it }

    private fun p(e: Float, d: Float) = (e / d).coerceIn(0f, 1f)

    private fun p(e: Long, d: Float) = p(e.toFloat(), d)

    private fun open(): Pair<Float, List<Read>> {
        shell.overlay.show()
        var n = 0
        while (!exists("overlay.plus.glyph")) {
            check(n++ < 5) { "no overlay after the show" }
            frame()
        }
        val first = frameMs
        val reads = ArrayList<Read>()
        while (frameMs < first + REST_AFTER) {
            reads += read()
            frame()
        }
        rest = read()
        return first.toFloat() to reads
    }

    private fun resting() {
        rest = read()
    }

    @Test
    fun theGroupGrowsOutOfTheRingAndFadesIn() {
        start()
        val (t0, reads) = open()
        val growing = reads.count { it.k in 0.03f..0.97f }
        assertTrue("too few frames in the grow: $growing", growing >= 4)
        for (r in reads) {
            val e = r.t - t0
            assertEquals("the group k on PowOut17 over ${Tok.motion_volumeGroup} from ${Tok.motion_volumeGroupLead} ahead at $e", pow17(p(e + Tok.motion_volumeGroupLead, Tok.motion_volumeGroup)), r.k, 0.01f)
            assertEquals("size at $e", Tok.motion_volumeGroupScale + (1f - Tok.motion_volumeGroupScale) * r.k, r.size, 0.01f)
            assertEquals("group alpha at $e", cubicOut(p(e + Tok.motion_volumeGroupLead.toLong(), Tok.motion_volumeGroupFade)), r.group, 0.01f)
        }
    }

    @Test
    fun theNumberAndExitComeInLate() {
        start()
        val (t0, reads) = open()
        for (r in reads) {
            val e = r.t - t0
            val want = pow17(p(e - Tok.motion_volumeNumberAt.toLong(), Tok.motion_volumeNumberIn))
            assertEquals("number at $e", want, r.number, 0.01f)
            assertEquals("exit at $e", pow17(p(e - Tok.motion_volumeExitAt.toLong(), Tok.motion_volumeExitIn)), r.exit, 0.01f)
        }
    }

    @Test
    fun theTextFadesInAndSlidesFromTheRight() {
        start()
        val (t0, reads) = open()
        until((t0 + Tok.motion_volumeOpenEnd).toLong() + 32)
        val all = reads + read()
        for (r in all) {
            val e = r.t - t0
            assertEquals("text alpha at $e", easeOut(p(e - Tok.motion_volumeTextFadeAt.toLong(), Tok.motion_volumeTextFadeIn)), r.text, 0.01f)
            assertEquals("text x at $e", Tok.motion_volumeTextShift * (1f - easeOut(p(e - Tok.motion_volumeTextAt.toLong(), Tok.motion_volumeTextSlide))), r.dx, 0.3f)
        }
    }

    @Test
    fun theNumberHoldsThenFadesOnEaseIn() {
        start()
        val (t0, _) = open()
        val hold = (t0 + Tok.motion_volumeOpenEnd + Tok.motion_volumeNumberHold).toLong()
        until(hold - 32)
        assertEquals(1f, read().number, 0.005f)
        until(hold + (Tok.motion_volumeNumberFade / 2).toLong())
        val r = read()
        assertEquals("the number fade at ${r.t - hold}", 1f - easeIn(p(r.t - hold, Tok.motion_volumeNumberFade)), r.number, 0.01f)
        assertEquals("the rest of the plate stays", 1f, r.group, 0.005f)
        until(hold + Tok.motion_volumeNumberFade.toLong() + 16)
        assertEquals(0f, read().number, 0.005f)
    }

    @Test
    fun aChangeCutsTheNumberAndRecoversItsFadeLinear() {
        start()
        val (t0, _) = open()
        until((t0 + Tok.motion_volumeOpenEnd + Tok.motion_volumeNumberHold + Tok.motion_volumeNumberFade).toLong() + 32)
        assertEquals(0f, read().number, 0.005f)
        c.device = c.device.withState(c.device.playback.state.copy(volumePct = 41), 0)
        frame()
        assertEquals("the new number on the next frame", "41", read().num)
        val reads = (0 until 12).map { frame(); read() }
        val rising = reads.filter { it.number in 0.05f..0.95f }
        assertTrue("too few frames in the recovery: ${rising.size}", rising.size >= 3)
        val from = rising.map { it.t - it.number * Tok.motion_volumeNumberBack }
        from.forEach { assertEquals("Linear over ${Tok.motion_volumeNumberBack} from one change", from.first(), it, 2f) }
    }

    @Test
    fun theRestHideHoldsThenDimsSlowThenFast() {
        start()
        val (t0, _) = open()
        val fade = (t0 + Tok.motion_volumeOpenEnd + Tok.motion_volumeRestHold).toLong()
        until(fade - 32)
        assertTrue("drawn through the rest hold", exists("overlay.plus.glyph"))
        resting()
        read().let {
            assertEquals(1f, it.exit, 0.005f)
            assertEquals(1f, it.k, 0.002f)
        }
        until(fade + (Tok.motion_volumeRestSlow / 2).toLong())
        read().let {
            val q = p(it.t - fade, Tok.motion_volumeRestSlow)
            assertEquals("slow alpha", 1f + (Tok.motion_volumeRestLevel - 1f) * q, it.exit, 0.01f)
            assertEquals("slow k", 1f + (Tok.motion_volumeCloseSlowK - 1f) * q, it.k, 0.002f)
            assertEquals("slow size", 1f, it.size, 0.002f)
        }
        val fast = fade + Tok.motion_volumeRestSlow.toLong()
        until(fast + (Tok.motion_volumeRestFast / 2).toLong())
        read().let {
            val q = p(it.t - fast, Tok.motion_volumeRestFast)
            assertEquals("fast alpha", Tok.motion_volumeRestLevel * (1f - q), it.exit, 0.01f)
            assertEquals("fast k", Tok.motion_volumeCloseSlowK + (Tok.motion_volumeCloseK - Tok.motion_volumeCloseSlowK) * q, it.k, 0.002f)
            assertEquals("fast size", 1f + (Tok.motion_volumeCloseSize - 1f) * q, it.size, 0.002f)
        }
        until(fast + Tok.motion_volumeScrimOut.toLong() + 32)
        assertFalse(exists("overlay"))
        assertFalse(shell.overlay.visible)
    }

    @Test
    fun aHeldFingerHoldsOffTheRestHide() {
        start()
        val (t0, _) = open()
        val plate = bounds("overlay")
        rule.onRoot().performTouchInput { down(Offset(plate.right - 4f, plate.center.y)) }
        val fade = (t0 + Tok.motion_volumeOpenEnd + Tok.motion_volumeRestHold).toLong()
        until(fade + Tok.motion_volumeRestSlow.toLong())
        assertTrue("no rest hide while held", exists("overlay.plus.glyph"))
        assertEquals("no rest hide while held", 1f, read().exit, 0.005f)
        rule.onRoot().performTouchInput { moveBy(Offset(0f, -200f)) }
        rule.onRoot().performTouchInput { up() }
        val up = rule.mainClock.currentTime
        until(up + Tok.motion_volumeRestHold.toLong() - 32)
        assertEquals("the hold restarts at the up", 1f, read().exit, 0.005f)
        until(up + Tok.motion_volumeRestHold.toLong() + (Tok.motion_volumeRestSlow / 2).toLong())
        assertTrue(read().exit < 0.9f)
    }

    @Test
    fun aKeyDuringTheRestHideRevivesFromItsValue() {
        start()
        val (t0, _) = open()
        val fade = (t0 + Tok.motion_volumeOpenEnd + Tok.motion_volumeRestHold).toLong()
        until(fade + (Tok.motion_volumeRestSlow / 2).toLong())
        assertTrue("in the rest hide", exists("overlay.plus.glyph"))
        val dim = read().exit
        assertTrue("dimmed $dim", dim < 0.8f)
        shell.overlay.show()
        val reads = (0 until 12).map { frame(); read() }
        val rising = reads.filter { it.exit > dim + 0.02f && it.exit < 0.98f }
        assertTrue("too few frames in the revive: ${rising.size}", rising.size >= 2)
        rising.zipWithNext().forEach { (a, b) -> assertEquals("Linear over ${Tok.motion_volumeNumberBack}", (b.t - a.t) / Tok.motion_volumeNumberBack, b.exit - a.exit, 0.01f) }
        assertTrue(reads.first().exit >= dim - 0.06f)
        assertEquals(1f, reads.last().exit, 0.005f)
    }

    @Test
    fun aTouchCloseFadesThePartsLinearToTheCloseSize() {
        start()
        open()
        settle(200)
        resting()
        val exit = bounds("overlay.exit.hit").center
        rule.onRoot().performTouchInput { down(exit) }
        rule.onRoot().performTouchInput { up() }
        val at = rule.mainClock.currentTime
        assertFalse(shell.overlay.visible)
        val reads = ArrayList<Read>()
        while (exists("overlay.plus.glyph") && reads.size < 10) {
            frame()
            if (exists("overlay.plus.glyph")) reads += read()
        }
        assertTrue("too few close frames: ${reads.size}", reads.size >= 2)
        for (r in reads) {
            val q = p(r.t - at, Tok.motion_volumeTouchClose)
            assertEquals("parts at ${r.t - at}", 1f - q, r.exit, 0.06f)
            assertEquals("k at ${r.t - at}", 1f + (Tok.motion_volumeCloseK - 1f) * q, r.k, 0.004f)
            assertEquals("size at ${r.t - at}", 1f + (Tok.motion_volumeCloseSize - 1f) * q, r.size, 0.004f)
        }
        until(at + Tok.motion_volumeTouchScrim.toLong() + 32)
        assertFalse(exists("overlay"))
    }

    @Test
    fun aTrackChangeSendsTheOldLinesOutLeftAndTheNewInFromTheRight() {
        start()
        open()
        settle(200)
        c.device = c.device.withState(c.device.playback.state.copy(artist = "Hikaru Utada", title = "Automatic"), 0)
        val reads = ArrayList<List<Float>>()
        var n = 0
        while (!exists("overlay.artist.old")) {
            check(n++ < 5) { "no old lines after the change" }
            frame()
        }
        while (exists("overlay.artist.old") && reads.size < 30) {
            reads += listOf(rule.mainClock.currentTime.toFloat(), dx("overlay.artist.old"), alpha("overlay.artist.old"), dx("overlay.title.old"), alpha("overlay.title.old"), dx("overlay.artist"), alpha("overlay.artist"), dx("overlay.title"))
            frame()
        }
        assertEquals("HIKARU UTADA", text("overlay.artist"))
        val starts = reads.filter { it[1] < -0.5f && it[1] > -Tok.motion_volumeTextOutShift + 0.5f }.map { it[0] + it[1] / Tok.motion_volumeTextOutShift * Tok.motion_volumeTextOut }
        assertTrue("too few frames in the old leg: ${starts.size}", starts.size >= 2)
        val c0 = starts.average().toFloat()
        starts.forEach { assertEquals("old artist Linear ${Tok.motion_volumeTextOutShift} u over ${Tok.motion_volumeTextOut}", c0, it, 2f) }
        for (r in reads) {
            val e = r[0] - c0
            val gone = p(e, Tok.motion_volumeTextOut)
            assertEquals("old title shift at $e", -Tok.motion_volumeTextOutShift * gone * Tok.motion_volumeTextTitleOut, r[3], 0.3f)
            assertEquals("old artist alpha at $e", 1f - p(e, Tok.motion_volumeTextOutFade), r[2], 0.02f)
            assertEquals("old title alpha at $e", 1f - easeOut(gone), r[4], 0.02f)
            val at = Tok.motion_volumeTextInAt
            val come = if (e < at) 0f else p(e - at, Tok.motion_volumeTextIn)
            val inA = if (e < at) 0f else p(e - at, Tok.motion_volumeTextInFade)
            assertEquals("new artist alpha at $e", inA, r[6], 0.02f)
            assertEquals("new artist x at $e", Tok.motion_volumeTextInShift * (1f - come), r[5], 0.3f)
            assertEquals("new title x at $e", Tok.motion_volumeTextInShift * (1f - come) * Tok.motion_volumeTextTitleIn, r[7], 0.3f)
        }
    }

    private companion object {
        val REST_AFTER = Tok.motion_volumeOpenEnd.toLong() + 80L
    }
}
