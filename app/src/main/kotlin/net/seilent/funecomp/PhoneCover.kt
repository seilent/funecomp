package net.seilent.funecomp

import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import net.seilent.funecomp.link.ArtGet
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class PhoneCover(
    private val get: (Long, () -> Boolean) -> ArtGet.Outcome?,
    private val exec: Executor = Executors.newSingleThreadExecutor { Thread(it, "phonecover").apply { isDaemon = true } },
    private val decode: (ByteArray) -> ImageBitmap? = { b -> BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() },
    private val log: (String) -> Unit = {},
) {
    private class Shown(val crc: Long, val image: ImageBitmap)

    private var shown by mutableStateOf<Shown?>(null)
    private var asked: Long? = null

    fun image(crc: Long): ImageBitmap? = shown?.takeIf { it.crc == crc }?.image

    fun state(s: State) {
        if (s.source != Enums.Source.phone) {
            clear()
            return
        }
        val crc = s.phoneArtCrc ?: return
        synchronized(this) {
            if (crc == asked) return
            asked = crc
        }
        exec.execute { fetch(crc) }
    }

    fun clear() = synchronized(this) {
        asked = null
        shown = null
    }

    private fun current(crc: Long) = synchronized(this) { asked == crc }

    private fun fetch(crc: Long) {
        if (!current(crc)) return
        val r = get(crc) { !current(crc) } ?: return forget(crc)
        when (r) {
            is ArtGet.Outcome.Image -> {
                val b = decode(r.jpeg) ?: return forget(crc)
                synchronized(this) {
                    if (asked != crc) return
                    shown = Shown(r.crc, b)
                }
                log("phone cover ${r.w}x${r.h} crc=${r.crc}")
            }
            is ArtGet.Outcome.Failed -> {
                log("phone cover crc=$crc -> ${r.code}")
                when (r.code) {
                    Err.staleRev -> synchronized(this) { if (asked == crc) shown = null }
                    Err.notFound -> {}
                    else -> forget(crc)
                }
            }
            is ArtGet.Outcome.Have, ArtGet.Outcome.Cancelled -> {}
        }
    }

    private fun forget(crc: Long) {
        synchronized(this) { if (asked == crc) asked = null }
    }

    companion object {
        const val PHONE_TRACK = 0L
    }
}
