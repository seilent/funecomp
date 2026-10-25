package net.seilent.funecomp

import android.os.SystemClock
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import net.seilent.funecomp.artist.ArtCache
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.BackdropArt
import net.seilent.funecomp.ui.Control
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Roles
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.Ui
import net.seilent.funecomp.ui.formatTime
import net.seilent.funecomp.ui.npArt
import net.seilent.funecomp.ui.npProgressY
import net.seilent.funecomp.ui.npTitleBaseline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import javax.imageio.ImageIO

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class NowPlayingTransportTest {
    @get:Rule
    val rule = createComposeRule()

    private var size by mutableStateOf(SIZES.first { it.name == "411x960" })

    private val shell = Shell(Nav(listOf(Page.Home, Page.NowPlaying)))

    private lateinit var view: View

    private fun start(art: BackdropArt? = null, wrap: (SimControl) -> Control = { it }): SimControl {
        val c = SimControl("ap80")
        val control = wrap(c)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            view = LocalView.current
            val s = size
            Box(Modifier.requiredSize(s.w.dp, s.h.dp)) { App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), control, shell, art = art, insets = s.bars) }
        }
        settle(1000)
        return c
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun bounds(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun glyphShown(): Boolean {
        val tag = if (rule.onAllNodesWithTag("np.toggle.pause").fetchSemanticsNodes().isNotEmpty()) "np.toggle.pause" else "np.toggle.play"
        val file = File.createTempFile("ring", ".png")
        rule.onNodeWithTag(tag).captureRoboImage(file.path)
        val img = ImageIO.read(file)
        file.delete()
        System.gc()
        val r = img.width * 0.3f
        var lit = 0
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val dx = x - img.width / 2f
            val dy = y - img.height / 2f
            if (dx * dx + dy * dy < r * r && (img.getRGB(x, y) shr 16 and 0xff) > 80) lit++
        }
        return lit > img.width
    }

    private fun swapsWhile(ms: Long): Int {
        var swaps = 0
        var shown = true
        var t = 0L
        while (t < ms) {
            rule.mainClock.advanceTimeByFrame()
            t += 16
            val now = glyphShown()
            if (shown && !now) swaps++
            shown = now
        }
        return swaps
    }

    private fun overlap(a: Rect, b: Rect) = minOf(a.right, b.right) - maxOf(a.left, b.left) > 1f && minOf(a.bottom, b.bottom) - maxOf(a.top, b.top) > 1f

    @Test
    fun theRowSendsPrevToggleNext() {
        val c = start()
        c.transports.clear()
        for (tag in listOf("np.prev", "np.toggle", "np.nextTrack")) {
            rule.onNodeWithTag(tag).performClick()
            settle(300)
        }
        assertEquals(listOf(Enums.TransportCmd.prev, Enums.TransportCmd.toggle, Enums.TransportCmd.next), c.transports)
    }

    @Test
    fun thePlayRingShowsPauseWhilePlaying() {
        val c = start()
        val playing = c.device.playback.state.playing == true
        assertTrue(exists(if (playing) "np.toggle.pause" else "np.toggle.play"))
        rule.onNodeWithTag("np.toggle").performClick()
        settle(300)
        assertEquals(!playing, c.device.playback.state.playing == true)
        assertTrue(exists(if (playing) "np.toggle.play" else "np.toggle.pause"))
    }

    @Test
    fun aHeldPositionStillShowsPause() {
        val c = start()
        val s = c.device.playback.state
        c.device = c.device.withState(s.copy(playing = true, positionHeld = true), 0)
        settle(300)
        assertTrue(exists("np.toggle.pause"))
    }

    @Test
    fun anEmptyQueueHasNoTransport() {
        val c = start()
        c.queueOp(Enums.QueueCmd.clear)
        settle(300)
        for (tag in listOf("np.prev", "np.toggle", "np.nextTrack")) assertFalse(tag, exists(tag))
        assertTrue(exists("np.shuffle"))
        assertTrue(exists("np.repeat"))
    }

    @Test
    fun thePlayRingSwapsOncePerGlyphChangeAndNotOnATap() {
        val c = start { sim -> object : Control by sim {
            override fun transport(cmd: Int, seekMs: Long?, on: Boolean?, repeat: Int?) {}
        } }
        assertTrue(glyphShown())
        rule.onNodeWithTag("np.toggle").performClick()
        assertEquals(0, swapsWhile(800))
        c.transport(Enums.TransportCmd.toggle)
        assertEquals(1, swapsWhile(1200))
        c.transport(Enums.TransportCmd.toggle)
        assertEquals(1, swapsWhile(1200))
    }

    @Test
    fun theHeartSitsInTheArtTopRightCornerOnEveryWindow() {
        start()
        for (s in SIZES) {
            size = s
            settle(1000)
            val np = rule.onNodeWithTag("np").fetchSemanticsNode().size
            val u = Ui.of(np.width.toFloat(), np.height.toFloat()).u
            val ring = bounds("np.heart.off")
            val art = bounds("np.art")
            assertEquals(s.name, Tok.detailRingInset * u, art.right - ring.right, 1f)
            assertEquals(s.name, Tok.detailRingInset * u, ring.top - art.top, 1f)
            assertEquals(s.name, Tok.npRing * u, ring.width, 1f)
        }
    }

    @Test
    fun theLyricsMarkSitsBesideTheArtInPortraitAndInItsCornerInLandscape() {
        val c = start()
        lyricsOn()(c)
        for (s in SIZES) {
            size = s
            settle(1000)
            val np = rule.onNodeWithTag("np").fetchSemanticsNode().size
            val ui = Ui.of(np.width.toFloat(), np.height.toFloat())
            val ring = bounds("np.mark")
            val art = bounds("np.art")
            if (ui.landscape) {
                assertEquals(s.name, Tok.detailRingInset * ui.u, art.right - ring.right, 1.5f)
                assertEquals(s.name, Tok.detailRingInset * ui.u, art.bottom - ring.bottom, 1.5f)
            } else {
                assertEquals(s.name, Tok.tileLGutter * ui.u, ring.left - art.right, 1.5f)
                assertEquals(s.name, art.bottom, ring.bottom, 1.5f)
            }
            assertEquals(s.name, Tok.npRing * ui.u, ring.width, 1.5f)
            assertEquals(s.name, Tok.npRing * ui.u, ring.height, 1.5f)
        }
    }

    @Test
    fun rowItemsDoNotOverlapTheTitleBlockOrEachOther() {
        val c = start()
        lyricsOn()(c)
        for (s in SIZES) {
            size = s
            settle(1000)
            val tags = listOf("np.shuffle", "np.prev", "np.toggle", "np.nextTrack", "np.repeat", "np.titleBlock", "np.seek", "np.heart", "np.mark.hit")
            val boxes = tags.map { it to bounds(it) }
            assertTrue(s.name, bounds("np.seek").height > 0f)
            for (i in boxes.indices) for (j in i + 1 until boxes.size) {
                assertFalse("${s.name} ${boxes[i].first} ${boxes[j].first}", overlap(boxes[i].second, boxes[j].second))
            }
            val np = rule.onNodeWithTag("np").fetchSemanticsNode().size
            val mark = bounds("np.mark.hit")
            val art = bounds("np.art")
            if (Ui.of(np.width.toFloat(), np.height.toFloat()).landscape) {
                assertTrue(s.name, mark.left >= art.left && mark.top >= art.top && mark.right <= art.right && mark.bottom <= art.bottom)
            } else {
                assertFalse(s.name, overlap(mark, art))
            }
        }
    }

    private class Seeks(private val sim: SimControl) : Control by sim {
        val seeks = ArrayList<Long?>()
        var favorites = 0
        override fun transport(cmd: Int, seekMs: Long?, on: Boolean?, repeat: Int?) {
            if (cmd == Enums.TransportCmd.seek) seeks += seekMs
            sim.transport(cmd, seekMs, on, repeat)
        }

        override fun favorite(s: State, toast: (String, String) -> Unit) {
            favorites++
            sim.favorite(s, toast)
        }
    }

    private fun ui(): Ui {
        val np = bounds("np")
        return Ui.of(np.width, np.height)
    }

    private fun bandY(): Float = bounds("np").top + (ui().npProgressY + SEEK_MID) * ui().u

    private fun text(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text

    private fun tap(at: Offset) {
        rule.onRoot().performTouchInput { click(at) }
        settle(1000)
    }

    private fun drag(from: Float, to: Float, rise: Float = 0f, line: Rect = bounds("np.art")) {
        val y = bandY()
        val u = ui().u
        rule.onRoot().performTouchInput {
            down(Offset(line.left + from * line.width, y))
            for (i in 1..DRAG_STEPS) moveTo(Offset(line.left + (from + (to - from) * i / DRAG_STEPS) * line.width, y - rise * u * i / DRAG_STEPS))
            up()
        }
        settle(300)
    }

    private fun halfway(c: SimControl) {
        val s = c.device.playback.state
        c.device = c.device.withState(s.copy(positionMs = s.durationMs!! / 2), SystemClock.elapsedRealtime())
        settle(300)
    }

    @Test
    fun aDragOnTheSeekBandSeeksOnceOnEveryWindow() {
        lateinit var seeks: Seeks
        val c = start { sim -> Seeks(sim).also { seeks = it } }
        val duration = c.device.playback.state.durationMs!!
        for (s in SIZES) {
            size = s
            settle(1000)
            for (overlay in listOf(false, true)) {
                if (overlay) {
                    shell.overlay.show()
                    settle(200)
                    assertTrue(s.name, shell.overlay.visible)
                }
                seeks.seeks.clear()
                drag(0.25f, 0.75f)
                assertEquals("${s.name} overlay $overlay ${bounds("np.seek")}", 1, seeks.seeks.size)
                assertEquals("${s.name} overlay $overlay", 0.75f * duration, seeks.seeks.single()!!.toFloat(), 0.01f * duration)
            }
            shell.overlay.hide()
            settle(1000)
        }
    }

    @Test
    fun aDragShowsTheTargetTimeAndFillThenTheDeviceAgain() {
        val c = start { sim -> object : Control by sim {
            override fun transport(cmd: Int, seekMs: Long?, on: Boolean?, repeat: Int?) {}
        } }
        val duration = c.device.playback.state.durationMs!!
        for (s in SIZES) {
            size = s
            settle(1000)
            val art = bounds("np.art")
            val y = bandY()
            rule.onRoot().performTouchInput {
                down(Offset(art.left + 0.25f * art.width, y))
                moveTo(Offset(art.left + HELD * art.width, y))
            }
            settle(100)
            assertEquals(s.name, formatTime((HELD * duration).toLong()), text("np.elapsed"))
            assertEquals(s.name, HELD * art.width, bounds("np.progress").width, 1f)
            rule.onRoot().performTouchInput { up() }
            settle(300)
            val now = c.device.positionMs(SystemClock.elapsedRealtime())
            assertEquals(s.name, formatTime(now), text("np.elapsed"))
            assertEquals(s.name, art.width * now / duration, bounds("np.progress").width, 1f)
        }
    }

    @Test
    fun aDragOffTheBandOntoTheArtKeepsTracking() {
        lateinit var seeks: Seeks
        val c = start { sim -> Seeks(sim).also { seeks = it } }
        val duration = c.device.playback.state.durationMs!!
        for (s in SIZES) {
            size = s
            settle(1000)
            seeks.seeks.clear()
            val mode = c.lyricsMode()
            val toasts = shell.toast.shows
            drag(0.25f, 0.9f, rise = OFF_BAND)
            assertEquals(s.name, 1, seeks.seeks.size)
            assertEquals(s.name, 0.9f * duration, seeks.seeks.single()!!.toFloat(), 0.01f * duration)
            assertEquals(s.name, mode, c.lyricsMode())
            assertEquals(s.name, toasts, shell.toast.shows)
            assertEquals(s.name, Page.NowPlaying, shell.nav.top)
        }
    }

    @Test
    fun theBandRunsFromTheLineForTheSk1HeightOnEveryWindow() {
        val c = start()
        halfway(c)
        for (s in SIZES) {
            size = s
            settle(1000)
            val ui = ui()
            val band = bounds("np.seek")
            val line = bounds("np.progress")
            val art = bounds("np.art")
            val margin = (Tok.edge - Tok.npSeekBandX0) * ui.u
            assertEquals(s.name, line.top, band.top, 1f)
            assertEquals(s.name, (Tok.npSeekBandBottom - Tok.npProgressY) * ui.u, band.height, 1f)
            assertEquals(s.name, art.left - margin, band.left, 1f)
            assertEquals(s.name, art.right + margin, band.right, 1.5f)
            if (!ui.landscape) {
                val titleTop = bounds("np").top + (ui.npTitleBaseline - Tok.capHeight * Roles.npTitle.size) * ui.u
                assertEquals(s.name, titleTop, band.bottom, 1f)
            }
        }
    }

    @Test
    fun theBandIsTheOnlySystemGestureExclusionOnEveryWindow() {
        start()
        for (s in SIZES) {
            size = s
            settle(1000)
            val band = bounds("np.seek")
            val rects = view.systemGestureExclusionRects
            assertEquals("${s.name} $rects", 1, rects.size)
            val r = rects.single()
            assertEquals(s.name, band.left, r.left.toFloat(), 1f)
            assertEquals(s.name, band.top, r.top.toFloat(), 1f)
            assertEquals(s.name, band.right, r.right.toFloat(), 1f)
            assertEquals(s.name, band.bottom, r.bottom.toFloat(), 1f)
            assertTrue("${s.name} $r", r.height() / view.resources.displayMetrics.density <= EDGE_LIMIT_DP)
        }
    }

    @Test
    fun theTimesRowLiesInsideTheBandOnEveryWindow() {
        val c = start()
        halfway(c)
        for (s in SIZES) {
            size = s
            settle(1000)
            val band = bounds("np.seek")
            val baseline = bounds("np.progress").top + Tok.npTimeDrop * ui().u
            assertTrue(s.name, baseline > band.top && baseline < band.bottom)
            for (tag in listOf("np.elapsed", "np.remaining")) {
                val b = bounds(tag)
                assertTrue("${s.name} $tag $b $band", b.left >= band.left - 1f && b.right <= band.right + 1f && b.top >= band.top - 1f && b.bottom <= band.bottom + 1f)
            }
        }
    }

    @Test
    fun aLongPressOnTheBandSeeksAndOpensNoBlockSheet() {
        lateinit var seeks: Seeks
        start(BackdropArt(ImageBitmap(8, 8), null, ENTRY, false)) { sim -> Seeks(sim).also { seeks = it } }
        for (s in SIZES) {
            size = s
            settle(1000)
            seeks.seeks.clear()
            val band = bounds("np.seek")
            rule.onRoot().performTouchInput { longClick(band.center) }
            settle(500)
            assertFalse(s.name, shell.sheet.shown)
            assertEquals(s.name, 1, seeks.seeks.size)
            val art = bounds("np.art")
            rule.onRoot().performTouchInput { longClick(Offset(art.center.x, art.top - ABOVE_ART * ui().u)) }
            settle(500)
            assertTrue(s.name, shell.sheet.shown)
            assertEquals(s.name, 1, seeks.seeks.size)
            shell.sheet.close()
            settle(1000)
        }
    }

    @Test
    fun theLyricsViewHasNoBand() {
        lateinit var seeks: Seeks
        start { sim ->
            lyricsOn()(sim)
            sim.lyricsMode(true)
            Seeks(sim).also { seeks = it }
        }
        for (s in SIZES) {
            size = s
            settle(1000)
            assertTrue(s.name, exists("np.lyrics"))
            assertFalse(s.name, exists("np.seek"))
            assertTrue(s.name, view.systemGestureExclusionRects.isEmpty())
            seeks.seeks.clear()
            val ui = ui()
            val left = bounds("np").left + Tok.edge * ui.u
            drag(0.25f, 0.75f, line = Rect(left, 0f, left + ui.npArt * ui.u, 0f))
            assertEquals(s.name, 0, seeks.seeks.size)
        }
    }

    @Test
    fun noDurationSendsNoSeek() {
        lateinit var seeks: Seeks
        val c = start { sim -> Seeks(sim).also { seeks = it } }
        val st = c.device.playback.state
        c.device = c.device.withState(st.copy(durationMs = null), SystemClock.elapsedRealtime())
        for (s in SIZES) {
            size = s
            settle(1000)
            assertTrue(s.name, exists("np.seek"))
            seeks.seeks.clear()
            drag(0.25f, 0.75f)
            assertEquals(s.name, 0, seeks.seeks.size)
        }
    }

    @Test
    fun theArtHeartAndProgressLineKeepTheirTapsBesideTheBand() {
        lateinit var seeks: Seeks
        val c = start { sim -> Seeks(sim).also { seeks = it } }
        c.lyricsMode(false)
        for (s in SIZES) {
            size = s
            lyricsOn()(c)
            settle(1000)
            val u = ui().u
            val art = bounds("np.art")
            tap(Offset(art.center.x, art.bottom - ART_EDGE * u))
            assertTrue("${s.name} mode", c.lyricsMode())
            assertTrue("${s.name} view", exists("np.lyrics"))
            rule.onNodeWithTag("back").performClick()
            settle(1000)
            assertFalse("${s.name} closed", exists("np.lyrics"))
            seeks.seeks.clear()
            tap(Offset(art.center.x, bounds("np").top + (ui().npProgressY + LINE * 0.5f) * u))
            assertEquals(s.name, 1, seeks.seeks.size)
            val favorites = seeks.favorites
            rule.onNodeWithTag("np.heart").performClick()
            settle(1000)
            assertEquals(s.name, favorites + 1, seeks.favorites)
        }
    }

    @Test
    fun theLandscapeMarkStillTogglesLyrics() {
        val c = start()
        lyricsOn()(c)
        c.lyricsMode(false)
        for (s in SIZES) {
            size = s
            settle(1000)
            assertTrue(s.name, exists("np.mark.hit"))
            tap(bounds("np.mark.hit").center)
            assertTrue(s.name, c.lyricsMode())
            assertTrue(s.name, exists("np.lyrics"))
            rule.onNodeWithTag("back").performClick()
            settle(1000)
            assertFalse(s.name, c.lyricsMode())
            assertFalse(s.name, exists("np.lyrics"))
        }
    }

    private companion object {
        const val SEEK_MID = (Tok.npSeekBandBottom - Tok.npProgressY) / 2
        const val DRAG_STEPS = 5
        const val HELD = 0.61f
        const val OFF_BAND = 60f
        const val ABOVE_ART = 4f
        const val ART_EDGE = 4f
        const val LINE = 2f
        const val EDGE_LIMIT_DP = 200f
        val ENTRY = ArtCache.Entry("name:automatic", "Automatic", null, "deezer", null, "https://example.invalid/a.jpg", 1, sha = "s")
    }
}
