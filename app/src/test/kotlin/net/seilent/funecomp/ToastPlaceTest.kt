package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import java.awt.image.BufferedImage
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.Ui
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class ToastPlaceTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var shell: Shell
    private lateinit var box: Rect
    private var link by mutableStateOf(LinkView(Phase.LIVE, "V1", everLive = true))
    private var ime by mutableStateOf(WindowInsets(0.dp))
    private var frameMs = 0L

    private val ui get() = Ui.of(box.width, box.height)
    private val rest get() = box.bottom - ui.px(Tok.toastHeight)
    private val travel get() = ui.px(Tok.motion_toastTravel)

    private fun start(tag: String, vararg pages: Page, insets: WindowInsets = WindowInsets(0.dp)) {
        val c = SimControl("v1")
        c.set("accentOff", "on")
        shell = Shell(Nav(pages.toList()))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LaunchedEffect(Unit) { while (true) withFrameMillis { frameMs = it } }
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, link, c, shell, insets = insets, ime = ime) }
        }
        settle(1000)
        box = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        assertFalse(shell.sheet.shown)
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

    private fun show() = shell.toast.show("ARTIST", "Title")

    private fun rise() = settle(Tok.motion_toastRise.toLong() + 200)

    private fun shown() = rule.onAllNodesWithTag("toast").fetchSemanticsNodes().isNotEmpty()

    private fun strip() = rule.onNodeWithTag("toast").fetchSemanticsNode().let { Rect(it.positionInRoot, it.size.toSize()) }

    private fun atBottom() = strip().top > box.center.y

    private fun offBottom() = strip().let { maxOf(abs(it.top - rest), abs(it.bottom - box.bottom)) }

    private fun offTop() = strip().let { maxOf(abs(it.top - box.top), abs(it.bottom - box.top - ui.px(Tok.toastHeight))) }

    private fun capture(): BufferedImage = rule.onRoot().pixels().also { System.gc() }

    private fun red(img: BufferedImage, x: Int, y: Int) = (img.getRGB(x, y) shr 16) and 0xff

    private fun light(img: BufferedImage, base: BufferedImage, top: Int, bottom: Int): Int {
        var most = 0
        for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(img.height)) {
            for (x in 0 until img.width) most = maxOf(most, red(img, x, y) - red(base, x, y))
        }
        return most
    }

    @Test
    fun aToastOnHomeSitsAtTheBottomOfTheSafeArea() {
        start("home", Page.Home, insets = WindowInsets(top = 24.dp, bottom = 48.dp))
        show()
        rise()
        assertEquals("home: off the bottom place", 0f, offBottom(), 1f)
    }

    @Test
    fun aToastOnAListPageSitsAtTheBottom() {
        start("hub.music", Page.Home, Page.Music)
        show()
        rise()
        assertEquals("music: off the bottom place", 0f, offBottom(), 1f)
    }

    @Test
    fun aToastWithASheetOpenSitsAtTheTop() {
        start("home", Page.Home)
        shell.sheet.open("sheet", listOf("a", "b")) {}
        settle(Tok.motion_sheet.toLong() + 100)
        show()
        rise()
        assertEquals("sheet open: off the top place", 0f, offTop(), 1f)
    }

    @Test
    fun aToastWithTheKeyboardUpSitsAtTheTop() {
        start("home", Page.Home)
        ime = WindowInsets(bottom = 300.dp)
        settle(100)
        show()
        rise()
        assertEquals("keyboard up: off the top place", 0f, offTop(), 1f)
    }

    @Test
    fun aToastOnNowPlayingSitsAtTheTop() {
        start("home", Page.Home)
        shell.nav.push(Page.NowPlaying)
        settle(1000)
        show()
        rise()
        assertEquals("now playing: off the top place", 0f, offTop(), 1f)
    }

    @Test
    fun aStackChangeKeepsTheLatchedPlace() {
        start("home", Page.Home)
        show()
        rise()
        shell.nav.push(Page.NowPlaying)
        settle(1000)
        assertTrue(shown())
        assertEquals("after the push: off the bottom place", 0f, offBottom(), 1f)
    }

    @Test
    fun aToastShownAsItsSheetClosesLandsAtTheBottom() {
        start("home", Page.Home)
        shell.sheet.open("sheet", listOf("a", "b")) {}
        settle(Tok.motion_sheet.toLong() + 100)
        show()
        shell.sheet.close()
        rise()
        assertEquals("shown as the sheet closes: off the bottom place", 0f, offBottom(), 1f)
    }

    @Test
    fun aSheetOpeningOverABottomToastMovesItToTheTopAtItsPhase() {
        start("home", Page.Home)
        show()
        var n = 0
        while (!shown()) {
            check(n++ < 5) { "no toast after the show" }
            frame()
        }
        assertTrue("the rise starts at the bottom", atBottom())
        val e1 = 1f - (strip().top - rest) / travel
        val t1 = frameMs
        assertTrue("caught in the rise: $e1", e1 < 0.7f)
        shell.sheet.open("sheet", listOf("a", "b")) {}
        n = 0
        do {
            check(n++ < 4) { "the toast stays at the bottom over the sheet" }
            frame()
        } while (atBottom())
        val e2 = 1f + (strip().top - box.top) / travel
        assertEquals("the phase across the move", minOf(1f, e1 + (frameMs - t1) / Tok.motion_toastRise), e2, 0.02f)
        rise()
        assertEquals("after the move: off the top place", 0f, offTop(), 1f)
    }

    @Test
    fun aHeldToastOnNowPlayingGoesToTheTop() {
        start("home", Page.Home)
        show()
        rise()
        link = LinkView(Phase.CONNECTING, "V1", everLive = true)
        shell.nav.push(Page.NowPlaying)
        settle(500)
        assertTrue(shown())
        assertEquals("held: off the top place", 0f, offTop(), 1f)
    }

    @Test
    fun aShowDuringTheOutKeepsThePlace() {
        start("home", Page.Home)
        show()
        rise()
        val at = strip().top
        shell.nav.push(Page.NowPlaying)
        settle(Tok.motion_toastHold.toLong() - 500)
        var n = 0
        while (abs(strip().top - at) < 2f) {
            check(n++ < 60) { "no out" }
            frame()
        }
        show()
        rise()
        assertEquals("a show during the out: off the bottom place", 0f, offBottom(), 1f)
    }

    @Test
    fun theStripLeavesOnTheDeviceOutLegs() {
        start("home", Page.Home)
        val rise = Tok.motion_toastRise
        val base = capture()
        show()
        var n = 0
        while (!shown()) {
            check(n++ < 5) { "no toast after the show" }
            frame()
        }
        val starts = ArrayList<Float>()
        while (true) {
            val e = 1f - (strip().top - rest) / travel
            if (e >= 0.98f) break
            if (e > 0.02f) starts += frameMs - e * rise
            check(n++ < 20) { "the rise does not end" }
            frame()
        }
        assertTrue("too few frames in the rise: ${starts.size}", starts.size >= 3)
        val inAt = starts.sorted()[starts.size / 2]
        settle(200)
        val full = light(capture(), base, rest.toInt(), box.bottom.toInt())
        assertTrue("the toast drew no text at rest", full > 40)
        val out = inAt + rise + Tok.motion_toastHold
        rule.mainClock.advanceTimeBy((out - 40f - rule.mainClock.currentTime).toLong())
        rule.waitForIdle()
        var moving = 0
        var faded = 0
        n = 0
        while (shown()) {
            val t = frameMs - out
            val p = ((t - Tok.motion_toastOutMoveAt) / Tok.motion_toastOutMove).coerceIn(0f, 1f)
            assertEquals("+$t ms y", rest + travel * p, strip().top, travel / Tok.motion_toastOutMove)
            if (p > 0f && p < 1f) moving++
            val u = ((t - Tok.motion_toastOutAt) / Tok.motion_toastOut).coerceIn(0f, 1f)
            val fade = 1f - u * (2f - u)
            if (fade >= 0.2f && travel * p <= ui.px(Tok.toastHeight - Tok.toastArtistBaseline)) {
                assertEquals("+$t ms alpha", fade, light(capture(), base, rest.toInt(), box.bottom.toInt()).toFloat() / full, 0.06f)
                if (fade < 1f) faded++
            }
            check(n++ < 30) { "the toast does not leave" }
            frame()
        }
        assertTrue("too few frames in the move: $moving", moving >= 3)
        assertTrue("too few frames in the fade: $faded", faded >= 2)
    }
}
