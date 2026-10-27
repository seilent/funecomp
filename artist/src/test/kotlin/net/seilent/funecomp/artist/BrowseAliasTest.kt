package net.seilent.funecomp.artist

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.IOException

class BrowseAliasTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val risa = "2bbcc070-62f8-4669-b77c-fdaa2d7c3f4a"
    private val clock = VirtualClock()
    private val http = Fixtures().also {
        it.clock = clock
        it.now = { clock.now() }
    }
    private val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val pool = clock.executor(ArtistArt.BROWSE_WORKERS)
    private val art by lazy {
        ArtistArt(http, AwtCodec(), ArtCache(tmp.newFolder()), { Sources.COMMONS.copy(youtube = true, order = Sources.ORDER) }, "0.1.0", { 0L }, clock, browseLog = { lines += it })
    }

    @After
    fun tearDown() {
        pool.shutdownNow()
    }

    private fun search(q: String) = "https://www.youtube.com/results?search_query=${Api.enc(q)}&sp=EgIQAg%253D%253D"

    private val aliases = "https://musicbrainz.org/ws/2/artist/$risa?inc=aliases&fmt=json"
    private val mbSearch = "https://musicbrainz.org/ws/2/artist/?query=Risa%20Yuzuki&fmt=json&limit=10"
    private val gated get() = Fixtures.load("yt-search-nanao.html")
    private val empty get() = Fixtures.load("yt-search-empty.html")
    private val drift get() = Fixtures.load("yt-search-drift.html")

    private fun queue(url: String, vararg bodies: ByteArray) {
        http.queued.getOrPut(url) { ArrayDeque() }.addAll(bodies.map { Response(200, it) })
    }

    private fun browse(name: String = "Risa Yuzuki", mbid: String? = null): Collected {
        val c = Collected()
        val t = clock.thread("art-browse") {
            try {
                art.browse(name, mbid, 53, c, pool)
            } catch (e: IOException) {
            }
        }
        clock.join(t)
        clock.check()
        return c
    }

    private fun calls(url: String) = http.snapshot().count { it.second == url }

    private fun youtube() = http.snapshot().map { it.second }.filter { it.startsWith("https://www.youtube.com/results?") }

    @Test
    fun aGatedPairSearchesTheJapaneseAliasOnce() {
        http.answer = { u -> if (u.contains("yt3.ggpht.com") || u.contains("yt3.googleusercontent.com")) Response(200, Fixtures.gen("176x176")) else null }
        val c = browse()
        assertEquals(1, calls(aliases))
        assertEquals(listOf(search("Risa Yuzuki"), search("Risa Yuzuki") + "&hl=en&gl=US", search("柚木梨沙")), youtube())
        assertTrue(c.search is YouTube.Search.Channels)
        assertEquals("Risa Yuzuki", c.of(ArtistArt.YOUTUBE_SEARCH).first().caption)
        assertTrue(c.of(ArtistArt.YOUTUBE_SEARCH).all { it.source == Sources.YOUTUBE })
        assertTrue(lines.contains("art browse youtube search alias 柚木梨沙 channels=20"))
    }

    @Test
    fun noResultsOrAFailureNeverTakesTheAlias() {
        queue(search("Risa Yuzuki"), empty)
        queue(search("Risa Yuzuki") + "&hl=en&gl=US", empty)
        assertEquals(YouTube.Search.NoResults, browse().search)
        queue(search("Risa Yuzuki"), drift)
        assertEquals(YouTube.Search.Unavailable(), browse().search)
        assertEquals(0, calls(aliases))
        assertTrue(youtube().none { it == search("柚木梨沙") })
    }

    @Test
    fun aGatedNameWithNoMusicBrainzArtistStaysUnavailable() {
        val name = "水波琴音Minami Kotone"
        queue(search(name), gated)
        queue(search(name) + "&hl=en&gl=US", gated)
        assertEquals(YouTube.Search.Unavailable(gated = true), browse(name).search)
        assertTrue(http.snapshot().none { it.second.contains("inc=aliases") })
        assertEquals(2, youtube().size)
    }

    @Test
    fun aGatedAliasSearchEndsUnavailableAfterOneAlias() {
        queue(search("柚木梨沙"), gated)
        queue(search("柚木梨沙") + "&hl=en&gl=US", gated)
        assertEquals(YouTube.Search.Unavailable(gated = true), browse().search)
        assertEquals(4, youtube().size)
        assertTrue(lines.contains("art browse youtube search alias 柚木梨沙 unavailable"))
    }

    @Test
    fun anEmptyAliasPageRunsItsOwnRetryAndNoResultsDecides() {
        queue(search("柚木梨沙"), empty)
        queue(search("柚木梨沙") + "&hl=en&gl=US", empty)
        assertEquals(YouTube.Search.NoResults, browse().search)
        assertEquals(search("柚木梨沙") + "&hl=en&gl=US", youtube().last())
        assertTrue(lines.contains("art browse youtube search alias 柚木梨沙 none"))
    }

    @Test
    fun aFailedAliasReadEndsUnavailableWithNoAliasSearch() {
        http.queued[aliases] = ArrayDeque(listOf(Response(500, ByteArray(0))))
        assertEquals(YouTube.Search.Unavailable(gated = true), browse().search)
        assertEquals(2, youtube().size)
    }

    @Test
    fun aTagMbidTakesTheAliasWithNoWaitForTheSearchArtists() {
        val h = http.hold(mbSearch)
        val c = Collected()
        val t = clock.thread("art-browse") {
            try {
                art.browse("Risa Yuzuki", risa, 53, c, pool)
            } catch (e: IOException) {
            }
        }
        clock.awaitIdle()
        assertEquals(1, calls(aliases))
        assertTrue(youtube().contains(search("柚木梨沙")))
        h.release()
        clock.join(t)
        clock.check()
        assertTrue(c.search is YouTube.Search.Channels)
    }

    @Test
    fun aCancelDuringTheArtistWaitEndsWithNoAliasRead() {
        val run = HttpRun()
        run.onCancel { pool.shutdownNow() }
        val h = http.hold(mbSearch)
        var thrown: Throwable? = null
        val t = clock.thread("art-browse") {
            try {
                run.on { art.browse("Risa Yuzuki", null, 53, Collected(), pool) }
            } catch (e: Throwable) {
                thrown = e
            }
        }
        clock.awaitIdle()
        assertEquals(2, youtube().size)
        run.cancel()
        clock.join(t)
        pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)
        h.release()
        clock.check()
        assertEquals("cancelled", (thrown as IOException).message)
        assertEquals(0, calls(aliases))
    }
}
