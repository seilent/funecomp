package net.seilent.funecomp

import android.content.Context
import net.seilent.funecomp.artist.AppPair
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.Response
import net.seilent.funecomp.link.Session
import net.seilent.funecomp.link.Transport
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScrobblesTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val bodies = CopyOnWriteArrayList<String>()

    private val net = object : Http {
        override fun get(url: String, headers: Map<String, String>) = Response(200, "{\"valid\":true,\"user_name\":\"fake\"}".toByteArray())

        override fun post(url: String, headers: Map<String, String>, body: ByteArray): Response {
            check(url.startsWith("https://api.listenbrainz.org/")) { "sent to $url" }
            bodies += String(body)
            return Response(200, "{\"status\":\"ok\"}".toByteArray())
        }
    }

    private val idle = object : Transport {
        override fun open() {}

        override fun read(buf: ByteArray) = -1

        override fun write(bytes: ByteArray) {}

        override fun close() {}
    }

    private fun session() = Session(idle, object : Session.Listener {}, Session.Config("test"))

    @Before
    fun setUp() {
        app.getSharedPreferences("scrobble", Context.MODE_PRIVATE).edit().putBoolean("listenbrainz", true).putString("token", "fake-token").commit()
        Scrobbles.init(app, AppPair.EMPTY, net) {}
        Scrobbles.idle()
    }

    @After
    fun tearDown() {
        Scrobbles.reset()
    }

    private fun state(pos: Long) = State(
        source = Enums.Source.local, track = 7, title = "Airbag", artist = "Radiohead", durationMs = 60_000,
        positionMs = pos, sampleMs = 1_000_000 + pos, playing = true, libRev = 3,
    )

    private fun play() {
        Scrobbles.state(state(0), 2_000_000_000_000)
        Scrobbles.state(state(31_000), 2_000_000_031_000)
        Scrobbles.idle()
    }

    private fun listens() = bodies.count { "playing_now" !in it }

    private fun nowPlaying() = bodies.count { "playing_now" in it }

    @Test
    fun withTheLogTheCrossingQueuesNothingAndNowPlayingStillGoes() {
        Scrobbles.linked(session(), true)
        play()
        assertEquals(0, listens())
        assertEquals(1, nowPlaying())
    }

    @Test
    fun withoutTheLogTheCrossingGoesOutLive() {
        Scrobbles.linked(session(), false)
        play()
        assertEquals(1, listens())
        assertEquals(1, nowPlaying())
    }

    @Test
    fun aStateAfterTheLinkClosedIsNotScrobbledLive() {
        Scrobbles.linked(session(), true)
        Scrobbles.unlinked()
        play()
        assertEquals(0, listens())
    }
}
