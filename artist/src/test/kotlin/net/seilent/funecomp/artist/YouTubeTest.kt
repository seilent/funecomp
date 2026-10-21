package net.seilent.funecomp.artist

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeTest {
    private val f = Fixtures()
    private val api = Api(f, "ua", RateLimiter(0) { 0 }, {})
    private val url = "https://www.youtube.com/channel/UCtest"
    private val head = Fixtures.text("yt-tsukiniterasarete-head.html")
    private val filler = "<div class=\"x\"></div>\n".repeat(5000)

    @After
    fun everyUrlHasAFixture() = assertEquals(emptyList<String>(), f.missing)

    private fun serve(html: String) {
        f.queued[url] = ArrayDeque(listOf(Response(200, html.toByteArray())))
    }

    private fun tagEnd(b: ByteArray) = String(b, Charsets.ISO_8859_1).let { it.indexOf('>', it.indexOf("property=\"og:image\"")) + 1 }

    @Test
    fun aChannelPageIsReadOnlyToTheOgImageTag() {
        val html = (filler.take(30_000) + head + filler).toByteArray()
        f.queued[url] = ArrayDeque(listOf(Response(200, html)))
        val page = YouTube.page(api, url)!!
        val read = f.read[url]!!
        assertTrue("$read", read >= tagEnd(html) && read < tagEnd(html) + f.chunk)
        assertTrue(read < html.size)
        assertEquals(YouTube.ogImage(head), YouTube.ogImage(page))
        assertEquals("月-Tsuki-", YouTube.ogTitle(page))
    }

    @Test
    fun aTagSplitAcrossChunksStillStops() {
        val h = head.toByteArray()
        val mark = String(h, Charsets.ISO_8859_1).indexOf("property=\"og:image\"")
        for (cut in listOf(3, 10, 25)) {
            val html = ByteArray(f.chunk - mark - cut) { ' '.code.toByte() } + h + filler.toByteArray()
            f.queued[url] = ArrayDeque(listOf(Response(200, html)))
            val page = YouTube.page(api, url)!!
            assertEquals("$cut", YouTube.ogImage(head), YouTube.ogImage(page))
            assertEquals("$cut", 2 * f.chunk, f.read[url])
        }
    }

    @Test
    fun aTitleAfterTheStopIsAbsent() {
        val og = Regex("<meta\\s+property=\"og:title\"[^>]*>").find(head)!!.value
        serve(head.replace(og, "") + filler + og)
        val page = YouTube.page(api, url)!!
        assertTrue(YouTube.ogImage(page) != null)
        assertNull(YouTube.ogTitle(page))
    }

    @Test
    fun aPageWithNoTagReadsToTheEnd() {
        serve(filler)
        val page = YouTube.page(api, url)!!
        assertEquals(filler.length, f.read[url])
        assertNull(YouTube.ogImage(page))
    }
}
