package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Shell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class ShotClockTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun aPlayingShotReadsItsSeekPointWhateverTheClockReads() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(7))
        val shot = SHOTS.single { it.name == "nowplaying-playing" }
        val c = shotControl(shot)
        assertNotEquals(0L, c.device.playback.receivedAt)
        val shell = Shell(Nav(shot.pages))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, shot.link, c, shell) }
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        val elapsed = rule.onNodeWithTag("np.elapsed").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
        assertEquals("1:01", elapsed)
        val stamped = c.device.playback.receivedAt
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        c.transport(Enums.TransportCmd.seek, seekMs = 61_000)
        assertNotEquals(stamped, c.device.playback.receivedAt)
        assertNotEquals(0L, c.device.playback.receivedAt)
    }
}
