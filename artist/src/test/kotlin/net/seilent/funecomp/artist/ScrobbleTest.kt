package net.seilent.funecomp.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.URLDecoder

class ScrobbleTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class Call(val method: String, val url: String, val headers: Map<String, String>, val body: String) {
        val form: Map<String, String>
            get() = body.split('&').filter { it.contains('=') }.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
        val host get() = url.substringAfter("://").substringBefore('/')
    }

    private class Net : Http {
        val calls = ArrayList<Call>()
        val script = HashMap<String, ArrayDeque<() -> Response>>()
        var down = HashSet<String>()

        fun on(host: String, vararg r: () -> Response) {
            script.getOrPut(host) { ArrayDeque() }.addAll(r)
        }

        private fun answer(c: Call): Response {
            if (c.host in down) throw IOException("offline")
            calls += c
            return script[c.host]?.removeFirstOrNull()?.invoke() ?: ok(c.host)
        }

        override fun get(url: String, headers: Map<String, String>) = answer(Call("GET", url, headers, ""))

        override fun post(url: String, headers: Map<String, String>, body: ByteArray) = answer(Call("POST", url, headers, String(body)))

        fun sent(host: String) = calls.filter { it.host == host && it.method == "POST" }
    }

    companion object {
        const val LFM = "ws.audioscrobbler.com"
        const val LB = "api.listenbrainz.org"

        fun file(code: Int, name: String, headers: Map<String, String> = emptyMap()) = { Response(code, Fixtures.load(name), headers) }

        fun ok(host: String) = Response(200, Fixtures.load(if (host == LFM) "lfm-scrobble-doc.json" else "lb-validate-doc.json"))

        val NOW = 1_800_000_000_000L
    }

    private var accounts = Accounts(true, "k".repeat(32), "s3cret", "me", "sk1", true, "tok", "me")
    private var app = AppPair("a".repeat(32), "appsecret")
    private val net = Net()
    private val time = FakeTime()

    private fun scrobbler(dir: java.io.File = tmp.root) = Scrobbler(dir, net, { accounts }, app, "0.1.0", { NOW }, time.sleep, { time.now })

    private fun listen(i: Int, startS: Long = NOW / 1000 - 3600 + i * 300L) = Listen(startS, "Radiohead", "Track $i", "OK Computer", 240)

    private fun lfmTracks() = net.sent(LFM).filter { it.form["method"] == "track.scrobble" }.flatMap { c -> c.form.filterKeys { it.startsWith("track[") }.entries.sortedBy { it.key.removePrefix("track[").removeSuffix("]").toInt() }.map { it.value } }

    private fun lbTracks() = net.sent(LB).filter { "playing_now" !in it.body }.flatMap { c -> Regex("\"track_name\":\"([^\"]+)\"").findAll(c.body).map { it.groupValues[1] } }

    @Test
    fun signatureMatchesTheLastFmDocsExample() {
        val p = mapOf("api_key" to "xxxxxxxx", "method" to "auth.getMobileSession", "password" to "xxxxxxx", "username" to "xxxxxxxx", "format" to "json")
        assertEquals("23243626fa2278a773439d3590e09cf4", LastFm.sign(p, "mysecret"))
    }

    @Test
    fun signatureMatchesTheLastFmAuthSpecExample() {
        val p = mapOf("api_key" to "xxxxxxxxxx", "method" to "auth.getSession", "token" to "yyyyyy", "format" to "json")
        assertEquals("b87d61da3cda91a8b6746c4aef55d6f8", LastFm.sign(p, "ilovecher"))
    }

    @Test
    fun signatureSortsArrayNamesByAscii() {
        val p = mapOf("artist[10]" to "b", "artist[1]" to "a", "api_key" to "k")
        assertEquals(LastFm.md5("api_keykartist[10]bartist[1]as".toByteArray()), LastFm.sign(p, "s"))
    }

    @Test
    fun scrobbleBodyIsSignedFormWithIndexedTracks() {
        val s = scrobbler()
        s.add(listen(0))
        val c = net.sent(LFM).single()
        val f = c.form
        assertEquals("track.scrobble", f["method"])
        assertEquals("Track 0", f["track[0]"])
        assertEquals((NOW / 1000 - 3600).toString(), f["timestamp[0]"])
        assertEquals("240", f["duration[0]"])
        assertEquals("json", f["format"])
        assertEquals(LastFm.sign(f - "api_sig", "s3cret"), f["api_sig"])
        assertTrue(c.headers["User-Agent"]!!.startsWith("funecomp/0.1.0"))
        val lb = net.sent(LB).single()
        assertEquals("Token tok", lb.headers["Authorization"])
        assertTrue(lb.body.contains("\"listen_type\":\"single\""))
        assertTrue(lb.body.contains("\"duration_ms\":240000"))
        assertEquals(0, s.pending())
    }

    @Test
    fun batchesSplitAtFifty() {
        accounts = accounts.copy(listenBrainz = false)
        val s = scrobbler()
        net.down += LFM
        repeat(120) { s.add(listen(it)) }
        net.down.clear()
        net.calls.clear()
        s.flush()
        assertEquals(listOf(50, 50, 20), net.sent(LFM).map { c -> c.form.keys.count { it.startsWith("track[") } })
        assertEquals((0 until 120).map { "Track $it" }, lfmTracks())
        assertEquals(0, s.pending())
    }

    @Test
    fun lastFmDownWhileListenBrainzSendsRecoversWithNoDuplicateOrGap() {
        val s = scrobbler()
        net.down += LFM
        repeat(3) { s.add(listen(it)) }
        assertEquals(listOf("Track 0", "Track 1", "Track 2"), lbTracks())
        assertEquals(3, s.pending())
        net.down.clear()
        s.add(listen(3))
        assertEquals((0..3).map { "Track $it" }, lfmTracks())
        assertEquals((0..3).map { "Track $it" }, lbTracks())
        assertEquals(0, s.pending())
        assertEquals(0, s.queue.size)
    }

    @Test
    fun retryCodesHoldTheQueue() {
        accounts = accounts.copy(listenBrainz = false)
        val s = scrobbler()
        net.on(LFM, { Response(503, "{\"error\":11,\"message\":\"Service Offline\"}".toByteArray()) })
        net.on(LFM, { Response(200, "{\"error\":16,\"message\":\"temporary\"}".toByteArray()) })
        assertEquals(Sent.RETRY, s.add(listen(0))[Accounts.LAST_FM])
        assertEquals(Sent.RETRY, s.add(listen(1))[Accounts.LAST_FM])
        assertEquals(2, s.pending())
        assertEquals(Sent.OK, s.flush()[Accounts.LAST_FM])
        assertEquals(listOf("Track 0", "Track 0", "Track 1", "Track 0", "Track 1"), lfmTracks())
        assertEquals(0, s.pending())
    }

    @Test
    fun invalidKeyHoldsAndInvalidSessionAsksForSignIn() {
        accounts = accounts.copy(listenBrainz = false)
        var expired: String? = null
        val s = Scrobbler(tmp.root, net, { accounts }, app, "0.1.0", { NOW }, time.sleep, { time.now }, reauth = { expired = it })
        net.on(LFM, file(403, "lfm-scrobble-badkey.json"))
        assertEquals(Sent.KEY, s.add(listen(0))[Accounts.LAST_FM])
        net.on(LFM, { Response(403, "{\"error\":9,\"message\":\"Invalid session key - Please re-authenticate\"}".toByteArray()) })
        assertEquals(Sent.REAUTH, s.flush()[Accounts.LAST_FM])
        assertEquals(Accounts.LAST_FM, expired)
        assertEquals(1, s.pending())
    }

    @Test
    fun badParametersSkipTheBatch() {
        accounts = accounts.copy(listenBrainz = false)
        val s = scrobbler()
        net.on(LFM, { Response(400, "{\"error\":6,\"message\":\"Missing required parameter artist\"}".toByteArray()) })
        assertEquals(Sent.OK, s.add(listen(0))[Accounts.LAST_FM])
        assertEquals(0, s.pending())
    }

    @Test
    fun lastFmSkipsEntriesOverFourteenDaysAndListenBrainzImportsThem() {
        val s = scrobbler()
        net.down += LFM
        net.down += LB
        s.add(listen(0, NOW / 1000 - 15 * 86400))
        s.add(listen(1, NOW / 1000 - 15 * 86400 + 60))
        s.add(listen(2))
        net.down.clear()
        net.calls.clear()
        s.flush()
        assertEquals(listOf("Track 2"), lfmTracks())
        assertEquals(listOf("Track 0", "Track 1", "Track 2"), lbTracks())
        assertTrue(net.sent(LB).single().body.contains("\"listen_type\":\"import\""))
    }

    @Test
    fun aServiceEnabledLaterSendsNothingOlder() {
        accounts = accounts.copy(listenBrainz = false)
        val s = scrobbler()
        net.down += LFM
        s.add(listen(0))
        s.add(listen(1))
        accounts = accounts.copy(listenBrainz = true)
        s.enable(Accounts.LISTEN_BRAINZ)
        s.add(listen(2))
        assertEquals(listOf("Track 2"), lbTracks())
        assertEquals(3, s.pending())
    }

    @Test
    fun queueCompactsWithOneServiceOff() {
        val s = scrobbler()
        net.down += LB
        repeat(3) { s.add(listen(it)) }
        assertEquals(3, s.queue.size)
        accounts = accounts.copy(listenBrainz = false)
        s.flush()
        assertEquals(0, s.queue.size)
        assertEquals(0, s.pending())
    }

    @Test
    fun nothingQueuesWithEveryServiceOff() {
        accounts = accounts.copy(lastFm = false, listenBrainz = false)
        val s = scrobbler()
        s.add(listen(0))
        assertEquals(0, s.queue.size)
        assertTrue(net.calls.isEmpty())
    }

    @Test
    fun toggleOffMakesNoCalls() {
        accounts = accounts.copy(lastFm = false, listenBrainz = false)
        val s = scrobbler()
        s.nowPlaying(listen(0))
        s.add(listen(0))
        s.flush()
        assertTrue(net.calls.isEmpty())
    }

    @Test
    fun crashBetweenCompactionAndACursorWriteLosesNothing() {
        val dir = tmp.newFolder("q")
        val s = scrobbler(dir)
        repeat(2) { s.add(listen(it)) }
        net.down += LB
        s.add(listen(2))
        s.add(listen(3))
        assertEquals(2, s.queue.size)
        net.down.clear()
        val files = dir.listFiles()!!.filter { it.isFile }.associateWith { it.readBytes() }
        s.flush()
        files.forEach { (f, b) -> f.writeBytes(b) }
        net.calls.clear()
        val again = scrobbler(dir)
        again.add(listen(4))
        assertEquals(listOf("Track 2", "Track 3", "Track 4"), lbTracks())
        assertEquals(listOf("Track 4"), lfmTracks())
        assertEquals(0, again.queue.size)
    }

    @Test
    fun idsStayMonotonicAfterTheQueueEmpties() {
        val dir = tmp.newFolder("ids")
        val s = scrobbler(dir)
        repeat(2) { s.add(listen(it)) }
        assertEquals(0, s.queue.size)
        val again = scrobbler(dir)
        net.down += LFM
        net.down += LB
        assertEquals(3L, again.queue.add(listen(9), accounts.enabled)!!.id)
    }

    @Test
    fun listenBrainzRateLimitBacksOff() {
        accounts = accounts.copy(lastFm = false)
        val s = scrobbler()
        net.on(LB, { Response(429, ByteArray(0), mapOf("x-ratelimit-reset-in" to "7", "x-ratelimit-remaining" to "0")) })
        assertEquals(Sent.RETRY, s.add(listen(0))[Accounts.LISTEN_BRAINZ])
        val before = time.sleeps.sum()
        s.flush()
        assertTrue(time.sleeps.sum() - before >= 6_000)
        assertEquals(0, s.pending())
    }

    @Test
    fun listenBrainzBadTokenAsksForANewOne() {
        accounts = accounts.copy(lastFm = false)
        var expired: String? = null
        val s = Scrobbler(tmp.root, net, { accounts }, app, "0.1.0", { NOW }, time.sleep, { time.now }, reauth = { expired = it })
        net.on(LB, file(401, "lb-submit-bad.json"))
        assertEquals(Sent.REAUTH, s.add(listen(0))[Accounts.LISTEN_BRAINZ])
        assertEquals(Accounts.LISTEN_BRAINZ, expired)
        assertEquals(1, s.pending())
    }

    @Test
    fun tokenIsSignedWithTheAppPairEvenWithAnOwnPair() {
        val s = scrobbler()
        net.on(LFM, file(200, "lfm-token-doc.json"))
        assertEquals("cf45fe5a3e3cebe168480a086d7fe481", s.lastFmToken())
        val c = net.calls.last()
        val f = c.form
        assertEquals("auth.getToken", f["method"])
        assertTrue(f["api_key"] == app.key)
        assertEquals("json", f["format"])
        assertEquals(listOf("api_key", "method", "api_sig", "format"), c.body.split('&').map { it.substringBefore('=') })
        assertEquals(LastFm.sign(f - "api_sig", app.secret), f["api_sig"])
        assertEquals(LastFm.md5(("api_key" + app.key + "methodauth.getToken" + app.secret).toByteArray()), f["api_sig"])
        assertTrue(c.url.startsWith("https://"))
    }

    @Test
    fun sessionIsSignedWithTheAppPairAndGivesNameAndKey() {
        val s = scrobbler()
        net.on(LFM, file(200, "lfm-session-doc.json"))
        assertEquals("MyLastFMUsername" to "d580d57f32848f5dcf574d1ce18d78b2", s.lastFmSession("cf45fe5a3e3cebe168480a086d7fe481"))
        val f = net.calls.last().form
        assertEquals("auth.getSession", f["method"])
        assertEquals("cf45fe5a3e3cebe168480a086d7fe481", f["token"])
        assertTrue(f["api_key"] == app.key)
        assertEquals(LastFm.md5(("api_key" + app.key + "methodauth.getSessiontokencf45fe5a3e3cebe168480a086d7fe481" + app.secret).toByteArray()), f["api_sig"])
    }

    private fun failure(block: () -> Unit): java.io.IOException? = try {
        block()
        null
    } catch (e: java.io.IOException) {
        e
    }

    private fun answer(code: Int) = { Response(403, "{\"message\":\"x\",\"error\":$code}".toByteArray()) }

    @Test
    fun tokenErrorsMapToTheirRows() {
        val s = scrobbler()
        net.on(LFM, file(403, "lfm-token-badkey.json"))
        assertEquals(SignIn.KEY, LastFm.signIn(failure { s.lastFmToken() }!!, false))
        for ((code, row) in listOf(8 to SignIn.TRY_AGAIN, 11 to SignIn.TRY_AGAIN, 16 to SignIn.TRY_AGAIN, 29 to SignIn.TRY_AGAIN, 4 to SignIn.KEY, 10 to SignIn.KEY, 13 to SignIn.KEY, 26 to SignIn.KEY, 6 to SignIn.FAILED, 14 to SignIn.FAILED)) {
            net.on(LFM, answer(code))
            val e = failure { s.lastFmToken() }
            assertEquals(code, (e as LastFm.Failed).code)
            assertEquals("getToken $code", row, LastFm.signIn(e, false))
        }
        net.on(LFM, { Response(200, "{}".toByteArray()) })
        assertEquals(SignIn.FAILED, LastFm.signIn(failure { s.lastFmToken() }!!, false))
        net.down += LFM
        assertEquals(SignIn.OFFLINE, LastFm.signIn(failure { s.lastFmToken() }!!, false))
        assertEquals("offline", SignIn.OFFLINE.row)
        assertEquals("try again", SignIn.TRY_AGAIN.row)
        assertEquals("app key refused", SignIn.KEY.row)
        assertEquals("sign in failed", SignIn.FAILED.row)
    }

    @Test
    fun sessionErrorsMapToTheirRowsAndPendingRule() {
        val s = scrobbler()
        net.on(LFM, file(403, "lfm-session-unauth.json"))
        assertEquals(SignIn.NOT_APPROVED, LastFm.signIn(failure { s.lastFmSession("t") }!!, true))
        net.on(LFM, file(403, "lfm-session-expired.json"))
        assertEquals(SignIn.FAILED, LastFm.signIn(failure { s.lastFmSession("t") }!!, true))
        net.on(LFM, file(403, "lfm-session-badkey.json"))
        assertEquals(SignIn.KEY, LastFm.signIn(failure { s.lastFmSession("t") }!!, true))
        for ((code, row) in listOf(14 to SignIn.NOT_APPROVED, 4 to SignIn.FAILED, 15 to SignIn.FAILED, 8 to SignIn.TRY_AGAIN, 11 to SignIn.TRY_AGAIN, 16 to SignIn.TRY_AGAIN, 29 to SignIn.TRY_AGAIN, 10 to SignIn.KEY, 13 to SignIn.KEY, 26 to SignIn.KEY, 6 to SignIn.FAILED, 2 to SignIn.FAILED)) {
            net.on(LFM, answer(code))
            assertEquals("getSession $code", row, LastFm.signIn(failure { s.lastFmSession("t") }!!, true))
        }
        net.on(LFM, { Response(200, "{\"session\":{\"name\":\"me\"}}".toByteArray()) })
        assertEquals(SignIn.FAILED, LastFm.signIn(failure { s.lastFmSession("t") }!!, true))
        net.down += LFM
        assertEquals(SignIn.OFFLINE, LastFm.signIn(failure { s.lastFmSession("t") }!!, true))
        assertEquals("not approved", SignIn.NOT_APPROVED.row)
        assertEquals(setOf(SignIn.OFFLINE, SignIn.TRY_AGAIN, SignIn.NOT_APPROVED), SignIn.entries.filter { it.keeps }.toSet())
    }

    @Test
    fun keyCodesOnScrobbleGiveKeyAndHoldTheQueue() {
        accounts = accounts.copy(listenBrainz = false)
        val s = scrobbler()
        net.on(LFM, answer(10))
        assertEquals(Sent.KEY, s.add(listen(0))[Accounts.LAST_FM])
        for (code in listOf(13, 26)) {
            net.on(LFM, answer(code))
            assertEquals("scrobble $code", Sent.KEY, s.flush()[Accounts.LAST_FM])
        }
        assertEquals(1, s.pending())
        assertEquals(Sent.OK, s.flush()[Accounts.LAST_FM])
        assertEquals(0, s.pending())
    }

    @Test
    fun ownPairBeatsTheAppPairAndTheAppPairSignsWithoutOne() {
        accounts = accounts.copy(listenBrainz = false)
        val s = scrobbler()
        s.add(listen(0))
        val own = net.sent(LFM).last().form
        assertTrue(own["api_key"] == "k".repeat(32))
        assertEquals(LastFm.sign(own - "api_sig", "s3cret"), own["api_sig"])
        accounts = accounts.copy(apiKey = "", secret = "")
        s.add(listen(1))
        val viaApp = net.sent(LFM).last().form
        assertTrue(viaApp["api_key"] == app.key)
        assertEquals(LastFm.sign(viaApp - "api_sig", app.secret), viaApp["api_sig"])
        assertEquals("sk1", viaApp["sk"])
    }

    @Test
    fun noPairMeansNotReadyAndNothingSent() {
        app = AppPair.EMPTY
        accounts = accounts.copy(apiKey = "", secret = "", listenBrainz = false)
        assertFalse(accounts.ready(Accounts.LAST_FM, app))
        assertNull(accounts.pair(app))
        val s = scrobbler()
        assertEquals(emptyMap<String, Sent>(), s.add(listen(0)))
        assertTrue(net.sent(LFM).isEmpty())
        assertEquals(1, s.pending())
        assertTrue(accounts.copy(apiKey = "k", secret = "s").ready(Accounts.LAST_FM, app))
        assertFalse(accounts.copy(apiKey = "k").ready(Accounts.LAST_FM, AppPair("a", "")))
        assertTrue(accounts.ready(Accounts.LAST_FM, AppPair("a", "b")))
    }

    @Test
    fun aFlushWithAnEmptyQueueReportsNoAnswer() {
        val s = scrobbler()
        assertEquals(emptyMap<String, Sent>(), s.flush())
        assertTrue(net.calls.isEmpty())
    }

    @Test
    fun printingAPairOrAccountsShowsNoAppValue() {
        val p = AppPair("f".repeat(32), "e".repeat(32))
        assertEquals("AppPair(set)", p.toString())
        assertEquals("AppPair(empty)", AppPair.EMPTY.toString())
        assertEquals("AppPair(empty)", AppPair("k", "").toString())
        val secrets = listOf("a".repeat(32), "b".repeat(32), "c".repeat(32), "d".repeat(32))
        val a = Accounts(true, secrets[0], secrets[1], "me", secrets[2], true, secrets[3], "me").toString()
        assertEquals("Accounts(lastFm=true, lastFmUser=me, session=true, own=true, listenBrainz=true, listenBrainzUser=me, token=true)", a)
        for (v in secrets) assertFalse(a.contains(v))
    }

    @Test
    fun listenBrainzTokenCheck() {
        val s = scrobbler()
        assertEquals("iliekcomputers", s.listenBrainzUser("tok"))
        net.on(LB, file(200, "lb-validate-bad.json"))
        assertEquals(null, s.listenBrainzUser("bad"))
    }

    @Test
    fun nowPlayingIsSentOnceWithoutRetry() {
        val s = scrobbler()
        net.on(LFM, { Response(200, "{\"error\":11,\"message\":\"Service Offline\"}".toByteArray()) })
        s.nowPlaying(listen(0))
        assertEquals(listOf("track.updateNowPlaying"), net.sent(LFM).map { it.form["method"] })
        assertTrue(net.sent(LB).single().body.contains("\"listen_type\":\"playing_now\""))
        assertTrue(!net.sent(LB).single().body.contains("listened_at"))
        assertEquals(0, s.queue.size)
    }
}
