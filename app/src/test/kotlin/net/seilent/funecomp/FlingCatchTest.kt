package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.sim.Library
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
class FlingCatchTest {
    @get:Rule
    val rule = createComposeRule()

    private val slots = PressSlots()
    private lateinit var shell: Shell

    private fun start() {
        val c = SimControl("ap80", Library((1..60).map { Library.track("Artist %02d".format(it), "Album", "Song", 1, 200_000) }))
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

    private fun ui(): Ui {
        val size = rule.onRoot().fetchSemanticsNode().size
        return Ui.of(size.width.toFloat(), size.height.toFloat())
    }

    private fun look() = slots.look(rule.mainClock.currentTime)

    private fun list() = rule.onNodeWithTag("rows").fetchSemanticsNode().boundsInRoot

    private fun scrolled() = rule.onNodeWithTag("rows").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    @Test
    fun aDownOnAFlingingListArmsNothingAndDoesNotAct() {
        start()
        val ui = ui()
        val box = list()
        val x = box.center.x
        val step = ui.px(FLING) * MOVE_MS / 1000f
        rule.onRoot().performTouchInput {
            down(Offset(x, box.bottom - ui.px(Tok.rowHeight)))
            repeat(((ui.px(Tok.touchSlop) + ui.px(40f)) / step).toInt() + 1) { moveBy(Offset(0f, -step), MOVE_MS) }
            up()
        }
        frame()
        frame()
        val before = scrolled()
        frame()
        assertTrue("the list flings", scrolled() > before)
        settle(Tok.motion_press.toLong())
        assertEquals("no look left from the fling's own down", 0f, look(), 0f)
        rule.onRoot().performTouchInput { down(Offset(x, box.center.y)) }
        repeat(6) {
            frame()
            assertEquals("a fling catch frame $it", 0f, look(), 0f)
            assertEquals("a fling catch drawn $it", 0f, slots.lit(), 0f)
        }
        rule.onRoot().performTouchInput { up() }
        settle(1000)
        assertEquals("a fling catch does not act", Page.Music, shell.nav.top)
    }

    @Test
    fun aDownAtRestThatBecomesAScrollReturnsByTheSlopPlus100() {
        start()
        val ui = ui()
        val row = rule.onNodeWithTag("row.2").fetchSemanticsNode().boundsInRoot
        rule.onRoot().performTouchInput { down(row.center) }
        settle(Tok.motion_pressGlow.toLong() + FRAME)
        assertEquals("risen", 1f, look(), 0f)
        rule.onRoot().performTouchInput { moveBy(Offset(0f, -(ui.px(Tok.touchSlop) + 10f))) }
        val slop = rule.mainClock.currentTime
        frame()
        val first = look()
        assertTrue("on the Linear return: $first", first > 0f && first < 1f)
        frame()
        assertTrue("still returning", look() < first)
        settle(slop + Tok.motion_press.toLong() + 2 * FRAME - rule.mainClock.currentTime)
        assertEquals("back by the slop plus 100", 0f, look(), 0f)
        frame()
        assertEquals("drawn back", 0f, slots.lit(), 0f)
        rule.onRoot().performTouchInput { up() }
        settle(1000)
        assertEquals("a scroll does not act", Page.Music, shell.nav.top)
    }

    private companion object {
        const val FRAME = 16L
        const val MOVE_MS = 4L
        const val FLING = 2000f
    }
}
