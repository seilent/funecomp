package net.seilent.funecomp

import android.content.Context
import net.seilent.funecomp.artist.AppPair
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.Response
import org.junit.Assert.assertEquals
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

val ZEROS = AppPair("0".repeat(32), "0".repeat(32))

class Refusing : Http {
    val asked = AtomicInteger()

    override fun get(url: String, headers: Map<String, String>): Response {
        asked.incrementAndGet()
        throw IOException("refused")
    }

    override fun post(url: String, headers: Map<String, String>, body: ByteArray): Response {
        asked.incrementAndGet()
        throw IOException("refused")
    }
}

private var refusing = Refusing()

fun scrobblesOff(c: Context) {
    refusing = Refusing()
    Scrobbles.init(c, AppPair.EMPTY, refusing) {}
}

fun scrobblesReset() {
    Scrobbles.reset()
    assertEquals("scrobble requests", 0, refusing.asked.get())
}
