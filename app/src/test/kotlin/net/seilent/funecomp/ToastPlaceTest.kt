package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import java.awt.image.BufferedImage
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.state.CardView
import net.seilent.funecomp.state.LibKey
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.LocalUi
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.Toast
import net.seilent.funecomp.ui.ToastState
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
import kotlin.math.ceil
import kotlin.math.floor

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
    private val toast = ToastState()
    private var held by mutableStateOf<Pair<String, String>?>(null)
    private var over by mutableStateOf(false)
    private var np by mutableStateOf(false)

    private val ui get() = Ui.of(box.width, box.height)
    private val rest get() = box.bottom - ui.px(Tok.toastHeight)
    private val travel get() = ui.px(Tok.motion_toastTravel)

    private lateinit var c: SimControl
    private val down = LinkView(Phase.CONNECTING, "V1", everLive = true)

    private fun start(tag: String, vararg pages: Page, insets: WindowInsets = WindowInsets(0.dp)) {
        c = SimControl("v1")
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

    private fun alone() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            BoxWithConstraints(Modifier.requiredSize(411.dp, 960.dp).testTag("place")) {
                CompositionLocalProvider(LocalUi provides Ui.of(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())) {
                    Toast(toast, held, over = over, np = np)
                }
            }
        }
        settle(100)
        box = rule.onNodeWithTag("place").fetchSemanticsNode().boundsInRoot
    }

    private fun drop() {
        held = "V1" to "connecting"
        rise()
        assertTrue(shown())
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

    private fun words(): List<String> =
        rule.onAllNodes(hasAnyAncestor(hasTestTag("toast")) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes().flatMap { n -> n.config[SemanticsProperties.Text].map { it.text.lowercase() } }

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
    fun aHeldStripOnNowPlayingStaysAtTheTopThroughASheet() {
        alone()
        np = true
        settle(100)
        drop()
        assertEquals("held on now playing: off the top place", 0f, offTop(), 1f)
        over = true
        settle(100)
        over = false
        settle(100)
        assertEquals("held on now playing after a sheet: off the top place", 0f, offTop(), 1f)
    }

    @Test
    fun aHeldStripSitsAtTheBottomOnHome() {
        alone()
        drop()
        assertEquals("held on home: off the bottom place", 0f, offBottom(), 1f)
    }

    @Test
    fun aHeldStripGoesToTheTopWhenNowPlayingOpens() {
        alone()
        drop()
        np = true
        settle(100)
        assertTrue(shown())
        assertEquals("now playing over the held strip: off the top place", 0f, offTop(), 1f)
    }

    @Test
    fun aHeldStripGoesBackDownWhenItsSheetCloses() {
        alone()
        drop()
        over = true
        settle(100)
        assertEquals("sheet open over the held strip: off the top place", 0f, offTop(), 1f)
        over = false
        settle(100)
        assertTrue(shown())
        assertEquals("sheet closed: off the bottom place", 0f, offBottom(), 1f)
    }

    @Test
    fun aHeldStripDroppedOnNowPlayingGoesDownOnThePop() {
        alone()
        np = true
        settle(100)
        drop()
        assertEquals("held on now playing: off the top place", 0f, offTop(), 1f)
        np = false
        settle(100)
        assertTrue(shown())
        assertEquals("popped to home: off the bottom place", 0f, offBottom(), 1f)
    }

    @Test
    fun theHeldStripSitsAtTheToastPlaceOnHomeMusicAndSettings() {
        start("home", Page.Home)
        link = down
        rise()
        assertEquals("home: off the bottom place", 0f, offBottom(), 1f)
        for (p in listOf(Page.Music, Page.Settings)) {
            shell.nav.push(p)
            settle(1000)
            assertTrue(shown())
            assertEquals("$p: off the bottom place", 0f, offBottom(), 1f)
        }
    }

    @Test
    fun aDropOnHomeGoesToTheTopWhenNowPlayingOpens() {
        start("home", Page.Home)
        link = down
        rise()
        assertEquals("home: off the bottom place", 0f, offBottom(), 1f)
        shell.nav.push(Page.NowPlaying)
        settle(1000)
        assertTrue(shown())
        assertEquals("now playing: off the top place", 0f, offTop(), 1f)
    }

    @Test
    fun theHeldStripGoesUpUnderASheetAndBackDownWhenItCloses() {
        start("home", Page.Home)
        link = down
        rise()
        shell.sheet.open("sheet", listOf("a", "b")) {}
        settle(Tok.motion_sheet.toLong() + 100)
        assertEquals("sheet open: off the top place", 0f, offTop(), 1f)
        shell.sheet.close()
        settle(Tok.motion_sheet.toLong() + 100)
        assertTrue(shown())
        assertEquals("sheet closed: off the bottom place", 0f, offBottom(), 1f)
    }

    @Test
    fun aDropOnNowPlayingGoesDownWhenNowPlayingIsLeft() {
        start("home", Page.Home)
        shell.nav.push(Page.NowPlaying)
        settle(1000)
        link = down
        rise()
        assertEquals("now playing: off the top place", 0f, offTop(), 1f)
        shell.nav.pop()
        settle(1000)
        assertTrue(shown())
        assertEquals("home: off the bottom place", 0f, offBottom(), 1f)
    }

    @Test
    fun theHeldStripLeavesAtLive() {
        start("home", Page.Home)
        link = down
        rise()
        assertTrue(shown())
        link = LinkView(Phase.LIVE, "V1", everLive = true)
        settle(1000)
        assertFalse(shown())
    }

    @Test
    fun aPairFailedToastDuringADropTakesTheStripForItsHoldThenTheHeldWordsComeBack() {
        start("home", Page.Home)
        link = down
        rise()
        assertTrue(words().toString(), "connecting" in words())
        shell.toast.show("fune r1", BoardScan.PAIR_FAILED)
        settle(300)
        assertTrue(words().toString(), "pair failed" in words())
        assertEquals("the swap: off the bottom place", 0f, offBottom(), 1f)
        settle(Tok.motion_toastHold.toLong() - 600)
        assertTrue(words().toString(), "pair failed" in words())
        assertEquals("the hold: off the bottom place", 0f, offBottom(), 1f)
        settle(600)
        assertTrue(words().toString(), "connecting" in words())
        assertTrue(shown())
        assertEquals("the held words back: off the bottom place", 0f, offBottom(), 1f)
    }

    @Test
    fun aCardToastDuringADropTakesTheStrip() {
        start("hub.music", Page.Home, Page.Music)
        rule.pivotTo("music", "songs") { settle(it) }
        c.push(Msg.STATE, c.device.playback.state.copy(card = Enums.Card.busy).encode())
        settle(300)
        link = down
        rise()
        rule.onNodeWithTag("row.0").performClick()
        settle(300)
        assertTrue(words().toString(), CardView.REFUSED in words())
        assertEquals("the swap: off the bottom place", 0f, offBottom(), 1f)
    }

    @Test
    fun theLastRowOfALongListEndsAboveTheHeldStrip() {
        start("hub.music", Page.Home, Page.Music)
        rule.pivotTo("music", "songs") { settle(it) }
        link = down
        rise()
        val last = c.device.lib.pages.getValue(LibKey(Enums.View.songs, "")).total.toInt() - 1
        rule.onNodeWithTag("rows").performScrollToIndex(last)
        settled("row.$last")
        val row = rule.onNodeWithTag("row.$last").fetchSemanticsNode().boundsInRoot
        assertTrue("row.$last ends at ${row.bottom}, the strip starts at ${strip().top}", row.bottom <= strip().top + 0.5f)
        assertEquals("the strip: off the bottom place", 0f, offBottom(), 1f)
    }

    @Test
    fun theHeldStripHidesTheRowsUnderIt() {
        start("hub.music", Page.Home, Page.Music)
        rule.pivotTo("music", "songs") { settle(it) }
        val rows = capture()
        link = down
        rise()
        settled("toast")
        val s = strip()
        val over = capture()
        val last = c.device.lib.pages.getValue(LibKey(Enums.View.songs, "")).total.toInt() - 1
        rule.onNodeWithTag("rows").performScrollToIndex(last)
        settled("row.$last")
        assertEquals(s, strip())
        val clear = capture()
        var ink = 0
        var off = 0
        for (y in ceil(s.top).toInt() until floor(s.bottom).toInt().coerceAtMost(over.height)) {
            for (x in 0 until over.width) {
                if (x < over.width / 2) ink = maxOf(ink, red(rows, x, y))
                if (over.getRGB(x, y) != clear.getRGB(x, y)) off++
            }
        }
        assertTrue("no row ink under the strip's place: $ink", ink > 40)
        assertEquals("strip pixels that differ over the rows and over the list end", 0, off)
    }

    private fun settled(tag: String) {
        var at: Rect? = null
        var n = 0
        while (true) {
            settle(100)
            val now = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()?.boundsInRoot
            if (now != null && now == at) return
            at = now
            check(n++ < 30) { "$tag does not settle" }
        }
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
