package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
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
class CardSnapTest {
    @get:Rule
    val rule = createComposeRule()

    private var t = 1000L

    private fun settle(c: SimControl, ms: Long) {
        val end = t + ms
        while (t < end) {
            t += 100
            c.tick()
            rule.mainClock.advanceTimeBy(100)
            rule.waitForIdle()
        }
    }

    @Test
    fun aSnapshotThatLandsAfterTheReadyStateIsReadAgainWithNoTap() {
        val c = SimControl("v1", now = { t }, faults = listOf("card-run=1000+500@0", "snap-late=3000"))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE), c, Shell(Nav(listOf(Page.Home, Page.Music)))) }
        }
        settle(c, 2000)
        assertEquals(Enums.Card.ready, c.device.playback.state.card)
        val snap = c.device.playback.state.snapRev!!
        val gets = c.sent.count { it == "LIB_GET" }
        assertTrue(gets > 0)
        settle(c, 4000)
        assertEquals(snap + 1, c.device.playback.state.snapRev)
        assertTrue("${c.sent}", c.sent.count { it == "LIB_GET" } > gets)
        assertEquals(snap + 1, c.device.lib.snapRev(Enums.View.artists))
    }
}
