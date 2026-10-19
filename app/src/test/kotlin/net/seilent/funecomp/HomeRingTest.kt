package net.seilent.funecomp

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.Ui
import net.seilent.funecomp.ui.homePitch
import net.seilent.funecomp.ui.homeSlotTop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class HomeRingTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var sim: SimControl

    private fun start(lib: Library = Library.synthetic(), ms: Long = 1000): Shell {
        val c = SimControl("ap80", lib).also { sim = it }
        val shell = Shell(Nav(listOf(Page.Home)))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), c, shell) }
        }
        settle(ms)
        return shell
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun ui(): Ui {
        val size = rule.onNodeWithTag("home").fetchSemanticsNode().size
        return Ui.of(size.width.toFloat(), size.height.toFloat())
    }

    private fun tapHome(x: Float, y: Float) {
        val ui = ui()
        rule.onNodeWithTag("home").performTouchInput { click(Offset(ui.px(x), ui.px(y))) }
        settle(1000)
    }

    private fun slotCenter(index: Int) = ui().let { it.homeSlotTop(index) + it.homePitch / 2 }

    private fun frame(): Bitmap {
        val view = rule.activity.window.decorView
        return Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }

    private fun ringBrightness(): Int {
        val r = rule.onNodeWithTag("home.ring").fetchSemanticsNode().boundsInWindow
        val b = frame()
        var max = 0
        for (y in r.top.toInt() until r.bottom.toInt()) for (x in r.left.toInt() until r.right.toInt()) max = maxOf(max, (b.getPixel(x, y) shr 16) and 0xff)
        return max
    }

    private fun barLit(index: Int): Boolean {
        val ui = ui()
        val origin = rule.onNodeWithTag("home").fetchSemanticsNode().boundsInWindow
        val p = frame().getPixel((origin.left + ui.px(Tok.homeBarX + Tok.focus_barWidth / 2)).toInt(), (origin.top + ui.px(slotCenter(index))).toInt())
        return (p shr 16) and 0xff > 230
    }

    @Test
    fun aRingTapShufflesTheLibraryAndStaysHome() {
        val shell = start()
        assertTrue(barLit(0))
        rule.onNodeWithTag("home.ring").performClick()
        settle(1000)
        assertEquals(listOf(Enums.ActionCmd.shuffleLibrary to null), sim.actions)
        assertEquals(listOf(Page.Home), shell.nav.stack.toList())
        assertTrue(barLit(0))
        val s = sim.device.playback.state
        assertEquals(sim.sim.lib.tracks.size.toLong(), s.slots)
        assertEquals(true, s.shuffle)
    }

    @Test
    fun aTapLeftOfNowPlayingSendsNothing() {
        val shell = start()
        tapHome(30f, 65f)
        assertTrue(sim.actions.isEmpty())
        assertEquals(listOf(Page.Home), shell.nav.stack.toList())
    }

    @Test
    fun tapsJustRightOfTheRingBoxOpenTheItems() {
        val shell = start()
        tapHome(63f, slotCenter(0))
        assertEquals(listOf(Page.Home, Page.NowPlaying), shell.nav.stack.toList())
        shell.nav.pop()
        settle(1000)
        tapHome(63f, slotCenter(1))
        assertEquals(listOf(Page.Home, Page.Music), shell.nav.stack.toList())
        assertTrue(sim.actions.isEmpty())
        shell.nav.pop()
        settle(1000)
        assertFalse(barLit(1))
        assertFalse(barLit(0))
    }

    @Test
    fun theRingFadesInOverThePeekToTheIdleAlpha() {
        start(ms = 210)
        val half = ringBrightness()
        settle(790)
        val full = ringBrightness()
        assertEquals((Tok.alpha_mid * 255).toInt().toFloat(), full.toFloat(), 3f)
        assertTrue(half < full)
    }

    @Test
    fun anEmptyLibraryAnswersNoLibraryWithNoToast() {
        val shell = start(Library(emptyList()))
        val rev = sim.device.playback.state.queueRev
        val shows = shell.toast.shows
        val failed = sim.failed.size
        rule.onNodeWithTag("home.ring").performClick()
        settle(1000)
        assertEquals(listOf(Enums.ActionCmd.shuffleLibrary to null), sim.actions)
        assertEquals(listOf(Err.noLibrary), sim.failed.drop(failed))
        assertEquals(shows, shell.toast.shows)
        assertEquals(rev, sim.device.playback.state.queueRev)
        assertEquals(listOf(Page.Home), shell.nav.stack.toList())
    }
}
