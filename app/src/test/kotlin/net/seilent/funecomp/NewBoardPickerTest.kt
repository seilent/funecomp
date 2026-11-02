package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.Bonded
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class NewBoardPickerTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var c: SimControl
    private val shell = Shell(Nav(listOf(Page.Home, Page.Connection)))

    private fun start(bonds: List<Bonded>? = null) {
        c = SimControl("ap80")
        bonds?.let { c.bonds = it }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), c, shell) }
        }
        settle(1000)
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    @Test
    fun pairADeviceIsTheLastDeviceItem() {
        start()
        rule.onNodeWithTag("conn.device").performClick()
        settle(600)
        assertEquals(listOf("AP80", "V1", "pair a device"), shell.sheet.items)
        shell.sheet.accept(2)
        assertEquals(1, c.pairDevices)
    }

    @Test
    fun withNoBoardBondedTheDeviceTapOpensThePicker() {
        start(listOf(Bonded("WH-1000XM4", "00:00:00:00:00:02")))
        rule.onNodeWithTag("conn.device").performClick()
        settle(600)
        assertFalse(shell.sheet.shown)
        assertEquals(1, c.pairDevices)
    }

    @Test
    fun openingConnectionAsksForARound() {
        start()
        assertEquals(1, c.connectionShows)
    }
}
