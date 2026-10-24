package net.seilent.funecomp.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class HostsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val clock = VirtualClock()
    private val http = Fixtures().also {
        it.clock = clock
        it.now = { clock.now() }
    }

    private fun gaps(host: String) = http.snapshot().filter { Hosts.host(it.second) == host }.map { it.first }.zipWithNext { a, b -> b - a }

    @Test
    fun everyHostHasItsOwnLimiter() {
        val h = Hosts(clock)
        assertSame(h.musicBrainz, h.limiter("https://musicbrainz.org/ws/2/artist/x"))
        for (u in listOf("https://commons.wikimedia.org/w/api.php", "https://upload.wikimedia.org/a.jpg", "https://thumb.wikimedia.org/a.jpg", "https://www.wikidata.org/w/api.php", "https://ja.wikipedia.org/api")) assertSame(u, h.wikimedia, h.limiter(u))
        assertSame(h.audioDb, h.limiter("https://www.theaudiodb.com/api/v1/json/123/x"))
        assertSame(h.audioDbImages, h.limiter("https://r2.theaudiodb.com/images/a.jpg/small"))
        assertSame(h.fanartTv, h.limiter("https://webservice.fanart.tv/v3/music/x?api_key=test"))
        assertSame(h.fanartTvImages, h.limiter("https://assets.fanart.tv/fanart/a.jpg"))
        assertSame(h.discogs, h.limiter("https://api.discogs.com/artists/1"))
        assertSame(h.discogsImages, h.limiter("https://i.discogs.com/a.jpeg"))
        assertSame(h.youtube, h.limiter("https://www.youtube.com/@x"))
        assertSame(h.youtube, h.limiter("https://music.youtube.com/@x"))
        for (u in listOf("https://yt3.ggpht.com/a=s176", "https://yt3.googleusercontent.com/a=s900", "https://i.ytimg.com/a.jpg")) assertSame(u, h.youtubeImages, h.limiter(u))
        assertSame(h.deezer, h.limiter("https://api.deezer.com/search/artist?q=x"))
        assertSame(h.deezerImages, h.limiter("https://cdn-images.dzcdn.net/images/artist/a/250x250.jpg"))
        val a = h.limiter("https://example.org/a.jpg")
        assertSame(a, h.limiter("https://EXAMPLE.org:443/b.jpg"))
        repeat(Hosts.OTHER_HOSTS) { h.limiter("https://h$it.example/a.jpg") }
        assertFalse(a === h.limiter("https://example.org/a.jpg"))
    }

    @Test
    fun browseLookupAndSweepTogetherKeepEveryHostSpaced() {
        val sources = Sources(fanartTvKey = "")
        val art = ArtistArt(http, AwtCodec(), ArtCache(tmp.newFolder()), { sources }, "0.1.0", { 0L }, clock)
        val run = clock.registered {
            listOf(
                clock.thread("browse") { art.browse("Radiohead", null, 53, Collected(), clock.executor(ArtistArt.BROWSE_WORKERS)) },
                clock.thread("get") { art.get("宇多田ヒカル", null, 360, 480) },
                clock.thread("sweep") { art.get("TSUKI", null, 360, 480) },
            )
        }
        clock.join(run)
        clock.check()
        assertEquals(0, clock.drained())
        val spacing = mapOf(
            "musicbrainz.org" to 1000L,
            "commons.wikimedia.org" to 300L,
            "www.theaudiodb.com" to 2000L,
            "r2.theaudiodb.com" to 1000L,
            "api.discogs.com" to 2400L,
            "i.discogs.com" to 1000L,
            "api.deezer.com" to 1000L,
            "cdn-images.dzcdn.net" to 1000L,
            "www.youtube.com" to 1000L,
            "yt3.ggpht.com" to 1000L,
        )
        for (host in listOf("musicbrainz.org", "www.theaudiodb.com", "api.discogs.com", "api.deezer.com", "www.youtube.com")) assertTrue("$host has calls", http.snapshot().any { Hosts.host(it.second) == host })
        for ((host, ms) in spacing) {
            val g = gaps(host)
            assertTrue("$host $g", g.all { it >= ms })
        }
        val wm = http.snapshot().filter { it.second.contains("wikimedia.org") || it.second.contains("wikidata.org") || it.second.contains("wikipedia.org") }.map { it.first }
        assertTrue(wm.zipWithNext { a, b -> b - a }.all { it >= 300 })
    }

    @Test
    fun aBusyHostDelaysOnlyItself() {
        val api = Api(http, "ua", Hosts(clock))
        http.queued["https://api.deezer.com/search/artist?q=x"] = ArrayDeque(listOf(Response(429, ByteArray(0), mapOf("retry-after" to "20"))))
        http.queued["https://api.deezer.com/search/artist?q=x"]!!.addLast(Response(200, "{}".toByteArray()))
        val (t, m) = clock.registered {
            clock.thread("deezer") { api.get("https://api.deezer.com/search/artist?q=x") } to
                clock.thread("mb") { repeat(5) { api.get("https://musicbrainz.org/ws/2/artist/?query=Radiohead&fmt=json&limit=10") } }
        }
        clock.join(t)
        clock.join(m)
        clock.check()
        val dz = http.snapshot().filter { it.second.contains("deezer") }.map { it.first }
        val mb = http.snapshot().filter { it.second.contains("musicbrainz") }.map { it.first }
        assertEquals(20_000L, dz[1] - dz[0])
        assertEquals(listOf(1000L, 1000L, 1000L, 1000L), mb.zipWithNext { a, b -> b - a })
        assertTrue(mb.last() < dz[1])
    }

    @Test
    fun neverMoreThanThreeWikimediaCallsAtOnceInsideAndOutsideARun() {
        val api = Api(http, "ua", Hosts(clock))
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val busy = clock.condition()
        var open = false
        val url = "https://commons.wikimedia.org/w/api.php?x="
        http.answer = { u ->
            if (u.startsWith(url)) {
                peak.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                clock.external(busy, null) { open }
                inFlight.decrementAndGet()
                Response(200, "{}".toByteArray())
            } else {
                null
            }
        }
        val run = HttpRun()
        val threads = clock.registered {
            (0 until 8).map { i ->
                clock.thread("wm$i") { if (i % 2 == 0) run.on { api.get(url + i) } else api.get(url + i) }
            }
        }
        clock.awaitIdle()
        assertEquals(3, inFlight.get())
        open = true
        busy.signalAll()
        clock.join(threads)
        clock.check()
        assertEquals(3, peak.get())
        assertEquals(8, http.calls.count { it.startsWith(url) })
    }

    @Test
    fun aCancelWakesALimiterPauseAndNoCallFollows() {
        val api = Api(http, "ua", Hosts(clock))
        val u = "https://musicbrainz.org/ws/2/artist/?query=Radiohead&fmt=json&limit=10"
        clock.auto = false
        val run = HttpRun()
        var thrown: Throwable? = null
        val t = clock.thread("mb") {
            try {
                run.on { repeat(2) { api.get(u) } }
            } catch (e: Throwable) {
                thrown = e
            }
        }
        clock.awaitIdle()
        val at = clock.now()
        run.cancel()
        clock.join(t)
        clock.check()
        assertEquals("cancelled", (thrown as IOException).message)
        assertEquals(1, http.calls.size)
        assertEquals(at, clock.now())
        assertEquals(0, clock.drained())
    }

    @Test
    fun aCancelWakesAWikimediaPermitWait() {
        val api = Api(http, "ua", Hosts(clock))
        val busy = clock.condition()
        var open = false
        val url = "https://commons.wikimedia.org/w/api.php?x="
        http.answer = { u -> if (u.startsWith(url)) clock.external(busy, null) { open }.let { Response(200, "{}".toByteArray()) } else null }
        val held = clock.registered { (0 until 3).map { i -> clock.thread("held$i") { api.get(url + i) } } }
        clock.awaitIdle()
        val run = HttpRun()
        var thrown: Throwable? = null
        val waiting = clock.thread("waiting") {
            try {
                run.on { api.get(url + "w") }
            } catch (e: Throwable) {
                thrown = e
            }
        }
        clock.awaitIdle()
        run.cancel()
        clock.join(waiting)
        assertEquals("cancelled", (thrown as IOException).message)
        open = true
        busy.signalAll()
        clock.join(held)
        clock.check()
        assertEquals(3, http.calls.size)
    }

    @Test
    fun anInterruptLeavesEveryWaitAsInterruptedException() {
        val api = Api(http, "ua", Hosts(clock))
        clock.auto = false
        val u = "https://musicbrainz.org/ws/2/artist/?query=Radiohead&fmt=json&limit=10"
        var pause: Throwable? = null
        val a = clock.thread("pause") {
            try {
                repeat(2) { api.get(u) }
            } catch (e: Throwable) {
                pause = e
            }
        }
        clock.awaitIdle()
        a.interrupt()
        clock.join(a)
        assertTrue(pause.toString(), pause is InterruptedException)
        clock.auto = true
        val busy = clock.condition()
        var open = false
        val url = "https://commons.wikimedia.org/w/api.php?x="
        http.answer = { x -> if (x.startsWith(url)) clock.external(busy, null) { open }.let { Response(200, "{}".toByteArray()) } else null }
        val held = clock.registered { (0 until 3).map { i -> clock.thread("held$i") { api.get(url + i) } } }
        clock.awaitIdle()
        var permit: Throwable? = null
        val b = clock.thread("permit") {
            try {
                api.get(url + "w")
            } catch (e: Throwable) {
                permit = e
            }
        }
        clock.awaitIdle()
        b.interrupt()
        clock.join(b)
        assertTrue(permit.toString(), permit is InterruptedException)
        open = true
        busy.signalAll()
        clock.join(held)
        clock.check()
    }

    @Test
    fun aRunWithNoBrowseStillPausesCancelAware() {
        val api = Api(http, "ua", Hosts(clock))
        clock.auto = false
        val run = HttpRun()
        var thrown: Throwable? = null
        val t = clock.thread("worker") {
            try {
                run.on {
                    api.get("https://api.deezer.com/search/artist?q=Radiohead")
                    api.get("https://api.deezer.com/search/artist?q=Radiohead")
                }
            } catch (e: Throwable) {
                thrown = e
            }
        }
        clock.awaitIdle()
        run.cancel()
        clock.join(t)
        if (thrown !is IOException) fail("$thrown")
    }
}
