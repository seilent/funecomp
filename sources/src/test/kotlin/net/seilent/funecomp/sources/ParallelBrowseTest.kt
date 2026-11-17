package net.seilent.funecomp.sources

import net.seilent.funekit.artist.ArtCache
import net.seilent.funekit.artist.ArtistArt
import net.seilent.funekit.artist.AwtCodec
import net.seilent.funekit.artist.Collected
import net.seilent.funekit.artist.Fixtures
import net.seilent.funekit.artist.HttpRun
import net.seilent.funekit.artist.Response
import net.seilent.funekit.artist.Sources
import net.seilent.funekit.artist.TEST_AGENT
import net.seilent.funekit.artist.VirtualClock
import net.seilent.funekit.artist.Wikimedia
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.IOException

class ParallelBrowseTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val clock = VirtualClock()
    private val http = Fixtures().also {
        it.clock = clock
        it.now = { clock.now() }
    }
    private var sources = ArtSources.DEFAULT
    private val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val pool by lazy { clock.executor(art.browseWorkers) }
    private val art by lazy {
        ArtistArt(http, AwtCodec(), ArtCache(tmp.newFolder()), { sources }, TEST_AGENT, { 0L }, clock, log = { lines += it }, browseLog = { lines += it }, extensions = listOf(YouTubeArt { lines += it }))
    }

    private val golden = Fixtures.text("browse-youtube.tsv").lineSequence().filter { it.isNotBlank() }.map { it.split('\t') }.toList()

    @After
    fun tearDown() {
        pool.shutdownNow()
    }

    private fun browse(name: String, mbid: String? = null, run: HttpRun? = null, sink: Collected = Collected()): Collected {
        val t = clock.thread("art-browse") {
            try {
                if (run == null) art.browse(name, mbid, 53, sink, pool) else run.on { art.browse(name, mbid, 53, sink, pool) }
            } catch (e: IOException) {
            }
        }
        clock.join(t)
        clock.check()
        return sink
    }

    private fun expected(name: String): Map<String, List<List<String>>> =
        golden.filter { it[0] == name && it[1] != "ok" }.groupBy({ it[1] }, { it.drop(2) }).mapValues { (_, v) -> v.distinctBy { it[1] } }

    private fun seen(c: Collected): Map<String, List<List<String>>> =
        synchronized(c.tiles) { c.tiles.groupBy({ it.first }, { listOf(it.second.source, it.second.id, it.second.full, it.second.caption) }) }

    @Test
    fun everySectionKeepsTheYouTubeTilesInAnyInterleaving() {
        val youtube = setOf(YouTubeArt.SOURCE, YouTubeArt.SEARCH)
        val covered = HashSet<String>()
        for (name in listOf("Radiohead", "TSUKI", "宇多田ヒカル")) {
            val c = browse(name)
            assertEquals(name, expected(name), seen(c).filterKeys { it in youtube }.filterValues { it.isNotEmpty() })
            assertEquals(name, c.order.toSet(), c.done.toSet())
            assertEquals(c.order.size, c.done.size)
            covered += seen(c).keys.filter { it in youtube }
        }
        assertEquals(youtube, covered)
        assertTrue("missing ${http.missing}", http.missing.isEmpty())
        assertEquals(0, clock.drained())
    }

    @Test
    fun theSearchSectionFollowsYouTube() {
        sources = ArtSources.DEFAULT.copy(fanartTvKey = "test", order = listOf("deezer", "youtube", "commons", "theaudiodb", "fanarttv", "discogs"))
        http.answer = { u -> if (u.contains("webservice.fanart.tv")) Response(200, "{}".toByteArray()) else null }
        val c = browse("TSUKI")
        assertEquals(listOf(Sources.DEEZER, YouTubeArt.SOURCE, YouTubeArt.SEARCH, Wikimedia.SOURCE, Sources.THE_AUDIO_DB, Sources.FANART_TV, Sources.DISCOGS), c.order)
    }

    @Test
    fun aDeniedYouTubePageSkipsOneCall() {
        sources = ArtSources.DEFAULT.copy(fanartTvKey = "test")
        val page = "https://www.youtube.com/channel/UChcgcbX3zMBwd0wTJyy2JlA"
        http.answer = { u ->
            when {
                u.contains("webservice.fanart.tv") -> Response(401, ByteArray(0))
                u == page -> Response(403, ByteArray(0))
                else -> null
            }
        }
        val c = browse("宇多田ヒカル")
        assertTrue(c.of(YouTubeArt.SOURCE).isEmpty())
        assertTrue(YouTubeArt.SOURCE in c.done)
    }

    @Test
    fun anIdWhoseReadFailsKeepsTheYouTubeSearchTiles() {
        http.queued["https://musicbrainz.org/ws/2/artist/91352441-9f79-48c2-81ad-ccdd1e46915a?inc=url-rels&fmt=json"] = ArrayDeque(listOf(Response(404, ByteArray(0))))
        assertEquals(expected("TSUKI").getValue(YouTubeArt.SEARCH), seen(browse("TSUKI"))[YouTubeArt.SEARCH])
    }

    @Test
    fun theSearchAnsweringFirstKeepsItsOwnSectionAndTheRelsKeepTheirs() {
        val h = http.hold("https://musicbrainz.org/ws/2/artist/b539e453-c4fe-47e3-8a07-8517eac74429?inc=url-rels&fmt=json")
        val c = Collected()
        val t = clock.thread("art-browse") { art.browse("宇多田ヒカル", null, 53, c, pool) }
        clock.awaitIdle()
        assertTrue(YouTubeArt.SEARCH in c.done)
        assertTrue(c.of(YouTubeArt.SOURCE).isEmpty())
        h.release()
        clock.join(t)
        clock.check()
        assertEquals(expected("宇多田ヒカル").getValue(YouTubeArt.SEARCH), seen(c)[YouTubeArt.SEARCH])
        assertEquals(expected("宇多田ヒカル").getValue(YouTubeArt.SOURCE), seen(c)[YouTubeArt.SOURCE])
    }

    @Test
    fun theYouTubeSearchStartsBeforeTheFirstRead() {
        val h = http.hold("https://musicbrainz.org/ws/2/artist/?query=Radiohead&fmt=json&limit=10")
        val c = Collected()
        val t = clock.thread("art-browse") { art.browse("Radiohead", null, 53, c, pool) }
        clock.awaitIdle()
        assertTrue(http.snapshot().any { it.second.startsWith("https://www.youtube.com/results") })
        h.release()
        clock.join(t)
        clock.check()
    }
}
