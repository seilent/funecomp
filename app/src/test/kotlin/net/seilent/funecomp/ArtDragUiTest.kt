package net.seilent.funecomp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import net.seilent.funecomp.artist.ArtPicks
import net.seilent.funecomp.artist.Crop
import net.seilent.funecomp.artist.Offer
import net.seilent.funecomp.artist.Sources
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.BackdropArt
import net.seilent.funecomp.ui.Control
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.PickFor
import net.seilent.funecomp.ui.Preview
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.Ui
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class ArtDragUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val points = ArrayList<Pair<Int, Int>?>()
    private lateinit var shell: Shell
    private lateinit var view: View
    private var size by mutableStateOf(Pair(411, 960))

    private fun offer(n: Int = 0) = Offer(ArtPicks.IMAGE, Sources.LINK, "https://example.org/$n.jpg", "https://example.org/$n.jpg", null, null, "TSUKI", ByteArray(1))

    private fun preview(w: Int, h: Int, n: Int = 0, faceY: Double? = null): Preview {
        val r = Crop.rect(w, h, BACKDROP_W, BACKDROP_H, faceY)
        return Preview(offer(n), null, Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).asImageBitmap(), IntRect(r.x, r.y, r.x + r.w, r.y + r.h), faceY)
    }

    private fun start(vararg previews: Preview) = start(listOf(Page.Home, Page.NowPlaying, Page.PickPreview(PickFor("TSUKI"), previews.toList())))

    private fun start(pages: List<Page>, art: BackdropArt? = null) {
        val c = SimControl("ap80")
        val control = object : Control by c {
            override fun pick(target: PickFor, offer: Offer, point: Pair<Int, Int>?) {
                points += point
            }
        }
        shell = Shell(Nav(pages))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            view = LocalView.current
            Box(Modifier.requiredSize(size.first.dp, size.second.dp)) {
                App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), control, shell, art = art)
            }
        }
        settle(1000)
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun bounds(tag: String): Rect = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().first().boundsInRoot

    private val slop: Float get() = bounds("pick.page").let { Ui.of(it.width, it.height) }.px(Tok.touchSlop)

    private fun scale(win: IntRect): Float = bounds("pick.preview").let { minOf(it.width / win.width, it.height / win.height) }

    private fun drag(d: Offset, at: Offset = bounds("pick.preview").center) {
        rule.onRoot().performTouchInput {
            down(at)
            moveBy(d)
            up()
        }
        settle(300)
    }

    private fun across(d: Float) = Offset(if (d < 0) d - slop else d + slop, 0f)

    private fun down(d: Float) = Offset(0f, if (d < 0) d - slop else d + slop)

    private fun picked(): Pair<Int, Int>? {
        rule.onNodeWithTag("pick.label").performClick()
        settle(500)
        return points.last()
    }

    private fun near(want: Pair<Int, Int>, got: Pair<Int, Int>?) = got != null && abs(want.first - got.first) <= 1 && abs(want.second - got.second) <= 1

    @Test
    fun anAcrossDragMovesTheWindowOneToOne() {
        val p = preview(1920, 1080)
        start(p)
        val win = p.window!!
        drag(across(-100f))
        val x = win.left + 100f / scale(win)
        val want = Crop.point(1920, 1080, net.seilent.funecomp.artist.Rect(x.toInt(), 0, win.width, win.height), null)
        val got = picked()
        assertTrue("$want $got", near(want, got))
    }

    @Test
    fun aResizeKeepsTheDragOneToOne() {
        val p = preview(1920, 1080)
        start(p)
        size = 330 to 960
        settle(1000)
        val win = p.window!!
        drag(across(-300f))
        val x = win.left + 300f / scale(win)
        val want = Crop.point(1920, 1080, net.seilent.funecomp.artist.Rect(x.toInt(), 0, win.width, win.height), null)
        val got = picked()
        assertTrue("$want $got", near(want, got))
    }

    @Test
    fun theWindowStopsAtTheLeftSourceEdge() {
        start(preview(1920, 1080))
        drag(across(2000f))
        assertEquals(158 to 333, picked())
    }

    @Test
    fun theWindowStopsAtTheRightSourceEdge() {
        start(preview(1920, 1080))
        drag(across(-2000f))
        assertEquals(841 to 333, picked())
    }

    @Test
    fun aDownDragMovesNothingOnAWideSource() {
        start(preview(1920, 1080))
        drag(down(-300f))
        assertNull(picked())
    }

    @Test
    fun aDownDragMovesATallSourceToItsBottomEdge() {
        start(preview(720, 1800))
        drag(down(-2000f))
        assertEquals(500 to 644, picked())
    }

    @Test
    fun aMoveInsideTheSlopMovesNothing() {
        start(preview(1920, 1080))
        drag(Offset(-slop * 0.8f, 0f))
        assertFalse(rule.onAllNodesWithTag("pick.reset").fetchSemanticsNodes().isNotEmpty())
        assertNull(picked())
    }

    @Test
    fun aPreviewWithoutTheSourceHasNoDrag() {
        val c = Bitmap.createBitmap(680, 1210, Bitmap.Config.ARGB_8888).asImageBitmap()
        start(Preview(offer(), c))
        drag(across(-300f))
        assertTrue(rule.onAllNodesWithTag("pick.exclude").fetchSemanticsNodes().isEmpty())
        assertNull(picked())
    }

    @Test
    fun aPressOnTheBackRingCornerGoesBack() {
        start(preview(1920, 1080))
        val f = bounds("pick.preview")
        rule.onRoot().performTouchInput { click(Offset(f.left + 4f, f.top + 4f)) }
        settle(1000)
        assertEquals(Page.NowPlaying, shell.nav.top)
        assertTrue(points.isEmpty())
    }

    @Test
    fun aDragFromTheBackRingCornerPansNothing() {
        start(preview(1920, 1080))
        val f = bounds("pick.preview")
        drag(across(-300f), Offset(f.left + 4f, f.top + 4f))
        assertNull(picked())
    }

    @Test
    fun resetShowsAfterAMoveAndPutsTheDefaultBack() {
        start(preview(1920, 1080))
        assertTrue(rule.onAllNodesWithTag("pick.reset").fetchSemanticsNodes().isEmpty())
        drag(across(-300f))
        rule.onNodeWithTag("pick.reset").performClick()
        settle(300)
        assertTrue(rule.onAllNodesWithTag("pick.reset").fetchSemanticsNodes().isEmpty())
        assertNull(picked())
    }

    @Test
    fun eachPreviewKeepsItsOwnWindow() {
        start(preview(1920, 1080, 0), preview(1600, 900, 1))
        drag(across(2000f))
        rule.onNodeWithTag("pick.next").performClick()
        settle(300)
        assertTrue(rule.onAllNodesWithTag("pick.reset").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("pick.next").performClick()
        settle(300)
        assertEquals(158 to 333, picked())
    }

    @Test
    fun theFrameExcludesTwoSideBandsOfAtMost200dp() {
        start(preview(1920, 1080))
        val rects = view.systemGestureExclusionRects
        val dp = view.resources.displayMetrics.density
        assertEquals(2, rects.size)
        assertTrue(rects.all { it.height() <= (200 * dp).toInt() + 1 })
        val f = bounds("pick.preview")
        val lefts = rects.map { it.left }.sorted()
        assertEquals(0, lefts[0])
        assertTrue("$lefts", abs(lefts[1] - f.width / 2) <= 1f)
        assertTrue(rects.all { abs((it.top + it.bottom) / 2f - f.center.y) <= 2f })
    }

    @Test
    fun aDragDuringThePageTurnMovesNothing() {
        start(preview(1920, 1080))
        val f = bounds("pick.preview")
        val d = across(-300f)
        rule.onRoot().performTouchInput { click(Offset(f.left + 4f, f.top + 4f)) }
        rule.mainClock.advanceTimeBy(48)
        rule.onRoot().performTouchInput {
            down(f.center)
            moveBy(d)
        }
        rule.mainClock.advanceTimeBy(16)
        val page = rule.onAllNodesWithTag("pick.page").fetchSemanticsNodes().size
        val reset = rule.onAllNodesWithTag("pick.reset").fetchSemanticsNodes().size
        rule.onRoot().performTouchInput { up() }
        assertEquals(1, page)
        assertEquals(0, reset)
    }

    @Test
    fun theNowPlayingBackdropCropsAtThePickPoint() {
        val b = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(b)
        canvas.drawRect(0f, 0f, 960f, 1080f, Paint().apply { color = AndroidColor.RED })
        canvas.drawRect(960f, 0f, 1920f, 1080f, Paint().apply { color = AndroidColor.BLUE })
        start(listOf(Page.Home, Page.NowPlaying), BackdropArt(b.asImageBitmap(), point = 0 to 333))
        val file = File.createTempFile("backdrop", ".png")
        rule.onNodeWithTag("np.backdrop").captureRoboImage(file.path)
        val img = ImageIO.read(file)
        file.delete()
        System.gc()
        val px = listOf(0.95f to 0.5f, 0.95f to 0.15f, 0.05f to 0.5f, 0.5f to 0.5f).map { (fx, fy) -> java.awt.Color(img.getRGB((img.width * fx).toInt(), (img.height * fy).toInt())) }
        assertTrue("$px", px[0].red > px[0].blue + 40 && px[1].red > px[1].blue + 40)
    }

    private companion object {
        const val BACKDROP_W = 720
        const val BACKDROP_H = 1280
    }
}
