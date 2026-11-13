package net.seilent.funecomp

import android.content.Context
import android.util.Log
import net.seilent.funecomp.artist.Accounts
import net.seilent.funecomp.artist.AppPair
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.Response
import net.seilent.funecomp.artist.ScrobbleQueue
import net.seilent.funecomp.link.Session
import net.seilent.funecomp.link.Transport
import net.seilent.funecomp.proto.ErrorReply
import net.seilent.funecomp.proto.Frame
import net.seilent.funecomp.proto.FrameReader
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.PlayEntry
import net.seilent.funecomp.proto.PlayLogReply
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
        sessions.forEach { it.close("test") }
        Scrobbles.reset()
    }

    private val sessions = ArrayList<Session>()
    private val at = System.currentTimeMillis() / 1000 - 86400
    private val entered = CountDownLatch(1)
    private val go = CountDownLatch(1)
    private val reads = AtomicInteger()
    private val tries = AtomicInteger()
    @Volatile private var down = false

    private val account = object : Http {
        override fun get(url: String, headers: Map<String, String>): Response {
            if ("/listens" !in url) return Response(200, "{\"valid\":true,\"user_name\":\"fake\"}".toByteArray())
            tries.incrementAndGet()
            if (down) throw IOException("offline")
            val n = reads.incrementAndGet()
            Log.i(Remote.TAG, "test get $n ${url.substringAfter("max_ts=").substringBefore('&')}")
            if (n == 1) {
                entered.countDown()
                check(go.await(5, TimeUnit.SECONDS))
            }
            val page = if (n == 1) List(100) { "{\"listened_at\":${at + 30},\"track_metadata\":{\"artist_name\":\"Filler\",\"track_name\":\"F$it\"}}" } else listOf("{\"listened_at\":$at,\"track_metadata\":{\"artist_name\":\"Radiohead\",\"track_name\":\"Airbag\"}}")
            return Response(200, "{\"payload\":{\"count\":${page.size},\"listens\":[${page.joinToString(",")}]}}".toByteArray())
        }

        override fun post(url: String, headers: Map<String, String>, body: ByteArray): Response {
            Log.i(Remote.TAG, "test post ${if ("playing_now" in String(body)) "now" else "listen"}")
            return Response(200, "{\"status\":\"ok\"}".toByteArray())
        }
    }

    private class Dap(val board: String, private val reply: (Node) -> ByteArray?) : Transport {
        private val inbound = LinkedBlockingQueue<ByteArray>()
        private val reader = FrameReader()
        private var pending: ByteArray? = null
        private val held = ArrayList<Frame>()
        @Volatile private var closed = false
        val asked: MutableList<Node> = CopyOnWriteArrayList()
        val holding = CountDownLatch(1)

        override fun open() {}

        override fun read(buf: ByteArray): Int {
            var b = pending
            while (b == null) {
                if (closed) return -1
                b = inbound.poll(50, TimeUnit.MILLISECONDS)
            }
            val n = minOf(buf.size, b.size)
            b.copyInto(buf, 0, 0, n)
            pending = if (n < b.size) b.copyOfRange(n, b.size) else null
            return n
        }

        override fun write(bytes: ByteArray) {
            if (closed) throw IOException("closed")
            for (f in reader.feed(bytes)) {
                if (!f.wantsReply) continue
                when (f.type) {
                    Msg.HELLO -> inbound.put(Frame(f.type, Proto.flagReply, f.seq, HelloReply(Proto.major, Proto.minor, board, "fake", 240, 320, 240, 320, Proto.maxPayload, 30_000, 5_000, Groups.all, 1, 1).encode()).encode())
                    Msg.PLAYLOG_GET, Msg.PLAYLOG_ACK -> {
                        val n = Node.decode(Msg.info.getValue(f.type).name, f.payload)
                        asked += n
                        val body = reply(n)
                        synchronized(held) {
                            if (body == null) {
                                held += f
                                holding.countDown()
                            } else {
                                inbound.put(Frame(f.type, Proto.flagReply, f.seq, body).encode())
                            }
                        }
                    }
                    else -> inbound.put(Frame(f.type, Proto.flagReply or Proto.flagError, f.seq, ErrorReply(3).encode()).encode())
                }
            }
        }

        fun release(code: Int) = synchronized(held) {
            held.forEach { inbound.put(Frame(it.type, Proto.flagReply or Proto.flagError, it.seq, ErrorReply(code).encode()).encode()) }
            held.clear()
            inbound.put(Frame(Msg.STATE, 0, 900, State().encode()).encode())
        }

        override fun close() {
            closed = true
        }
    }

    private fun linkedTo(dap: Dap, playLog: Boolean = true, pushed: CountDownLatch? = null): Session {
        val ready = CountDownLatch(1)
        val s = Session(dap, object : Session.Listener {
            override fun onHello(hello: HelloReply) = ready.countDown()

            override fun onPush(type: Int, body: Node) {
                pushed?.countDown()
            }
        }, Session.Config("test"))
        sessions += s
        Thread { s.run() }.apply { isDaemon = true }.start()
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        Scrobbles.linked(s, playLog, dap.board)
        return s
    }

    private fun exposed(seq: Long = 1, startLocalS: Long = at, zoneS: Int? = 0, title: String = "Airbag") =
        PlayEntry(seq, startLocalS, 200_000, 240_000, 7, title, "Radiohead", listOf("Radiohead"), zoneS = zoneS, exposed = true)

    private fun checking(net: Http = account) {
        Scrobbles.reset()
        app.getSharedPreferences("scrobble", Context.MODE_PRIVATE).edit().putString("listenbrainzUser", "fake").commit()
        val dir = File(app.filesDir, "scrobble")
        File(dir, "own.listenbrainz").apply { dir.mkdirs() }.writeText("from\t${at - 86400}\nc\t0\n")
        ShadowLog.clear()
        Scrobbles.init(app, AppPair.EMPTY, net) {}
    }

    private fun events() = ShadowLog.getLogsForTag(Remote.TAG).map { it.msg }.filter { it.startsWith("test ") || it.startsWith("playlog: ack error") || it.startsWith("playlog: get error") }

    private fun waitFor(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(20)
        }
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
    fun aSecondYieldWhileAFlushIsPendingPostsNoOther() {
        Scrobbles.reset()
        ScrobbleQueue(File(app.filesDir, "scrobble")).addLog(0xA1, listOf(exposed()), setOf(Accounts.LISTEN_BRAINZ), ZoneOffset.UTC, at + 86400)
        checking()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        Scrobbles.state(State(), 0)
        Scrobbles.flush()
        Scrobbles.state(State(), 0)
        down = true
        go.countDown()
        Scrobbles.idle()
        Scrobbles.idle()
        Scrobbles.idle()
        assertEquals(2, tries.get())
    }

    @Test
    fun aQueuedGetReplyStopsTheCheckBetweenReads() {
        Scrobbles.reset()
        ScrobbleQueue(File(app.filesDir, "scrobble")).addLog(0xA1, listOf(exposed()), setOf(Accounts.LISTEN_BRAINZ), ZoneOffset.UTC, at + 86400)
        down = true
        checking()
        Scrobbles.idle()
        val pushed = CountDownLatch(1)
        val dap = Dap("v1") { null }
        linkedTo(dap, pushed = pushed)
        assertTrue(dap.holding.await(5, TimeUnit.SECONDS))
        down = false
        Scrobbles.flush()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        dap.release(3)
        assertTrue(pushed.await(5, TimeUnit.SECONDS))
        go.countDown()
        Scrobbles.idle()
        Scrobbles.idle()
        assertEquals(listOf("test get 1 ${at + 61}", "playlog: get error 3", "test get 2 ${at + 31}"), events())
    }

    @Test
    fun aQueuedStateStopsTheCheckBetweenReadsAndTheFlushBehindItKeepsItsReads() {
        Scrobbles.reset()
        ScrobbleQueue(File(app.filesDir, "scrobble")).addLog(0xA1, listOf(exposed()), setOf(Accounts.LISTEN_BRAINZ), ZoneOffset.UTC, at + 86400)
        checking()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        Scrobbles.state(state(0), 2_000_000_000_000)
        Scrobbles.state(state(1_000), 2_000_000_001_000)
        go.countDown()
        Scrobbles.idle()
        Scrobbles.idle()
        assertEquals(listOf("test get 1 ${at + 61}", "test post now", "test get 2 ${at + 31}"), events())
    }

    @Test
    fun aQueuedAckResultStopsTheCheckBetweenReads() {
        checking()
        val pushed = CountDownLatch(1)
        val dap = Dap("v1") { n -> if (n.scope == "PLAYLOG_ACK") null else PlayLogReply(0xA1, 1, false, listOf(exposed()), 1).encode() }
        linkedTo(dap, pushed = pushed)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertTrue(dap.holding.await(5, TimeUnit.SECONDS))
        dap.release(3)
        assertTrue(pushed.await(5, TimeUnit.SECONDS))
        go.countDown()
        Scrobbles.idle()
        Scrobbles.idle()
        assertEquals(listOf("test get 1 ${at + 61}", "playlog: ack error 3", "test get 2 ${at + 31}"), events())
    }

    private val offline = Http { _, _ -> throw IOException("offline") }

    private fun stored(board: String, vararg entries: PlayEntry): List<Pair<Long, Int?>> {
        Scrobbles.reset()
        Scrobbles.zone = { ZoneId.of("Asia/Tokyo") }
        Scrobbles.init(app, AppPair.EMPTY, offline) {}
        linkedTo(Dap(board) { n -> if (n.scope == "PLAYLOG_ACK") ByteArray(0) else PlayLogReply(0xA1, entries.size.toLong(), false, entries.toList(), 1).encode() })
        val dir = File(app.filesDir, "scrobble")
        waitFor { ScrobbleQueue(dir).after(Accounts.LISTEN_BRAINZ).size == entries.size }
        return ScrobbleQueue(dir).after(Accounts.LISTEN_BRAINZ).map { it.startS to it.z }
    }

    @Test
    fun aPocketBlockEntryWithNoZoneIsStoredAsUtcAndOneWithAZoneUsesIt() {
        assertEquals(listOf(at to 0, at + 300 - 3600 to 3600), stored("pb", exposed(1, at, null), exposed(2, at + 300, 3600, "Lucky")))
    }

    @Test
    fun anotherBoardsEntryWithNoZoneIsReadInThePhoneZone() {
        assertEquals(listOf(at - 32400 to 32400), stored("v1", exposed(1, at, null)))
    }

    @Test
    fun aStateAfterTheLinkClosedIsNotScrobbledLive() {
        Scrobbles.linked(session(), true)
        Scrobbles.unlinked()
        play()
        assertEquals(0, listens())
    }
}
