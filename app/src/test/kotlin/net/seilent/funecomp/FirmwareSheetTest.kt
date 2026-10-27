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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.FirmwareView
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.UNSIGNED_FIRMWARE
import net.seilent.funecomp.ui.UNSIGNED_RELEASE
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
class FirmwareSheetTest {
    @get:Rule
    val rule = createComposeRule()

    private val found = FirmwareView(tag = "v0.4.0", size = 28_043_264, newer = true)
    private var view by mutableStateOf(FirmwareView())
    private lateinit var c: SimControl
    private lateinit var shell: Shell

    private fun start(v: FirmwareView, minor: Int = Proto.minor, signed: Boolean = true, mismatch: Boolean = false) {
        view = v
        c = SimControl("v1", minor = minor)
        c.pickSigned = signed
        c.pickMismatch = mismatch
        shell = Shell(Nav(listOf(Page.Home, Page.Settings, Page.Firmware)))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, "V1", everLive = true), c, shell, firmware = view) }
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

    private fun value(text: String) = rule.onAllNodes(hasText(text, ignoreCase = true) and hasAnyAncestor(hasTestTag("fw.release"))).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aReadyReleaseWithNoStatementOnAnUpdSigDeviceHasNoSend() {
        start(found.copy(ready = true))
        tap("fw.release")
        assertEquals(listOf("cancel", "delete"), shell.sheet.items)
        assertTrue(shell.sheet.info.containsAll(listOf(UNSIGNED_RELEASE, "update from the card")))
    }

    @Test
    fun aV03DeviceSendsAnUnsignedRelease() {
        start(found.copy(ready = true), minor = 14)
        tap("fw.release")
        assertEquals(listOf("send", "cancel", "delete"), shell.sheet.items)
        accept(0)
        assertEquals("send release", c.firmware.last())
    }

    @Test
    fun aStatementNotFetchedShowsThePauseAndADownloadThenSend() {
        start(found.copy(ready = true, sigListed = true, download = "paused 100%"))
        assertTrue(value("paused 100%"))
        tap("fw.release")
        assertEquals(listOf("download", "cancel", "delete"), shell.sheet.items)
        accept(0)
        assertEquals("download", c.firmware.last())
        view = view.copy(signed = true, download = "")
        settle()
        assertTrue(value("downloaded"))
        tap("fw.release")
        assertEquals(listOf("send", "cancel", "delete"), shell.sheet.items)
    }

    @Test
    fun theUnsignedReleaseInfoHasTheCardLine() {
        start(FirmwareView(info = UNSIGNED_RELEASE))
        assertTrue(rule.onAllNodes(hasTestTag("fw.card")).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodes(hasText("update from the card", ignoreCase = true)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun thePageOpenAsksOnceAndARecomposeDoesNot() {
        start(found.copy(ready = true, sigListed = true, info = "bad signature file"))
        assertEquals(1, c.firmware.count { it == "opened" })
        view = view.copy(info = "")
        settle()
        view = view.copy(info = "bad signature file")
        settle()
        assertEquals(1, c.firmware.count { it == "opened" })
    }

    @Test
    fun pickOnAnUpdSigDeviceShowsTheHintFirst() {
        start(FirmwareView())
        tap("fw.pick")
        assertEquals("pick file", shell.sheet.title)
        assertEquals(listOf("pick the image and its signature"), shell.sheet.info)
        assertEquals(listOf("pick", "cancel"), shell.sheet.items)
        accept(0)
        assertEquals("funeos-v0.4.0-v1.upt", shell.sheet.title)
        assertEquals(listOf("send", "cancel"), shell.sheet.items)
        accept(0)
        assertEquals("send picked", c.firmware.last())
    }

    @Test
    fun anUnsignedPickOnAnUpdSigDeviceHasNoSend() {
        start(FirmwareView(), signed = false)
        tap("fw.pick")
        accept(0)
        assertEquals(listOf("cancel"), shell.sheet.items)
        assertTrue(shell.sheet.info.containsAll(listOf(UNSIGNED_FIRMWARE, "update from the card")))
    }

    @Test
    fun aMismatchedPickHasNoSendOnAnyDevice() {
        start(FirmwareView(), minor = 14, signed = false, mismatch = true)
        tap("fw.pick")
        assertEquals(listOf("cancel"), shell.sheet.items)
        assertTrue(shell.sheet.info.contains(UNSIGNED_FIRMWARE))
        assertFalse(shell.sheet.info.contains("update from the card"))
    }

    @Test
    fun aV03DevicePicksStraightAway() {
        start(FirmwareView(), minor = 14, signed = false)
        tap("fw.pick")
        assertEquals("funeos-v0.4.0-v1.upt", shell.sheet.title)
        assertEquals(listOf("send", "cancel"), shell.sheet.items)
        assertFalse(shell.sheet.info.contains(UNSIGNED_FIRMWARE))
    }
}
