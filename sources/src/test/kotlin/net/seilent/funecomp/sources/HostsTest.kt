package net.seilent.funecomp.sources

import net.seilent.funekit.artist.ArtCache
import net.seilent.funekit.artist.ArtistArt
import net.seilent.funekit.artist.AwtCodec
import net.seilent.funekit.artist.Collected
import net.seilent.funekit.artist.Fixtures
import net.seilent.funekit.artist.Hosts
import net.seilent.funekit.artist.TEST_AGENT
import net.seilent.funekit.artist.VirtualClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout

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
    fun youtubeAndItsImagesHaveTheirOwnLimiters() {
        val (pages, images) = YouTubeArt {}.limits
        val h = Hosts(clock, YouTubeArt {}.limits)
        assertSame(h.limiter(pages), h.limiter("https://www.youtube.com/@x"))
        assertSame(h.limiter(pages), h.limiter("https://music.youtube.com/@x"))
        for (u in listOf("https://yt3.ggpht.com/a=s176", "https://yt3.googleusercontent.com/a=s900", "https://i.ytimg.com/a.jpg")) assertSame(u, h.limiter(images), h.limiter(u))
        assertNotSame(h.limiter(pages), h.limiter(images))
    }

    @Test
    fun browseLookupAndSweepTogetherKeepTheYouTubeHostsSpaced() {
        val sources = ArtSources.DEFAULT
        val art = ArtistArt(http, AwtCodec(), ArtCache(tmp.newFolder()), { sources }, TEST_AGENT, { 0L }, clock, extensions = listOf(YouTubeArt {}))
        val run = clock.registered {
            listOf(
                clock.thread("browse") { art.browse("Radiohead", null, 53, Collected(), clock.executor(art.browseWorkers)) },
                clock.thread("get") { art.get("宇多田ヒカル", null, 360, 480) },
                clock.thread("sweep") { art.get("TSUKI", null, 360, 480) },
            )
        }
        clock.join(run)
        clock.check()
        assertEquals(0, clock.drained())
        assertTrue("www.youtube.com has calls", http.snapshot().any { Hosts.host(it.second) == "www.youtube.com" })
        for (host in listOf("www.youtube.com", "yt3.ggpht.com")) {
            val g = gaps(host)
            assertTrue("$host $g", g.all { it >= 1000L })
        }
    }
}
