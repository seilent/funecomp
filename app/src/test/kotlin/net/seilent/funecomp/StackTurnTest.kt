package net.seilent.funecomp

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.LyrLine
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.state.LyricsFetch
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.Decel
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.LocalTurnProbe
import net.seilent.funecomp.ui.LocalUptime
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.Ui
import net.seilent.funecomp.ui.homeItems
import net.seilent.funecomp.ui.homeRingEnter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class StackTurnTest {
    @get:Rule
    val rule = createComposeRule()

    private val shell = Shell(Nav(listOf(Page.Home)))

    private var frame = -1L

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun capture(): BufferedImage = rule.onRoot().pixels().also { System.gc() }

    private fun top(img: BufferedImage): Int {
        var m = 0
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val p = img.getRGB(x, y)
            m = maxOf(m, p shr 16 and 0xff, p shr 8 and 0xff, p and 0xff)
            if (m > BLANK) return m
        }
        return m
    }

    private fun bounds(tag: String): Rect? = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().firstOrNull()?.boundsInRoot

    private fun box(img: BufferedImage, r: Rect): IntArray {
        val x0 = r.left.roundToInt().coerceIn(0, img.width)
        val y0 = r.top.roundToInt().coerceIn(0, img.height)
        return intArrayOf(x0, y0, r.right.roundToInt().coerceIn(x0, img.width), r.bottom.roundToInt().coerceIn(y0, img.height))
    }

    private fun peak(img: BufferedImage, r: Rect): Int {
        val (x0, y0, x1, y1) = box(img, r).toList()
        var m = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val p = img.getRGB(x, y)
            m = maxOf(m, p shr 16 and 0xff, p shr 8 and 0xff, p and 0xff)
        }
        return m
    }

    private fun drawnOutside(img: BufferedImage, mask: Rect): Boolean {
        val (x0, y0, x1, y1) = box(img, mask).toList()
        for (y in 0 until img.height) for (x in 0 until img.width) {
            if (x in x0 until x1 && y in y0 until y1) continue
            val p = img.getRGB(x, y)
            if (maxOf(p shr 16 and 0xff, p shr 8 and 0xff, p and 0xff) > BLANK) return true
        }
        return false
    }

    private fun text(tag: String): Rect {
        val home = bounds("home")!!
        val r = bounds(tag)!!
        return r.copy(left = r.left + Tok.focus_barOffset * Ui.of(home.width, home.height).u)
    }

    private fun crop(img: BufferedImage, r: Rect): List<Int> {
        val (x0, y0, x1, y1) = box(img, r).toList()
        return (y0 until y1).flatMap { y -> (x0 until x1).map { x -> img.getRGB(x, y) } }
    }

    private fun same(a: BufferedImage, b: BufferedImage): Boolean {
        if (a.width != b.width || a.height != b.height) return false
        for (y in 0 until a.height) for (x in 0 until a.width) if (a.getRGB(x, y) != b.getRGB(x, y)) return false
        return true
    }

    private fun turn(name: String, act: () -> Unit, keep: List<Long> = emptyList()): Map<Long, BufferedImage> {
        val start = rule.mainClock.currentTime
        act()
        val kept = HashMap<Long, BufferedImage>()
        var seen = frame
        var frames = 0
        while (rule.mainClock.currentTime - start < TURN) {
            rule.mainClock.advanceTimeBy(1, ignoreFrameDuration = true)
            if (frame == seen) continue
            seen = frame
            frames++
            val t = rule.mainClock.currentTime - start
            val img = capture()
            assertTrue("$name frame at $t ms is blank", top(img) > BLANK)
            keep.firstOrNull { it <= t && it !in kept }?.let { kept[it] = img }
        }
        assertTrue("$name drew $frames frames", frames > TURN / FRAME / 2)
        return kept
    }

    @Test
    fun noFrameOfAPushOrPopIsBlankAndTheInLegRunsThenRests() {
        val c = SimControl("ap80")
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LaunchedEffect(Unit) { while (true) withFrameMillis { frame = it } }
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), c, shell) }
        }
        settle(1500)
        val kept = turn("push settings", { shell.nav.push(Page.Settings) }, listOf(MID, REST))
        settle(REST + REST_AGAIN - TURN)
        val later = capture()
        assertFalse("the in leg is still turning at $MID ms", same(kept.getValue(MID), later))
        assertTrue("settled from $REST ms", same(kept.getValue(REST), later))
        settle(1000)
        turn("pop settings", { shell.nav.pop() })
        settle(1000)
        turn("push now playing", { shell.nav.push(Page.NowPlaying) })
    }

    private val drawn = ArrayList<Triple<Long, String, Float>>()

    private val ticks = ArrayList<Long>()

    private val wrong = ArrayList<String>()

    private fun expect(ok: Boolean, what: String) {
        if (!ok) wrong += what
    }

    private fun junction(name: String, act: () -> Unit, push: Boolean, home: Boolean = false) {
        settle(1000)
        drawn.clear()
        ticks.clear()
        val start = rule.mainClock.currentTime
        act()
        while (rule.mainClock.currentTime - start < TURN) rule.mainClock.advanceTimeBy(1, ignoreFrameDuration = true)
        val reports = drawn.groupBy { it.first }
        var last: Triple<Long, String, Float>? = null
        val frames = ticks.mapNotNull { t ->
            reports[t]?.let { last = it.last() }
            last?.let { Triple(t, it.second, it.third) }
        }
        val out = frames.first().second
        val outs = frames.filter { it.second == out }
        val ins = frames.filter { it.second != out }
        val outEnd = if (push) Tok.motion_turnstileOutAngle else Tok.motion_turnstileInAngle
        val inStart = if (push) Tok.motion_turnstileInAngle else Tok.motion_turnstileOutAngle
        expect(abs(outs.last().third - outEnd) < POSE, "$name: the last frame of the outgoing page draws ${outs.last().third}, not $outEnd")
        expect(outs.none { abs(it.third - inStart) < POSE }, "$name: the outgoing page drawn at the in angle $inStart")
        expect(ins.size > (if (home) 0 else 1), "$name: ${ins.size} incoming frames")
        if (ins.size < (if (home) 1 else 2)) return
        if (home) {
            val seen = drawn.filter { it.second != out }.map { it.third }
            expect(seen.all { abs(it) < POSE }, "$name: home drawn turned ${seen.filter { abs(it) >= POSE }}")
        } else {
            expect(abs(ins[0].third - inStart) < POSE, "$name: the first frame of the incoming page draws ${ins[0].third}, not $inStart")
            expect(abs(ins[1].third) < abs(ins[0].third) - POSE, "$name: the in leg has not moved on its second frame (${ins[0].third}, ${ins[1].third})")
        }
    }

    @Test
    fun theJunctionFrameDrawsTheOutLegsEndPose() {
        val c = SimControl("ap80")
        var lyrics by mutableStateOf<LyricsFetch.Show>(LyricsFetch.Show.Off)
        val probe: (String, Float) -> Unit = { k, a -> drawn += Triple(frame, k, a) }
        val uptime = { rule.mainClock.currentTime }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LaunchedEffect(Unit) {
                while (true) withFrameMillis {
                    frame = it
                    ticks += it
                }
            }
            CompositionLocalProvider(LocalUptime provides uptime, LocalTurnProbe provides probe) {
                Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), c, shell, lyrics = lyrics) }
            }
        }
        settle(1500)
        junction("push settings", { shell.nav.push(Page.Settings) }, push = true)
        junction("push eq", { shell.nav.push(Page.Eq(0)) }, push = true)
        junction("pop to settings", { shell.nav.pop() }, push = false)
        junction("back to home", { shell.nav.pop() }, push = false, home = true)
        junction("push now playing", { shell.nav.push(Page.NowPlaying) }, push = true)
        junction("back to home from now playing", { shell.nav.pop() }, push = false, home = true)
        c.transport(Enums.TransportCmd.next)
        c.transport(Enums.TransportCmd.seek, seekMs = 61_000)
        c.mode = true
        val s = c.device.playback.state
        c.device = c.device.withState(s.copy(playing = false, positionMs = 61_000, lyrics = true, lyricsPlain = false, lyricsCrc = 1), SystemClock.elapsedRealtime())
        val lines = (0 until 8).map { LyrLine(54_000L + 4_000L * it, "line $it") }
        lyrics = LyricsFetch.Show.Lines(c.device.playback.state.track!!, LyricsFetch.Copy(1, Enums.LyricsKind.synced, lines))
        junction("lyrics open", { shell.nav.push(Page.NowPlaying) }, push = true)
        expect(rule.onAllNodes(hasTestTag("np.lyrics")).fetchSemanticsNodes().isNotEmpty(), "lyrics open: no lyrics view")
        assertTrue(wrong.joinToString("; "), wrong.isEmpty())
    }

    private class Return(val first: BufferedImage, val firstAt: Long, val outEnd: Long, val marks: List<List<Int>>, val drawn: List<Boolean>, val itemsAt: Map<String, List<Int>>, val ringAt: List<Int>)

    private fun homeReturn(name: String, act: () -> Unit, items: List<String>): Return {
        val start = rule.mainClock.currentTime
        act()
        var seen = frame
        var frames = 0
        var outStart = -1L
        var first: BufferedImage? = null
        var firstAt = -1L
        val marks = ArrayList<List<Int>>()
        val drawn = ArrayList<Boolean>()
        var itemsAt: Map<String, List<Int>>? = null
        var ringAt: List<Int>? = null
        while (rule.mainClock.currentTime - start < TURN + REST_AGAIN) {
            rule.mainClock.advanceTimeBy(1, ignoreFrameDuration = true)
            if (frame == seen) continue
            seen = frame
            if (++frames == LEG_START) outStart = frame
            val mark = bounds("home.watermark") ?: continue
            val img = capture()
            if (first == null) {
                first = img
                firstAt = frame
            }
            marks += crop(img, mark)
            drawn += drawnOutside(img, mark)
            val outEnd = outStart + Tok.motion_turnstileOut.toLong()
            if (itemsAt == null && frame >= outEnd + Tok.motion_homeEnter.toLong() + 2 * FRAME) itemsAt = items.associateWith { crop(img, text(it)) }
            if (ringAt == null && frame >= outEnd + Tok.motion_homeEnterPeek.toLong() + 2 * FRAME) ringAt = crop(img, bounds("home.ring")!!)
        }
        assertTrue("$name reached home", first != null && itemsAt != null && ringAt != null)
        return Return(first!!, firstAt, outStart + Tok.motion_turnstileOut.toLong(), marks, drawn, itemsAt!!, ringAt!!)
    }

    private fun checkReturn(name: String, r: Return, items: List<String>) {
        settle(1000)
        val rest = capture()
        val mark = crop(rest, bounds("home.watermark")!!)
        r.drawn.forEachIndexed { i, d -> assertTrue("$name frame $i draws more than the watermark", d) }
        r.marks.forEachIndexed { i, m -> assertEquals("$name frame $i is flat", mark, m) }
        val ratios = items.map { peak(r.first, bounds(it)!!).toFloat() / peak(rest, bounds(it)!!) }
        assertTrue("$name first frame item 0 at ${ratios[0]}", ratios[0] > ENTERED)
        ratios.forEachIndexed { i, x -> assertEquals("$name item $i stagger", ratios[0], x, NEAR) }
        val pose = Decel.transform(((r.firstAt - r.outEnd).toFloat() / Tok.motion_homeEnter).coerceIn(0f, 1f))
        assertEquals("$name first pose at ${r.firstAt - r.outEnd} ms", pose, ratios[0], NEAR)
        val ring = peak(r.first, bounds("home.ring")!!).toFloat() / peak(rest, bounds("home.ring")!!)
        assertEquals("$name first ring pose", homeRingEnter((r.firstAt - r.outEnd).toFloat()), ring, NEAR)
        items.forEach { assertEquals("$name $it at rest", crop(rest, text(it)), r.itemsAt.getValue(it)) }
        assertEquals("$name ring at rest", crop(rest, bounds("home.ring")!!), r.ringAt)
    }

    @Test
    fun aReturnToHomeDrawsHomeFromItsFirstFrame() {
        val c = SimControl("ap80")
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LaunchedEffect(Unit) { while (true) withFrameMillis { frame = it } }
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), c, shell) }
        }
        settle(1500)
        val items = homeItems(c.device, LinkView(Phase.LIVE, "AP80", everLive = true)).map { "home.${it.name}" }
        shell.nav.push(Page.Settings)
        settle(1500)
        checkReturn("settings pop", homeReturn("settings pop", { shell.nav.pop() }, items), items)
        rule.onNodeWithTag("home.music").performClick()
        settle(1500)
        checkReturn("music pop", homeReturn("music pop", { shell.nav.pop() }, items), items)
    }

    private companion object {
        const val LEG_START = 2
        const val ENTERED = 0.1f
        const val NEAR = 0.05f
        const val POSE = 0.5f
        const val BLANK = 8
        const val FRAME = 16L
        const val TURN = 800L
        const val MID = 400L
        const val REST = 700L
        const val REST_AGAIN = 500L
    }
}
