package net.seilent.funecomp

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Looper
import android.os.SystemClock
import net.seilent.funecomp.art.Thumbs
import net.seilent.funecomp.link.Session
import net.seilent.funecomp.proto.LibRow
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import net.seilent.funecomp.state.LibDelete
import net.seilent.funecomp.ui.Phase
import net.seilent.funekit.artist.Http
import net.seilent.funekit.artist.Response
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HelloTokenTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val sims = ArrayList<Sim>()
    private val logs = CopyOnWriteArrayList<String>()
    private var service: ServiceController<LinkService>? = null
    private val exec = Executors.newSingleThreadExecutor()

    @Before
    fun setUp() {
        Remote.resetThumbs()
        scrobblesOff(app)
        debuggable(true)
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        MediaLink.controls(app, false)
        app.getSharedPreferences(Main.DEVICE_PREFS, Context.MODE_PRIVATE).edit().putBoolean("batteryAsked", true).commit()
        Remote.artist(app, Http { _, _ -> Response(404, ByteArray(0)) })
        Lyrics.init(app, Http { _, _ -> Response(404, ByteArray(0)) })
        BindTokens.init(app, JvmSeal(), exec)
    }

    @After
    fun tearDown() {
        Remote.helloHook = null
        service?.destroy()
        Remote.stop()
        sims.forEach { it.stop() }
        BindTokens.reset()
        exec.shutdownNow()
        Remote.resetThumbs()
        scrobblesReset()
    }

    private fun debuggable(on: Boolean) {
        val f = app.applicationInfo.flags
        app.applicationInfo.flags = if (on) f or ApplicationInfo.FLAG_DEBUGGABLE else f and ApplicationInfo.FLAG_DEBUGGABLE.inv()
    }

    private fun waitFor(ms: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun sim(vararg faults: String, board: String = "v1"): Pair<Sim, String> {
        val s = Sim(Options(Board.of(board), Faults(faults.toList()), log = { logs += it }))
        sims += s
        return s to "tcp:127.0.0.1:${s.listen(0)}"
    }

    private fun start(spec: String) {
        app.getSharedPreferences(Main.PREFS, Context.MODE_PRIVATE).edit().putString(Main.EXTRA_LINK, spec).commit()
        service?.destroy()
        service = Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java).putExtra(Main.EXTRA_LINK, spec)).create().startCommand(0, 1)
    }

    private fun live(vararg faults: String): Pair<Sim, String> = sim(*faults).also { (_, spec) ->
        start(spec)
        waitFor { Remote.phase == Phase.LIVE }
    }

    private fun tap() = Tap(SystemClock.elapsedRealtime())

    private fun replyOnTheLink() {
        val replied = CountDownLatch(1)
        Remote.session!!.request(Msg.LIB_GET, Req.libGet(Enums.View.albums, "", 0, 1, null, null), 10_000) { replied.countDown() }
        assertTrue(replied.await(10, TimeUnit.SECONDS))
    }

    private fun leaked(token: ByteArray): List<String> {
        val hex = token.joinToString("") { "%02x".format(it) }
        val forms = listOf(hex, hex.uppercase(), Base64.getEncoder().encodeToString(token), Base64.getEncoder().withoutPadding().encodeToString(token))
        return ShadowLog.getLogs().map { it.msg }.filter { m -> forms.any { it in m } }
    }

    @Test
    fun theWindowTokenIsStoredUnderTheKeyAndKeptOutOfTheHello() {
        val (s, spec) = live("bind-window")
        val token = s.device.bindToken!!
        waitFor { BindTokens.get(BindTokens.key(spec)) != null }
        assertArrayEquals(token, BindTokens.get(BindTokens.key(spec)))
        assertNull(Remote.device.hello!!.bindToken)
        assertNotNull(Remote.device.hello!!.bootId)
        assertNull(Remote.session!!.hello!!.bindToken)
        val first = Remote.device.hello
        Remote.stop()
        start(spec)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.hello !== first && Remote.device.hello != null }
        assertNull(Remote.device.hello!!.bindToken)
        assertArrayEquals(token, BindTokens.get(BindTokens.key(spec)))
        assertEquals(1, ShadowLog.getLogs().count { it.msg == "bind token stored" })
        assertEquals(emptyList<String>(), leaked(token))
    }

    @Test
    fun theTokenIsStoredUnderItsOwnKeyWhenAnotherStartRacesTheHello() {
        val parked = CountDownLatch(1)
        val release = CountDownLatch(1)
        Remote.helloHook = {
            Remote.helloHook = null
            parked.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        val (s, spec) = sim("bind-window")
        val (_, other) = sim(board = "ap80")
        start(spec)
        assertTrue(parked.await(10, TimeUnit.SECONDS))
        val racer = Thread { Remote.start(app, other) }.apply { start() }
        racer.join(10_000)
        assertFalse(racer.isAlive)
        release.countDown()
        waitFor { Remote.phase == Phase.LIVE && Remote.device.hello?.board == "ap80" }
        waitFor { BindTokens.get(BindTokens.key(spec)) != null }
        assertArrayEquals(s.device.bindToken, BindTokens.get(BindTokens.key(spec)))
        assertNull(BindTokens.get(BindTokens.key(other)))
        assertEquals("ap80", Remote.device.hello!!.board)
    }

    @Test
    fun aHeldBindAskClearsItsMarkWhenTheLinkStops() {
        val (_, spec) = live("bind-ms=60000")
        val results = CopyOnWriteArrayList<Session.Result>()
        Remote.bindAsk(BindTokens.key(spec), tap(), Remote.session) { results += it }
        waitFor { logs.any { it == "bind ask open" } }
        assertTrue(Remote.bindAsking)
        Remote.stop()
        waitFor { !Remote.bindAsking }
        assertTrue(results.single() is Session.Result.Closed)
    }

    @Test
    fun aWaitingDeleteClearsWhenTheLinkStopsAndReadsTheHelloKey() {
        val (s, spec) = live("bind-ms=60000")
        waitFor { Remote.device.lib.snap != null }
        val (store, session) = Remote.deleteTarget()!!
        assertEquals(spec, store)
        val t = s.device.lib.tracks.last()
        val ask = LibDelete.ask(Remote.device, LibRow(t.title, 1, t.id, java.lang.Long.toUnsignedString(t.id)), false, store, session, false)!!
        Remote.libDelete(ask, tap())
        waitFor { logs.any { it == "bind ask open" } }
        assertTrue(Remote.deleteWaiting)
        Remote.stop()
        waitFor { !Remote.deleteWaiting && !Remote.bindAsking }
    }

    @Test
    fun aRemoteDeleteReadsTheHelloTokenAndToastsThroughTheRefusalSink() {
        val (s, spec) = live("bind-window", "del-failed=1")
        waitFor { Remote.device.lib.snap != null && BindTokens.get(BindTokens.key(spec)) != null }
        val (store, session) = Remote.deleteTarget()!!
        assertArrayEquals(s.device.bindToken, BindTokens.get(store))
        val toasts = CopyOnWriteArrayList<Pair<String, String>>()
        val sink: (String, String) -> Unit = { l, t -> toasts += l to t }
        Remote.refusals(sink)
        val t = s.device.lib.tracks.last()
        val ask = LibDelete.ask(Remote.device, LibRow(t.title, 1, t.id, java.lang.Long.toUnsignedString(t.id)), false, store, session, false)!!
        Remote.libDelete(ask, tap())
        waitFor { toasts.isNotEmpty() }
        assertEquals(listOf("song not deleted" to t.title), toasts)
        assertTrue(logs.none { it == "bind ask open" })
        Remote.refusals(null, sink)
    }

    @Test
    fun bindAskWithNoTapOrAnExpiredOneSendsNothing() {
        val (_, spec) = live()
        val results = CopyOnWriteArrayList<Session.Result>()
        Remote.bindAsk(BindTokens.key(spec), null, Remote.session) { results += it }
        Remote.bindAsk(BindTokens.key(spec), Tap(SystemClock.elapsedRealtime() - Tap.LIFE_MS - 1_000), Remote.session) { results += it }
        val used = tap().also { it.take() }
        Remote.bindAsk(BindTokens.key(spec), used, Remote.session) { results += it }
        assertEquals(3, results.size)
        assertTrue(results.all { (it as Session.Result.Failed).error.code == Err.refused })
        replyOnTheLink()
        assertTrue(logs.none { it.startsWith("bind ask") })
        assertFalse(Remote.bindAsking)
    }

    @Test
    fun aHelloWithNoWindowTokenStoresNothingAndAsksNothing() {
        val (_, spec) = live()
        replyOnTheLink()
        assertNull(Remote.device.hello!!.bindToken)
        assertNull(BindTokens.get(BindTokens.key(spec)))
        assertTrue(logs.none { it.startsWith("bind ask") })
        assertTrue(ShadowLog.getLogs().none { it.msg.startsWith("bind token") })
        assertFalse(Remote.bindAsking)
    }

    @Test
    fun twoCallersWithGoodTapsShareOneBindAsk() {
        val skew = AtomicLong()
        val s = Sim(Options(Board.of("v1"), Faults(listOf("bind-ms=60000")), log = { logs += it }, deviceClock = { System.nanoTime() / 1_000_000 + skew.get() })).also { sims += it }
        val spec = "tcp:127.0.0.1:${s.listen(0)}"
        start(spec)
        waitFor { Remote.phase == Phase.LIVE }
        val results = CopyOnWriteArrayList<Session.Result>()
        val key = BindTokens.key(spec)
        Remote.bindAsk(key, tap(), Remote.session) { results += it }
        waitFor { logs.any { it == "bind ask open" } }
        Remote.bindAsk(key, tap(), Remote.session) { results += it }
        assertEquals(1, ShadowLog.getLogs().count { it.msg == "bind ask joined" })
        assertTrue(results.isEmpty())
        skew.addAndGet(60_000)
        waitFor { results.size == 2 }
        assertTrue(results.all { it is Session.Result.Ok })
        assertEquals(1, logs.count { it == "bind ask open" })
        val token = s.device.bindToken!!
        assertArrayEquals(token, BindTokens.get(key))
        assertFalse(Remote.bindAsking)
        assertEquals(emptyList<String>(), leaked(token))
    }

    @Test
    fun theThumbIndexOfADapGoesAtANewBootIdAndStaysAcrossTheSameOne() {
        val dir = File(app.filesDir, "thumb").apply { mkdirs() }
        val prefs = app.getSharedPreferences(Main.DEVICE_PREFS, Context.MODE_PRIVATE)
        fun boot(id: Long) = Sim(Options(Board.of("v1"), Faults(emptyList()), log = { logs += it }, bootId = id)).also { sims += it }
        val first = boot(11)
        val port = listenBelowEphemeral(first)
        val spec = "tcp:127.0.0.1:$port"
        val key = BindTokens.key(spec)
        val index = File(dir, Thumbs.indexName(key, first.device.helloReply().snapRev!!))
        val elsewhere = File(dir, Thumbs.indexName("tcp:127.0.0.1:1", 1))
        fun seed() = index.writeText("track:101:256\t5\n")
        fun relink(id: Long) {
            Remote.stop()
            sims.last().stop()
            listenAgain(boot(id), port)
            start(spec)
            waitFor { Remote.phase == Phase.LIVE }
        }
        seed()
        elsewhere.writeText("track:101:256\t5\n")
        start(spec)
        waitFor { Remote.phase == Phase.LIVE }
        assertFalse(index.exists())
        assertTrue(elsewhere.exists())
        assertEquals("11", prefs.getString("boot.$key", null))
        seed()
        relink(11)
        assertTrue(index.exists())
        relink(12)
        assertFalse(index.exists())
        assertTrue(elsewhere.exists())
        assertEquals("12", prefs.getString("boot.$key", null))
        assertEquals(2, ShadowLog.getLogs().count { it.msg == "boot changed, thumb index dropped" })
    }

    @Test
    fun aDapWithNoBootIdKeepsItsThumbIndexAcrossRestarts() {
        val dir = File(app.filesDir, "thumb").apply { mkdirs() }
        val prefs = app.getSharedPreferences(Main.DEVICE_PREFS, Context.MODE_PRIVATE)
        fun boot() = Sim(Options(Board.of("v1"), Faults(emptyList()), log = { logs += it }, minor = 17)).also { sims += it }
        val first = boot()
        val port = listenBelowEphemeral(first)
        val spec = "tcp:127.0.0.1:$port"
        val key = BindTokens.key(spec)
        val index = File(dir, Thumbs.indexName(key, first.device.helloReply().snapRev!!))
        index.writeText("track:101:256\t5\n")
        start(spec)
        waitFor { Remote.phase == Phase.LIVE }
        assertNull(Remote.device.hello!!.bootId)
        assertTrue(index.exists())
        Remote.stop()
        sims.last().stop()
        listenAgain(boot(), port)
        start(spec)
        waitFor { Remote.phase == Phase.LIVE }
        assertTrue(index.exists())
        assertNull(prefs.getString("boot.$key", null))
        assertTrue(ShadowLog.getLogs().none { it.msg == "boot changed, thumb index dropped" })
    }
}
