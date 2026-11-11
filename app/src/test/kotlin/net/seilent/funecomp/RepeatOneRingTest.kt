package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.awt.image.BufferedImage
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class RepeatOneRingTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var c: SimControl

    private fun start(repeat: Int) {
        c = SimControl("v1")
        c.transport(Enums.TransportCmd.next)
        c.transport(Enums.TransportCmd.repeat, repeat = repeat)
        val shell = Shell(Nav(listOf(Page.Home, Page.NowPlaying)))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, "V1", everLive = true), c, shell) }
        }
        settle(1500)
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun u() = rule.onNodeWithTag("np").fetchSemanticsNode().size.let { Ui.of(it.width.toFloat(), it.height.toFloat()).u }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun lit(img: BufferedImage, x: Float, y: Float) = (img.getRGB(x.roundToInt(), y.roundToInt()) shr 16 and 0xff) > LIT

    private fun at(deg: Float, r: Float, ring: Rect): Pair<Float, Float> {
        val a = Math.toRadians(deg.toDouble())
        return ring.center.x + r * sin(a).toFloat() to ring.center.y - r * cos(a).toFloat()
    }

    @Test
    fun repeatOneDrawsTheArcFromTwelveToNineWithItsOne() {
        start(Enums.Repeat.one)
        val u = u()
        val ring = bounds("np.repeat.ring")
        val r = (ring.width - LayoutTok.npRingStroke * u) / 2
        val img = rule.onRoot().pixels().also { System.gc() }
        for (deg in 2..268 step 6) at(deg.toFloat(), r, ring).let { (x, y) -> assertTrue("$deg lit", lit(img, x, y)) }
        val (ox, oy) = at(Tok.npRepeatOneAt, LayoutTok.npRepeatOneR * u, ring)
        val one = bounds("np.repeat.one")
        assertEquals(ox, one.center.x, 1f)
        assertEquals(oy, one.center.y, 1f)
        val k = LayoutTok.npRepeatOne * u / Tok.glyphBox
        val ink = Rect(one.left + 13.94f * k - 1f, one.top + 9.76f * k - 1f, one.left + 22.2f * k + 1f, one.top + 22.24f * k + 1f)
        for (deg in 280..350 step 5) at(deg.toFloat(), r, ring).let { (x, y) -> if (!ink.contains(Offset(x, y))) assertFalse("$deg dark", lit(img, x, y)) }
        assertTrue("stem", lit(img, one.left + 18.2f * k, one.top + 16f * k))
        assertTrue("foot", lit(img, one.left + 21.2f * k, one.top + 21.24f * k))
        assertFalse("over the foot", lit(img, one.left + 21.2f * k, one.top + 16.5f * k))
        assertTrue("flag", lit(img, one.left + 15.7f * k, one.top + 11.82f * k))
        assertFalse("under the flag", lit(img, one.left + 14.6f * k, one.top + 15.5f * k))
    }

    @Test
    fun repeatAllDrawsTheFullRingWithNoOne() {
        start(Enums.Repeat.all)
        val u = u()
        val ring = bounds("np.repeat.ring")
        val r = (ring.width - LayoutTok.npRingStroke * u) / 2
        val img = rule.onRoot().pixels().also { System.gc() }
        for (deg in 0 until 360 step 10) at(deg.toFloat(), r, ring).let { (x, y) -> assertTrue("$deg lit", lit(img, x, y)) }
        assertTrue(rule.onAllNodesWithTag("np.repeat.one", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun allToOneCutsTheGlyph() {
        start(Enums.Repeat.all)
        val glyph = bounds("np.repeat.glyph")
        fun peak(): Int {
            val img = rule.onRoot().pixels().also { System.gc() }
            var m = 0
            for (y in glyph.top.toInt() until glyph.bottom.toInt()) for (x in glyph.left.toInt() until glyph.right.toInt()) m = maxOf(m, img.getRGB(x, y) shr 16 and 0xff)
            return m
        }
        val rest = peak()
        c.transport(Enums.TransportCmd.repeat, repeat = Enums.Repeat.one)
        val frames = (1..3).map {
            rule.mainClock.advanceTimeByFrame()
            peak()
        }
        frames.forEach { assertEquals("$rest $frames", rest.toFloat(), it.toFloat(), 8f) }
        assertEquals(glyph.width, bounds("np.repeat.glyph").width, 0.5f)
        settle(1000)
        assertEquals(rest.toFloat(), peak().toFloat(), 8f)
    }

    private companion object {
        const val LIT = 80
    }
}
