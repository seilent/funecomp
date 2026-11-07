package net.seilent.funecomp

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Looper
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.Response
import net.seilent.funecomp.proto.SvcInfo
import net.seilent.funecomp.proto.SvcKey
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import net.seilent.funecomp.sim.SvcAcct
import net.seilent.funecomp.state.SvcOpens
import net.seilent.funecomp.ui.Phase
import org.junit.After
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
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.FutureTask

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SvcAccountTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val sims = ArrayList<Sim>()
    private val logs = CopyOnWriteArrayList<String>()
    private val ends = CopyOnWriteArrayList<String>()
    private var service: ServiceController<LinkService>? = null
    private val tidal = SvcInfo("tidal", "TIDAL", listOf("client_id"), true)
    private val radio = SvcInfo("radio", "Radio", emptyList(), false)
    private val redirect = "https://svcdemo.test/cb?code=c0de"

    private inner class Page : ISvcLoginPage.Stub() {
        override fun ended() {
            ends += "ended"
        }
    }

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
        service?.destroy()
        Remote.stop()
        sims.forEach { it.stop() }
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

    private fun live(vararg faults: String, accounts: List<String> = listOf("tidal:out", "radio:out"), minor: Int = 17): Sim {
        val s = Sim(Options(Board.of("v1"), Faults(faults.toList()), minor = minor, log = { logs += it }, services = listOf(tidal, radio), accounts = accounts.map { SvcAcct.parse(it) }))
        sims += s
        val spec = "tcp:127.0.0.1:${s.listen(0)}"
        app.getSharedPreferences(Main.PREFS, Context.MODE_PRIVATE).edit().putString(Main.EXTRA_LINK, spec).commit()
        service = Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java).putExtra(Main.EXTRA_LINK, spec)).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE }
        return s
    }

    private fun again(drop: () -> Unit) {
        val hellos = logs.count { it.contains("rx HELLO") }
        drop()
        val end = System.currentTimeMillis() + 30_000
        while (logs.count { it.contains("rx HELLO") } <= hellos || Remote.phase != Phase.LIVE) {
            check(System.currentTimeMillis() < end) { "no second session" }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
            Thread.sleep(100)
        }
    }

    private fun start(service: String = "tidal"): String? {
        var line: String? = "none"
        Remote.svcStart(service) { line = it }
        waitFor { line != "none" }
        return line
    }

    private fun logout(account: String, downloads: Long?): String? {
        var line: String? = "none"
        Remote.svcLogout("tidal", account, downloads) { line = it }
        waitFor { line != "none" }
        return line
    }

    private fun page(): String {
        waitFor { Svc.view.ask != null }
        val t = Svc.begin()!!
        Svc.binder.ask(t, Page())
        return t
    }

    private fun count(text: String) = logs.count { it.contains(text) }

    @Test
    fun aStartBindsTheArmToItsAskAndOpensPastTheCap() {
        live("svc-start=ok:5@0")
        assertTrue(Remote.device.has("svcAccount"))
        waitFor { Remote.device.accounts?.get("tidal")?.signedIn == false }
        Svc.opens.remember("tidal", "login.svcdemo.test")
        repeat(SvcOpens.CAP) { Svc.opens.take("tidal", 100L + it, "https://login.svcdemo.test/x$it", true, true, SvcOpens.Kind.OPEN, android.os.SystemClock.elapsedRealtime()) }
        assertNull(start())
        waitFor { Svc.view.ask?.seq == 5L }
        assertEquals(1, count("rx SVC_LOGIN_START"))
        val a = Svc.view.ask!!
        assertEquals(SvcOpens.Verdict.OPEN, Remote.svcTake(a.service, a.seq, a.link, a.host, true))
        assertEquals(SvcOpens.Verdict.NONE, Remote.svcTake(a.service, a.seq, a.link, a.host, true))
    }

    @Test
    fun pastTheCapTheArmsNoticeIsAHeadsUp() {
        live("svc-start=ok:5@0")
        repeat(SvcOpens.CAP) { Svc.opens.take("tidal", 100L + it, "https://login.svcdemo.test/x$it", true, true, SvcOpens.Kind.NOTICE, android.os.SystemClock.elapsedRealtime()) }
        assertNull(start())
        waitFor { ShadowLog.getLogsForTag(Remote.TAG).any { it.msg.startsWith("svc notice") } }
        assertEquals(listOf("svc notice posted"), ShadowLog.getLogsForTag(Remote.TAG).map { it.msg }.filter { it.startsWith("svc notice") })
    }

    @Test
    fun aDeviceWithoutBit35GetsNoStartAndNoLogout() {
        live("svc-start=ok:5@0", minor = 16)
        assertFalse(Remote.device.has("svcAccount"))
        assertEquals(Svc.NO_REPLY, start())
        assertEquals(Svc.NO_REPLY, logout("", null))
        var keys: String? = null
        Remote.svcKeys("tidal", listOf(SvcKey("client_id", "x"))) { keys = it }
        waitFor { keys != null }
        assertEquals(Svc.KEYS_SENT, keys)
        assertEquals(0, count("rx SVC_LOGIN_START"))
        assertEquals(0, count("rx SVC_LOGOUT"))
        assertNull(Remote.device.accounts)
    }

    @Test
    fun aJoinOfAPendingCodeLoginShowsItAgainAndArmsItsLink() {
        live("svc-login=4:link.svcdemo.test:ABCDE@0")
        waitFor { Remote.device.logins.shown?.seq == 4L }
        Remote.svcSeen("tidal", 4)
        assertNull(Remote.device.logins.shown)
        assertNull(start())
        waitFor { count("tx SVC_LOGIN ") >= 2 }
        waitFor { Remote.device.logins.shown?.seq == 4L }
        val l = Remote.device.logins.shown!!
        Svc.opens.remember("tidal", "link.svcdemo.test")
        assertTrue(Svc.armed("tidal", 4, l.uri!!))
        assertEquals(SvcOpens.Verdict.OPEN, Remote.svcTake("tidal", 4, l.uri!!, l.host, true))
    }

    @Test
    fun theReplyAndItsLoginInOneReadBindBeforeTheLogin() {
        live("svc-start=ok:5@0", "svc-start-gap")
        assertNull(start("radio"))
        waitFor { Remote.device.logins.shown?.seq == 5L }
        val l = Remote.device.logins.shown!!
        assertEquals("radio", l.service)
        assertTrue(Svc.armed("radio", 5, l.uri!!))
    }

    @Test
    fun aLogoutCarriesTheAccountAndItsRepliesFollowTheDevice() {
        live("svc-logout=ok@1500", accounts = listOf("tidal:in:Night Owl:3"))
        waitFor { Remote.device.accounts?.get("tidal")?.downloads == 3L }
        assertEquals(Svc.ACCOUNT_CHANGED, logout("NIGHT OWL", 3))
        assertNull(logout("Night Owl", 2))
        waitFor { Svc.view.reopen == "tidal" }
        Remote.svcReopened()
        assertNull(Svc.view.reopen)
        val t0 = System.currentTimeMillis()
        var line: String? = "none"
        Remote.svcLogout("tidal", "Night Owl", 3) { line = it }
        waitFor { Svc.view.held == setOf("tidal") }
        waitFor { line != "none" }
        assertTrue(System.currentTimeMillis() - t0 >= 1500)
        assertEquals(Svc.SIGNED_OUT, line)
        assertTrue(Svc.view.held.isEmpty())
        assertTrue(ShadowLog.getLogsForTag(Remote.TAG).none { "Night Owl" in it.msg })
    }

    @Test
    fun aDeadlineTimeoutAfterTheSignedOutStateSaysSignedOut() {
        live("svc-logout=3:timeout@0", accounts = listOf("tidal:in:Night Owl"))
        waitFor { Remote.device.accounts?.get("tidal")?.signedIn == true }
        assertEquals(Svc.SIGNED_OUT, logout("Night Owl", null))
    }

    @Test
    fun aPageAcrossADropIsAnsweredOnTheNewLink() {
        val s = live("svc-start=ok:3@0", "svc-reask")
        start()
        val t = page()
        again { s.current!!.close("drop") }
        waitFor { count("tx SVC_PKCE") >= 2 }
        waitFor { Svc.attached("tidal") == t }
        assertEquals(1, count("rx SVC_LOGIN_START"))
        assertTrue(Svc.binder.caught(t, redirect))
        waitFor { logs.any { it.contains("ask SVC_PKCE") && it.contains("-> ok len=${redirect.length}") } }
        assertTrue(ends.isEmpty())
    }

    @Test
    fun aCatchLostInADropIsAnsweredFromTheKeptRedirect() {
        live("svc-start=ok:3@0", "svc-reask", "drop-after-reply")
        start()
        val t = page()
        again { assertTrue(Svc.binder.caught(t, redirect)) }
        waitFor { logs.any { it.contains("ask SVC_PKCE") && it.contains("-> ok len=${redirect.length}") } }
        waitFor { ShadowLog.getLogsForTag(Remote.TAG).any { it.msg == "svc pkce tidal 3 kept" } }
        assertNull(Svc.view.ask)
        assertEquals(1, count("rx SVC_LOGIN_START"))
    }

    @Test
    fun aLateReaskAfterTheFirstSignedOutStateGetsTheKeptRedirect() {
        live("svc-start=ok:3@0", "svc-reask", "drop-after-reply", "ask-held=1500")
        start()
        val t = page()
        again { assertTrue(Svc.binder.caught(t, redirect)) }
        waitFor { Remote.device.accounts?.get("tidal")?.signedIn == false }
        waitFor { logs.any { it.contains("ask SVC_PKCE") && it.contains("-> ok len=${redirect.length}") } }
        waitFor { ShadowLog.getLogsForTag(Remote.TAG).any { it.msg == "svc pkce tidal 3 kept" } }
        assertNull(Svc.view.ask)
    }

    @Test
    fun aBoundArmWhoseNoticeWasPostedSaysNothingAt120s() {
        val timers = CopyOnWriteArrayList<Pair<Long, Runnable>>()
        live("svc-start=ok:5@0")
        Svc.later = { ms, r ->
            timers += ms to r
            FutureTask<Unit>({}, Unit)
        }
        assertNull(start())
        waitFor { ShadowLog.getLogsForTag(Remote.TAG).any { it.msg == "svc notice posted" } }
        timers.last { it.first == Svc.ARM_MS }.second.run()
        assertNull(Svc.view.line)
    }

    @Test
    fun aRefusedLinkOfTheStartEndsItsArm() {
        live("svc-login-bare")
        assertNull(start("radio"))
        waitFor { ShadowLog.getLogsForTag(Remote.TAG).any { it.msg == "svc login radio 1 ok=false" } }
        assertFalse(Svc.armed("radio", 1, "link.svcdemo.test/C1"))
    }

    @Test
    fun aReplacedByeDetachesThePage() {
        val s = live("svc-start=ok:3@0", "svc-reask")
        start()
        val t = page()
        s.current!!.bye(Enums.ByeReason.replaced)
        waitFor { Remote.phase != Phase.LIVE }
        assertEquals(setOf("tidal"), Svc.view.held)
        assertNull(Svc.attached("tidal"))
        assertTrue(ends.isEmpty())
        assertNull(Svc.view.line)
        assertTrue(Svc.binder.caught(t, redirect))
        assertEquals(Svc.WAITING, Svc.view.line?.line)
    }

    @Test
    fun anyOtherByeEndsThePage() {
        val s = live("svc-start=ok:3@0", "svc-reask")
        start()
        page()
        s.current!!.bye(Enums.ByeReason.shutdown)
        waitFor { ends.isNotEmpty() }
        assertEquals(Svc.ENDED, Svc.view.line?.line)
        assertTrue(Svc.view.held.isEmpty())
    }
}
