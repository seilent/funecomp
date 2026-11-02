package net.seilent.funecomp

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.LyrLine
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.sim.Track
import net.seilent.funecomp.state.LyricsFetch
import net.seilent.funecomp.state.SettingsMirror
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.BackdropArt
import net.seilent.funecomp.ui.CoverBlur
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.Ui
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.time.Duration
import javax.imageio.ImageIO
import kotlin.math.exp
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class LyrbgShots {
    @get:Rule
    val rule = createComposeRule()

    private val lib = net.seilent.funecomp.sim.Library.synthetic()
    private var lyrics by mutableStateOf<LyricsFetch.Show>(LyricsFetch.Show.Off)
    private var size by mutableStateOf(SIZES[1])
    private lateinit var c: SimControl
    private val dirs = ArrayList<File>()
    private val lines = listOf("Light on the water", "Every road leads back home", "line three", "line four", "line five", "line six").mapIndexed { i, t -> LyrLine(30_000L + i * 4_000L, t) }

    @After
    fun tearDown() {
        dirs.forEach { it.deleteRecursively() }
    }

    private fun out(sub: String) = File(System.getProperty("shots.dir") ?: "build/shots", "lyrbg/$sub").apply { mkdirs() }

    private fun input(name: String): File? = System.getProperty("shots.input")?.let { File(it, name) }?.takeIf { it.isFile }

    private fun photo(): BackdropArt {
        val img = System.getProperty("shots.backdrop")?.let { File(it) }?.takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
            ?: android.graphics.Bitmap.createBitmap(IntArray(64 * 64) { 0xFF8A3A2A.toInt() }, 64, 64, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
        return BackdropArt(img, null, FADE_ENTRY)
    }

    private fun covers(first: File?): File {
        val d = kotlin.io.path.createTempDirectory("lyrbg").toFile()
        dirs += d
        lib.tracks.mapNotNull { lib.albumKey(it.id) }.distinct().forEachIndexed { i, key ->
            val f = File(d, "$key.jpg")
            if (i == 0 && first != null) {
                first.copyTo(f)
            } else {
                val img = BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB)
                val g = img.createGraphics()
                g.paint = java.awt.GradientPaint(0f, 0f, java.awt.Color.getHSBColor(i * 0.13f, 0.6f, 0.8f), 600f, 600f, java.awt.Color.getHSBColor(i * 0.13f + 0.5f, 0.7f, 0.3f))
                g.fillRect(0, 0, 600, 600)
                g.color = java.awt.Color(255, 255, 255, 90)
                g.fillOval(150, 150, 300, 300)
                g.dispose()
                ImageIO.write(img, "jpg", f)
            }
        }
        return d
    }

    private fun play(t: Track) {
        val s = c.device.playback.state
        c.device = c.device.withState(
            s.copy(
                source = Enums.Source.local, track = t.id, title = t.title, artist = t.artist, artists = t.artists, album = t.album, durationMs = t.durationMs,
                positionMs = 31_000, playing = false, lyrics = true, lyricsPlain = false, lyricsCrc = 1,
            ),
            SystemClock.elapsedRealtime(),
        )
        lyrics = LyricsFetch.Show.Lines(t.id, LyricsFetch.Copy(1, Enums.LyricsKind.synced, lines))
    }

    private fun start(mode: Boolean = true, photoOn: Boolean = true, first: File? = null, hold: Boolean = false) {
        c = SimControl("ap80", artDir = covers(first))
        c.mode = mode
        c.holdArt = hold
        play(lib.tracks[0])
        if (!photoOn) c.device = c.device.copy(settings = SettingsMirror(1, "artistPhoto=off\n"))
        val art = photo()
        val shell = Shell(Nav(listOf(Page.Home, Page.NowPlaying)))
        rule.mainClock.autoAdvance = false
        rule.setUptimeContent {
            Box(Modifier.requiredSize(size.w.dp, size.h.dp).testTag("frame")) {
                App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), c, shell, art = art, lyrics = lyrics, insets = size.bars, liveDevice = { c.device })
            }
        }
        settle(3000)
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        var left = ms
        while (left > 0) {
            val step = minOf(16L, left)
            val target = SystemClock.elapsedRealtime() + step
            rule.mainClock.advanceTimeBy(step)
            val now = SystemClock.elapsedRealtime()
            if (now < target) ShadowSystemClock.advanceBy(Duration.ofMillis(target - now))
            left -= step
        }
        rule.waitForIdle()
    }

    private fun shot(): BufferedImage = rule.onNodeWithTag("frame").pixels().also { System.gc() }

    private fun small(img: BufferedImage): BufferedImage {
        val o = BufferedImage(img.width / SCALE, img.height / SCALE, BufferedImage.TYPE_INT_RGB)
        val g = o.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(img, 0, 0, o.width, o.height, null)
        g.dispose()
        return o
    }

    private fun frames(name: String, count: Int, act: (Long) -> Unit) {
        val dir = out("motion/$name")
        val names = ArrayList<String>()
        for (i in 0..count) {
            act(i * STEP)
            settle(STEP)
            val n = "f%04d.png".format(i)
            ImageIO.write(small(shot()), "png", File(dir, n))
            names += n
        }
        File(dir, "anim.json").writeText("{\"fps\": 40, \"frames\": [" + names.joinToString(", ") { "\"$it\"" } + "]}\n")
    }

    private fun run(): Boolean = System.getProperty("shots.run") == "true"

    private fun dap(cover: String, s: Size) {
        assumeTrue(run())
        val f = input("$cover.jpg")
        assumeTrue(f != null)
        size = s
        start(first = f)
        ImageIO.write(shot(), "png", File(out("dap"), "$cover-${s.name}.png"))
    }

    @Test
    fun dapBright() = dap("lybright", SIZES[1])

    @Test
    fun dapBrightCover() = dap("lybright", SIZES[2])

    @Test
    fun dapBusy() = dap("lybusy", SIZES[1])

    @Test
    fun dapBusyCover() = dap("lybusy", SIZES[2])

    @Test
    fun dapText() = dap("lytext", SIZES[1])

    @Test
    fun dapTextCover() = dap("lytext", SIZES[2])

    @Test
    fun dapSmall() = dap("lysmall", SIZES[1])

    @Test
    fun dapSmallCover() = dap("lysmall", SIZES[2])

    @Test
    fun enter() {
        assumeTrue(run())
        start(mode = false)
        frames("enter", 100) { t -> if (t == 0L) rule.onNodeWithTag("np.art").performClick() }
    }

    @Test
    fun leave() {
        assumeTrue(run())
        start()
        frames("leave", 100) { t -> if (t == 0L) rule.onNodeWithTag("back").performClick() }
    }

    @Test
    fun leavePhotoOff() {
        assumeTrue(run())
        start(photoOn = false)
        frames("leave-photooff", 100) { t -> if (t == 0L) rule.onNodeWithTag("back").performClick() }
    }

    @Test
    fun lateCover() {
        assumeTrue(run())
        start(hold = true)
        frames("late-cover", 100) { t -> if (t == 200L) c.releaseArt() }
    }

    @Test
    fun sameAlbum() {
        assumeTrue(run())
        start()
        frames("same-album", 60) { t -> if (t == 0L) play(lib.tracks[1]) }
    }

    @Test
    fun otherAlbum() {
        assumeTrue(run())
        start()
        c.holdArt = true
        frames("other-album", 120) { t ->
            if (t == 0L) play(lib.tracks[5])
            if (t == 400L) c.releaseArt()
        }
    }

    @Test
    fun fold() {
        assumeTrue(run())
        start()
        frames("fold", 60) { t -> if (t == 100L) size = SIZES[2] }
    }

    private fun take(name: String, act: (Long) -> Unit) {
        val at = listOf(0L, 150L, 300L, 500L, 800L, 1300L, 2300L, 3300L)
        val dir = out("q5/$name")
        var t = 0L
        while (t <= at.last()) {
            act(t)
            settle(STEP)
            if (t in at) ImageIO.write(small(shot()), "png", File(dir, "t%04d.png".format(t)))
            t += STEP
        }
    }

    @Test
    fun q5a() {
        assumeTrue(run())
        start()
        c.holdArt = true
        take("a") { t ->
            if (t == 0L) play(lib.tracks[5])
            if (t == 300L) c.releaseArt()
        }
    }

    @Test
    fun q5bLoadsInTheHold() {
        assumeTrue(run())
        start()
        take("b-300") { t ->
            if (t == 0L) c.wantArt(0, lib.tracks[5].id, net.seilent.funecomp.art.Thumbs.LARGE, lib.tracks[5].album)
            if (t == 300L) play(lib.tracks[5])
        }
    }

    @Test
    fun q5bLoadsAfterTheHold() {
        assumeTrue(run())
        start()
        take("b-900") { t ->
            if (t == 500L) {
                c.holdArt = true
                play(lib.tracks[5])
            }
            if (t == 900L) c.releaseArt()
        }
    }

    @Test
    fun q1() {
        assumeTrue(run())
        val f = input("lybusy.jpg") ?: return
        size = SIZES[1]
        start(first = f)
        ImageIO.write(shot(), "png", File(out("q1"), "a-app.png"))
        val np = rule.onNodeWithTag("np").fetchSemanticsNode().boundsInRoot
        val u = Ui.of(np.width, np.height).u
        val frame = rule.onNodeWithTag("frame").fetchSemanticsNode().boundsInRoot
        val bw = frame.width.roundToInt()
        val bh = frame.height.roundToInt()
        val src = ImageIO.read(f)
        val px = IntArray(src.width * src.height)
        src.getRGB(0, 0, src.width, src.height, px, 0, src.width)
        val a = CoverBlur.cover(px, src.width, src.height, bw, bh, u)!!
        val grid = BufferedImage(a.w, a.h, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, a.w, a.h, a.px, 0, a.w) }
        val up = BufferedImage(bw, bh, BufferedImage.TYPE_INT_RGB)
        up.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(grid, 0, 0, bw, bh, null)
            dispose()
        }
        ImageIO.write(shade(up), "png", File(out("q1"), "a-background.png"))
        val crop = CoverBlur.crop(src.width, src.height, bw, bh)
        val cut = src.getSubimage((src.width - crop[0]) / 2, (src.height - crop[1]) / 2, crop[0], crop[1])
        val full = BufferedImage(bw, bh, BufferedImage.TYPE_INT_RGB)
        full.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(cut, 0, 0, bw, bh, null)
            dispose()
        }
        ImageIO.write(shade(gauss(full, Tok.backdrop_coverSigma * u)), "png", File(out("q1"), "b-background.png"))
    }

    private fun gauss(img: BufferedImage, sigma: Float): BufferedImage {
        val r = (sigma * 3).roundToInt()
        val k = FloatArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2f * sigma * sigma)) }
        val sum = k.sum()
        for (i in k.indices) k[i] /= sum
        val w = img.width
        val h = img.height
        var p = IntArray(w * h).also { img.getRGB(0, 0, w, h, it, 0, w) }
        for (pass in 0..1) {
            val o = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                var rr = 0f
                var gg = 0f
                var bb = 0f
                for (j in -r..r) {
                    val v = if (pass == 0) p[y * w + (x + j).coerceIn(0, w - 1)] else p[(y + j).coerceIn(0, h - 1) * w + x]
                    val q = k[j + r]
                    rr += (v shr 16 and 0xFF) * q
                    gg += (v shr 8 and 0xFF) * q
                    bb += (v and 0xFF) * q
                }
                o[y * w + x] = (rr.roundToInt() shl 16) or (gg.roundToInt() shl 8) or bb.roundToInt()
            }
            p = o
        }
        return BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, w, h, p, 0, w) }
    }

    private fun shade(img: BufferedImage): BufferedImage {
        val bg = (Tok.color_bg shr 16 and 0xFF).toFloat()
        val o = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until img.height) {
            val f = y.toFloat() / (img.height - 1)
            val a = if (f < Tok.backdrop_stop1_pos) {
                Tok.backdrop_stop0_alpha + (Tok.backdrop_stop1_alpha - Tok.backdrop_stop0_alpha) * (f - Tok.backdrop_stop0_pos) / (Tok.backdrop_stop1_pos - Tok.backdrop_stop0_pos)
            } else {
                Tok.backdrop_stop1_alpha + (Tok.backdrop_stop2_alpha - Tok.backdrop_stop1_alpha) * (f - Tok.backdrop_stop1_pos) / (Tok.backdrop_stop2_pos - Tok.backdrop_stop1_pos)
            }
            for (x in 0 until img.width) {
                val v = img.getRGB(x, y)
                fun ch(s: Int) = ((v shr s and 0xFF) * (1f - a) * (1f - Tok.scrim_sheet_alpha) + bg * Tok.scrim_sheet_alpha).roundToInt().coerceIn(0, 255)
                o.setRGB(x, y, ch(16) shl 16 or (ch(8) shl 8) or ch(0))
            }
        }
        return o
    }

    private companion object {
        const val STEP = 25L
        const val SCALE = 3
    }
}
