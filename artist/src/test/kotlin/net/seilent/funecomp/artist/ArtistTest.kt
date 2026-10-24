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
    fun cropIsCentredAcrossAndTheFaceOnlySetsTheHeight() {
        assertEquals(Rect(280, 0, 640, 800), Crop.rect(1200, 800, 384, 480, faceY = 400.0))
        assertEquals(Rect(280, 0, 640, 800), Crop.rect(1200, 800, 384, 480))
        val top = Crop.rect(800, 2000, 384, 480, faceY = 50.0)
        assertEquals(0 to 0, top.x to top.y)
        assertEquals(1000, Crop.rect(800, 2000, 384, 480, faceY = 1950.0).y)
        assertEquals(1200, Crop.rect(800, 2000, 384, 480, faceY = 1200.0).let { it.y + it.h / 2 })
    }

    @Test
    fun aFaceAtTheLeftEdgeGivesACentredWindow() {
        val face = 26.4 / 640 * 1680 to 593.8 / 640 * 1680
        val r = Backdrop.crop(1680, 1680, 240, 320, face)!!
        assertEquals((1680 - r.w) / 2, r.x)
        assertEquals(0, Backdrop.crop(800, 2000, 384, 480, 700.0 to 50.0)!!.y)
        assertEquals(1000, Backdrop.crop(800, 2000, 384, 480, 10.0 to 1950.0)!!.y)
        val phone = Crop.rect(1680, 1680, 1080, 2520, face.second)
        assertEquals(Rect(480, 0, 720, 1680), phone)
        val upscaled = Backdrop.render(object : Picture {
            override val w = 300
            override val h = 300
            override fun face() = 2.0 to 150.0
            override fun jpeg(crop: Rect, w: Int, h: Int, quality: Int): ByteArray {
                assertEquals((300 - crop.w) / 2, crop.x)
                return ByteArray(10)
            }
        }, 480, 800, upscale = true)
        assertEquals(10, upscaled?.size)
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
