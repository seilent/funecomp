package net.seilent.funecomp

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import net.seilent.funecomp.art.Forward
import net.seilent.funecomp.art.Thumbs
import net.seilent.funecomp.artist.LyricSources
import net.seilent.funecomp.proto.FwStatementBuilder
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.sim.Battery
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.PhoneLine
import net.seilent.funecomp.sim.Sim
import net.seilent.funecomp.state.CardView
import net.seilent.funecomp.state.EqSlots
import net.seilent.funecomp.state.Heart
import net.seilent.funecomp.state.LyricsPlan
import net.seilent.funecomp.state.PushGate
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackgroundTest {
    private val sims = ArrayList<Sim>()
    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        scrobblesOff(app)
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        MediaLink.controls(app, false)
        Remote.artist(app, net.seilent.funecomp.artist.Http { _, _ -> net.seilent.funecomp.artist.Response(404, ByteArray(0)) })
        Lyrics.init(app, net.seilent.funecomp.artist.Http { _, _ -> net.seilent.funecomp.artist.Response(404, ByteArray(0)) })
    }

    @After
    fun tearDown() {
        Remote.stop()
        Remote.shown(false)
        Lyrics.reset()
        Firmwares.reset()
        sims.forEach { it.stop() }
        scrobblesReset()
    }

    private val logs = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun sim(battery: Battery = Battery(87, 87, 0), lrcDir: java.io.File? = null, faults: List<String> = emptyList(), updDir: java.io.File? = null, minor: Int = Proto.minor, phone: List<PhoneLine> = emptyList(), receiver: Boolean = false, output: Int? = null, offsets: Map<Long, Int> = emptyMap(), lib: net.seilent.funecomp.sim.Library = net.seilent.funecomp.sim.Library.synthetic()): String {
        val s = Sim(Options(Board.of("v1"), net.seilent.funecomp.sim.Faults(faults), battery = battery, lrcDir = lrcDir, updDir = updDir, minor = minor, log = { logs += it }, phone = phone, receiver = receiver, lyrOffsets = offsets), lib)
        output?.let { s.device.output(it) }
        sims += s
        return "tcp:127.0.0.1:${s.listen(0)}"
    }

    private fun waitFor(ms: Long = 5000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun text(): String? {
        val n = shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(1) ?: return null
        return n.extras.getCharSequence("android.text")?.toString()
    }

    private fun startIntent(spec: String) = Intent(app, LinkService::class.java).putExtra(Main.EXTRA_LINK, spec)

    private val swept = listOf("Swa", "Swb", "Swc")
    private val wire = ShadowNetwork.newInstance(7)

    private fun artPrefs() = app.getSharedPreferences(Main.ART_PREFS, Context.MODE_PRIVATE)

    private fun network(free: Boolean) {
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (free) shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        for (cb in shadowOf(app.getSystemService(ConnectivityManager::class.java)).networkCallbacks) cb.onCapabilitiesChanged(wire, caps)
    }

    private fun sweepLink(urls: MutableList<String>, sweep: Int?, faults: List<String> = emptyList(), onUrl: (String) -> Unit = {}): org.robolectric.android.controller.ServiceController<LinkService> {
        val e = artPrefs().edit().putInt("defaults", 2)
        for (k in listOf("theaudiodb", "discogs", "fanarttv", "deezer", "youtube")) e.putBoolean(k, false)
        if (sweep != null) e.putInt("sweep", sweep)
        e.commit()
        Remote.resetArtist()
        Remote.artist(app, net.seilent.funecomp.artist.Http { u, _ ->
            urls += u
            onUrl(u)
            net.seilent.funecomp.artist.Response(404, ByteArray(0))
        })
        val names = listOf("Now") + List(Forward.QUEUE_AHEAD.toInt()) { "Q" } + swept
        val lib = net.seilent.funecomp.sim.Library(names.mapIndexed { i, n -> net.seilent.funecomp.sim.Track(i + 1L, n, listOf(n), "A$i", n, "T$i", 200_000, "a", "t$i.flac") })
        val s = Sim(Options(Board.of("v1"), net.seilent.funecomp.sim.Faults(faults), log = { logs += it }), lib)
        sims += s
        return Robolectric.buildService(LinkService::class.java, startIntent("tcp:127.0.0.1:${s.listen(0)}")).create().startCommand(0, 1)
    }

    private fun looked(urls: List<String>, name: String) = urls.any { "query=$name&" in it }

    @Test
    fun aRefusedQueuePageIsAskedAgainForTheNextArtists() {
        Lyrics.set(LyricSources.LRCLIB, false)
        Lyrics.set(LyricSources.NETEASE, false)
        val urls = java.util.concurrent.CopyOnWriteArrayList<String>()
        val c = sweepLink(urls, null, faults = listOf("busy=QUEUE_OP:1"))
        waitFor { Remote.phase == Phase.LIVE }
        network(false)
        waitFor(15_000) { logs.any { " rx QUEUE_OP " in it } && looked(urls, "Now") }
        waitFor(30_000) { looked(urls, "Q") }
        c.destroy()
    }

    private fun pageTries(offset: Long? = null) = org.robolectric.shadows.ShadowLog.getLogsForTag(Remote.TAG)
        .mapNotNull { Regex("art queue page ([0-9]+) [0-9]+ try ([0-9]+) -> (.+)").matchEntire(it.msg) }
        .filter { offset == null || it.groupValues[1].toLong() == offset }
        .map { it.groupValues[2].toInt() to it.groupValues[3] }

    @Test
    fun aQueuePageRefusedForGoodIsNotRetried() {
        Lyrics.set(LyricSources.LRCLIB, false)
        Lyrics.set(LyricSources.NETEASE, false)
        val urls = java.util.concurrent.CopyOnWriteArrayList<String>()
        val c = sweepLink(urls, null, faults = listOf("fail=QUEUE_OP:${Err.noLibrary}"))
        waitFor { Remote.phase == Phase.LIVE }
        network(false)
        waitFor(15_000) { pageTries().contains(1 to "error ${Err.noLibrary}") }
        Thread.sleep(6000)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(pageTries().none { it.first >= 2 })
        assertFalse(looked(urls, "Q"))
        c.destroy()
    }

    @Test
    fun aBusyQueueStillLetsTheSweepRun() {
        Lyrics.set(LyricSources.LRCLIB, false)
        Lyrics.set(LyricSources.NETEASE, false)
        val urls = java.util.concurrent.CopyOnWriteArrayList<String>()
        val c = sweepLink(urls, null, faults = listOf("busy=QUEUE_OP:100"))
        waitFor { Remote.phase == Phase.LIVE }
        network(true)
        waitFor(40_000) { artPrefs().getInt("sweep", 0) == 2 }
        assertTrue(swept.all { looked(urls, it) })
        assertTrue(pageTries().any { it.first == 4 })
        assertTrue(pageTries().none { it.first >= 5 })
        c.destroy()
    }

    @Test
    fun namesFromAnOkPageArePushedWhenTheWrapPageStaysBusy() {
        Lyrics.set(LyricSources.LRCLIB, false)
        Lyrics.set(LyricSources.NETEASE, false)
        val urls = java.util.concurrent.CopyOnWriteArrayList<String>()
        val c = sweepLink(urls, null, faults = listOf("busy-page=0"))
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.queueRev != null }
        network(false)
        Remote.transport(Enums.TransportCmd.repeat, null, null, Enums.Repeat.all)
        waitFor { Remote.device.playback.state.repeat == Enums.Repeat.all }
        Remote.queueOp(Enums.QueueCmd.playAt, 7, null, emptyList())
        waitFor(30_000) { looked(urls, "Swc") }
        waitFor(15_000) { pageTries(0).any { it.first == 4 } }
        assertTrue(pageTries(8).contains(1 to "ok"))
        assertTrue(pageTries(0).none { it.first >= 5 })
        c.destroy()
    }

    @Test
    fun theFirstSweepWaitsForAnUnmeteredNetworkAndThenMarksItsPass() {
        val urls = java.util.concurrent.CopyOnWriteArrayList<String>()
        val c = sweepLink(urls, null)
        waitFor { Remote.phase == Phase.LIVE }
        network(false)
        try {
            waitFor(15_000) { looked(urls, "Q") }
        } catch (e: IllegalStateException) {
            System.err.println("theFirstSweep urls " + urls.toList())
            org.robolectric.shadows.ShadowLog.getLogsForTag(Remote.TAG).takeLast(80).forEach { System.err.println("theFirstSweep app " + it.msg) }
            logs.takeLast(60).forEach { System.err.println("theFirstSweep sim " + it) }
            throw e
        }
        Thread.sleep(2500)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(swept.none { looked(urls, it) })
        assertEquals(0, artPrefs().getInt("sweep", 0))
        network(true)
        waitFor(20_000) { artPrefs().getInt("sweep", 0) == 2 }
        assertTrue(swept.all { looked(urls, it) })
        c.destroy()
    }

    @Test
    fun aSweptInstallSweepsOnAMeteredNetwork() {
        val urls = java.util.concurrent.CopyOnWriteArrayList<String>()
        val c = sweepLink(urls, 2)
        waitFor { Remote.phase == Phase.LIVE }
        network(false)
        waitFor(20_000) { swept.all { looked(urls, it) } }
        c.destroy()
    }

    @Test
    fun aSweepCutByAMeteredNetworkLeavesThePassOpenAndGoesOnLater() {
        val urls = java.util.concurrent.CopyOnWriteArrayList<String>()
        val cut = java.util.concurrent.atomic.AtomicBoolean(false)
        val c = sweepLink(urls, null) { u -> if (swept.any { "query=$it&" in u } && cut.compareAndSet(false, true)) network(false) }
        waitFor { Remote.phase == Phase.LIVE }
        network(true)
        waitFor(20_000) { cut.get() }
        Thread.sleep(2500)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, swept.count { looked(urls, it) })
        assertEquals(0, artPrefs().getInt("sweep", 0))
        network(true)
        waitFor(20_000) { artPrefs().getInt("sweep", 0) == 2 }
        assertTrue(swept.all { looked(urls, it) })
        c.destroy()
    }

    @Test
    fun serviceStaysUpAcrossADropAndStopClearsThePhase() {
        val spec = sim()
        val c = Robolectric.buildService(LinkService::class.java, startIntent(spec)).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE && text() == "connected, dap 87%" }
        sims.forEach { it.stop() }
        waitFor { Remote.phase != Phase.LIVE && text() != "connected, dap 87%" }
        assertFalse(shadowOf(c.get()).isStoppedBySelf)
        c.withIntent(Intent(app, LinkService::class.java).setAction(LinkService.ACTION_STOP)).startCommand(0, 2)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(c.get()).isStoppedBySelf)
        assertEquals(Phase.NONE, Remote.phase)
        c.destroy()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(Remote.onPhase)

        val again = Robolectric.buildService(LinkService::class.java, startIntent(sim())).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE && text() == "connected, dap 87%" }
        again.destroy()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun fetchedMediaArtHasTheMediaSize() {
        val dir = kotlin.io.path.createTempDirectory("art").toFile()
        val lib = net.seilent.funecomp.sim.Library.synthetic()
        val jpeg = java.awt.image.BufferedImage(1000, 1000, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (t in lib.tracks) javax.imageio.ImageIO.write(jpeg, "jpg", java.io.File(dir, "${lib.albumKey(t.id)}.jpg"))
        val s = Sim(Options(Board.of("v1"), artDir = dir))
        sims += s
        val c = Robolectric.buildService(LinkService::class.java, startIntent("tcp:127.0.0.1:${s.listen(0)}")).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.track != null }
        val track = Remote.device.playback.state.track!!
        Remote.wantArt(Thumbs.MEDIA, track, MediaLink.ART_PX, Remote.device.playback.state.album)
        waitFor { Remote.mediaArt(track, MediaLink.ART_PX) != null }
        assertEquals(MediaLink.ART_PX, Remote.mediaArt(track, MediaLink.ART_PX)!!.width)
        c.destroy()
    }

    @Test
    fun eqPresetSaveRepliesWithTheKeyOnce() {
        val c = Robolectric.buildService(LinkService::class.java, startIntent(sim())).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE }
        val got = java.util.concurrent.CopyOnWriteArrayList<Pair<Int?, String?>>()
        Remote.eqPreset(Enums.EqPresetOp.save, EqSlots.HEADPHONES, null, "HD 650", null, false) { code, key -> got += code to key }
        waitFor { got.isNotEmpty() }
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf<Pair<Int?, String?>>(null to "/data/funeos/eq/HD 650.txt"), got.toList())
        assertTrue(logs.any { it == "eq preset 0 key=null -> ok /data/funeos/eq/HD 650.txt" })
        c.destroy()
    }

    @Test
    fun eqPresetWithoutTheBitFailsWithoutAFrame() {
        val c = Robolectric.buildService(LinkService::class.java, startIntent(sim(minor = 4))).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE }
        val got = java.util.concurrent.CopyOnWriteArrayList<Pair<Int?, String?>>()
        Remote.eqPreset(Enums.EqPresetOp.save, EqSlots.HEADPHONES, null, "HD 650", null, false) { code, key -> got += code to key }
        waitFor { got.isNotEmpty() }
        assertEquals(listOf<Pair<Int?, String?>>(-1 to null), got.toList())
        assertTrue(logs.none { "EQ_PRESET" in it })
        c.destroy()
    }

    @Test
    fun bluetoothOffPausesAndOnResumes() {
        val c = Robolectric.buildService(LinkService::class.java, startIntent(sim())).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE }
        app.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF))
        waitFor { Remote.phase == Phase.DOWN && text() == "bluetooth off" }
        Thread.sleep(1500)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Phase.DOWN, Remote.phase)
        app.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON))
        waitFor { Remote.phase == Phase.LIVE && text() == "connected, dap 87%" }
        c.destroy()
    }

    @Test
    fun mediaNotificationShowsTheSongWhileThePhoneControlsAreOn() {
        MediaLink.controls(app, true)
        val c = Robolectric.buildService(LinkService::class.java, startIntent(sim())).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.title != null }
        waitFor { title() == Remote.device.playback.state.title }
        val n = shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(1)!!
        assertEquals("media", n.channelId)
        MediaLink.controls(app, false)
        c.withIntent(Intent(app, LinkService::class.java).setAction(LinkService.ACTION_MEDIA)).startCommand(0, 2)
        waitFor { text() == "connected, dap 87%" }
        assertEquals("link", shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(1)!!.channelId)
        c.destroy()
    }

    @Test
    fun lowDapBatteryNotifiesOnceAndTheLinkTextCarriesIt() {
        val c = Robolectric.buildService(LinkService::class.java, startIntent(sim(Battery.parse("17:14@300")))).create().startCommand(0, 1)
        val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        waitFor { Remote.phase == Phase.LIVE && text()?.startsWith("connected, dap ") == true }
        waitFor { nm.getNotification(2) != null }
        val first = nm.getNotification(2)!!
        assertTrue(first.extras.getCharSequence("android.title")?.toString() in setOf("v1 battery 15%", "v1 battery 14%"))
        assertEquals("dap", first.channelId)
        waitFor { text() == "connected, dap 14%" }
        assertSame(first, nm.getNotification(2))
        c.destroy()
    }

    @Test
    fun lyricsArePutOncePerTrackAcrossADrop() {
        val dir = kotlin.io.path.createTempDirectory("lrc").toFile()
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        Lyrics.init(app, net.seilent.funecomp.artist.Http { url, _ ->
            calls += url
            net.seilent.funecomp.artist.Response(200, "{\"syncedLyrics\":\"[00:01.00]line\\n\",\"instrumental\":false}".toByteArray())
        })
        Lyrics.set(LyricSources.NETEASE, false)
        Lyrics.set(LyricSources.LRCLIB, true)
        val c = Robolectric.buildService(LinkService::class.java, startIntent(sim(lrcDir = dir))).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.track != null }
        Remote.queuePage(0)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        waitFor { logs.count { it.startsWith("stored lrc") } >= 3 }
        Thread.sleep(1500)
        val first = logs.filter { it.startsWith("stored lrc") }.toSet()
        assertTrue(first.size in 3..1 + LyricsPlan.AHEAD)
        assertEquals(first.size, calls.size)
        assertEquals(first.size, logs.count { it.startsWith("stored lrc") })
        sims.forEach { it.stop() }
        waitFor { Remote.phase != Phase.LIVE }
        logs.clear()
        c.withIntent(Intent(app, LinkService::class.java).setAction(LinkService.ACTION_STOP)).startCommand(0, 2)
        c.destroy()
        val again = Robolectric.buildService(LinkService::class.java, startIntent(sim(lrcDir = dir))).create().startCommand(0, 3)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.track != null }
        Remote.queuePage(0)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        Thread.sleep(1500)
        shadowOf(Looper.getMainLooper()).idle()
        val second = logs.filter { it.startsWith("stored lrc") }
        assertEquals(second.toSet().size, second.size)
        assertTrue(second.none { it in first })
        Lyrics.set(LyricSources.LRCLIB, false)
        again.destroy()
    }

    @Test
    fun aRefusedLyricsPutIsNotSentAgain() {
        Lyrics.init(app, net.seilent.funecomp.artist.Http { _, _ ->
            net.seilent.funecomp.artist.Response(200, "{\"syncedLyrics\":\"[00:01.00]line\\n\",\"instrumental\":false}".toByteArray())
        })
        Lyrics.set(LyricSources.NETEASE, false)
        Lyrics.set(LyricSources.LRCLIB, true)
        val c = Robolectric.buildService(LinkService::class.java, startIntent(sim(faults = listOf("refuse=LRC_PUT")))).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.track != null }
        Remote.queuePage(0)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        val end = System.currentTimeMillis() + 30_000
        while (logs.count { it.contains("rx LRC_PUT") } < 1 + LyricsPlan.AHEAD) {
            check(System.currentTimeMillis() < end) { "puts: ${logs.count { it.contains("rx LRC_PUT") }}" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
        Thread.sleep(500)
        val first = logs.count { it.contains("rx LRC_PUT") }
        assertEquals(1 + LyricsPlan.AHEAD, first)
        repeat(3) {
            Lyrics.poke()
            Remote.queuePage(0)
            Thread.sleep(300)
        }
        assertEquals(first, logs.count { it.contains("rx LRC_PUT") })
        Lyrics.set(LyricSources.LRCLIB, false)
        c.destroy()
    }

    private val syntheticPlainBody = "{\"plainLyrics\":\"First line\\nSecond line\\n\",\"syncedLyrics\":null,\"instrumental\":false}"
    private val syntheticSyncedBody = "{\"syncedLyrics\":\"[00:01.00]line\\n\",\"instrumental\":false}"
    private val head = java.lang.Long.toUnsignedString(net.seilent.funecomp.sim.Library.synthetic().tracks.first().id)

    private fun lyricsFrom(body: String, code: Int = 200): MutableList<String> {
        java.io.File(app.filesDir, "lyrics").deleteRecursively()
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        Lyrics.init(app, net.seilent.funecomp.artist.Http { url, _ ->
            calls += url
            net.seilent.funecomp.artist.Response(code, body.toByteArray())
        })
        Lyrics.set(LyricSources.NETEASE, false)
        Lyrics.set(LyricSources.LRCLIB, true)
        return calls
    }

    private fun live(spec: String): org.robolectric.android.controller.ServiceController<LinkService> {
        val c = Robolectric.buildService(LinkService::class.java, startIntent(spec)).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.track != null }
        Remote.queuePage(0)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        return c
    }

    private fun stored() = logs.filter { it.startsWith("stored lrc") }

    private fun neteaseFor(lrclib: (String) -> net.seilent.funecomp.artist.Response, gets: MutableList<String>, posts: MutableList<String>) {
        java.io.File(app.filesDir, "lyrics").deleteRecursively()
        val tracks = net.seilent.funecomp.sim.Library.synthetic().tracks
        Lyrics.init(app, object : net.seilent.funecomp.artist.Http {
            override fun get(url: String, headers: Map<String, String>): net.seilent.funecomp.artist.Response {
                gets += url
                return lrclib(url)
            }

            override fun post(url: String, headers: Map<String, String>, body: ByteArray): net.seilent.funecomp.artist.Response {
                val form = String(body, Charsets.UTF_8)
                posts += "$url $form"
                val json = if (url == net.seilent.funecomp.artist.NetEase.SEARCH) {
                    val q = java.net.URLDecoder.decode(form.substringAfter("s=").substringBefore('&'), "UTF-8")
                    val i = tracks.indexOfFirst { q == it.title || q.startsWith(it.title + " ") }
                    val t = tracks.getOrNull(i)
                    "{\"result\":{\"songs\":[${if (t == null) "" else "{\"id\":${i + 1},\"name\":\"${t.title.replace("\"", "")}\",\"alia\":[],\"tns\":[],\"dt\":${t.durationMs}}"}]},\"code\":200}"
                } else {
                    "{\"lrc\":{\"lyric\":\"${(1..5).joinToString("\\n") { "[00:0$it.00]ne$it" }}\"},\"code\":200}"
                }
                return net.seilent.funecomp.artist.Response(200, json.toByteArray())
            }
        })
        org.robolectric.shadows.ShadowLog.clear()
    }

    private fun putsMatchStored(source: String) {
        var last = -1
        waitFor(15_000) {
            val n = stored().size
            val settled = n == last && puts(source) == n
            last = n
            if (!settled) Thread.sleep(500)
            settled
        }
    }

    private fun puts(source: String) = org.robolectric.shadows.ShadowLog.getLogsForTag(Remote.TAG).count { Regex("lrc put [0-9]+ [0-9]+ synced $source -> ok").matches(it.msg) }

    @Test
    fun aNeteaseSyncedIsPutWhenLrclibMisses() {
        val gets = java.util.concurrent.CopyOnWriteArrayList<String>()
        val posts = java.util.concurrent.CopyOnWriteArrayList<String>()
        neteaseFor({ net.seilent.funecomp.artist.Response(404, ByteArray(0)) }, gets, posts)
        val c = live(sim())
        waitFor(30_000) { stored().size >= 3 }
        Thread.sleep(1500)
        assertTrue(stored().all { it.endsWith("kind=synced") })
        putsMatchStored("netease")
        assertTrue(posts.any { it.startsWith(net.seilent.funecomp.artist.NetEase.LYRIC) })
        Lyrics.set(LyricSources.NETEASE, false)
        c.destroy()
    }

    @Test
    fun turningASourceOnAsksItForTracksAlreadyDone() {
        val gets = java.util.concurrent.CopyOnWriteArrayList<String>()
        val posts = java.util.concurrent.CopyOnWriteArrayList<String>()
        neteaseFor({ net.seilent.funecomp.artist.Response(404, ByteArray(0)) }, gets, posts)
        Lyrics.set(LyricSources.NETEASE, false)
        val c = live(sim())
        waitFor(30_000) { gets.size >= 1 + LyricsPlan.AHEAD }
        Thread.sleep(1000)
        assertTrue(posts.isEmpty())
        assertTrue(stored().isEmpty())
        Lyrics.set(LyricSources.NETEASE, true)
        waitFor(60_000) { stored().size >= 1 + LyricsPlan.AHEAD }
        putsMatchStored("netease")
        Lyrics.set(LyricSources.NETEASE, false)
        c.destroy()
    }

    @Test
    fun aFailingLrclibTripsOnlyItsOwnBreakerAndNeteaseStillPuts() {
        val gets = java.util.concurrent.CopyOnWriteArrayList<String>()
        val posts = java.util.concurrent.CopyOnWriteArrayList<String>()
        neteaseFor({ net.seilent.funecomp.artist.Response(500, ByteArray(0)) }, gets, posts)
        val c = live(sim())
        waitFor(60_000) { stored().size >= 1 + LyricsPlan.AHEAD }
        Thread.sleep(1000)
        assertEquals(3, gets.size)
        putsMatchStored("netease")
        assertEquals(0, puts("lrclib"))
        Lyrics.set(LyricSources.NETEASE, false)
        c.destroy()
    }

    @Test
    fun aPlainOnlyTrackGetsOnePlainPutOnA14Device() {
        lyricsFrom(syntheticPlainBody)
        val c = live(sim())
        waitFor { stored().size >= 3 }
        Thread.sleep(1500)
        val s = stored()
        assertTrue(s.any { it.startsWith("stored lrc track=$head ") })
        assertTrue(s.all { it.endsWith("kind=plain") })
        assertEquals(s.map { it.substringBefore(" bytes") }.toSet().size, s.size)
        assertEquals(s.size, logs.count { it.contains("rx LRC_PUT") })
        Lyrics.set(LyricSources.LRCLIB, false)
        c.destroy()
    }

    private fun plainDir() = kotlin.io.path.createTempDirectory("lrc").toFile().also { it.resolve("$head.lrc").writeText("Device words\n") }

    @Test
    fun aDevicePlainGetsNoPlainPut() {
        lyricsFrom(syntheticPlainBody)
        val c = live(sim(lrcDir = plainDir()))
        waitFor { stored().size >= 3 }
        Thread.sleep(1500)
        assertTrue(stored().none { it.startsWith("stored lrc track=$head ") })
        assertEquals(stored().size, logs.count { it.contains("rx LRC_PUT") })
        Lyrics.set(LyricSources.LRCLIB, false)
        c.destroy()
    }

    @Test
    fun aDevicePlainStillGetsASyncedPut() {
        lyricsFrom(syntheticSyncedBody)
        val c = live(sim(lrcDir = plainDir()))
        waitFor { stored().any { it.startsWith("stored lrc track=$head ") } }
        assertTrue(stored().single { it.startsWith("stored lrc track=$head ") }.endsWith("kind=synced"))
        Lyrics.set(LyricSources.LRCLIB, false)
        c.destroy()
    }

    @Test
    fun aMinor3DeviceGetsNoPlainPut() {
        val calls = lyricsFrom(syntheticPlainBody)
        val c = live(sim(minor = 3))
        waitFor(30_000) { calls.size >= 1 + LyricsPlan.AHEAD }
        Thread.sleep(1500)
        assertEquals(0, logs.count { it.contains("rx LRC_PUT") })
        Lyrics.set(LyricSources.LRCLIB, false)
        c.destroy()
    }

    @Test
    fun aRefusedPlainPutIsNotSentAgain() {
        lyricsFrom(syntheticPlainBody)
        val c = live(sim(faults = listOf("refuse=LRC_PUT")))
        val end = System.currentTimeMillis() + 30_000
        while (logs.count { it.contains("rx LRC_PUT") } < 1 + LyricsPlan.AHEAD) {
            check(System.currentTimeMillis() < end) { "puts: ${logs.count { it.contains("rx LRC_PUT") }}" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
        Thread.sleep(500)
        val first = logs.count { it.contains("rx LRC_PUT") }
        assertEquals(1 + LyricsPlan.AHEAD, first)
        repeat(3) {
            Lyrics.poke()
            Remote.queuePage(0)
            Thread.sleep(300)
        }
        assertEquals(first, logs.count { it.contains("rx LRC_PUT") })
        Lyrics.set(LyricSources.LRCLIB, false)
        c.destroy()
    }

    private fun puts() = logs.count { it.contains("rx LRC_PUT") }

    private fun allPut(): Int {
        waitFor(30_000) { puts() >= 1 + LyricsPlan.AHEAD }
        Thread.sleep(500)
        return puts()
    }

    private fun nextSession(drop: () -> Unit) {
        val hellos = logs.count { it.contains("rx HELLO") }
        drop()
        val end = System.currentTimeMillis() + 30_000
        while (logs.count { it.contains("rx HELLO") } <= hellos || Remote.phase != Phase.LIVE) {
            check(System.currentTimeMillis() < end) { "no second session" }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
            Thread.sleep(100)
        }
        settleLyrics()
    }

    private fun settleLyrics() {
        Remote.queuePage(0)
        repeat(8) {
            Lyrics.poke()
            Thread.sleep(1000)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        }
    }

    @Test
    fun aRefusedPutIsNotSentAgainInTheNextSessionToTheSameDevice() {
        lyricsFrom(syntheticPlainBody)
        val c = live(sim(faults = listOf("refuse=LRC_PUT")))
        val first = allPut()
        nextSession { sims.last().current!!.close("drop") }
        assertTrue(logs.count { it.contains("rx QUEUE_OP") } > 0)
        assertEquals(first, puts())
        Lyrics.set(LyricSources.LRCLIB, true)
        settleLyrics()
        assertEquals(2 * first, puts())
        Lyrics.set(LyricSources.LRCLIB, false)
        c.destroy()
    }

    @Test
    fun aPutErrorThatCanClearIsSentAgainInTheNextSession() {
        lyricsFrom(syntheticPlainBody)
        val c = live(sim(faults = listOf("fail=LRC_PUT:${Err.noSpace}")))
        val first = allPut()
        nextSession { sims.last().current!!.close("drop") }
        assertEquals(2 * first, puts())
        Lyrics.set(LyricSources.LRCLIB, false)
        c.destroy()
    }

    private val synth = net.seilent.funecomp.sim.Library.synthetic().tracks

    private fun uid(i: Int) = java.lang.Long.toUnsignedString(synth[i].id)

    private fun keyOf(t: net.seilent.funecomp.sim.Track, artist: String) = net.seilent.funecomp.artist.Lrclib.Query(t.title, artist, t.album, t.durationMs).key

    private fun marker(i: Int): java.io.File {
        val t = synth[i]
        return java.io.File(app.filesDir, "lyrics").resolve("${keyOf(t, if (i == 0) t.artist else t.artists.joinToString(", "))}.ne.bad")
    }

    private fun mark(f: java.io.File) = f.apply { parentFile!!.mkdirs() }.writeText("1\nhan")

    private fun mark(i: Int) = mark(marker(i))

    private val oldSynced = (1..5).joinToString("\n") { "[00:0$it.00]あめが ふる$it\n[00:1$it.00]视线时间$it" } + "\n"

    private val oldPlain = (1..5).joinToString("\n") { "あめが ふる$it\n视线时间$it" } + "\n"

    private val glyphSynced = (1..5).joinToString("\n") { "[00:0$it.00]あめが 视る$it" } + "\n"

    private fun copy(i: Int, text: String) = kotlin.io.path.createTempDirectory("lrc").toFile().also { it.resolve("${uid(i)}.lrc").writeText(text) }

    private fun appLog(prefix: String) = org.robolectric.shadows.ShadowLog.getLogsForTag(Remote.TAG).map { it.msg }.filter { it.startsWith(prefix) }

    private fun putLines(i: Int) = appLog("lrc put ${uid(i)} ")

    private fun delLines(i: Int) = appLog("lrc del ${uid(i)} ").filter { " -> " in it }

    private fun verdicts(i: Int) = appLog("lrc del ${uid(i)} verdict ")

    private fun dels() = logs.count { it.contains("rx LRC_DEL") }

    private fun resets(i: Int) = logs.count { it == "lyr offset reset track=${uid(i)}" }

    private fun offsetSends() = logs.count { it.contains("rx LYR_OFFSET") }

    private fun lines(i: Int, vararg tails: String) = tails.map { Regex("lrc put ${uid(i)} [0-9]+ $it") }

    private fun matches(want: List<Regex>, got: List<String>) = want.size == got.size && want.zip(got).all { (r, g) -> r.matches(g) }

    private fun settle(n: Int = 3) = repeat(n) {
        Lyrics.poke()
        Thread.sleep(700)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
    }

    private fun heldLive(i: Int, body: String, code: Int = 200, copy: String = oldSynced, faults: List<String> = emptyList(), minor: Int = Proto.minor, late: Boolean = false): org.robolectric.android.controller.ServiceController<LinkService> {
        lyricsFrom(body, code)
        if (late) Lyrics.set(LyricSources.LRCLIB, false)
        mark(i)
        org.robolectric.shadows.ShadowLog.clear()
        val c = live(sim(lrcDir = copy(i, copy), faults = faults, minor = minor, offsets = mapOf(synth[i].id to 500)))
        if (late) {
            waitFor { Remote.device.queue.items.size > LyricsPlan.AHEAD }
            Lyrics.set(LyricSources.LRCLIB, true)
        }
        return c
    }

    private fun aSyncedCopyTakesASyncedAnswer(i: Int) {
        val c = heldLive(i, syntheticSyncedBody, late = i == 0)
        waitFor(15_000) { putLines(i).any { it.endsWith("-> ok") } }
        settle()
        assertTrue(putLines(i).toString(), matches(lines(i, "synced lrclib -> ok"), putLines(i)))
        assertFalse(marker(i).exists())
        assertEquals(0, dels())
        assertEquals(1, resets(i))
        assertEquals(0, offsetSends())
        if (i == 0) assertTrue(appLog("lrc put ").first().startsWith("lrc put ${uid(0)} "))
        c.destroy()
    }

    @Test
    fun aMarkedPlayingSyncedCopyIsReplacedFirst() = aSyncedCopyTakesASyncedAnswer(0)

    @Test
    fun aMarkedQueueSyncedCopyIsReplaced() = aSyncedCopyTakesASyncedAnswer(2)

    private fun aSyncedCopyTakesAPlainAnswer(i: Int) {
        val c = heldLive(i, syntheticPlainBody)
        waitFor(15_000) { stored().any { it.startsWith("stored lrc track=${uid(i)} ") } }
        settle()
        val del = logs.indexOfFirst { it.startsWith("lrc del track=${uid(i)} ") }
        val put = logs.indexOfFirst { it.startsWith("stored lrc track=${uid(i)} ") }
        assertTrue("$del $put", del in 0 until put)
        assertTrue(logs[put].endsWith("kind=plain"))
        assertEquals(1, dels())
        assertFalse(marker(i).exists())
        assertEquals(1, resets(i))
        assertEquals(0, offsetSends())
        c.destroy()
    }

    @Test
    fun aMarkedPlayingSyncedCopyWithAPlainAnswerIsDeletedThenPut() = aSyncedCopyTakesAPlainAnswer(0)

    @Test
    fun aMarkedQueueSyncedCopyWithAPlainAnswerIsDeletedThenPut() = aSyncedCopyTakesAPlainAnswer(2)

    private fun aPlainCopyTakesAPlainAnswer(i: Int) {
        val c = heldLive(i, syntheticPlainBody, copy = oldPlain)
        waitFor(15_000) { stored().any { it.startsWith("stored lrc track=${uid(i)} ") } }
        settle()
        assertEquals(1, putLines(i).size)
        assertTrue(putLines(i).single().endsWith("plain lrclib -> ok"))
        assertEquals(0, dels())
        assertFalse(marker(i).exists())
        c.destroy()
    }

    @Test
    fun aMarkedPlayingPlainCopyTakesOnePlainPut() = aPlainCopyTakesAPlainAnswer(0)

    @Test
    fun aMarkedQueuePlainCopyTakesOnePlainPut() = aPlainCopyTakesAPlainAnswer(2)

    @Test
    fun aMarkedQueuePlainCopyTakesOneSyncedPutAndNoWant() {
        val c = heldLive(2, syntheticSyncedBody, copy = oldPlain)
        waitFor(15_000) { stored().any { it.startsWith("stored lrc track=${uid(2)} ") } }
        settle()
        assertTrue(putLines(2).toString(), matches(lines(2, "synced lrclib -> ok"), putLines(2)))
        assertEquals(0, dels())
        assertFalse(marker(2).exists())
        c.destroy()
    }

    private fun aCopyWithNoAnswer(i: Int, faults: List<String> = emptyList(), minor: Int = Proto.minor): org.robolectric.android.controller.ServiceController<LinkService> {
        val c = heldLive(i, "", code = 404, copy = oldPlain, faults = faults, minor = minor)
        waitFor(15_000) { appLog("lrc del ${uid(i)} ").isNotEmpty() }
        settle()
        return c
    }

    private fun aCopyWithNoAnswerIsDeleted(i: Int) {
        val c = aCopyWithNoAnswer(i)
        assertEquals(1, dels())
        assertEquals(listOf("lrc del ${uid(i)} han -> ok flags false false"), delLines(i))
        assertEquals(listOf("lrc del ${uid(i)} verdict gone"), verdicts(i))
        assertFalse(marker(i).exists())
        assertEquals(0, resets(i))
        assertEquals(0, offsetSends())
        c.destroy()
    }

    @Test
    fun aMarkedPlayingPlainCopyWithNoAnswerIsDeleted() = aCopyWithNoAnswerIsDeleted(0)

    @Test
    fun aMarkedQueuePlainCopyWithNoAnswerIsDeleted() = aCopyWithNoAnswerIsDeleted(2)

    private fun aDeleteThatLeavesTheUsersLyricsDropsTheMarker(i: Int) {
        val c = aCopyWithNoAnswer(i, listOf("del-beside"))
        assertEquals(1, dels())
        assertEquals(listOf("lrc del ${uid(i)} han -> ok flags false true"), delLines(i))
        assertEquals(listOf("lrc del ${uid(i)} verdict gone"), verdicts(i))
        assertFalse(marker(i).exists())
        c.destroy()
    }

    @Test
    fun aPlayingDeleteThatLeavesTheUsersLyricsDropsTheMarker() = aDeleteThatLeavesTheUsersLyricsDropsTheMarker(0)

    @Test
    fun aQueueDeleteThatLeavesTheUsersLyricsDropsTheMarker() = aDeleteThatLeavesTheUsersLyricsDropsTheMarker(2)

    private fun anUntaggedDeleteKeepsTheMarker(i: Int) {
        val c = aCopyWithNoAnswer(i, listOf("del-untagged"))
        settle()
        assertEquals(1, dels())
        assertEquals(listOf("lrc del ${uid(i)} han -> ok"), delLines(i))
        assertEquals(listOf("lrc del ${uid(i)} verdict kept"), verdicts(i))
        assertTrue(marker(i).exists())
        nextSession { sims.last().current!!.close("drop") }
        assertEquals(2, dels())
        assertTrue(marker(i).exists())
        c.destroy()
    }

    @Test
    fun aPlayingUntaggedDeleteKeepsTheMarker() = anUntaggedDeleteKeepsTheMarker(0)

    @Test
    fun aQueueUntaggedDeleteKeepsTheMarker() = anUntaggedDeleteKeepsTheMarker(2)

    private fun aPre15CopyThatNeedsADeleteGetsNothing(i: Int, body: String, code: Int) {
        val c = heldLive(i, body, code = code, minor = 14)
        waitFor(15_000) { appLog("lrc del ${uid(i)} ").isNotEmpty() }
        settle()
        assertEquals(listOf("lrc del ${uid(i)} kept, link below 1.15"), appLog("lrc del ${uid(i)} "))
        assertEquals(0, dels())
        assertEquals(emptyList<String>(), putLines(i))
        assertTrue(marker(i).exists())
        c.destroy()
    }

    @Test
    fun aPre15PlayingCopyWithNoAnswerGetsNothing() = aPre15CopyThatNeedsADeleteGetsNothing(0, "", 404)

    @Test
    fun aPre15QueueCopyWithNoAnswerGetsNothing() = aPre15CopyThatNeedsADeleteGetsNothing(2, "", 404)

    @Test
    fun aPre15QueueSyncedCopyWithAPlainAnswerGetsNothing() = aPre15CopyThatNeedsADeleteGetsNothing(2, syntheticPlainBody, 200)

    @Test
    fun aPre15QueueSyncedCopyTakesASyncedAnswer() {
        val c = heldLive(2, syntheticSyncedBody, minor = 14)
        waitFor(15_000) { putLines(2).any { it.endsWith("-> ok") } }
        settle()
        assertTrue(putLines(2).toString(), matches(lines(2, "synced lrclib -> ok"), putLines(2)))
        assertEquals(0, dels())
        assertFalse(marker(2).exists())
        c.destroy()
    }

    @Test
    fun aMarkerUnderThePlayingTracksSecondArtistKeyIsFoundAndDropped() {
        val lib = net.seilent.funecomp.sim.Library(listOf(net.seilent.funecomp.sim.Track(1L, "A / B", listOf("A", "B"), "Al", "A", "Song", 200_000, "a", "s.flac")) + List(6) { net.seilent.funecomp.sim.Track(it + 2L, "C$it", listOf("C$it"), "Al$it", "C$it", "T$it", 200_000, "a", "t$it.flac") })
        val t = lib.tracks[0]
        lyricsFrom(syntheticSyncedBody)
        val second = java.io.File(app.filesDir, "lyrics").resolve("${keyOf(t, "A, B")}.ne.bad")
        mark(second)
        org.robolectric.shadows.ShadowLog.clear()
        val dir = kotlin.io.path.createTempDirectory("lrc").toFile().also { it.resolve("1.lrc").writeText(oldSynced) }
        val c = live(sim(lrcDir = dir, lib = lib))
        waitFor(15_000) { appLog("lrc put 1 ").any { it.endsWith("-> ok") } }
        settle()
        assertEquals(1, appLog("lrc put 1 ").size)
        assertFalse(second.exists())
        c.destroy()
    }

    @Test
    fun aReadOnlyCardGetsOneDeletePerSession() {
        val c = heldLive(2, "", code = 404, copy = oldPlain, faults = listOf("fail=LRC_DEL:${Err.readOnly}"))
        waitFor(15_000) { dels() >= 1 }
        settle(5)
        assertEquals(1, dels())
        assertEquals(listOf("lrc del ${uid(2)} han -> error ${Err.readOnly}"), delLines(2))
        assertTrue(marker(2).exists())
        nextSession { sims.last().current!!.close("drop") }
        assertEquals(2, dels())
        assertTrue(marker(2).exists())
        c.destroy()
    }

    @Test
    fun aBusyDeleteIsTriedTwicePerSession() {
        val c = heldLive(2, "", code = 404, copy = oldPlain, faults = listOf("busy=LRC_DEL:50"))
        waitFor(15_000) { dels() >= 2 }
        settle(5)
        assertEquals(2, dels())
        assertTrue(marker(2).exists())
        nextSession { sims.last().current!!.close("drop") }
        assertEquals(4, dels())
        c.destroy()
    }

    private fun aFailedHeldPutIsNotRepeated(code: Int) {
        val c = heldLive(2, syntheticSyncedBody, faults = listOf("fail=LRC_PUT:$code"))
        waitFor(15_000) { putLines(2).isNotEmpty() }
        settle()
        assertTrue(putLines(2).toString(), matches(lines(2, "synced lrclib -> error $code"), putLines(2)))
        assertTrue(marker(2).exists())
        c.destroy()
    }

    @Test
    fun aHeldPutAnsweringNoSpaceIsNotRepeated() = aFailedHeldPutIsNotRepeated(Err.noSpace)

    @Test
    fun aHeldPutAnsweringStaleRevIsNotRepeated() = aFailedHeldPutIsNotRepeated(Err.staleRev)

    @Test
    fun aHeldPutAnsweringNoLibraryIsNotRepeated() = aFailedHeldPutIsNotRepeated(Err.noLibrary)

    @Test
    fun aHeldPutAnsweringNotFoundIsNotRepeated() = aFailedHeldPutIsNotRepeated(Err.notFound)

    @Test
    fun aHeldNoCardPutWaitsForTheCard() {
        val c = heldLive(0, syntheticSyncedBody, faults = listOf("fail=LRC_PUT:${Err.noCard}:2"))
        waitFor(15_000) { putLines(0).any { it.endsWith("-> ok") } }
        assertTrue(putLines(0).toString(), matches(lines(0, "synced lrclib -> error ${Err.noCard}", "synced lrclib -> error ${Err.noCard}", "synced lrclib -> ok"), putLines(0)))
        assertFalse(marker(0).exists())
        c.destroy()
    }

    @Test
    fun aBusyHeldPutIsTriedTwicePerSession() {
        val c = heldLive(0, syntheticSyncedBody, faults = listOf("busy=LRC_PUT:50"))
        waitFor(15_000) { putLines(0).size >= 2 }
        settle(5)
        assertTrue(putLines(0).toString(), matches(lines(0, "synced lrclib -> error ${Err.busy}", "synced lrclib -> error ${Err.busy}"), putLines(0)))
        assertTrue(marker(0).exists())
        c.destroy()
    }

    private fun aDapCopyIsJudgedBeforeADelete(copy: String, verdict: String, deleted: Boolean) {
        val c = heldLive(0, "", code = 404, copy = copy)
        waitFor(15_000) { appLog("lrc del ${uid(0)} ").isNotEmpty() }
        settle()
        assertEquals(if (deleted) 1 else 0, dels())
        assertTrue(appLog("lrc del ${uid(0)} ").toString(), appLog("lrc del ${uid(0)} ").contains(verdict))
        c.destroy()
    }

    @Test
    fun aCleanDapCopyDropsTheMarkersWithNothingSent() {
        aDapCopyIsJudgedBeforeADelete((1..5).joinToString("\n") { "[00:0$it.00]あめが 見る$it" } + "\n", "lrc del ${uid(0)} skipped, dap copy clean", false)
        assertFalse(marker(0).exists())
    }

    @Test
    fun anAbsentDapCopyDropsTheMarkersWithNothingSent() {
        val c = heldLive(0, "", code = 404, copy = oldSynced, faults = listOf("lyrget-absent"))
        waitFor(15_000) { appLog("lrc del ${uid(0)} ").isNotEmpty() }
        settle()
        assertEquals(0, dels())
        assertEquals(listOf("lrc del ${uid(0)} skipped, dap copy clean"), appLog("lrc del ${uid(0)} "))
        assertFalse(marker(0).exists())
        c.destroy()
    }

    @Test
    fun aGlyphOnlyDapCopyIsKeptWithNoReplacement() {
        aDapCopyIsJudgedBeforeADelete(glyphSynced, "lrc del ${uid(0)} kept, dap copy glyphs only", false)
        assertTrue(marker(0).exists())
    }

    @Test
    fun aMixedChineseDapCopyIsStillDeleted() {
        aDapCopyIsJudgedBeforeADelete(oldSynced, "lrc del ${uid(0)} verdict gone", true)
        assertFalse(marker(0).exists())
    }

    @Test
    fun aGlyphOnlySyncedCopyIsKeptOverAPlainAnswer() {
        val c = heldLive(0, syntheticPlainBody, copy = glyphSynced)
        waitFor(15_000) { appLog("lrc del ${uid(0)} ").isNotEmpty() }
        settle()
        assertEquals(listOf("lrc del ${uid(0)} kept, dap copy glyphs only"), appLog("lrc del ${uid(0)} "))
        assertEquals(0, dels())
        assertEquals(emptyList<String>(), putLines(0))
        assertTrue(marker(0).exists())
        c.destroy()
    }

    private fun reads() = logs.count { it.contains("rx LYR_GET") }

    @Test
    fun aBusyDapReadKeepsTheCopyAndIsTriedTwicePerSession() {
        val c = heldLive(2, "", code = 404, copy = oldPlain, faults = listOf("busy=LYR_GET:50"))
        waitFor(15_000) { reads() >= 2 }
        settle(5)
        assertEquals(2, reads())
        assertEquals(0, dels())
        assertTrue(marker(2).exists())
        c.destroy()
    }

    @Test
    fun aDapReadWithNoCardWaitsForTheCard() {
        val c = heldLive(2, "", code = 404, copy = oldPlain, faults = listOf("fail=LYR_GET:${Err.noCard}:1"))
        waitFor(15_000) { appLog("lrc del ${uid(2)} verdict ").isNotEmpty() }
        settle()
        assertEquals(2, reads())
        assertEquals(1, dels())
        assertFalse(marker(2).exists())
        c.destroy()
    }

    @Test
    fun anAnswerOverTheSizeLimitCountsAsNoAnswer() {
        val big = (1..500).joinToString("\\n") { "[${"%02d".format(it / 60)}:${"%02d".format(it % 60)}.00]line number $it of a long text" }
        val c = heldLive(0, "{\"syncedLyrics\":\"$big\",\"instrumental\":false}", copy = oldSynced)
        waitFor(15_000) { appLog("lrc del ${uid(0)} ").any { "verdict" in it } }
        settle()
        assertTrue(appLog("lyrics ").any { "bytes over ${Proto.lrcMaxBytes}" in it })
        assertEquals(1, dels())
        assertEquals(emptyList<String>(), putLines(0))
        assertFalse(marker(0).exists())
        c.destroy()
    }

    private fun twoKeyLib(): net.seilent.funecomp.sim.Library = net.seilent.funecomp.sim.Library(
        listOf(net.seilent.funecomp.sim.Track(1L, "C", listOf("C"), "Al0", "C", "T0", 200_000, "a", "t0.flac"), net.seilent.funecomp.sim.Track(2L, "A / B", listOf("A", "B"), "Al", "A", "Song", 200_000, "a", "s.flac")) +
            List(5) { net.seilent.funecomp.sim.Track(it + 3L, "D$it", listOf("D$it"), "Al$it", "D$it", "U$it", 200_000, "a", "u$it.flac") },
    )

    private fun songKey(artist: String) = java.io.File(app.filesDir, "lyrics").resolve("${keyOf(twoKeyLib().tracks[1], artist)}.ne.bad")

    @Test
    fun aMarkerLeftUnderTheOtherKeyNeverDeletesACleanCopy() {
        java.io.File(app.filesDir, "lyrics").deleteRecursively()
        Lyrics.init(app, net.seilent.funecomp.artist.Http { url, _ ->
            if ("artist_name=A%2C%20B" in url) net.seilent.funecomp.artist.Response(200, syntheticSyncedBody.toByteArray()) else net.seilent.funecomp.artist.Response(404, ByteArray(0))
        })
        Lyrics.set(LyricSources.NETEASE, false)
        Lyrics.set(LyricSources.LRCLIB, true)
        val first = songKey("A / B")
        val second = songKey("A, B")
        mark(first)
        mark(second)
        org.robolectric.shadows.ShadowLog.clear()
        val dir = kotlin.io.path.createTempDirectory("lrc").toFile().also { it.resolve("2.lrc").writeText(oldSynced) }
        val c = live(sim(lrcDir = dir, lib = twoKeyLib()))
        waitFor(15_000) { appLog("lrc put 2 ").any { it.endsWith("-> ok") } }
        settle()
        assertFalse(second.exists())
        assertTrue(first.exists())
        Remote.transport(Enums.TransportCmd.next, null, null, null)
        waitFor { Remote.device.playback.state.library == 2L }
        nextSession { sims.last().current!!.close("drop") }
        waitFor(15_000) { appLog("lrc del 2 ").isNotEmpty() }
        settle()
        assertEquals(0, dels())
        assertEquals(listOf("lrc del 2 skipped, dap copy clean"), appLog("lrc del 2 "))
        assertFalse(first.exists())
        c.destroy()
    }

    @Test
    fun markersUnderBothKeysOfThePlayingTrackAreDropped() {
        lyricsFrom(syntheticSyncedBody)
        val lib = twoKeyLib()
        val song = lib.tracks[1]
        val keys = listOf(songKey("A / B"), songKey("A, B"))
        keys.forEach { mark(it) }
        org.robolectric.shadows.ShadowLog.clear()
        val dir = kotlin.io.path.createTempDirectory("lrc").toFile().also { it.resolve("2.lrc").writeText(oldSynced) }
        val c = live(sim(lrcDir = dir, lib = net.seilent.funecomp.sim.Library(listOf(song) + lib.tracks.filter { it != song })))
        waitFor(15_000) { appLog("lrc put 2 ").any { it.endsWith("-> ok") } }
        settle()
        assertEquals(1, appLog("lrc put 2 ").size)
        assertTrue(keys.none { it.exists() })
        c.destroy()
    }

    @Test
    fun aMarkerUnderThePlayingTracksFirstKeyIsFound() {
        lyricsFrom(syntheticSyncedBody)
        val lib = twoKeyLib()
        val song = lib.tracks[1]
        val first = songKey("A / B")
        mark(first)
        org.robolectric.shadows.ShadowLog.clear()
        val dir = kotlin.io.path.createTempDirectory("lrc").toFile().also { it.resolve("2.lrc").writeText(oldSynced) }
        val c = live(sim(lrcDir = dir, lib = net.seilent.funecomp.sim.Library(listOf(song) + lib.tracks.filter { it != song })))
        waitFor(15_000) { appLog("lrc put 2 ").any { it.endsWith("-> ok") } }
        settle()
        assertEquals(1, appLog("lrc put 2 ").size)
        assertFalse(first.exists())
        c.destroy()
    }

    @Test
    fun aPlainPutRefusedAfterADeleteIsNotSentAgainNextSession() {
        val c = heldLive(2, syntheticPlainBody, faults = listOf("refuse=LRC_PUT"))
        waitFor(15_000) { putLines(2).isNotEmpty() }
        settle()
        assertEquals(1, dels())
        assertTrue(putLines(2).toString(), matches(lines(2, "plain lrclib -> error ${Err.refused}"), putLines(2)))
        nextSession { sims.last().current!!.close("drop") }
        assertEquals(1, putLines(2).size)
        assertEquals(1, dels())
        assertTrue(marker(2).exists())
        c.destroy()
    }

    @Test
    fun aFailedSourceSendsNothingForAMarkedCopy() {
        val c = heldLive(0, "", code = 500)
        waitFor(15_000) { appLog("lyrics lrclib ").isNotEmpty() }
        settle(5)
        assertEquals(emptyList<String>(), putLines(0))
        assertEquals(0, dels())
        assertTrue(marker(0).exists())
        c.destroy()
    }

    @Test
    fun aCopyWithoutAMarkerIsLeftAlone() {
        lyricsFrom(syntheticSyncedBody)
        val c = live(sim(lrcDir = copy(2, oldSynced)))
        waitFor(15_000) { stored().size >= 3 }
        settle()
        assertEquals(emptyList<String>(), putLines(2))
        assertEquals(0, dels())
        c.destroy()
    }

    @Test
    fun everySourceOffSendsNothingForAMarkedCopy() {
        lyricsFrom("", code = 404)
        Lyrics.set(LyricSources.LRCLIB, false)
        mark(2)
        val c = live(sim(lrcDir = copy(2, oldPlain)))
        settle(4)
        assertEquals(0, dels())
        assertEquals(0, puts())
        assertTrue(marker(2).exists())
        c.destroy()
    }

    @Test
    fun aWantPutDropsAMarker() {
        lyricsFrom(syntheticSyncedBody)
        mark(2)
        val c = live(sim())
        waitFor(15_000) { stored().any { it.startsWith("stored lrc track=${uid(2)} ") } }
        settle()
        assertFalse(marker(2).exists())
        assertEquals(0, dels())
        c.destroy()
    }

    private val mixedBody = "rule=1\n" + (1..5).joinToString("\n") { "[00:0$it.00]あめが 视る$it" }

    @Test
    fun theSweepMarksARejectedCacheFileOnceAndRunsBeforeTheFirstLyricsRun() {
        Lyrics.reset()
        val dir = java.io.File(app.filesDir, "lyrics").apply { deleteRecursively(); mkdirs() }
        dir.resolve("abc.ne.lrc").writeText(mixedBody)
        app.getSharedPreferences("lyrics", Context.MODE_PRIVATE).edit().remove("nemix").commit()
        val gate = java.util.concurrent.CountDownLatch(1)
        Lyrics.phone { gate.await() }
        org.robolectric.shadows.ShadowLog.clear()
        Lyrics.init(app, net.seilent.funecomp.artist.Http { _, _ -> net.seilent.funecomp.artist.Response(200, syntheticSyncedBody.toByteArray()) })
        Lyrics.set(LyricSources.NETEASE, false)
        Lyrics.set(LyricSources.LRCLIB, true)
        val c = Robolectric.buildService(LinkService::class.java, startIntent(sim())).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.track != null }
        Lyrics.poke()
        gate.countDown()
        waitFor(15_000) { appLog("lrc put ").isNotEmpty() }
        val all = org.robolectric.shadows.ShadowLog.getLogsForTag(Remote.TAG).map { it.msg }
        assertTrue(all.indexOf("nemix sweep 1 1") in 0 until all.indexOfFirst { it.startsWith("lrc put ") })
        assertEquals("han", dir.resolve("abc.ne.bad").readText().substringAfter('\n'))
        assertFalse(dir.resolve("abc.ne.lrc").exists())
        assertEquals(net.seilent.funecomp.artist.NeMix.VERSION, app.getSharedPreferences("lyrics", Context.MODE_PRIVATE).getInt("nemix", 0))
        c.destroy()
        Lyrics.reset()
        dir.resolve("def.ne.lrc").writeText(mixedBody)
        Lyrics.init(app, net.seilent.funecomp.artist.Http { _, _ -> net.seilent.funecomp.artist.Response(404, ByteArray(0)) })
        Lyrics.phone {}
        Thread.sleep(300)
        assertTrue(dir.resolve("def.ne.lrc").exists())
        assertEquals(1, appLog("nemix sweep ").size)
    }

    @Test
    fun aSweepFileErrorLeavesTheKeyUnwrittenAndTheOtherFilesSwept() {
        Lyrics.reset()
        val dir = java.io.File(app.filesDir, "lyrics").apply { deleteRecursively(); mkdirs() }
        val locked = dir.resolve("abc.ne.lrc").apply { writeText(mixedBody) }
        dir.resolve("def.ne.lrc").writeText(mixedBody)
        try {
            locked.setReadable(false)
            assumeFalse(locked.canRead())
            app.getSharedPreferences("lyrics", Context.MODE_PRIVATE).edit().remove("nemix").commit()
            org.robolectric.shadows.ShadowLog.clear()
            Lyrics.init(app, net.seilent.funecomp.artist.Http { _, _ -> net.seilent.funecomp.artist.Response(404, ByteArray(0)) })
            val done = java.util.concurrent.CountDownLatch(1)
            Lyrics.phone { done.countDown() }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertTrue(appLog("nemix sweep abc.ne.lrc: ").isNotEmpty())
            assertEquals(listOf("nemix sweep 2 1 errors 1"), appLog("nemix sweep 2"))
            assertTrue(dir.resolve("def.ne.bad").isFile)
            assertFalse(app.getSharedPreferences("lyrics", Context.MODE_PRIVATE).contains("nemix"))
        } finally {
            locked.setReadable(true)
        }
    }

    private fun listenAgain(s: Sim, port: Int, waitMs: Long = 10_000) {
        val end = System.currentTimeMillis() + waitMs
        var seen = ""
        while (true) {
            try {
                s.listen(port)
                return
            } catch (e: java.net.BindException) {
                val holders = portHolders(port)
                if (System.currentTimeMillis() > end) throw java.net.BindException("${e.message} on $port, held by $holders")
                if (holders != seen) System.err.println("listenAgain $port busy, held by $holders")
                seen = holders
                Thread.sleep(50)
            }
        }
    }

    private fun ephemeralLow(): Int = runCatching { java.io.File("/proc/sys/net/ipv4/ip_local_port_range").readText().trim().split(Regex("\\s+"))[0].toInt() }.getOrDefault(32768)

    private fun listenBelowEphemeral(s: Sim): Int {
        val low = ephemeralLow()
        val rnd = java.util.Random()
        repeat(200) {
            try {
                return s.listen(low - 12_000 + rnd.nextInt(12_000))
            } catch (_: java.net.BindException) {
            }
        }
        error("no free port below $low")
    }

    @Test
    fun aSimThatRestartsListensBelowTheEphemeralRange() {
        val s = Sim(Options(Board.of("v1"), log = { logs += it }))
        sims += s
        val port = listenBelowEphemeral(s)
        assertTrue("$port", port in ephemeralLow() - 12_000 until ephemeralLow())
        assertTrue(logs.any { it == "sim v1 listening on $port" })
    }

    private fun portHolders(port: Int): String {
        val own = java.io.File("/proc/self/fd").listFiles().orEmpty().mapNotNull { runCatching { java.nio.file.Files.readSymbolicLink(it.toPath()).toString() }.getOrNull() }.toSet()
        val states = mapOf("01" to "ESTABLISHED", "02" to "SYN_SENT", "03" to "SYN_RECV", "04" to "FIN_WAIT1", "05" to "FIN_WAIT2", "06" to "TIME_WAIT", "07" to "CLOSE", "08" to "CLOSE_WAIT", "09" to "LAST_ACK", "0A" to "LISTEN", "0B" to "CLOSING")
        val local = ":%04X".format(port)
        val rows = listOf("/proc/net/tcp", "/proc/net/tcp6").map { java.io.File(it) }.filter { it.canRead() }.flatMap { it.readLines().drop(1) }
            .map { it.trim().split(Regex("\\s+")) }
            .filter { it.size > 9 && it[1].endsWith(local) }
        if (rows.isEmpty()) return "nothing listed"
        return rows.joinToString("; ") {
            val owner = when {
                it[9] == "0" -> "no owner"
                "socket:[${it[9]}]" in own -> "this jvm"
                else -> "another process"
            }
            "${states[it[3]] ?: it[3]} remote ${it[2]} $owner"
        }
    }

    @Test
    fun aSimRestartedOnItsOldPortWaitsUntilThePortIsFree() {
        val holder = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val port = holder.localPort
        val s = Sim(Options(Board.of("v1"), log = { logs += it }))
        sims += s
        Thread {
            Thread.sleep(300)
            holder.close()
        }.start()
        listenAgain(s, port)
        assertTrue(logs.any { it == "sim v1 listening on $port" })
    }

    @Test
    fun aRefusedPutIsSentAgainAfterAFirmwareChange() {
        lyricsFrom(syntheticPlainBody)
        val old = Sim(Options(Board.of("v1"), net.seilent.funecomp.sim.Faults(listOf("refuse=LRC_PUT")), log = { logs += it }))
        sims += old
        val port = listenBelowEphemeral(old)
        val c = live("tcp:127.0.0.1:$port")
        val first = allPut()
        nextSession {
            old.stop()
            val updated = Sim(Options(Board.of("v1"), net.seilent.funecomp.sim.Faults(listOf("refuse=LRC_PUT")), log = { logs += it }, version = "sim.v1.next"))
            sims += updated
            listenAgain(updated, port)
        }
        assertEquals("sim.v1.next", Remote.device.hello?.version)
        assertEquals(2 * first, puts())
        Lyrics.set(LyricSources.LRCLIB, false)
        c.destroy()
    }

    @Test
    fun aFileErrorEndsTheTrackWithoutTrippingTheBreaker() {
        val calls = lyricsFrom(syntheticPlainBody)
        val blocked = java.io.File(app.filesDir, "lyrics")
        blocked.writeText("not a dir")
        val c = live(sim())
        waitFor(30_000) {
            Lyrics.poke()
            Thread.sleep(200)
            calls.toSet().size >= 1 + LyricsPlan.AHEAD
        }
        Thread.sleep(1500)
        assertEquals(calls.toSet().size, calls.size)
        assertEquals(0, logs.count { it.contains("rx LRC_PUT") })
        Lyrics.set(LyricSources.LRCLIB, false)
        blocked.delete()
        c.destroy()
    }

    @Test
    fun aFirmwareSendResumesAfterDropsWithoutASecondTap() {
        val dir = kotlin.io.path.createTempDirectory("upd").toFile()
        val image = java.io.File(dir, "image.upt").apply { writeBytes(ByteArray(400_000) { (it * 13 + 5).toByte() }) }
        val card = java.io.File(dir, "card")
        shadowOf(app.getSystemService(android.os.BatteryManager::class.java)).setIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY, 80)
        Firmwares.init(app)
        val c = Robolectric.buildService(LinkService::class.java, startIntent(sim(faults = listOf("drop-after-bytes=150000"), updDir = card))).create().startCommand(0, 1)
        Firmwares.send(image, FwStatementBuilder.forFile("v1", image))
        val done = java.io.File(card, "v1.upt")
        val end = System.currentTimeMillis() + 30_000
        while (!done.exists()) {
            check(System.currentTimeMillis() < end) { "timed out: ${logs.filter { it.startsWith("upd") }} ${Firmwares.view}" }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
            Thread.sleep(100)
        }
        assertArrayEquals(image.readBytes(), done.readBytes())
        val trace = logs.filter { it.startsWith("upd begin") || it.endsWith("closed drop-after-bytes") }
        val resumed = trace.zipWithNext().filter { (a, _) -> a.endsWith("closed drop-after-bytes") }.map { it.second }
        assertTrue("$trace", resumed.isNotEmpty() && resumed.all { it.startsWith("upd begin") && !it.endsWith("offset=0") })
        waitFor { Firmwares.view.info == "ready on the dap" }
        c.destroy()
    }

    @Test
    fun aBusyFirmwareBeginPausesThenResumes() {
        val dir = kotlin.io.path.createTempDirectory("upd").toFile()
        val image = java.io.File(dir, "image.upt").apply { writeBytes(ByteArray(100_000) { (it * 7 + 3).toByte() }) }
        val card = java.io.File(dir, "card")
        shadowOf(app.getSystemService(android.os.BatteryManager::class.java)).setIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY, 80)
        Firmwares.init(app)
        val c = Robolectric.buildService(LinkService::class.java, startIntent(sim(faults = listOf("busy=UPD_BEGIN:1"), updDir = card))).create().startCommand(0, 1)
        Firmwares.send(image, FwStatementBuilder.forFile("v1", image))
        waitFor { Firmwares.view.info == "dap busy" }
        val done = java.io.File(card, "v1.upt")
        val end = System.currentTimeMillis() + 30_000
        while (!done.exists()) {
            check(System.currentTimeMillis() < end) { "timed out: ${logs.filter { it.startsWith("upd") }} ${Firmwares.view}" }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
            Thread.sleep(100)
        }
        assertArrayEquals(image.readBytes(), done.readBytes())
        waitFor { Firmwares.view.info == "ready on the dap" }
        c.destroy()
    }

    @Test
    fun aBusyAbortMidSendPausesThenResumesAtTheDeviceOffset() {
        val dir = kotlin.io.path.createTempDirectory("upd").toFile()
        val image = java.io.File(dir, "image.upt").apply { writeBytes(ByteArray(400_000) { (it * 11 + 1).toByte() }) }
        val card = java.io.File(dir, "card")
        shadowOf(app.getSystemService(android.os.BatteryManager::class.java)).setIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY, 80)
        Firmwares.init(app)
        val c = Robolectric.buildService(LinkService::class.java, startIntent(sim(faults = listOf("upd-abort-at=150000"), updDir = card))).create().startCommand(0, 1)
        Firmwares.send(image, FwStatementBuilder.forFile("v1", image))
        val done = java.io.File(card, "v1.upt")
        val end = System.currentTimeMillis() + 30_000
        while (!done.exists()) {
            check(System.currentTimeMillis() < end) { "timed out: ${logs.filter { it.startsWith("upd") }} ${Firmwares.view}" }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
            Thread.sleep(100)
        }
        assertArrayEquals(image.readBytes(), done.readBytes())
        val begins = logs.filter { it.startsWith("upd begin") }
        assertTrue("$begins", begins.last().substringAfter("offset=").toLong() >= 150_000)
        waitFor { Firmwares.view.info == "ready on the dap" }
        c.destroy()
    }

    private val idol = listOf(PhoneLine("Idol", "YOASOBI", "THE BOOK 3", 213_000, 0, true))

    private fun upd(faults: List<String> = emptyList(), phone: List<PhoneLine> = idol, output: Int? = null): Pair<ServiceController<LinkService>, java.io.File> {
        val card = java.io.File(kotlin.io.path.createTempDirectory("upd").toFile(), "card")
        shadowOf(app.getSystemService(android.os.BatteryManager::class.java)).setIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY, 80)
        Firmwares.init(app)
        val c = Robolectric.buildService(LinkService::class.java, startIntent(sim(faults = faults, updDir = card, phone = phone, receiver = phone.isNotEmpty(), output = output))).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.battery != null }
        return c to java.io.File(card, "v1.upt")
    }

    private fun image(n: Int) = ByteArray(n) { (it * 17 + 3).toByte() }

    private fun landed(done: java.io.File, bytes: ByteArray) {
        val end = System.currentTimeMillis() + 30_000
        while (!done.exists()) {
            check(System.currentTimeMillis() < end) { "timed out: ${logs.filter { it.startsWith("upd") }} ${Firmwares.view}" }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
            Thread.sleep(50)
        }
        assertArrayEquals(bytes, done.readBytes())
        waitFor(10_000) { Firmwares.view.info == "ready on the dap" }
    }

    @Test
    fun aFirmwareSendRunsWhileThePhoneStreams() {
        val (c, done) = upd()
        assertTrue(PushGate.receiver(Remote.device))
        assertEquals(true, Remote.device.playback.state.receiver)
        val bytes = image(300_000)
        Firmwares.send(HeldSource(bytes, Int.MAX_VALUE).source())
        landed(done, bytes)
        assertFalse(logs.any { it.startsWith("upd begin") && !it.endsWith("offset=0") })
        c.destroy()
    }

    @Test
    fun aCardRunDuringAStreamSendPausesWithCardNotAvailable() {
        val (c, done) = upd(listOf("card-run=3000+1000@upd:150000"))
        val bytes = image(400_000)
        Firmwares.send(HeldSource(bytes, Int.MAX_VALUE).source())
        waitFor(10_000) { Firmwares.view.info == CardView.REFUSED }
        landed(done, bytes)
        c.destroy()
    }

    @Test
    fun thePageExitMidSendPausesThenResumes() {
        val (c, done) = upd(listOf("phone-exit=4000"))
        val bytes = image(400_000)
        val held = HeldSource(bytes, 150_000)
        Firmwares.send(held.source())
        assertTrue(held.reached.await(10, TimeUnit.SECONDS))
        assertEquals(Enums.Source.phone, Remote.device.playback.state.source)
        waitFor(10_000) { Remote.device.playback.state.source == Enums.Source.local && Remote.device.playback.state.output == Enums.Output.wired }
        held.go.countDown()
        waitFor(10_000) { Firmwares.view.info == "waiting for playback to settle" }
        landed(done, bytes)
        assertTrue(logs.filter { it.startsWith("upd begin") }.last().substringAfter("offset=").toLong() >= 150_000)
        c.destroy()
    }

    @Test
    fun aFirmwareSendStaysRefusedOnBluetoothHeadphones() {
        val (c, _) = upd(phone = emptyList(), output = Enums.Output.btHeadset)
        Firmwares.send(HeldSource(image(1000), Int.MAX_VALUE).source())
        waitFor { Firmwares.view.info == "dap on bluetooth output" }
        assertFalse(Firmwares.sending)
        assertFalse(logs.any { it.startsWith("upd begin") })
        c.destroy()
    }

    @Test
    fun aRevokedFirmwareSourceFailsAndALaterSendStillRuns() {
        val dir = kotlin.io.path.createTempDirectory("upd").toFile()
        val image = java.io.File(dir, "image.upt").apply { writeBytes(ByteArray(100_000) { (it * 5 + 9).toByte() }) }
        val card = java.io.File(dir, "card")
        shadowOf(app.getSystemService(android.os.BatteryManager::class.java)).setIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY, 80)
        Firmwares.init(app)
        val c = Robolectric.buildService(LinkService::class.java, startIntent(sim(updDir = card))).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.battery != null }
        Firmwares.send(Firmwares.Source("picked.upt", 1000, null) { throw SecurityException("grant revoked") })
        waitFor { Firmwares.view.info == "send failed" && !Firmwares.sending }
        Firmwares.send(image, FwStatementBuilder.forFile("v1", image))
        val done = java.io.File(card, "v1.upt")
        val end = System.currentTimeMillis() + 30_000
        while (!done.exists()) {
            check(System.currentTimeMillis() < end) { "timed out: ${logs.filter { it.startsWith("upd") }} ${Firmwares.view}" }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
            Thread.sleep(100)
        }
        assertArrayEquals(image.readBytes(), done.readBytes())
        c.destroy()
    }

    @Test
    fun aSendRightAfterStopIsNotDroppedByTheOldAttempt() {
        val dir = kotlin.io.path.createTempDirectory("upd").toFile()
        val first = java.io.File(dir, "first.upt").apply { writeBytes(ByteArray(400_000) { (it * 3 + 1).toByte() }) }
        val second = java.io.File(dir, "second.upt").apply { writeBytes(ByteArray(120_000) { (it * 17 + 4).toByte() }) }
        val card = java.io.File(dir, "card")
        shadowOf(app.getSystemService(android.os.BatteryManager::class.java)).setIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY, 80)
        Firmwares.init(app)
        val c = Robolectric.buildService(LinkService::class.java, startIntent(sim(faults = listOf("slow-bps=200000"), updDir = card))).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.battery != null }
        Firmwares.send(first, FwStatementBuilder.forFile("v1", first))
        val end = System.currentTimeMillis() + 30_000
        while (!(Firmwares.sending && logs.any { it.contains("rx UPD_DATA") })) {
            check(System.currentTimeMillis() < end) { "never sending: ${Firmwares.view}" }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
            Thread.sleep(20)
        }
        Firmwares.stopSend()
        Firmwares.send(second, FwStatementBuilder.forFile("v1", second))
        val done = java.io.File(card, "v1.upt")
        while (!(done.exists() && done.readBytes().contentEquals(second.readBytes()))) {
            check(System.currentTimeMillis() < end) { "second not sent: ${logs.filter { it.startsWith("upd") }} ${Firmwares.view}" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(100)
        }
        c.destroy()
    }

    @Test
    fun aLongBusyHoldKeepsOneRetryChain() {
        val dir = kotlin.io.path.createTempDirectory("upd").toFile()
        val image = java.io.File(dir, "image.upt").apply { writeBytes(ByteArray(10_000)) }
        shadowOf(app.getSystemService(android.os.BatteryManager::class.java)).setIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY, 80)
        Firmwares.init(app)
        val c = Robolectric.buildService(LinkService::class.java, startIntent(sim(faults = listOf("busy=UPD_BEGIN:100000"), updDir = java.io.File(dir, "card")))).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.battery != null }
        Firmwares.send(image, FwStatementBuilder.forFile("v1", image))
        val start = System.currentTimeMillis()
        var pokes = 0
        while (System.currentTimeMillis() - start < 30_000) {
            if (pokes < 5 && System.currentTimeMillis() - start >= (pokes + 1) * 5_000L - 2_500) {
                Firmwares.state()
                pokes++
            }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
        val begins = logs.count { it.contains("rx UPD_BEGIN") }
        assertEquals("dap busy", Firmwares.view.info)
        assertTrue("$begins", begins <= 1 + pokes + 30_000 / Firmwares.BUSY_RETRY_MS.toInt() + 1)
        c.destroy()
    }

    @Test
    fun anUnreadableFirmwareSourceIsNotRetried() {
        val dir = kotlin.io.path.createTempDirectory("upd").toFile()
        val image = java.io.File(dir, "image.upt").apply { writeBytes(ByteArray(1000)) }
        val card = java.io.File(dir, "card")
        shadowOf(app.getSystemService(android.os.BatteryManager::class.java)).setIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY, 80)
        Firmwares.init(app)
        Firmwares.send(image, FwStatementBuilder.forFile("v1", image))
        image.delete()
        val c = Robolectric.buildService(LinkService::class.java, startIntent(sim(updDir = card))).create().startCommand(0, 1)
        waitFor { Firmwares.view.info == "send failed" }
        repeat(4) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
            Thread.sleep(500)
        }
        assertEquals(1, org.robolectric.shadows.ShadowLog.getLogsForTag(Remote.TAG).count { it.msg.startsWith("firmware send: ") })
        assertEquals("send failed", Firmwares.view.info)
        c.destroy()
    }

    private fun title(): String? =
        shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(1)?.extras?.getCharSequence("android.title")?.toString()

    @Test
    fun aWholeSourcePlayPastTheEndIsSentAgainFromTheFirstTrack() {
        val c = Robolectric.buildService(LinkService::class.java, startIntent(sim())).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.lib.rev != null && Remote.device.playback.state.queueRev != null }
        val rev = Remote.device.playback.state.queueRev!!
        val songs = net.seilent.funecomp.sim.Library.synthetic().tracksOf(Enums.View.artistAlbums, "Utada Hikaru")!!.size.toLong()
        Remote.play(Enums.View.artistAlbums, "Utada Hikaru", songs, false, emptyList())
        waitFor { Remote.device.playback.state.queueRev != rev }
        assertEquals(2, logs.count { " rx PLAY " in it })
        assertEquals("Automatic", Remote.device.playback.state.title)
        c.destroy()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theFirstVolumeKeyOnlyShowsTheOverlay() {
        val spec = sim()
        app.getSharedPreferences(Main.PREFS, Context.MODE_PRIVATE).edit().putString(Main.EXTRA_LINK, spec).commit()
        val c = Robolectric.buildService(LinkService::class.java, startIntent(spec)).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.hello != null }
        val a = Robolectric.buildActivity(Main::class.java, Intent(app, Main::class.java)).create().start().resume().visible()
        val main = a.get()
        fun volumes() = logs.count { " rx VOLUME " in it }
        fun key(repeat: Int = 0) = main.onKeyDown(android.view.KeyEvent.KEYCODE_VOLUME_UP, android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_VOLUME_UP, repeat))
        fun quiet() {
            Thread.sleep(300)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis((Tok.motion_volumeHide + Tok.motion_volumeFade).toLong()))
        }
        assertTrue(key())
        Thread.sleep(1000)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, volumes())
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        assertTrue(key())
        key()
        waitFor { volumes() == 1 }
        quiet()
        assertEquals(1, volumes())
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        key()
        quiet()
        assertEquals(1, volumes())
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        key(repeat = 1)
        waitFor { volumes() == 2 }
        a.pause().stop().destroy()
        c.destroy()
    }

    private fun running(): org.robolectric.android.controller.ActivityController<Main> {
        app.getSharedPreferences(Main.PREFS, Context.MODE_PRIVATE).edit().putString(Main.EXTRA_LINK, "tcp:127.0.0.1:1").commit()
        val a = Robolectric.buildActivity(Main::class.java, Intent(app, Main::class.java).setAction(Intent.ACTION_MAIN)).create().start().resume()
        a.get().shell.gated = false
        a.get().shell.nav.reset(listOf(Page.Home, Page.Settings, Page.Eq(0)))
        return a
    }

    private fun reopen(a: org.robolectric.android.controller.ActivityController<Main>, intent: Intent) {
        a.pause().stop()
        a.newIntent(intent)
        a.restart().resume()
    }

    @Test
    fun aNotificationTapWhileRunningOpensNowPlayingAndClosesTheSheet() {
        val a = running()
        var dismissed = 0
        a.get().shell.sheet.open("background", listOf("allow", "not now"), onDismiss = { dismissed++ }) {}
        reopen(a, Intent(app, Main::class.java).setAction(Main.ACTION_OPEN))
        assertEquals(listOf(Page.Home, Page.NowPlaying), a.get().shell.nav.stack.toList())
        assertFalse(a.get().shell.sheet.shown)
        assertEquals(0, dismissed)
        a.pause().stop().destroy()
    }

    @Test
    fun aLauncherOpenWhileRunningOpensNowPlaying() {
        val a = running()
        reopen(a, Intent(app, Main::class.java).setAction(Intent.ACTION_MAIN))
        assertEquals(listOf(Page.Home, Page.NowPlaying), a.get().shell.nav.stack.toList())
        a.pause().stop().destroy()
    }

    @Test
    fun aReturnFromHistoryKeepsThePage() {
        val a = running()
        reopen(a, Intent(app, Main::class.java).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
        assertEquals(listOf(Page.Home, Page.Settings, Page.Eq(0)), a.get().shell.nav.stack.toList())
        a.pause().stop().destroy()
    }

    @Test
    fun anOpenWhileGatedLeavesTheGateToReset() {
        val a = running()
        a.get().shell.gated = true
        a.get().shell.nav.reset(listOf(Page.Connection))
        reopen(a, Intent(app, Main::class.java).setAction(Main.ACTION_OPEN))
        assertEquals(listOf(Page.Connection), a.get().shell.nav.stack.toList())
        a.pause().stop().destroy()
    }

    @Test
    fun aDebugPageIntentKeepsItsPage() {
        val a = running()
        reopen(a, Intent(app, Main::class.java).setAction(Main.ACTION_OPEN).putExtra(Main.EXTRA_PAGE, "settings"))
        assertEquals(listOf(Page.Home, Page.Settings), a.get().shell.nav.stack.toList())
        a.pause().stop().destroy()
    }

    @Test
    fun aStartedLinkIsWantedUntilStop() {
        val prefs = app.getSharedPreferences(Main.PREFS, Context.MODE_PRIVATE)
        val c = Robolectric.buildService(LinkService::class.java, startIntent(sim())).create().startCommand(0, 1)
        assertTrue(prefs.getBoolean(Main.WANTED, false))
        c.withIntent(Intent(app, LinkService::class.java).setAction(LinkService.ACTION_STOP)).startCommand(0, 2)
        assertFalse(prefs.contains(Main.WANTED))
        c.destroy()
    }

    @Test
    fun anUnusableSpecLeavesNothingWanted() {
        val prefs = app.getSharedPreferences(Main.PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(Main.WANTED, true).commit()
        val c = Robolectric.buildService(LinkService::class.java, startIntent("bogus:1")).create().startCommand(0, 1)
        assertFalse(prefs.contains(Main.WANTED))
        c.destroy()
    }

    @Test
    fun aDestroyWithoutStopKeepsWanted() {
        val prefs = app.getSharedPreferences(Main.PREFS, Context.MODE_PRIVATE)
        Robolectric.buildService(LinkService::class.java, startIntent(sim())).create().startCommand(0, 1).destroy()
        assertTrue(prefs.getBoolean(Main.WANTED, false))
    }

    @Test
    fun aServiceStartedWithoutMainReadsTheLyricsPref() {
        Lyrics.reset()
        app.getSharedPreferences("lyrics", Context.MODE_PRIVATE).edit().putBoolean("netease", false).commit()
        val c = Robolectric.buildService(LinkService::class.java, startIntent(sim(faults = listOf("stall-ms=60000"))).putExtra(LinkService.EXTRA_UPDATED, true)).create().startCommand(0, 1)
        assertEquals(LyricSources(lrclib = true, netease = false), Lyrics.sources)
        Lyrics.set(LyricSources.LRCLIB, false)
        c.withIntent(Intent(app, LinkService::class.java).setAction(LinkService.ACTION_STOP)).startCommand(0, 2)
        c.destroy()
    }

    @Test
    fun anAskWrittenAfterCreateIsShownAtLiveAndOnStoresIt() {
        val prefs = app.getSharedPreferences(Main.PREFS, Context.MODE_PRIVATE)
        val spec = sim()
        prefs.edit().putString(Main.EXTRA_LINK, spec).commit()
        app.getSharedPreferences(Main.DEVICE_PREFS, Context.MODE_PRIVATE).edit().putBoolean("batteryAsked", true).commit()
        val c = Robolectric.buildService(LinkService::class.java, startIntent(spec)).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE }
        val a = Robolectric.buildActivity(Main::class.java, Intent(app, Main::class.java).setAction(Intent.ACTION_MAIN)).create()
        prefs.edit().putBoolean(Main.RECONNECT_ASK, true).commit()
        a.start().resume().visible()
        waitFor { a.get().shell.sheet.shown }
        assertEquals("reconnect after update", a.get().shell.sheet.title)
        a.get().shell.sheet.accept(0)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(prefs.getBoolean(Main.RECONNECT, false))
        assertFalse(prefs.contains(Main.RECONNECT_ASK))
        a.pause().stop().destroy()
        c.withIntent(Intent(app, LinkService::class.java).setAction(LinkService.ACTION_STOP)).startCommand(0, 2)
        c.destroy()
    }

    @Test
    fun aBackgroundAnswerIsStoredOnThisPhoneOnly() {
        val prefs = app.getSharedPreferences(Main.PREFS, Context.MODE_PRIVATE)
        val spec = sim()
        prefs.edit().putString(Main.EXTRA_LINK, spec).commit()
        val c = Robolectric.buildService(LinkService::class.java, startIntent(spec)).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE }
        val a = Robolectric.buildActivity(Main::class.java, Intent(app, Main::class.java).setAction(Intent.ACTION_MAIN)).create()
        a.start().resume().visible()
        waitFor { a.get().shell.sheet.shown }
        assertEquals("background", a.get().shell.sheet.title)
        a.get().shell.sheet.accept(1)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(app.getSharedPreferences(Main.DEVICE_PREFS, Context.MODE_PRIVATE).getBoolean("batteryAsked", false))
        assertFalse(prefs.contains("batteryAsked"))
        a.pause().stop().destroy()
        c.withIntent(Intent(app, LinkService::class.java).setAction(LinkService.ACTION_STOP)).startCommand(0, 2)
        c.destroy()
    }

    @Test
    fun startGate() {
        assertTrue(Main.opens(Intent(Intent.ACTION_MAIN), debuggable = false))
        assertTrue(Main.opens(Intent(Main.ACTION_OPEN), debuggable = false))
        assertTrue(Main.opens(Intent().putExtra(Main.EXTRA_LINK, "tcp:127.0.0.1:1"), debuggable = true))
        assertFalse(Main.opens(Intent().putExtra(Main.EXTRA_LINK, "tcp:127.0.0.1:1"), debuggable = false))
        assertFalse(Main.opens(Intent(), debuggable = true))
        assertFalse(Main.opens(Intent(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY), debuggable = true))
        assertFalse(Main.opens(null, debuggable = true))
    }

    @Test
    fun aLauncherOpenStartsTheLinkAndAReturnDoesNot() {
        app.getSharedPreferences(Main.PREFS, Context.MODE_PRIVATE).edit().putString(Main.EXTRA_LINK, "tcp:127.0.0.1:1").commit()
        val shadow = shadowOf(app)
        while (shadow.nextStartedService != null) Unit
        val a = Robolectric.buildActivity(Main::class.java, Intent(app, Main::class.java).setAction(Intent.ACTION_MAIN)).create().start().resume()
        assertEquals(LinkService::class.java.name, shadow.nextStartedService?.component?.className)
        a.pause().stop()
        assertNull(shadow.nextStartedService)
        a.restart().resume()
        assertNull(shadow.nextStartedService)
        a.pause().stop()
        a.newIntent(Intent(app, Main::class.java).setAction(Intent.ACTION_MAIN))
        a.restart().resume()
        assertEquals(LinkService::class.java.name, shadow.nextStartedService?.component?.className)
        a.pause().stop().destroy()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aHeartTapToastsFromTheStateAfterTheReplyAndKeepsTheThumbs() {
        val dir = kotlin.io.path.createTempDirectory("art").toFile()
        val lib = net.seilent.funecomp.sim.Library.synthetic()
        val jpeg = java.awt.image.BufferedImage(600, 600, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (t in lib.tracks) javax.imageio.ImageIO.write(jpeg, "jpg", java.io.File(dir, "${lib.albumKey(t.id)}.jpg"))
        val s = Sim(Options(Board.of("v1"), net.seilent.funecomp.sim.Faults(listOf("stale-state")), artDir = dir, log = { logs += it }), lib)
        sims += s
        val c = Robolectric.buildService(LinkService::class.java, startIntent("tcp:127.0.0.1:${s.listen(0)}")).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.track != null }
        val st = Remote.device.playback.state
        Remote.wantArt(Thumbs.NOW_PLAYING, st.track!!, Thumbs.SMALL, st.album)
        waitFor { Remote.art(st.track!!, Thumbs.SMALL) != null }
        val toasts = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        val rev = Remote.device.lib.rev
        Remote.favorite(st) { line, title -> toasts += line to title }
        waitFor { toasts.isNotEmpty() && Remote.device.lib.rev != rev }
        assertEquals(listOf(Heart.ADDED to st.title), toasts)
        assertEquals(true, Remote.device.playback.state.favorite)
        assertEquals(1, logs.count { it.contains("rx ACTION") })
        val gets = logs.count { it.contains("rx ART_GET") }
        Remote.wantArt(Thumbs.MEDIA, st.track!!, Thumbs.SMALL, st.album)
        waitFor { Remote.mediaArt(st.track!!, Thumbs.SMALL) != null }
        assertEquals(gets, logs.count { it.contains("rx ART_GET") })
        c.destroy()
    }

    @Test
    fun aHeartTapSendsNothingWithoutTheSnapRev() {
        val s = Sim(Options(Board.of("v1"), minor = 2, log = { logs += it }))
        sims += s
        val c = Robolectric.buildService(LinkService::class.java, startIntent("tcp:127.0.0.1:${s.listen(0)}")).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.track != null }
        Remote.favorite(Remote.device.playback.state) { _, _ -> }
        Thread.sleep(300)
        assertEquals(0, logs.count { it.contains("rx ACTION") })
        c.destroy()
    }

    @Test
    fun anActionAnswersDoneOnce() {
        val c = Robolectric.buildService(LinkService::class.java, startIntent(sim(faults = listOf("refuse=ACTION")))).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.track != null }
        val calls = java.util.concurrent.CopyOnWriteArrayList<Pair<Int?, String?>>()
        Remote.action(Enums.ActionCmd.favorite, Remote.device.playback.state.track) { code, text -> calls += code to text }
        waitFor { calls.isNotEmpty() }
        Thread.sleep(300)
        assertEquals(listOf(Err.refused to "refused"), calls)
        c.destroy()
        Remote.stop()
        val closed = ArrayList<Pair<Int?, String?>>()
        Remote.action(Enums.ActionCmd.favorite, 1) { code, text -> closed += code to text }
        assertEquals(listOf(-1 to null), closed)
    }
}
