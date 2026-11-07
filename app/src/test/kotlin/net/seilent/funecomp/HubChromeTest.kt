package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.PickFor
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
import java.awt.image.BufferedImage
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class HubChromeTest {
    @get:Rule
    val rule = createComposeRule()

    private var size by mutableStateOf(SIZES.first { it.name == "411x960" })

    private lateinit var shell: Shell

    private fun start(page: Page, off: Boolean = false, at: String = "411x960") {
        size = SIZES.first { it.name == at }
        val c = SimControl("v1")
        if (off) c.set("accentOff", "on")
        shell = Shell(Nav(listOf(Page.Home, page)))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val s = size
            Box(Modifier.requiredSize(s.w.dp, s.h.dp)) {
                App(c.device, LinkView(Phase.LIVE, "V1", everLive = true), c, shell, insets = s.bars)
            }
        }
        settle(1500)
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun scale(at: String) = if (at == "411x960") Ui.PORTRAIT_W / 240f else 1f

    private fun canvas(page: String): Rect = rule.onNodeWithTag("hub.$page").fetchSemanticsNode().boundsInRoot

    private fun ui(page: String) = canvas(page).let { Ui.of(it.width, it.height) }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun left(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.x

    private fun layout(tag: String): TextLayoutResult {
        val out = ArrayList<TextLayoutResult>()
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(out)
        return out.single()
    }

    private fun tap(page: String, x: Float, y: Float) {
        val c = canvas(page)
        val u = ui(page).u
        rule.onRoot().performTouchInput { click(Offset(c.left + x * u, c.top + y * u)) }
        settle(1500)
    }

    private fun lit(img: BufferedImage, x: Int) = (0 until img.height).any { y ->
        val p = img.getRGB(x, y)
        (p shr 16 and 0xff) > 8 || (p shr 8 and 0xff) > 8 || (p and 0xff) > 8
    }

    private fun assertPageHeader(name: String, page: String, s: Float) {
        val ui = ui(page)
        val t = layout("header.word")
        val style = t.layoutInput.style
        with(rule.density) {
            assertEquals("$name size", (150f * s * ui.u).roundToInt().toFloat(), style.fontSize.toPx(), 0.5f)
            assertEquals("$name tracking", -1.0f * s * ui.u, style.letterSpacing.toPx(), 0.05f)
        }
        assertEquals("$name weight", FontWeight.Light, style.fontWeight)
        assertEquals("$name alpha", 0.27f, style.color.alpha, 0.005f)
        val top = rule.onNodeWithTag("header.word", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y - canvas(page).top
        assertEquals("$name baseline", 52f * s * ui.u, top + t.firstBaseline, 1f)
    }

    @Test
    fun theHeaderWordIsThePageHeader() {
        for (at in listOf("411x960", "sn411x438", "960x600")) {
            if (at == "411x960") start(Page.Music, at = at) else {
                size = SIZES.first { it.name == at }
                settle(1500)
            }
            assertPageHeader(at, "music", scale(at))
        }
    }

    @Test
    fun theMusicWordCutsAsTheZuneHd() {
        start(Page.Music, off = true)
        val u = ui("music").u
        val img = rule.onNodeWithTag("header.clip", useUnmergedTree = true).pixels().also { System.gc() }
        val right = (img.width - 1 downTo 0).first { lit(img, it) }
        assertTrue("s ink right ${right / u}", right / u in 260.4f..262.0f)
        assertTrue("lit past 262", ((262f * u).roundToInt() until img.width).none { lit(img, it) })
    }

    @Test
    fun theHeaderWordIsClippedAtThePivotBand() {
        for ((at, top) in listOf("411x960" to 74f, "sn411x438" to 58f, "960x600" to 58f)) {
            if (at == "411x960") start(Page.Music, at = at) else {
                size = SIZES.first { it.name == at }
                settle(1500)
            }
            val ui = ui("music")
            assertEquals(at, top * ui.u, bounds("header.clip").height, 1f)
        }
    }

    @Test
    fun theHeaderWordStartsAtThePenX() {
        start(Page.Music)
        assertEquals(-22f * scale("411x960") * ui("music").u, left("header.word") - canvas("music").left, 1f)
    }

    @Test
    fun everyHubPageDrawsThePageHeader() {
        val pages = listOf(
            Page.Settings to "settings",
            Page.Online() to "online",
            Page.Queue to "queue",
            Page.Connection to "connection",
            Page.Firmware to "firmware",
            Page.Browse(PickFor("TSUKI")) to "TSUKI",
        )
        start(pages.first().first)
        for ((page, header) in pages) {
            if (shell.nav.top != page) {
                rule.runOnIdle { shell.nav.popTo(Page.Home) }
                settle(1500)
                rule.runOnIdle { shell.nav.push(page) }
                settle(1500)
            }
            val ui = ui(header)
            val style = layout("header.word").layoutInput.style
            with(rule.density) { assertEquals(header, (150f * scale("411x960") * ui.u).roundToInt().toFloat(), style.fontSize.toPx(), 0.5f) }
            assertEquals(header, FontWeight.Light, style.fontWeight)
            assertEquals(header, 0.27f, style.color.alpha, 0.005f)
        }
    }

    @Test
    fun theHeaderWordIsClippedAtThePivotTop() {
        start(Page.Queue, off = true)
        val c = canvas("queue")
        val u = ui("queue").u
        val word = layout("header.word")
        val right = (left("header.word") + word.getBoundingBox(0).right).roundToInt()
        val count = layout("count")
        val size = with(rule.density) { count.layoutInput.style.fontSize.toPx() }
        val capTop = rule.onNodeWithTag("count", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y + count.firstBaseline - net.seilent.funecomp.proto.gen.Tok.capHeight * size
        val img = rule.onRoot().pixels().also { System.gc() }
        val rows = bounds("header.clip").bottom.roundToInt() until (capTop - u).toInt()
        val cols = c.left.roundToInt().coerceAtLeast(0) until right
        assertTrue(rows.first < rows.last && cols.first < cols.last)
        val lit = rows.flatMap { y -> cols.map { x -> img.getRGB(x, y) } }.count { p -> (p shr 16 and 0xff) > 8 || (p shr 8 and 0xff) > 8 || (p and 0xff) > 8 }
        assertEquals(0, lit)
    }

    @Test
    fun theHeaderBackBoxIsTheBand() {
        start(Page.Settings)
        rule.runOnIdle { shell.nav.push(Page.Connection) }
        settle(1500)
        tap("connection", ui("connection").w / 2, 72f)
        assertEquals(Page.Connection, shell.nav.top)
        tap("connection", ui("connection").w / 2, 30f)
        assertEquals(Page.Settings, shell.nav.top)
        tap("settings", ui("settings").w / 2, 30f)
        assertEquals(Page.Home, shell.nav.top)
    }

    @Test
    fun theShortHeaderBackBoxReachesUnderTheBand() {
        start(Page.Connection, at = "sn411x438")
        tap("connection", ui("connection").w / 2, 65f)
        assertEquals(Page.Home, shell.nav.top)
    }

    @Test
    fun aTapOnAPivotInsideTheBandPicksThePivot() {
        start(Page.Music, at = "sn411x438")
        val c = canvas("music")
        val ui = ui("music")
        val b = bounds("pivot.albums")
        assertTrue(b.top - c.top < 70f * ui.u)
        rule.onRoot().performTouchInput { click(Offset(b.center.x, b.top + 0.25f * b.height)) }
        settle(1500)
        assertEquals(Page.Music, shell.nav.top)
        assertEquals(20f * ui.u, left("pivot.albums") - c.left, 1.5f)
    }
}
