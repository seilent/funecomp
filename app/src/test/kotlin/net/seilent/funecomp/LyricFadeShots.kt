package net.seilent.funecomp

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameMillis
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
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import com.github.takahirom.roborazzi.captureRoboImage
import net.seilent.funecomp.artist.ArtCache
import net.seilent.funecomp.artist.Credit
import net.seilent.funecomp.proto.LyrLine
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.state.LyricsFetch
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.BackdropArt
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import org.junit.After
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration
import kotlin.math.roundToLong

private val FADE_LINES = listOf(
    "Light on the water", "Every road leads back home", "Hold on until the morning", "Under a paper moon",
    "We were young and the night was ours", "Lights out", "Say my name again", "Across the quiet field",
    "Nothing left to lose", "Run with me", "One more mile", "Paper moon",
).mapIndexed { i, t -> LyrLine(54_000L + 4_000L * i, t) }

internal val FADE_ENTRY = ArtCache.Entry("a74b1b7f-71a5-4011-9441-d0b5e4122711", "Radiohead", "a74b1b7f-71a5-4011-9441-d0b5e4122711", "commons", Credit("Raph_PH", "CC BY 4.0", "https://commons.wikimedia.org/wiki/File:RadioheadO2211125_composite.jpg", "https://creativecommons.org/licenses/by/4.0"), "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a1/RadioheadO2211125_composite.jpg/500px-RadioheadO2211125_composite.jpg", 0, "File:RadioheadO2211125 composite.jpg", "s", "Radiohead")

private const val BODY_B = "shows on songs with synced lyrics"

internal fun syntheticCovers(): File {
    val dir = kotlin.io.path.createTempDirectory("covers").toFile()
    val lib = net.seilent.funecomp.sim.Library.synthetic()
    lib.tracks.mapNotNull { lib.albumKey(it.id) }.distinct().forEachIndexed { i, key ->
        val img = java.awt.image.BufferedImage(600, 600, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.paint = java.awt.GradientPaint(0f, 0f, java.awt.Color.getHSBColor(i * 0.13f, 0.6f, 0.8f), 600f, 600f, java.awt.Color.getHSBColor(i * 0.13f + 0.5f, 0.7f, 0.3f))
        g.fillRect(0, 0, 600, 600)
        g.color = java.awt.Color(255, 255, 255, 90)
        g.fillOval(150, 150, 300, 300)
        g.dispose()
        javax.imageio.ImageIO.write(img, "jpg", File(dir, "$key.jpg"))
    }
    return dir
}

internal fun shotPhoto(): ImageBitmap = System.getProperty("shots.backdrop")?.let { File(it) }?.takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } ?: run {
    val img = java.awt.image.BufferedImage(720, 960, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.paint = java.awt.GradientPaint(0f, 0f, java.awt.Color(90, 70, 60), 720f, 960f, java.awt.Color(20, 30, 50))
    g.fillRect(0, 0, 720, 960)
    g.dispose()
    val out = java.io.ByteArrayOutputStream()
    javax.imageio.ImageIO.write(img, "jpg", out)
    BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size()).asImageBitmap()
}

class FadeCase(val lead: Boolean, val name: String, val dir: String, val frames: Int, val mode: Boolean, val lyrics: Boolean?, val pages: List<Page>, val pre: () -> Unit = {}, val events: List<Pair<Long, () -> Unit>>)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class LyricFadeShots {
    @get:Rule
    val rule = createComposeRule()

    private var size by mutableStateOf<Size?>(null)
    lateinit var c: SimControl
    var shell by mutableStateOf(Shell())
    private var lyrics by mutableStateOf<LyricsFetch.Show>(LyricsFetch.Show.Off)
    private var gen by mutableStateOf(0)
    private var art by mutableStateOf<BackdropArt?>(null)
    private var base = 0L
    private var sys0 = 0L

    private val made = lazy { syntheticCovers() }

