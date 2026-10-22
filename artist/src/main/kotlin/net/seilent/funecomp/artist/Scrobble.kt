package net.seilent.funecomp.artist

import java.io.File
import java.io.IOException
import java.security.MessageDigest

data class AppPair(val key: String, val secret: String) {
    val empty: Boolean get() = key.isBlank() || secret.isBlank()

    override fun toString() = if (empty) "AppPair(empty)" else "AppPair(set)"

    companion object {
        val EMPTY = AppPair("", "")
    }
}

data class Accounts(
    val lastFm: Boolean = false,
    val apiKey: String = "",
    val secret: String = "",
    val lastFmUser: String = "",
    val lastFmSession: String = "",
    val listenBrainz: Boolean = false,
    val token: String = "",
    val listenBrainzUser: String = "",
) {
    val enabled: Set<String>
        get() = buildSet {
            if (lastFm) add(LAST_FM)
            if (listenBrainz) add(LISTEN_BRAINZ)
        }

    val own: Boolean get() = apiKey.isNotBlank() && secret.isNotBlank()

    override fun toString() = "Accounts(lastFm=$lastFm, lastFmUser=$lastFmUser, session=${lastFmSession.isNotBlank()}, own=$own, listenBrainz=$listenBrainz, listenBrainzUser=$listenBrainzUser, token=${token.isNotBlank()})"

    fun pair(app: AppPair): AppPair? = if (own) AppPair(apiKey.trim(), secret.trim()) else app.takeIf { !it.empty }

    fun ready(service: String, app: AppPair) = when (service) {
        LAST_FM -> lastFm && lastFmSession.isNotBlank() && pair(app) != null
        LISTEN_BRAINZ -> listenBrainz && token.isNotBlank()
        else -> false
    }

    companion object {
        const val LAST_FM = "lastfm"
        const val LISTEN_BRAINZ = "listenbrainz"
    }
}

enum class Sent { OK, RETRY, REAUTH, SKIP, HOLD, KEY }

enum class SignIn(val row: String) {
    OFFLINE("offline"),
    TRY_AGAIN("try again"),
    NOT_APPROVED("not approved"),
    KEY("app key refused"),
    FAILED("sign in failed"),
    ;

    val keeps: Boolean get() = this == OFFLINE || this == TRY_AGAIN || this == NOT_APPROVED
}

