package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class RingSwapTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var c: SimControl

    private fun start() {
        c = SimControl("ap80")
        val shell = Shell(Nav(listOf(Page.Home, Page.NowPlaying)))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), c, shell) }
        }
        settle(1000)
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

    private fun peak(box: Rect): Int {
        val img = rule.onRoot().pixels().also { System.gc() }
        var m = 0
        for (y in box.top.toInt().coerceAtLeast(0) until box.bottom.toInt().coerceAtMost(img.height)) {
            for (x in box.left.toInt().coerceAtLeast(0) until box.right.toInt().coerceAtMost(img.width)) m = maxOf(m, img.getRGB(x, y) shr 16 and 0xff)
        }
        return m
    }

    private fun cuts(ring: String, hit: String, state: () -> Any?) {
        start()
        val rest = bounds("$ring.glyph")
        val before = peak(rest)
        val from = state()
        rule.onNodeWithTag(hit).performClick()
        val frames = ArrayList<Pair<Float, Int>>()
        repeat(FRAMES) {
            frame()
            val b = bounds("$ring.glyph")
            frames += b.width to peak(b)
        }
        settle(1000)
        assertNotEquals("$hit changed nothing", from, state())
        val after = peak(bounds("$ring.glyph"))
        frames.forEachIndexed { i, (w, p) ->
            assertEquals("$ring frame ${i + 1} at rest size", rest.width, w, 0.5f)
            assertTrue("$ring frame ${i + 1} peak $p, rest $before and $after", abs(p - before) <= LEVEL || abs(p - after) <= LEVEL)
        }
    }

    @Test
    fun theShuffleRingCutsToItsNewLook() = cuts("np.shuffle", "np.shuffle") { c.device.playback.state.shuffle }

    @Test
    fun theRepeatRingCutsToItsNewLook() = cuts("np.repeat", "np.repeat") { c.device.playback.state.repeat ?: Enums.Repeat.off }

    @Test
    fun thePlayRingCutsToItsNewGlyph() = cuts("np.toggle", "np.toggle") { c.device.playback.state.playing }

    @Test
    fun theHeartCutsToItsNewLook() = cuts("np.heart", "np.heart") { c.device.playback.state.favorite == true }

    private companion object {
        const val FRAMES = 15
        const val LEVEL = 6
    }
}
