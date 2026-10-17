package net.seilent.funecomp.art

import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import net.seilent.funecomp.link.ArtGet
import net.seilent.funecomp.link.Session
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ThumbWorker(dir: File, private val link: () -> Session?, private val log: (String) -> Unit = {}) {
    private val thumbs = Thumbs(dir)
    private val exec = Executors.newSingleThreadScheduledExecutor { Thread(it, "thumbs").apply { isDaemon = true } }
    private val order = ArrayList<String>()
    private var running = false

    val images = mutableStateMapOf<String, ImageBitmap>()

    fun image(track: Long, px: Int): ImageBitmap? = images[Thumbs.key(track, Thumbs.bucket(px))]

    fun rev(libRev: Long) = thumbs.rev(libRev)

    fun session() = thumbs.session()

    fun want(slot: Int, track: Long, px: Int, album: String?) {
        val w = Thumbs.Want(slot, track, Thumbs.bucket(px), album)
        if (images.containsKey(w.key)) return
        val f = thumbs.want(w)
        exec.execute { if (f != null) show(w, f) else pump() }
    }

    fun file(track: Long, px: Int): File? = thumbs.cached(track, Thumbs.bucket(px))

    private fun pump() {
        if (running) return
        val s = link() ?: return
        val w = thumbs.next() ?: return
        running = true
        val got = try {
            when (val r = ArtGet(s) { thumbs.superseded(w) }.run(w.track, w.px, thumbs.have(w))) {
                is ArtGet.Outcome.Image -> Thumbs.Got.Image(r.crc, r.jpeg)
                is ArtGet.Outcome.Have -> Thumbs.Got.Have(r.crc)
                is ArtGet.Outcome.Failed -> Thumbs.Got.Failed(r.code)
                ArtGet.Outcome.Cancelled -> null
            }
        } catch (e: RuntimeException) {
            log("thumb ${java.lang.Long.toUnsignedString(w.track)}: $e")
            null
        } finally {
            running = false
        }
        if (got != null) {
            when (val d = thumbs.done(w, got)) {
                is Thumbs.Done.Ready -> show(w, d.file)
                is Thumbs.Done.Retry -> exec.schedule({ if (thumbs.want(w) == null) pump() }, d.afterMs, TimeUnit.MILLISECONDS)
                else -> {}
            }
            log("thumb ${java.lang.Long.toUnsignedString(w.track)} px=${w.px} -> ${got.javaClass.simpleName.lowercase()}")
        }
        exec.execute { pump() }
    }

    private fun show(w: Thumbs.Want, f: File) {
        val b = BitmapFactory.decodeFile(f.path) ?: return
        val k = w.key
        order.remove(k)
        order += k
        while (order.size > KEEP) images.remove(order.removeAt(0))
        images[k] = b.asImageBitmap()
    }

    companion object {
        private const val KEEP = 24
    }
}
