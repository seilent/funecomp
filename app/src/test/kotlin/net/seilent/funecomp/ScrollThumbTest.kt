package net.seilent.funecomp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.Black
import net.seilent.funecomp.ui.ListRow
import net.seilent.funecomp.ui.LocalSheet
import net.seilent.funecomp.ui.LocalUi
import net.seilent.funecomp.ui.Rows
import net.seilent.funecomp.ui.SheetState
import net.seilent.funecomp.ui.ThumbMath
import net.seilent.funecomp.ui.ThumbSpan
import net.seilent.funecomp.ui.Ui
import net.seilent.funecomp.ui.dp
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
import java.awt.image.BufferedImage
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class ScrollThumbTest {
    private class Scale(var factor: Float) : MotionDurationScale {
        override val scaleFactor: Float get() = factor
    }

    private val scale = Scale(1f)

    @get:Rule
    val rule = createComposeRule(scale)

    private lateinit var ui: Ui
    private val state = LazyListState()
    private var tapped = -1
    private var composed = 0
    private var spacer by mutableStateOf(0)
    private var show by mutableStateOf("a")
    private var tall by mutableStateOf(false)

    private fun rows(n: Int, tail: Boolean = false, past: Float = 0f) {
        val sheet = SheetState().also {
            it.pastList = state
            it.past = past
        }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            BoxWithConstraints(Modifier.requiredSize(411.dp, 960.dp).background(Black).testTag("box")) {
                val u = Ui.of(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
                ui = u
                CompositionLocalProvider(LocalUi provides u, LocalSheet provides sheet) {
                    Rows(0f, state, show = show, tail = if (tail) 1 else 0, under = WindowInsets(bottom = spacer.dp)) {
                        items(n) { i ->
                            SideEffect { composed++ }
                            if (tall) {
                                Box(Modifier.fillMaxWidth().height((2 * u.px(Tok.rowHeight)).dp()))
                            } else {
                                ListRow("row $i", Modifier.testTag("r.$i"), onTap = { tapped = i })
                            }
                        }
                        if (tail) item { Spacer(Modifier.height(spacer.dp)) }
                    }
                }
            }
        }
        settle(100)
    }

    private fun len(view: Int, content: Long): Int = ThumbMath.of(ThumbSpan(0, view, view.toLong(), content, 0), px(Tok.scrollbar_min), ui.u.roundToInt())!!.len

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun px(v: Float) = ui.px(v).roundToInt()

    private fun shot(tag: String = "box") = rule.onNodeWithTag(tag).pixels()

    private fun run(img: BufferedImage = shot(), inset: Int = 0) = thumbRun(img, img.width - inset - px(Tok.scrollbar_width) / 2, px(3f))

    @Test
    fun aLongListShowsTheThumbAtOpenFlushRightThenFades() {
        rows(100)
        val img = shot()
        val r = run(img)
        assertNotNull(r)
        assertEquals(0, r!!.first)
        assertTrue("${r.last - r.first + 1}", r.last - r.first + 1 >= px(Tok.scrollbar_min))
        assertNotNull(thumbRun(img, img.width - px(Tok.scrollbar_width), px(3f)))
        assertNull(thumbRun(img, img.width - px(Tok.scrollbar_width) - 1, px(3f)))
        val lit = thumbLevel(img, img.width - 1, r.first + 1)
        assertEquals(Tok.scrollbar_alpha, lit, 0.02f)
        settle(1300)
        assertNotNull(run())
        settle(700)
        assertNull(run())
    }

    @Test
    fun aScrollShowsItAgainFurtherDown() {
        rows(100)
        settle(2000)
        assertNull(run())
        rule.onNodeWithTag("rows").performTouchInput { swipeUp() }
        settle(100)
        val r = run()
        assertNotNull(r)
        assertTrue("$r", r!!.first > 0)
    }

    @Test
    fun aShortListShowsNone() {
        rows(3)
        assertNull(run())
    }

    @Test
    fun aTapOnTheThumbPressesTheRowUnderIt() {
        rows(100)
        assertNotNull(run())
        val w = shot().width
        rule.onNodeWithTag("rows").performTouchInput { click(Offset(w - px(Tok.scrollbar_width) / 2f, ui.px(Tok.rowHeight) / 2)) }
        settle(600)
        assertEquals(0, tapped)
    }

    @Test
    fun theFadeDoesNotRecomposeTheRows() {
        rows(100)
        fun changes() = Recomposer.runningRecomposers.value.sumOf { it.changeCount }
        val before = changes()
        val items = composed
        repeat(25) { settle(100) }
        assertNull(run())
        assertEquals(before, changes())
        assertEquals(items, composed)
    }

    @Test
    fun theKeyboardShortensTheTrackAndTheSpacerStaysOutOfTheContent() {
        rows(100, tail = true)
        val h = shot().height
        val row = px(Tok.rowHeight).toLong()
        assertEquals(len(h, 100 * row), run()!!.let { it.last - it.first + 1 })
        spacer = 300
        rule.runOnIdle { runBlocking { state.scrollToItem(100) } }
        settle(100)
        val ime = (300 * 2.625f).roundToInt()
        val end = run()!!
        assertEquals(h - ime - 1, end.last)
        assertEquals(len(h - ime, 100 * row), end.last - end.first + 1)
    }

    @Test
    fun aPastPaddingIsOutOfTheView() {
        val past = 600f
        rows(100, past = past)
        val h = shot().height
        val content = 100 * px(Tok.rowHeight)
        rule.runOnIdle { runBlocking { state.scrollBy((content - h).toFloat()) } }
        settle(100)
        val r = run()!!
        val gap = past.roundToInt()
        assertTrue("$r $h", r.last < h - gap - 2)
        rule.runOnIdle { runBlocking { state.scrollBy(past) } }
        settle(100)
        val end = run()!!
        assertEquals(h - gap - 1, end.last)
        assertEquals(len(h - gap, content.toLong()), end.last - end.first + 1)
    }

    @Test
    fun aPivotSwitchShowsItAgainWithTheNewRowHeights() {
        rows(100)
        settle(2000)
        assertNull(run())
        show = "b"
        tall = true
        settle(100)
        val r = run()
        assertNotNull(r)
        assertEquals(len(shot().height, 100L * px(2 * Tok.rowHeight)), r!!.last - r.first + 1)
    }

    @Test
    fun aRowHeightChangeWithNoScrollKeepsItHidden() {
        rows(100)
        rule.runOnIdle { runBlocking { state.scrollToItem(50) } }
        settle(2100)
        assertNull(run())
        tall = true
        settle(100)
        assertNull(run())
    }

    @Test
    fun animationsOffEndTheHoldWithNoFade() {
        scale.factor = 0f
        rows(100)
        settle(1300)
        assertNotNull(run())
        settle(130)
        assertNull(run())
    }
}
