package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Roles
import net.seilent.funecomp.ui.Shape
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.Ui
import net.seilent.funecomp.ui.homeItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class HomeLayoutTest {
    @get:Rule
    val rule = createComposeRule()

    private class Frame(val w: Int, val h: Int, val shell: Shell)

    private val c = SimControl("v1")
    private val link = LinkView(Phase.LIVE, "V1", everLive = true)
    private val names = homeItems(c.device, link).map { it.name }
    private var frame by mutableStateOf<Frame?>(null)
    private var shown = false

    private fun show(w: Int, h: Int): Shell {
        if (!shown) {
            shown = true
            rule.mainClock.autoAdvance = false
            rule.setContent {
                val f = frame ?: return@setContent
                key(f) { Box(Modifier.requiredSize(f.w.dp, f.h.dp)) { App(c.device, link, c, f.shell) } }
            }
        }
        val shell = Shell(Nav(listOf(Page.Home)))
        frame = Frame(w, h, shell)
        settle(1000)
        return shell
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun ui() = rule.onNodeWithTag("home").fetchSemanticsNode().size.let { Ui.of(it.width.toFloat(), it.height.toFloat()) }

    private fun layout(n: SemanticsNode): TextLayoutResult {
        val out = ArrayList<TextLayoutResult>()
        n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(out)
        return out.single()
    }

    private fun texts(item: String) = rule.onAllNodes(hasAnyAncestor(hasTestTag("home.$item")) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes()

    private fun word(item: String) = texts(item).single { it.config[SemanticsProperties.Text].joinToString("") == item }

    private fun alpha(item: String) = layout(word(item)).layoutInput.style.color.alpha

    private fun node(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode()

    private fun px(t: TextLayoutResult) = with(rule.density) { t.layoutInput.style.fontSize.toPx() }

    private fun inkRight(item: String) = layout(word(item)).let { it.getPathForRange(0, it.layoutInput.text.length).getBounds().right }

    private fun sub(item: String) = word(item).let { wn -> texts(item).single { it.id != wn.id } }

    private fun semilight(item: String?) {
        names.forEach { assertEquals("$it with ${item ?: "nothing"} pressed", FontWeight(350), layout(word(it)).layoutInput.style.fontWeight) }
    }

    private fun scroll() = rule.onNodeWithTag("home.column").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun marked(item: String) {
        names.forEach { assertEquals("$it with $item marked", if (it == item) 1f else 0.6f, alpha(it), 0.005f) }
    }

    @Test
    fun theTouchMarkDimsTheOthers() {
        val shell = show(411, 960)
        marked("now playing")
        rule.onNodeWithTag("home.settings").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(100)
        val mid = alpha("settings")
        assertEquals(0.8f, mid, 0.05f)
        rule.mainClock.advanceTimeBy(48)
        assertEquals(0.4f * 48 / Tok.motion_focusAlpha, alpha("settings") - mid, 0.01f)
        rule.mainClock.advanceTimeBy(100)
        marked("settings")
        rule.onNodeWithTag("home.settings").performTouchInput { up() }
        settle(1000)
        assertEquals(listOf(Page.Home, Page.Settings), shell.nav.stack.toList())
    }

    @Test
    fun theMarkMovesAtTheDown() {
        val shell = show(411, 960)
        rule.onNodeWithTag("home.playlists").performTouchInput { down(center) }
        settle(300)
        marked("playlists")
        rule.onNodeWithTag("home.playlists").performTouchInput { cancel() }
        settle(1000)
        marked("playlists")
        assertEquals(listOf(Page.Home), shell.nav.stack.toList())
    }

    @Test
    fun aHeldItemKeepsItsLabelAndSubtitleAtRest() {
        show(411, 960)
        val u = ui().u
        val rest = word("playlists").boundsInRoot
        val subRest = sub("playlists").boundsInRoot
        rule.onNodeWithTag("home.playlists").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(200)
        val semi = layout(word("playlists"))
        val held = word("playlists").boundsInRoot
        assertEquals(FontWeight(350), semi.layoutInput.style.fontWeight)
        assertEquals(rest.width, held.width, 0.01f)
        assertEquals(rest.left, held.left, 0.01f * u)
        assertEquals(rest.center.y, held.center.y, 0.01f * u)
        assertEquals(subRest.width, sub("playlists").boundsInRoot.width, 0.01f)
        assertEquals(subRest.left, sub("playlists").boundsInRoot.left, 0.01f * u)
        rule.onNodeWithTag("home.playlists").performTouchInput { cancel() }
        settle(1000)
    }

    @Test
    fun theWidestWordStaysWholeOnLandscape() {
        for ((w, h) in listOf(960 to 600, 1280 to 800, 960 to 411)) {
            show(w, h)
            val ui = ui()
            val u = ui.u
            val home = node("home")
            val o = home.positionInRoot
            val n = "$w x $h"
            assertEquals(n, Shape.L, ui.shape)
            val widest = names.maxBy { layout(word(it)).size.width }
            val wn = word(widest)
            val img = rule.onRoot().pixels().also { System.gc() }
            val rows = wn.positionInRoot.y.roundToInt() until (wn.positionInRoot.y + layout(wn).firstBaseline).roundToInt()
            val cols = wn.positionInRoot.x.roundToInt() until (o.x + home.size.width).roundToInt()
            val lit = cols.lastOrNull { x -> rows.any { y -> img.getRGB(x, y).let { p -> minOf(p shr 16 and 0xff, p shr 8 and 0xff, p and 0xff) > 40 } } }
            val right = (checkNotNull(lit) { "$n $widest has no ink" } + 1 - o.x) / u
            assertTrue("$n $widest ink ends at $right past ${ui.w - Tok.edge}", right <= ui.w - Tok.edge)
            val pen = wn.positionInRoot.x - o.x
            if (h == 411) {
                assertEquals("$n menu x stays 240", 292f * u, pen, 1f)
            } else {
                assertEquals("$n menu x on the Semilight bound", (minOf(240f, ui.w - Tok.edge - 52f - names.maxOf { inkRight(it) } / u) + 52f) * u, pen, 1f)
            }
        }
    }

    @Test
    fun theWidestWordStaysWholeWhilePressed() {
        for ((w, h) in listOf(960 to 600, 1280 to 800, 960 to 411, 411 to 960)) {
            show(w, h)
            val ui = ui()
            val u = ui.u
            val home = node("home")
            val o = home.positionInRoot
            val n = "$w x $h"
            val widest = names.maxBy { layout(word(it)).size.width }
            rule.onNodeWithTag("home.$widest").performTouchInput { down(center) }
            rule.mainClock.advanceTimeBy(200)
            val right = (word(widest).positionInRoot.x + inkRight(widest) - o.x) / u
            val limit = if (h > w) ui.w else ui.w - Tok.edge
            assertTrue("$n pressed $widest ink ends at $right past $limit", right <= limit)
            rule.onNodeWithTag("home.$widest").performTouchInput { cancel() }
            settle(1000)
        }
    }

    @Test
    fun aPressedItemKeepsItsSemilight() {
        val shell = show(411, 960)
        val slop = ui().px(Tok.touchSlop)
        val item = rule.onNodeWithTag("home.settings")
        item.performTouchInput { down(center) }
        rule.mainClock.advanceTimeByFrame()
        semilight("settings")
        assertEquals(Roles.homeSubtitle.weight, layout(sub("settings")).layoutInput.style.fontWeight)
        item.performTouchInput { moveBy(Offset(slop * 2, 0f)) }
        rule.mainClock.advanceTimeByFrame()
        semilight(null)
        item.performTouchInput { up() }
        settle(1000)
        item.performTouchInput { down(center) }
        rule.mainClock.advanceTimeByFrame()
        semilight("settings")
        item.performTouchInput { cancel() }
        rule.mainClock.advanceTimeByFrame()
        semilight(null)
        settle(1000)
        assertEquals(listOf(Page.Home), shell.nav.stack.toList())
        val rest = sub("settings").boundsInRoot.width
        item.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(150)
        semilight("settings")
        item.performTouchInput { up() }
        rule.mainClock.advanceTimeByFrame()
        semilight(null)
        assertEquals(1f, sub("settings").boundsInRoot.width / rest, 0.002f)
        settle(1000)
        assertEquals(listOf(Page.Home, Page.Settings), shell.nav.stack.toList())
    }

    @Test
    fun theItemsSitAtTheDevicePlaces() {
        for ((w, h) in listOf(411 to 960, 411 to 438, 960 to 600)) {
            val shell = show(w, h)
            val ui = ui()
            val u = ui.u
            val home = node("home")
            val o = home.positionInRoot
            val x = if (ui.shape == Shape.L) minOf(240f, ui.w - 16f - 52f - names.maxOf { inkRight(it) } / u) + 52f else 52f
            val pitch = if (ui.shape == Shape.L) 48f else 55f
            val n = "$w x $h"
            names.forEachIndexed { i, item ->
                val wn = word(item)
                val wl = layout(wn)
                val sn = texts(item).single { it.id != wn.id }
                val sl = layout(sn)
                val baseline = wn.positionInRoot.y + wl.firstBaseline - o.y
                assertEquals("$n $item size", 39.7f, px(wl) / u, 0.5f / u + 1e-3f)
                assertEquals("$n $item x", x * u, wn.positionInRoot.x - o.x, 1f)
                assertEquals("$n $item baseline", (100.6f + pitch * i) * u, baseline, 1f)
                assertEquals("$n $item subtitle size", 9f, px(sl) / u, 0.5f / u + 1e-3f)
                assertEquals("$n $item subtitle x", 11.1f * u, sn.positionInRoot.x - wn.positionInRoot.x, 1f)
                assertEquals("$n $item subtitle cap top", 3.5f * u, sn.positionInRoot.y + sl.firstBaseline - Tok.capHeight * px(sl) - o.y - baseline, 1f)
                val box = node("home.$item")
                assertEquals("$n $item box left", (x - 8f) * u, box.positionInRoot.x - o.x, 1f)
                assertEquals("$n $item box right", home.size.width.toFloat(), box.positionInRoot.x - o.x + box.size.width, 1f)
                assertEquals("$n $item box top", baseline - 27.79f * u, box.positionInRoot.y - o.y, 1f)
            }
            val left = ui.px(20f)
            rule.mainClock.autoAdvance = true
            rule.onNodeWithTag("home.column").performTouchInput { swipe(Offset(left, bottom - 1f), Offset(left, top + 1f)) }
            rule.mainClock.autoAdvance = false
            settle(1000)
            val band = node("home.settings")
            val y = band.positionInRoot.y - o.y + band.size.height / 2f
            rule.onNodeWithTag("home").performTouchInput { click(Offset(width - ui.px(4f), y)) }
            settle(1000)
            assertEquals(n, listOf(Page.Home, Page.Settings), shell.nav.stack.toList())
        }
    }

    private fun lit(p: Int) = (p shr 16 and 0xff) > (Tok.alpha_mid * 255 / 2).toInt()

    @Test
    fun theRingFollowsTheMenuOnLandscape() {
        for ((w, h) in listOf(960 to 600, 1280 to 800, 960 to 411)) {
            show(w, h)
            val u = ui().u
            val o = node("home").positionInRoot
            val n = "$w x $h"
            val pen = word("music").positionInRoot.x - o.x
            val box = node("home.ring")
            assertEquals("$n ring box left", pen - 52f * u, box.positionInRoot.x - o.x, 1f)
            assertEquals("$n ring box right", pen - 8f * u, box.positionInRoot.x - o.x + box.size.width, 1f)
            val img = rule.onRoot().pixels().also { System.gc() }
            val row = node("home.ring.ring").boundsInRoot.center.y.roundToInt()
            val xs = ((o.x + pen - 52f * u).roundToInt() until (o.x + pen - 8f * u).roundToInt()).filter { lit(img.getRGB(it, row)) }
            assertTrue("$n no ring on its row", xs.isNotEmpty())
            assertEquals("$n ring centre", pen - 26.3f * u, (xs.first() + xs.last() + 1) / 2f - o.x, 0.5f * u)
            if (h == 411) assertEquals("$n menu x stays 240", 292f * u, pen, 1f)
        }
    }

    @Test
    fun theWordsAreSemilight() {
        show(411, 960)
        names.forEach { assertEquals(it, FontWeight(350), layout(word(it)).layoutInput.style.fontWeight) }
        val input = layout(word("music")).layoutInput
        val measurer = TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection)
        val size = with(input.density) { 2048f.toSp() }
        fun advance(family: FontFamily, weight: FontWeight) = measurer.measure("m", TextStyle(fontFamily = family, fontWeight = weight, fontSize = size), softWrap = false, maxLines = 1).multiParagraph.getLineWidth(0)
        val semilight = advance(FontFamily(Font(R.font.selawksl)), FontWeight.Normal)
        val light = advance(FontFamily(Font(R.font.selawkl)), FontWeight.Normal)
        assertTrue("selawksl $semilight selawkl $light", abs(semilight - light) > 8f)
        assertEquals(semilight, advance(input.style.fontFamily!!, input.style.fontWeight!!), 1f)
    }

    @Test
    fun aDragFromAnItemScrollsTheColumnAndKeepsTheMark() {
        val shell = show(960, 600)
        val x = ui().px(100f)
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("home.column").performTouchInput { swipe(Offset(x, bottom - 1f), Offset(x, top + 1f)) }
        rule.mainClock.autoAdvance = false
        settle(1000)
        val end = scroll()
        assertTrue(end > 0f)
        marked("now playing")
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("home.settings").performTouchInput { swipe(center, center + Offset(0f, height.toFloat())) }
        rule.mainClock.autoAdvance = false
        settle(1000)
        assertTrue("scroll ${scroll()} from $end", scroll() < end)
        marked("settings")
        assertEquals(listOf(Page.Home), shell.nav.stack.toList())
    }
}
