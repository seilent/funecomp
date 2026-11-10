package net.seilent.funecomp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import com.github.takahirom.roborazzi.captureRoboImage
import net.seilent.funecomp.artist.Sources
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.AccentState
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.Black
import net.seilent.funecomp.ui.Entry
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.LocalAccent
import net.seilent.funecomp.ui.LocalUi
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.SearchField
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.TextEntry
import net.seilent.funecomp.ui.Ui
import net.seilent.funecomp.ui.accentFor
import net.seilent.funecomp.ui.white
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class AccentOffTest {
    @get:Rule
    val rule = createComposeRule()

    private val album = java.lang.Long.toUnsignedString(net.seilent.funecomp.sim.Library.synthetic().tracks.first().id)
    private val hue = accentFor(Tok.accent_hueDefault.toInt())
    private val full = white(Tok.alpha_full)
    private lateinit var c: SimControl
    private lateinit var shell: Shell

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun start(off: Boolean, pages: List<Page>, faults: List<String> = emptyList(), playing: Boolean = false) {
        c = SimControl("v1", faults = faults)
        if (off) c.set("accentOff", "on")
        if (playing) c.transport(Enums.TransportCmd.next)
        c.setTexts.clear()
        shell = Shell(Nav(pages))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) {
                App(c.device, LinkView(Phase.LIVE, "V1", everLive = true), c, shell, Sources.COMMONS, {})
            }
        }
        settle(1000)
    }

    private fun settings(off: Boolean, pivot: String, faults: List<String> = emptyList()) {
        start(off, listOf(Page.Home, Page.Settings), faults)
        rule.walkPivots(c.device.schema.rows, pivot) { settle(1000) }
    }

    private fun tap(tag: String, x: Float = 0.5f) {
        if (SemanticsActions.ScrollToIndex in rule.onNodeWithTag("rows").fetchSemanticsNode().config) {
            rule.mainClock.autoAdvance = true
            rule.onNodeWithTag("rows").performScrollToNode(hasTestTag(tag))
            rule.mainClock.autoAdvance = false
        }
        step(tag, x)
    }

    private fun step(tag: String, x: Float) {
        rule.onNodeWithTag(tag).performTouchInput { click(Offset(width * x, height / 2f)) }
        settle(600)
    }

    private fun shot(node: SemanticsNodeInteraction): BufferedImage {
        val file = File.createTempFile("accent", ".png").apply { deleteOnExit() }
        node.captureRoboImage(file.path)
        return ImageIO.read(file)
    }

    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val out = ArrayList<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
        return out.single()
    }

    private fun rgb(p: Int) = Triple(p shr 16 and 0xFF, p shr 8 and 0xFF, p and 0xFF)

    private fun chroma(img: BufferedImage): Boolean {
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val (r, g, b) = rgb(img.getRGB(x, y))
            if (maxOf(r, g, b) - minOf(r, g, b) > 30) return true
        }
        return false
    }

    private fun bright(img: BufferedImage): Boolean {
        for (y in 0 until img.height) for (x in 0 until img.width) if (rgb(img.getRGB(x, y)).let { minOf(it.first, it.second, it.third) } > 128) return true
        return false
    }

    private fun near(a: Color, b: Color) = abs(a.red - b.red) < 0.01f && abs(a.green - b.green) < 0.01f && abs(a.blue - b.blue) < 0.01f && abs(a.alpha - b.alpha) < 0.01f

    private fun text(node: SemanticsNodeInteraction, off: Boolean, bold: FontWeight?) {
        settle(Tok.motion_focusAlpha.toLong() + 16)
        val style = layout(node).layoutInput.style
        assertTrue("$off ${style.color}", near(style.color, if (off) full else hue))
        if (bold != null) assertEquals(off.toString(), if (off) FontWeight.Bold else bold, style.fontWeight)
    }

    private fun blinks(shots: List<BufferedImage>) =
        assertTrue("the cursor blinks", shots.map { img -> (0 until img.height).sumOf { y -> (0 until img.width).sumOf { x -> img.getRGB(x, y).toLong() and 0xFF } } }.distinct().size > 1)

    private fun blink(tag: String): List<BufferedImage> = (0 until 4).map {
        settle(250)
        shot(rule.onNodeWithTag(tag, useUnmergedTree = true))
    }

    private fun pageGround(off: Boolean) {
        start(off, listOf(Page.Home, Page.Settings))
        val m = shot(rule.onRoot())
        val black = rgb(Color(Tok.color_black).toArgb())
        assertEquals(off, (0 until 40).any { y -> (0 until m.width).any { x -> rgb(m.getRGB(x, y)) == black } })
    }

    @Test
    fun aPushedPageHasAFlatGroundUnderOff() = pageGround(true)

    @Test
    fun aPushedPageHasTheAccentGroundWithOffOff() = pageGround(false)

    private fun playingTitle(off: Boolean) {
        start(off, listOf(Page.Home, Page.Music, Page.Album(album, "First Love", "Utada Hikaru")), playing = true)
        text(rule.onNode(hasTestTag("primary") and hasAnyAncestor(hasTestTag("row.1")), useUnmergedTree = true), off, FontWeight.Normal)
    }

    @Test
    fun thePlayingTitleIsWhiteAndBoldUnderOff() = playingTitle(true)

    @Test
    fun thePlayingTitleIsTheAccentWithOffOff() = playingTitle(false)

    private fun progress(off: Boolean) {
        start(off, listOf(Page.Home, Page.NowPlaying), playing = true)
        c.transport(Enums.TransportCmd.seek, seekMs = 100_000)
        settle(1000)
        val img = shot(rule.onNodeWithTag("np.progress"))
        assertEquals(off.toString(), !off, chroma(img))
        assertTrue(bright(img) || !off)
    }

    @Test
    fun theProgressFillIsWhiteUnderOff() = progress(true)

    @Test
    fun theProgressFillIsTheAccentWithOffOff() = progress(false)

    private fun battery(off: Boolean) {
        start(off, listOf(Page.Home, Page.NowPlaying), playing = true)
        val d = c.device
        c.device = d.copy(playback = d.playback.copy(state = d.playback.state.copy(battery = Tok.batteryLowPercent.toInt() - 1)))
        settle(600)
        val img = shot(rule.onNodeWithTag("status.battery", useUnmergedTree = true))
        assertEquals(off.toString(), !off, chroma(img))
    }

    @Test
    fun theLowBatteryGlyphIsWhiteUnderOff() = battery(true)

    @Test
    fun theLowBatteryGlyphIsTheAccentWithOffOff() = battery(false)

    private fun editedValue(off: Boolean) {
        settings(off, "output")
        tap("set.balance")
        text(rule.onNode(hasText("CENTER") and hasAnyAncestor(hasTestTag("set.balance")), useUnmergedTree = true), off, FontWeight.Light)
    }

    @Test
    fun anEditedValueIsWhiteAndBoldUnderOff() = editedValue(true)

    @Test
    fun anEditedValueIsTheAccentWithOffOff() = editedValue(false)

    private fun swatch(off: Boolean) {
        settings(off, "display")
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("rows").performScrollToNode(hasTestTag("set.accentHue"))
        rule.mainClock.autoAdvance = false
        settle(600)
        assertEquals(off.toString(), !off, chroma(shot(rule.onNodeWithTag("set.accentHue"))))
    }

    @Test
    fun theAccentSwatchIsWhiteUnderOff() = swatch(true)

    @Test
    fun theAccentSwatchIsTheHueWithOffOff() = swatch(false)

    private fun markedItem(off: Boolean) {
        start(off, listOf(Page.Home, Page.NowPlaying))
        shell.sheet.open("offset", listOf("lyrics"), 0, emptyList(), itemValues = { listOf("+100 MS") }, accent = { 0 }) {}
        settle(1000)
        text(rule.onNode(hasText("+100 MS"), useUnmergedTree = true), off, FontWeight.Light)
    }

    @Test
    fun theMarkedSheetValueIsWhiteAndBoldUnderOff() = markedItem(true)

    @Test
    fun theMarkedSheetValueIsTheAccentWithOffOff() = markedItem(false)

    private fun entryText(off: Boolean) {
        start(off, listOf(Page.Home, Page.NowPlaying))
        shell.sheet.openEntry("name") { Entry("12") {} }
        settle(1000)
        text(rule.onNodeWithTag("entry"), off, null)
    }

    @Test
    fun theEntryTextIsWhiteUnderOff() = entryText(true)

    @Test
    fun theEntryTextIsTheAccentWithOffOff() = entryText(false)

    private fun alone(off: Boolean, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) {
                BoxWithConstraints(Modifier.fillMaxSize().background(Black).testTag("alone")) {
                    CompositionLocalProvider(LocalUi provides Ui.of(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()), LocalAccent provides AccentState(hue, off)) { content() }
                }
            }
        }
        settle(600)
    }

    private fun entryCursor(off: Boolean) {
        alone(off) { Entry("12") {} }
        rule.onNodeWithTag("entry").performSemanticsAction(SemanticsActions.RequestFocus)
        settle(100)
        val shots = blink("alone")
        blinks(shots)
        assertEquals(off.toString(), !off, shots.any { chroma(it) })
        assertTrue(shots.any { bright(it) } || !off)
    }

    @Test
    fun theEntryCursorIsWhiteUnderOff() = entryCursor(true)

    @Test
    fun theEntryCursorIsTheAccentWithOffOff() = entryCursor(false)

    private fun searchCursor(off: Boolean) {
        alone(off) { SearchField("", {}, "search.field", ImeAction.Done) }
        rule.onNodeWithTag("search.field").performClick()
        settle(100)
        val shots = blink("alone")
        blinks(shots)
        assertEquals(off.toString(), !off, shots.any { chroma(it) })
        assertTrue(shots.any { bright(it) } || !off)
    }

    @Test
    fun theSearchCursorIsWhiteUnderOff() = searchCursor(true)

    @Test
    fun theSearchCursorIsTheAccentWithOffOff() = searchCursor(false)

    private fun secret(off: Boolean) {
        start(off, listOf(Page.Home, Page.NowPlaying))
        shell.sheet.openEntry("key") { TextEntry("ab", secret = true) {} }
        settle(1000)
        assertEquals((if (off) full else hue).toArgb(), secretField().currentTextColor)
    }

    @Test
    fun theSecretEntryTextIsWhiteUnderOff() = secret(true)

    @Test
    fun theSecretEntryTextIsTheAccentWithOffOff() = secret(false)

    private val first = Tok.accent_hueDefault.toInt()
    private val ring = (Tok.accent_hueCount * Tok.accent_hueStep).toInt()
    private val next = (first + Tok.accent_hueStep.toInt()) % ring

    private val second = (next + Tok.accent_hueStep.toInt()) % ring

    private fun stepped(off: Boolean, faults: List<String> = emptyList(), steps: Int = 1) {
        settings(off, "display", faults)
        tap("set.accentHue")
        repeat(steps) { step("set.accentHue", 0.9f) }
    }

    @Test
    fun aHueStepSendsNothingAndTheApplySendsOneSetTurningOffOff() {
        stepped(true, steps = 2)
        assertEquals(emptyList<String>(), c.setTexts)
        assertEquals("on", c.device.settings.values["accentOff"])
        tap("set.confirm")
        assertEquals(listOf("accentHue=$second\naccentOff=off\n"), c.setTexts)
        assertEquals("off", c.device.settings.values["accentOff"])
    }

    @Test
    fun aHueStepWithOffOffAppliesTheHueOnly() {
        stepped(false)
        assertEquals(emptyList<String>(), c.setTexts)
        tap("set.confirm")
        assertEquals(listOf("accentHue=$next\n"), c.setTexts)
    }

    @Test
    fun anUnchangedApplySendsNothing() {
        stepped(true)
        step("set.accentHue", 0.1f)
        assertFalse(chroma(shot(rule.onNodeWithTag("set.accentHue"))))
        tap("set.confirm")
        assertEquals(emptyList<String>(), c.setTexts)
    }

    @Test
    fun aTapOnAnotherRowAppliesTheHue() {
        stepped(true)
        assertEquals(emptyList<String>(), c.setTexts)
        tap("set.accentOff")
        assertEquals(listOf("accentHue=$next\naccentOff=off\n"), c.setTexts)
        assertFalse(shell.sheet.shown)
    }

    @Test
    fun theBackRingSendsNothingAndKeepsTheOpenedValue() {
        stepped(true)
        rule.onNodeWithTag("header").performClick()
        settle(600)
        assertEquals(emptyList<String>(), c.setTexts)
        assertEquals("$first", c.device.settings.values["accentHue"])
        assertEquals("on", c.device.settings.values["accentOff"])
    }

    @Test
    fun systemBackSendsNothingAndKeepsTheOpenedValue() {
        stepped(true)
        Espresso.pressBack()
        settle(600)
        assertEquals(emptyList<String>(), c.setTexts)
        assertEquals("$first", c.device.settings.values["accentHue"])
        assertEquals("on", c.device.settings.values["accentOff"])
    }

    @Test
    fun aPageChangeDuringTheEditSendsNothing() {
        stepped(false)
        rule.runOnIdle { shell.nav.push(Page.Connection) }
        settle(1000)
        assertEquals(emptyList<String>(), c.setTexts)
    }

    @Test
    fun aPeerWithNoAccentOffRowNeverGetsTheKey() {
        stepped(false, listOf("rows-off=accentOff"))
        assertFalse("accentOff" in c.device.settings.values)
        assertEquals(emptyList<String>(), c.setTexts)
        tap("set.confirm")
        assertEquals(listOf("accentHue=$next\n"), c.setTexts)
    }

    @Test
    fun aStepPreviewsTheHueUnderOffAndLeavesTheAppAccent() {
        stepped(true)
        assertTrue(chroma(shot(rule.onNodeWithTag("set.accentHue"))))
        assertEquals("on", c.device.settings.values["accentOff"])
        assertEquals(emptyList<String>(), c.setTexts)
    }

    @Test
    fun aStepWithOffOffLeavesTheAppAccent() {
        stepped(false)
        assertEquals("$first", c.device.settings.values["accentHue"])
        assertEquals(emptyList<String>(), c.setTexts)
    }

    @Test
    fun balanceKeepsItsSetPerStepAndItsRevertSet() {
        settings(false, "output")
        val opened = c.device.settings.values["balance"]
        tap("set.balance")
        step("set.balance", 0.9f)
        assertEquals(1, c.setTexts.size)
        assertTrue(c.setTexts.single().startsWith("balance="))
        rule.onNodeWithTag("header").performClick()
        settle(600)
        assertEquals(2, c.setTexts.size)
        assertEquals("balance=$opened\n", c.setTexts.last())
    }
}
