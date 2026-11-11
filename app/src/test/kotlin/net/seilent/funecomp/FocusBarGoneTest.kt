package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Roles
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.debugPages
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
class FocusBarGoneTest {
    @get:Rule
    val rule = createComposeRule()

    private val c = SimControl("v1")
    private val link = LinkView(Phase.LIVE, "V1", everLive = true)
    private var shell by mutableStateOf<Shell?>(null)
    private var shown = false

    private val album = java.lang.Long.toUnsignedString(Library.synthetic().tracks.first().id)

    private val names = listOf(
        "home", "nowplaying", "queue", "music", "playlists", "settings", "eq", "autoeq", "connection", "art", "artpicked",
        "artblocked", "artcredits", "scrobbling", "firmware", "lyrics", "online", "streaming", "artist:Utada Hikaru",
        "album:$album|First Love|Utada Hikaru",
    )

    private fun show(name: String): Shell {
        if (!shown) {
            shown = true
            rule.mainClock.autoAdvance = false
            rule.setContent {
                val s = shell ?: return@setContent
                key(s) { Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, link, c, s) } }
            }
        }
        val s = Shell(Nav(debugPages(name)!!))
        shell = s
        settle(1500)
        return s
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun bars() = rule.onAllNodesWithTag("bar", useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun noPageDrawsAFocusBar() {
        for (name in names) {
            show(name)
            assertEquals(name, 0, bars())
        }
    }

    @Test
    fun aListMarksItsFocusByTheTitleAlphaOnly() {
        show("music")
        rule.pivotTo("music", "artists") { settle(it) }
        assertEquals(0, bars())
        assertEquals(Roles.listPrimary.alpha, rule.titleAlpha("row.0"), 0.005f)
        assertEquals(Roles.listPrimary.dim, rule.titleAlpha("row.1"), 0.005f)
        assertEquals(Roles.listPrimary.dim, rule.titleAlpha("row.2"), 0.005f)
    }

    @Test
    fun settingsAndAnOpenSheetMarkTheirFocusByTheTitleAlphaOnly() {
        val s = show("settings")
        rule.onNodeWithTag("set.digitalFilter").performClick()
        settle(1000)
        assertTrue(s.sheet.shown)
        assertEquals(0, bars())
        assertEquals(Roles.pageRowTitle.alpha, rule.titleAlpha("set.digitalFilter"), 0.005f)
        assertEquals(Roles.pageRowTitle.dim, rule.titleAlpha("set.dsdOutput"), 0.005f)
        val focus = s.sheet.focus
        val other = if (focus == 0) 1 else 0
        assertEquals(Roles.dialogItem.alpha, rule.titleAlpha("sheet.item.$focus"), 0.005f)
        assertEquals(Roles.dialogItem.dim, rule.titleAlpha("sheet.item.$other"), 0.005f)
    }

    @Test
    fun aHeldListRowOpensASheetWithNoBar() {
        val s = show("music")
        rule.pivotTo("music", "artists") { settle(it) }
        rule.onNodeWithTag("row.1").performTouchInput { longClick() }
        settle(1000)
        assertTrue(s.sheet.shown)
        assertEquals(0, bars())
        assertEquals(Roles.listPrimary.alpha, rule.titleAlpha("row.1"), 0.005f)
        assertEquals(Roles.listPrimary.dim, rule.titleAlpha("row.0"), 0.005f)
    }
}
