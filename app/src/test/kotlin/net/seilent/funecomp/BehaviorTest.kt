package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.state.LibKey
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
class BehaviorTest {
    @get:Rule
    val rule = createComposeRule()

    private fun start(pages: List<Page>): Pair<SimControl, Shell> {
        val c = SimControl("ap80")
        val shell = Shell(Nav(pages))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE), c, shell) }
        }
        settle(1000)
        return c to shell
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    @Test
    fun backDuringPushRestoresThePage() {
        val (_, shell) = start(listOf(Page.Home))
        rule.onNodeWithTag("home.music").performClick()
        settle(100)
        shell.nav.pop()
        settle(1000)
        rule.onNodeWithTag("home").assertIsDisplayed()
        rule.onNodeWithTag("home.music").performClick()
        settle(1500)
        rule.onNodeWithTag("hub.music").assertIsDisplayed()
    }

    @Test
    fun tapBelowTheRowsConfirmsADraft() {
        val (c, _) = start(listOf(Page.Home, Page.Settings))
        rule.onNodeWithTag("set.btCodec").performClick()
        settle(300)
        rule.onNodeWithTag("set.btCodec").performTouchInput { click(centerRight) }
        settle(300)
        assertTrue(c.sent.none { it == "DRAFT" })
        rule.onRoot().performTouchInput { click(bottomCenter.copy(y = bottom - 10f)) }
        settle(300)
        assertEquals(1, c.sent.count { it == "DRAFT" })
    }

    @Test
    fun popKeepsThePageBelow() {
        val (_, shell) = start(listOf(Page.Home, Page.Music))
        rule.onNodeWithTag("pivot.albums").performClick()
        settle(1200)
        shell.nav.push(Page.Album("1", "x", ""))
        settle(1200)
        shell.nav.pop()
        settle(1200)
        assertTrue(rule.onAllNodesWithText("First Love").fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("Utada Hikaru").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun favoriteToggleDropsLibPagesAndReloadsTheOpenList() {
        val (c, _) = start(listOf(Page.Home, Page.Playlists))
        val fav = LibKey(Enums.View.favorites, "")
        assertEquals(0L, c.device.lib.pages.getValue(fav).total)
        c.libGet(Enums.View.songs, "", 0)
        val song = c.device.lib.pages.getValue(LibKey(Enums.View.songs, "")).rows.getValue(0).first!!
        val rev = c.device.lib.rev
        val gets = c.sent.count { it == "LIB_GET" }
        c.action(Enums.ActionCmd.favorite, song)
        assertNotEquals(rev, c.device.lib.rev)
        assertNull(c.device.lib.pages[LibKey(Enums.View.songs, "")])
        settle(500)
        assertTrue(c.sent.count { it == "LIB_GET" } > gets)
        assertEquals(1L, c.device.lib.pages.getValue(fav).total)
        assertEquals(song, c.device.lib.pages.getValue(fav).rows.getValue(0).first)
        rule.onNode(hasText("1 song", ignoreCase = true) and hasAnyAncestor(hasTestTag("row.favorites"))).assertExists()
        val on = c.device.lib.rev
        c.action(Enums.ActionCmd.favorite, song)
        assertNotEquals(on, c.device.lib.rev)
        settle(500)
        assertEquals(0L, c.device.lib.pages.getValue(fav).total)
        rule.onNode(hasText("0 songs", ignoreCase = true) and hasAnyAncestor(hasTestTag("row.favorites"))).assertExists()
        assertEquals(emptyList<Int>(), c.failed)
    }
}
