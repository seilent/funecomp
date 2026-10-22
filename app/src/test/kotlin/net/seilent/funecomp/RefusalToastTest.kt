package net.seilent.funecomp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.Control
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class RefusalToastTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private class Sink(val sim: SimControl) : Control by sim {
        var show: ((String, String) -> Unit)? = null

        override fun refusals(show: ((String, String) -> Unit)?, owner: ((String, String) -> Unit)?) {
            if (owner == null || this.show === owner) this.show = show
        }
    }

    private fun settle(ms: Long = 1000) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    @Test
    fun theAppShowsARefusalAsAToastOnHomeAndMusicAndLetsGoOfTheSink() {
        val sim = SimControl("v1")
        val c = Sink(sim)
        val shell = Shell(Nav(listOf(Page.Home)))
        var on by mutableStateOf(true)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            if (on) Box(Modifier.requiredSize(411.dp, 960.dp)) { App(sim.device, LinkView(Phase.LIVE, "V1", everLive = true), c, shell) }
        }
        settle()
        val show = c.show
        assertNotNull(show)
        rule.runOnUiThread { show!!(Remote.RECEIVER, "") }
        settle(300)
        assertEquals(1, shell.toast.shows)
        assertEquals(Remote.RECEIVER, shell.toast.artist)
        assertEquals("", shell.toast.title)
        rule.onNodeWithTag("toast").assertExists()
        rule.runOnUiThread { shell.nav.push(Page.Music) }
        settle()
        rule.runOnUiThread { c.show!!(Remote.RECEIVER, "") }
        settle(300)
        assertEquals(2, shell.toast.shows)
        rule.onNodeWithTag("toast").assertExists()
        on = false
        settle()
        assertNull(c.show)
    }

    @Test
    fun theSinkIsLetGoWhileTheActivityIsStoppedAndSetAgainWhenItStarts() {
        val sim = SimControl("v1")
        val c = Sink(sim)
        val shell = Shell(Nav(listOf(Page.Home)))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(sim.device, LinkView(Phase.LIVE, "V1", everLive = true), c, shell) }
        }
        settle()
        assertNotNull(c.show)
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertNull(c.show)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        settle()
        val show = c.show
        assertNotNull(show)
        rule.runOnUiThread { show!!(Remote.RECEIVER, "") }
        settle(300)
        assertEquals(1, shell.toast.shows)
    }
}
