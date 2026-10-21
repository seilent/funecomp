package net.seilent.funecomp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import net.seilent.funecomp.art.GalleryRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class GalleryReadTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun file(name: String, bmp: Bitmap, format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG): File =
        File(context.cacheDir, name).also { f -> f.outputStream().use { bmp.compress(format, 95, it) } }

    private fun marked(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
        Canvas(b).apply {
            drawColor(Color.BLUE)
            drawRect(0f, 0f, w.toFloat(), h / 10f, android.graphics.Paint().apply { color = Color.RED })
        }
    }

    private fun decoded(f: File): Bitmap {
        val out = GalleryRead.jpeg(ImageDecoder.createSource(f))
        assertNotNull(out)
        return BitmapFactory.decodeByteArray(out, 0, out!!.size)
    }

    @Test
    fun anOrientationSixPhotoComesOutUprightWithNoExifLeft() {
        val f = file("rot6.jpg", marked(400, 600))
        ExifInterface(f.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "35/1,40/1,0/1")
            saveAttributes()
        }
        val out = GalleryRead.jpeg(ImageDecoder.createSource(f))!!
        val b = BitmapFactory.decodeByteArray(out, 0, out.size)
        assertEquals(600, b.width)
        assertEquals(400, b.height)
        assertTrue(Color.red(b.getPixel(b.width - 5, b.height / 2)) > 200)
        assertTrue(Color.blue(b.getPixel(5, b.height / 2)) > 200)
        val exif = ExifInterface(ByteArrayInputStream(out))
        assertEquals(ExifInterface.ORIENTATION_UNDEFINED, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED))
        assertEquals(null, exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
    }

    @Test
    fun anAlphaPngComesOutOnBlack() {
        val clear = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.TRANSPARENT) }
        val b = decoded(file("clear.png", clear, Bitmap.CompressFormat.PNG))
        val p = b.getPixel(200, 300)
        assertTrue(Color.red(p) < 10 && Color.green(p) < 10 && Color.blue(p) < 10)
    }

    @Test
    fun aLargePhotoIsBoundedAndASmallOneKeepsItsSize() {
        val big = decoded(file("big.jpg", marked(4000, 3000)))
        assertEquals(1920, big.width)
        assertEquals(1440, big.height)
        val small = decoded(file("small.jpg", marked(800, 600)))
        assertEquals(800, small.width)
        assertEquals(600, small.height)
    }

    @Test
    fun aFileThatIsNotAnImageIsRefusedAndAMissingOneCloses() {
        val f = File(context.cacheDir, "text.jpg").apply { writeText("not an image") }
        val g = GalleryRead.read(context.contentResolver, Uri.fromFile(f))
        assertNotNull(g)
        assertEquals(null, g!!.jpeg)
        assertEquals(null, GalleryRead.read(context.contentResolver, Uri.fromFile(File(context.cacheDir, "gone.jpg"))))
    }
}
