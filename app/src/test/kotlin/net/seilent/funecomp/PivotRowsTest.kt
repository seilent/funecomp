package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
import kotlin.math.abs
import kotlin.math.sqrt

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class PivotRowsTest {
    @get:Rule
    val rule = createComposeRule()

    private val old = listOf("outputPriority", "volumeStep", "maxVolume").map { "set.$it" }
    private val new = listOf("crossfeed", "tube", "stereoWidth").map { "set.$it" }

    private class Seen(val ms: Long, val x: List<Float>, val light: Int)

    private fun start() {
        val c = SimControl("v1")
        c.set("accentOff", "on")
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, "V1", everLive = true), c, Shell(Nav(listOf(Page.Home, Page.Settings)))) }
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

    private fun shown(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun x(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.x

    private fun band(tags: List<String>): IntRange {
        val b = tags.map { rule.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        return b.minOf { it.top }.toInt() until b.maxOf { it.bottom }.toInt()
    }

    private fun light(rows: IntRange, right: Int): Int {
        val img: BufferedImage = rule.onRoot().pixels()
        System.gc()
        var most = 0
        for (y in rows.first.coerceAtLeast(0)..rows.last.coerceAtMost(img.height - 1)) {
            for (x in 0 until right) most = maxOf(most, (img.getRGB(x, y) shr 16) and 0xff)
        }
        return most
    }

    @Test
    fun aPivotSwitchSlidesTheOldPaneOnAndBringsEveryNewRowInTogether() {
        start()
        val ui = rule.onNodeWithTag("hub.settings").fetchSemanticsNode().size.let { Ui.of(it.width.toFloat(), it.height.toFloat()) }
        val right = (ui.px(ui.w) - ui.px(Tok.hubEdge) / 2).toInt()
        val speed = ui.px(Tok.motion_pivotPaneSpeed)
        val shift = ui.px(Tok.motion_pivotRowInShift)
        val out = Tok.motion_pivotOut
        val rows = Tok.motion_pivotRows
        val fade = Tok.motion_pivotRowFade
        val oldRest = old.map { x(it) }
        val region = band(old)
        val oldFull = light(region, right)
        assertTrue("the output rows drew no text", oldFull > 40)
        rule.onNodeWithTag("pivot.effects").performClick()
        val leaving = ArrayList<Seen>()
        val coming = ArrayList<Seen>()
        var frames = 0
        while (coming.isEmpty() || coming.last().ms - coming.first().ms < maxOf(rows, fade) + 48) {
            check(frames++ < 60) { "the switch never ended" }
            frame()
            val now = rule.mainClock.currentTime
            if (shown(old[0])) {
                val xs = old.mapIndexed { j, t -> x(t) - oldRest[j] }
                if (abs(xs[0] - shift) > 0.5f) leaving += Seen(now, xs, if (abs(xs[0]) <= 12f * ui.u) light(region, right) else -1)
            } else if (new.all { shown(it) }) {
                coming += Seen(now, new.map { x(it) }, light(region, right))
            }
        }
        settle(1000)
        val newRest = new.map { x(it) }
        val newFull = light(region, right)
        assertTrue("the effects rows drew no text", newFull > 40)

        leaving.forEach { s -> s.x.forEach { assertEquals("the old pane moves as one at ${s.ms}", s.x[0], it, 0.5f) } }
        val moving = leaving.filter { abs(it.x[0]) > 0.5f }
        assertTrue("too few frames of the old pane: ${moving.size}", moving.size >= 3)
        assertTrue("the old pane goes left on a switch to the right", moving.all { it.x[0] < 0f })
        val outStarts = moving.map { it.ms - abs(it.x[0]) / speed * 1000f }
        outStarts.forEach { assertEquals("the old pane at ${Tok.motion_pivotPaneSpeed} u/s Linear from one start", outStarts.first(), it, 1.5f) }
        val t0 = outStarts.first()
        leaving.forEach { assertTrue("the old pane still drawn at ${it.ms - t0} ms", it.ms - t0 <= out + 16) }
        leaving.filter { it.light >= 0 }.forEach { s ->
            assertEquals("the old pane alpha at ${s.ms - t0} ms", (1f - (s.ms - t0) / out).coerceIn(0f, 1f), s.light.toFloat() / oldFull, 0.06f)
        }

        val p = coming.map { s -> s.x.mapIndexed { j, v -> 1f - (v - newRest[j]) / shift } }
        coming.forEach { s -> s.x.forEachIndexed { j, v -> assertEquals("the new rows together at ${s.ms}", s.x[0] - newRest[0], v - newRest[j], 0.5f) } }
        val inStarts = coming.indices.filter { p[it][0] in 0.02f..0.98f }.map { k -> coming[k].ms - (1f - sqrt(1f - p[k][0])) * rows }
        assertTrue("too few frames of the new rows: ${inStarts.size}", inStarts.size >= 3)
        inStarts.forEach { assertEquals("the new rows on EaseOut over $rows from one start", inStarts.first(), it, 1.5f) }
        val t1 = inStarts.first()
        assertTrue("the new rows start ${t1 - t0} ms after the old pane, the out leg is $out ms", t1 - t0 >= out && t1 - t0 <= out + 64)
        coming.forEach { s ->
            assertEquals("the new rows alpha at ${s.ms - t1} ms", ((s.ms - t1) / fade).coerceIn(0f, 1f), s.light.toFloat() / newFull, 0.06f)
        }
    }
}
