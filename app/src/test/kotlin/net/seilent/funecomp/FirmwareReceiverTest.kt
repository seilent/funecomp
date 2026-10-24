package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.FirmwareView
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
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
class FirmwareReceiverTest {
    @get:Rule
    val rule = createComposeRule()

    private val sending = FirmwareView(tag = "v0.4.0", size = 28_043_264, newer = true, ready = true, send = "firmware 42%")

    private fun start(view: FirmwareView = sending, output: Int = Enums.Output.phoneReceiver, source: Int = Enums.Source.phone, card: Int = Enums.Card.ready) {
        val c = SimControl("v1")
        c.device = c.device.withState(c.device.playback.state.copy(source = source, output = output, card = card), 0)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, "V1", everLive = true), c, Shell(Nav(listOf(Page.Home, Page.Settings, Page.Firmware))), firmware = view) }
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
    }

    private fun info(text: String) = rule.onAllNodes(hasText(text, ignoreCase = true) and hasAnyAncestor(hasTestTag("fw.info"))).fetchSemanticsNodes().isNotEmpty()

    private fun anyInfo() = rule.onAllNodes(hasTestTag("fw.info")).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aSendOnTheReceiverShowsBluetoothReceiver() {
        start()
        assertTrue(info("bluetooth receiver"))
    }

    @Test
    fun aCardRunShowsNoReceiverLine() {
        start(card = Enums.Card.busy)
        assertFalse(info("bluetooth receiver"))
    }

    @Test
    fun aPausedSendShowsNoReceiverLine() {
        start(sending.copy(send = "paused 42%"))
        assertFalse(anyInfo())
    }

    @Test
    fun wiredShowsNoReceiverLine() {
        start(output = Enums.Output.wired, source = Enums.Source.local)
        assertFalse(anyInfo())
    }

    @Test
    fun anInfoTextWinsOverTheReceiverLine() {
        start(sending.copy(info = "checksum mismatch"))
        assertTrue(info("checksum mismatch"))
        assertFalse(info("bluetooth receiver"))
    }
}
