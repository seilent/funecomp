package net.seilent.funecomp.artist

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ApiTest {
    private val time = FakeTime()
    private val f = Fixtures()
    private val url = "https://example.org/form"
    private val key = "POST $url a=x+y%2Fz&b=%E9%9F%B3"
    private val api = Api(f, "ua", RateLimiter(0) { time.now }, time.sleep, jitter = { 0.25 })

    @After
    fun everyUrlHasAFixture() = assertEquals(emptyList<String>(), f.missing)

    private fun post() = api.post(url, linkedMapOf("a" to "x y/z", "b" to "音"), mapOf("Referer" to "r"))

    private fun queue(vararg r: Response) {
        f.queued[key] = ArrayDeque(r.toList())
    }

    @Test
    fun postSendsTheFormAndHeaders() {
        queue(Response(200, "ok".toByteArray()))
        assertEquals("ok", post()!!.text)
        assertEquals(listOf(key), f.calls)
        assertEquals(mapOf("User-Agent" to "ua", "Referer" to "r"), f.sent.single())
    }

    @Test
    fun busyIsRetriedAfterRetryAfterWithJitter() {
        queue(Response(429, ByteArray(0), mapOf("retry-after" to "2")), Response(503, ByteArray(0), mapOf("retry-after" to "1")), Response(200, "ok".toByteArray()))
        assertEquals("ok", post()!!.text)
        assertEquals(3, f.calls.size)
        assertEquals(listOf(2500L, 1250L), time.sleeps)
    }

    @Test
    fun forbiddenThrows() {
        queue(Response(403, ByteArray(0)))
        assertThrows(IOException::class.java) { post() }
        assertEquals(1, f.calls.size)
    }

    @Test
    fun serverErrorThrows() {
        queue(Response(500, ByteArray(0)))
        assertThrows(IOException::class.java) { post() }
    }

    @Test
    fun notFoundIsNoResult() {
        queue(Response(404, ByteArray(0)))
        assertNull(post())
    }

    @Test
    fun busyOnEveryTryThrows() {
        queue(*Array(4) { Response(503, ByteArray(0)) })
        val e = assertThrows(IOException::class.java) { post() }
        assertTrue(e.message!!.startsWith("busy after 4 tries"))
    }

    private val page = "https://example.org/page"

    private fun download(maxBytes: Int = 1 shl 20, stop: (ByteArray, Int) -> Boolean = { _, _ -> false }) = api.download(page, maxBytes, stop)

    private fun body(n: Int) = ByteArray(n) { (it % 251).toByte() }

    @Test
    fun downloadAccumulatesTheBody() {
        f.queued[page] = ArrayDeque(listOf(Response(200, body(10_000))))
        assertTrue(body(10_000).contentEquals(download()!!.body))
        assertEquals(listOf<String?>("ua"), f.agents)
        assertEquals(10_000, f.read[page])
    }

    @Test
    fun downloadStopsReadingOnceStopAnswersTrue() {
        f.queued[page] = ArrayDeque(listOf(Response(200, body(20_000))))
        val seen = ArrayList<Int>()
        val r = download { _, n -> seen += n; n >= 5000 }!!
        assertEquals(listOf(4096, 8192), seen)
        assertEquals(8192, f.read[page])
        assertEquals(8192, r.body.size)
    }

    @Test
    fun downloadPastTheCapThrows() {
        f.queued[page] = ArrayDeque(listOf(Response(200, body(20_000))))
        val e = assertThrows(IOException::class.java) { download(maxBytes = 10_000) }
        assertEquals("body over 10000 bytes: $page", e.message)
        assertEquals(12_288, f.read[page])
    }

    @Test
    fun downloadKeepsTheGetRules() {
        f.queued[page] = ArrayDeque(listOf(Response(429, ByteArray(0), mapOf("retry-after" to "2")), Response(503, ByteArray(0), mapOf("retry-after" to "1")), Response(200, body(10))))
        assertEquals(10, download()!!.body.size)
        assertEquals(listOf(2500L, 1250L), time.sleeps)
        for (code in listOf(403, 500)) {
            f.queued[page] = ArrayDeque(listOf(Response(code, ByteArray(0))))
            assertThrows("$code", IOException::class.java) { download() }
        }
        f.queued[page] = ArrayDeque(listOf(Response(404, ByteArray(0))))
        assertNull(download())
    }

    @Test
    fun downloadWaitsForTheLimiter() {
        val t = FakeTime()
        val g = Fixtures()
        val a = Api(g, "ua", RateLimiter(1000) { t.now }, t.sleep)
        g.queued[page] = ArrayDeque(listOf(Response(200, body(10)), Response(200, body(10))))
        a.download(page, 100) { _, _ -> false }
        a.download(page, 100) { _, _ -> false }
        assertEquals(listOf(1000L), t.sleeps)
        assertEquals(emptyList<String>(), g.missing)
    }

    @Test
    fun anHttpGetIsServedAsOneDownloadChunk() {
        val h = Http { _, _ -> Response(200, body(300)) }
        val chunks = ArrayList<Int>()
        val r = h.download(page, emptyMap()) { _, _, n -> chunks += n; true }
        assertEquals(listOf(300), chunks)
        assertEquals(200, r.code)
    }
}
