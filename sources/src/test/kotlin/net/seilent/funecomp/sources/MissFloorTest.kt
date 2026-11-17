package net.seilent.funecomp.sources

import net.seilent.funekit.artist.ArtBlocks
import net.seilent.funekit.artist.ArtCache
import net.seilent.funekit.artist.ArtistArt
import net.seilent.funekit.artist.AwtCodec
import net.seilent.funekit.artist.FakeTime
import net.seilent.funekit.artist.Fixtures
import net.seilent.funekit.artist.Response
import net.seilent.funekit.artist.Size
import net.seilent.funekit.artist.TEST_AGENT
import net.seilent.funekit.artist.Targets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MissFloorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val name = "Sztest"
    private val key = "name:sztest"
    private val dzSearch = "https://api.deezer.com/search/artist?q=$name"
    private val mbSearch = "https://musicbrainz.org/ws/2/artist/?query=$name&fmt=json&limit=10"
    private val ytSearch = "https://www.youtube.com/results?search_query=$name&sp=EgIQAg%253D%253D"
    private val one = "11111111111111111111111111111111"
    private fun dz(side: Int = 1000) = "https://cdn-images.dzcdn.net/images/artist/$one/${side}x$side-000000-80-0-0.jpg"

    private val http = Fixtures()
    private val time = FakeTime()
    private val dir by lazy { tmp.newFolder("art") }
    private val cache by lazy { ArtCache(dir) }
    private var sources = ArtSources.NONE.copy(deezer = true)

    private fun art() = ArtistArt(http, AwtCodec(), cache, { sources }, TEST_AGENT, { time.now }, time, blocks = ArtBlocks(), targets = { Targets(Size(720, 1680)) }, extensions = listOf(YouTubeArt {}))

    private fun queue(url: String, r: Response) {
        http.queued.getOrPut(url) { ArrayDeque() } += r
    }

    private fun search(image: String?) {
        queue(mbSearch, Response(404, ByteArray(0)))
        val data = image?.let { "{\"name\":\"$name\",\"nb_fan\":1,\"picture_xl\":\"${dz()}\"}" } ?: ""
        queue(dzSearch, Response(200, "{\"data\":[$data]}".toByteArray()))
        if (image == null) return
        queue(dz(), Response(200, Fixtures.gen(image)))
        queue(dz(1680), Response(404, ByteArray(0)))
    }

    private fun channels(answered: Boolean) {
        sources = YouTubeArt.with(ArtSources.NONE.copy(deezer = true), true)
        val page = if (answered) "<script>var ytInitialData = {\"estimatedResults\":\"0\"};</script>" else "<html></html>"
        queue(ytSearch, Response(200, page.toByteArray()))
        if (answered) queue("$ytSearch&hl=en&gl=US", Response(200, page.toByteArray()))
    }

    private fun lookedUp(before: Int) = http.calls.drop(before).contains(dzSearch)

    private fun theV1CallLooksUp() {
        val before = http.calls.size
        search("300x400@5")
        assertNotNull(art().get(name, null, 240, 320))
        assertTrue(lookedUp(before))
        assertFalse(http.calls.drop(before).contains(ytSearch))
    }

    @Test
    fun anAnsweredChannelSearchWritesTheFloorWithItsStamp() {
        channels(answered = true)
        search("300x400@5")
        val t = time.now
        assertNull(art().get(name, null, 720, 1280))
        val m = cache.miss(key)!!
        assertEquals(Size(360, 480), m.floor)
        assertEquals(t, m.kept["yt"]?.toLong())
        assertNull(m.kept["ytFailed"]?.toLong())
        assertTrue(http.missing.isEmpty())
        theV1CallLooksUp()
    }

    @Test
    fun aFailedChannelSearchWritesTheFloorWithItsStamp() {
        channels(answered = false)
        search("300x400@5")
        val t = time.now
        assertNull(art().get(name, null, 720, 1280))
        val m = cache.miss(key)!!
        assertEquals(Size(360, 480), m.floor)
        assertNull(m.kept["yt"]?.toLong())
        assertEquals(t, m.kept["ytFailed"]?.toLong())
        assertTrue(http.missing.isEmpty())
        theV1CallLooksUp()
    }
}
