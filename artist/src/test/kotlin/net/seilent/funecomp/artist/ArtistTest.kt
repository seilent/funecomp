package net.seilent.funecomp.artist

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistTest {
    @Test
    fun cropKeepsTargetAspectInsideTheImage() {
        val r = Crop.rect(1200, 800, 384, 480)
        assertEquals(Rect(280, 0, 640, 800), r)
        val tall = Crop.rect(800, 2000, 384, 480)
        assertEquals(800, tall.w)
        assertEquals(1000, tall.h)
        assertEquals((2000 / 3.0 - 500).toInt(), tall.y)
    }

    @Test
    fun cropCentersOnTheFaceAndClamps() {
        assertEquals(Rect(0, 0, 640, 800), Crop.rect(1200, 800, 384, 480, faceX = 100.0, faceY = 400.0))
        assertEquals(Rect(560, 0, 640, 800), Crop.rect(1200, 800, 384, 480, faceX = 1150.0, faceY = 400.0))
        val top = Crop.rect(800, 2000, 384, 480, faceX = 400.0, faceY = 50.0)
        assertEquals(0, top.y)
    }

    @Test
    fun limiterSpacesRequests() {
        var t = 0L
        val l = RateLimiter(1000) { t }
        assertEquals(0, l.reserve())
        assertEquals(1000, l.reserve())
        assertEquals(2000, l.reserve())
        t = 5000
        assertEquals(0, l.reserve())
        l.backoff(10_000)
        assertEquals(10_000, l.reserve())
        assertEquals(2000, RateLimiter.perMinute(30) { 0 }.let { it.reserve(); it.reserve() })
    }
}
