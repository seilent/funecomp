package net.seilent.funecomp

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Looper
import net.seilent.funecomp.art.Thumbs
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.sim.Battery
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import net.seilent.funecomp.state.LyricsPlan
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackgroundTest {
    private val sims = ArrayList<Sim>()
    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        MediaLink.controls(app, false)
    }

    @After
    fun tearDown() {
        Remote.stop()
        Firmwares.reset()
        sims.forEach { it.stop() }
    }

    private val logs = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun sim(battery: Battery = Battery(87, 87, 0), lrcDir: java.io.File? = null, faults: List<String> = emptyList(), updDir: java.io.File? = null): String {
        val s = Sim(Options(Board.of("v1"), net.seilent.funecomp.sim.Faults(faults), battery = battery, lrcDir = lrcDir, updDir = updDir, log = { logs += it }))
        sims += s
        return "tcp:127.0.0.1:${s.listen(0)}"
    }

    private fun waitFor(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
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

    @Test
    fun serviceStaysUpAcrossADropAndStopClearsThePhase() {
        val spec = sim()
        val c = Robolectric.buildService(LinkService::class.java, startIntent(spec)).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE && text() == "connected, dap 87 %" }
        sims.forEach { it.stop() }
        waitFor { Remote.phase != Phase.LIVE && text() != "connected, dap 87 %" }
        assertFalse(shadowOf(c.get()).isStoppedBySelf)
        c.withIntent(Intent(app, LinkService::class.java).setAction(LinkService.ACTION_STOP)).startCommand(0, 2)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(c.get()).isStoppedBySelf)
        assertEquals(Phase.NONE, Remote.phase)
        c.destroy()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(Remote.onPhase)

        val again = Robolectric.buildService(LinkService::class.java, startIntent(sim())).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE && text() == "connected, dap 87 %" }
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
    fun bluetoothOffPausesAndOnResumes() {
        val c = Robolectric.buildService(LinkService::class.java, startIntent(sim())).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE }
        app.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF))
        waitFor { Remote.phase == Phase.DOWN && text() == "bluetooth off" }
        Thread.sleep(1500)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Phase.DOWN, Remote.phase)
        app.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON))
        waitFor { Remote.phase == Phase.LIVE && text() == "connected, dap 87 %" }
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
        waitFor { text() == "connected, dap 87 %" }
        assertEquals("link", shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(1)!!.channelId)
        c.destroy()
    }

    @Test
    fun lowDapBatteryNotifiesOnceAndTheLinkTextCarriesIt() {
        val c = Robolectric.buildService(LinkService::class.java, startIntent(sim(Battery.parse("17:14@300")))).create().startCommand(0, 1)
        val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        waitFor { Remote.phase == Phase.LIVE && text()?.startsWith("connected, dap ") == true }
        waitFor { nm.getNotification(2) != null }
        assertEquals("v1 battery 15 %", nm.getNotification(2)!!.extras.getCharSequence("android.title")?.toString())
        assertEquals("dap", nm.getNotification(2)!!.channelId)
        waitFor { text() == "connected, dap 14 %" }
        assertEquals("v1 battery 15 %", nm.getNotification(2)!!.extras.getCharSequence("android.title")?.toString())
        c.destroy()
    }

    @Test
    fun lyricsArePutOncePerTrackAcrossADrop() {
        val dir = kotlin.io.path.createTempDirectory("lrc").toFile()
        val calls = java.util.Collections.synchronizedList(ArrayList<String>())
        Lyrics.init(app, net.seilent.funecomp.artist.Http { url, _ ->
            calls += url
            net.seilent.funecomp.artist.Response(200, "{\"syncedLyrics\":\"[00:01.00]line\\n\",\"instrumental\":false}".toByteArray())
        })
        Lyrics.set(true)
        val c = Robolectric.buildService(LinkService::class.java, startIntent(sim(lrcDir = dir))).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE }
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
        waitFor { Remote.phase == Phase.LIVE }
        Remote.queuePage(0)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        Thread.sleep(1500)
        shadowOf(Looper.getMainLooper()).idle()
        val second = logs.filter { it.startsWith("stored lrc") }
        assertEquals(second.toSet().size, second.size)
        assertTrue(second.none { it in first })
        Lyrics.set(false)
        again.destroy()
    }

    @Test
    fun aRefusedLyricsPutIsNotSentAgain() {
        Lyrics.init(app, net.seilent.funecomp.artist.Http { _, _ ->
            net.seilent.funecomp.artist.Response(200, "{\"syncedLyrics\":\"[00:01.00]line\\n\",\"instrumental\":false}".toByteArray())
        })
        Lyrics.set(true)
        val c = Robolectric.buildService(LinkService::class.java, startIntent(sim(faults = listOf("refuse=LRC_PUT")))).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE }
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
        Lyrics.set(false)
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
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue(key())
        quiet()
        assertEquals(0, volumes())
        key()
        waitFor { volumes() == 1 }
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
        app.getSharedPreferences("lyrics", Context.MODE_PRIVATE).edit().putBoolean("lrclib", true).commit()
        val c = Robolectric.buildService(LinkService::class.java, startIntent(sim()).putExtra(LinkService.EXTRA_UPDATED, true)).create().startCommand(0, 1)
        assertTrue(Lyrics.on)
        Lyrics.set(false)
        c.withIntent(Intent(app, LinkService::class.java).setAction(LinkService.ACTION_STOP)).startCommand(0, 2)
        c.destroy()
    }

    @Test
    fun anAskWrittenAfterCreateIsShownAtLiveAndOnStoresIt() {
        val prefs = app.getSharedPreferences(Main.PREFS, Context.MODE_PRIVATE)
        val spec = sim()
        prefs.edit().putString(Main.EXTRA_LINK, spec).putBoolean("batteryAsked", true).commit()
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
}
