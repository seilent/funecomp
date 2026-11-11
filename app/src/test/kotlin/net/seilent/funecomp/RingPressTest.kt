package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
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
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class RingPressTest {
    @get:Rule
    val rule = createComposeRule()

    private class Kind(val name: String, val hit: String, val ring: String, val stroke: Float)

    private fun start(pages: List<Page>, prep: (SimControl) -> Unit = {}) {
        val c = SimControl("ap80")
        prep(c)
        val shell = Shell(Nav(pages))
        rule.mainClock.autoAdvance = false
        rule.setUptimeContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), c, shell) }
        }
        settle(1000)
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun u(): Float {
        val size = rule.onRoot().fetchSemanticsNode().size
        return Ui.of(size.width.toFloat(), size.height.toFloat()).u
    }

    private fun capture(): BufferedImage = rule.onRoot().pixels().also { System.gc() }

    private fun red(img: BufferedImage, at: Offset) = img.getRGB(at.x.roundToInt(), at.y.roundToInt()) shr 16 and 0xff

    private fun wake() {
        val np = rule.onNodeWithTag("np").fetchSemanticsNode().boundsInRoot
        rule.onRoot().performTouchInput {
            down(Offset(np.left + 2f, np.center.y))
            up()
        }
        settle(600)
    }

    private fun pressed(k: Kind) {
        val u = u()
        val width = rule.onRoot().fetchSemanticsNode().size.width
        val rest = capture()
        rule.onNodeWithTag(k.hit, useUnmergedTree = true).performTouchInput { down(center) }
        settle(Tok.motion_pressGlow.toLong() + FRAME)
        val held = capture()
        val box = rule.onNodeWithTag(k.ring, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val side = if (box.center.x < width / 2f) 1f else -1f
        val r = box.width / 2
        val stroke = k.stroke * u * box.width / rule.onNodeWithTag(k.ring, useUnmergedTree = true).fetchSemanticsNode().size.width
        val inside = Offset(box.center.x + side * (r - stroke - DISC_IN * u), box.center.y)
        val outside = Offset(box.center.x + side * (r + HALO_OUT * u), box.center.y)
        val inBase = red(rest, inside)
        assertEquals("${k.name} disc", Tok.vol_discAlpha, (red(held, inside) - inBase).toFloat() / (255 - inBase), DISC_TOLERANCE)
        assertTrue("${k.name} halo", red(held, outside) > red(rest, outside) + HALO_LIFT)
        rule.onNodeWithTag(k.hit, useUnmergedTree = true).performTouchInput { cancel() }
        settle(Tok.motion_press.toLong() + FRAME * 2)
        val back = capture()
        assertEquals("${k.name} disc back", inBase.toFloat(), red(back, inside).toFloat(), 3f)
        assertEquals("${k.name} halo back", red(rest, outside).toFloat(), red(back, outside).toFloat(), 3f)
        settle(600)
    }

    @Test
    fun theNowPlayingRingsDrawADiscAndAHaloWhilePressed() {
        start(listOf(Page.Home, Page.NowPlaying), lyricsOn())
        wake()
        val side = LayoutTok.npRingStroke
        for (k in listOf(
            Kind("shuffle", "np.shuffle", "np.shuffle.ring", side),
            Kind("repeat", "np.repeat", "np.repeat.ring", side),
            Kind("heart", "np.heart", "np.heart.ring", side),
            Kind("play", "np.toggle", "np.toggle.ring", Tok.ringStrokeIdle),
            Kind("back", "back", "back.ring", LayoutTok.npBackRingStroke),
        )) pressed(k)
    }

    @Test
    fun theLyricsMarkScalesWithNoDiscOrHalo() {
        start(listOf(Page.Home, Page.NowPlaying), lyricsOn())
        wake()
        val u = u()
        val width = rule.onRoot().fetchSemanticsNode().size.width
        val ring = { rule.onNodeWithTag("np.mark.ring", useUnmergedTree = true).fetchSemanticsNode() }
        val at = ring().boundsInRoot
        val side = if (at.center.x < width / 2f) 1f else -1f
        val stroke = LayoutTok.npRingStroke * u
        val inside = Offset(at.center.x + side * (at.width / 2 * Tok.motion_pressScale - stroke - DISC_IN * u), at.center.y)
        val outside = Offset(at.center.x + side * (at.width / 2 + MARK_OUT * u), at.center.y)
        val rest = capture()
        rule.onNodeWithTag("np.mark.hit", useUnmergedTree = true).performTouchInput { down(center) }
        settle(Tok.motion_press.toLong() + FRAME)
        val held = capture()
        assertEquals("the mark at the press scale", Tok.motion_pressScale, ring().boundsInRoot.width / at.width, 0.01f)
        assertEquals("no disc", red(rest, inside).toFloat(), red(held, inside).toFloat(), 3f)
        assertEquals("no halo", red(rest, outside).toFloat(), red(held, outside).toFloat(), 3f)
        rule.onNodeWithTag("np.mark.hit", useUnmergedTree = true).performTouchInput { cancel() }
        settle(600)
    }

    @Test
    fun theArtistAndHomeRingsDrawADiscAndAHaloWhilePressed() {
        start(listOf(Page.Home, Page.Music, Page.Artist("Utada Hikaru")))
        pressed(Kind("artist play", "artist.play", "artist.play.ring", Tok.ringStrokeIdle))
        pressed(Kind("artist shuffle", "artist.shuffle", "artist.shuffle.ring", Tok.ringStrokeIdle))
    }

    @Test
    fun theHomeRingHaloReachesItsScaledGlow() {
        start(listOf(Page.Home))
        val u = u()
        pressed(Kind("home ring", "home.ring", "home.ring.ring", LayoutTok.homeRingStroke))
        val box = rule.onNodeWithTag("home.ring.ring", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val d = box.width.roundToInt()
        val grow = maxOf(1, (Tok.vol_glowRing * d / Tok.vol_ring).roundToInt())
        assertEquals("the reach 3.1 x 35 / 76.8", Tok.vol_glowRing * LayoutTok.homeRingSize / Tok.vol_ring, grow / u, 1f / u)
        val extent = 3 * maxOf(1, grow / 3)
        val near = Offset(box.center.x, box.top - extent / 2f)
        val past = Offset(box.center.x, box.top - extent - 2f)
        val rest = capture()
        rule.onNodeWithTag("home.ring", useUnmergedTree = true).performTouchInput { down(center) }
        settle(Tok.motion_pressGlow.toLong() + FRAME)
        val held = capture()
        assertTrue("lit inside the reach", red(held, near) > red(rest, near) + HALO_LIFT)
        assertEquals("dark past the reach", red(rest, past).toFloat(), red(held, past).toFloat(), 2f)
        rule.onNodeWithTag("home.ring", useUnmergedTree = true).performTouchInput { cancel() }
        settle(600)
    }

    private companion object {
        const val FRAME = 16L
        const val DISC_IN = 1f
        const val HALO_OUT = 0.25f
        const val MARK_OUT = 0.5f
        const val HALO_LIFT = 10
        const val DISC_TOLERANCE = 0.08f
    }
}
