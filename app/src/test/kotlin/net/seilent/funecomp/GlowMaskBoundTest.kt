package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.Ui
import net.seilent.funecomp.ui.glowMasksBuilt
import net.seilent.funecomp.ui.glowMasksLive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class GlowMaskBoundTest {
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
        rule.pivotTo("music", "artists") { settle(it) }
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun drawn() {
        rule.onRoot().pixels()
        System.gc()
    }

    private fun slop(): Float {
        val size = rule.onRoot().fetchSemanticsNode().size
        return Ui.of(size.width.toFloat(), size.height.toFloat()).px(Tok.touchSlop)
    }

    private fun lit(tag: String) {
        rule.onNodeWithTag(tag).performTouchInput { down(center) }
        settle(Tok.motion_pressGlow.toLong() + FRAME)
        drawn()
    }

    private fun returning(tag: String) {
        val past = slop() + SLOP_PAST
        rule.onNodeWithTag(tag).performTouchInput { moveBy(Offset(0f, -past)) }
        settle(STILL)
        rule.onNodeWithTag(tag).performTouchInput { up() }
        settle(RETURNING)
        drawn()
    }

    @Test
    fun aSecondPressOfTheSameRowBuildsNoNewMask() {
        start()
        val built = glowMasksBuilt
        lit("row.0")
        assertEquals("the first press", 1, glowMasksBuilt - built)
        returning("row.0")
        lit("row.0")
        assertEquals("the same row again", 1, glowMasksBuilt - built)
        returning("row.0")
        settle(1000)
    }

    @Test
    fun atMostTwoMasksLiveWithThreeRowsPressedInTurn() {
        start()
        val live = glowMasksLive
        for (i in 0..2) {
            lit("row.$i")
            assertTrue("row $i: ${glowMasksLive - live} masks", glowMasksLive - live in 1..2)
            returning("row.$i")
        }
        settle(1000)
        assertEquals("all freed at rest", 0, glowMasksLive - live)
    }

    private companion object {
        const val FRAME = 16L
        const val RETURNING = 20L
        const val SLOP_PAST = 10f
        const val STILL = 50L
    }
}
