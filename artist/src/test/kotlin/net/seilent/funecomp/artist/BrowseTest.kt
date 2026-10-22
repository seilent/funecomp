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
        val tiles = ArrayList<Tile>()
        val ok = a.browse("TSUKI", null, 53) { tiles += it }
        return tiles to ok
    }

    @Test
    fun tilesComeInSourceOrderWithTheYouTubeSearchLast() {
        val (tiles, ok) = browse()
        assertTrue(ok)
        val order = sources.ordered
        val ranks = tiles.dropLast(10).map { order.indexOf(it.source) }
        assertEquals(ranks.sorted(), ranks)
        assertEquals(listOf(Sources.DISCOGS), tiles.filter { it.source == Sources.DISCOGS }.map { it.source })
        assertEquals("dnb, Jump up", tiles.first { it.source == Sources.DISCOGS }.caption)
        assertEquals(10, tiles.count { it.source == Sources.DEEZER })
        val yt = tiles.takeLast(10)
        assertTrue(yt.all { it.source == Sources.YOUTUBE })
        val tsuki = yt.first { it.caption == "月-Tsuki-" }
        assertTrue(tsuki.full.endsWith("=s900-c-k-c0x00ffffff-no-rj-mo"))
        assertEquals(tsuki.full.substringBefore('='), tsuki.id)
        assertEquals(4, http.calls.count { it.startsWith("https://musicbrainz.org/ws/2/artist/") && "query=" !in it })
        assertTrue("missing ${http.missing}", http.missing.isEmpty())
    }

    @Test
    fun timingLinesNameEveryTileAndSection() {
        val lines = ArrayList<String>()
        val a = ArtistArt(http, AwtCodec(), cache, { sources }, "0.1.0", { time.now }, time, browseLog = { lines += it })
        http.now = { time.now }
        val t0 = time.now
        val tiles = ArrayList<Tile>()
        assertTrue(a.browse("TSUKI", null, 53) { tiles += it })
        val sections = sources.ordered + ArtistArt.YOUTUBE_SEARCH
        val tile = Regex("art browse tile (.+) \\+(\\d+)")
        val done = Regex("art browse source (.+) done n=(\\d+) \\+(\\d+)")
        assertEquals(sections, lines.mapNotNull { done.matchEntire(it)?.groupValues?.get(1) })
        assertEquals(tiles.size, lines.count { tile.matches(it) })
        assertEquals(lines.size, tiles.size + sections.size)
        val counts = lines.mapNotNull { done.matchEntire(it) }.associate { it.groupValues[1] to it.groupValues[2].toInt() }
        assertEquals(mapOf(Wikimedia.SOURCE to 0, Sources.YOUTUBE to 0, Sources.DISCOGS to 1, Sources.DEEZER to 10, ArtistArt.YOUTUBE_SEARCH to 10), counts)
        val ms = lines.map { it.substringAfterLast('+').toLong() }
        assertEquals(ms.sorted(), ms)
        assertEquals(time.now - t0, ms.last())
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
        val (tiles, ok) = browse()
        assertTrue(ok)
        assertTrue(tiles.isEmpty())
        assertEquals(setOf("musicbrainz.org"), http.hosts())
    }

    @Test
    fun aCancelStopsFurtherCalls() {
        val run = HttpRun()
        val tiles = ArrayList<Tile>()
        var calls = -1
        try {
            run.on {
                art().browse("TSUKI", null, 53) {
                    tiles += it
                    if (tiles.size == 2) {
                        run.cancel()
                        calls = http.calls.size
                    }
                }
            }
            fail()
        } catch (e: IOException) {
            assertEquals("cancelled", e.message)
        }
        assertEquals(2, tiles.size)
        assertTrue(http.calls.none { it == search })
        assertEquals(calls, http.calls.size)
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
        val tiles = ArrayList<Tile>()
        val ok = ArtistArt(bad, AwtCodec(), cache, { sources }, "0.1.0", { time.now }, time).browse("TSUKI", null, 53) { tiles += it }
        assertTrue(ok)
        assertEquals(9, tiles.count { it.source == Sources.DEEZER })
        assertEquals(10, tiles.count { it.source == Sources.YOUTUBE })
    }
}
