package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.App
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class ToastRiseTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var shell: Shell

    private class Frame(val ms: Long, val y: Float, val img: BufferedImage)

    private fun start() {
        val c = SimControl("v1")
        c.set("accentOff", "on")
        shell = Shell(Nav(listOf(Page.Home)))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, "V1", everLive = true), c, shell) }
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

    private fun shown() = rule.onAllNodesWithTag("toast").fetchSemanticsNodes().isNotEmpty()

    private fun y() = rule.onNodeWithTag("toast").fetchSemanticsNode().positionInRoot.y

    private fun capture(): BufferedImage = rule.onRoot().pixels().also { System.gc() }

    private fun red(img: BufferedImage, x: Int, y: Int) = (img.getRGB(x, y) shr 16) and 0xff

    private fun light(img: BufferedImage, base: BufferedImage, top: Int, bottom: Int): Int {
        var most = 0
        for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(img.height)) {
            for (x in 0 until img.width) most = maxOf(most, red(img, x, y) - red(base, x, y))
        }
        return most
    }

    @Test
    fun theToastRisesLinearInPlaceAndAlphaOverTheRise() {
        start()
        val ui = rule.onNodeWithTag("home").fetchSemanticsNode().size.let { Ui.of(it.width.toFloat(), it.height.toFloat()) }
        val travel = ui.px(Tok.motion_toastTravel)
        val rise = Tok.motion_toastRise
        val base = capture()
        shell.toast.show("ARTIST", "Title")
        var waited = 0
        while (!shown()) {
            check(waited++ < 5) { "no toast after the show" }
            frame()
        }
        val frames = ArrayList<Frame>()
        while (frames.isEmpty() || frames.last().ms - frames.first().ms < rise + 64) {
            frames += Frame(rule.mainClock.currentTime, y(), capture())
            frame()
        }
        settle(500)
        assertTrue(shown())
        val rest = y()
        val restImg = capture()
        val e = frames.map { 1f + (it.y - rest) / travel }
        val starts = frames.indices.filter { e[it] in 0.02f..0.98f }.map { k -> frames[k].ms - e[k] * rise }
        assertTrue("too few frames in the rise: ${starts.size}", starts.size >= 3)
        starts.forEach { assertEquals("a Linear rise over $rise from one start", starts.first(), it, 1.5f) }
        val t0 = starts.first()
        frames.forEachIndexed { k, f ->
            assertEquals("frame $k at ${f.ms - t0} ms", ((f.ms - t0) / rise).coerceIn(0f, 1f), e[k], 0.01f)
        }
        val top = rest.toInt()
        val bottom = (rest + ui.px(Tok.toastHeight)).toInt()
        val full = light(restImg, base, top, bottom)
        assertTrue("the toast drew no text at rest", full > 40)
        frames.forEachIndexed { k, f ->
            if (e[k] >= 0.6f) assertEquals("frame $k alpha", e[k], light(f.img, base, top, bottom).toFloat() / full, 0.06f)
        }
    }
}
