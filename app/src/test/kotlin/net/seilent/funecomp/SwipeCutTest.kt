package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
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
import net.seilent.funecomp.ui.LocalPressSlots
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.PressSlots
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class SwipeCutTest {
    @get:Rule
    val rule = createComposeRule()

    private val slots = PressSlots()
    private lateinit var shell: Shell

    private fun start() {
        val c = SimControl("ap80")
        shell = Shell(Nav(listOf(Page.Home, Page.Music)))
        rule.mainClock.autoAdvance = false
        rule.setUptimeContent {
            CompositionLocalProvider(LocalPressSlots provides slots) {
                Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), c, shell) }
            }
        }
        settle(1000)
        rule.pivotTo("music", "artists") { settle(it) }
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

    private fun slop(): Float {
        val size = rule.onRoot().fetchSemanticsNode().size
        return Ui.of(size.width.toFloat(), size.height.toFloat()).px(Tok.touchSlop)
    }

    private fun look() = slots.look(rule.mainClock.currentTime)

    private fun hold(): Offset {
        val row = rule.onNodeWithTag("row.1").fetchSemanticsNode().boundsInRoot
        val at = Offset(row.center.x - slop() * 2, row.center.y)
        rule.onRoot().performTouchInput { down(at) }
        settle(Tok.motion_pressGlow.toLong() + FRAME)
        assertEquals("held", 1f, look(), 0f)
        return at
    }

    @Test
    fun aDiagonalDragReturnsLinearAndTheLockCutsIt() {
        start()
        val s = slop()
        hold()
        rule.onRoot().performTouchInput { moveBy(Offset(s * 0.75f, s * 0.75f)) }
        frame()
        val first = look()
        assertTrue("past the slop with no lock, on the Linear return: $first", first > 0f && first < 1f)
        frame()
        val second = look()
        assertTrue("still returning: $second", second > 0f && second < first)
        rule.onRoot().performTouchInput { moveBy(Offset(s * 0.5f, 0f)) }
        assertEquals("the lock cuts", 0f, look(), 0f)
        frame()
        assertEquals("the lock frame", 0f, look(), 0f)
        assertEquals("the lock frame drawn", 0f, slots.lit(), 0f)
        repeat(4) {
            frame()
            assertEquals("after the lock $it", 0f, look(), 0f)
        }
        rule.onRoot().performTouchInput { up() }
        settle(1000)
    }

    @Test
    fun aFastSwipeLockingSoonAfterTheSlopDrawsNoLookAtTheLock() {
        start()
        val s = slop()
        hold()
        rule.onRoot().performTouchInput { moveBy(Offset(s * 0.8f, s * 0.8f)) }
        settle(LOCK_AFTER_SLOP)
        assertTrue("returning before the lock", look() > 0f)
        rule.onRoot().performTouchInput { moveBy(Offset(s * 0.5f, 0f)) }
        frame()
        assertEquals("the lock frame", 0f, look(), 0f)
        assertEquals("the lock frame drawn", 0f, slots.lit(), 0f)
        rule.onRoot().performTouchInput { up() }
        settle(1000)
    }

    private companion object {
        const val FRAME = 16L
        const val LOCK_AFTER_SLOP = 30L
    }
}
