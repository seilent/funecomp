package net.seilent.funecomp.art

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import net.seilent.funecomp.artist.ArtistArt
import net.seilent.funecomp.artist.Backdrop
import net.seilent.funecomp.proto.gen.Tok
import java.io.ByteArrayOutputStream
import java.io.IOException

class Gallery(val jpeg: ByteArray?, val name: String)

object GalleryRead {
    val MAX_SIDE = maxOf(ArtistArt.PICK_W, ArtistArt.PICK_H) * 4

    fun read(resolver: ContentResolver, uri: Uri): Gallery? {
        val name = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: ""
        } catch (e: RuntimeException) {
            ""
        }
        return try {
            Gallery(jpeg(ImageDecoder.createSource(resolver, uri)), name)
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    fun jpeg(source: ImageDecoder.Source): ByteArray? {
        val bmp = try {
            ImageDecoder.decodeBitmap(source) { d, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val side = maxOf(w, h)
                if (side > MAX_SIDE) d.setTargetSize(maxOf(1, w * MAX_SIDE / side), maxOf(1, h * MAX_SIDE / side))
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: ImageDecoder.DecodeException) {
            return null
        }
        val flat = if (bmp.hasAlpha()) {
            Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888).also { out ->
                Canvas(out).apply {
                    drawColor(Tok.color_black.toInt())
                    drawBitmap(bmp, 0f, 0f, null)
                }
                bmp.recycle()
            }
        } else {
            bmp
        }
        val o = ByteArrayOutputStream()
        flat.compress(Bitmap.CompressFormat.JPEG, Backdrop.qualities.first(), o)
        flat.recycle()
        return o.toByteArray()
    }
}
