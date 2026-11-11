package net.seilent.funecomp

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.ui.VolumeTilt
import net.seilent.funecomp.ui.platePose
import net.seilent.funecomp.ui.tiltMatrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeTiltPoseTest {
    private val rate = Math.toRadians(Tok.motion_volumeTiltRate.toDouble()).toFloat()

    @Test
    fun thePoseIsTheRateTimesTheTouchFromTheCentre() {
        for (nx in listOf(0f, 0.25f, 0.5f, 0.8f, 1f)) for (ny in listOf(0f, 0.3f, 0.5f, 1f)) {
            val p = platePose(nx, ny)
            assertEquals("$nx $ny rx", rate * (ny - 0.5f), p.rx, 1e-5f)
            assertEquals("$nx $ny ry", -rate * (nx - 0.5f), p.ry, 1e-5f)
        }
        val out = platePose(-0.4f, 1.7f)
        assertEquals(rate * 0.5f, out.rx, 1e-5f)
        assertEquals(rate * 0.5f, out.ry, 1e-5f)
    }

    @Test
    fun theMatrixTurnsThePlaneAboutTheCentreSeenFromTheEyeWithNoDepth() {
        val plate = Rect(0f, 0f, 1080f, 2400f)
        val eye = Tok.motion_volumeTiltEye * plate.width
        for (touch in listOf(Offset(100f, 300f), Offset(980f, 2200f), Offset(540f, 400f), Offset(200f, 1200f))) {
            val pose = platePose(touch.x / plate.width, touch.y / plate.height)
            val h = tiltMatrix(pose, 1f, plate.center.x, plate.center.y, eye)
            for (p in listOf(Offset(0f, 0f), Offset(1080f, 0f), Offset(0f, 2400f), Offset(1080f, 2400f), plate.center)) {
                val w = h[6] * p.x + h[7] * p.y + h[8]
                val got = Offset((h[0] * p.x + h[1] * p.y + h[2]) / w, (h[3] * p.x + h[4] * p.y + h[5]) / w)
                val want = tiltedPoint(p, touch, plate)
                assertEquals("$touch $p x", want.x, got.x, 0.5f)
                assertEquals("$touch $p y", want.y, got.y, 0.5f)
            }
        }
    }

    @Test
    fun theTiltHoldsAfterTheUpThenReturnsLinear() {
        val t = VolumeTilt()
        assertEquals(0f, t.k(0L), 0f)
        t.down(0.2f, 0.3f)
        assertEquals(1f, t.k(1_000L), 0f)
        t.move(0.4f, 0.6f)
        assertEquals(rate * 0.1f, t.pose.rx, 1e-5f)
        t.up(2_000L)
        val hold = Tok.motion_volumeTiltHold.toLong()
        val back = Tok.motion_volumeTiltReturn.toLong()
        assertEquals(1f, t.k(2_000L + hold), 0f)
        assertEquals(0.5f, t.k(2_000L + hold + back / 2), 1e-3f)
        assertEquals(0f, t.k(2_000L + hold + back), 0f)
        assertTrue(t.moving(2_000L + hold + back - 1))
        assertFalse(t.moving(2_000L + hold + back))
        t.move(0.9f, 0.9f)
        assertEquals("a move after the up keeps the pose", rate * 0.1f, t.pose.rx, 1e-5f)
    }
}
