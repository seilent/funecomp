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
}
