package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class VolumeTiltTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var shell: Shell

    private fun start() {
        val c = SimControl("v1")
        c.transport(Enums.TransportCmd.next)
        shell = Shell(Nav(listOf(Page.Home, Page.NowPlaying)))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, "V1", everLive = true), c, shell) }
        }
        settle(1000)
        shell.overlay.show()
        settle(400)
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

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun capture(): BufferedImage = rule.onRoot().pixels().also { System.gc() }

    private fun barY(img: BufferedImage, x: Float, from: Float, to: Float): Float {
        val ys = (from.roundToInt() until to.roundToInt()).filter { (img.getRGB(x.roundToInt(), it) shr 16 and 0xff) > 128 }
        check(ys.isNotEmpty()) { "no bar at $x in $from to $to" }
        return (ys.first() + ys.last() + 1) / 2f
    }

    private fun targets() = listOf("overlay.plus", "overlay.minus", "overlay.prev", "overlay.ring", "overlay.next", "overlay.exit.hit").map { bounds(it) }

    @Test
    fun aHeldPlusTurnsThePlateAboutTheScreenCentre() {
        start()
        val plate = bounds("overlay")
        val minus = bounds("overlay.minus.glyph")
        val q = minus.height / 4
        val rest = barY(capture(), minus.center.x, minus.center.y - q, minus.center.y + q)
        val touch = bounds("overlay.plus").center
        rule.onRoot().performTouchInput { down(touch) }
        frame()
        val want = tiltedPoint(Offset(minus.center.x, rest), touch, plate)
        val held = barY(capture(), want.x, want.y - q, want.y + q)
        assertTrue("the plate turned: $rest to $held", abs(held - rest) > 4f)
        assertEquals(want.y, held, 1.5f)
        rule.onRoot().performTouchInput { up() }
    }

    @Test
    fun theTiltHoldsAfterTheUpAndReturnsFlat() {
        start()
        val plate = bounds("overlay")
        val minus = bounds("overlay.minus.glyph")
        val q = minus.height / 4
        val rest = barY(capture(), minus.center.x, minus.center.y - q, minus.center.y + q)
        val touch = bounds("overlay.plus").center
        val want = tiltedPoint(Offset(minus.center.x, rest), touch, plate)
        rule.onRoot().performTouchInput { down(touch) }
        frame()
        rule.onRoot().performTouchInput { up() }
        rule.mainClock.advanceTimeBy(Tok.motion_volumeTiltHold.toLong() - 40)
        rule.waitForIdle()
        assertEquals("held after the up", want.y, barY(capture(), want.x, want.y - q, want.y + q), 1.5f)
        rule.mainClock.advanceTimeBy(Tok.motion_volumeTiltReturn.toLong() + 80)
        rule.waitForIdle()
        assertEquals("flat after the return", rest, barY(capture(), minus.center.x, minus.center.y - q, minus.center.y + q), 1f)
    }

    @Test
    fun theTargetBoxesStayAtRestWhileThePlateTurns() {
        start()
        val rest = targets()
        rule.onRoot().performTouchInput { down(bounds("overlay.prev").center) }
        frame()
        val held = targets()
        rule.onRoot().performTouchInput { up() }
        rest.zip(held).forEachIndexed { i, (a, b) ->
            assertEquals("$i left", a.left, b.left, 0.5f)
            assertEquals("$i top", a.top, b.top, 0.5f)
            assertEquals("$i right", a.right, b.right, 0.5f)
            assertEquals("$i bottom", a.bottom, b.bottom, 0.5f)
        }
    }

    @Test
    fun aDownOnThePlateOffTheControlsTurnsNothing() {
        start()
        val minus = bounds("overlay.minus.glyph")
        val q = minus.height / 4
        val rest = barY(capture(), minus.center.x, minus.center.y - q, minus.center.y + q)
        val ring = bounds("overlay.ring")
        val next = bounds("overlay.next")
        val off = Offset((ring.right + next.left) / 2, bounds("overlay.plus").center.y)
        rule.onRoot().performTouchInput { down(off) }
        frame()
        assertEquals(rest, barY(capture(), minus.center.x, minus.center.y - q, minus.center.y + q), 0.5f)
        rule.onRoot().performTouchInput { up() }
    }
}
