package net.seilent.funecomp.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

class CropPointTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val url = "https://example.org/halves.jpg"
    private val http = Fixtures()
    private val time = FakeTime()
    private val codec = AwtCodec()
    private val cache by lazy { ArtCache(tmp.newFolder("art")) }
    private val logs = ArrayList<String>()
    private fun crops() = ArtCrops(File(tmp.root, "artcrop.tsv")) { logs += it }
    private lateinit var picks: ArtPicks

    @Before
    fun setUp() {
        picks = ArtPicks(File(tmp.root, "artpick.tsv"), crops = crops())
    }

    private fun art() = ArtistArt(http, codec, cache, { Sources.COMMONS }, "0.1.0", { time.now }, time, picks = picks)

    private fun pick(id: String = url, at: Long = time.now++): ArtPicks.Pick {
        val (k, k2) = ArtPicks.keys("Halves", null, false)
        return ArtPicks.Pick(k, k2, "Halves", ArtPicks.IMAGE, Sources.LINK, url, id, "", null, null, at).also { picks.add(it) }
    }

    private fun halves(w: Int = 1920, h: Int = 1080): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(220, 20, 20)
        g.fillRect(0, 0, w / 2, h)
        g.color = Color(20, 20, 220)
        g.fillRect(w / 2, 0, w - w / 2, h)
        g.dispose()
        return Images.jpeg(img, 95)
    }

    private fun centre(jpeg: ByteArray): Color = ImageIO.read(ByteArrayInputStream(jpeg)).let { Color(it.getRGB(it.width / 2, it.height / 2)) }

    private fun red(c: Color) = c.red > 150 && c.blue < 100

    private fun blue(c: Color) = c.blue > 150 && c.red < 100

    @Test
    fun aPointCentresTheWindowOnTheFreeAxisClampedAtTheEdges() {
        assertEquals(Rect(620, 0, 680, 1210), Crop.rect(1920, 1210, 720, 1280))
        assertEquals(Rect(0, 0, 680, 1210), Crop.rect(1920, 1210, 720, 1280, null, 0 to 500))
        assertEquals(Rect(140, 0, 680, 1210), Crop.rect(1920, 1210, 720, 1280, null, 250 to 500))
        assertEquals(Rect(1240, 0, 680, 1210), Crop.rect(1920, 1210, 720, 1280, null, 1000 to 500))
        assertEquals(Rect(0, 0, 720, 1280), Crop.rect(720, 1800, 720, 1280))
        assertEquals(Rect(0, 0, 720, 1280), Crop.rect(720, 1800, 720, 1280, 900.0, 500 to 0))
        assertEquals(Rect(0, 260, 720, 1280), Crop.rect(720, 1800, 720, 1280, 100.0, 500 to 500))
        assertEquals(Rect(0, 520, 720, 1280), Crop.rect(720, 1800, 720, 1280, null, 500 to 1000))
    }

    @Test
    fun aPointKeepsTheAutomaticValueOnTheAxisThatDidNotMove() {
        assertEquals(177 to 333, Crop.point(1920, 1210, Rect(0, 0, 680, 1210), null))
        assertEquals(500 to 250, Crop.point(1920, 1210, Rect(620, 0, 680, 1210), 302.5))
        assertEquals(500 to 644, Crop.point(720, 1800, Rect(0, 520, 720, 1280), 100.0))
        assertEquals(500 to 333, Crop.point(720, 720, Rect(0, 0, 720, 720), null))
    }

    @Test
    fun aPointIsStoredForItsPickAndReadBackAfterARestart() {
        val p = pick()
        picks.setPoint(p, 120 to 333)
        picks.save()
        assertEquals("crop\tname:halves\t$url\t${p.at}\t120\t333\n", File(tmp.root, "artcrop.tsv").readText())
        picks = ArtPicks(File(tmp.root, "artpick.tsv"), crops = crops())
        assertEquals(120 to 333, picks.point(picks.of(null, "Halves")!!))
    }

    @Test
    fun aLineForAnotherIdOrAnotherAtIsIgnored() {
        val p = pick()
        picks.setPoint(p, 120 to 333)
        assertNull(picks.point(p.copy(id = "https://example.org/other.jpg")))
        assertNull(picks.point(p.copy(at = p.at + 1)))
        assertEquals(120 to 333, picks.point(p))
    }

    @Test
    fun aMalformedLineIsSkippedAndLogged() {
        File(tmp.root, "artcrop.tsv").writeText("crop\tname:halves\t$url\t5\t1200\t333\ncrop\tname:halves\t$url\tx\t1\t2\ncrop\tname:halves\t$url\t5\t1\t1001\ncrop\tname:halves\t$url\t5\t1\t2\n")
        val c = crops()
        assertEquals(3, c.skipped)
        assertEquals(3, logs.size)
        assertTrue(logs.all { it.startsWith("artcrop skipped line") })
        assertEquals("crop\tname:halves\t$url\t5\t1\t2\n", c.encode())
    }

    @Test
    fun aDefaultRePickOfTheSameLinkDropsTheLineAndSendsTheFaceFocus() {
        codec.face = { w, h -> w * 0.6 to h * 0.25 }
        val a = art()
        val p = pick()
        picks.setPoint(p, 120 to 333)
        a.store(p, halves())
        assertEquals(120 to 333, a.full.of(p.cacheKey)!!.focus)
        val again = pick()
        picks.setPoint(again, null)
        a.store(again, halves())
        picks.save()
        assertEquals("", File(tmp.root, "artcrop.tsv").readText())
        assertEquals(500 to 250, a.full.of(again.cacheKey)!!.focus)
    }

    @Test
    fun aReplacementWithAnotherIdOrAtAndAnUndoDropTheLine() {
        val p = pick()
        picks.setPoint(p, 120 to 333)
        pick(id = "https://example.org/other.jpg", at = p.at)
        picks.add(p)
        assertNull(picks.point(p))
        picks.setPoint(p, 120 to 333)
        picks.remove(p)
        picks.add(p)
        assertNull(picks.point(p))
        picks.save()
        assertEquals("", File(tmp.root, "artcrop.tsv").readText())
    }

    @Test
    fun aBlockedPickHasNoPointAndGetsItBackWhenUnblocked() {
        val p = pick()
        picks.setPoint(p, 120 to 333)
        val blocked = p.copy(kind = ArtPicks.NONE, was = p.kind)
        picks.add(blocked)
        assertNull(picks.point(blocked))
        picks.add(blocked.copy(kind = blocked.was, was = ""))
        assertEquals(120 to 333, picks.point(picks.of(null, "Halves")!!))
    }

    @Test
    fun theFullSourceFocusIsThePointOverTheFace() {
        codec.face = { w, h -> w * 0.6 to h * 0.25 }
        val a = art()
        val p = pick()
        picks.setPoint(p, 120 to 333)
        a.store(p, halves())
        assertEquals(120 to 333, a.full.of(p.cacheKey)!!.focus)
        assertEquals(120 to 333, a.full.of(p.cacheKey)!!.focus)
    }

    @Test
    fun aNoFaceSourceMovedAcrossSendsTheThirdDown() {
        val a = art()
        val p = pick()
        picks.setPoint(p, Crop.point(1920, 1080, Rect(0, 0, 607, 1080), null))
        a.store(p, halves())
        assertEquals(158 to 333, a.full.of(p.cacheKey)!!.focus)
    }

    @Test
    fun aStoredPickRendersAtItsPoint() {
        val a = art()
        val left = pick()
        picks.setPoint(left, 0 to 333)
        a.store(left, halves())
        assertTrue(red(centre(a.render(cache.hit(left.cacheKey)!!, 360, 640)!!)))
        val right = pick()
        picks.setPoint(right, 1000 to 333)
        a.store(right, halves())
        assertTrue(blue(centre(a.render(cache.hit(right.cacheKey)!!, 360, 640)!!)))
    }

    @Test
    fun aGalleryPickRendersAtItsPoint() {
        val g = GalleryStore(File(tmp.root, "pickimg"))
        val sha = g.put(halves())
        val (k, k2) = ArtPicks.keys("Halves", null, false)
        val p = ArtPicks.Pick(k, k2, "Halves", ArtPicks.IMAGE, Sources.GALLERY, "", GalleryStore.ID_PREFIX + sha, sha, null, null, time.now++).also { picks.add(it) }
        picks.setPoint(p, 1000 to 333)
        val a = ArtistArt(http, codec, cache, { Sources.COMMONS }, "0.1.0", { time.now }, time, picks = picks, gallery = g)
        assertTrue(blue(centre(a.get("Halves", null, 360, 640)!!.jpeg)))
    }

    @Test
    fun aFailedCropWriteStillSavesThePicksAndIsLogged() {
        File(tmp.root, "artcrop.tsv.tmp").mkdirs()
        val saved = ArrayList<String>()
        val own = ArtPicks(File(tmp.root, "artpick.tsv"), crops()) { saved += it }
        val (k, k2) = ArtPicks.keys("Halves", null, false)
        val p = ArtPicks.Pick(k, k2, "Halves", ArtPicks.IMAGE, Sources.LINK, url, url, "", null, null, time.now++).also { own.add(it) }
        own.setPoint(p, 120 to 333)
        own.save()
        assertEquals(listOf(p), ArtPicks(File(tmp.root, "artpick.tsv")).list())
        assertTrue(saved.single().startsWith("artcrop save failed"))
    }

    @Test
    fun aFetchedPickRendersAtItsPoint() {
        http.queued.getOrPut(url) { ArrayDeque() } += Response(200, halves())
        val p = pick()
        picks.setPoint(p, 1000 to 333)
        assertTrue(blue(centre(art().get("Halves", null, 360, 640)!!.jpeg)))
    }
}
