package net.seilent.funecomp

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.AndroidUiDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.seilent.funecomp.proto.ArtPick
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Feature
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import net.seilent.funecomp.sim.Track
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.PickFor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArtPickAskTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val sims = ArrayList<Sim>()
    private val logs = CopyOnWriteArrayList<String>()
    private val nm get() = shadowOf(app.getSystemService(NotificationManager::class.java))
    private var service: ServiceController<LinkService>? = null
    private val dj = "91352441-9f79-48c2-81ad-ccdd1e46915a"

    @Before
    fun setUp() {
        scrobblesOff(app)
        debuggable(true)
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        MediaLink.controls(app, false)
        app.getSharedPreferences(Main.PREFS, Context.MODE_PRIVATE).edit().putBoolean("batteryAsked", true).commit()
    }

    @After
    fun tearDown() {
        Remote.queuedAsk = {}
        Remote.pickAsk = null
        service?.destroy()
        Remote.stop()
        sims.forEach { it.stop() }
        debuggable(true)
        waitFor { System.nanoTime() - offMainWrite.get() > QUIET_NS }
        val ran = AtomicBoolean(false)
        CoroutineScope(AndroidUiDispatcher.Main).launch { ran.set(true) }
        waitFor { ran.get() }
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

    private fun live(minor: Int = Options(Board.of("v1")).minor, faults: List<String> = emptyList()): Sim {
        debuggable(true)
        val s = Sim(Options(Board.of("v1"), Faults(faults), log = { logs += it }, minor = minor), Library(listOf(Track(1, "TSUKI", listOf("TSUKI"), "A", "TSUKI", "Song", 200_000, "a", "a.flac"))))
        sims += s
        val spec = "tcp:127.0.0.1:${s.listen(0)}"
        app.getSharedPreferences(Main.PREFS, Context.MODE_PRIVATE).edit().putString(Main.EXTRA_LINK, spec).commit()
        service = Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java).putExtra(Main.EXTRA_LINK, spec)).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE }
        debuggable(false)
        return s
    }

    private fun main(intent: Intent = Intent(app, Main::class.java).setAction(Intent.ACTION_MAIN)): ActivityController<Main> {
        val a = Robolectric.buildActivity(Main::class.java, intent).create().start().resume().visible()
        waitFor { !a.get().shell.gated }
        return a
    }

    private fun ask(s: Sim, name: String, mbid: String? = null): String {
        var out = ""
        val t = Thread { out = s.askPick(ArtPick(name, mbid)) }.apply { start() }
        waitFor { !t.isAlive }
        return out
    }

    private fun notice() = nm.getNotification(PickNotice.ID)

    private fun tapped(): Intent = shadowOf(notice()!!.contentIntent).savedIntent

    @Test
    fun withTheAppShownTheSheetOpens() {
        val s = live()
        val a = main()
        assertEquals("ok shown=true", ask(s, "TSUKI"))
        waitFor { a.get().shell.sheet.shown }
        assertEquals("TSUKI", a.get().shell.sheet.title)
        assertEquals(listOf("paste link", "browse", "from gallery"), a.get().shell.sheet.items)
        assertNull(notice())
        a.pause().stop().destroy()
    }

    @Test
    fun aShownConsentSheetStaysAndTheAskPostsANotice() {
        val s = live()
        app.getSharedPreferences(Main.PREFS, Context.MODE_PRIVATE).edit().putBoolean(Main.RECONNECT_ASK, true).commit()
        val a = main()
        waitFor { a.get().shell.sheet.shown }
        assertEquals("ok shown=false", ask(s, "TSUKI"))
        assertEquals("reconnect after update", a.get().shell.sheet.title)
        assertEquals("TSUKI", notice()!!.extras.getCharSequence("android.title").toString())
        assertEquals("pick image", notice()!!.extras.getCharSequence("android.text").toString())
        a.pause().stop().destroy()
    }

    @Test
    fun withTheAppStoppedATapOpensThePickColdAndWarm() {
        val s = live()
        assertEquals("ok shown=false", ask(s, "TSUKI", dj))
        val tap = tapped()
        assertEquals(Main.ACTION_PICK_ART, tap.action)
        assertEquals(dj, tap.getStringExtra(Main.EXTRA_PICK_MBID))
        val cold = Robolectric.buildActivity(Main::class.java, tap).create().start().resume().visible()
        waitFor { cold.get().shell.sheet.shown }
        assertEquals(Page.NowPlaying, cold.get().shell.nav.top)
        assertEquals("TSUKI", cold.get().shell.sheet.title)
        assertNull(notice())
        cold.get().shell.sheet.close()
        cold.get().shell.nav.reset(listOf(Page.Home, Page.Settings))
        cold.pause().stop()
        assertEquals("ok shown=false", ask(s, "TSUKI"))
        cold.newIntent(tapped())
        cold.restart().resume()
        waitFor { cold.get().shell.sheet.shown }
        assertEquals(Page.NowPlaying, cold.get().shell.nav.top)
        assertEquals("TSUKI", cold.get().shell.sheet.title)
        cold.pause().stop().destroy()
    }

    private fun offer() = net.seilent.funecomp.artist.Offer(net.seilent.funecomp.artist.ArtPicks.IMAGE, net.seilent.funecomp.artist.Sources.YOUTUBE, "https://yt3.googleusercontent.com/a=s900", "https://yt3.googleusercontent.com/a", null, null, "TSUKI", jpeg())

    private fun jpeg(): ByteArray {
        val img = java.awt.image.BufferedImage(600, 800, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = java.awt.Color(30, 160, 60)
        g.fillRect(0, 0, 600, 800)
        g.dispose()
        return java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(img, "jpg", it) }.toByteArray()
    }

    private fun pickedOrigin(s: Sim, target: PickFor): Pair<String, Int?> {
        s.source(Enums.Source.phone)
        waitFor { Remote.device.playback.state.source == Enums.Source.phone }
        s.device.begins.clear()
        Remote.pick(target, offer())
        waitFor { s.device.begins.isNotEmpty() }
        return s.device.begins.first()
    }

    @Test
    fun anAskTakesTheSourceItCameWithOnTheSheetAndTheNotice() {
        Remote.artist(app, net.seilent.funecomp.artist.Http { _, _ -> net.seilent.funecomp.artist.Response(404, ByteArray(0)) })
        val s = live()
        val a = main()
        assertEquals("ok shown=true", ask(s, "TSUKI"))
        waitFor { a.get().shell.sheet.shown }
        a.get().shell.sheet.accept(1)
        waitFor { a.get().shell.nav.top is Page.Browse }
        val target = (a.get().shell.nav.top as Page.Browse).target
        assertEquals(PickFor("TSUKI", origin = Enums.ImgOrigin.library), target)
        a.pause().stop().destroy()
        assertEquals("TSUKI" to Enums.ImgOrigin.library, pickedOrigin(s, target))
        s.source(Enums.Source.local)
        waitFor { Remote.device.playback.state.source == Enums.Source.local }
        assertEquals("ok shown=false", ask(s, "TSUKI"))
        assertEquals(Enums.ImgOrigin.library, tapped().getIntExtra(Main.EXTRA_PICK_ORIGIN, -1))
        val cold = Robolectric.buildActivity(Main::class.java, tapped()).create().start().resume().visible()
        waitFor { cold.get().shell.sheet.shown }
        cold.get().shell.sheet.accept(1)
        waitFor { cold.get().shell.nav.top is Page.Browse }
        val tap = (cold.get().shell.nav.top as Page.Browse).target
        assertEquals(PickFor("TSUKI", origin = Enums.ImgOrigin.library), tap)
        cold.pause().stop().destroy()
        assertEquals("TSUKI" to Enums.ImgOrigin.library, pickedOrigin(s, tap))
    }

    @Test
    fun theLinkNotificationKeepsItsOpenIntent() {
        val s = live()
        assertEquals("ok shown=false", ask(s, "TSUKI"))
        val link = nm.getNotification(1)!!.contentIntent
        assertEquals(Main.ACTION_OPEN, shadowOf(link).savedIntent.action)
        assertFalse(shadowOf(link).savedIntent.hasExtra(Main.EXTRA_PICK_NAME))
        assertNotEquals(shadowOf(link).requestCode, shadowOf(notice()!!.contentIntent).requestCode)
        assertTrue(shadowOf(notice()!!.contentIntent).flags and PendingIntent.FLAG_IMMUTABLE != 0)
    }

    @Test
    fun aStopBetweenTheReadAndTheMainThreadPostsANotice() {
        val s = live()
        val a = main()
        val queued = CountDownLatch(1)
        Remote.queuedAsk = { queued.countDown() }
        var out = ""
        val t = Thread { out = s.askPick(ArtPick("TSUKI", null)) }.apply { start() }
        assertTrue(queued.await(5, TimeUnit.SECONDS))
        a.pause().stop()
        waitFor { !t.isAlive }
        assertEquals("ok shown=false", out)
        assertFalse(a.get().shell.sheet.shown)
        assertNotNull(notice())
        a.destroy()
    }

    @Test
    fun blockedNotificationsRefuseAndANameIsRequired() {
        val s = live()
        nm.setNotificationsEnabled(false)
        assertEquals("error 4 open the phone app", ask(s, "TSUKI"))
        assertEquals("error 2 bad value", ask(s, " "))
        assertNull(notice())
    }

    @Test
    fun aPickOrAnOpenedPickClearsTheNotice() {
        val s = live()
        assertEquals("ok shown=false", ask(s, "TSUKI"))
        Remote.pick(PickFor("Other"), net.seilent.funecomp.artist.Offer("artist", "musicbrainz", "u", dj, null, dj, "Other", null))
        waitFor { Remote.picks.isNotEmpty() }
        assertNotNull(notice())
        Remote.pick(PickFor("tsuki", library = true), net.seilent.funecomp.artist.Offer("artist", "musicbrainz", "u", dj, null, dj, "Tsuki", null))
        assertNull(notice())
        assertEquals("ok shown=false", ask(s, "TSUKI"))
        val a = main(tapped())
        waitFor { a.get().shell.sheet.shown }
        assertNull(notice())
        a.get().shell.sheet.close()
        a.pause().stop().destroy()
    }

    @Test
    fun aDelayedAskFromTheFaultIsAnswered() {
        live(faults = listOf("ask-pick=TSUKI@200"))
        waitFor { logs.any { "ask ART_PICK" in it } }
        assertTrue(logs.any { it.contains("ask ART_PICK") && it.endsWith("-> ok shown=false") })
    }

    @Test
    fun a17PeerNeverAsksAndThePhoneOffersTheBit() {
        live(minor = 7, faults = listOf("ask-pick=TSUKI@100"))
        Thread.sleep(500)
        shadowOf(Looper.getMainLooper()).idle()
        val hello = logs.first { "hello app" in it }
        val features = hello.substringAfter("features=").toLong()
        assertTrue(features and (1L shl Feature.artPick) != 0L)
        assertTrue(logs.none { "ART_PICK" in it })
        assertNull(notice())
    }

    @Test
    fun anIntentWithoutTheNonceDoesNothing() {
        val s = live()
        assertEquals("ok shown=false", ask(s, "TSUKI"))
        val forged = Intent(app, Main::class.java).setAction(Main.ACTION_PICK_ART).putExtra(Main.EXTRA_PICK_NAME, "Forged")
        val a = main(forged)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(a.get().shell.sheet.shown)
        a.pause().stop()
        a.newIntent(Intent(forged).putExtra(Main.EXTRA_PICK_NONCE, "wrong"))
        a.restart().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(a.get().shell.sheet.shown)
        a.pause().stop()
        a.newIntent(tapped())
        a.restart().resume()
        waitFor { a.get().shell.sheet.shown }
        assertEquals("TSUKI", a.get().shell.sheet.title)
        a.pause().stop().destroy()
    }

    @Test
    fun aNoticeLeftByAnEarlierProcessIsClearedByAPick() {
        live()
        val n = android.app.Notification.Builder(app, PickNotice.CHANNEL).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle("TSUKI").build()
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(android.app.NotificationChannel(PickNotice.CHANNEL, "Art pick", NotificationManager.IMPORTANCE_DEFAULT))
        app.getSystemService(NotificationManager::class.java).notify(PickNotice.ID, n)
        Remote.pick(PickFor("Other"), net.seilent.funecomp.artist.Offer("artist", "musicbrainz", "u", dj, null, dj, "Other", null))
        assertNotNull(notice())
        Remote.pick(PickFor("tsuki"), net.seilent.funecomp.artist.Offer("artist", "musicbrainz", "u", dj, null, dj, "Tsuki", null))
        assertNull(notice())
    }

    @Test
    fun aDisconnectOrAStopClearsTheNotice() {
        val s = live()
        assertEquals("ok shown=false", ask(s, "TSUKI"))
        s.stop()
        waitFor { Remote.phase != Phase.LIVE }
        assertNull(notice())
        val again = live()
        assertEquals("ok shown=false", ask(again, "TSUKI"))
        assertNotNull(notice())
        service!!.withIntent(Intent(app, LinkService::class.java).setAction(LinkService.ACTION_STOP)).startCommand(0, 2)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Phase.NONE, Remote.phase)
        assertNull(notice())
    }

    @Test
    fun aTapAfterAStopOnlyOpensTheApp() {
        val s = live()
        assertEquals("ok shown=false", ask(s, "TSUKI"))
        val tap = tapped()
        Remote.stop()
        val shadow = shadowOf(app)
        while (shadow.nextStartedService != null) Unit
        val a = Robolectric.buildActivity(Main::class.java, tap).create().start().resume().visible()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadow.nextStartedService)
        assertNull(a.get().shell.pick)
        assertFalse(a.get().shell.sheet.shown)
        a.pause().stop().destroy()
    }

    @Test
    fun aPendingConsentAskKeepsTheSheetForItself() {
        live()
        val a = main()
        a.pause().stop()
        app.getSharedPreferences(Main.PREFS, Context.MODE_PRIVATE).edit().putBoolean(Main.RECONNECT_ASK, true).commit()
        a.restart()
        assertFalse(Remote.pickAsk!!(PickFor("TSUKI")))
        a.resume()
        waitFor { a.get().shell.sheet.shown }
        assertEquals("reconnect after update", a.get().shell.sheet.title)
        a.pause().stop().destroy()
    }

    companion object {
        private const val QUIET_NS = 300_000_000L
        private val offMainWrite = AtomicLong(0)

        init {
            Snapshot.registerGlobalWriteObserver { if (Looper.myLooper() != Looper.getMainLooper()) offMainWrite.set(System.nanoTime()) }
        }
    }
}
