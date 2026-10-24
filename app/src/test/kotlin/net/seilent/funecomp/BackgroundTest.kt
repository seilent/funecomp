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

    private fun sim(battery: Battery = Battery(87, 87, 0), lrcDir: java.io.File? = null, faults: List<String> = emptyList(), updDir: java.io.File? = null, minor: Int = Proto.minor, phone: List<PhoneLine> = emptyList(), receiver: Boolean = false, output: Int? = null): String {
        val s = Sim(Options(Board.of("v1"), net.seilent.funecomp.sim.Faults(faults), battery = battery, lrcDir = lrcDir, updDir = updDir, minor = minor, log = { logs += it }, phone = phone, receiver = receiver))
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
        Remote.eqPreset(Enums.EqPresetOp.save, Enums.EqOutput.wired, null, "HD 650", null, false) { code, key -> got += code to key }
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
        Remote.eqPreset(Enums.EqPresetOp.save, Enums.EqOutput.wired, null, "HD 650", null, false) { code, key -> got += code to key }
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

    private fun lyricsFrom(body: String): MutableList<String> {
        java.io.File(app.filesDir, "lyrics").deleteRecursively()
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        Lyrics.init(app, net.seilent.funecomp.artist.Http { url, _ ->
            calls += url
            net.seilent.funecomp.artist.Response(200, body.toByteArray())
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
        Firmwares.send(image)
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
        Firmwares.send(image)
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
        Firmwares.send(image)
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
        Firmwares.send(Firmwares.Source("picked.upt", 1000) { throw SecurityException("grant revoked") })
        waitFor { Firmwares.view.info == "send failed" && !Firmwares.sending }
        Firmwares.send(image)
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
        Firmwares.send(first)
        val end = System.currentTimeMillis() + 30_000
        while (!(Firmwares.sending && logs.any { it.contains("rx UPD_DATA") })) {
            check(System.currentTimeMillis() < end) { "never sending: ${Firmwares.view}" }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
            Thread.sleep(20)
        }
        Firmwares.stopSend()
        Firmwares.send(second)
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
        Firmwares.send(image)
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
        Firmwares.send(image)
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
