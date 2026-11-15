package net.seilent.funecomp

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import net.seilent.funecomp.art.Thumbs
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import org.junit.Assert.assertFalse
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
class BookShots {
    @get:Rule
    val rule = createComposeRule()

    private class Case(val name: String, val faults: List<String> = listOf("bookplay"), val pages: List<Page> = NP, val held: Boolean = false, val after: Long = 1000, val rest: String? = null, val prep: (SimControl) -> Unit = {}, val act: BookShots.(SimControl, Shell) -> Unit = { _, _ -> })

    private var frame by mutableStateOf<Pair<Size, Shell>?>(null)
    private var control by mutableStateOf<SimControl?>(null)
    private val root = File(System.getProperty("shots.dir") ?: "build/shots", "books/final")

    fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun SimControl.restate(change: (State) -> State) {
        device = device.withState(change(device.playback.state), SystemClock.elapsedRealtime())
    }

    private fun SimControl.cover(art: ImageBitmap) {
        val t = device.playback.state.track ?: return
        for (px in listOf(Thumbs.SMALL, Thumbs.LARGE)) arts[Thumbs.key(t, px)] = art
    }

    private fun image(draw: (java.awt.Graphics2D) -> Unit): ImageBitmap {
        val img = java.awt.image.BufferedImage(600, 600, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        draw(g)
        g.dispose()
        val out = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(img, "jpg", out)
        return BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size()).asImageBitmap()
    }

    private val light: ImageBitmap by lazy {
        image { g ->
            g.paint = java.awt.GradientPaint(0f, 0f, java.awt.Color(250, 246, 236), 600f, 600f, java.awt.Color(214, 222, 230))
            g.fillRect(0, 0, 600, 600)
        }
    }

    private val busy: ImageBitmap by lazy {
        image { g ->
            for (i in 0 until 24) {
                g.color = java.awt.Color.getHSBColor(i * 0.137f, 0.7f, if (i % 2 == 0) 0.95f else 0.35f)
                g.fillRect(i * 25, 0, 25, 600)
            }
            g.color = java.awt.Color(255, 255, 255, 140)
            for (i in 0 until 12) g.fillOval(i * 97 % 520, i * 151 % 520, 80, 80)
        }
    }

