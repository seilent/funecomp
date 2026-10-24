package net.seilent.funecomp.artist

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private var sources = Sources(fanartTvKey = "")
    private val lines = java.util.Collections.synchronizedList(ArrayList<String>())
    private val pool = clock.executor(ArtistArt.BROWSE_WORKERS)
    private val art by lazy {
        ArtistArt(http, AwtCodec(), ArtCache(tmp.newFolder()), { sources }, "0.1.0", { 0L }, clock, log = { lines += it }, browseLog = { lines += it })
    }

    private val golden = Fixtures.text("browse-golden.tsv").lineSequence().filter { it.isNotBlank() }.map { it.split('\t') }.toList()

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

    private fun mbCalls() = http.snapshot().filter { it.second.startsWith("https://musicbrainz.org/ws/2/artist/") && "query=" !in it.second }

    @Test
    fun everySectionKeepsTheSequentialTilesInAnyInterleaving() {
        val covered = HashSet<String>()
        for (name in listOf("Radiohead", "TSUKI", "宇多田ヒカル")) {
            val c = browse(name)
            val want = expected(name)
            assertEquals(name, want, seen(c).filterValues { it.isNotEmpty() })
            assertEquals(name, c.order.toSet(), c.done.toSet())
            assertEquals(c.order.size, c.done.size)
            covered += seen(c).keys
        }
        assertEquals(setOf(Wikimedia.SOURCE, Sources.THE_AUDIO_DB, Sources.DISCOGS, Sources.YOUTUBE, ArtistArt.YOUTUBE_SEARCH, Sources.DEEZER), covered)
        assertTrue("missing ${http.missing}", http.missing.isEmpty())
        assertEquals(0, clock.drained())
    }

    @Test
    fun sectionsFollowTheLookupOrderWithTheSearchAfterYouTube() {
        sources = Sources(fanartTvKey = "test", order = listOf("deezer", "youtube", "commons", "theaudiodb", "fanarttv", "discogs"))
        http.answer = { u -> if (u.contains("webservice.fanart.tv")) Response(200, "{}".toByteArray()) else null }
        val c = browse("TSUKI")
        assertEquals(listOf(Sources.DEEZER, Sources.YOUTUBE, ArtistArt.YOUTUBE_SEARCH, Wikimedia.SOURCE, Sources.THE_AUDIO_DB, Sources.FANART_TV, Sources.DISCOGS), c.order)
    }

    @Test
    fun theaudiodbTilesUseTheSmallPreviewAndTheFullImageOnTap() {
        val c = browse("Radiohead")
        val t = c.of(Sources.THE_AUDIO_DB)
        assertEquals(4, t.size)
        assertTrue(http.calls.filter { it.contains("r2.theaudiodb.com") }.all { it.endsWith("/small") })
        assertTrue(t.none { it.full.endsWith("/small") })
    }

    private fun lessOne(want: List<List<String>>, got: List<List<String>>?) {
        assertEquals(want.size - 1, got?.size)
        assertEquals(want.filter { it in got!! }, got)
    }

    private fun url(prefix: String) = Fixtures.text("urls.tsv").lineSequence().map { it.substringBefore('\t') }.first { it.startsWith(prefix) }

    @Test
    fun aFailedCallSkipsOnlyItsTilesAndTheSectionEndsNormally() {
        http.queued[url("https://i.discogs.com/CkRUgew74o1pI0PKrW0gKYbadjLazsdgonRYDNvn87o")] = ArrayDeque(List(4) { Response(503, ByteArray(0)) })
        val adb = "https://www.theaudiodb.com/api/v1/json/123/artist-mb.php?i=a74b1b7f-71a5-4011-9441-d0b5e4122711"
        http.answer = { u -> if (u == adb) throw IOException("timeout") else null }
        val c = browse("Radiohead")
        val got = seen(c)
        lessOne(expected("Radiohead").getValue(Sources.DISCOGS), got[Sources.DISCOGS])
        assertEquals(null, got[Sources.THE_AUDIO_DB])
        assertEquals(expected("Radiohead").getValue(Sources.DEEZER), got[Sources.DEEZER])
        assertTrue(Sources.DISCOGS in c.done && Sources.THE_AUDIO_DB in c.done)
    }

    @Test
    fun a401OnAKeyedSourceEndsItAndOtherDenialsSkipOneCall() {
        sources = Sources(fanartTvKey = "test")
        val fanart = ArrayList<String>()
        val page = "https://www.youtube.com/channel/UChcgcbX3zMBwd0wTJyy2JlA"
        http.answer = { u ->
            when {
                u.contains("webservice.fanart.tv") -> {
                    synchronized(fanart) { fanart += u }
                    Response(401, ByteArray(0))
                }
                u == page -> Response(403, ByteArray(0))
                else -> null
            }
        }
        val c = browse("宇多田ヒカル")
        assertEquals(1, fanart.size)
        assertTrue(Sources.FANART_TV in c.done)
        assertTrue(c.of(Sources.FANART_TV).isEmpty())
        assertTrue(c.of(Sources.YOUTUBE).isEmpty())
        assertTrue(Sources.YOUTUBE in c.done)
        assertEquals(expected("宇多田ヒカル").getValue(Sources.DISCOGS), seen(c)[Sources.DISCOGS])
        assertTrue(lines.contains("art browse denied ${Sources.FANART_TV}"))
        assertTrue(lines.none { it.contains("api_key=test") })
        val e = HttpDenied(401, "https://webservice.fanart.tv/v3/music/x?api_key=test")
        assertEquals("http 401: https://webservice.fanart.tv/v3/music/x?api_key=test", e.message)
        assertTrue(e is IOException)
    }

    @Test
    fun a403OnOnePreviewSkipsThatTileOnly() {
        val small = url("https://i.discogs.com/0fBd_IuD")
        http.answer = { u -> if (u == small) Response(403, ByteArray(0)) else null }
        lessOne(expected("Radiohead").getValue(Sources.DISCOGS), seen(browse("Radiohead"))[Sources.DISCOGS])
    }

    @Test
    fun twoMbidsWithOneDiscogsRelGiveOneTileAndAFailedFirstLetsTheSecondIn() {
        val a = "https://musicbrainz.org/ws/2/artist/2e8436af-0604-42c6-84d6-68f8f5b4b93d?inc=url-rels&fmt=json"
        val dup = "{\"id\":\"2e8436af-0604-42c6-84d6-68f8f5b4b93d\",\"name\":\"tsuki\",\"disambiguation\":\"twin\",\"relations\":[{\"type\":\"discogs\",\"url\":{\"resource\":\"https://www.discogs.com/artist/5861424\"}}]}"
        http.answer = { u -> if (u == a) Response(200, dup.toByteArray()) else null }
        val c = browse("TSUKI")
        assertEquals(1, c.of(Sources.DISCOGS).size)
        val preview = http.calls.filter { it.startsWith("https://i.discogs.com/") }
        assertEquals(1, preview.size)
        val http2 = Fixtures().also {
            it.clock = clock
            it.now = { clock.now() }
        }
        var failed = false
        http2.answer = { u ->
            when {
                u == a -> Response(200, dup.toByteArray())
                u == preview.single() && !failed -> {
                    failed = true
                    Response(404, ByteArray(0))
                }
                else -> null
            }
        }
        val art2 = ArtistArt(http2, AwtCodec(), ArtCache(tmp.newFolder()), { sources }, "0.1.0", { 0L }, clock)
        val c2 = Collected()
        clock.join(clock.thread("b2") { art2.browse("TSUKI", null, 53, c2, pool) })
        clock.check()
        assertEquals(listOf("twin"), c2.of(Sources.DISCOGS).map { it.caption })
        assertEquals(2, http2.calls.count { it == preview.single() })
    }

    @Test
    fun anIdWhoseReadFailsGivesNoTilesAndTheOthersKeepTheirs() {
        http.queued["https://musicbrainz.org/ws/2/artist/91352441-9f79-48c2-81ad-ccdd1e46915a?inc=url-rels&fmt=json"] = ArrayDeque(listOf(Response(404, ByteArray(0))))
        val got = seen(browse("TSUKI"))
        assertEquals(null, got[Sources.DISCOGS])
        assertEquals(expected("TSUKI").getValue(Sources.DEEZER), got[Sources.DEEZER])
        assertEquals(expected("TSUKI").getValue(ArtistArt.YOUTUBE_SEARCH), got[ArtistArt.YOUTUBE_SEARCH])
    }

    @Test
    fun theSearchAnsweringFirstKeepsItsOwnSectionAndTheRelsKeepTheirs() {
        val h = http.hold("https://musicbrainz.org/ws/2/artist/b539e453-c4fe-47e3-8a07-8517eac74429?inc=url-rels&fmt=json")
        val c = Collected()
        val t = clock.thread("art-browse") { art.browse("宇多田ヒカル", null, 53, c, pool) }
        clock.awaitIdle()
        assertTrue(ArtistArt.YOUTUBE_SEARCH in c.done)
        assertTrue(c.of(Sources.YOUTUBE).isEmpty())
        h.release()
        clock.join(t)
        clock.check()
        assertEquals(expected("宇多田ヒカル").getValue(ArtistArt.YOUTUBE_SEARCH), seen(c)[ArtistArt.YOUTUBE_SEARCH])
        assertEquals(expected("宇多田ヒカル").getValue(Sources.YOUTUBE), seen(c)[Sources.YOUTUBE])
    }

    @Test
    fun independentSourcesStartAtOnceAndDependentOnesOnTheFirstRead() {
        val h = http.hold("https://musicbrainz.org/ws/2/artist/?query=Radiohead&fmt=json&limit=10")
        val c = Collected()
        val t = clock.thread("art-browse") { art.browse("Radiohead", null, 53, c, pool) }
        clock.awaitIdle()
        val early = http.snapshot().map { it.second }
        assertTrue(early.any { it.startsWith("https://api.deezer.com/search/artist") })
        assertTrue(early.any { it.startsWith("https://www.youtube.com/results") })
        assertTrue(early.none { it.contains("theaudiodb") || it.contains("discogs") })
        h.release()
        clock.join(t)
        clock.check()
        val all = http.snapshot().map { it.second }
        val reads = all.withIndex().filter { (_, u) -> u.startsWith("https://musicbrainz.org/ws/2/artist/") && "query=" !in u }.map { it.index }
        val adb = all.indexOfFirst { it.startsWith("https://www.theaudiodb.com/") }
        assertEquals(2, reads.size)
        assertTrue("$reads $adb", adb > reads[0] && adb < reads[1])
    }

    @Test
    fun aFailedOrEmptySearchStillEndsEveryDependentSection() {
        val q = "https://musicbrainz.org/ws/2/artist/?query=TSUKI&fmt=json&limit=10"
        http.queued[q] = ArrayDeque(List(4) { Response(503, ByteArray(0)) })
        val c = browse("TSUKI")
        assertEquals(c.order.toSet(), c.done.toSet())
        assertTrue(c.of(Sources.DISCOGS).isEmpty())
        val empty = "{\"artists\":[]}".toByteArray()
        val withMbid = Collected()
        http.queued[q] = ArrayDeque(listOf(Response(200, empty)))
        browse("TSUKI", "91352441-9f79-48c2-81ad-ccdd1e46915a", sink = withMbid)
        assertEquals(1, withMbid.of(Sources.DISCOGS).size)
        val none = Collected()
        http.queued[q] = ArrayDeque(listOf(Response(200, empty)))
        browse("TSUKI", sink = none)
        assertEquals(none.order.toSet(), none.done.toSet())
        assertTrue(none.of(Sources.DISCOGS).isEmpty())
    }

    @Test
    fun aCancelEndsEveryThreadWithNoFurtherRequestOrEvent() {
        val run = HttpRun()
        run.onCancel { pool.shutdownNow() }
        val h = http.hold("https://musicbrainz.org/ws/2/artist/?query=Radiohead&fmt=json&limit=10")
        val c = Collected()
        var thrown: Throwable? = null
        val t = clock.thread("art-browse") {
            try {
                run.on { art.browse("Radiohead", null, 53, c, pool) }
            } catch (e: Throwable) {
                thrown = e
            }
        }
        clock.awaitIdle()
        val at = clock.now()
        val calls = http.snapshot().size
        val tiles = c.tiles.size
        run.cancel()
        clock.join(t)
        pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)
        h.release()
        clock.check()
        assertEquals("cancelled", (thrown as IOException).message)
        assertEquals(at, clock.now())
        assertEquals(calls, http.snapshot().size)
        assertEquals(tiles, c.tiles.size)
        assertEquals(0, clock.drained())
        assertTrue(pool.isTerminated)
    }

    @Test
    fun aRestartLeavesAtMostOneReservationPerHostFromTheOldBrowse() {
        val run = HttpRun()
        val old = clock.executor(ArtistArt.BROWSE_WORKERS)
        run.onCancel { old.shutdownNow() }
        clock.auto = false
        val c = Collected()
        val t = clock.thread("old") {
            try {
                run.on { art.browse("Radiohead", null, 53, c, old) }
            } catch (e: IOException) {
            }
        }
        clock.awaitIdle()
        run.cancel()
        clock.join(t)
        old.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)
        clock.auto = true
        val before = clock.now()
        val n0 = http.snapshot().size
        val fresh = browse("Radiohead")
        val firstMb = http.snapshot().drop(n0).first { it.second.contains("musicbrainz.org") }.first
        assertTrue("${firstMb - before}", firstMb - before <= 2000)
        assertEquals(fresh.order.toSet(), fresh.done.toSet())
    }

    @Test
    fun aNormalEndLeavesNoBrowseThread() {
        browse("Radiohead")
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(0, clock.drained())
    }

    @Test
    fun anInterruptedWorkerStillEndsItsSection() {
        val h = http.hold("https://musicbrainz.org/ws/2/artist/?query=Radiohead&fmt=json&limit=10")
        val c = Collected()
        var thrown: Throwable? = null
        val t = clock.thread("art-browse") {
            try {
                art.browse("Radiohead", null, 53, c, pool)
            } catch (e: Throwable) {
                thrown = e
            }
        }
        clock.awaitIdle()
        pool.shutdownNow()
        h.release()
        clock.join(t)
        clock.check()
        assertEquals(null, thrown)
        assertEquals(c.order.toSet(), c.done.toSet())
    }

    @Test
    fun oneSourceThrowingLeavesTheOthersComplete() {
        http.answer = { u -> if (u.startsWith("https://api.deezer.com/")) throw IllegalStateException("bug") else null }
        val got = seen(browse("Radiohead"))
        assertEquals(null, got[Sources.DEEZER])
        assertEquals(expected("Radiohead").getValue(Sources.DISCOGS), got[Sources.DISCOGS])
        assertEquals(expected("Radiohead").getValue(Sources.THE_AUDIO_DB).size, got.getValue(Sources.THE_AUDIO_DB).size)
    }

    @Test
    fun backgroundCallsDuringABrowseWaitABoundedNumberOfIntervals() {
        val c = Collected()
        var got: Offered? = null
        val asked = clock.now()
        val runs = listOf(
            clock.task { art.browse("Radiohead", null, 53, c, pool) },
            clock.task { art.get("TSUKI", null, 360, 480) },
            clock.task { got = art.take(Tile(Sources.DEEZER, "x", ByteArray(0), TAKE, "x", null)) },
        ).map { Thread(it).apply { start() } }
        val t = runs[0]
        val bg = runs[1]
        val take = runs[2]
        clock.join(bg)
        clock.join(take)
        clock.join(t)
        clock.check()
        val after = http.snapshot().filter { it.first >= asked }
        val mb = after.first { it.second.contains("query=TSUKI") }.first
        assertTrue("mb ${mb - asked}", mb - asked <= 2000)
        val dz = after.first { it.second == TAKE }.first
        assertTrue("take ${dz - asked}", dz - asked <= 2 * 1000)
        assertTrue(got != null)
    }

    companion object {
        private const val TAKE = "https://cdn-images.dzcdn.net/images/artist/0d58cfbc90f2e776608bcdc0c45a4711/1000x1000-000000-80-0-0.jpg"
    }
}
