package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.Control
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
import java.io.File
import javax.imageio.ImageIO

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class NowPlayingTransportTest {
    @get:Rule
    val rule = createComposeRule()

    private var size by mutableStateOf(SIZES.first { it.name == "411x960" })

    private fun start(wrap: (SimControl) -> Control = { it }): SimControl {
        val c = SimControl("ap80")
        val control = wrap(c)
        val shell = Shell(Nav(listOf(Page.Home, Page.NowPlaying)))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val s = size
            Box(Modifier.requiredSize(s.w.dp, s.h.dp)) { App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), control, shell, insets = s.bars) }
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
    fun rowItemsDoNotOverlapTheTitleBlockOrEachOther() {
        start()
        for (name in listOf("411x960", "360x640", "600x960", "flip960x411", "cover361x399")) {
            size = SIZES.first { it.name == name }
            settle(1000)
            val tags = listOf("np.shuffle", "np.prev", "np.toggle", "np.nextTrack", "np.repeat", "np.titleBlock", "np.seek", "np.heart")
            val boxes = tags.map { it to bounds(it) }
            for (i in boxes.indices) for (j in i + 1 until boxes.size) {
                assertFalse("$name ${boxes[i].first} ${boxes[j].first}", overlap(boxes[i].second, boxes[j].second))
            }
        }
    }
}
