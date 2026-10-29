package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
import net.seilent.funecomp.ui.contentTop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class ScrollThumbAppTest {
    @get:Rule
    val rule = createComposeRule()

    private fun start(w: Int, h: Int, pages: List<Page>, insets: WindowInsets = WindowInsets(0)): Pair<SimControl, Shell> {
        val c = SimControl("ap80")
        val shell = Shell(Nav(pages))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(w.dp, h.dp).testTag("box")) { App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), c, shell, insets = insets) }
        }
        settle(100)
        return c to shell
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun shot() = rule.onNodeWithTag("box").pixels()

    @Test
    fun aLongSheetShowsTheThumbOnItsListFlushRight() {
        val (_, shell) = start(411, 960, listOf(Page.Home))
        shell.sheet.open("long", (1..40).map { "item $it" }, 0) {}
        settle(600)
        val img = shot()
        val ui = Ui.of(img.width.toFloat(), img.height.toFloat())
        val box = bounds("box")
        val list = bounds("sheet.list")
        val top = (list.top - box.top).roundToInt()
        val bottom = (list.bottom - box.top).roundToInt()
        val r = thumbRun(img, img.width - ui.px(Tok.scrollbar_width).roundToInt() / 2, ui.px(3f).roundToInt(), top, bottom)
        assertNotNull(r)
        assertEquals(top.toFloat(), r!!.first.toFloat(), 1f)
        assertTrue(r.last < bottom)
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("sheet.list").performScrollToNode(hasTestTag("sheet.item.39"))
        rule.mainClock.autoAdvance = false
        settle(100)
        val end = thumbRun(shot(), img.width - ui.px(Tok.scrollbar_width).roundToInt() / 2, ui.px(3f).roundToInt(), top, bottom)
        assertNotNull(end)
        assertEquals((bottom - 1).toFloat(), end!!.last.toFloat(), 1f)
        assertEquals((r.last - r.first).toFloat(), (end.last - end.first).toFloat(), 1f)
    }

    @Test
    fun aSettingsEditLeavesTheThumbLengthAlone() {
        val (c, _) = start(960, 411, listOf(Page.Home, Page.Settings))
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("rows").performScrollToNode(hasTestTag("set.balance"))
        rule.mainClock.autoAdvance = false
        settle(100)
        val img = shot()
        val ui = Ui.of(img.width.toFloat(), img.height.toFloat())
        val x = img.width - ui.px(Tok.scrollbar_width).roundToInt() / 2
        val from = ui.px(ui.contentTop).roundToInt()
        val before = thumbRun(img, x, ui.px(3f).roundToInt(), from)
        assertNotNull(before)
        rule.onNodeWithTag("set.balance").performClick()
        settle(600)
        rule.onNodeWithTag("set.balance").performTouchInput { click(centerRight) }
        settle(300)
        assertEquals("1", c.device.settings.values["balance"])
        val after = thumbRun(shot(), x, ui.px(3f).roundToInt(), from)
        assertNotNull(after)
        assertEquals(before!!.last - before.first, after!!.last - after.first)
    }

    @Test
    fun homeShowsNone() {
        start(411, 960, listOf(Page.Home))
        val img = shot()
        val ui = Ui.of(img.width.toFloat(), img.height.toFloat())
        assertNull(thumbRun(img, img.width - ui.px(Tok.scrollbar_width).roundToInt() / 2, ui.px(3f).roundToInt(), ui.px(ui.contentTop).roundToInt()))
    }

    @Test
    fun aRightInsetPutsTheThumbLeftOfIt() {
        start(960, 411, listOf(Page.Home, Page.Music), WindowInsets(right = 48.dp))
        val img = shot()
        val inset = (48 * 2.625f).roundToInt()
        val ui = Ui.of((img.width - inset).toFloat(), img.height.toFloat())
        val w = ui.px(Tok.scrollbar_width).roundToInt()
        val gap = ui.px(3f).roundToInt()
        val from = ui.px(ui.contentTop).roundToInt()
        val right = img.width - inset
        assertNotNull(thumbRun(img, right - w / 2, gap, from))
        assertNull(thumbRun(img, right + w / 2, gap, from))
        assertNull(thumbRun(img, right - w - 1, gap, from))
    }
}