    private val covers: File by made

    @After
    fun clean() {
        if (made.isInitialized()) covers.deleteRecursively()
    }

    private val photo: ImageBitmap by lazy { shotPhoto() }

    private fun tick(ms: Long) {
        repeat(ms.toInt()) {
            rule.mainClock.advanceTimeBy(1, ignoreFrameDuration = true)
            val want = sys0 + (rule.mainClock.currentTime - base)
            val now = SystemClock.elapsedRealtime()
            if (now < want) ShadowSystemClock.advanceBy(Duration.ofMillis(want - now))
        }
    }

    private fun mark() {
        base = rule.mainClock.currentTime
        sys0 = SystemClock.elapsedRealtime()
    }

    private var lastFrame = -1L

    private fun tickUntil(what: String, done: () -> Boolean) {
        var n = 0
        while (!done()) {
            if (++n > 2000) fail(what)
            tick(1)
        }
    }

    private fun at() = rule.mainClock.currentTime - base

    private fun go(t: Long) {
        if (t > at()) tick(t - at())
    }

    fun settle(ms: Long) {
        rule.waitForIdle()
        tick(ms)
        rule.waitForIdle()
    }

    fun tap(tag: String) {
        rule.onNodeWithTag(tag).performClick()
    }

    fun back() {
        Espresso.pressBack()
    }

    fun song(on: Boolean?) {
        c.transport(Enums.TransportCmd.next)
        flags(on)
    }

    fun flags(on: Boolean?) {
        val s = c.device.playback.state
        c.device = c.device.withState(s.copy(playing = false, positionMs = 61_000, lyrics = on, lyricsPlain = on?.let { false }, lyricsCrc = if (on == true) 1 else null), SystemClock.elapsedRealtime())
        val t = c.device.playback.state.track!!
        lyrics = when (on) {
            true -> LyricsFetch.Show.Lines(t, LyricsFetch.Copy(1, Enums.LyricsKind.synced, FADE_LINES))
            false -> LyricsFetch.Show.None
            null -> LyricsFetch.Show.Blank
        }
    }

    fun toastB() {
        shell.toast.show("lyrics mode on", BODY_B)
    }

    private val fades = listOf(
        FadeCase(true, "forward", "", 29, false, true, NP_PAGES, events = listOf(0L to { tap("np.art") })),
        FadeCase(true, "back-100", "", 13, false, true, NP_PAGES, events = listOf(0L to { tap("np.art") }, 100L to { back() })),
        FadeCase(true, "back-380", "", 29, false, true, NP_PAGES, events = listOf(0L to { tap("np.art") }, 380L to { back() })),
        FadeCase(true, "back-600", "", 43, false, true, NP_PAGES, events = listOf(0L to { tap("np.art") }, 600L to { back() })),
        FadeCase(true, "key-380", "", 29, false, true, NP_PAGES, events = listOf(0L to { tap("np.art") }, 364L to { tap("np.art") })),
        FadeCase(true, "close-back-200", "", 29, false, true, NP_PAGES, pre = { tap("np.art"); settle(1500) }, events = listOf(0L to { back() }, 200L to { back() })),
        FadeCase(false, "next-none", "follow/", 55, true, true, NP_PAGES, events = listOf(0L to { song(false) })),
        FadeCase(false, "next-lyrics", "follow/", 55, true, false, NP_PAGES, events = listOf(0L to { song(true) })),
        FadeCase(false, "skip3", "follow/", 73, true, false, NP_PAGES, events = listOf(0L to { song(false) }, 304L to { song(false) }, 608L to { song(true) })),
        FadeCase(false, "late", "follow/", 73, true, false, NP_PAGES, events = listOf(0L to { song(null) }, 1504L to { flags(true) })),
        FadeCase(false, "open", "follow/", 67, true, true, listOf(Page.Home), events = listOf(0L to { shell.nav.push(Page.NowPlaying) })),
    )

