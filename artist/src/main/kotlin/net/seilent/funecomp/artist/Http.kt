package net.seilent.funecomp.artist

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

class Response(val code: Int, val body: ByteArray, val headers: Map<String, String> = emptyMap()) {
    val text: String get() = String(body, Charsets.UTF_8)
}

fun interface Http {
    fun get(url: String, headers: Map<String, String>): Response

    fun post(url: String, headers: Map<String, String>, body: ByteArray): Response = throw IOException("no post: $url")
}

class HttpRun(private val onCall: (String) -> Unit = {}) {
    @Volatile var cancelled = false
        private set
    private val open = HashSet<HttpURLConnection>()

    fun cancel() {
        val drop = synchronized(this) {
            cancelled = true
            open.toList().also { open.clear() }
        }
        drop.forEach { it.disconnect() }
    }

    fun <T> on(block: () -> T): T {
        val prev = current.get()
        current.set(this)
        try {
            return block()
        } finally {
            current.set(prev)
        }
    }

    internal fun call(url: String) {
        if (cancelled) throw IOException("cancelled")
        onCall(url)
    }

    internal fun add(c: HttpURLConnection) = synchronized(this) {
        if (cancelled) throw IOException("cancelled")
        open += c
    }

    internal fun remove(c: HttpURLConnection) = synchronized(this) { open -= c }

    companion object {
        internal val current = ThreadLocal<HttpRun?>()
    }
}

class UrlHttp(private val maxBytes: Int = 16 shl 20, private val timeoutMs: Int = 15_000) : Http {
    override fun get(url: String, headers: Map<String, String>): Response = send(url, headers, null)

    override fun post(url: String, headers: Map<String, String>, body: ByteArray): Response = send(url, headers, body)

    private fun send(url: String, headers: Map<String, String>, body: ByteArray?): Response {
        val run = HttpRun.current.get()
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        run?.add(c)
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = true
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val out = ByteArrayOutputStream()
            (if (code >= 400) c.errorStream else c.inputStream)?.use { input ->
                val buf = ByteArray(16384)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (out.size() + n > maxBytes) throw IOException("body over $maxBytes bytes: $url")
                    out.write(buf, 0, n)
                }
            }
            val h = c.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.firstOrNull() ?: "" }
            return Response(code, out.toByteArray(), h)
        } finally {
            run?.remove(c)
            c.disconnect()
        }
    }
}

class Api(
    private val http: Http,
    private val userAgent: String,
    private val limiter: RateLimiter,
    private val sleep: (Long) -> Unit,
    private val tries: Int = 4,
    private val backoffMs: Long = 2000,
    private val maxRetryAfterMs: Long = 60_000,
) {
    fun get(url: String): Response? {
        val u = if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url
        repeat(tries) { attempt ->
            val wait = limiter.reserve()
            if (wait > 0) sleep(wait)
            HttpRun.current.get()?.call(u)
            val r = http.get(u, mapOf("User-Agent" to userAgent))
            when {
                r.code in 200..299 -> return r
                r.code == 503 || r.code == 429 -> {
                    val after = r.headers["retry-after"]?.trim()?.toLongOrNull()?.times(1000)
                    if (after != null && after > maxRetryAfterMs) throw IOException("retry after ${after / 1000} s: $u")
                    limiter.backoff(after ?: (backoffMs shl attempt))
                }
                r.code == 401 || r.code == 403 -> throw IOException("http ${r.code}: $u")
                r.code in 100..499 -> return null
                else -> throw IOException("http ${r.code}: $u")
            }
        }
        throw IOException("busy after $tries tries: $u")
    }

    fun json(url: String): J? = get(url)?.let {
        try {
            J.parse(it.text)
        } catch (e: IllegalArgumentException) {
            throw IOException("${e.message}: $url")
        }
    }

    companion object {
        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}
