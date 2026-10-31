package net.seilent.funecomp

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.Control
import net.seilent.funecomp.ui.LayoutTok
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.Ui
import net.seilent.funecomp.ui.npProgressY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class NowPlayingIdleTest {
    @get:Rule
    val rule = createComposeRule()

    private var size by mutableStateOf(SIZES.first { it.name == "411x960" })

    private val shell = Shell(Nav(listOf(Page.Home, Page.NowPlaying)))

    private lateinit var c: SimControl

    private fun start(wrap: (SimControl) -> Control = { it }): SimControl {
        c = SimControl("ap80")
        val control = wrap(c)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val s = size
            Box(Modifier.requiredSize(s.w.dp, s.h.dp)) { App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), control, shell, insets = s.bars) }
        }
        settle(1000)
        return c
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

    private fun until(at: Long) = settle((at - rule.mainClock.currentTime).coerceAtLeast(0))

    private fun exists(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun bounds(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun ui(): Ui {
        val np = bounds("np")
        return Ui.of(np.width, np.height)
    }

    private fun status() = if (exists("status.play")) "status.play" else "status.pause"

    private fun luma(tag: String): Int {
        val img = rule.onNodeWithTag(tag).pixels()
        System.gc()
        var m = 0
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val p = img.getRGB(x, y)
            m = maxOf(m, (299 * (p shr 16 and 0xff) + 587 * (p shr 8 and 0xff) + 114 * (p and 0xff)) / 1000)
        }
        return m
    }

    private fun dimmed(name: String, full: Int, now: Int) = assertEquals(name, Tok.motion_idleAlpha, now.toFloat() / full, DIM_TOLERANCE)

    private fun wake(): Long {
        val np = bounds("np")
        val at = rule.mainClock.currentTime
        rule.onRoot().performTouchInput {
            down(Offset(np.left + EMPTY_X, np.center.y))
            up()
        }
        return at
    }

    private fun idle(from: Long) = until(from + IDLE)

    private fun drag(from: Float, to: Float) {
        val line = bounds("np.art")
        val y = bounds("np").top + (ui().npProgressY + SEEK_MID) * ui().u
        rule.onRoot().performTouchInput {
            down(Offset(line.left + from * line.width, y))
            for (i in 1..DRAG_STEPS) moveTo(Offset(line.left + (from + (to - from) * i / DRAG_STEPS) * line.width, y))
            up()
        }
    }

    private class Seeks(private val sim: SimControl) : Control by sim {
        val seeks = ArrayList<Long?>()
        override fun transport(cmd: Int, seekMs: Long?, on: Boolean?, repeat: Int?) {
            if (cmd == Enums.TransportCmd.seek) seeks += seekMs
            sim.transport(cmd, seekMs, on, repeat)
        }
    }

    @Test
    fun theUnfocusedRingsAndStatusDimAfterTheIdleTimeAndTheFocusedRingStays() {
        start()
        for (s in SIZES) {
            size = s
            settle(1000)
            val at = wake()
            until(at + RESTORED)
            val st = status()
            val status = luma(st)
            val repeat = luma("np.repeat")
            val shuffle = luma("np.shuffle")
            until(at + Tok.motion_idle.toLong() - BEFORE_IDLE)
            assertEquals(s.name, status, luma(st))
            assertEquals(s.name, repeat, luma("np.repeat"))
            until(at + MID_FADE)
            val mid = luma(st).toFloat() / status
            assertTrue("${s.name} mid fade $mid", mid > Tok.motion_idleAlpha + DIM_TOLERANCE && mid < 1f - DIM_TOLERANCE)
            until(at + DIMMED)
            dimmed("${s.name} status", status, luma(st))
            dimmed("${s.name} repeat", repeat, luma("np.repeat"))
            assertEquals(s.name, shuffle, luma("np.shuffle"))
        }
    }

    @Test
    fun theFirstTouchOnTheBandOfAnIdlePageRestoresAndSeeksOnEveryWindow() {
        lateinit var seeks: Seeks
        val c = start { sim -> Seeks(sim).also { seeks = it } }
        val duration = c.device.playback.state.durationMs!!
        for (s in SIZES) {
            size = s
            settle(1000)
            val woke = wake()
            until(woke + RESTORED)
            val st = status()
            val status = luma(st)
            idle(woke)
            dimmed("${s.name} idle", status, luma(st))
            seeks.seeks.clear()
            drag(0.25f, 0.75f)
            settle(RESTORED)
            assertEquals(s.name, 1, seeks.seeks.size)
            assertEquals(s.name, 0.75f * duration, seeks.seeks.single()!!.toFloat(), 0.01f * duration)
            assertEquals(s.name, status, luma(st))
        }
    }

    @Test
    fun theFirstTouchOnARingOfAnIdlePageRestoresAndActs() {
        val c = start()
        for (s in SIZES) {
            size = s
            settle(1000)
            val woke = rule.mainClock.currentTime
            rule.onNodeWithTag("np.repeat").performTouchInput { click() }
            until(woke + RESTORED)
            val st = status()
            val status = luma(st)
            val shuffle = luma("np.shuffle")
            idle(woke)
            dimmed("${s.name} idle", shuffle, luma("np.shuffle"))
            c.transports.clear()
            val at = rule.mainClock.currentTime
            rule.onNodeWithTag("np.repeat").performTouchInput { click() }
            until(at + RESTORED)
            assertEquals(s.name, listOf(Enums.TransportCmd.repeat), c.transports)
            assertEquals(s.name, status, luma(st))
            assertEquals(s.name, shuffle, luma("np.shuffle"))
        }
    }

    private companion object {
        val SEEK_MID = (LayoutTok.npSeekBandBottom - LayoutTok.npProgressY) / 2
        const val DRAG_STEPS = 5
        const val EMPTY_X = 2f
        const val BEFORE_IDLE = 100L
        const val FRAMES = 64L
        val RESTORED = Tok.motion_idleFade.toLong() + FRAMES
        val DIMMED = (Tok.motion_idle + Tok.motion_idleFade).toLong() + FRAMES
        val IDLE = DIMMED + 100L
        val MID_FADE = Tok.motion_idle.toLong() + FRAMES / 2 + Tok.motion_idleFade.toLong() / 2
        const val DIM_TOLERANCE = 0.02f
    }
}
