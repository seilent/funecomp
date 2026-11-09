package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.LyrLine
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.state.LyricsFetch
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.Control
import net.seilent.funecomp.ui.Held
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class VolumeOverlayTouchTest {
    @get:Rule
    val rule = createComposeRule()

    private var size by mutableStateOf(SIZES.first { it.name == "411x960" })
    private lateinit var c: SimControl
    private lateinit var shell: Shell
    private val seeks = ArrayList<Long?>()
    private val steps = ArrayList<Int>()

    private fun start() {
        c = SimControl("ap80")
        lyricsOn()(c)
        val track = c.device.playback.state.track!!
        val lines = (0 until 12).map { LyrLine(it * 4_000L, "line $it") }
        val control = object : Control by c {
            override fun transport(cmd: Int, seekMs: Long?, on: Boolean?, repeat: Int?) {
                if (cmd == Enums.TransportCmd.seek) seeks += seekMs
                c.transport(cmd, seekMs, on, repeat)
            }

            override fun volume(detents: Int) {
                steps += detents
                c.volume(detents)
            }
        }
        shell = Shell(Nav(listOf(Page.Home, Page.NowPlaying)))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val s = size
            Box(Modifier.requiredSize(s.w.dp, s.h.dp)) {
                App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), control, shell, lyrics = LyricsFetch.Show.Lines(track, LyricsFetch.Copy(1, Enums.LyricsKind.synced, lines)), insets = s.bars)
            }
        }
        settle(1000)
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun open() {
        shell.overlay.show()
        settle(400)
    }

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun bounds(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun tap(at: Offset) {
        rule.onRoot().performTouchInput { click(at) }
        rule.waitForIdle()
    }

    private fun red(at: Offset) = rule.onRoot().pixels().also { System.gc() }.getRGB(at.x.toInt(), at.y.toInt()) shr 16 and 0xff

    private fun ui() = rule.onNodeWithTag("np").fetchSemanticsNode().size.let { Ui.of(it.width.toFloat(), it.height.toFloat()) }

    @Test
    fun noTwoTargetBoxesCrossOnEveryWindow() {
        start()
        for (s in SIZES) {
            size = s
            settle(1000)
            open()
            val boxes = listOf("overlay.plus", "overlay.minus", "overlay.prev", "overlay.ring", "overlay.next", "overlay.exit.hit").map { it to bounds(it) }
            for (i in boxes.indices) for (j in i + 1 until boxes.size) {
                assertFalse("${s.name} ${boxes[i].first} ${boxes[j].first}", boxes[i].second.deflate(0.5f).overlaps(boxes[j].second.deflate(0.5f)))
            }
            val u = ui().u
            assertEquals(s.name, Tok.vol_ring * u, bounds("overlay.ring").width, 1f)
            assertEquals(s.name, Tok.vol_hit * u, bounds("overlay.plus").width, 1f)
            assertEquals(s.name, Tok.adjustExit * u, bounds("overlay.exit.hit").width, 1f)
            assertEquals(s.name, bounds("overlay.plus.glyph").center.y, bounds("overlay.plus").center.y, 1f)
            assertEquals(s.name, bounds("overlay.next.glyph").center.x, bounds("overlay.next").center.x, 1f)
            shell.overlay.hide()
            settle(1000)
        }
    }

    @Test
    fun aClosingTapFadesOutOver100AndTheTimeoutOver380() {
        start()
        open()
        val glyph = bounds("overlay.plus.glyph").center
        val full = red(glyph)
        val ring = bounds("overlay.ring")
        tap(Offset((ring.right + bounds("overlay.next").left) / 2, ring.center.y))
        val closed = rule.mainClock.currentTime
        assertFalse(shell.overlay.visible)
        var start = red(glyph)
        repeat(6) {
            if (start >= full - 2) {
                rule.mainClock.advanceTimeByFrame()
                rule.waitForIdle()
                start = red(glyph)
            }
        }
        repeat(3) { rule.mainClock.advanceTimeByFrame() }
        rule.waitForIdle()
        val later = red(glyph)
        rule.mainClock.advanceTimeBy(closed + Held.Vol.motion_volumeTouchClose.toLong() + 48 - rule.mainClock.currentTime)
        rule.waitForIdle()
        assertFalse(exists("overlay"))
        settle(500)
        val under = red(glyph)
        assertFalse(exists("overlay"))
        assertEquals(3 * 16f / Held.Vol.motion_volumeTouchClose, (start - later).toFloat() / (full - under), 0.06f)
        open()
        settle(Tok.motion_volumeHide.toLong() - 400 + Held.Vol.motion_volumeTouchClose.toLong())
        assertTrue(exists("overlay"))
        settle(Tok.motion_volumeFade.toLong())
        assertFalse(exists("overlay"))
    }

    @Test
    fun exitAndTheRingRowBandOffTheBoxesClose() {
        start()
        open()
        tap(bounds("overlay.exit.hit").center)
        assertFalse(shell.overlay.visible)
        settle(500)
        open()
        val ring = bounds("overlay.ring")
        val next = bounds("overlay.next")
        tap(Offset((ring.right + next.left) / 2, ring.center.y))
        assertFalse(shell.overlay.visible)
    }

    @Test
    fun exitStillClosesDuringTheTimeoutFade() {
        start()
        open()
        settle(Tok.motion_volumeHide.toLong() - 400 + 50)
        assertTrue(shell.overlay.visible)
        tap(bounds("overlay.exit.hit").center)
        assertFalse(shell.overlay.visible)
        settle(500)
        assertTrue(exists("np"))
    }

    @Test
    fun anArtTapUnderTheOverlayClosesItAndLeavesLyricsAsTheyWere() {
        start()
        assertFalse(exists("np.lyrics"))
        open()
        tap(bounds("np.art").center)
        settle(1000)
        assertFalse(shell.overlay.visible)
        assertFalse(exists("np.lyrics"))
    }

    @Test
    fun anArtLongPressUnderItClosesAtTheUpAndOpensNoSheet() {
        start()
        open()
        rule.onRoot().performTouchInput { longClick(bounds("np.art").center) }
        settle(1000)
        assertFalse(shell.overlay.visible)
        assertFalse(shell.sheet.shown)
    }

    @Test
    fun aDragUnderItMovesNothing() {
        start()
        open()
        val art = bounds("np.art")
        rule.onRoot().performTouchInput { swipe(Offset(art.left + 4f, art.bottom + 8f), Offset(art.right - 4f, art.bottom + 8f), 300) }
        settle(400)
        assertEquals(emptyList<Long?>(), seeks)
        assertTrue(shell.overlay.visible)
    }

    @Test
    fun duringTheTimeoutFadeTheArtGetsTheTap() {
        start()
        open()
        settle(Tok.motion_volumeHide.toLong() - 400 + 50)
        assertTrue(shell.overlay.visible)
        tap(bounds("np.art").center)
        settle(1000)
        assertTrue(exists("np.lyrics"))
    }

    @Test
    fun rightAfterHideTheArtGetsTheTap() {
        start()
        open()
        shell.overlay.hide()
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        tap(bounds("np.art").center)
        settle(1000)
        assertTrue(exists("np.lyrics"))
    }

    @Test
    fun duringTheTimeoutFadePlusStepsAndShowsAgain() {
        start()
        open()
        settle(Tok.motion_volumeHide.toLong() - 400 + 50)
        steps.clear()
        tap(bounds("overlay.plus").center)
        assertTrue(shell.overlay.visible)
        assertEquals(listOf(1), steps)
        settle(Tok.motion_volumeHide.toLong() - 200)
        assertTrue(exists("overlay"))
    }

    @Test
    fun plusStepsOnceAndKeepsItOpen() {
        start()
        open()
        steps.clear()
        tap(bounds("overlay.plus").center)
        settle(100)
        assertEquals(listOf(1), steps)
        assertTrue(shell.overlay.visible)
    }

    @Test
    fun theIdleRestartsAtAClosingTap() {
        start()
        val shuffle = bounds("np.shuffle.ring").center
        val lit = red(shuffle)
        open()
        settle(1000)
        tap(bounds("np.art").center)
        settle(Tok.motion_idle.toLong() - 600)
        assertEquals(lit.toFloat(), red(shuffle).toFloat(), 4f)
        settle(600 + Tok.motion_idleFade.toLong() + 100)
        assertTrue(red(shuffle) < lit - 20)
    }
}
