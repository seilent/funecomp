package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.debugPages
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class MusicPivotsTest {
    @get:Rule
    val rule = createComposeRule()

    private val order = listOf("albums", "artists", "playlists", "songs", "genres", "folders")

    private fun start(board: String, pages: List<Page>, minor: Int = Proto.minor): Shell {
        val c = SimControl(board, minor = minor)
        val shell = Shell(Nav(pages))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE), c, shell) }
        }
        settle(1500)
        return shell
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun strip() = rule.pivotNodes().map { it.config[SemanticsProperties.TestTag].removePrefix("pivot.") }

    private fun theStripIsTheDeviceOrderOpeningOnAlbums(board: String) {
        start(board, debugPages("music")!!)
        assertEquals(order, strip())
        assertEquals("albums", rule.activePivot("music"))
    }

    private fun theStripHasNoGenresWithoutTheBit(board: String) {
        start(board, listOf(Page.Home, Page.Music), minor = 14)
        assertEquals(order - "genres", strip())
        assertEquals("albums", rule.activePivot("music"))
    }

    private fun aKeptPivotHoldsThroughAPushAndAReopenOpensOnAlbums(board: String) {
        val shell = start(board, listOf(Page.Home))
        rule.onNodeWithTag("home.music").performClick()
        settle(1500)
        assertEquals("albums", rule.activePivot("music"))
        rule.pivotTo("music", "artists") { settle(it) }
        shell.nav.push(Page.Album("1", "x", ""))
        settle(1500)
        shell.nav.pop()
        settle(1500)
        assertEquals("artists", rule.activePivot("music"))
        shell.nav.pop()
        settle(1500)
        rule.onNodeWithTag("home.music").performClick()
        settle(1500)
        assertEquals("albums", rule.activePivot("music"))
    }

    @Test
    fun theV1StripIsTheDeviceOrderOpeningOnAlbums() = theStripIsTheDeviceOrderOpeningOnAlbums("v1")

    @Test
    fun theAp80StripIsTheDeviceOrderOpeningOnAlbums() = theStripIsTheDeviceOrderOpeningOnAlbums("ap80")

    @Test
    fun theV1StripHasNoGenresWithoutTheBit() = theStripHasNoGenresWithoutTheBit("v1")

    @Test
    fun theAp80StripHasNoGenresWithoutTheBit() = theStripHasNoGenresWithoutTheBit("ap80")

    @Test
    fun aV1KeptPivotHoldsThroughAPushAndAReopenOpensOnAlbums() = aKeptPivotHoldsThroughAPushAndAReopenOpensOnAlbums("v1")

    @Test
    fun anAp80KeptPivotHoldsThroughAPushAndAReopenOpensOnAlbums() = aKeptPivotHoldsThroughAPushAndAReopenOpensOnAlbums("ap80")
}
