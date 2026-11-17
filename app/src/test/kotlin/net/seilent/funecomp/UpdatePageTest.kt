package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.Control
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.UpdateView
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class UpdatePageTest {
    @get:Rule
    val rule = createComposeRule()

    private val found = UpdateView(installed = "0.3.0", tag = "v0.4.0", size = 19_211_272, newer = true)
    private var view by mutableStateOf(UpdateView(installed = "0.3.0"))
    private val calls = CopyOnWriteArrayList<String>()
    private lateinit var shell: Shell

    private fun start(pages: List<Page> = listOf(Page.Home, Page.Connection, Page.Update), link: LinkView = LinkView(Phase.LIVE, "V1", everLive = true), gated: Boolean = false) {
        val c = SimControl("v1")
        val control = object : Control by c {
            override fun updateCheck() {
                calls += "check"
            }

            override fun updateInstall() {
                calls += "install"
            }

            override fun updateCancel() {
                calls += "cancel"
            }
        }
        shell = Shell(Nav(pages), gated = gated)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) {
                App(c.device, link, control, shell, update = view)
            }
        }
        settle()
    }

    private fun settle() {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
    }

    private fun tap(tag: String) {
        rule.onNodeWithTag(tag).performClick()
        settle()
    }

    private fun accept(i: Int) {
        rule.runOnIdle { shell.sheet.accept(i) }
        settle()
    }

    private fun shows(tag: String, text: String) =
        rule.onNode(hasText(text, ignoreCase = true) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).assertExists()

    private fun none(tag: String) = assertEquals(0, rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size)

    private fun openRow() {
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("rows").performScrollToNode(hasTestTag("conn.update"))
        rule.mainClock.autoAdvance = false
        shows("conn.update", "update app")
        shows("conn.update", "v0.4.0")
        tap("conn.update")
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun beforeAnyLinkTheConnectionPageOpensTheUpdatePage() {
        view = found
        start(listOf(Page.Connection), LinkView(), gated = true)
        openRow()
        assertEquals(listOf(Page.Connection, Page.Update), shell.nav.stack.toList())
        shows("update.release", "v0.4.0, 19.2 mb")
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun withADapTheConnectionPageOpensTheUpdatePage() {
        view = found
        start(listOf(Page.Home, Page.Connection))
        openRow()
        assertEquals(listOf(Page.Home, Page.Connection, Page.Update), shell.nav.stack.toList())
    }

    @Test
    fun theOpenChecksAndTheCheckRowCarriesTheStatus() {
        start()
        assertEquals(listOf("check"), calls.toList())
        view = view.copy(status = "checking")
        settle()
        shows("update.check", "checking")
        none("update.release")
        view = view.copy(status = "offline")
        settle()
        shows("update.check", "offline")
        tap("update.check")
        assertEquals(listOf("check", "check"), calls.toList())
    }

    @Test
    fun aNewerReleaseOpensTheInstallSheetWithTheInstalledVersion() {
        view = found
        start()
        shows("update.release", "v0.4.0, 19.2 mb")
        shows("update.release", "newer")
        tap("update.release")
        assertEquals("v0.4.0", shell.sheet.title)
        assertEquals(listOf("install", "cancel"), shell.sheet.items)
        assertEquals(listOf("19.2 mb", "installed 0.3.0"), shell.sheet.info)
        accept(1)
        assertEquals(listOf("check"), calls.toList())
        tap("update.release")
        accept(0)
        assertEquals(listOf("check", "install"), calls.toList())
    }

    @Test
    fun aDownloadOffersItsCancelAndLaterStatesOfferNoInstall() {
        view = found.copy(downloading = true, download = "42%")
        start()
        shows("update.release", "42%")
        tap("update.release")
        assertEquals(listOf("cancel download"), shell.sheet.items)
        accept(0)
        assertEquals(listOf("check", "cancel"), calls.toList())
        for ((v, value) in listOf(found.copy(verifying = true) to "checking file", found.copy(installing = true) to "installing", found.copy(newer = false) to "up to date")) {
            view = v
            settle()
            shows("update.release", value)
            tap("update.release")
            assertEquals(listOf("cancel"), shell.sheet.items)
            assertEquals(listOf("19.2 mb", "installed 0.3.0"), shell.sheet.info)
            accept(0)
        }
        assertEquals(listOf("check", "cancel"), calls.toList())
    }

    @Test
    fun eachInfoLineShows() {
        start()
        val lines = listOf(
            "rate limited, try later", "no public releases", "no app release", "checksum mismatch", "unknown signing key",
            Apps.DAMAGED, Apps.OLDER, "wrong package", "download failed", "install failed", "install cancelled", Updates.ALLOW,
        )
        for (line in lines) {
            view = found.copy(info = line)
            settle()
            shows("update.info", line)
        }
        view = found
        settle()
        none("update.info")
    }
}
