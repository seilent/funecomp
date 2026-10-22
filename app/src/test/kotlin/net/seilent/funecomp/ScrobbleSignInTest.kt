package net.seilent.funecomp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import net.seilent.funecomp.artist.Accounts
import net.seilent.funecomp.artist.AppPair
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.Listen
import net.seilent.funecomp.artist.Response
import net.seilent.funecomp.artist.ScrobbleQueue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScrobbleSignInTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("scrobble", Context.MODE_PRIVATE)
    private val net = Lfm()
    private val opened = CopyOnWriteArrayList<String>()

    private class Lfm : Http {
        val calls = CopyOnWriteArrayList<Map<String, String>>()
        val answers = HashMap<String, ArrayDeque<() -> String>>()

        fun on(method: String, vararg a: () -> String) = synchronized(answers) { answers.getOrPut(method) { ArrayDeque() }.addAll(a) }

        fun count(method: String) = calls.count { it["method"] == method }

        override fun get(url: String, headers: Map<String, String>): Response = throw IOException("no get")

        override fun post(url: String, headers: Map<String, String>, body: ByteArray): Response {
            val f = String(body).split('&').associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            calls += f
            val a = synchronized(answers) { answers[f["method"]]?.removeFirstOrNull() }
            val text = a?.invoke() ?: when (f["method"]) {
                "auth.getToken" -> "{\"token\":\"$T1\"}"
                "auth.getSession" -> "{\"session\":{\"name\":\"seilent\",\"key\":\"$SK\",\"subscriber\":0}}"
                else -> "{\"scrobbles\":{\"@attr\":{\"accepted\":1,\"ignored\":0}}}"
            }
            return Response(200, text.toByteArray())
        }
    }

    companion object {
        val T1 = "1".repeat(32)
        val T2 = "2".repeat(32)
        val SK = "5".repeat(32)
        val OWN = AppPair("7".repeat(32), "8".repeat(32))
        const val MIN = 60_000L

        fun error(code: Int) = { "{\"message\":\"x\",\"error\":$code}" }
    }

    @Before
    fun setUp() {
        prefs.edit().putBoolean("lastfm", true).commit()
    }

    @After
    fun tearDown() {
        Scrobbles.reset()
    }

    private fun start(pair: AppPair = ZEROS) {
        Scrobbles.init(app, pair, net)
        Scrobbles.opener = { opened += it; true }
        settle()
    }

    private fun settle() {
        repeat(4) {
            Scrobbles.idle()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun pend(t: String, at: Long = System.currentTimeMillis()) = prefs.edit().putString("pendingToken", t).putLong("pendingAt", at).commit()

    private fun row() = Scrobbles.view.lastFmRow

    private fun queued(n: Int) {
        val q = ScrobbleQueue(File(app.filesDir, "scrobble"))
        q.sent(Accounts.LAST_FM, 0)
        repeat(n) { q.add(Listen(System.currentTimeMillis() / 1000 - 600 + it, "Radiohead", "Track $it", null, 240), setOf(Accounts.LAST_FM)) }
    }

    private fun signedIn(own: AppPair? = null) {
        prefs.edit().putString("lastfmUser", "seilent").putString("lastfmSession", SK).putString("apiKey", own?.key ?: "").putString("secret", own?.secret ?: "").commit()
    }

    @Test
    fun signInOpensTheAuthPageWithTheAppKeyAndTheToken() {
        start()
        Scrobbles.lastFmStart()
        settle()
        assertEquals(1, net.count("auth.getToken"))
        assertTrue(net.calls.single()["api_key"] == ZEROS.key)
        val u = Uri.parse(opened.single())
        assertEquals("https", u.scheme)
        assertEquals("www.last.fm", u.host)
        assertEquals("/api/auth/", u.path)
        assertTrue(u.getQueryParameter("api_key") == ZEROS.key)
        assertEquals(T1, u.getQueryParameter("token"))
        assertNull(u.getQueryParameter("cb"))
        assertEquals(T1, prefs.getString("pendingToken", null))
        assertEquals("waiting", row())
    }

    @Test
    fun twoExchangesForTheSameTokenSendOneGetSession() {
        start()
        pend(T1)
        Scrobbles.lastFmExchange(T1)
        Scrobbles.lastFmExchange(T1)
        settle()
        assertEquals(1, net.count("auth.getSession"))
        assertEquals("seilent", row())
        assertEquals(SK, prefs.getString("lastfmSession", null))
        assertFalse(prefs.contains("pendingToken"))
    }

    @Test
    fun aResumeDuringANotApprovedAnswerRunsOneMoreExchange() {
        start()
        pend(T1)
        val inCall = CountDownLatch(1)
        val release = CountDownLatch(1)
        net.on("auth.getSession", {
            inCall.countDown()
            release.await(5, TimeUnit.SECONDS)
            error(14)()
        })
        Scrobbles.lastFmExchange(T1)
        assertTrue(inCall.await(5, TimeUnit.SECONDS))
        Scrobbles.lastFmExchange(T1)
        release.countDown()
        settle()
        assertEquals(2, net.count("auth.getSession"))
        assertEquals("seilent", row())
    }

    @Test
    fun anExchangeForAnOldTokenDoesNothing() {
        start()
        pend(T2)
        Scrobbles.lastFmExchange(T1)
        settle()
        assertEquals(0, net.count("auth.getSession"))
        assertEquals(T2, prefs.getString("pendingToken", null))
        assertEquals("waiting", row())
    }

    @Test
    fun notApprovedKeepsPendingAndOtherErrorsDropIt() {
        start()
        pend(T1)
        net.on("auth.getSession", error(14))
        Scrobbles.resumed()
        settle()
        assertEquals("not approved", row())
        assertEquals(T1, prefs.getString("pendingToken", null))
        net.on("auth.getSession", error(15))
        Scrobbles.resumed()
        settle()
        assertEquals("sign in failed", row())
        assertFalse(prefs.contains("pendingToken"))
        pend(T2)
        net.on("auth.getSession", error(6))
        Scrobbles.resumed()
        settle()
        assertEquals("sign in failed", row())
        assertFalse(prefs.contains("pendingToken"))
        pend(T1)
        net.on("auth.getSession", error(10))
        Scrobbles.resumed()
        settle()
        assertEquals("app key refused", row())
        assertFalse(prefs.contains("pendingToken"))
    }

    @Test
    fun offlineAndTryAgainKeepPendingAndATapReopensTheSamePage() {
        start()
        pend(T1)
        net.on("auth.getSession", { throw IOException("offline") }, error(29))
        Scrobbles.resumed()
        settle()
        assertEquals("offline", row())
        Scrobbles.resumed()
        settle()
        assertEquals("try again", row())
        assertEquals(T1, prefs.getString("pendingToken", null))
        Scrobbles.lastFmStart()
        settle()
        assertEquals(0, net.count("auth.getToken"))
        assertEquals(T1, Uri.parse(opened.single()).getQueryParameter("token"))
    }

    @Test
    fun aPendingTokenOlderThanAnHourIsDroppedWithNoRequest() {
        start()
        val t0 = System.currentTimeMillis()
        pend(T1, t0)
        Scrobbles.now = { t0 + 61 * MIN }
        Scrobbles.resumed()
        settle()
        assertEquals(0, net.calls.size)
        assertFalse(prefs.contains("pendingToken"))
        assertEquals("not signed in", row())
    }

    @Test
    fun aFreshPendingTokenShowsWaitingAfterAProcessDeath() {
        pend(T1)
        start()
        Scrobbles.reset()
        Scrobbles.init(app, ZEROS, net)
        assertEquals("waiting", row())
        settle()
        assertEquals("waiting", row())
        assertEquals(0, net.calls.size)
    }

    @Test
    fun aTokenAnsweredWhileStoppedIsNotStored() {
        start()
        Scrobbles.opener = null
        Scrobbles.lastFmStart()
        settle()
        assertEquals(1, net.count("auth.getToken"))
        assertFalse(prefs.contains("pendingToken"))
        assertTrue(opened.isEmpty())
        Scrobbles.resumed()
        settle()
        assertEquals(1, net.calls.size)
        assertEquals("not signed in", row())
    }

    @Test
    fun aTapWhileATokenIsAskedSendsNoSecondRequestAndAWaitingTapReopens() {
        start()
        val release = CountDownLatch(1)
        net.on("auth.getToken", {
            release.await(5, TimeUnit.SECONDS)
            "{\"token\":\"$T1\"}"
        })
        Scrobbles.lastFmStart()
        Scrobbles.lastFmStart()
        release.countDown()
        settle()
        Scrobbles.lastFmStart()
        settle()
        assertEquals(1, net.count("auth.getToken"))
        assertEquals(2, opened.size)
        assertEquals(opened[0], opened[1])
    }

    @Test
    fun aTokenTaskBehindAnExchangeThatSignsInAsksNothing() {
        start()
        pend(T1)
        val inCall = CountDownLatch(1)
        val release = CountDownLatch(1)
        net.on("auth.getSession", {
            inCall.countDown()
            release.await(5, TimeUnit.SECONDS)
            "{\"session\":{\"name\":\"seilent\",\"key\":\"$SK\"}}"
        })
        Scrobbles.lastFmExchange(T1)
        assertTrue(inCall.await(5, TimeUnit.SECONDS))
        prefs.edit().remove("pendingToken").remove("pendingAt").commit()
        Scrobbles.lastFmStart()
        release.countDown()
        settle()
        assertEquals(0, net.count("auth.getToken"))
        assertTrue(opened.isEmpty())
        assertEquals("seilent", row())
    }

    @Test
    fun signInClearsAnOwnPairAndAsksWithTheAppPair() {
        prefs.edit().putString("apiKey", OWN.key).putString("secret", OWN.secret).commit()
        start()
        Scrobbles.lastFmStart()
        settle()
        assertEquals("", prefs.getString("apiKey", ""))
        assertEquals("", prefs.getString("secret", ""))
        assertTrue(net.calls.single()["api_key"] == ZEROS.key)
    }

    @Test
    fun aKeyAnswerOnScrobbleShowsKeyRefusedUntilAnOk() {
        signedIn()
        queued(1)
        net.on("track.scrobble", error(10))
        start()
        assertEquals(1, net.count("track.scrobble"))
        assertEquals("app key refused", row())
        assertTrue(Scrobbles.view.signedIn)
        Scrobbles.flush()
        settle()
        assertEquals(2, net.count("track.scrobble"))
        assertEquals("seilent", row())
    }

    @Test
    fun aFlushWithAnEmptyQueueKeepsKeyRefused() {
        signedIn()
        queued(1)
        net.on("track.scrobble", error(26))
        start()
        assertEquals("app key refused", row())
        File(app.filesDir, "scrobble/sent.lastfm").writeText("1")
        Scrobbles.flush()
        settle()
        assertEquals(1, net.count("track.scrobble"))
        assertEquals("app key refused", row())
    }

    @Test
    fun anOwnPairKeyAnswerShowsApiKeyRefused() {
        signedIn(OWN)
        queued(1)
        net.on("track.scrobble", error(10))
        start()
        assertTrue(net.calls.single()["api_key"] == OWN.key)
        assertEquals("api key refused", row())
    }

    @Test
    fun signOutRemovesTheSessionKeysAndKeepsToggleAndQueue() {
        signedIn(OWN)
        pend(T1)
        queued(2)
        net.on("track.scrobble", error(10), error(10))
        start()
        assertEquals("api key refused", row())
        Scrobbles.signOut(Accounts.LAST_FM)
        settle()
        for (k in listOf("lastfmUser", "lastfmSession", "apiKey", "secret", "pendingToken")) assertEquals(k, "", prefs.getString(k, "") ?: "")
        assertFalse(prefs.contains("pendingAt"))
        assertTrue(prefs.getBoolean("lastfm", false))
        assertEquals(2, Scrobbles.view.queued)
        assertTrue(File(app.filesDir, "scrobble/queue.tsv").isFile)
        assertEquals("not signed in", row())
        assertTrue(Scrobbles.view.keyRefused.isEmpty())
    }

    @Test
    fun listenBrainzSignOutClearsTokenAndUser() {
        prefs.edit().putBoolean("listenbrainz", true).putString("token", "tok").putString("listenbrainzUser", "me").commit()
        start()
        Scrobbles.signOut(Accounts.LISTEN_BRAINZ)
        settle()
        assertEquals("", Scrobbles.view.accounts.token)
        assertEquals("", Scrobbles.view.accounts.listenBrainzUser)
        assertTrue(prefs.getBoolean("listenbrainz", false))
    }

    @Test
    fun anOwnPairAccountStaysReadyAndSendsWithItsPair() {
        signedIn(OWN)
        queued(1)
        start()
        assertEquals(1, net.count("track.scrobble"))
        assertTrue(net.calls.single()["api_key"] == OWN.key)
        assertEquals("seilent", row())
    }

    @Test
    fun anEmptyPairShowsNotInThisBuildUnlessAnOwnPairIsSignedIn() {
        start(AppPair.EMPTY)
        assertEquals("not in this build", row())
        Scrobbles.lastFmStart()
        settle()
        assertEquals(0, net.calls.size)
        assertTrue(opened.isEmpty())
        Scrobbles.reset()
        signedIn(OWN)
        start(AppPair.EMPTY)
        assertEquals("seilent", row())
    }

    @Test
    fun noBrowserDropsPendingAndSaysSo() {
        start()
        Scrobbles.opener = { false }
        Scrobbles.lastFmStart()
        settle()
        assertFalse(prefs.contains("pendingToken"))
        assertEquals("no browser", row())
    }

    @Test
    fun getTokenWithNoBrowserSaysSoOnItsRow() {
        start()
        Scrobbles.listenBrainzOpen()
        settle()
        assertEquals(Scrobbles.LISTEN_BRAINZ_TOKEN_URL, opened.single())
        assertEquals("", Scrobbles.view.tokenPage)
        Scrobbles.opener = { false }
        Scrobbles.listenBrainzOpen()
        settle()
        assertEquals("no browser", Scrobbles.view.tokenPage)
    }

    @Test
    fun aFailedTokenRequestStoresNoPendingTokenAndShowsItsRow() {
        val io: () -> String = { throw IOException("down") }
        for ((answer, want) in listOf(error(10) to "app key refused", error(29) to "try again", io to "offline")) {
            Scrobbles.reset()
            net.on("auth.getToken", answer)
            start()
            Scrobbles.lastFmStart()
            settle()
            assertFalse(prefs.contains("pendingToken"))
            assertTrue(opened.isEmpty())
            assertEquals(want, row())
        }
    }

    @Test
    fun aRuntimeFailureOnTheTokenFailsTheSignInAndAllowsANewStart() {
        net.on("auth.getToken", { throw IllegalStateException("bad json") })
        start()
        Scrobbles.lastFmStart()
        settle()
        assertEquals("sign in failed", row())
        assertTrue(opened.isEmpty())
        Scrobbles.lastFmStart()
        settle()
        assertEquals(2, net.count("auth.getToken"))
        assertEquals(T1, Uri.parse(opened.single()).getQueryParameter("token"))
    }

    @Test
    fun aRuntimeFailureOnTheSessionDropsThePendingToken() {
        pend(T1)
        net.on("auth.getSession", { throw IllegalStateException("bad json") })
        start()
        Scrobbles.resumed()
        settle()
        assertFalse(prefs.contains("pendingToken"))
        assertEquals("", prefs.getString("lastfmSession", ""))
        assertEquals("sign in failed", row())
    }

    @Test
    fun aToggleWithStaleAccountsKeepsTheStoredSessionAndPair() {
        signedIn(OWN)
        start()
        Scrobbles.set(Accounts(lastFm = false, listenBrainz = true))
        settle()
        assertFalse(prefs.getBoolean("lastfm", true))
        assertTrue(prefs.getBoolean("listenbrainz", false))
        assertTrue(prefs.getString("lastfmSession", "") == SK)
        assertTrue(prefs.getString("apiKey", "") == OWN.key)
        assertTrue(prefs.getString("secret", "") == OWN.secret)
        assertEquals("seilent", prefs.getString("lastfmUser", ""))
        assertTrue(Scrobbles.view.accounts.lastFmSession == SK)
    }

    @Test
    fun aReauthAnswerClearsTheSessionAndAsksToSignInAgain() {
        signedIn(OWN)
        queued(1)
        net.on("track.scrobble", error(9))
        start()
        assertEquals("", prefs.getString("lastfmSession", ""))
        assertEquals("", prefs.getString("apiKey", ""))
        assertEquals("sign in again", row())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
    fun mainSignsInWithTheTestPairAndTheResumeLeavesNavAndLink() {
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        app.getSharedPreferences(Main.PREFS, Context.MODE_PRIVATE).edit().putBoolean("batteryAsked", true).putString(Main.EXTRA_LINK, "tcp:127.0.0.1:1").commit()
        Scrobbles.init(app, ZEROS, net)
        val a = Robolectric.buildActivity(Main::class.java, Intent(app, Main::class.java).setAction(Intent.ACTION_MAIN).putExtra(Main.EXTRA_PAGE, "scrobbling")).create().start().resume().visible()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            rule.waitForIdle()
            while (shadowOf(app).nextStartedService != null) Unit
            rule.onNodeWithTag("scrobble.account").performClick()
            settle()
            assertTrue(net.calls.single()["api_key"] == ZEROS.key)
            val view = shadowOf(app).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, view?.action)
            assertEquals("www.last.fm", view?.data?.host)
            val pages = a.get().shell.nav.stack.toList()
            a.pause().resume()
            settle()
            assertEquals(1, net.count("auth.getSession"))
            assertEquals(pages, a.get().shell.nav.stack.toList())
            assertNull(shadowOf(app).nextStartedService)
            assertEquals("seilent", row())
        } finally {
            a.pause().stop().destroy()
            Remote.stop()
            Remote.resetArtist()
            Lyrics.reset()
            Firmwares.reset()
        }
    }
}