class LastFm(
    private val http: Http,
    private val userAgent: String,
    private val limiter: RateLimiter,
    private val sleep: (Long) -> Unit,
    private val base: String = "https://ws.audioscrobbler.com/2.0/",
) {
    class Failed(val code: Int?, message: String) : IOException(message)

    fun token(app: AppPair): String {
        val r = failed(call(mapOf("method" to "auth.getToken", "api_key" to app.key), app.secret))
        return r["token"].str?.takeIf { it.isNotBlank() } ?: throw Failed(null, "no token")
    }

    fun session(app: AppPair, token: String): Pair<String, String> {
        val r = failed(call(mapOf("method" to "auth.getSession", "token" to token, "api_key" to app.key), app.secret))
        val s = r["session"]
        val key = s["key"].str?.takeIf { it.isNotBlank() } ?: throw Failed(null, "no session key")
        val name = s["name"].str?.takeIf { it.isNotBlank() } ?: throw Failed(null, "no session name")
        return name to key
    }

    fun nowPlaying(a: Accounts, p: AppPair, l: Listen): Sent = outcome {
        call(auth(a, p) + track(l, null) + ("method" to "track.updateNowPlaying"), p.secret)
    }

    fun scrobble(a: Accounts, p: AppPair, batch: List<Listen>): Sent = outcome {
        val m = HashMap(auth(a, p))
        m["method"] = "track.scrobble"
        batch.forEachIndexed { i, l -> m += track(l, i) }
        call(m, p.secret)
    }

    private fun auth(a: Accounts, p: AppPair) = mapOf("api_key" to p.key, "sk" to a.lastFmSession)

    private fun failed(r: J): J {
        val code = r["error"].long?.toInt()
        if (code != null) throw Failed(code, "error $code")
        return r
    }

    private fun track(l: Listen, i: Int?): Map<String, String> {
        fun k(name: String) = if (i == null) name else "$name[$i]"
        return buildMap {
            put(k("artist"), l.artist)
            put(k("track"), l.title)
            if (i != null) put(k("timestamp"), l.startS.toString())
            l.album?.takeIf { it.isNotBlank() }?.let { put(k("album"), it) }
            l.durationS?.let { put(k("duration"), it.toString()) }
        }
    }

    private fun outcome(block: () -> J): Sent {
        val r = try {
            block()
        } catch (e: IOException) {
            return Sent.RETRY
        }
        return when (r["error"].long?.toInt()) {
            null -> Sent.OK
            11, 16, 29, 8 -> Sent.RETRY
            9 -> Sent.REAUTH
            6, 7 -> Sent.SKIP
            10, 13, 26 -> Sent.KEY
            else -> Sent.HOLD
        }
    }

    private fun call(params: Map<String, String>, secret: String): J {
        val wait = limiter.reserve()
        if (wait > 0) sleep(wait)
        HttpRun.current.get()?.call(base)
        val all = params.toSortedMap() + ("api_sig" to sign(params, secret.trim())) + ("format" to "json")
        val body = all.entries.joinToString("&") { (k, v) -> Api.enc(k) + "=" + Api.enc(v) }.toByteArray()
        val r = http.post(base, mapOf("User-Agent" to userAgent, "Content-Type" to "application/x-www-form-urlencoded"), body)
        return try {
            J.parse(r.text)
        } catch (e: IllegalArgumentException) {
            throw IOException("http ${r.code}: $base")
        }
    }

    companion object {
        fun sign(params: Map<String, String>, secret: String): String {
            val s = params.filterKeys { it != "format" && it != "callback" }.toSortedMap().entries.joinToString("") { it.key + it.value } + secret
            return md5(s.toByteArray(Charsets.UTF_8))
        }

        fun md5(b: ByteArray): String = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }

        fun signIn(e: IOException, session: Boolean): SignIn {
            if (e !is Failed) return SignIn.OFFLINE
            return when (e.code) {
                8, 11, 16, 29 -> SignIn.TRY_AGAIN
                14 -> if (session) SignIn.NOT_APPROVED else SignIn.FAILED
                4 -> if (session) SignIn.FAILED else SignIn.KEY
                10, 13, 26 -> SignIn.KEY
                else -> SignIn.FAILED
            }
        }
    }
}

class ListenBrainz(
    private val http: Http,
    private val userAgent: String,
    private val limiter: RateLimiter,
    private val sleep: (Long) -> Unit,
    private val base: String = "https://api.listenbrainz.org",
    private val version: String = "",
) {
    fun validate(token: String): String? {
        val wait = limiter.reserve()
        if (wait > 0) sleep(wait)
        val r = http.get("$base/1/validate-token", headers(token))
        if (r.code != 200) throw IOException("http ${r.code}: validate-token")
        val j = try {
            J.parse(r.text)
        } catch (e: IllegalArgumentException) {
            throw IOException("bad json: validate-token")
        }
        return if (j["valid"].bool == true) j["user_name"].str ?: "" else null
    }

    fun nowPlaying(token: String, l: Listen): Sent = submit(token, "playing_now", listOf(l))

    fun submit(token: String, batch: List<Listen>): Sent = submit(token, if (batch.size == 1) "single" else "import", batch)

    private fun submit(token: String, type: String, batch: List<Listen>): Sent {
        val wait = limiter.reserve()
        if (wait > 0) sleep(wait)
        HttpRun.current.get()?.call("$base/1/submit-listens")
        val body = buildString {
            append("{\"listen_type\":\"").append(type).append("\",\"payload\":[")
            batch.forEachIndexed { i, l ->
                if (i > 0) append(',')
                append('{')
                if (type != "playing_now") append("\"listened_at\":").append(l.startS).append(',')
                append("\"track_metadata\":{\"artist_name\":").append(quote(l.artist)).append(",\"track_name\":").append(quote(l.title))
                l.album?.takeIf { it.isNotBlank() }?.let { append(",\"release_name\":").append(quote(it)) }
                append(",\"additional_info\":{")
                l.durationS?.let { append("\"duration_ms\":").append(it * 1000).append(',') }
                append("\"media_player\":\"funeOS\",\"submission_client\":\"funecomp\",\"submission_client_version\":").append(quote(version))
                append("}}}")
            }
            append("]}")
        }
        val r = try {
            http.post("$base/1/submit-listens", headers(token) + ("Content-Type" to "application/json"), body.toByteArray())
        } catch (e: IOException) {
            return Sent.RETRY
        }
        val reset = r.headers["x-ratelimit-reset-in"]?.trim()?.toLongOrNull()?.times(1000)
        if (reset != null && (r.code == 429 || r.headers["x-ratelimit-remaining"]?.trim() == "0")) limiter.backoff(reset)
        return when {
            r.code == 200 -> Sent.OK
            r.code == 401 -> Sent.REAUTH
            r.code == 400 -> Sent.SKIP
            r.code == 429 || r.code >= 500 -> Sent.RETRY
            else -> Sent.HOLD
        }
    }

    private fun headers(token: String) = mapOf("User-Agent" to userAgent, "Authorization" to "Token ${token.trim()}")

    companion object {
        fun quote(s: String) = buildString {
            append('"')
            for (c in s) when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
            append('"')
        }
    }
}

