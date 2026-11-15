package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class ListLinesShots {
    @get:Rule
    val rule = createComposeRule()

    private var size by mutableStateOf<Size?>(null)
    private var gen by mutableIntStateOf(0)
    private var shell by mutableStateOf(Shell())
    private lateinit var c: SimControl

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun load(sz: Size, pages: List<Page>, lib: Library = Library.synthetic(), faults: List<String> = emptyList(), prep: (SimControl) -> Unit = {}) {
        c = SimControl("v1", lib, faults = faults, stateNow = SHOT_CLOCK).also {
            shotClock(it)
            prep(it)
        }
        shell = Shell(Nav(pages))
        size = sz
        gen++
        settle(1000)
    }

    private fun shoot(dir: File, name: String) {
        rule.onNodeWithTag("frame").captureRoboImage(File(dir, "$name-${size!!.name}.png").path)
        System.gc()
    }

    private fun sheet(tag: String, item: Int) {
        rule.onNodeWithTag(tag).performTouchInput { longClick() }
        settle(600)
        rule.runOnIdle { shell.sheet.accept(item) }
        settle(600)
    }

    private fun shots(set: String, body: (File) -> Unit) {
        assumeTrue(System.getProperty("shots.run") == "true")
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val sz = size ?: return@setContent
            key(gen) {
                Box(Modifier.requiredSize(sz.w.dp, sz.h.dp).testTag("frame")) {
                    App(c.device, LIVE, c, shell, insets = sz.bars, ime = WindowInsets(0.dp))
                }
            }
        }
        body(File(System.getProperty("shots.dir") ?: "build/shots", "lines/$set").apply { mkdirs() })
    }

    private fun playing(c: SimControl) = c.transport(Enums.TransportCmd.next)

    @Test
    fun toast() = shots("toast") { dir ->
        for (sz in SHAPES) {
            load(sz, HOME, prep = ::playing)
            shell.toast.show(DeviceState.USB_DAC, "")
            settle(600)
            shoot(dir, "usbdac-home")
            load(sz, HOME + Page.NowPlaying, prep = ::playing)
            shell.toast.show(DeviceState.USB_DAC, "")
            settle(600)
            shoot(dir, "usbdac-nowplaying")
            load(sz, HOME + Page.Music, prep = ::playing)
            rule.pivotTo("music", "songs") { settle(it) }
            sheet("row.0", 1)
            shoot(dir, "added-song")
            load(sz, HOME + Page.Music, prep = ::playing)
            sheet("row.0", 1)
            shoot(dir, "added-songs")
            load(sz, HOME + Page.Settings, faults = listOf("draftpending"))
            rule.walkPivots(c.device.schema.rows, "connect") { settle(1000) }
            rule.onNodeWithTag("set.usbMode").performClick()
            settle(600)
            rule.onNodeWithTag("sheet.item.2").performClick()
            settle(600)
            shoot(dir, "pending")
        }
    }

    @Test
    fun queue() = shots("queue") { dir ->
        for (sz in SHAPES) {
            load(sz, HOME + Page.NowPlaying + Page.Queue, QUEUE_LIB)
            shoot(dir, "rows")
        }
    }

    @Test
    fun lines() = shots("lines") { dir ->
        val empty = Library(emptyList())
        for (sz in SHAPES) {
            for ((name, lib, faults) in listOf(Triple("out", Library.synthetic(), OUT), Triple("error", Library.synthetic(), listOf("card-seq=error")), Triple("empty", empty, emptyList()))) {
                load(sz, HOME + Page.Music, lib, faults)
                for (p in PIVOTS) {
                    rule.pivotTo("music", p) { settle(it) }
                    shoot(dir, "music-$p-$name")
                }
            }
            load(sz, HOME + Page.Playlists, faults = OUT)
            shoot(dir, "playlists-out")
            load(sz, HOME + Page.NowPlaying + Page.Queue, faults = OUT)
            shoot(dir, "queue-out")
            load(sz, HOME + Page.NowPlaying + Page.Queue, empty)
            shoot(dir, "queue-empty")
            load(sz, HOME + Page.Music, faults = OUT)
            rule.onNodeWithTag("pivot.artists").performClick()
            for ((at, step) in listOf(50L to 50L, 150L to 100L, 400L to 250L)) {
                rule.mainClock.advanceTimeBy(step)
                rule.waitForIdle()
                shoot(dir, "switch-out-$at")
            }
        }
    }

    private fun phoneAppOn(s: SimControl) {
        val d = s.device
        s.device = d.copy(settings = d.settings.copy(text = d.settings.text + "\ncompanion=on\n"))
    }

    private fun scrollTo(tag: String) {
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("rows").performScrollToNode(hasTestTag(tag))
        rule.mainClock.autoAdvance = false
        settle(600)
    }

    @Test
    fun readonly() = shots("readonly") { dir ->
        for (sz in SHAPES) {
            load(sz, HOME + Page.Settings, prep = ::phoneAppOn)
            rule.walkPivots(c.device.schema.rows, "display") { settle(1000) }
            scrollTo("set.size")
            shoot(dir, "display-size")
            rule.walkPivots(c.device.schema.rows, "system", "display") { settle(1000) }
            scrollTo("set.companion")
            shoot(dir, "system-phoneapp")
        }
    }

    private companion object {
        val SHAPES = listOf("411x960", "sn411x438", "wm361x343", "flip960x411").map { n -> SIZES.single { it.name == n } }
        val HOME = listOf(Page.Home)
        val LIVE = LinkView(Phase.LIVE, "V1", everLive = true)
        val OUT = listOf("card-seq=out")
        val PIVOTS = listOf("albums", "artists", "playlists", "songs", "genres", "folders")
    }
}
