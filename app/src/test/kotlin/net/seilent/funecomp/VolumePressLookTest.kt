package net.seilent.funecomp

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.CoverBlur
import net.seilent.funecomp.ui.GlyphName
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.Ui
import net.seilent.funecomp.ui.glowsBuilt
import net.seilent.funecomp.ui.glyphGlow
import net.seilent.funecomp.ui.path
import net.seilent.funecomp.ui.ringGlow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class VolumePressLookTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var shell: Shell

    private fun start(): SimControl {
        val c = SimControl("v1")
        c.transport(Enums.TransportCmd.next)
        shell = Shell(Nav(listOf(Page.Home, Page.NowPlaying)))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, "V1", everLive = true), c, shell) }
        }
        settle(1000)
        open()
        return c
    }

    private fun open() {
        shell.overlay.show()
        settle(400)
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun u() = rule.onNodeWithTag("np").fetchSemanticsNode().size.let { Ui.of(it.width.toFloat(), it.height.toFloat()).u }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun red(at: Offset) = rule.onRoot().pixels().also { System.gc() }.getRGB(at.x.roundToInt(), at.y.roundToInt()) shr 16 and 0xff

    private fun square(at: Offset) = listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1).map { (dx, dy) -> at.x.toInt() + dx to at.y.toInt() + dy }

    private fun red4(at: Offset): Float {
        val img = rule.onRoot().pixels().also { System.gc() }
        return square(at).map { (x, y) -> img.getRGB(x, y) shr 16 and 0xff }.average().toFloat()
    }

    private fun barSide(): Pair<Offset, Float> {
        val u = u()
        val g = bounds("overlay.prev.glyph")
        val at = Offset(g.left + 1.7f * g.width / Tok.glyphBox - u, g.center.y)
        val m = glyphGlow(GlyphName.SKIP_PREVIOUS, (Tok.vol_skip * u).roundToInt(), Tok.vol_glowSkip * u).asAndroidBitmap()
        val a = square(at).map { (x, y) -> m.getPixel((x - g.center.x + m.width / 2f).toInt(), (y - g.center.y + m.height / 2f).toInt()) ushr 24 }.average().toFloat() / 255f
        return at to a
    }

    private fun hold(tag: String) {
        rule.onNodeWithTag(tag).performTouchInput { down(center) }
        settle(150)
    }

    private fun up(tag: String) {
        rule.onNodeWithTag(tag).performTouchInput { up() }
        rule.waitForIdle()
    }

    @Test
    fun aHeldPrevGlowsPastItsInkAndCutsAtTheUp() {
        start()
        val (past, a) = barSide()
        val base = red4(past)
        val want = base + (255 - base) * a
        assertTrue("$a", want > base + 30)
        val size = bounds("overlay.prev.glyph").width
        hold("overlay.prev")
        assertEquals(want, red4(past), 8f)
        assertEquals(size, bounds("overlay.prev.glyph").width, 0.5f)
        up("overlay.prev")
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertEquals(base, red4(past), 3f)
    }

    @Test
    fun aDragPastTheSlopReturnsThePrevGlowLinearAndDoesNotAct() {
        val c = start()
        val (past, a) = barSide()
        val base = red4(past)
        val want = base + (255 - base) * a
        val slop = Tok.touchSlop * u()
        val transports = c.transports.size
        hold("overlay.prev")
        rule.onNodeWithTag("overlay.prev").performTouchInput { moveBy(Offset(0f, slop + 4f)) }
        rule.mainClock.advanceTimeBy(Tok.motion_press.toLong() / 2)
        rule.waitForIdle()
        assertEquals(base + (want - base) / 2, red4(past), 12f)
        rule.mainClock.advanceTimeBy(Tok.motion_press.toLong() / 2 + 17)
        rule.waitForIdle()
        assertEquals(base, red4(past), 3f)
        up("overlay.prev")
        rule.waitForIdle()
        assertEquals(transports, c.transports.size)
    }

    @Test
    fun aHeldPlusOrMinusGlowsPastItsInkAndCutsAtTheUp() {
        start()
        val u = u()
        for (tag in listOf("overlay.plus", "overlay.minus")) {
            val g = bounds("$tag.glyph")
            val past = Offset(g.left + g.width * BAR_LEFT / Tok.glyphBox - u, g.center.y)
            val base = red4(past)
            hold(tag)
            assertTrue("$tag glows past its ink: $base", red4(past) > base + 20)
            up(tag)
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            assertEquals("$tag cut at the up", base, red4(past), 3f)
            settle(100)
        }
    }

    @Test
    fun theGlowIsLitInTheFirstFrameOfAPress() {
        start()
        val (past, a) = barSide()
        val base = red4(past)
        val want = base + (255 - base) * a
        rule.onNodeWithTag("overlay.prev").performTouchInput { down(center) }
        rule.mainClock.advanceTimeByFrame()
        assertEquals(want, red4(past), 8f)
    }

    @Test
    fun aHeldRingDrawsTheDiscInsideAndTheGlowOutsideOnly() {
        start()
        val ring = bounds("overlay.ring.ring")
        val glyph = bounds("overlay.ring.glyph")
        val inside = Offset(ring.center.x, (glyph.top + ring.top + Tok.vol_ringStroke * u()) / 2)
        val outside = Offset(ring.center.x, ring.top - 1.5f * u())
        val inBase = red(inside)
        val outBase = red(outside)
        val size = ring.width
        hold("overlay.ring")
        val disc = (red(inside) - inBase).toFloat() / (255 - inBase)
        assertEquals(Tok.vol_discAlpha, disc, 0.06f)
        assertTrue("$outBase", red(outside) > outBase + 10)
        assertEquals(size, bounds("overlay.ring.ring").width, 0.5f)
        up("overlay.ring")
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertEquals(inBase.toFloat(), red(inside).toFloat(), 3f)
        assertEquals(outBase.toFloat(), red(outside).toFloat(), 3f)
        val side = (Tok.vol_ring * u()).roundToInt()
        val m = ringGlow(side, Tok.vol_ringStroke * u(), Tok.vol_glowRing * u()).asAndroidBitmap()
        val c = m.width / 2
        assertEquals(0, m.getPixel(c, c - side / 2 + 2) ushr 24)
        assertTrue(m.getPixel(c, c - side / 2 - 2) ushr 24 > 0)
    }

    @Test
    fun theMaskIsTheCoverBlurOfThePaddedCoverage() {
        start()
        val u = u()
        val side = (Tok.vol_skip * u).roundToInt()
        val r = (Tok.vol_glowSkip * u / 3).roundToInt().coerceAtLeast(1)
        val got = glyphGlow(GlyphName.SKIP_PREVIOUS, side, Tok.vol_glowSkip * u).asAndroidBitmap()
        val pad = 3 * r
        val b = Bitmap.createBitmap(side + 2 * pad, side + 2 * pad, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(b)
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.translate(pad.toFloat(), pad.toFloat())
        canvas.drawPath(path(GlyphName.SKIP_PREVIOUS, side / Tok.glyphBox).asAndroidPath(), Paint().apply { color = android.graphics.Color.WHITE; isAntiAlias = true })
        val want = IntArray(b.width * b.height)
        b.getPixels(want, 0, b.width, 0, 0, b.width, b.height)
        CoverBlur.blur(want, b.width, b.height, r)
        val have = IntArray(got.width * got.height)
        got.getPixels(have, 0, got.width, 0, 0, got.width, got.height)
        assertEquals(b.width, got.width)
        assertArrayEquals(want.map { it shr 16 and 0xff }.toIntArray(), have.map { it ushr 24 }.toIntArray())
    }

    @Test
    fun theMaskIsBuiltOncePerOpen() {
        start()
        val first = glowsBuilt
        hold("overlay.prev")
        up("overlay.prev")
        settle(100)
        assertEquals(1, glowsBuilt - first)
        val second = glowsBuilt
        hold("overlay.prev")
        up("overlay.prev")
        settle(100)
        assertEquals(0, glowsBuilt - second)
        shell.overlay.hide()
        settle(1000)
        open()
        val third = glowsBuilt
        hold("overlay.prev")
        up("overlay.prev")
        settle(100)
        assertEquals(1, glowsBuilt - third)
    }

    private companion object {
        const val BAR_LEFT = 4f
    }
}
