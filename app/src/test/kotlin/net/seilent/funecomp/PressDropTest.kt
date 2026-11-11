package net.seilent.funecomp

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.LocalPressSlots
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.PressSlots
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
class PressDropTest {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @get:Rule
    val rule = createComposeRule()

    private val owner = Owner()
    private val slots = PressSlots()
    private lateinit var c: SimControl
    private lateinit var shell: Shell

    private fun start(prep: (SimControl) -> Unit = {}) {
        owner.registry.currentState = Lifecycle.State.RESUMED
        c = SimControl("ap80")
        prep(c)
        shell = Shell(Nav(listOf(Page.Home, Page.NowPlaying)))
        rule.mainClock.autoAdvance = false
        rule.setUptimeContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalPressSlots provides slots) {
                Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), c, shell) }
            }
        }
        settle(1000)
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun look() = slots.look(rule.mainClock.currentTime)

    private fun holdPlay() {
        rule.onNodeWithTag("np.toggle").performTouchInput { down(center) }
        settle(Tok.motion_pressGlow.toLong() + FRAME)
        assertEquals("held", 1f, look(), 0f)
    }

    @Test
    fun theOverlayShowingDropsAHeldPress() {
        start()
        holdPlay()
        shell.overlay.show()
        settle(FRAME * 2)
        assertEquals(0f, look(), 0f)
        rule.onNodeWithTag("np.toggle").performTouchInput { up() }
        settle(3000)
    }

    @Test
    fun aSheetRisingDropsAHeldPress() {
        start()
        holdPlay()
        shell.sheet.open("confirm", listOf("apply", "cancel")) {}
        settle(FRAME * 2)
        assertEquals(0f, look(), 0f)
        rule.onNodeWithTag("np.toggle").performTouchInput { up() }
        settle(600)
    }

    @Test
    fun aSourceChangeDropsAHeldPress() {
        start()
        holdPlay()
        c.device = c.device.withState(c.device.playback.state.copy(source = Enums.Source.phone), SystemClock.elapsedRealtime())
        settle(FRAME * 2)
        assertEquals(0f, look(), 0f)
        rule.onRoot().performTouchInput { up() }
        settle(1000)
    }

    @Test
    fun aLyricsModeChangeFromAnotherFingerDropsAHeldPress() {
        start(lyricsOn())
        val play = rule.onNodeWithTag("np.toggle").fetchSemanticsNode().boundsInRoot.center
        val art = rule.onNodeWithTag("np.art").fetchSemanticsNode().boundsInRoot.center
        rule.onRoot().performTouchInput { down(0, play) }
        settle(Tok.motion_pressGlow.toLong() + FRAME)
        assertEquals("held", 1f, look(), 0f)
        rule.onRoot().performTouchInput { down(1, art) }
        settle(FRAME)
        rule.onRoot().performTouchInput { up(1) }
        settle(FRAME * 2)
        assertTrue("lyrics mode on", c.lyricsMode())
        assertEquals(0f, look(), 0f)
        rule.onRoot().performTouchInput { up(0) }
        settle(1000)
    }

    @Test
    fun onStopResetsAHeldPress() {
        start()
        holdPlay()
        owner.registry.currentState = Lifecycle.State.CREATED
        settle(FRAME * 2)
        assertEquals(0f, look(), 0f)
        owner.registry.currentState = Lifecycle.State.RESUMED
        rule.onRoot().performTouchInput { cancel() }
        settle(600)
    }

    private companion object {
        const val FRAME = 16L
    }
}
