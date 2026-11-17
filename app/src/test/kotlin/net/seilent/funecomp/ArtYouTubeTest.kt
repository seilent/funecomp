package net.seilent.funecomp

import net.seilent.funecomp.sources.YouTubeArt
import net.seilent.funekit.artist.ArtBlocks
import net.seilent.funekit.artist.ArtCache
import net.seilent.funekit.artist.Http
import net.seilent.funekit.artist.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArtYouTubeTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val searched = CopyOnWriteArrayList<String>()
    private val http = Http { url, _ ->
        when {
            url.startsWith("https://musicbrainz.org/ws/2/artist/?query=") -> Response(200, "{\"artists\":[]}".toByteArray())
            url.startsWith("https://www.youtube.com/results?") -> {
                if (!url.endsWith("&hl=en&gl=US")) searched += java.net.URLDecoder.decode(url.substringAfter("search_query=").substringBefore('&'), "UTF-8")
                Response(200, "<html><script>var ytInitialData = {\"estimatedResults\":\"0\"};</script></html>".toByteArray())
            }
            else -> Response(404, ByteArray(0))
        }
    }

    @Before
    fun setUp() {
        Remote.resetArtist()
    }

    @After
    fun tearDown() {
        Remote.resetArtist()
    }

    @Test
    fun theAppsArtLookupSearchesYouTube() {
        val a = Remote.artist(app, http)
        assertNull(a.get("Nobody", null, 240, 320))
        assertEquals(listOf("Nobody"), searched)
    }

    @Test
    fun aYouTubeBlockRecordsTheImageIdBeforeTheSize() {
        val url = "https://yt3.googleusercontent.com/ytc/AIdro_l2Ct30OyvxG3NA=s900-c-k-c0x00ffffff-no-rj"
        val a = Remote.artist(app, http)
        val e = ArtCache.Entry("name:kobasolo", "Kobasolo", null, YouTubeArt.SOURCE, null, url, 1)
        a.cache.putHit(e, ByteArray(16) { it.toByte() })
        Remote.blockArt(e)
        val b = a.blocks.records().single() as ArtBlocks.Block
        assertEquals("https://yt3.googleusercontent.com/ytc/AIdro_l2Ct30OyvxG3NA", b.id)
        assertEquals(YouTubeArt.SOURCE, b.source)
    }
}
