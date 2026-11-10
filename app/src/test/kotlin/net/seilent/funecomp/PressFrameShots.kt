package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.state.EqSlots
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.Ui
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class PressFrameShots {
    @get:Rule
    val rule = createComposeRule()

    private class Case(val name: String, val pages: List<Page>, val scaled: String, val prep: PressFrameShots.() -> Unit = {}, val area: String? = null, val act: PressFrameShots.(Long) -> Unit)

    private lateinit var c: SimControl
    private lateinit var shell: Shell

    private fun node(tag: String) = if (tag.startsWith(TEXT)) rule.onNode(hasText(tag.removePrefix(TEXT), ignoreCase = true)) else rule.onNodeWithTag(tag)

    private fun bounds(tag: String) = node(tag).fetchSemanticsNode().boundsInRoot

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun until(at: Long) = settle((at - rule.mainClock.currentTime).coerceAtLeast(0))

    private fun slop() = Tok.touchSlop * Ui.of(411f * rule.density.density, 960f * rule.density.density).u

    private fun tap(tag: String, upAt: Long): PressFrameShots.(Long) -> Unit = { t0 ->
        if (rule.mainClock.currentTime == t0) node(tag).performTouchInput { down(center) }
        if (rule.mainClock.currentTime >= t0 + upAt && !upDone) {
            node(tag).performTouchInput { up() }
            upDone = true
        }
    }

    private fun drag(tag: String, at: Long, by: Offset): PressFrameShots.(Long) -> Unit = { t0 ->
        if (rule.mainClock.currentTime == t0) node(tag).performTouchInput { down(center) }
        if (rule.mainClock.currentTime >= t0 + at && !moved) {
            node(tag).performTouchInput { moveBy(by) }
            moved = true
        }
        if (rule.mainClock.currentTime >= t0 + at + DRAG_UP && !upDone) {
            node(tag).performTouchInput { up() }
            upDone = true
        }
    }

    private fun two(): PressFrameShots.(Long) -> Unit = { t0 ->
        val now = rule.mainClock.currentTime
        val a = bounds("np.prev").center
        val b = bounds("np.nextTrack").center
        if (now == t0) rule.onRoot().performTouchInput { down(0, a) }
        if (now >= t0 + 150 && !moved) {
            rule.onRoot().performTouchInput { down(1, b) }
            moved = true
        }
        if (now >= t0 + 300 && !upDone) {
            rule.onRoot().performTouchInput {
                up(0)
                up(1)
            }
            upDone = true
        }
    }

    private var upDone = false
    private var moved = false

    private fun crop(img: BufferedImage, r: Rect): BufferedImage {
        val mx = r.width * MARGIN
        val my = r.height * MARGIN
        val x0 = (r.left - mx).toInt().coerceIn(0, img.width - 1)
        val y0 = (r.top - my).toInt().coerceIn(0, img.height - 1)
        val x1 = (r.right + mx).toInt().coerceIn(x0 + 1, img.width)
        val y1 = (r.bottom + my).toInt().coerceIn(y0 + 1, img.height)
        val sub = img.getSubimage(x0, y0, x1 - x0, y1 - y0)
        val out = BufferedImage(sub.width * ZOOM, sub.height * ZOOM, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        g.drawImage(sub, 0, 0, out.width, out.height, null)
        g.dispose()
        return out
    }

    private fun extent(img: BufferedImage, r: Rect): Int {
        val pad = r.width * PAD
        val x0 = (r.left - pad).toInt().coerceIn(0, img.width - 1)
        val x1 = (r.right + pad).toInt().coerceIn(x0 + 1, img.width)
        val y0 = r.top.toInt().coerceIn(0, img.height - 1)
        val y1 = r.bottom.toInt().coerceIn(y0 + 1, img.height)
        var lo = Int.MAX_VALUE
        var hi = -1
        for (y in y0 until y1) for (x in x0 until x1) {
            val p = img.getRGB(x, y)
            if ((299 * (p shr 16 and 0xff) + 587 * (p shr 8 and 0xff) + 114 * (p and 0xff)) / 1000 > BRIGHT) {
                if (x < lo) lo = x
                if (x > hi) hi = x
            }
        }
        return if (hi < lo) 0 else hi - lo + 1
    }

    private fun run(k: Case, dir: File) {
        c = SimControl("ap80")
        c.autoEq = SimControl.FULL
        shell = Shell(Nav(k.pages))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp).testTag("frame")) { App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), c, shell) }
        }
        settle(1000)
        k.prep(this)
        settle(600)
        val rest = k.area?.let { bounds(k.scaled).let { a -> val b = bounds(it); Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)) } } ?: bounds(k.scaled)
        val restExtent = extent(rule.onNodeWithTag("frame").pixels().also { System.gc() }, rest)
        val out = File(dir, k.name).apply { mkdirs() }
        val t0 = rule.mainClock.currentTime
        val tsv = StringBuilder("ms\tscale\n")
        val names = ArrayList<String>()
        upDone = false
        moved = false
        for (i in 0..FRAMES) {
            until(t0 + i * STEP)
            k.act(this, t0)
            rule.waitForIdle()
            val img = rule.onNodeWithTag("frame").pixels()
            System.gc()
            val s = if (restExtent > 0) extent(img, rest).toFloat() / restExtent else Float.NaN
            tsv.append(i * STEP).append('\t').append(s).append('\n')
            val name = "f%04d.png".format(i)
            ImageIO.write(crop(img, rest), "png", File(out, name))
            names += name
        }
        File(out, "scale.tsv").writeText(tsv.toString())
        File(out, "anim.json").writeText("{\"fps\": 40, \"frames\": [" + names.joinToString(", ") { "\"$it\"" } + "]}\n")
    }

    private fun artists() = rule.pivotTo("music", "artists") { settle(it) }

    private fun cases(): List<Case> = listOf(
        Case("ring-tap", NP, "np.toggle.play", act = tap("np.toggle", 50)),
        Case("glyph-tap", NP, "np.nextTrack.glyph", act = tap("np.nextTrack", 50)),
        Case("row-push", listOf(Page.Home, Page.Music), "${TEXT}Utada Hikaru", prep = { artists() }, act = tap("${TEXT}Utada Hikaru", 50)),
        Case("home-push", listOf(Page.Home), "${TEXT}music", act = tap("home.music", 50)),
        Case("jump-tap", EQ + Page.AutoEq(EqSlots.HEADPHONES), "jump.19", prep = {
            node("aeq.div.#").performClick()
            settle(300)
        }, act = tap("jump.19", 50)),
        Case("row-drag", listOf(Page.Home, Page.Music), "${TEXT}Utada Hikaru", prep = { artists() }, act = drag("${TEXT}Utada Hikaru", 150, Offset(0f, -slop() * 2))),
        Case("ring-drag", NP, "np.toggle.play", act = drag("np.toggle", 150, Offset(0f, slop() + 10f))),
        Case("back-ring", NP, "back", act = tap("back", 50)),
        Case("q2", NP, "np.prev", area = "np.nextTrack", act = two()),
    )

    private fun frames(name: String) {
        assumeTrue(System.getProperty("shots.run") == "true")
        val only = System.getProperty("shots.only").orEmpty()
        assumeTrue(only.isEmpty() || only.split(',').any { it == "presshold" || it == name })
        run(cases().first { it.name == name }, File(System.getProperty("shots.dir") ?: "build/shots", "presshold"))
    }

    @Test
    fun ringTap() = frames("ring-tap")

    @Test
    fun glyphTap() = frames("glyph-tap")

    @Test
    fun rowPush() = frames("row-push")

    @Test
    fun homePush() = frames("home-push")

    @Test
    fun jumpTap() = frames("jump-tap")

    @Test
    fun rowDrag() = frames("row-drag")

    @Test
    fun ringDrag() = frames("ring-drag")

    @Test
    fun backRing() = frames("back-ring")

    @Test
    fun twoItems() = frames("q2")

    private companion object {
        const val TEXT = "text:"
        const val STEP = 25L
        const val FRAMES = 20
        const val ZOOM = 4
        const val MARGIN = 0.4f
        const val DRAG_UP = 200L
        const val PAD = 0.06f
        const val BRIGHT = 120
        val NP = listOf(Page.Home, Page.NowPlaying)
        val EQ = listOf(Page.Home, Page.Settings, Page.Eq(Enums.EqOutput.wired))
    }
}
