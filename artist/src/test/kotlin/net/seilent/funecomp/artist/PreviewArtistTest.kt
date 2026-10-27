package net.seilent.funecomp.artist

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PreviewArtistTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val mbid = "2e8436af-0604-42c6-84d6-68f8f5b4b93d"
    private val thumb = "https://r2.theaudiodb.com/images/media/artist/thumb/p.jpg"
    private val fanart = "https://r2.theaudiodb.com/images/media/artist/fanart/f.jpg"
    private val dz = "https://cdn-images.dzcdn.net/images/artist/248af7d86fec19be1b95301b3c7c1926/1000x1000-000000-80-0-0.jpg"
    private val thumbBytes = Images.jpeg(Images.photo(600, 800, 1), 92)
    private val fanartBytes = Images.jpeg(Images.photo(600, 800, 2), 92)
    private val cached = Images.jpeg(Images.photo(600, 800, 3), 92)
    private val calls = ArrayList<String>()
    private val time = FakeTime()
    private var sources = Sources.COMMONS.copy(theAudioDb = true)
    private val blocks = ArtBlocks()
    private val dir get() = File(tmp.root, "art")
    private val cache get() = ArtCache(dir)

    private val http = Http { url, _ ->
        calls += url
        when {
            url.contains("/ws/2/artist/$mbid") -> Response(200, "{\"id\":\"$mbid\",\"name\":\"tsuki\",\"relations\":[]}".toByteArray())
            url.contains("artist-mb.php?i=$mbid") -> Response(200, "{\"artists\":[{\"strArtistThumb\":\"$thumb\",\"strArtistFanart\":\"$fanart\"}]}".toByteArray())
            url.contains("api.deezer.com/search/artist") -> Response(200, "{\"data\":[{\"name\":\"TSUKI\",\"nb_fan\":1,\"picture_xl\":\"$dz\"}]}".toByteArray())
            url == thumb -> Response(200, thumbBytes)
            url == fanart -> Response(200, fanartBytes)
            url == dz -> Response(200, Images.jpeg(Images.photo(600, 800, 4), 92))
            else -> Response(404, ByteArray(0))
        }
    }

    private fun art() = ArtistArt(http, AwtCodec(), cache, { sources }, "0.1.0", { time.now }, time, blocks = blocks)

    private fun files(): Map<String, String> = dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to ArtCache.sha256(it.readBytes()) }

    private fun entry() = ArtCache.Entry(mbid, "TSUKI", mbid, Sources.THE_AUDIO_DB, null, "https://r2.theaudiodb.com/images/media/artist/thumb/old.jpg", time.now, sha = ArtCache.sha256(cached))

    @Test
    fun aCachedHitIsShownWithNoRequestAndNoRenderedCopyWritten() {
        cache.putHit(entry(), cached)
        val before = files()
        assertArrayEquals(cached, art().previewArtist("TSUKI", mbid, 240, 320))
        assertTrue(calls.isEmpty())
        assertEquals(before, files())
    }

    @Test
    fun aBlockedHitIsSkippedNotDropped() {
        val e = entry()
        cache.putHit(e, cached)
        blocks.add(ArtBlocks.Block(mbid, mbid, e.source, ImageIds.of(e), e.sha, "TSUKI", time.now))
        blocks.add(ArtBlocks.Block(mbid, mbid, Sources.THE_AUDIO_DB, ImageIds.of(Sources.THE_AUDIO_DB, thumb), "", "TSUKI", time.now))
        val before = files()
        assertArrayEquals(fanartBytes, art().previewArtist("TSUKI", mbid, 240, 320))
        assertEquals(before, files())
        assertEquals(Sources.THE_AUDIO_DB, cache.hit(mbid)!!.source)
    }

    @Test
    fun aHitFromASwitchedOffSourceIsSkippedAndTheNameIsSearched() {
        cache.putHit(entry(), cached)
        sources = Sources.COMMONS.copy(deezer = true)
        val before = files()
        assertTrue(!art().previewArtist("TSUKI", mbid, 240, 320).contentEquals(cached))
        assertTrue(calls.any { it.contains("search/artist?q=TSUKI") })
        assertEquals(before, files())
    }

    @Test
    fun aMissWithinItsTtlAnswersNoImage() {
        cache.putMiss(mbid, time.now, sources.enabled)
        val before = files()
        assertNull(art().previewArtist("TSUKI", mbid, 240, 320))
        assertTrue(calls.isEmpty())
        assertEquals(before, files())
    }

    @Test
    fun aLookupWritesNothing() {
        val before = files()
        assertArrayEquals(thumbBytes, art().previewArtist("TSUKI", mbid, 240, 320))
        assertEquals(before, files())
        assertNull(cache.hit(mbid))
        assertNull(cache.mbid("TSUKI"))
    }
}
