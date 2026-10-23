package net.seilent.funecomp

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import net.seilent.funecomp.proto.LyrLine
import net.seilent.funecomp.proto.State
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

private const val PHONE_CRC = 77L

private val LOCAL_LINES = listOf("Light on the water", "Every road leads back home", "Hold on until the morning", "Under a paper moon", "Lights out", "Say my name again")
    .mapIndexed { i, t -> LyrLine(54_000L + 4_000L * i, t) }

private class VariantCase(val name: String, val frames: Int, val lyrics: Boolean, val toPhone: Boolean)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class VariantShots {
    @get:Rule
    val rule = createComposeRule()

    private var size by mutableStateOf<Size?>(null)
    private lateinit var c: SimControl
    private lateinit var home: State
    private var shell by mutableStateOf(Shell())
    private var lyrics by mutableStateOf<LyricsFetch.Show>(LyricsFetch.Show.Off)
    private var gen by mutableStateOf(0)
    private var art by mutableStateOf<BackdropArt?>(null)
    private var base = 0L
    private var sys0 = 0L
    private var lastFrame = -1L
    private val made = lazy { syntheticCovers() }
    private val covers: File by made
    private val photo: ImageBitmap by lazy { shotPhoto() }
    private val cover: ImageBitmap by lazy {
        val img = java.awt.image.BufferedImage(200, 200, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.paint = java.awt.GradientPaint(0f, 0f, java.awt.Color(40, 120, 160), 200f, 200f, java.awt.Color(150, 40, 90))
        g.fillRect(0, 0, 200, 200)
        g.color = java.awt.Color(255, 255, 255, 90)
        g.fillRect(50, 50, 100, 100)
        g.dispose()
        val out = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(img, "jpg", out)
        android.graphics.BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size()).asImageBitmap()
    }

    @After
    fun clean() {
        if (made.isInitialized()) covers.deleteRecursively()
    }

    private val cases = listOf(
        VariantCase("in", 25, false, true),
        VariantCase("in-lyrics", 25, true, true),
        VariantCase("out", 30, false, false),
    )

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

    private fun tickUntil(what: String, done: () -> Boolean) {
        var n = 0
        while (!done()) {
            if (++n > 2000) fail(what)
            tick(1)
        }
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        tick(ms)
        rule.waitForIdle()
    }

    private fun local(lyricsOn: Boolean) {
        c.device = c.device.withState(home.copy(lyrics = lyricsOn, lyricsPlain = false, lyricsCrc = if (lyricsOn) 1 else null), SystemClock.elapsedRealtime())
        c.phoneCovers = emptyMap()
        val t = c.device.playback.state.track
        lyrics = if (lyricsOn && t != null) LyricsFetch.Show.Lines(t, LyricsFetch.Copy(1, Enums.LyricsKind.synced, LOCAL_LINES)) else LyricsFetch.Show.None
    }

    private fun phone() {
        val s = c.device.playback.state
        c.device = c.device.withState(
            s.copy(
                source = Enums.Source.phone, output = Enums.Output.phoneReceiver, track = 0, title = "Signal Fire", artist = "Kaze Ensemble", artists = listOf("Kaze Ensemble"),
                mbids = emptyList(), album = "Coastlines", durationMs = 213_000, positionMs = 61_000, playing = true, lyrics = null, lyricsPlain = null, lyricsCrc = null,
                lyricsOffsetMs = null, favorite = null, receiver = true, phoneName = "Phone 7", phoneTrackNo = 3, phoneTracks = 12, phoneArtCrc = PHONE_CRC,
            ),
            SystemClock.elapsedRealtime(),
        )
        c.phoneCovers = mapOf(PHONE_CRC to cover)
        lyrics = LyricsFetch.Show.Off
    }

    private fun begin(k: VariantCase, sz: Size) {
        c = SimControl("v1", artDir = covers, minor = 15)
        c.transport(Enums.TransportCmd.next)
        c.transport(Enums.TransportCmd.seek, seekMs = 61_000)
        home = c.device.playback.state
        c.mode = k.lyrics
        mark()
        if (k.toPhone) local(k.lyrics) else phone()
        shell = Shell(Nav(listOf(Page.Home, Page.NowPlaying)))
        art = BackdropArt(photo, null, FADE_ENTRY)
        size = sz
        gen++
        settle(1500)
        rule.waitForIdle()
        mark()
    }

    @Test
    fun frames() {
        assumeTrue(System.getProperty("shots.run") == "true")
        val dir = File(System.getProperty("shots.dir") ?: "build/shots", "rxnp-t7")
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
        for (sz in SIZES) {
            for (k in cases) {
                if (only.isNotEmpty() && only.split(',').none { k.name == it }) continue
                begin(k, sz)
                val out = File(dir, "${k.name}/${sz.name}").apply { mkdirs() }
                if (k.toPhone) phone() else local(false)
                val f = lastFrame
                tickUntil("a frame after $f") { lastFrame != f }
                val lag = rule.mainClock.currentTime - lastFrame
                base = lastFrame
                sys0 = SystemClock.elapsedRealtime() - lag
                val ms = ArrayList<Long>()
                for (n in 0 until k.frames) {
                    val t = (n * 1000.0 / 30).roundToLong()
                    tickUntil("the frame at $t") { lastFrame - base >= t - 8 }
                    rule.onNodeWithTag("frame").captureRoboImage(File(out, "f%04d.png".format(n)).path)
                    ms += lastFrame - base
                }
                val names = (0 until k.frames).joinToString(", ") { "\"f%04d.png\"".format(it) }
                File(out, "anim.json").writeText("{\"fps\": 30, \"frames\": [$names], \"ms\": [${ms.joinToString(", ")}]}\n")
            }
        }
    }
}
