package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.Band
import net.seilent.funecomp.proto.Profile
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.state.EqSlots
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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class EqSubRowsTest {
    @get:Rule
    val rule = createComposeRule()

    private val subs = listOf("type", "frequency", "gain", "q").map { "eq.band.0.$it" }

    private class Frame(val ms: Long, val x: List<Float>, val img: BufferedImage)

    private fun start() {
        val c = SimControl("v1")
        c.eqSet(EqSlots.HEADPHONES, ints = Profile(-20, listOf(Band(Enums.BandType.lowShelf, -1, 100_000, 0, 70))))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE), c, Shell(Nav(listOf(Page.Home, Page.Settings, Page.Eq(Enums.EqOutput.wired))))) }
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

    private fun ui() = rule.onNodeWithTag("hub.eq").fetchSemanticsNode().size.let { Ui.of(it.width.toFloat(), it.height.toFloat()) }

    private fun shown() = subs.all { rule.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() }

    private fun x(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.x

    private fun capture(): BufferedImage = rule.onRoot().pixels().also { System.gc() }

    private fun red(img: BufferedImage, x: Int, y: Int) = (img.getRGB(x, y) shr 16) and 0xff

    private fun light(img: BufferedImage, base: BufferedImage, row: Rect, right: Int): Int {
        var most = 0
        for (y in row.top.toInt().coerceAtLeast(0) until row.bottom.toInt().coerceAtMost(img.height)) {
            for (x in 0 until right) most = maxOf(most, red(img, x, y) - red(base, x, y))
        }
        return most
    }

    private fun shown(img: BufferedImage, base: BufferedImage, rest: BufferedImage, row: Rect, dx: Float, right: Int): Float {
        val s = dx.roundToInt()
        var now = 0L
        var full = 0L
        for (y in row.top.toInt().coerceAtLeast(0) until row.bottom.toInt().coerceAtMost(img.height)) {
            for (x in maxOf(0, s) until minOf(right, right + s)) {
                now += maxOf(0, red(img, x, y) - red(base, x, y))
                full += maxOf(0, red(rest, x - s, y) - red(base, x - s, y))
            }
        }
        return if (full == 0L) 0f else now.toFloat() / full
    }

    private fun easeOut(t: Float) = t * (2f - t)

    @Test
    fun theSubRowsEnterTogetherOnEaseOutFromTheShift() {
        start()
        val ui = ui()
        val shift = ui.px(Tok.motion_listChangeShift)
        val d = Tok.motion_listChange
        rule.onNodeWithTag("eq.band.0").performClick()
        var waited = 0
        while (!shown()) {
            check(waited++ < 5) { "no sub rows after the tap" }
            frame()
        }
        val frames = ArrayList<Frame>()
        repeat(ceil(d / 16f).toInt() + 6) {
            frames += Frame(rule.mainClock.currentTime, subs.map { x(it) }, capture())
            frame()
        }
        settle(1000)
        val rest = subs.map { x(it) }
        val rows = subs.map { rule.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        val restImg = capture()
        val progress = frames.map { f -> f.x.mapIndexed { j, v -> 1f - (v - rest[j]) / shift } }
        frames.forEachIndexed { k, f ->
            f.x.forEachIndexed { j, v -> assertEquals("frame $k row $j x", f.x[0] - rest[0], v - rest[j], 0.5f) }
        }
        assertEquals("the first frame at the shift", 0f, progress.first()[0], 0.5f / abs(shift))
        val base = frames[progress.indexOfFirst { abs(it[0]) < 0.001f }].img
        val starts = frames.indices.filter { progress[it][0] in 0.02f..0.98f }.map { k -> frames[k].ms - (1f - sqrt(1f - progress[k][0])) * d }
        assertTrue("too few frames in the move: ${starts.size}", starts.size >= 3)
        starts.forEach { assertEquals("an EaseOut over $d from one start", starts.first(), it, 2f) }
        val t0 = starts.first()
        frames.forEachIndexed { k, f ->
            val s = ((f.ms - t0) / d).coerceIn(0f, 1f)
            assertEquals("frame $k at ${f.ms - t0} ms", easeOut(s), progress[k][0], 0.01f)
        }
        val right = (restImg.width - ui.px(Tok.hubEdge) / 2).toInt()
        val full = subs.indices.map { j -> light(restImg, base, rows[j], right) }
        full.forEach { assertTrue("a sub row drew no text at rest", it > 40) }
        frames.forEachIndexed { k, f ->
            subs.indices.forEach { j ->
                assertEquals("frame $k row $j alpha", progress[k][j], shown(f.img, base, restImg, rows[j], f.x[j] - rest[j], right), 0.06f)
            }
        }
    }
}
