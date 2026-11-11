package net.seilent.funecomp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.Black
import net.seilent.funecomp.ui.Glyph
import net.seilent.funecomp.ui.GlyphName
import net.seilent.funecomp.ui.LayoutTok
import net.seilent.funecomp.ui.LocalUi
import net.seilent.funecomp.ui.Ui
import net.seilent.funecomp.ui.at
import net.seilent.funecomp.ui.white
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
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class RepeatOneTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var ui: Ui

    private fun alone(name: GlyphName, size: Float) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) {
                BoxWithConstraints(Modifier.fillMaxSize().background(Black)) {
                    val u = Ui.of(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
                    ui = u
                    CompositionLocalProvider(LocalUi provides u) {
                        Glyph(name, size, white(Tok.alpha_full), Modifier.at(u.px(u.w) / 2, u.px(u.h) / 2, 0.5f, 0.5f).testTag("glyph"))
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
    }

    private fun capture(): BufferedImage = rule.onRoot().pixels().also { System.gc() }

    private fun red(img: BufferedImage, x: Float, y: Float) = img.getRGB(x.roundToInt(), y.roundToInt()) shr 16 and 0xff

    @Test
    fun theOneInkIsFivePointTwoBySevenPointEightAtTheDevicePlace() {
        alone(GlyphName.ONE, LayoutTok.npRepeatOne)
        val box = rule.onNodeWithTag("glyph", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val img = capture()
        var left = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var top = Int.MAX_VALUE
        var bottom = Int.MIN_VALUE
        for (y in box.top.toInt() until box.bottom.toInt()) for (x in box.left.toInt() until box.right.toInt()) {
            if ((img.getRGB(x, y) shr 16 and 0xff) >= HALF) {
                left = minOf(left, x)
                right = maxOf(right, x + 1)
                top = minOf(top, y)
                bottom = maxOf(bottom, y + 1)
            }
        }
        val k = box.width / Tok.glyphBox
        assertEquals("ink width", INK_W * ui.u, (right - left).toFloat(), PX)
        assertEquals("ink height", INK_H * ui.u, (bottom - top).toFloat(), PX)
        assertEquals("the flag's corner", box.left + FLAG_CORNER * k, left.toFloat(), PX)
        assertEquals("the flag's top", box.top + FLAG_TOP * k, top.toFloat(), PX)
    }

    @Test
    fun theRepeatArrowIsOpenFrom350To30AtRadius12Of32() {
        alone(GlyphName.REPEAT, LayoutTok.npRing / 2)
        val box = rule.onNodeWithTag("glyph", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val k = box.width / Tok.glyphBox
        val img = capture()
        fun at(deg: Int): Pair<Float, Float> {
            val a = Math.toRadians(deg.toDouble())
            return box.center.x + ARC_R * k * sin(a).toFloat() to box.center.y - ARC_R * k * cos(a).toFloat()
        }
        for (deg in listOf(40, 60, 90, 120, 180, 240, 300, 330)) at(deg).let { (x, y) -> assertTrue("$deg lit", red(img, x, y) >= HALF) }
        for (deg in listOf(5, 10, 15)) at(deg).let { (x, y) -> assertFalse("$deg in the gap", red(img, x, y) >= HALF) }
    }

    private companion object {
        const val HALF = 128
        const val PX = 1.5f
        const val INK_W = 5.2f
        const val INK_H = 7.8f
        const val FLAG_CORNER = 13.94f
        const val FLAG_TOP = 9.76f
        const val ARC_R = 12f
    }
}
