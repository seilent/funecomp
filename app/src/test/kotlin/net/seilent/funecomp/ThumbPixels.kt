package net.seilent.funecomp

import androidx.compose.ui.test.SemanticsNodeInteraction
import com.github.takahirom.roborazzi.captureRoboImage
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

internal fun SemanticsNodeInteraction.pixels(): BufferedImage {
    val f = File.createTempFile("thumb", ".png")
    captureRoboImage(f.path)
    return ImageIO.read(f).also { f.delete() }
}

private fun BufferedImage.red(x: Int, y: Int) = (getRGB(x, y) shr 16) and 0xff

internal fun thumbRun(img: BufferedImage, x: Int, gap: Int, from: Int = 0, to: Int = img.height): IntRange? {
    var a = -1
    var b = -1
    for (y in from until to) {
        if (img.red(x, y) - img.red(x - gap, y) > THUMB_STEP) {
            if (a < 0) a = y
            b = y
        }
    }
    return if (a < 0) null else a..b
}

internal fun thumbLevel(img: BufferedImage, x: Int, y: Int) = img.red(x, y) / 255f

private const val THUMB_STEP = 20
