package net.seilent.funecomp

import net.seilent.funecomp.art.BitmapCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BitmapCodecTest {
    private fun jpeg(w: Int, h: Int): ByteArray = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()

    @Test
    fun aLargePickDecodesWithinTheSideSquared() {
        val p = BitmapCodec().decode(jpeg(8000, 6000), 4096)!!
        try {
            assertTrue("${p.w}x${p.h}", p.w.toLong() * p.h <= 4096L * 4096)
            assertEquals(4000 to 3000, p.w to p.h)
        } finally {
            p.release()
        }
    }

    @Test
    fun theDecodeSideCoversTheWindowAndIsCapped() {
        Remote.window(RuntimeEnvironment.getApplication(), 1080, 2520)
        assertEquals(3361, Remote.decodeSide(jpeg(3840, 2880)))
        assertEquals(1680, Remote.decodeSide(jpeg(1680, 1680)))
        assertEquals(4096, Remote.decodeSide(jpeg(1080, 20000)))
    }
}
