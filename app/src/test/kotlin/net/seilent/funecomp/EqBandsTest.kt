package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.Band
import net.seilent.funecomp.proto.Profile
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tags
import net.seilent.funecomp.state.EqSlots
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
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
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class EqBandsTest {
    @get:Rule
    val rule = createComposeRule()

    private fun band(i: Int) = Band(Enums.BandType.lowShelf, -1, 100_000 + 1000 * i, -10 * i, 70)

    private fun start(n: Int): SimControl {
        val c = SimControl("v1")
        c.eqSet(EqSlots.HEADPHONES, ints = Profile(-20, (0 until n).map { band(it) }))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE), c, Shell(Nav(listOf(Page.Home, Page.Settings, Page.Eq(Enums.EqOutput.wired))))) }
        }
        settle(1000)
        c.eqSets.clear()
        return c
    }

    private fun settle(ms: Long = 500) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun scrollTo(tag: String) {
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("rows").performScrollToNode(hasTestTag(tag))
        rule.mainClock.autoAdvance = false
        settle()
    }

    private fun tap(tag: String) {
        scrollTo(tag)
        rule.onNodeWithTag(tag).performClick()
        settle()
    }

    private fun exists(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun sent(c: SimControl) = Profile.from(c.eqSets.last().node(Tags.EqSet.ints)!!).bands

    @Test
    fun aOneBandEditSendsOneBand() {
        val c = start(1)
        assertTrue(rule.onAllNodes(hasText("1") and hasAnyAncestor(hasTestTag("eq.band.0"))).fetchSemanticsNodes().isNotEmpty())
        assertFalse(exists("eq.band.1"))
        tap("eq.band.0")
        tap("eq.band.0.gain")
        rule.onNodeWithTag("entry").performTextClearance()
        rule.onNodeWithTag("entry").performTextInput("2")
        rule.onNodeWithTag("entry").performImeAction()
        settle()
        assertEquals(listOf(band(0).copy(gain10 = 20)), sent(c))
    }

    @Test
    fun addBandSendsOneMoreAndOpensTheNewBand() {
        val c = start(1)
        assertFalse(exists("eq.remove"))
        tap("eq.add")
        assertEquals(listOf(band(0), Band(Enums.BandType.peak, 34, 1_000_000, 0, 140)), sent(c))
        scrollTo("eq.band.1.type")
        assertTrue(exists("eq.band.1.type"))
        scrollTo("eq.remove")
        assertTrue(exists("eq.remove"))
    }

    @Test
    fun removeBandDropsTheLastAndLeavesAtOneBand() {
        val c = start(2)
        scrollTo("eq.remove")
        assertTrue(rule.onAllNodes(hasText("2") and hasAnyAncestor(hasTestTag("eq.remove"))).fetchSemanticsNodes().isNotEmpty())
        tap("eq.remove")
        assertEquals(listOf(band(0)), sent(c))
        assertFalse(exists("eq.remove"))
        assertTrue(exists("eq.add"))
    }

    @Test
    fun addBandLeavesAtTenBands() {
        start(9)
        scrollTo("eq.add")
        assertTrue(exists("eq.add"))
        rule.onNodeWithTag("eq.add").performClick()
        settle()
        scrollTo("eq.remove")
        assertFalse(exists("eq.add"))
        assertTrue(exists("eq.band.9"))
    }
}
