package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.Band
import net.seilent.funecomp.proto.Profile
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.state.EqSlots
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.Ui
import net.seilent.funecomp.ui.gridHz
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
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class SettingsRowTitleTest {
    @get:Rule
    val rule = createComposeRule()

    private fun start(page: Page, under: List<Page> = emptyList(), prep: (SimControl) -> Unit = {}) {
        val c = SimControl("v1")
        prep(c)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) {
                App(c.device, LinkView(Phase.LIVE, "V1", everLive = true), c, Shell(Nav(listOf(Page.Home) + under + page)))
            }
        }
        settle()
    }

    private fun settle() {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()
    }

    private fun size(t: TextLayoutResult) = with(rule.density) { t.layoutInput.style.fontSize.toPx() }

    private fun u(page: String) = rule.onNodeWithTag("hub.$page").fetchSemanticsNode().size.let { Ui.of(it.width.toFloat(), it.height.toFloat()).u }

    private fun rows(prefix: String) = rule.onAllNodes(SemanticsMatcher(prefix) { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true }, useUnmergedTree = true)
        .fetchSemanticsNodes().sortedBy { it.positionInRoot.y }.map { it.config[SemanticsProperties.TestTag] }

    private fun title(tag: String): TextLayoutResult {
        val n = rule.onAllNodes(hasAnyAncestor(hasTestTag(tag)) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes().minBy { it.positionInRoot.x }
        val out = ArrayList<TextLayoutResult>()
        n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(out)
        return out.single()
    }

    private fun assertTitle(tag: String, u: Float) {
        val t = title(tag)
        val style = t.layoutInput.style
        with(rule.density) { assertEquals(tag, (19f * u).roundToInt().toFloat(), style.fontSize.toPx(), 0.5f) }
        assertEquals(tag, FontWeight.Bold, style.fontWeight)
        assertEquals(tag, t.layoutInput.text.text.uppercase(), t.layoutInput.text.text)
        assertTrue("$tag alpha ${style.color.alpha}", listOf(1.0f, 0.6f).any { abs(it - style.color.alpha) < 0.005f })
    }

    @Test
    fun settingsRowTitlesAreThePageRowTitle() {
        start(Page.Settings)
        val u = u("settings")
        val tags = rows("set.").take(2)
        assertEquals(2, tags.size)
        for (tag in tags) assertTitle(tag, u)
    }

    @Test
    fun onlineRowTitlesAreThePageRowTitle() {
        start(Page.Online())
        assertTitle(rows("art.").first(), u("online"))
    }

    @Test
    fun theFocusedRowIsFullAndTheOthersDim() {
        start(Page.Settings)
        val tags = rows("set.").take(2)
        assertEquals(1.0f, title(tags[0]).layoutInput.style.color.alpha, 0.005f)
        assertEquals(0.6f, title(tags[1]).layoutInput.style.color.alpha, 0.005f)
    }

    @Test
    fun aSubRowKeepsSettingsSub() {
        start(Page.Eq(Enums.EqOutput.wired), listOf(Page.Settings)) { c ->
            c.eqSet(EqSlots.HEADPHONES, ints = Profile(-64, (0 until 10).map { i ->
                val grid = 4 + 6 * i
                val b = Band(Enums.BandType.peak, grid, (gridHz(grid) * 1000).roundToInt(), 0, 140)
                if (i == 0) b.copy(type = Enums.BandType.lowShelf, gain10 = 55, q100 = 70) else b
            }))
        }
        rule.onNodeWithTag("eq.band.0").performClick()
        settle()
        val n = rule.onAllNodes(hasText("TYPE"), useUnmergedTree = true).fetchSemanticsNodes().first()
        val out = ArrayList<TextLayoutResult>()
        n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(out)
        assertEquals((Tok.type_settingsSub_size * u("eq")).roundToInt().toFloat(), size(out.single()), 0.5f)
    }

    @Test
    fun anInfoRowKeepsSettingsValue() {
        start(Page.Settings)
        assertEquals((Tok.type_settingsValue_size * u("settings")).roundToInt().toFloat(), size(title("set.sampleRate")), 0.5f)
    }
}
