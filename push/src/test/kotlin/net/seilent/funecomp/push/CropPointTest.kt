package net.seilent.funecomp.push

import net.seilent.funekit.artist.ArtCache
import net.seilent.funekit.artist.ArtCrops
import net.seilent.funekit.artist.ArtPicks
import net.seilent.funekit.artist.ArtistArt
import net.seilent.funekit.artist.AwtCodec
import net.seilent.funekit.artist.Crop
import net.seilent.funekit.artist.FakeTime
import net.seilent.funekit.artist.Fixtures
import net.seilent.funekit.artist.Images
import net.seilent.funekit.artist.Rect
import net.seilent.funekit.artist.Sources
import net.seilent.funekit.artist.TEST_AGENT
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File

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

    private fun art() = ArtistArt(http, codec, cache, { Sources.COMMONS }, TEST_AGENT, { time.now }, time, picks = picks)

    private fun full(a: ArtistArt) = FullSource(a.cache, a.codec) { k -> picks.byCache(k)?.let { picks.point(it) } }

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

    @Test
    fun aDefaultRePickOfTheSameLinkDropsTheLineAndSendsTheFaceFocus() {
        codec.face = { w, h -> w * 0.6 to h * 0.25 }
        val a = art()
        val p = pick()
        picks.setPoint(p, 120 to 333)
        a.store(p, halves())
        assertEquals(120 to 333, full(a).of(p.cacheKey)!!.focus)
        val again = pick()
        picks.setPoint(again, null)
        a.store(again, halves())
        picks.save()
        assertEquals("", File(tmp.root, "artcrop.tsv").readText())
        assertEquals(500 to 250, full(a).of(again.cacheKey)!!.focus)
    }

    @Test
    fun theFullSourceFocusIsThePointOverTheFace() {
        codec.face = { w, h -> w * 0.6 to h * 0.25 }
        val a = art()
        val p = pick()
        picks.setPoint(p, 120 to 333)
        a.store(p, halves())
        assertEquals(120 to 333, full(a).of(p.cacheKey)!!.focus)
        assertEquals(120 to 333, full(a).of(p.cacheKey)!!.focus)
    }

    @Test
    fun aNoFaceSourceMovedAcrossSendsTheThirdDown() {
        val a = art()
        val p = pick()
        picks.setPoint(p, Crop.point(1920, 1080, Rect(0, 0, 607, 1080), null))
        a.store(p, halves())
        assertEquals(158 to 333, full(a).of(p.cacheKey)!!.focus)
    }

}
