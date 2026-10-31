package net.seilent.funecomp

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Looper
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.Response
import net.seilent.funecomp.proto.ArtPick
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.SvcInfo
import net.seilent.funecomp.proto.SvcKey
import net.seilent.funecomp.proto.SvcLogin
import net.seilent.funecomp.proto.SvcLoginEnd
import net.seilent.funecomp.proto.SvcPkce
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import net.seilent.funecomp.ui.Phase
import org.junit.After
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
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SvcRemoteTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val sims = ArrayList<Sim>()
    private val logs = CopyOnWriteArrayList<String>()
    private val nm get() = shadowOf(app.getSystemService(NotificationManager::class.java))
    private var service: ServiceController<LinkService>? = null
    private val tidal = SvcInfo("tidal", "TIDAL", listOf("client_id", "client_secret", "pkce_client_id", "pkce_client_secret"), true)
    private val pkce = SvcPkce("tidal", "https://login.svcdemo.test/auth", "login.svcdemo.test", "https://svcdemo.test/cb", 7)

    @Before
    fun setUp() {
        Svc.reset()
        scrobblesOff(app)
        debuggable(true)
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        MediaLink.controls(app, false)
        app.getSharedPreferences(Main.DEVICE_PREFS, Context.MODE_PRIVATE).edit().putBoolean("batteryAsked", true).commit()
        Remote.artist(app, Http { _, _ -> Response(404, ByteArray(0)) })
        Lyrics.init(app, Http { _, _ -> Response(404, ByteArray(0)) })
    }

    @After
    fun tearDown() {
        Remote.svcShown = null
        service?.destroy()
        Remote.stop()
        sims.forEach { it.stop() }
        debuggable(true)
        Svc.reset()
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

    private fun live(vararg faults: String): Sim {
        val s = Sim(Options(Board.of("v1"), Faults(faults.toList()), log = { logs += it }, services = listOf(tidal)))
        sims += s
        val spec = "tcp:127.0.0.1:${s.listen(0)}"
        app.getSharedPreferences(Main.PREFS, Context.MODE_PRIVATE).edit().putString(Main.EXTRA_LINK, spec).commit()
        service = Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java).putExtra(Main.EXTRA_LINK, spec)).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE }
        return s
    }

    private fun notice() = nm.getNotification(SvcNotice.ID)

    private fun ask(s: Sim, q: SvcPkce = pkce): Thread = Thread { logs += "answer " + s.askPkce(q) }.apply { start() }

    @Test
    fun aLoginOffScreenPostsANoticeWithOnlyANonceAndItsEndClearsIt() {
        val s = live()
        assertEquals(listOf(tidal), Remote.device.hello!!.services)
        s.svcLogin(SvcLogin("tidal", "https://link.svcdemo.test/ABCDE", "link.svcdemo.test", "ABCDE", 300, 7))
        waitFor { notice() != null }
        assertEquals(7L, Remote.device.logins.shown?.seq)
        val n = notice()!!
        assertEquals("TIDAL", n.extras.getCharSequence("android.title").toString())
        assertEquals(SvcNotice.TEXT, n.extras.getCharSequence("android.text").toString())
        val tap = shadowOf(n.contentIntent).savedIntent
        assertEquals(Main.ACTION_SIGN_IN, tap.action)
        assertEquals(setOf(Main.EXTRA_SIGN_IN_NONCE), tap.extras!!.keySet())
        val nonce = tap.getStringExtra(Main.EXTRA_SIGN_IN_NONCE)
        assertTrue(SvcNotice.take(app, nonce))
        assertFalse(SvcNotice.take(app, nonce))
        s.svcEnd(SvcLoginEnd("tidal", 7, Enums.SvcLoginResult.done))
        waitFor { Remote.device.logins.shown == null }
        waitFor { notice() == null }
        assertEquals("TIDAL" to Svc.SIGNED_IN, Svc.view.line?.let { it.title to it.line })
    }

    @Test
    fun aShownAppGetsNoNotice() {
        val s = live()
        Remote.svcShown = { true }
        s.svcLogin(SvcLogin("tidal", "https://link.svcdemo.test/ABCDE", "link.svcdemo.test", "ABCDE", 300, 7))
        waitFor { Remote.device.logins.shown != null }
        Thread.sleep(100)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(notice())
    }

    @Test
    fun thePkceAskIsHeldAndCancelAnswers13Once() {
        val s = live()
        val t = ask(s)
        waitFor { Svc.view.ask != null }
        assertEquals("TIDAL", Svc.view.ask!!.name)
        assertEquals(pkce.authorizeUrl, Svc.view.ask!!.link)
        waitFor { notice() != null }
        Remote.svcCancel()
        Remote.svcCancel()
        t.join(5000)
        assertEquals(listOf("answer error ${Err.cancelled} cancelled"), logs.filter { it.startsWith("answer") })
        assertNull(notice())
        assertNull(Svc.view.ask)
    }

    @Test
    fun anEndThatOvertakesItsQueuedAskLeavesNothingHeld() {
        val s = live()
        val t = ask(s)
        val end = System.currentTimeMillis() + 10_000
        while (logs.none { it.contains("tx SVC_PKCE") }) {
            check(System.currentTimeMillis() < end) { "no ask sent" }
            Thread.sleep(10)
        }
        s.svcEnd(SvcLoginEnd("tidal", 7, Enums.SvcLoginResult.cancelled))
        while (ShadowLog.getLogsForTag(Remote.TAG).none { it.msg.startsWith("svc login end tidal 7") }) {
            check(System.currentTimeMillis() < end) { "no end read" }
            Thread.sleep(10)
        }
        t.join(5000)
        waitFor { ShadowLog.getLogsForTag(Remote.TAG).any { it.msg.startsWith("svc pkce tidal 7") } }
        assertNull(Svc.view.ask)
        ask(s, pkce.copy(seq = 8))
        waitFor { Svc.view.ask?.seq == 8L }
    }

    @Test
    fun anotherLinkStartsWithNoAskHeldAndNoEndedPairs() {
        val a = live()
        ask(a)
        waitFor { Svc.view.ask?.seq == 7L }
        a.svcEnd(SvcLoginEnd("tidal", 3, Enums.SvcLoginResult.cancelled))
        waitFor { ShadowLog.getLogsForTag(Remote.TAG).any { it.msg.startsWith("svc login end tidal 3") } }
        val b = live()
        assertNull(Svc.view.ask)
        ask(b, pkce.copy(seq = 3))
        waitFor { Svc.view.ask?.seq == 3L }
        assertTrue(logs.none { it == "answer error ${Err.cancelled} cancelled" })
    }

    @Test
    fun aRefusedAskIsAnswered4AndHeldNotAndItsLineShowsOncePerLink() {
        val s = live()
        val bad = pkce.copy(authorizeUrl = "https://evil.test/auth")
        val first = ask(s, bad)
        waitFor { !first.isAlive }
        assertEquals(listOf("answer error ${Err.refused} refused"), logs.filter { it.startsWith("answer") })
        assertNull(Svc.view.ask)
        assertEquals("TIDAL" to "login link refused", Svc.view.line?.let { it.title to it.line })
        val id = Svc.view.line!!.id
        repeat(2) {
            val t = ask(s, bad.copy(seq = 8))
            waitFor { !t.isAlive }
        }
        assertEquals(3, logs.count { it.startsWith("answer error ${Err.refused}") })
        assertEquals(id + 1, Svc.view.line!!.id)
        assertNull(notice())
    }

    @Test
    fun aRefusedLoginPostsNoNoticeAndQueuesItsLine() {
        val s = live()
        s.svcLogin(SvcLogin("tidal", "link.svcdemo.test/ABCDE", "link.svcdemo.test", "ABCDE", 300, 7))
        waitFor { Svc.view.line != null }
        assertEquals("TIDAL" to "login link refused", Svc.view.line?.let { it.title to it.line })
        assertTrue(Remote.device.logins.open.getValue("tidal").seen)
        assertNull(Remote.device.logins.shown)
        Thread.sleep(100)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(notice())
    }

    @Test
    fun theNoticeMovesToAHeadsUpChannelAndAnUnlistedLoginGetsIt() {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(SvcNotice.OLD_CHANNEL, "Sign in", NotificationManager.IMPORTANCE_DEFAULT))
        val s = live()
        s.svcLogin(SvcLogin("other", "https://link.svcdemo.test/ABCDE", "link.svcdemo.test", "ABCDE", 300, 3))
        waitFor { notice() != null }
        assertNull(nm.getNotificationChannel(SvcNotice.OLD_CHANNEL))
        assertEquals(NotificationManager.IMPORTANCE_HIGH, nm.getNotificationChannel(SvcNotice.CHANNEL).importance)
        assertEquals(SvcNotice.CHANNEL, notice()!!.channelId)
        assertEquals("other", notice()!!.extras.getCharSequence("android.title").toString())
    }

    @Test
    fun theFourthAskIn10MinutesGetsASilentNotice() {
        val s = live()
        for (seq in 1L..4L) {
            val t = ask(s, pkce.copy(seq = seq, authorizeUrl = "${pkce.authorizeUrl}?state=s$seq"))
            waitFor { Svc.view.ask?.seq == seq && ShadowLog.getLogsForTag(Remote.TAG).count { it.msg.startsWith("svc notice") } == seq.toInt() }
            assertEquals(if (seq < 4) Notification.GROUP_ALERT_ALL else Notification.GROUP_ALERT_SUMMARY, notice()!!.groupAlertBehavior)
            Remote.svcCancel()
            t.join(5000)
        }
        val lines = ShadowLog.getLogsForTag(Remote.TAG).map { it.msg }.filter { it.startsWith("svc notice") }
        assertEquals(List(3) { "svc notice posted" } + "svc notice posted silent", lines)
    }

    @Test
    fun aLinkSwitchClearsTheSignInAndPickNoticesAndTheirNonces() {
        val a = live()
        a.svcLogin(SvcLogin("tidal", "https://link.svcdemo.test/ABCDE", "link.svcdemo.test", "ABCDE", 300, 7))
        waitFor { notice() != null }
        val signIn = shadowOf(notice()!!.contentIntent).savedIntent.getStringExtra(Main.EXTRA_SIGN_IN_NONCE)
        val t = Thread { logs += "pick " + a.askPick(ArtPick("TSUKI", null)) }.apply { start() }
        waitFor { !t.isAlive && nm.getNotification(PickNotice.ID) != null }
        val pick = shadowOf(nm.getNotification(PickNotice.ID).contentIntent).savedIntent.getStringExtra(Main.EXTRA_PICK_NONCE)
        live()
        assertNull(notice())
        assertNull(nm.getNotification(PickNotice.ID))
        assertFalse(SvcNotice.take(app, signIn))
        assertFalse(PickNotice.take(app, pick))
    }

    @Test
    fun aCaughtRedirectGoesBackOnTheAsk() {
        val s = live()
        val t = ask(s)
        waitFor { Svc.view.ask != null }
        val token = Svc.begin()!!
        Svc.binder.ask(token, object : ISvcLoginPage.Stub() {
            override fun ended() {}
        })
        val url = "https://svcdemo.test/cb?code=c0de&state=s"
        assertTrue(Svc.binder.caught(token, url))
        t.join(5000)
        assertEquals(listOf("answer ok len=${url.length}"), logs.filter { it.startsWith("answer") })
        assertTrue(ShadowLog.getLogsForTag(Remote.TAG).none { "c0de" in it.msg })
        assertTrue(logs.none { "c0de" in it })
    }

    @Test
    fun aDropEndsTheAskAndTheNotice() {
        val s = live("replace@400")
        ask(s)
        waitFor { Svc.view.ask != null && notice() != null }
        waitFor { Remote.phase != Phase.LIVE }
        waitFor { Svc.view.ask == null && notice() == null }
        assertTrue(Remote.device.logins.open.isEmpty())
    }

    @Test
    fun keysGetTheirReplyLinesAndNeverReachTheLog() {
        val s = live("svc-keys=4:1")
        fun send(): String {
            var line: String? = null
            Remote.svcKeys("tidal", listOf(SvcKey("client_id", "s3cret"))) { line = it }
            waitFor { line != null }
            return line!!
        }
        assertEquals(Svc.REFUSED, send())
        assertEquals(Svc.KEYS_SENT, send())
        assertTrue(ShadowLog.getLogsForTag(Remote.TAG).none { "s3cret" in it.msg })
        assertTrue(logs.none { "s3cret" in it })
        val files = app.filesDir.walkTopDown().filter { it.isFile }.toList()
        assertTrue(files.none { f -> "s3cret" in String(f.readBytes(), Charsets.ISO_8859_1) })
        val prefs = File(app.applicationInfo.dataDir, "shared_prefs").walkTopDown().filter { it.isFile }.toList()
        assertTrue(prefs.none { f -> "s3cret" in f.readText() })
        assertNotNull(s)
    }

    @Test
    fun withoutBit32OnTheDeviceNothingIsKeptAndNoKeysGoOut() {
        val s = live("svc-login=7:link.svcdemo.test:ABCDE@0", "features=${Groups.all and Groups.mask("svcLogin").inv()}")
        assertFalse(Remote.device.has("svcLogin"))
        assertTrue(Remote.device.hello!!.services.isEmpty())
        Thread.sleep(300)
        var line: String? = null
        Remote.svcKeys("tidal", listOf(SvcKey("client_id", "x"))) { line = it }
        waitFor { line != null }
        assertEquals(Svc.NO_REPLY, line)
        assertTrue(logs.none { it.contains("rx SVC_KEYS") })
        assertTrue(logs.none { it.contains("tx SVC_LOGIN") })
        assertNull(Remote.device.logins.shown)
        assertNotNull(s)
    }
}
