package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.artist.ArtCache
import net.seilent.funecomp.artist.Credit
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
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
    @get:Rule
    val rule = createComposeRule()

    private lateinit var c: SimControl
    private lateinit var shell: Shell

    private class Item(val name: String, val hit: String, val drawn: String)

    private fun start(pages: List<Page>, credits: List<ArtCache.Entry> = emptyList(), prep: (SimControl) -> Unit = {}) {
        c = SimControl("ap80")
        prep(c)
        shell = Shell(Nav(pages))
        rule.mainClock.autoAdvance = false
        rule.setUptimeContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), c, shell, credits = credits) }
        }
        settle(1000)
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun until(at: Long) = settle((at - rule.mainClock.currentTime).coerceAtLeast(0))

    private fun node(tag: String): SemanticsNodeInteraction = if (tag.startsWith(TEXT)) rule.onNode(hasText(tag.removePrefix(TEXT), ignoreCase = true)) else rule.onNodeWithTag(tag, useUnmergedTree = true)

    private fun bounds(tag: String) = node(tag).fetchSemanticsNode().boundsInRoot

    private fun same(name: String, want: Rect, got: Rect) {
        for ((side, w, g) in listOf(Triple("left", want.left, got.left), Triple("top", want.top, got.top), Triple("right", want.right, got.right), Triple("bottom", want.bottom, got.bottom))) assertEquals("$name $side", w, g, EXACT)
    }

    private fun slop() = Tok.touchSlop * Ui.of(411f * rule.density.density, 960f * rule.density.density).u

    private fun held(item: Item) {
        val rest = bounds(item.drawn)
        val t0 = rule.mainClock.currentTime
        node(item.hit).performTouchInput { down(center) }
        for (at in HELD) {
            until(t0 + at)
            same("${item.name} held +$at", rest, bounds(item.drawn))
        }
        node(item.hit).performTouchInput { cancel() }
        settle(FRAME)
        same("${item.name} cancelled", rest, bounds(item.drawn))
        settle(600)
    }

    private fun tapped(item: Item) {
        val rest = bounds(item.drawn)
        val t0 = rule.mainClock.currentTime
        node(item.hit).performTouchInput { down(center) }
        until(t0 + UP)
        node(item.hit).performTouchInput { up() }
        for (at in AFTER) {
            until(t0 + UP + at)
            same("${item.name} after the up +$at", rest, bounds(item.drawn))
        }
        settle(600)
    }

    @Test
    fun nowPlayingItemsKeepTheirBoundsWhileHeldAndAfterTheUp() {
        start(NP)
        for (item in NP_ITEMS) {
            held(item)
            tapped(item)
        }
        held(Item("back", "back", "back.ring"))
    }

    @Test
    fun homeItemsAndTheRingKeepTheirBoundsWhileHeld() {
        start(HOME) { it.holdReply = true }
        held(Item("home item", "home.music", "${TEXT}music"))
        held(Item("home ring", "home.ring", "home.ring.ring"))
        tapped(Item("home ring", "home.ring", "home.ring.ring"))
    }

    @Test
    fun listAndSettingsRowsKeepTheirBoundsWhileHeld() {
        start(listOf(Page.Home, Page.Music))
        rule.pivotTo("music", "artists") { settle(it) }
        held(Item("artist row", "${TEXT}Utada Hikaru", "${TEXT}Utada Hikaru"))
        shell.nav.reset(listOf(Page.Home, Page.Settings))
        settle(1000)
        val row = rule.onAllNodes(SemanticsMatcher("set row") { n -> n.config.getOrNull(SemanticsProperties.TestTag).let { it != null && it.startsWith("set.") && it != "set.confirm" } }, useUnmergedTree = true).fetchSemanticsNodes().first().config[SemanticsProperties.TestTag]
        val title = rule.onAllNodes(hasAnyAncestor(hasTestTag(row)) and hasTestTag("settings.title"), useUnmergedTree = true).fetchSemanticsNodes().single().boundsInRoot
        val t0 = rule.mainClock.currentTime
        node(row).performTouchInput { down(center) }
        for (at in HELD) {
            until(t0 + at)
            same("settings title held +$at", title, rule.onAllNodes(hasAnyAncestor(hasTestTag(row)) and hasTestTag("settings.title"), useUnmergedTree = true).fetchSemanticsNodes().single().boundsInRoot)
        }
        node(row).performTouchInput { cancel() }
        settle(600)
    }

    @Test
    fun creditRowsKeepTheirBoundsWhileHeld() {
        val e = ArtCache.Entry("b539e453-c4fe-47e3-8a07-8517eac74429", "宇多田ヒカル", "b539e453-c4fe-47e3-8a07-8517eac74429", "commons", Credit("Goldnrush Podcast", "CC BY 4.0", ""), "u", 1, "File:A.jpg", "s", "宇多田ヒカル")
        start(listOf(Page.Home, Page.Online(), Page.ArtCredits), credits = listOf(e))
        held(Item("credit row", "art.credit.0", "${TEXT}宇多田ヒカル"))
    }

    @Test
    fun theArtistRingsKeepTheirBoundsWhileHeld() {
        start(listOf(Page.Home, Page.Music, Page.Artist("Utada Hikaru")))
        held(Item("artist play", "artist.play", "artist.play.ring"))
        held(Item("artist shuffle", "artist.shuffle", "artist.shuffle.ring"))
    }

    @Test
    fun theLyricsMarkAloneDrawsAtThePressedScale() {
        start(NP, prep = lyricsOn())
        val rest = bounds("np.mark.ring").width
        val t0 = rule.mainClock.currentTime
        node("np.mark.hit").performTouchInput { down(center) }
        until(t0 + Tok.motion_press.toLong() + FRAME)
        assertEquals("held", Tok.motion_pressScale, bounds("np.mark.ring").width / rest, FLAT)
        node("np.mark.hit").performTouchInput { cancel() }
        settle(Tok.motion_press.toLong() + FRAME)
        assertEquals("back", 1f, bounds("np.mark.ring").width / rest, FLAT)
        settle(1000)
    }

    @Test
    fun aMoveUnderTheSlopNearARowsEdgeStillTaps() {
        start(listOf(Page.Home, Page.Music))
        rule.pivotTo("music", "artists") { settle(it) }
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
    fun aDragPastTheSlopDoesNotAct() {
        start(NP)
        val slop = slop()
        for (item in NP_ITEMS + Item("back", "back", "back.ring")) {
            val rest = bounds(item.drawn)
            val transports = c.transports.size
            val actions = c.actions.size
            val stack = shell.nav.stack.toList()
            node(item.hit).performTouchInput { down(center) }
            settle(150)
            node(item.hit).performTouchInput { moveBy(Offset(0f, slop + 10f)) }
            settle(FRAME)
            same("${item.name} dragged", rest, bounds(item.drawn))
            node(item.hit).performTouchInput { up() }
            settle(600)
            assertEquals("${item.name} transports", transports, c.transports.size)
            assertEquals("${item.name} actions", actions, c.actions.size)
            assertEquals("${item.name} page", stack, shell.nav.stack.toList())
        }
    }

    @Test
    fun aLongPressFiringDoesNotTap() {
        start(NP)
        val heart = NP_ITEMS.first { it.name == "heart" }
        val rest = bounds(heart.drawn)
        val acts = c.actions.size
        node(heart.hit).performTouchInput { down(center) }
        settle(Tok.input_keyHoldMs.toLong() + 50)
        same("heart long", rest, bounds(heart.drawn))
        node(heart.hit).performTouchInput { up() }
        settle(600)
        assertEquals(acts, c.actions.size)
    }

    private companion object {
        const val TEXT = "text:"
        const val FLAT = 0.002f
        const val EXACT = 0.01f
        const val FRAME = 16L
        const val UP = 150L
        val HELD = listOf(16L, 50L, 100L, 150L)
        val AFTER = listOf(16L, 100L, 200L, 250L, 350L)
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
