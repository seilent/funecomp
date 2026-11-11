package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Roles
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
import kotlin.math.abs
import kotlin.math.exp

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class PivotStripTest {
    @get:Rule
    val rule = createComposeRule()

    private fun start() {
        val c = SimControl("ap80")
        val shell = Shell(Nav(listOf(Page.Home, Page.Music)))
        rule.mainClock.autoAdvance = false
        rule.setUptimeContent {
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

    private fun u(): Float {
        val size = rule.onRoot().fetchSemanticsNode().size
        return Ui.of(size.width.toFloat(), size.height.toFloat()).u
    }

    private fun expOut6(t: Float) = 1f - (exp(EXP * (1f - t)) - 1f) / (exp(EXP) - 1f)

    private fun x(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.x

    private fun alpha(tag: String): Float {
        val n = rule.onAllNodes(hasTestTag(tag) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes().single()
        val out = ArrayList<TextLayoutResult>()
        n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(out)
        return out.single().layoutInput.style.color.alpha
    }

    private fun tags() = rule.pivotNodes().map { it.config[SemanticsProperties.TestTag] }

    private fun active() = "pivot." + rule.activePivot("music")

    private fun rowLeft() = rule.onNodeWithTag("row.0").fetchSemanticsNode().boundsInRoot.left

    private fun onCurve(name: String, offsets: List<Float>, from: Float) {
        val start = offsets.indexOfLast { abs(it - from) <= 1f }
        assertTrue("$name never starts at $from: $offsets", start >= 0)
        offsets.drop(start + 1).forEachIndexed { k, s ->
            val t = ((k + 1) * FRAME).toFloat() / Tok.motion_pivotStrip
            val want = if (t >= 1f) 0f else from * (1f - expOut6(t))
            assertEquals("$name +${(k + 1) * FRAME}: $offsets", want, s, 1.5f)
        }
    }

    @Test
    fun aTapSwitchMovesTheStripRigidlyOnePitchOnExpOut6Over236() {
        start()
        val tags = tags()
        val old = active()
        val new = tags[tags.indexOf(old) + 1]
        rule.onNodeWithTag(new, useUnmergedTree = true).performTouchInput { down(center) }
        frame()
        rule.onNodeWithTag(new, useUnmergedTree = true).performTouchInput { up() }
        val frames = (0 until FRAMES).map {
            frame()
            assertEquals("the old label cut to its inactive look +$it", Roles.pivotInactive.alpha, alpha(old), 0.005f)
            assertEquals("the new label lit +$it", Tok.alpha_full, alpha(new), 0.005f)
            tags.associateWith { t -> x(t) }
        }
        settle(500)
        val rest = tags.associateWith { t -> x(t) }
        frames.forEachIndexed { k, f ->
            val s = f.getValue(new) - rest.getValue(new)
            for (t in tags) assertEquals("rigid $t +$k", s, f.getValue(t) - rest.getValue(t), 0.5f)
            for (t in tags) assertEquals("whole px $t +$k", Math.round(f.getValue(t)).toFloat(), f.getValue(t), 0.01f)
        }
        onCurve("strip", frames.map { it.getValue(new) - rest.getValue(new) }, rest.getValue(new) - rest.getValue(old))
    }

    @Test
    fun aSwipeBackInsideTheStripMoveDropsTheTappedLabelToItsInactiveLook() {
        start()
        val u = u()
        val tags = tags()
        val old = active()
        val new = tags[tags.indexOf(old) + 1]
        val row = rule.onNodeWithTag("row.0").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag(new, useUnmergedTree = true).performTouchInput { down(center) }
        frame()
        rule.onNodeWithTag(new, useUnmergedTree = true).performTouchInput { up() }
        val tap = rule.mainClock.currentTime
        frame()
        rule.onRoot().performTouchInput { down(row.center) }
        frame()
        rule.onRoot().performTouchInput { moveBy(Offset(LOCK * u, 0f)) }
        frame()
        rule.onRoot().performTouchInput { moveBy(Offset((FOLLOW - LOCK) * u, 0f)) }
        frame()
        rule.onRoot().performTouchInput { up() }
        frame()
        frame()
        assertTrue("inside the strip move", rule.mainClock.currentTime - tap < Tok.motion_pivotStrip)
        assertEquals("the old label lit", Tok.alpha_full, alpha(old), 0.005f)
        assertEquals("the tapped label at its inactive look", Roles.pivotInactive.alpha, alpha(new), 0.005f)
        settle(1000)
        assertEquals("back on the old label", old, active())
    }

    @Test
    fun aDragFollowsTheFingerAndALiftPast45uCommitsAtTheUp() {
        start()
        val u = u()
        val first = active()
        val left = rowLeft()
        val row = rule.onNodeWithTag("row.0").fetchSemanticsNode().boundsInRoot
        rule.onRoot().performTouchInput { down(row.center) }
        settle(FRAME)
        rule.onRoot().performTouchInput { moveBy(Offset(-LOCK * u, 0f)) }
        frame()
        assertEquals("the lock frame catches up", left - LOCK * u, rowLeft(), 1f)
        rule.onRoot().performTouchInput { moveBy(Offset(-(FOLLOW - LOCK) * u, 0f)) }
        frame()
        assertEquals("a drag of 60 u moves the pane 60 u", left - FOLLOW * u, rowLeft(), 1f)
        assertEquals("no switch before the up", first, active())
        rule.onRoot().performTouchInput { moveBy(Offset((FOLLOW - COMMIT) * u, 0f)) }
        frame()
        rule.onRoot().performTouchInput { up() }
        settle(1000)
        assertTrue("a lift at 50 u commits", active() != first)
    }

    @Test
    fun aLiftAt40uReturnsThePaneOnExpOut6Over236() {
        start()
        val u = u()
        val first = active()
        val left = rowLeft()
        val row = rule.onNodeWithTag("row.0").fetchSemanticsNode().boundsInRoot
        rule.onRoot().performTouchInput { down(row.center) }
        settle(FRAME)
        rule.onRoot().performTouchInput { moveBy(Offset(-LOCK * u, 0f)) }
        frame()
        rule.onRoot().performTouchInput { moveBy(Offset(-(SHORT - LOCK) * u, 0f)) }
        frame()
        assertEquals("following", left - SHORT * u, rowLeft(), 1f)
        rule.onRoot().performTouchInput { up() }
        val offsets = (0 until FRAMES).map {
            frame()
            rowLeft() - left
        }
        onCurve("return", offsets, -SHORT * u)
        settle(500)
        assertEquals("no switch", first, active())
    }

    @Test
    fun aLiftAt60uWith50uDownReturns() {
        start()
        val u = u()
        val first = active()
        val left = rowLeft()
        val row = rule.onNodeWithTag("row.0").fetchSemanticsNode().boundsInRoot
        rule.onRoot().performTouchInput { down(row.center) }
        settle(FRAME)
        rule.onRoot().performTouchInput { moveBy(Offset(-FOLLOW * u, STEEP * u)) }
        frame()
        rule.onRoot().performTouchInput { up() }
        settle(1000)
        assertEquals("no switch", first, active())
        assertEquals("the pane back", left, rowLeft(), 1f)
    }

    private companion object {
        const val FRAME = 16L
        const val FRAMES = 20
        const val EXP = 6f
        const val LOCK = 30f
        const val FOLLOW = 60f
        const val COMMIT = 50f
        const val SHORT = 40f
        const val STEEP = 50f
    }
}
