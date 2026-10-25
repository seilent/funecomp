package net.seilent.funecomp.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class BrowseTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val search = "https://www.youtube.com/results?search_query=TSUKI&sp=EgIQAg%253D%253D"

    private val http = Fixtures()
    private val time = FakeTime()
    private var sources = Sources.NONE.copy(discogs = true, deezer = true, youtube = true)
    private val cache by lazy { ArtCache(tmp.newFolder("art")) }

    private fun art() = ArtistArt(http, AwtCodec(), cache, { sources }, "0.1.0", { time.now }, time).also {
        http.now = { time.now }
    }

    private fun browse(a: ArtistArt = art()): Pair<List<Tile>, Boolean> {
        val c = Collected()
        a.browse("TSUKI", null, 53, c, Inline)
        return c.all() to (c.search ?: true)
    }

    @Test
    fun tilesComeInSectionOrderWithTheYouTubeSearchAfterYouTube() {
        val c = Collected()
        art().browse("TSUKI", null, 53, c, Inline)
        assertEquals(listOf(Wikimedia.SOURCE, Sources.DISCOGS, Sources.YOUTUBE, ArtistArt.YOUTUBE_SEARCH, Sources.DEEZER), c.order)
        assertEquals(c.order, c.done)
        assertEquals(true, c.search)
        val ranks = c.tiles.map { c.order.indexOf(it.first) }
        assertEquals(ranks.sorted(), ranks)
        assertEquals("dnb, Jump up", c.of(Sources.DISCOGS).single().caption)
        assertEquals(10, c.of(Sources.DEEZER).size)
        val yt = c.of(ArtistArt.YOUTUBE_SEARCH)
        assertEquals(9, yt.size)
        assertEquals(yt.size, yt.map { it.id }.toSet().size)
        assertTrue(yt.all { it.source == Sources.YOUTUBE })
        val tsuki = yt.first { it.caption == "月-Tsuki-" }
        assertTrue(tsuki.full.endsWith("=s900-c-k-c0x00ffffff-no-rj-mo"))
        assertEquals(tsuki.full.substringBefore('='), tsuki.id)
        assertEquals(4, http.calls.count { it.startsWith("https://musicbrainz.org/ws/2/artist/") && "query=" !in it })
        assertTrue("missing ${http.missing}", http.missing.isEmpty())
    }

    @Test
    fun theLimitersHoldAcrossTheFanOut() {
        browse()
        fun gaps(hosts: Set<String>) = http.times.filter { (_, u) -> u.substringAfter("://").substringBefore('/') in hosts }.map { it.first }.zipWithNext { a, b -> b - a }
        assertTrue(gaps(setOf("musicbrainz.org")).all { it >= 1000 })
        assertTrue(gaps(setOf("api.discogs.com")).all { it >= 60_000L / 25 })
        for (h in listOf("i.discogs.com", "www.youtube.com", "api.deezer.com", "cdn-images.dzcdn.net", "yt3.ggpht.com")) assertTrue(h, gaps(setOf(h)).all { it >= 1000 })
    }

    @Test
    fun disabledSourcesMakeNoCalls() {
        sources = Sources.NONE
        val c = Collected()
        art().browse("TSUKI", null, 53, c, Inline)
        assertEquals(listOf(Wikimedia.SOURCE), c.order)
        assertEquals(listOf(Wikimedia.SOURCE), c.done)
        assertEquals(null, c.search)
        assertTrue(c.tiles.isEmpty())
        assertEquals(setOf("musicbrainz.org"), http.hosts())
    }

    @Test
    fun aCancelStopsFurtherCalls() {
        val run = HttpRun()
        val c = Collected()
        var calls = -1
        c.onTile = {
            run.cancel()
            calls = http.calls.size
        }
        try {
            run.on { art().browse("TSUKI", null, 53, c, Inline) }
            fail()
        } catch (e: IOException) {
            assertEquals("cancelled", e.message)
        }
        assertEquals(1, c.tiles.size)
        assertTrue(http.calls.none { it == search })
        assertEquals(calls, http.calls.size)
        assertEquals(c.order, c.done)
    }

    @Test
    fun noInitialDataGivesTheUnavailableStateAndNoException() {
        http.queued[search] = ArrayDeque(listOf(Response(200, "<html><body>consent</body></html>".toByteArray())))
        val (tiles, ok) = browse()
        assertFalse(ok)
        assertTrue(tiles.none { it.caption == "月-Tsuki-" })
        assertTrue(tiles.isNotEmpty())
    }

    @Test
    fun aTapFetchesTheFullImage() {
        val (tiles, _) = browse()
        val a = art()
        val o = (a.take(tiles.first { it.caption == "月-Tsuki-" }) as Offered.Ok).offers.single()
        assertEquals(Sources.YOUTUBE, o.source)
        assertTrue(o.url.contains("=s900"))
        assertNotNull(o.src)
        val small = tiles.first { it.source == Sources.DEEZER }
        http.queued[small.full] = ArrayDeque(listOf(Response(200, Images.jpeg(Images.photo(250, 250), 92))))
        assertEquals(PickLink.TOO_SMALL, (a.take(small) as Offered.Refused).reason)
    }

    @Test
    fun theChannelParserReadsTheElementForm() {
        val html = Fixtures.text("yt-search-tenjin.html")
        assertTrue(html.contains("<script id=\"yt-initial-data\"") && !html.contains("var ytInitialData = "))
        val c = YouTube.channels(html)!!
        assertEquals(20, c.size)
        assertEquals("UCAr7rLi_Wn09G-XfTA07d4g", c[0].id)
        assertEquals("天神 子兎音 Tenjin Kotone", c[0].title)
        assertTrue(c.all { it.thumb.startsWith("https://") })
        assertEquals(null, YouTube.channels("<script id=\"yt-initial-data\" type=\"application/json\">{bad</script>"))
        assertEquals(emptyList<YouTube.Channel>(), YouTube.channels("<script id=\"yt-initial-data\" type=\"application/json\" nonce=\"n\">{\"a\":[]}</script>"))
    }

    @Test
    fun anUnparsedSearchPageLogsItsMarkersOnly() {
        val http = Fixtures()
        val url = "https://www.youtube.com/results?search_query=Nanao%20Sakuya&sp=EgIQAg%253D%253D"
        val page = "<html><body><script>window['ytInitialData'] = secretWord;</script></body></html>"
        http.queued[url] = ArrayDeque(listOf(Response(200, page.toByteArray())))
        val lines = ArrayList<String>()
        assertEquals(null, YouTube(Api(http, "ua", Hosts(FakeTime()))) { lines += it }.search("Nanao Sakuya"))
        assertEquals(listOf("art browse youtube search unparsed chars=${page.length} var=false element=false window=true"), lines)
        assertTrue(lines.none { it.contains("secretWord") })
    }

    @Test
    fun theChannelParser() {
        val html = Fixtures.text("yt-search-tsuki.html")
        val c = YouTube.channels(html)!!
        assertEquals(20, c.size)
        assertEquals("UC368BL46Vr1Y2ZbyWXY-ebw", c[2].id)
        assertEquals("月-Tsuki-", c[2].title)
        assertTrue(c.all { it.thumb.startsWith("https://") })
        assertEquals(null, YouTube.channels("<html></html>"))
        assertEquals(null, YouTube.channels("var ytInitialData = {bad;</script>"))
        assertEquals(emptyList<YouTube.Channel>(), YouTube.channels("var ytInitialData = {\"a\":[1,{\"b\":null}]};</script>"))
        assertEquals("月-Tsuki-", YouTube.ogTitle(Fixtures.text("yt-tsukiniterasarete-head.html")))
        assertEquals("https://x/a=s176-c", YouTube.sized("https://x/a=s900-c", 176))
    }

    @Test
    fun noChannelRenderersIsUnavailable() {
        http.queued[search] = ArrayDeque(listOf(Response(200, "<script>var ytInitialData = {\"contents\":{}};</script>".toByteArray())))
        val (_, ok) = browse()
        assertFalse(ok)
    }

    @Test
    fun aRuntimeErrorInOneCallSkipsThatTile() {
        val bad = Http { url, h ->
            if (url.contains("dzcdn.net") && url.contains("3c0e4ec7")) throw IllegalArgumentException("bad url")
            http.get(url, h)
        }
        val c = Collected()
        ArtistArt(bad, AwtCodec(), cache, { sources }, "0.1.0", { time.now }, time).browse("TSUKI", null, 53, c, Inline)
        assertEquals(true, c.search)
        assertEquals(9, c.of(Sources.DEEZER).size)
        assertEquals(9, c.of(ArtistArt.YOUTUBE_SEARCH).size)
    }
}
