package net.seilent.funecomp

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Looper
import net.seilent.funecomp.artist.ArtCache
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.Names
import net.seilent.funecomp.artist.Response
import net.seilent.funecomp.artist.Wikimedia
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.PhoneLine
import net.seilent.funecomp.sim.Sim
import net.seilent.funecomp.ui.Phase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PhoneArtLinkTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val sims = ArrayList<Sim>()
    private val logs = CopyOnWriteArrayList<String>()
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private var service: ServiceController<LinkService>? = null
    private val http = Http { _, _ ->
        entered.countDown()
        release.await(30, TimeUnit.SECONDS)
        Response(404, ByteArray(0))
    }
    private val stream = listOf(
        PhoneLine("Idol", "YOASOBI", "THE BOOK 3", 213_000, 0, true),
        PhoneLine("Bling-Bang-Bang-Born", "Creepy Nuts", "Bling-Bang-Bang-Born", 200_000, 0, true),
    )

    @Before
    fun setUp() {
        scrobblesOff(app)
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        MediaLink.controls(app, false)
        Remote.resetArtist()
    }

    @After
    fun tearDown() {
        release.countDown()
        Firmwares.reset()
        service?.destroy()
        Remote.stop()
        sims.forEach { it.stop() }
        Remote.resetArtist()
        scrobblesReset()
    }

    private fun waitFor(ms: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
            Thread.sleep(20)
        }
    }

    private fun jpeg(): ByteArray {
        val img = java.awt.image.BufferedImage(600, 800, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = java.awt.Color(120, 80, 40)
        g.fillRect(0, 0, 600, 800)
        g.dispose()
        return ByteArrayOutputStream().also { javax.imageio.ImageIO.write(img, "jpg", it) }.toByteArray()
    }

    private fun pendingFile() = File(app.filesDir, "art/pending.txt")

    private fun live(vararg faults: String, updDir: File? = null): Sim {
        Remote.artist(app, http)
        val s = Sim(Options(Board.of("v1"), Faults(if (updDir != null) faults.toList() + NO_SIG else faults.toList()), log = { logs += it }, phone = stream, receiver = true, updDir = updDir))
        sims += s
        service = Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java).putExtra(Main.EXTRA_LINK, "tcp:127.0.0.1:${s.listen(0)}")).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE && Remote.device.playback.state.source == Enums.Source.phone }
        return s
    }

    @Test
    fun aPhoneArtistJoinsTheSetFromTheStateWhileTheWorkerIsBusy() {
        live()
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        Remote.transport(Enums.TransportCmd.next)
        waitFor { Remote.device.playback.state.title == "Bling-Bang-Bang-Born" }
        waitFor { pendingFile().isFile && "Creepy Nuts\t" in pendingFile().readText() }
        assertEquals(1, release.count)
    }

    @Test
    fun aCardRunMidRunClosesThePhoneGateBeforeTheNextPush() {
        val old = "Old One"
        ArtCache(File(app.filesDir, "art")).putHit(ArtCache.Entry("name:${Names.fold(old)}", old, null, Wikimedia.SOURCE, null, "https://upload.wikimedia.org/a.jpg", 1, "File:A.jpg"), jpeg())
        pendingFile().writeText("$old\t\n")
        val s = live("card-run=5000+1000@manual")
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        assertFalse(Remote.device.cardDown)
        s.device.startCard()
        waitFor { Remote.device.cardDown }
        val from = logs.size
        release.countDown()
        Thread.sleep(2000)
        assertTrue(Remote.device.cardDown)
        assertFalse(logs.drop(from).any { "rx IMG_BEGIN" in it })
        waitFor(15_000) { s.device.begins.any { it.first == old && it.second == Enums.ImgOrigin.phone } }
    }

    @Test
    fun aPendingPhoneArtistWaitsForAFirmwareSendAndGoesAfterIt() {
        val old = "Old One"
        ArtCache(File(app.filesDir, "art")).putHit(ArtCache.Entry("name:${Names.fold(old)}", old, null, Wikimedia.SOURCE, null, "https://upload.wikimedia.org/a.jpg", 1, "File:A.jpg"), jpeg())
        pendingFile().apply { parentFile.mkdirs() }.writeText("$old\t\n")
        shadowOf(app.getSystemService(android.os.BatteryManager::class.java)).setIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY, 80)
        Firmwares.init(app)
        val card = File(kotlin.io.path.createTempDirectory("upd").toFile(), "card")
        val s = live(updDir = card)
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        waitFor { Remote.device.playback.state.battery != null }
        val bytes = ByteArray(300_000) { (it * 17 + 3).toByte() }
        val held = HeldSource(bytes, 150_000)
        Firmwares.send(held.source())
        assertTrue(held.reached.await(10, TimeUnit.SECONDS))
        assertTrue(Firmwares.sending)
        release.countDown()
        Thread.sleep(3000)
        assertFalse(s.device.begins.any { it.first == old })
        held.go.countDown()
        waitFor(20_000) { File(card, "v1.upt").exists() && !Firmwares.sending }
        waitFor(15_000) { s.device.begins.any { it.first == old && it.second == Enums.ImgOrigin.phone } }
    }
}
