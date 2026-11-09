package net.seilent.funecomp

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.Held
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.LocalPressProbe
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
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
class PressHoldTest {
    private class Scale(var factor: Float) : MotionDurationScale {
        override val scaleFactor: Float get() = factor
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val motion = Scale(1f)

    @get:Rule
    val rule = createComposeRule(motion)

    private val owner = Owner()
    private var frames = 0
    private lateinit var c: SimControl
    private lateinit var shell: Shell

    private class Item(val name: String, val hit: String, val scaled: String)

    private fun start(pages: List<Page>) {
        owner.registry.currentState = Lifecycle.State.RESUMED
        c = SimControl("ap80")
        shell = Shell(Nav(pages))
        rule.mainClock.autoAdvance = false
        rule.setUptimeContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalPressProbe provides { frames++ }) {
                Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), c, shell) }
            }
        }
        settle(1000)
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun until(at: Long) = settle((at - rule.mainClock.currentTime).coerceAtLeast(0))

    private fun node(tag: String): SemanticsNodeInteraction = if (tag.startsWith(TEXT)) rule.onNode(hasText(tag.removePrefix(TEXT), ignoreCase = true)) else rule.onNodeWithTag(tag)

    private fun width(tag: String) = node(tag).fetchSemanticsNode().boundsInRoot.width

    private fun slop() = Tok.touchSlop * Ui.of(411f * rule.density.density, 960f * rule.density.density).u

    private fun read(name: String, item: Item, rest: Float, t0: Long, at: Long, want: Float, tol: Float = LEG) {
        until(t0 + at)
        assertEquals("$name ${item.name} +$at", want, width(item.scaled) / rest, tol)
    }

    private fun tapAndRead(item: Item, upAt: Long, reads: List<Pair<Long, Float>>) {
        val rest = width(item.scaled)
        val t0 = rule.mainClock.currentTime
        node(item.hit).performTouchInput { down(center) }
        until(t0 + upAt)
        node(item.hit).performTouchInput { up() }
        for ((at, want) in reads) read("tap up +$upAt", item, rest, t0, at, want, if (want == Tok.motion_pressScale && at > upAt + Tok.motion_press) FLAT else LEG)
        settle(600)
    }

    private fun holdReads(up: Long) = if (up == 50L) listOf(75L to 0.97f, 100L to 0.96f, 249L to 0.96f, 300L to 0.98f, 350L to 1f) else listOf(349L to 0.96f, 400L to 0.98f, 450L to 1f)

    @Test
    fun aTapOnNowPlayingHoldsThePressPastTheUpThenReturns() {
        start(NP)
        for (item in NP_ITEMS) {
            tapAndRead(item, 50, holdReads(50))
            tapAndRead(item, 150, holdReads(150))
        }
    }

    private fun leaving(item: Item, upAt: Long) {
        val rest = width(item.scaled)
        val t0 = rule.mainClock.currentTime
        node(item.hit).performTouchInput { down(center) }
        until(t0 + upAt)
        node(item.hit).performTouchInput { up() }
        rule.mainClock.advanceTimeByFrame()
        assertEquals("${item.name} pinned on the leaving page", Tok.motion_pressScale, width(item.scaled) / rest, FLAT)
        until(t0 + upAt + Tok.motion_touchReturnDelay.toLong())
        frames = 0
        until(t0 + upAt + Held.Nav.motion_turnstileOut.toLong() - FRAME)
        assertEquals("${item.name} leaving page asks for no frames", 0, frames)
        settle(1500)
    }

    @Test
    fun aTapThatPushesAPageHoldsThePressOnTheLeavingPage() {
        start(HOME)
        leaving(Item("home item", "home.music", "${TEXT}music"), 50)
    }

    @Test
    fun aTapOnTheHomeRingHoldsThePressPastTheUpThenReturns() {
        start(HOME)
        tapAndRead(Item("home ring", "home.ring", "home.ring.ring"), 50, holdReads(50))
    }

    @Test
    fun aBackDuringTheOutLegReturnsTheItemOnItsOwnClock() {
        start(HOME)
        val item = Item("home item", "home.music", "${TEXT}music")
        val rest = width(item.scaled)
        val t0 = rule.mainClock.currentTime
        node(item.hit).performTouchInput { down(center) }
        until(t0 + 50)
        node(item.hit).performTouchInput { up() }
        until(t0 + 150)
        shell.nav.pop()
        read("back in the out leg", item, rest, t0, 249, Tok.motion_pressScale, FLAT)
        read("back in the out leg", item, rest, t0, 300, 0.98f)
        read("back in the out leg", item, rest, t0, 350, 1f)
        settle(600)
        assertEquals(1f, width(item.scaled) / rest, FLAT)
    }

    @Test
    fun aReturningItemOnALeavingPageKeepsItsReturnValue() {
        start(NP)
        val shuffle = NP_ITEMS.first { it.name == "shuffle" }
        val rest = width(shuffle.scaled)
        val t0 = rule.mainClock.currentTime
        node(shuffle.hit).performTouchInput { down(center) }
        until(t0 + 50)
        node(shuffle.hit).performTouchInput { up() }
        until(t0 + 270)
        node("back").performTouchInput { down(center) }
        until(t0 + 290)
        node("back").performTouchInput { up() }
        rule.mainClock.advanceTimeByFrame()
        val e = rule.mainClock.currentTime - t0 - 50 - Tok.motion_touchReturnDelay.toLong()
        val want = Tok.motion_pressScale + (1f - Tok.motion_pressScale) * e / Tok.motion_press
        assertEquals("returning shuffle +$e past the hold", want, width(shuffle.scaled) / rest, LEG)
        settle(1500)
    }

    @Test
    fun aPageThatLeavesInTheHoldPinsThePressAndAsksForNoFrames() {
        start(NP)
        val shuffle = NP_ITEMS.first { it.name == "shuffle" }
        val rest = width(shuffle.scaled)
        val t0 = rule.mainClock.currentTime
        node(shuffle.hit).performTouchInput { down(center) }
        until(t0 + 50)
        node(shuffle.hit).performTouchInput { up() }
        until(t0 + 120)
        node("back").performTouchInput { down(center) }
        until(t0 + 150)
        node("back").performTouchInput { up() }
        rule.mainClock.advanceTimeByFrame()
        assertEquals("shuffle pinned on the leaving page", Tok.motion_pressScale, width(shuffle.scaled) / rest, FLAT)
        until(t0 + 150 + FRAME * 2)
        frames = 0
        until(t0 + 150 + Held.Nav.motion_turnstileOut.toLong() - FRAME)
        assertEquals("leaving page in the hold asks for no frames", 0, frames)
        settle(1500)
    }

    @Test
    fun aMoveUnderTheSlopNearARowsEdgeStillTaps() {
        start(listOf(Page.Home, Page.Music))
        val row = node("row.0").fetchSemanticsNode().boundsInRoot
        val slop = slop()
        rule.onRoot().performTouchInput { down(Offset(row.left + slop + 10f, row.center.y)) }
        settle(150)
        rule.onRoot().performTouchInput { moveBy(Offset(-(slop - 8f), 0f)) }
        settle(FRAME)
        rule.onRoot().performTouchInput { up() }
        settle(1500)
        assertTrue(shell.nav.top is Page.Artist)
    }

    @Test
    fun aLyricsModeChangeFromAnotherFingerDropsAHeldPress() {
        start(NP)
        lyricsOn()(c)
        settle(1000)
        val play = NP_ITEMS.first { it.name == "play" }
        val rest = width(play.scaled)
        val a = node(play.hit).fetchSemanticsNode().boundsInRoot.center
        val art = node("np.art").fetchSemanticsNode().boundsInRoot.center
        rule.onRoot().performTouchInput { down(0, a) }
        settle(150)
        assertEquals("held", Tok.motion_pressScale, width(play.scaled) / rest, FLAT)
        rule.onRoot().performTouchInput { down(1, art) }
        settle(FRAME)
        rule.onRoot().performTouchInput { up(1) }
        settle(FRAME * 2)
        assertTrue("lyrics mode on", c.lyricsMode())
        assertEquals("lyrics mode while down", 1f, width(play.scaled) / rest, FLAT)
        rule.onRoot().performTouchInput { up(0) }
        settle(1000)
    }

    @Test
    fun aSourceChangeDropsAHeldPress() {
        start(NP)
        val play = NP_ITEMS.first { it.name == "play" }
        val rest = width(play.scaled)
        node(play.hit).performTouchInput { down(center) }
        settle(150)
        assertEquals("held", Tok.motion_pressScale, width(play.scaled) / rest, FLAT)
        c.device = c.device.withState(c.device.playback.state.copy(source = Enums.Source.phone), SystemClock.elapsedRealtime())
        settle(FRAME * 2)
        assertEquals("source change while down", 1f, width(play.scaled) / rest, FLAT)
        node(play.hit).performTouchInput { up() }
        settle(1000)
    }

    @Test
    fun aListRowTapThatOpensTheArtistHoldsTheRowThroughTheOutLeg() {
        start(listOf(Page.Home, Page.Music))
        leaving(Item("artist row", "${TEXT}Utada Hikaru", "${TEXT}Utada Hikaru"), 120)
        assertTrue(shell.nav.top is Page.Artist)
    }

    @Test
    fun aLongPressFiringResetsThePress() {
        start(NP)
        val heart = NP_ITEMS.first { it.name == "heart" }
        val rest = width(heart.scaled)
        val acts = c.actions.size
        node(heart.hit).performTouchInput { down(center) }
        settle(Tok.input_keyHoldMs.toLong() + 50)
        assertEquals(1f, width(heart.scaled) / rest, FLAT)
        node(heart.hit).performTouchInput { up() }
        settle(600)
        assertEquals(acts, c.actions.size)
    }

    @Test
    fun aSecondPressAtTheReturnMidpointStartsFromItsValue() {
        start(NP)
        val play = NP_ITEMS.first { it.name == "play" }
        val rest = width(play.scaled)
        val t0 = rule.mainClock.currentTime
        node(play.hit).performTouchInput { down(center) }
        until(t0 + 50)
        node(play.hit).performTouchInput { up() }
        until(t0 + 300)
        node(play.hit).performTouchInput { down(center) }
        rule.mainClock.advanceTimeByFrame()
        assertEquals(0.98f, width(play.scaled) / rest, LEG)
        until(t0 + 350)
        assertEquals(0.97f, width(play.scaled) / rest, LEG)
        node(play.hit).performTouchInput { up() }
        settle(600)
    }

    @Test
    fun aDragPastTheSlopReturnsAtOnceAndDoesNotAct() {
        start(NP)
        val slop = slop()
        for (item in NP_ITEMS + Item("back", "back", "back.ring")) {
            val rest = width(item.scaled)
            val transports = c.transports.size
            val actions = c.actions.size
            val stack = shell.nav.stack.toList()
            val t0 = rule.mainClock.currentTime
            node(item.hit).performTouchInput { down(center) }
            until(t0 + 150)
            node(item.hit).performTouchInput { moveBy(Offset(0f, slop + 10f)) }
            read("slop", item, rest, t0, 200, 0.98f)
            read("slop", item, rest, t0, 250, 1f)
            node(item.hit).performTouchInput { up() }
            settle(600)
            assertEquals("${item.name} transports", transports, c.transports.size)
            assertEquals("${item.name} actions", actions, c.actions.size)
            assertEquals("${item.name} page", stack, shell.nav.stack.toList())
        }
    }

    @Test
    fun aDragOnTheArtistRingsReturnsAtOnceAndDoesNotAct() {
        start(listOf(Page.Home, Page.Music, Page.Artist("Utada Hikaru")))
        val slop = slop()
        for (item in listOf(Item("artist play", "artist.play", "artist.play.ring"), Item("artist shuffle", "artist.shuffle", "artist.shuffle.ring"))) {
            val rest = width(item.scaled)
            val stack = shell.nav.stack.toList()
            val t0 = rule.mainClock.currentTime
            node(item.hit).performTouchInput { down(center) }
            until(t0 + 150)
            node(item.hit).performTouchInput { moveBy(Offset(slop + 10f, 0f)) }
            read("slop", item, rest, t0, 200, 0.98f)
            read("slop", item, rest, t0, 250, 1f)
            node(item.hit).performTouchInput { up() }
            settle(600)
            assertEquals("${item.name} page", stack, shell.nav.stack.toList())
        }
    }

    @Test
    fun aScrollOrAPivotSwipeOnAPressedRowReturnsAtOnce() {
        start(listOf(Page.Home, Page.Music))
        val row = Item("artist row", "${TEXT}Utada Hikaru", "${TEXT}Utada Hikaru")
        val slop = slop()
        for (move in listOf(Offset(0f, -slop * 2), Offset(-slop * 2, 0f))) {
            val rest = width(row.scaled)
            val t0 = rule.mainClock.currentTime
            node(row.hit).performTouchInput { down(center) }
            until(t0 + 150)
            node(row.hit).performTouchInput { moveBy(move) }
            read("scroll $move", row, rest, t0, 260, 1f)
            node(row.hit).performTouchInput { up() }
            settle(1200)
            assertEquals(listOf(Page.Home, Page.Music), shell.nav.stack.toList())
            if (shell.nav.top == Page.Music) settle(100)
        }
    }

    @Test
    fun theOverlayOrASheetDropsAHeldPressAndLeavesAReleaseHold() {
        start(NP)
        val play = NP_ITEMS.first { it.name == "play" }
        val rest = width(play.scaled)
        node(play.hit).performTouchInput { down(center) }
        settle(150)
        shell.overlay.show()
        settle(FRAME * 2)
        assertEquals("overlay while down", 1f, width(play.scaled) / rest, FLAT)
        node(play.hit).performTouchInput { up() }
        settle(3000)
        val t0 = rule.mainClock.currentTime
        node(play.hit).performTouchInput { down(center) }
        until(t0 + 50)
        node(play.hit).performTouchInput { up() }
        until(t0 + 150)
        shell.overlay.show()
        read("overlay in hold", play, rest, t0, 249, 0.96f, FLAT)
        read("overlay in hold", play, rest, t0, 300, 0.98f)
        settle(3000)
        node(play.hit).performTouchInput { down(center) }
        settle(150)
        shell.sheet.open("confirm", listOf("apply", "cancel")) {}
        settle(FRAME * 2)
        assertEquals("sheet while down", 1f, width(play.scaled) / rest, FLAT)
        node(play.hit).performTouchInput { up() }
        settle(600)
    }

    @Test
    fun onStopResetsAPressInItsHold() {
        start(NP)
        val play = NP_ITEMS.first { it.name == "play" }
        val rest = width(play.scaled)
        val t0 = rule.mainClock.currentTime
        node(play.hit).performTouchInput { down(center) }
        until(t0 + 50)
        node(play.hit).performTouchInput { up() }
        until(t0 + 150)
        owner.registry.currentState = Lifecycle.State.CREATED
        settle(FRAME * 2)
        assertEquals(1f, width(play.scaled) / rest, FLAT)
        owner.registry.currentState = Lifecycle.State.RESUMED
        settle(600)
    }

    @Test
    fun twoItemsDrawPressedAtMost() {
        start(NP)
        val prev = NP_ITEMS.first { it.name == "prev" }
        val next = NP_ITEMS.first { it.name == "next" }
        val play = NP_ITEMS.first { it.name == "play" }
        val restPrev = width(prev.scaled)
        val restNext = width(next.scaled)
        val a = node(prev.hit).fetchSemanticsNode().boundsInRoot.center
        val b = node(next.hit).fetchSemanticsNode().boundsInRoot.center
        var t0 = rule.mainClock.currentTime
        rule.onRoot().performTouchInput { down(0, a) }
        until(t0 + 150)
        rule.onRoot().performTouchInput { down(1, b) }
        until(t0 + 200)
        assertEquals("held first returns", 0.98f, width(prev.scaled) / restPrev, LEG)
        until(t0 + 260)
        assertEquals("held first back", 1f, width(prev.scaled) / restPrev, LEG)
        rule.onRoot().performTouchInput {
            cancel()
        }
        settle(1000)
        t0 = rule.mainClock.currentTime
        node(prev.hit).performTouchInput { down(center) }
        until(t0 + 50)
        node(prev.hit).performTouchInput { up() }
        until(t0 + 100)
        node(next.hit).performTouchInput { down(center) }
        read("tapped first keeps its hold", prev, restPrev, t0, 249, 0.96f, FLAT)
        read("tapped first keeps its hold", prev, restPrev, t0, 300, 0.98f)
        node(next.hit).performTouchInput { up() }
        settle(1000)
        t0 = rule.mainClock.currentTime
        node(prev.hit).performTouchInput { down(center) }
        until(t0 + 50)
        node(prev.hit).performTouchInput { up() }
        until(t0 + 60)
        node(next.hit).performTouchInput { down(center) }
        until(t0 + 110)
        node(next.hit).performTouchInput { up() }
        until(t0 + 270)
        node(play.hit).performTouchInput { down(center) }
        rule.mainClock.advanceTimeByFrame()
        assertEquals("third snaps the older", 1f, width(prev.scaled) / restPrev, FLAT)
        assertEquals("the newer keeps its hold", Tok.motion_pressScale, width(next.scaled) / restNext, FLAT)
        node(play.hit).performTouchInput { up() }
        settle(1000)
    }

    @Test
    fun theHoldAsksForNoFrames() {
        start(NP)
        val play = NP_ITEMS.first { it.name == "play" }
        val t0 = rule.mainClock.currentTime
        node(play.hit).performTouchInput { down(center) }
        until(t0 + 50)
        node(play.hit).performTouchInput { up() }
        until(t0 + 50 + Tok.motion_press.toLong() + FRAME)
        frames = 0
        until(t0 + 50 + Tok.motion_touchReturnDelay.toLong() - 1)
        assertEquals(0, frames)
        settle(600)
    }

    @Test
    fun aZeroMotionScaleSnapsTheLegsAndKeepsTheHold() {
        motion.factor = 0f
        start(NP)
        val play = NP_ITEMS.first { it.name == "play" }
        val rest = width(play.scaled)
        val t0 = rule.mainClock.currentTime
        node(play.hit).performTouchInput { down(center) }
        rule.mainClock.advanceTimeByFrame()
        assertEquals(Tok.motion_pressScale, width(play.scaled) / rest, FLAT)
        until(t0 + 50)
        node(play.hit).performTouchInput { up() }
        read("snap", play, rest, t0, 249, Tok.motion_pressScale, FLAT)
        read("snap", play, rest, t0, 250 + FRAME * 2, 1f, FLAT)
        motion.factor = 1f
    }

    private companion object {
        const val TEXT = "text:"
        const val LEG = 0.013f
        const val FLAT = 0.002f
        const val FRAME = 16L
        val NP = listOf(Page.Home, Page.NowPlaying)
        val HOME = listOf(Page.Home)
        val NP_ITEMS = listOf(
            Item("play", "np.toggle", "np.toggle.ring"),
            Item("prev", "np.prev", "np.prev.glyph"),
            Item("next", "np.nextTrack", "np.nextTrack.glyph"),
            Item("shuffle", "np.shuffle", "np.shuffle.ring"),
            Item("repeat", "np.repeat", "np.repeat.ring"),
            Item("heart", "np.heart", "np.heart.ring"),
        )
    }
}
