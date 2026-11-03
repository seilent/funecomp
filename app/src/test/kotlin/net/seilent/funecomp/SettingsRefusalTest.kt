package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.ONLINE_SCROBBLING
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
class SettingsRefusalTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var shell: Shell

    private fun start(page: Page, vararg faults: String): SimControl {
        shell = Shell(Nav(listOf(Page.Home, page)))
        val c = SimControl("v1", faults = faults.toList())
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE), c, shell) }
        }
        settle(1000)
        return c
    }

    private fun settings(to: String, faults: List<String> = emptyList()): SimControl {
        val c = start(Page.Settings, *faults.toTypedArray())
        rule.walkPivots(c.device.schema.rows, to) { settle(1000) }
        return c
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun tap(tag: String) {
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("rows").performScrollToNode(hasTestTag(tag))
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(tag).performClick()
        settle(600)
    }

    private fun pick(i: Int) {
        rule.onNodeWithTag("sheet.item.$i").performClick()
        settle(600)
    }

    private fun shows(tag: String, text: String) {
        rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag))).assertExists()
    }

    private fun toast() = shell.toast.artist to shell.toast.title

    @Test
    fun aBootLogoPictureIsListedAndItsPickIsRefusedWithTheRowLabel() {
        val c = settings("display")
        tap("set.bootLogo")
        assertEquals("boot logo", shell.sheet.title)
        assertEquals(listOf("FUNE", "STOCK", "PICTURE"), shell.sheet.items)
        pick(2)
        assertFalse(shell.sheet.shown)
        assertEquals(listOf(Err.refused), c.failed)
        assertEquals(1, shell.toast.shows)
        assertEquals("boot logo" to "refused on the dap", toast())
        assertEquals("fune", c.device.settings.values["bootLogo"])
        shows("set.bootLogo", "FUNE")
    }

    @Test
    fun aBootLogoPickTheDapTakesShowsNoToast() {
        val c = settings("display")
        tap("set.bootLogo")
        pick(1)
        assertEquals("stock", c.device.settings.values["bootLogo"])
        tap("set.bootLogo")
        pick(0)
        assertEquals("fune", c.device.settings.values["bootLogo"])
        assertEquals(2, c.sent.count { it == "SET" })
        assertTrue(c.failed.isEmpty())
        assertEquals(0, shell.toast.shows)
    }

    @Test
    fun aBadValueSetShowsNoToast() {
        val c = settings("display", faults = listOf("fail=SET:${Err.badValue}:1"))
        tap("set.bootLogo")
        pick(1)
        assertEquals(listOf(Err.badValue), c.failed)
        assertEquals("fune", c.device.settings.values["bootLogo"])
        assertEquals(0, shell.toast.shows)
    }

    @Test
    fun aCodecPickForARoleTheDapLeftIsRefusedWithTheRowLabel() {
        val c = settings("bluetooth")
        tap("set.btCodec")
        assertEquals("codec", shell.sheet.title)
        c.sim.btRole = Enums.BtRole.headphones
        pick(2)
        assertEquals(listOf("DRAFT"), c.sent.filter { it == "SET" || it == "DRAFT" })
        assertEquals(listOf(Err.refused), c.failed)
        assertEquals("codec" to "refused on the dap", toast())
        assertEquals("auto", c.device.settings.values["btCodec"])
    }

    @Test
    fun aUsbModePickTheDapRefusesIsToastedWithTheRowLabel() {
        val c = settings("connect", faults = listOf("fail=DRAFT:${Err.refused}:1"))
        tap("set.usbMode")
        assertEquals("usb mode", shell.sheet.title)
        pick(2)
        assertEquals(listOf("DRAFT"), c.sent.filter { it == "SET" || it == "DRAFT" })
        assertEquals(listOf(Err.refused), c.failed)
        assertEquals("usb mode" to "refused on the dap", toast())
        assertEquals("prompt", c.device.settings.values["usbMode"])
    }

    @Test
    fun aRefusedScrobbleSetIsToastedWithTheRowLabel() {
        val c = start(Page.Online(ONLINE_SCROBBLING), "fail=SET:${Err.refused}:1")
        rule.onNodeWithTag("scrobble.dap").performClick()
        settle(600)
        assertEquals("scrobble", shell.sheet.title)
        pick(1)
        assertEquals(listOf(Err.refused), c.failed)
        assertEquals("scrobble" to "refused on the dap", toast())
        assertEquals("phone", c.device.settings.values["scrobble"])
        rule.onNodeWithTag("scrobble.dap").performClick()
        settle(600)
        pick(1)
        assertEquals("card", c.device.settings.values["scrobble"])
        assertEquals(1, shell.toast.shows)
    }
}
