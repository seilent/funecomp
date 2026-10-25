package net.seilent.funecomp

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.state.LyricsFetch
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.BackdropArt
import net.seilent.funecomp.ui.LayoutTok
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.Ui
import net.seilent.funecomp.ui.npProgressY
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class SeekBandHitShots {
    @get:Rule
    val rule = createComposeRule()

    private var frame by mutableStateOf<Pair<Shot, Size>?>(null)
    private var control by mutableStateOf<SimControl?>(null)
    private var shell by mutableStateOf(Shell())

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private val covers: File by lazy {
        val dir = createTempDirectory("covers").toFile()
        val lib = Library.synthetic()
        lib.tracks.mapNotNull { lib.albumKey(it.id) }.distinct().forEachIndexed { i, key ->
            val img = BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.paint = GradientPaint(0f, 0f, Color.getHSBColor(i * 0.13f, 0.6f, 0.8f), 600f, 600f, Color.getHSBColor(i * 0.13f + 0.5f, 0.7f, 0.3f))
            g.fillRect(0, 0, 600, 600)
            g.color = Color(255, 255, 255, 90)
            g.fillOval(150, 150, 300, 300)
            g.dispose()
            ImageIO.write(img, "jpg", File(dir, "$key.jpg"))
        }
        dir
    }

    private val plain: ImageBitmap by lazy {
        val img = BufferedImage(720, 960, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.paint = GradientPaint(0f, 0f, Color(90, 70, 60), 720f, 960f, Color(20, 30, 50))
        g.fillRect(0, 0, 720, 960)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "jpg", out)
        BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size()).asImageBitmap()
    }

    private fun content() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val f = frame ?: return@setContent
            val c = control ?: return@setContent
            key(f) {
                Box(Modifier.requiredSize(f.second.w.dp, f.second.h.dp).testTag("frame")) {
                    val held = f.first.artEntry?.let { BackdropArt(plain, null, it, f.first.wrong) }
                    App(c.device, f.first.link, c, shell, art = held, lyrics = f.first.lyrics?.invoke(c.device) ?: LyricsFetch.Show.Off, insets = f.second.bars)
                }
            }
        }
    }

    private fun show(shot: Shot, size: Size) {
        val c = SimControl(shot.board, artDir = if (shot.art) covers else null, minor = shot.minor, faults = shot.faults, cardEq = shot.cardEq)
        shot.prep(c)
        control = c
        shell = Shell(Nav(shot.pages))
        frame = shot to size
        settle(1000)
        settle(shot.after)
    }

    private fun bounds(tag: String): Rect? = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()?.boundsInRoot

    private fun capture(file: File): BufferedImage {
        file.parentFile.mkdirs()
        rule.onNodeWithTag("frame").captureRoboImage(file.path)
        return ImageIO.read(file)
    }

    private fun Graphics2D.smooth() {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    }

    private fun sheet(tiles: List<Pair<BufferedImage, String>>, file: File) {
        val w = tiles.maxOf { it.first.width }
        val cap = CAPTION
        val h = tiles.sumOf { it.first.height + cap }
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.smooth()
        g.color = Color.BLACK
        g.fillRect(0, 0, w, h)
        var y = 0
        for ((img, caption) in tiles) {
            g.drawImage(img, 0, y, null)
            g.color = Color.WHITE
            var size = cap * 3 / 5
            do {
                g.font = Font(Font.SANS_SERIF, Font.PLAIN, size--)
            } while (size > 1 && g.fontMetrics.stringWidth(caption) > w - cap / 2)
            g.drawString(caption, cap / 4, y + img.height + cap * 3 / 4)
            y += img.height + cap
        }
        g.dispose()
        ImageIO.write(out, "png", file)
    }

    @Test
    fun hits() {
        assumeTrue(System.getProperty("shots.run") == "true")
        val root = File(System.getProperty("shots.dir") ?: "build/shots", "seekband")
        val dir = File(root, "hits")
        content()
        val shots = SHOTS.filter { it.name == "nowplaying" || it.name == "lyrmark-short" }
        val sizes = SIZES.filter { it.w > it.h || it.name == "411x960" || it.name.startsWith("cover") }
        val tsv = StringBuilder("shot\tsize\ttag\tleft\ttop\tright\tbottom\n")
        for (size in sizes) {
            val tiles = ArrayList<Pair<BufferedImage, String>>()
            for (shot in shots) {
                show(shot, size)
                val name = "${shot.name}-${size.name}.png"
                val img = capture(File(dir, "raw/$name"))
                val g = img.createGraphics()
                g.smooth()
                val stroke = (img.width / 400f).coerceAtLeast(2f)
                g.stroke = BasicStroke(stroke)
                g.font = Font(Font.SANS_SERIF, Font.BOLD, (img.width / 70).coerceAtLeast(14))
                for ((tag, color) in HITS) {
                    val b = bounds(tag)
                    tsv.append("${shot.name}\t${size.name}\t$tag\t${b?.left}\t${b?.top}\t${b?.right}\t${b?.bottom}\n")
                    if (b == null || b.width <= 0f || b.height <= 0f) continue
                    g.color = color
                    g.drawRect(b.left.toInt(), b.top.toInt(), b.width.toInt(), b.height.toInt())
                    val ty = if (tag == "np.seek") minOf(b.bottom + g.font.size, img.height - stroke) else b.top + g.font.size
                    g.drawString(tag, b.left.toInt() + stroke.toInt() * 2, ty.toInt())
                }
                g.dispose()
                ImageIO.write(img, "png", File(dir, name))
                tiles += img to "${shot.name} ${size.name}: hits/$name"
            }
            sheet(tiles, File(dir, "sheet-${size.name}.png"))
        }
        File(dir, "bounds.tsv").writeText(tsv.toString())
        File(dir, "raw").deleteRecursively()
        covers.deleteRecursively()
    }

    @Test
    fun drag() {
        assumeTrue(System.getProperty("shots.run") == "true")
        val dir = File(File(System.getProperty("shots.dir") ?: "build/shots", "seekband"), "drag")
        content()
        val shot = SHOTS.first { it.name == "nowplaying" }
        val tiles = ArrayList<Pair<BufferedImage, String>>()
        for (size in SIZES.filter { it.name in DRAG_SIZES }) {
            show(shot, size)
            val np = bounds("np")!!
            val ui = Ui.of(np.width, np.height)
            val art = bounds("np.art")!!
            val y = np.top + (ui.npProgressY + SEEK_MID) * ui.u
            for ((i, at) in HELD_AT.withIndex()) {
                rule.onRoot().performTouchInput {
                    if (i == 0) down(Offset(art.left + at * art.width, y)) else moveTo(Offset(art.left + at * art.width, y))
                }
                settle(100)
                val name = "nowplaying-${size.name}-at${(at * 100).toInt()}.png"
                val img = capture(File(dir, name))
                val g = img.createGraphics()
                g.smooth()
                g.color = Color.MAGENTA
                val r = (8 * ui.u).toInt()
                g.stroke = BasicStroke((img.width / 400f).coerceAtLeast(2f))
                g.drawOval((art.left + at * art.width).toInt() - r, y.toInt() - r, 2 * r, 2 * r)
                g.dispose()
                ImageIO.write(img, "png", File(dir, name))
                tiles += img to "${size.name} pointer held at ${(at * 100).toInt()} % of the line: drag/$name"
            }
            rule.onRoot().performTouchInput { cancel() }
            settle(100)
        }
        sheet(tiles, File(dir, "sheet.png"))
    }

    private companion object {
        val SEEK_MID = (LayoutTok.npSeekBandBottom - LayoutTok.npProgressY) / 2
        const val CAPTION = 64
        val HELD_AT = listOf(0.25f, 0.75f)
        val DRAG_SIZES = setOf("flip960x411", "960x600", "cover361x399")
        val HITS = listOf(
            "np.seek" to Color(255, 64, 64),
            "np.titleBlock" to Color(64, 160, 255),
            "np.art" to Color(255, 255, 255),
            "np.heart" to Color(255, 200, 0),
            "np.mark.hit" to Color(0, 230, 120),
            "np.shuffle" to Color(200, 120, 255),
            "np.prev" to Color(200, 120, 255),
            "np.toggle" to Color(200, 120, 255),
            "np.nextTrack" to Color(200, 120, 255),
            "np.repeat" to Color(200, 120, 255),
        )
    }
}