    private fun content() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val f = frame ?: return@setContent
            val c = control ?: return@setContent
            key(f, c) {
                Box(Modifier.requiredSize(f.first.w.dp, f.first.h.dp).testTag("frame")) {
                    App(c.device, LinkView(Phase.LIVE, "V1", everLive = true), c, f.second, insets = f.first.bars)
                }
            }
        }
    }

    private fun shoot(cases: List<Case>, sizes: List<Size>) {
        assumeTrue(System.getProperty("shots.run") == "true")
        content()
        for (size in sizes) {
            for (case in cases) {
                val c = SimControl("v1", faults = case.faults, appFeatures = LINK_FEATURES or Groups.mask("books"), stateNow = SHOT_CLOCK).also { shotClock(it) }
                case.prep(c)
                val shell = Shell(Nav(case.pages))
                control = c
                frame = size to shell
                settle(1000)
                case.act(this, c, shell)
                settle(case.after)
                val file = File(root, "${case.name}-${size.name}.png")
                file.parentFile.mkdirs()
                rule.onNodeWithTag("frame").captureRoboImage(file.path)
                if (case.held) rule.onNodeWithTag("frame").performTouchInput { cancel() }
                case.rest?.let { r -> assertFalse("${case.name} at ${size.name} draws as $r", file.readBytes().contentEquals(File(root, "$r-${size.name}.png").readBytes())) }
                System.gc()
            }
        }
    }

    @Test
    fun nowPlaying() = shoot(
        listOf(
            Case("book-1x", prep = { c -> c.restate { it.copy(speedPct = 100) } }),
            Case("book-1.5x"),
            Case("book-2x", prep = { c -> c.restate { it.copy(speedPct = 200) } }),
            Case("book-1.75x", prep = { c -> c.restate { it.copy(speedPct = 175) } }),
            Case("book-paused", prep = { c -> c.restate { it.copy(playing = false) } }),
            Case("book-idle", act = { _, _ -> settle(IDLE_MS) }),
            Case("book-art-light", prep = { c -> c.cover(light) }),
            Case("book-art-busy", prep = { c -> c.cover(busy) }),
            Case("book-art-busy-1.75x", prep = { c ->
                c.cover(busy)
                c.restate { it.copy(speedPct = 175) }
            }),
            Case("book-long", prep = { c ->
                c.restate { it.copy(title = "Chapter 3: The Teacher and His Last Letter to the Young Student", artist = "Natsume Soseki and A Second Long Reader Name", artists = listOf("Natsume Soseki", "A Second Long Reader Name"), album = "Kokoro, the Complete and Unabridged Reading in Five Parts") }
            }, act = { c, _ ->
                val q = c.device.queue
                c.device = c.device.copy(queue = q.copy(items = q.items.mapValues { (slot, item) -> if (slot > 2) item.copy(title = "${item.title}: A Long Coming Up Chapter Title That Runs Past the Line") else item }))
            }),
            Case("book-skip-down", held = true, after = PRESS_MS, rest = "book-1.5x", act = { _, _ -> rule.onNodeWithTag("np.skipBack").performTouchInput { down(center) } }),
            Case("book-speed-down", held = true, after = PRESS_MS, rest = "book-1.5x", act = { _, _ -> rule.onNodeWithTag("np.speed").performTouchInput { down(center) } }),
            Case("book-nostretch", faults = listOf("bookplay", "nostretch")),
            Case("book-speed-sheet", act = { _, _ ->
                rule.onNodeWithTag("np.speed").performClick()
                settle(1000)
            }),
            Case("book-link-drop", act = { c, _ -> c.device = c.device.unlinked() }),
            Case("music-after-book", prep = { c -> c.play(null, null, 0, false, listOf(c.sim.lib.tracks[0].id)) }),
            Case("music-idle", prep = { c -> c.play(null, null, 0, false, listOf(c.sim.lib.tracks[0].id)) }, act = { _, _ -> settle(IDLE_MS) }),
        ),
        SIZES + NP_SIZES,
    )

    private fun power(c: SimControl) = rule.walkPivots(c.device.schema.rows, "power") { settle(1000) }

    private fun tapSleep() {
        rule.onNodeWithTag("set.sleep").performClick()
        settle(1000)
    }

    @Test
    fun sleepTimer() = shoot(
        listOf(
            Case("sleep-sheet", pages = SETTINGS, act = { c, _ ->
                power(c)
                tapSleep()
            }),
            Case("sleep-armed-sheet", pages = SETTINGS, prep = { c -> c.endOfTrack() }, act = { c, _ ->
                power(c)
                tapSleep()
            }),
            Case("sleep-timed-sheet", pages = SETTINGS, prep = { c -> c.draft(Enums.DraftKind.sleep, minutes = 45) }, act = { c, _ ->
                power(c)
                tapSleep()
            }),
            Case("sleep-typed-sheet", pages = SETTINGS, prep = { c -> c.draft(Enums.DraftKind.sleep, minutes = 37) }, act = { c, _ ->
                power(c)
                tapSleep()
            }),
            Case("sleep-armed-row", pages = SETTINGS, prep = { c -> c.endOfTrack() }, act = { c, _ -> power(c) }),
            Case("sleep-fired-row", pages = SETTINGS, prep = { c ->
                c.endOfTrack()
                c.restate { it.copy(sleepEnd = null, sleepLeftS = 0) }
            }, act = { c, _ -> power(c) }),
            Case("sleep-nobit-sheet", faults = listOf("bookplay", "nobooks"), pages = SETTINGS, act = { c, _ ->
                power(c)
                tapSleep()
            }),
        ),
        SIZES + NP_SIZES,
    )

    private companion object {
        val IDLE_MS = (Tok.motion_idle + Tok.motion_idleFade).toLong() + 500
        val PRESS_MS = Tok.motion_pressGlow.toLong() + 100
        val NP = listOf(Page.Home, Page.NowPlaying)
        val SETTINGS = listOf(Page.Home, Page.Settings)
    }
}
