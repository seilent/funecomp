package net.seilent.funecomp.art

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import net.seilent.funecomp.MediaLink
import net.seilent.funecomp.link.ArtGet
import net.seilent.funecomp.link.Session
import net.seilent.funecomp.state.CardView
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ThumbWorker(dir: File, private val link: () -> Session?, private val card: () -> CardView = { CardView() }, private val log: (String) -> Unit = {}) {
    private val thumbs = Thumbs(dir)
    private val exec = Executors.newSingleThreadScheduledExecutor { Thread(it, "thumbs").apply { isDaemon = true } }
    private val order = ArrayList<String>()
    private var running = false

    private val media = object : LinkedHashMap<String, Bitmap>(MEDIA_KEEP, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > MEDIA_KEEP
    }

    val images = mutableStateMapOf<String, ImageBitmap>()

    @Volatile var onMedia: (() -> Unit)? = null

    fun media(track: Long, px: Int): Bitmap? = synchronized(media) { media[Thumbs.key(track, Thumbs.bucket(px))] }

    fun image(track: Long, px: Int): ImageBitmap? = images[Thumbs.key(track, Thumbs.bucket(px))]

    fun rev(libRev: Long) = thumbs.rev(libRev)

    fun session() = thumbs.session()

    fun cardReady() {
        thumbs.cardReady()
        exec.execute { pump() }
    }

    fun want(slot: Int, track: Long, px: Int, album: String?) {
        val w = Thumbs.Want(slot, track, Thumbs.bucket(px), album)
        if (if (slot == Thumbs.MEDIA) synchronized(media) { media.containsKey(w.key) } else images.containsKey(w.key)) return
        val f = thumbs.want(w)
        exec.execute { if (f != null) show(w, f) else pump() }
    }

    private fun pump() {
        for ((w, f) in thumbs.ready()) show(w, f)
        if (running) return
        val s = link() ?: return
        val w = thumbs.next() ?: return
        running = true
        val epoch = card().epoch
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
            val c = card()
            when (val d = thumbs.done(w, got, c.down, epoch < c.epoch)) {
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
        if (w.slot == Thumbs.MEDIA) {
            val px = MediaLink.ART_PX
            val m = if (b.width <= px) b else Bitmap.createScaledBitmap(b, px, px * b.height / b.width.coerceAtLeast(1), true)
            synchronized(media) { media[k] = m }
            onMedia?.invoke()
            return
        }
        order.remove(k)
        order += k
        while (order.size > KEEP) images.remove(order.removeAt(0))
        images[k] = b.asImageBitmap()
    }

    companion object {
        private const val KEEP = 24
        private const val MEDIA_KEEP = 4
    }
}
