package net.seilent.funecomp

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.Ui
import net.seilent.funecomp.ui.rememberFling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.ceil
import kotlin.math.ln

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class FlingDecayTest {
    @get:Rule
    val rule = createComposeRule()

    private class Fake(private val stopAfter: Int = Int.MAX_VALUE) : ScrollScope {
        var moved = 0f
        private var calls = 0

        override fun scrollBy(pixels: Float): Float {
            if (pixels == 0f) return 0f
            calls++
            if (calls > stopAfter) return 0f
            moved += pixels
            return pixels
        }
    }

    private val row = SemanticsMatcher("row") { it.config.getOrNull(SemanticsProperties.TestTag)?.matches(Regex("row\\.[0-9]+")) == true }

    private fun travel(v: Float, retention: Float) = (v - Tok.flingStop) / (1000f / Tok.motion_flingTick * -ln(retention))

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private lateinit var c: SimControl

    private fun app(w: Int, h: Int, pages: List<Page>): Ui {
        c = SimControl("ap80")
        val shell = Shell(Nav(pages))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(w.dp, h.dp).testTag("box")) { App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), c, shell) }
        }
        settle(1000)
        val b = rule.onNodeWithTag("box").fetchSemanticsNode().boundsInRoot
        return Ui.of(b.width, b.height)
    }

    private fun drag(tag: String, ui: Ui, speed: Float, past: Float) {
        val step = ui.px(speed) * STEP_MS / 1000f
        val n = ceil((ui.px(Tok.touchSlop) + ui.px(past)) / step).toInt()
        rule.onNodeWithTag(tag).performTouchInput {
            down(center)
            repeat(n) { moveBy(Offset(0f, -step), STEP_MS) }
        }
    }

    private fun release(tag: String) {
        rule.onNodeWithTag(tag).performTouchInput { up() }
        settle(4000)
    }

    private fun top(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top

    private fun scroll(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]

    private var retention by mutableFloatStateOf(Tok.motion_listFlingRetention)

    private lateinit var list: FlingBehavior
    private lateinit var grid: FlingBehavior
    private lateinit var scope: CoroutineScope

    private fun fake() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            list = rememberFling(retention)
            grid = rememberFling()
            scope = rememberCoroutineScope()
        }
        rule.waitForIdle()
    }

    private fun run(f: Fake, v: Float, fling: FlingBehavior = list): Float {
        var left = Float.NaN
        scope.launch { left = with(fling) { f.performFling(v) } }
        settle(10_000)
        assertTrue(!left.isNaN())
        return left
    }

    @Test
    fun aPageListFlingsAtTheListDecay() {
        val ui = app(411, 960, listOf(Page.Home, Page.Music))
        rule.onNodeWithTag("pivot.songs").performClick()
        settle(1000)
        val rows = rule.onAllNodes(row).fetchSemanticsNodes()
        val pitch = (top("row.1") - top("row.0")) / ui.u
        val n = c.sim.lib.rows(Enums.View.songs, "")!!.size
        assertTrue("$n x $pitch", n * pitch - rows.size * pitch > 400f)
        drag("rows", ui, 2000f, 20f)
        val probe = rule.onAllNodes(row).fetchSemanticsNodes().maxBy { it.boundsInRoot.top }.config[SemanticsProperties.TestTag]
        val at = top(probe)
        release("rows")
        val want = travel(2000f, Tok.motion_listFlingRetention)
        assertEquals(want, (at - top(probe)) / ui.u, want * 0.05f)
    }

    @Test
    fun theHomeMenuFlingsAtTheListDecay() {
        val ui = app(960, 600, listOf(Page.Home))
        val want = travel(1000f, Tok.motion_listFlingRetention)
        val range = scroll("home.column").maxValue() / ui.u
        assertTrue("$range", range > want + 10f + 2 * want * 0.05f)
        drag("home.column", ui, 1000f, 10f)
        val at = scroll("home.column").value()
        assertTrue("$at", at > 0f && at / ui.u + want * 1.05f < range)
        release("home.column")
        assertEquals(want, (scroll("home.column").value() - at) / ui.u, want * 0.05f)
    }

    @Test
    fun aFlingReturnsTheStartOfFrameVelocityAtAnEnd() {
        fake()
        assertEquals(2000f, run(Fake(stopAfter = 0), 2000f), 0f)
    }

    @Test
    fun theDecayTravelOnAFakeScope() {
        fake()
        val a = Fake()
        run(a, 2000f)
        assertEquals(travel(2000f, Tok.motion_listFlingRetention), a.moved, 1f)
        val b = Fake()
        run(b, 2000f, grid)
        assertEquals(travel(2000f, Tok.motion_flingRetention), b.moved, 1f)
    }

    @Test
    fun aRetentionChangeRebuildsTheFling() {
        fake()
        val first = Fake()
        run(first, 2000f)
        assertEquals(travel(2000f, Tok.motion_listFlingRetention), first.moved, 1f)
        val before = list
        retention = Tok.motion_flingRetention
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertNotSame(before, list)
        val second = Fake()
        run(second, 2000f)
        assertEquals(travel(2000f, Tok.motion_flingRetention), second.moved, 1f)
    }

    @Test
    fun aFlingStopsUnderTheStopSpeed() {
        fake()
        val f = Fake()
        assertEquals(0f, run(f, Tok.flingMin - 10f, grid), 0f)
        assertEquals(0f, f.moved, 0f)
        val slow = Fake()
        run(slow, Tok.flingMin + 10f, grid)
        assertEquals(travel(Tok.flingMin + 10f, Tok.motion_flingRetention), slow.moved, 0.5f)
    }

    private companion object {
        const val STEP_MS = 4L
    }
}
