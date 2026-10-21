package net.seilent.funecomp.artist

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GalleryStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val http = Fixtures()
    private val time = FakeTime()
    private val logs = ArrayList<String>()
    private val dir get() = File(tmp.root, "pickimg")
    private val tsv get() = File(tmp.root, "artpick.tsv")
    private val photo = Images.jpeg(Images.photo(600, 800), 92)
    private val other = Images.jpeg(Images.photo(600, 800, 3), 92)

    private fun pick(src: ByteArray, name: String = "TSUKI", kind: String = ArtPicks.IMAGE, was: String = "") = ArtPicks.Pick(
        "name:${Names.fold(name)}", null, name, kind, Sources.GALLERY, "", GalleryStore.ID_PREFIX + ArtCache.sha256(src), ArtCache.sha256(src), null, null, time.now++, was = was,
    )

    private fun art(picks: ArtPicks, store: GalleryStore = GalleryStore(dir)) =
        ArtistArt(http, AwtCodec(), ArtCache(File(tmp.root, "art")), { Sources.NONE }, "0.1.0", { time.now }, time.sleep, { time.now }, picks = picks, gallery = store, log = { logs += it })

    @Test
    fun aPutIsReadBackByItsSha() {
        val g = GalleryStore(dir)
        val sha = g.put(photo)
        assertEquals(ArtCache.sha256(photo), sha)
        assertTrue(g.has(sha))
        assertArrayEquals(photo, g.get(sha))
        assertEquals(listOf("$sha.jpg"), dir.list()!!.toList())
        assertNull(g.get(""))
        assertFalse(g.has(ArtCache.sha256(other)))
    }

    @Test
    fun aPruneKeepsEveryNamedShaABlockedOneIncluded() {
        val picks = ArtPicks(tsv)
        val g = GalleryStore(dir)
        val kept = pick(photo)
        val blocked = pick(other, "Other", ArtPicks.NONE, ArtPicks.IMAGE)
        picks.add(kept)
        picks.add(blocked)
        picks.save()
        g.put(photo)
        g.put(other)
        val stray = g.put(Images.jpeg(Images.photo(600, 800, 5), 92))
        val a = art(ArtPicks(tsv), g)
        assertEquals(1, a.pruneGallery())
        assertTrue(g.has(kept.sha))
        assertTrue(g.has(blocked.sha))
        assertFalse(g.has(stray))
    }

    @Test
    fun noPruneAfterASkippedLineOrAMissingPickFile() {
        val g = GalleryStore(dir)
        val sha = g.put(photo)
        val a = art(ArtPicks(tsv), g)
        assertNull(a.pruneGallery())
        assertTrue(g.has(sha))
        tsv.writeText("pick\tbroken\n")
        val b = art(ArtPicks(tsv), g)
        assertNull(b.pruneGallery())
        assertTrue(g.has(sha))
        assertEquals(listOf("art pick prune skipped", "art pick prune skipped"), logs)
    }

    @Test
    fun aGalleryPickIsServedFromTheStoreWithNoRequest() {
        val picks = ArtPicks(tsv)
        val g = GalleryStore(dir)
        val p = pick(photo)
        picks.add(p)
        g.put(photo)
        val a = art(picks, g)
        val art = a.get("TSUKI", null, 240, 320)
        assertNotNull(art)
        assertEquals(Sources.GALLERY, art!!.source)
        assertTrue(http.calls.isEmpty())
        ArtCache(File(tmp.root, "art")).drop(p.cacheKey)
        assertNotNull(a.get("TSUKI", null, 240, 320))
        assertTrue(http.calls.isEmpty())
        assertTrue(a.galleryMissing().isEmpty())
    }

    @Test
    fun aMissingFileShowsNoImageWithNoFailedAtAndLogsOnce() {
        val picks = ArtPicks(tsv)
        val p = pick(photo)
        picks.add(p)
        val a = art(picks)
        assertNull(a.get("TSUKI", null, 240, 320))
        assertNull(a.get("TSUKI", null, 240, 320))
        assertNull(picks.list().single().failedAt)
        assertTrue(http.calls.isEmpty())
        assertEquals(listOf("art pick gallery missing TSUKI"), logs)
        assertEquals(setOf(p.sha), a.galleryMissing())
    }

    @Test
    fun aGalleryOfferIsCheckedAndKeyedByItsBytes() {
        val a = art(ArtPicks(tsv))
        val o = (a.galleryOffer(photo, "IMG_1.jpg") as Offered.Ok).offers.single()
        assertEquals(Sources.GALLERY, o.source)
        assertEquals("gallery", o.label)
        assertEquals("", o.url)
        assertEquals("gallery:" + ArtCache.sha256(photo), o.id)
        assertEquals("IMG_1.jpg", o.title)
        assertNull(o.credit)
        assertEquals(PickLink.TOO_SMALL, (a.galleryOffer(Images.jpeg(Images.photo(300, 200), 92), "") as Offered.Refused).reason)
        assertFalse(dir.exists())
    }
}
