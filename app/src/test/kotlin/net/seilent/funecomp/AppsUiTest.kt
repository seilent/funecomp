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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.AppsView
import net.seilent.funecomp.ui.Control
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
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class AppsUiTest {
    @get:Rule
    val rule = createComposeRule()

    private var view by mutableStateOf(AppsView())
    private val calls = CopyOnWriteArrayList<String>()
    private lateinit var shell: Shell

    private fun start() {
        val c = SimControl("v1")
        val control = object : Control by c {
            override fun appFile() {
                calls += "file"
            }

            override fun appSend() {
                calls += "send"
            }

            override fun appStop() {
                calls += "stop"
            }
        }
        shell = Shell(Nav(listOf(Page.Home, Page.Settings, Page.Apps)))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) {
                App(c.device, LinkView(Phase.LIVE, "V1", everLive = true), control, shell, apps = view)
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

    private fun shows(tag: String, text: String) =
        rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).assertExists()

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theAboutInstallAppItemOpensTheAppsPage() {
        val c = SimControl("ap80")
        shell = Shell(Nav(listOf(Page.Home, Page.Settings)))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) {
                App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), c, shell, apps = view)
            }
        }
        settle()
        rule.walkPivots(c.device.schema.rows, "system") { settle() }
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("rows").performScrollToNode(hasTestTag("set.about"))
        rule.mainClock.autoAdvance = false
        tap("set.about")
        assertEquals("about", shell.sheet.title)
        val install = "sheet.item.${shell.sheet.items.indexOf("install app")}"
        rule.onNode(hasText("INSTALL APP") and hasAnyAncestor(hasTestTag(install)), useUnmergedTree = true).assertExists()
        tap(install)
        assertFalse(shell.sheet.shown)
        assertEquals(listOf(Page.Home, Page.Settings, Page.Apps), shell.nav.stack.toList())
        shows("apps.pick", "PICK FILE")
    }

    @Test
    fun thePickRowOpensThePicker() {
        start()
        shows("apps.pick", "PICK FILE")
        tap("apps.pick")
        assertEquals(listOf("file"), calls.toList())
    }

    @Test
    fun aPickedFileShowsItAndTheSendRowSends() {
        start()
        view = AppsView(picked = "tidal tidal-3.fnapp")
        settle()
        shows("apps.pick", "TIDAL TIDAL-3.FNAPP")
        shows("apps.send", "SEND")
        tap("apps.send")
        assertEquals(listOf("send"), calls.toList())
    }

    @Test
    fun theSendRowShowsThePercentAndCancels() {
        start()
        view = AppsView(picked = "tidal tidal-3.fnapp", send = "app 40%")
        settle()
        shows("apps.send", "40%")
        tap("apps.send")
        assertEquals("app", shell.sheet.title)
        assertEquals(listOf("CANCEL"), shell.sheet.items.map { it.uppercase() })
        tap("sheet.item.0")
        assertEquals(listOf("stop"), calls.toList())
    }

    @Test
    fun eachLineShows() {
        start()
        val lines = listOf(
            Apps.CHECKING_FILE, Apps.CONFIRM, Apps.CHECKING, Apps.INSTALLED, Apps.DECLINED, Apps.EXPIRED, Apps.PENDING,
            Apps.NO_ANSWER, Apps.SIGNATURE, Apps.OLDER, Apps.NO_ROOM, Apps.BOARD, Apps.DAMAGED, Apps.NEWER, Apps.SEND_FAILED,
            Apps.BLUETOOTH, Apps.BUSY_STOP, Apps.OTHER_DAP, Apps.STREAMING, Apps.NO_INSTALLER, Apps.FIRMWARE,
        )
        for (line in lines) {
            view = AppsView(picked = "tidal tidal-3.fnapp", info = line)
            settle()
            shows("apps.info", line.uppercase())
        }
        assertTrue(calls.isEmpty())
    }
}