class Scrobbler(
    dir: File,
    http: Http,
    private val accounts: () -> Accounts,
    private val app: AppPair,
    version: String,
    private val clock: () -> Long = { System.currentTimeMillis() },
    sleep: (Long) -> Unit = { Thread.sleep(it) },
    mono: () -> Long = { System.nanoTime() / 1_000_000 },
    private val reauth: (String) -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    val queue = ScrobbleQueue(dir)
    private val ua = "funecomp/$version (https://github.com/seilent/funecomp)"
    private val lastFm = LastFm(http, ua, RateLimiter(LAST_FM_INTERVAL_MS, mono), sleep)
    private val listenBrainz = ListenBrainz(http, ua, RateLimiter(LISTEN_BRAINZ_INTERVAL_MS, mono), sleep, version = version)

    fun pending() = queue.pending(accounts().enabled)

    fun nowPlaying(l: Listen) {
        val a = accounts()
        val p = a.pair(app)
        if (p != null && a.ready(Accounts.LAST_FM, app)) log("scrobble now playing lastfm ${lastFm.nowPlaying(a, p, l)}")
        if (a.ready(Accounts.LISTEN_BRAINZ, app)) log("scrobble now playing listenbrainz ${listenBrainz.nowPlaying(a.token, l)}")
    }

    fun add(l: Listen): Map<String, Sent> {
        val e = queue.add(l, accounts().enabled) ?: return emptyMap()
        log("scrobble queued id=${e.id}")
        return flush()
    }

    fun enable(service: String) = queue.enable(service)

    fun flush(): Map<String, Sent> {
        val a = accounts()
        val out = HashMap<String, Sent>()
        for (service in a.enabled) {
            if (!a.ready(service, app)) continue
            send(service, a)?.let { out[service] = it }
        }
        queue.compact(accounts().enabled)
        return out
    }

    private fun send(service: String, a: Accounts): Sent? {
        val now = clock() / 1000
        val p = a.pair(app)
        var called = false
        while (true) {
            val rest = queue.after(service)
            if (rest.isEmpty()) return if (called) Sent.OK else null
            var last = 0L
            val batch = ArrayList<Listen>()
            for (l in rest) {
                if (batch.size == batchSize(service)) break
                last = l.id
                if (service == Accounts.LAST_FM && now - l.startS > LAST_FM_MAX_AGE_S) continue
                batch += l
            }
            if (batch.isEmpty()) {
                queue.sent(service, last)
                continue
            }
            val r = if (service == Accounts.LAST_FM) lastFm.scrobble(a, p ?: return null, batch) else listenBrainz.submit(a.token, batch)
            called = true
            log("scrobble $service ${batch.size} up to $last $r")
            when (r) {
                Sent.OK, Sent.SKIP -> queue.sent(service, last)
                Sent.REAUTH -> {
                    reauth(service)
                    return r
                }
                else -> return r
            }
        }
    }

    private fun batchSize(service: String) = if (service == Accounts.LAST_FM) LAST_FM_BATCH else LISTEN_BRAINZ_BATCH

    fun lastFmToken() = lastFm.token(app)

    fun lastFmSession(token: String) = lastFm.session(app, token)

    fun listenBrainzUser(token: String) = listenBrainz.validate(token)

    companion object {
        const val LAST_FM_BATCH = 50
        const val LISTEN_BRAINZ_BATCH = 100
        const val LAST_FM_MAX_AGE_S = 14L * 24 * 3600
        const val LAST_FM_INTERVAL_MS = 250L
        const val LISTEN_BRAINZ_INTERVAL_MS = 1000L
    }
}
