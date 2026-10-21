package net.seilent.funecomp

import android.app.Activity
import android.os.Looper
import android.view.Window
import net.seilent.funecomp.ui.RefreshPin
import net.seilent.funecomp.ui.RefreshPin.ModeInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class RefreshPinTest {
    private val flip7 = listOf(1 to 120.00001f, 2 to 80f, 3 to 60f, 4 to 48f, 5 to 30f, 6 to 24f, 7 to 10f).map { (id, hz) -> ModeInfo(id, 1080, 2520, hz) }
    private val cover = listOf(ModeInfo(8, 948, 1048, 120.00001f), ModeInfo(9, 948, 1048, 60f))
    private var modes = flip7 to flip7[2]
    private var reads = 0
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val window: Window = activity.window
    private val pin = RefreshPin(window) {
        reads++
        modes
    }

    private fun id() = window.attributes.preferredDisplayModeId

    private fun fold() {
        modes = cover to cover[1]
        ShadowDisplayManager.changeDisplay(0, "w361dp-h399dp-420dpi")
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun theFlip7ListGivesThe120Mode() {
        assertEquals(1, RefreshPin.top(flip7, flip7[2]))
        assertEquals(1, RefreshPin.top(flip7, flip7[5]))
    }

    @Test
    fun aSecondResolutionAt120IsNotPicked() {
        val modes = flip7.drop(1) + ModeInfo(20, 720, 1680, 120f)
        assertEquals(2, RefreshPin.top(modes, modes[1]))
    }

    @Test
    fun oneRateGivesZero() {
        val one = listOf(ModeInfo(8, 948, 1048, 60f), ModeInfo(30, 1080, 2520, 120f))
        assertEquals(0, RefreshPin.top(one, one[0]))
    }

    @Test
    fun aTieWithTheCurrentModeKeepsIt() {
        val modes = flip7 + ModeInfo(11, 1080, 2520, 120.00001f)
        assertEquals(11, RefreshPin.top(modes, modes.last()))
        assertEquals(1, RefreshPin.top(modes, flip7[2]))
    }

    @Test
    fun aSecondReleaseOfOneHandleKeepsTheOtherHeld() {
        val a = pin.hold()
        val b = pin.hold()
        assertEquals(1, id())
        a.release()
        a.release()
        assertEquals(1, id())
        b.release()
        assertEquals(0, id())
    }

    @Test
    fun aDisplayChangeAfterTheLastReleaseWritesNothing() {
        val h = pin.hold()
        fold()
        assertEquals(8, id())
        h.release()
        assertEquals(0, id())
        val before = reads
        modes = flip7 to flip7[2]
        ShadowDisplayManager.changeDisplay(0, "w411dp-h960dp-420dpi")
        shadowOf(Looper.getMainLooper()).idle()
        fold()
        assertEquals(0, id())
        assertEquals(before, reads)
    }

    @Test
    fun aDetachedDecorStopsListening() {
        pin.hold()
        assertEquals(1, id())
        val decor = window.decorView
        activity.windowManager.removeViewImmediate(decor)
        activity.windowManager.addView(decor, window.attributes)
        shadowOf(Looper.getMainLooper()).idle()
        val before = reads
        fold()
        assertEquals(1, id())
        assertEquals(before, reads)
    }
}