    private val stills = listOf(
        FadeCase(false, "toast-on-a", "follow/stills/", 0, false, false, NP_PAGES, events = listOf(0L to { tap("np.art") })),
        FadeCase(false, "toast-on-b", "follow/stills/", 0, false, false, NP_PAGES, events = listOf(0L to { tap("np.art"); toastB() })),
        FadeCase(false, "toast-off", "follow/stills/", 0, true, false, NP_PAGES, events = listOf(0L to { tap("np.art") })),
    )

    private fun begin(k: FadeCase, sz: Size) {
        c = SimControl("v1", artDir = covers)
        c.transport(Enums.TransportCmd.next)
        c.transport(Enums.TransportCmd.seek, seekMs = 61_000)
        c.mode = k.mode
        mark()
        flags(k.lyrics)
        shell = Shell(Nav(k.pages))
        art = BackdropArt(photo, null, FADE_ENTRY)
        size = sz
        gen++
        settle(1500)
        k.pre()
        rule.waitForIdle()
        mark()
    }

    @Test
    fun frames() {
        assumeTrue(System.getProperty("shots.run") == "true")
        val dir = File(System.getProperty("shots.dir") ?: "build/shots", "lyricsfade")
        val only = System.getProperty("shots.only").orEmpty()
        rule.mainClock.autoAdvance = false
        rule.setUptimeContent {
            val sz = size ?: return@setUptimeContent
            LaunchedEffect(Unit) { while (true) withFrameMillis { lastFrame = it } }
            key(gen) {
                Box(Modifier.requiredSize(sz.w.dp, sz.h.dp).testTag("frame")) {
                    App(c.device, LinkView(Phase.LIVE, "V1", everLive = true), c, shell, art = art, lyrics = lyrics, insets = sz.bars, liveDevice = { c.device })
                }
            }
        }
        val sizes = SIZES.filter { it.name == "411x960" || it.name.startsWith("cover") }
        for (sz in sizes) {
            for (k in fades) {
                if (only.isNotEmpty() && only.split(',').none { k.name == it }) continue
                begin(k, sz)
                val out = File(dir, "${k.dir}${k.name}/${sz.name}").apply { mkdirs() }
                val ev = k.events.toMutableList()
                val ms = ArrayList<Long>()
                for (n in 0 until k.frames) {
                    val t = (n * 1000.0 / 30).roundToLong()
                    while (ev.isNotEmpty() && ev.first().first <= t) {
                        val e = ev.removeAt(0)
                        go(e.first)
                        e.second()
                        if (e.first == 0L) {
                            mark()
                            if (k.lead) {
                                val f = lastFrame
                                tickUntil("a frame after $f") { lastFrame != f }
                                val lag = rule.mainClock.currentTime - lastFrame
                                base = lastFrame
                                sys0 = SystemClock.elapsedRealtime() - lag
                            }
                        }
                    }
                    tickUntil("the frame at $t") { lastFrame - base >= t - 8 }
                    rule.onNodeWithTag("frame").captureRoboImage(File(out, "f%04d.png".format(n)).path)
                    ms += lastFrame - base
                }
                val names = (0 until k.frames).joinToString(", ") { "\"f%04d.png\"".format(it) }
                File(out, "anim.json").writeText("{\"fps\": 30, \"frames\": [$names], \"ms\": [${ms.joinToString(", ")}]}\n")
            }
            for (k in stills) {
                if (only.isNotEmpty() && only.split(',').none { k.name == it }) continue
                begin(k, sz)
                k.events.forEach { it.second() }
                settle(600)
                val out = File(dir, k.dir).apply { mkdirs() }
                rule.onNodeWithTag("frame").captureRoboImage(File(out, "${k.name}-${sz.name}.png").path)
            }
        }
    }
}

private val NP_PAGES = listOf(Page.Home, Page.NowPlaying)
