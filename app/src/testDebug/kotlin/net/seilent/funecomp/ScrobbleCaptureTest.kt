package net.seilent.funecomp

import android.content.Context
import net.seilent.funecomp.artist.Accounts
import net.seilent.funecomp.artist.AppPair
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.Listen
import net.seilent.funecomp.artist.Response
import net.seilent.funecomp.artist.ScrobbleQueue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScrobbleCaptureTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val urls = CopyOnWriteArrayList<String>()
    private val net = object : Http {
        override fun get(url: String, headers: Map<String, String>): Response {
            urls += url
            return Response(200, "{}".toByteArray())
        }

        override fun post(url: String, headers: Map<String, String>, body: ByteArray): Response {
            urls += url
            return Response(200, "{\"status\":\"ok\"}".toByteArray())
        }
    }

    @After
    fun tearDown() {
        Scrobbles.reset()
    }

    @Test
    fun theGuardRefusesTheRealHostsAndPassesTheCapture() {
        val g = ScrobbleCapture.Guarded(net)
        for (u in listOf("https://ws.audioscrobbler.com/2.0/", "https://api.listenbrainz.org/1/submit-listens")) {
            try {
                g.post(u, emptyMap(), ByteArray(0))
                fail(u)
            } catch (_: IOException) {
            }
            try {
                g.get(u, emptyMap())
                fail(u)
            } catch (_: IOException) {
            }
        }
        g.post("http://127.0.0.1:7701/1/submit-listens", emptyMap(), ByteArray(0))
        assertEquals(listOf("http://127.0.0.1:7701/1/submit-listens"), urls)
    }

    @Test
    fun theBaseComesFromTheDebugPrefOnly() {
        assertNull(ScrobbleCapture.base(app))
        app.getSharedPreferences(ScrobbleCapture.PREFS, Context.MODE_PRIVATE).edit().putString(ScrobbleCapture.BASE, " http://127.0.0.1:7701/ ").commit()
        assertEquals("http://127.0.0.1:7701", ScrobbleCapture.base(app))
    }

    @Test
    fun withACaptureTheSendsGoToItsBase() {
        app.getSharedPreferences("scrobble", Context.MODE_PRIVATE).edit().putBoolean("listenbrainz", true).putString("token", "fake-token").putBoolean("lastfm", true).putString("lastfmSession", "fake-session").commit()
        val q = ScrobbleQueue(File(app.filesDir, "scrobble"))
        q.add(Listen(System.currentTimeMillis() / 1000 - 600, "Radiohead", "Lucky"), setOf(Accounts.LAST_FM, Accounts.LISTEN_BRAINZ))
        Scrobbles.capture = Capture("http://127.0.0.1:7701") { ScrobbleCapture.Guarded(it) }
        Scrobbles.init(app, AppPair("f".repeat(32), "e".repeat(32)), net) {}
        repeat(3) { Scrobbles.idle() }
        assertTrue(urls.toString(), urls.isNotEmpty())
        assertTrue(urls.toString(), urls.all { it.startsWith("http://127.0.0.1:7701/") })
        assertTrue(urls.contains("http://127.0.0.1:7701/2.0/"))
        assertTrue(urls.contains("http://127.0.0.1:7701/1/submit-listens"))
    }
}
