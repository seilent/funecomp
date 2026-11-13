package net.seilent.funecomp.artist

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class HttpTest {
    private val sent = AtomicLong()
    private val release = CountDownLatch(1)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/big") { x ->
            x.sendResponseHeaders(200, 0)
            try {
                x.responseBody.use { out ->
                    val chunk = ByteArray(16384) { 'a'.code.toByte() }
                    repeat(256) {
                        out.write(chunk)
                        out.flush()
                        sent.addAndGet(chunk.size.toLong())
                    }
                }
            } catch (_: IOException) {
            }
        }
        createContext("/stall") { x ->
            release.await(10, TimeUnit.SECONDS)
            x.close()
        }
        start()
    }
    private val url = "http://127.0.0.1:${server.address.port}/big"

    @After
    fun stop() {
        release.countDown()
        server.stop(0)
    }

    @Test
    fun aSinkThatAnswersFalseEndsTheRead() {
        var got = 0L
        var calls = 0
        val r = UrlHttp().download(url, emptyMap()) { _, _, n ->
            got += n
            calls++
            false
        }
        assertEquals(200, r.code)
        assertEquals(1, calls)
        assertTrue(got in 1..16384)
    }

    @Test
    fun aSinkThatAnswersTrueReadsToTheEnd() {
        var got = 0L
        UrlHttp().download(url, emptyMap()) { _, _, n ->
            got += n
            true
        }
        assertEquals(256L * 16384, got)
    }

    @Test
    fun aServerThatTakesTheConnectionAndNeverAnswersIsAReadTimeout() {
        assertThrows(HttpTimeout::class.java) { UrlHttp(timeoutMs = 300).get("http://127.0.0.1:${server.address.port}/stall", emptyMap()) }
    }
}
