package net.seilent.funecomp.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Properties

class ArtistTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun cropKeepsTargetAspectInsideTheImage() {
        val r = Crop.rect(1200, 800, 384, 480)
        assertEquals(Rect(280, 0, 640, 800), r)
        val tall = Crop.rect(800, 2000, 384, 480)
        assertEquals(800, tall.w)
        assertEquals(1000, tall.h)
        assertEquals((2000 / 3.0 - 500).toInt(), tall.y)
        val screen = Crop.rect(1200, 800, 360, 480)
        assertEquals(600, screen.w)
        assertEquals(800, screen.h)
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

    @Test
    fun noMbidNamesAreDroppedOnceForTheKanaSearch() {
        val dir = tmp.newFolder("art")
        val f = java.io.File(dir, "names.properties")
        val mbid = "a74b1b7f-71a5-4011-9441-d0b5e4122711"
        f.writeText("むと=5\nradiohead=6 $mbid\n")
        val c = ArtCache(dir)
        assertNull(c.mbid("むと"))
        assertEquals(mbid, c.mbid("Radiohead")!!.mbid)
        assertNull(c.mbid(".kana"))
        val saved = Properties().apply { f.reader().use { load(it) } }
        assertEquals("1", saved.getProperty(".kana"))
        assertNull(saved.getProperty("むと"))
        c.putMbid("むと", null, 7)
        c.putMbid(".kana", mbid, 8)
        val again = ArtCache(dir)
        assertEquals(7L, again.mbid("むと")!!.at)
        assertNull(again.mbid("むと")!!.mbid)
        assertNull(again.mbid(".kana"))
        assertEquals("1", Properties().apply { f.reader().use { load(it) } }.getProperty(".kana"))
    }
}
