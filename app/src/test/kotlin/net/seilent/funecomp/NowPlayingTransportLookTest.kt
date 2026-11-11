package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LayoutTok
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.Ui
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.awt.image.BufferedImage
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class NowPlayingTransportLookTest {
    @get:Rule
    val rule = createComposeRule()

    private var size by mutableStateOf(SIZES.first { it.name == "411x960" })

    private lateinit var shell: Shell

    private fun start(pages: List<Page> = listOf(Page.Home, Page.NowPlaying)): SimControl {
        val c = SimControl("ap80")
        shell = Shell(Nav(pages))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val s = size
            Box(Modifier.requiredSize(s.w.dp, s.h.dp)) { App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), c, shell, insets = s.bars) }
        }
        settle(1000)
        return c
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun wake() {
        val np = rule.onNodeWithTag("np").fetchSemanticsNode().boundsInRoot
        rule.onRoot().performTouchInput {
            down(Offset(np.left + EMPTY_X, np.center.y))
            up()
        }
        settle(WOKEN)
    }

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun ui(): Ui {
        val np = rule.onNodeWithTag("np").fetchSemanticsNode().boundsInRoot
        return Ui.of(np.width, np.height)
    }

    private fun img(tag: String): BufferedImage = rule.onNodeWithTag(tag).pixels().also { System.gc() }

    private fun luma(img: BufferedImage): Int {
        var m = 0
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val p = img.getRGB(x, y)
            m = maxOf(m, (299 * (p shr 16 and 0xff) + 587 * (p shr 8 and 0xff) + 114 * (p and 0xff)) / 1000)
        }
        return m
    }

    private fun luma(tag: String) = luma(img(tag))

    private fun stroke(tag: String): Int {
        val im = img(tag)
        val y = im.height / 2
        var x = 0
        while (x < im.width && (im.getRGB(x, y) shr 8 and 0xff) < BRIGHT) x++
        var run = 0
        while (x < im.width && (im.getRGB(x, y) shr 8 and 0xff) >= BRIGHT) {
            run++
            x++
        }
        return run
    }

    private fun lit(name: String, tag: String) = assertEquals("$name $tag", FULL, luma(tag), TOLERANCE)

    private fun mid(name: String, tag: String) = assertEquals("$name $tag", MID, luma(tag), TOLERANCE)

    private fun overTile(alpha: Float): Int {
        val t = Tok.color_tile.toInt()
        val under = (299 * (t shr 16 and 0xff) + 587 * (t shr 8 and 0xff) + 114 * (t and 0xff)) / 1000
        return (alpha * FULL + (1 - alpha) * under).roundToInt()
    }

    private fun play() = if (exists("np.toggle.pause")) "np.toggle.pause" else "np.toggle.play"

    private fun heart() = if (exists("np.heart.on")) "np.heart.on" else "np.heart.off"

    private fun assertEquals(name: String, want: Int, got: Int, tolerance: Int) = assertEquals(name, want.toFloat(), got.toFloat(), tolerance.toFloat())

    @Test
    fun thePlayControlsAreLitAndTheTogglesShowTheirStateAtOpen() {
        start()
        for (s in SIZES) {
            size = s
            settle(1000)
            wake()
            lit(s.name, "np.prev")
            lit(s.name, "np.toggle")
            lit(s.name, "np.nextTrack")
            mid(s.name, "np.shuffle")
            mid(s.name, "np.repeat")
            assertEquals("${s.name} np.heart.off", overTile(Tok.alpha_mid), luma("np.heart.off"), TOLERANCE)
        }
    }

    @Test
    fun aTapOnNextLeavesPrevAndPlayLit() {
        start()
        for (s in SIZES) {
            size = s
            settle(1000)
            wake()
            rule.onNodeWithTag("np.nextTrack").performClick()
            settle(600)
            lit(s.name, "np.prev")
            lit(s.name, "np.toggle")
            lit(s.name, "np.nextTrack")
            mid(s.name, "np.shuffle")
        }
    }

    @Test
    fun theTogglesAndTheHeartLightWhenOn() {
        val c = start()
        c.transport(Enums.TransportCmd.shuffle, on = true)
        c.transport(Enums.TransportCmd.repeat, repeat = Enums.Repeat.one)
        c.action(Enums.ActionCmd.favorite, c.device.playback.state.track)
        settle(1000)
        for (s in SIZES) {
            size = s
            settle(1000)
            wake()
            lit(s.name, "np.shuffle")
            lit(s.name, "np.repeat")
            lit(s.name, "np.heart.on")
        }
    }

    @Test
    fun noRingOfTheRowDrawsTheFocusStroke() {
        start()
        val idle = ui().px(Tok.ringStrokeIdle)
        val side = ui().px(LayoutTok.npRingStroke)
        assertEquals("play at open", idle, stroke(play()).toFloat(), STROKE_TOLERANCE)
        assertEquals("shuffle at open", side, stroke("np.shuffle").toFloat(), STROKE_TOLERANCE)
        rule.onNodeWithTag("np.toggle").performClick()
        settle(600)
        assertEquals("play after a tap", idle, stroke(play()).toFloat(), STROKE_TOLERANCE)
        rule.onNodeWithTag("np.heart").performClick()
        settle(600)
        assertEquals("heart after a tap", side, stroke(heart()).toFloat(), STROKE_TOLERANCE)
    }

    private fun red(img: BufferedImage, at: Offset) = img.getRGB(at.x.roundToInt(), at.y.roundToInt()) shr 16 and 0xff

    @Test
    fun aPressGlowsThePrevAndNextGlyphsAtRestSize() {
        start()
        wake()
        val u = ui().u
        for (tag in listOf("np.prev", "np.nextTrack")) {
            val box = rule.onNodeWithTag("$tag.glyph").fetchSemanticsNode().boundsInRoot
            val past = Offset(box.left + box.width * INK_LEFT / Tok.glyphBox - u, box.center.y)
            val before = rule.onRoot().pixels().also { System.gc() }
            rule.onNodeWithTag(tag).performTouchInput { down(center) }
            settle(Tok.motion_press.toLong() + 50)
            val held = rule.onNodeWithTag("$tag.glyph").fetchSemanticsNode().boundsInRoot.width
            val lit = rule.onRoot().pixels().also { System.gc() }
            rule.onNodeWithTag(tag).performTouchInput { cancel() }
            settle(600)
            assertEquals(tag, 1f, held / box.width, 0.002f)
            assertTrue("$tag glows past its ink", red(lit, past) > red(before, past) + GLOW_LIFT)
        }
    }

    @Test
    fun theOverlayControlsAndItsLabelsAreLit() {
        start(listOf(Page.Home))
        for (s in SIZES) {
            size = s
            settle(1000)
            shell.overlay.show()
            settle(450)
            lit(s.name, "overlay.prev")
            lit(s.name, "overlay.ring")
            lit(s.name, "overlay.next")
            assertEquals("${s.name} exit", FULL, luma(rule.onNode(hasText("exit", ignoreCase = true)).pixels().also { System.gc() }), TOLERANCE)
            assertEquals("${s.name} volume", FULL, luma(rule.onNode(hasText("volume", ignoreCase = true)).pixels().also { System.gc() }), TOLERANCE)
            settle(OVERLAY_GONE)
        }
    }

    @Test
    fun theArtistRingsAreLitBeforeATap() {
        start(listOf(Page.Home, Page.Music, Page.Artist("Utada Hikaru")))
        for (s in SIZES) {
            size = s
            settle(1000)
            lit(s.name, "artist.play")
            lit(s.name, "artist.shuffle")
        }
    }

    private companion object {
        const val FULL = 255
        const val MID = 153
        const val TOLERANCE = 3
        const val INK_LEFT = 5f
        const val GLOW_LIFT = 60
        const val BRIGHT = 128
        const val STROKE_TOLERANCE = 1.5f
        const val EMPTY_X = 2f
        const val WOKEN = 600L
        val OVERLAY_GONE = OVERLAY_SPAN + 300L
    }
}
