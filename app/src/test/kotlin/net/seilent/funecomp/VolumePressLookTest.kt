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
import kotlin.math.floor
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

    private fun barSide(): Offset {
        val g = bounds("overlay.prev.glyph")
        return Offset(g.left + 1.7f * g.width / Tok.glyphBox - u(), g.center.y)
    }

    private fun alphaAt(m: Bitmap, x: Float, y: Float): Float {
        val fx = x - 0.5f
        val fy = y - 0.5f
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val tx = fx - x0
        val ty = fy - y0
        fun a(i: Int, j: Int) = (m.getPixel(i.coerceIn(0, m.width - 1), j.coerceIn(0, m.height - 1)) ushr 24) / 255f
        return (a(x0, y0) * (1 - tx) + a(x0 + 1, y0) * tx) * (1 - ty) + (a(x0, y0 + 1) * (1 - tx) + a(x0 + 1, y0 + 1) * tx) * ty
    }

    private fun maskAlpha(past: Offset, tag: String, moved: Offset = Offset.Zero): Float {
        val u = u()
        val g = bounds("overlay.prev.glyph")
        val m = glyphGlow(GlyphName.SKIP_PREVIOUS, (Tok.vol_skip * u).roundToInt(), Tok.vol_glowSkip * u).asAndroidBitmap()
        val touch = bounds(tag).center + moved
        val plate = bounds("overlay")
        return square(past).map { (x, y) ->
            val p = flatPoint(Offset(x + 0.5f, y + 0.5f), touch, plate)
            alphaAt(m, p.x - g.center.x + m.width / 2f, p.y - g.center.y + m.height / 2f)
        }.average().toFloat()
    }

    private fun hold(tag: String) {
        rule.onNodeWithTag(tag).performTouchInput { down(center) }
        settle(150)
    }

    private fun tilted(at: Offset, tag: String, moved: Offset = Offset.Zero) = tiltedPoint(at, bounds(tag).center + moved, bounds("overlay"))

    private fun up(tag: String) {
        rule.onNodeWithTag(tag).performTouchInput { up() }
        rule.waitForIdle()
    }

    @Test
    fun aHeldPrevGlowsPastItsInkAndCutsAtTheUp() {
        start()
        val past = tilted(barSide(), "overlay.prev")
        val a = maskAlpha(past, "overlay.prev")
        val size = bounds("overlay.prev.glyph").width
        hold("overlay.prev")
        val held = red4(past)
        assertEquals(size, bounds("overlay.prev.glyph").width, 0.5f)
        up("overlay.prev")
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        val cut = red4(past)
        val want = cut + (255 - cut) * a
        assertTrue("$a", want > cut + 30)
        assertEquals(want, held, 8f)
    }

    @Test
    fun aDragPastTheSlopReturnsThePrevGlowLinearAndDoesNotAct() {
        val c = start()
        val slop = Tok.touchSlop * u()
        val past = tilted(barSide(), "overlay.prev", Offset(0f, slop + 4f))
        val a = maskAlpha(past, "overlay.prev", Offset(0f, slop + 4f))
        val transports = c.transports.size
        hold("overlay.prev")
        rule.onNodeWithTag("overlay.prev").performTouchInput { moveBy(Offset(0f, slop + 4f)) }
        rule.mainClock.advanceTimeBy(Tok.motion_press.toLong() / 2)
        rule.waitForIdle()
        val half = red4(past)
        rule.mainClock.advanceTimeBy(Tok.motion_press.toLong() / 2 + 17)
        rule.waitForIdle()
        val gone = red4(past)
        val want = gone + (255 - gone) * a
        assertEquals(gone + (want - gone) / 2, half, 12f)
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
            val flat = Offset(g.left + g.width * BAR_LEFT / Tok.glyphBox - u, g.center.y)
            val base = red4(flat)
            val past = tilted(flat, tag)
            hold(tag)
            val held = red4(past)
            up(tag)
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            val cut = red4(past)
            assertTrue("$tag glows past its ink: $held, cut at the up: $cut", held > cut + 20)
            settle(400)
            assertEquals("$tag flat and dark again", base, red4(flat), 3f)
        }
    }

    @Test
    fun theGlowIsLitInTheFirstFrameOfAPress() {
        start()
        val past = tilted(barSide(), "overlay.prev")
        val a = maskAlpha(past, "overlay.prev")
        rule.onNodeWithTag("overlay.prev").performTouchInput { down(center) }
        rule.mainClock.advanceTimeByFrame()
        val first = red4(past)
        rule.onNodeWithTag("overlay.prev").performTouchInput { up() }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        val cut = red4(past)
        assertEquals(cut + (255 - cut) * a, first, 8f)
    }

    @Test
    fun aHeldRingDrawsTheDiscInsideAndTheGlowOutsideOnly() {
        start()
        val ring = bounds("overlay.ring.ring")
        val glyph = bounds("overlay.ring.glyph")
        val flatIn = Offset(ring.center.x, (glyph.top + ring.top + Tok.vol_ringStroke * u()) / 2)
        val flatOut = Offset(ring.center.x, ring.top - 1.5f * u())
        val inBase = red(flatIn)
        val outBase = red(flatOut)
        val inside = tilted(flatIn, "overlay.ring")
        val outside = tilted(flatOut, "overlay.ring")
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
