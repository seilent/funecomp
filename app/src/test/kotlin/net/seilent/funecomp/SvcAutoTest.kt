package net.seilent.funecomp

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.Response
import net.seilent.funecomp.proto.SvcInfo
import net.seilent.funecomp.proto.SvcLogin
import net.seilent.funecomp.proto.SvcPkce
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.sim.Board
import net.seilent.funecomp.sim.Faults
import net.seilent.funecomp.sim.Options
import net.seilent.funecomp.sim.Sim
import net.seilent.funecomp.ui.ONLINE_STREAMING
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Previewed
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class SvcAutoTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val app get() = RuntimeEnvironment.getApplication()
    private val nm get() = shadowOf(app.getSystemService(NotificationManager::class.java))
    private val sims = ArrayList<Sim>()
    private val logs = CopyOnWriteArrayList<String>()
    private var service: ServiceController<LinkService>? = null
    private val open = ArrayList<ActivityController<Main>>()
    private val tidal = SvcInfo("tidal", "TIDAL", listOf("client_id"), true)
    private val uri = "https://link.svcdemo.test/ABCDE"
    private val slow = "https://example.org/slow.jpg"
    private val gate = CountDownLatch(1)

    @Before
    fun setUp() {
        Svc.reset()
        scrobblesOff(app)
        debuggable(true)
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.BLUETOOTH_SCAN)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        MediaLink.controls(app, false)
        Remote.artist(app, Http { url, _ ->
            if (url == slow) gate.await(10, TimeUnit.SECONDS)
            Response(404, ByteArray(0))
        })
        Lyrics.init(app, Http { _, _ -> Response(404, ByteArray(0)) })
        app.getSharedPreferences(Main.DEVICE_PREFS, Context.MODE_PRIVATE).edit().putBoolean("batteryAsked", true).commit()
    }

    @After
    fun tearDown() {
        gate.countDown()
        open.filter { !it.get().isDestroyed }.forEach { it.pause().stop().destroy() }
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
            rule.waitForIdle()
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
        rule.waitForIdle()
    }

    private fun settle() {
        Thread.sleep(200)
        waitFor { true }
    }

    private fun known(vararg hosts: String) {
        app.getSharedPreferences(SvcNotice.PREFS, Context.MODE_PRIVATE).edit().putString("hosts", hosts.joinToString("\n") { "tidal $it" }).commit()
    }

    private fun live(vararg faults: String): Sim {
        val s = Sim(Options(Board.of("v1"), Faults(faults.toList()), log = { logs += it }, services = listOf(tidal)))
        sims += s
        val spec = "tcp:127.0.0.1:${s.listen(0)}"
        app.getSharedPreferences(Main.PREFS, Context.MODE_PRIVATE).edit().putString(Main.EXTRA_LINK, spec).commit()
        service = Robolectric.buildService(LinkService::class.java, Intent(app, LinkService::class.java).putExtra(Main.EXTRA_LINK, spec)).create().startCommand(0, 1)
        waitFor { Remote.phase == Phase.LIVE }
        debuggable(false)
        return s
    }

    private fun main(): ActivityController<Main> {
        val a = Robolectric.buildActivity(Main::class.java, Intent(app, Main::class.java).setAction(Intent.ACTION_MAIN)).create().start().resume().visible()
        open += a
        waitFor { !a.get().shell.gated }
        return a
    }

    private fun ask(s: Sim, seq: Long): Thread = Thread {
        logs += "answer " + s.askPkce(SvcPkce("tidal", "https://login.svcdemo.test/auth?state=s$seq", "login.svcdemo.test", "https://svcdemo.test/cb", seq))
    }.apply { start() }

    private fun started(a: ActivityController<Main>): Intent? = shadowOf(a.get()).nextStartedActivity

    private fun notice() = nm.getNotification(SvcNotice.ID)

    private fun tap(): Intent = shadowOf(notice()!!.contentIntent).savedIntent

    private fun notices() = ShadowLog.getLogsForTag(Remote.TAG).map { it.msg }.filter { it.startsWith("svc notice") }

    private fun page(i: Intent?) {
        assertNotNull(i)
        assertEquals(net.seilent.funecomp.SvcLogin::class.java.name, i!!.component?.className)
        assertEquals(setOf(Svc.EXTRA_TOKEN), i.extras!!.keySet())
        assertEquals(0, i.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun aKnownHostOpensThePageAndTheTabWithNoTapIntoMainsTask() {
        known("login.svcdemo.test", "link.svcdemo.test")
        val s = live()
        val a = main()
        val t = ask(s, 7)
        waitFor { Svc.view.ask?.page == true }
        page(started(a))
        assertFalse(a.get().shell.sheet.shown)
        a.pause()
        a.resume()
        settle()
        assertNull(started(a))
        Remote.svcCancel()
        t.join(5000)
        s.svcLogin(SvcLogin("tidal", uri, "link.svcdemo.test", "ABCDE", 300, 8))
        waitFor { a.get().shell.sheet.shown }
        val tab = started(a)!!
        assertEquals(Intent.ACTION_VIEW, tab.action)
        assertEquals(uri, tab.dataString)
        assertEquals(0, tab.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertEquals(listOf("open", "cancel"), a.get().shell.sheet.items)
        assertEquals(listOf("link.svcdemo.test", "ABCDE"), a.get().shell.sheet.info)
        assertNull(notice())
        assertTrue(notices().isEmpty())
    }

    @Test
    fun aFirstHostAsksOnceIsKeptAndTheNextOpensByItself() {
        val s = live()
        val a = main()
        val t = ask(s, 7)
        waitFor { a.get().shell.sheet.shown }
        assertEquals(listOf("login.svcdemo.test"), a.get().shell.sheet.info)
        assertEquals(listOf("continue", "cancel"), a.get().shell.sheet.items)
        assertNull(started(a))
        rule.onNodeWithTag("sheet.item.0").performClick()
        waitFor { Svc.view.ask?.page == true }
        page(started(a))
        assertEquals("tidal login.svcdemo.test", app.getSharedPreferences(SvcNotice.PREFS, Context.MODE_PRIVATE).getString("hosts", null))
        Remote.svcCancel()
        t.join(5000)
        ask(s, 8)
        waitFor { Svc.view.ask?.page == true }
        page(started(a))
        assertFalse(a.get().shell.sheet.shown)
    }

    @Test
    fun aPageKilledWhileAwayShowsSignInEndedOnceMainIsBackFromRecents() {
        known("login.svcdemo.test")
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, SvcLoginHost::class.java), Svc.binder)
        val deaths = ArrayList<() -> Unit>()
        Svc.watch = { _, died -> deaths += died }
        val s = live()
        val a = main()
        val t = ask(s, 7)
        waitFor { Svc.view.ask?.page == true }
        val start = started(a)!!
        a.pause().stop()
        Robolectric.buildActivity(net.seilent.funecomp.SvcLogin::class.java, start).create().start().resume()
        waitFor { deaths.isNotEmpty() }
        val shows = a.get().shell.toast.shows
        deaths.single()()
        waitFor { !t.isAlive }
        assertTrue(logs.any { it == "answer error ${Err.cancelled} cancelled" })
        assertEquals(Svc.ENDED, Svc.view.line?.line)
        val again = Robolectric.buildActivity(net.seilent.funecomp.SvcLogin::class.java, start).create().start().resume()
        waitFor { again.get().isFinishing }
        a.restart()
        settle()
        assertEquals(shows, a.get().shell.toast.shows)
        assertEquals(Svc.ENDED, Svc.view.line?.line)
        a.resume()
        waitFor { a.get().shell.toast.shows == shows + 1 }
        assertEquals("TIDAL" to Svc.ENDED, a.get().shell.toast.let { it.artist to it.title })
        assertNull(Svc.view.line)
        assertTrue(ShadowLog.getLogsForTag(Remote.TAG).any { it.msg == "svc line shown sign in ended" })
        a.pause()
        a.resume()
        settle()
        assertEquals(shows + 1, a.get().shell.toast.shows)
    }

    @Test
    fun aPausedMainStartsNothingPostsTheHeadsUpAndALauncherResumeOpensOnce() {
        known("login.svcdemo.test")
        val s = live()
        val a = main()
        a.pause()
        ask(s, 7)
        waitFor { notice() != null }
        assertEquals(listOf("svc notice posted"), notices())
        a.get().control.svcContinue()
        assertNull(started(a))
        assertFalse(Svc.view.ask!!.page)
        a.resume()
        waitFor { Svc.view.ask?.page == true }
        page(started(a))
        assertNull(started(a))
        assertNull(notice())
        assertFalse(a.get().shell.sheet.shown)
    }

    @Test
    fun anAskBehindAnotherSheetGetsTheHeadsUpWhenMainLeaves() {
        known("login.svcdemo.test")
        val s = live()
        val a = main()
        a.get().shell.sheet.open("other", listOf("a")) {}
        ask(s, 7)
        waitFor { Svc.view.ask != null }
        settle()
        assertNull(notice())
        assertNull(started(a))
        a.pause()
        waitFor { notice() != null }
        assertEquals(listOf("svc notice posted"), notices())
        a.get().shell.sheet.close()
        a.resume()
        waitFor { Svc.view.ask?.page == true }
        page(started(a))
        assertNull(notice())
    }

    @Test
    fun aNoticeTapOpensKeepsTheStackLeavesAPendingPickAloneAndATapOpensAgain() {
        known("link.svcdemo.test")
        val s = live()
        val a = main()
        a.get().shell.nav.push(Page.Online(ONLINE_STREAMING))
        waitFor { a.get().shell.nav.top == Page.Online(ONLINE_STREAMING) }
        var picked: Previewed? = null
        Remote.pickLink(slow, "TSUKI") { picked = it }
        a.pause().stop()
        s.svcLogin(SvcLogin("tidal", uri, "link.svcdemo.test", "ABCDE", 300, 7))
        waitFor { notice() != null }
        a.newIntent(tap())
        a.restart().resume()
        waitFor { a.get().shell.sheet.shown }
        assertEquals(uri, started(a)!!.dataString)
        assertEquals(Page.Online(ONLINE_STREAMING), a.get().shell.nav.top)
        gate.countDown()
        waitFor { picked != null }
        assertTrue(ShadowLog.getLogsForTag(Remote.TAG).none { it.msg == "art pick cancelled" })
        assertNull(started(a))
        assertTrue(SvcNotice.post(app, "TIDAL"))
        a.pause()
        a.newIntent(tap())
        a.resume()
        waitFor { a.get().shell.sheet.shown }
        assertEquals(uri, started(a)!!.dataString)
        assertNull(started(a))
    }

    @Test
    fun theFourthAskIn10MinutesShowsTheTapSheetAndANoticeTapStillOpens() {
        known("login.svcdemo.test")
        val s = live()
        val a = main()
        for (seq in 1L..3L) {
            a.pause()
            val t = ask(s, seq)
            waitFor { notices().size == seq.toInt() }
            a.resume()
            waitFor { Svc.view.ask?.page == true }
            page(started(a))
            Remote.svcCancel()
            t.join(5000)
        }
        assertEquals(List(3) { "svc notice posted" }, notices())
        a.pause()
        val t = ask(s, 4)
        waitFor { notices().size == 4 }
        assertEquals("svc notice posted silent", notices().last())
        a.resume()
        waitFor { a.get().shell.sheet.shown }
        assertEquals(listOf("continue", "cancel"), a.get().shell.sheet.items)
        assertNull(started(a))
        rule.onNodeWithTag("sheet.item.1").performClick()
        t.join(5000)
        waitFor { Svc.view.ask == null }
        a.pause()
        ask(s, 5)
        waitFor { notices().size == 5 }
        a.newIntent(tap())
        a.resume()
        waitFor { Svc.view.ask?.page == true }
        page(started(a))
    }

    @Test
    fun aReconnectRePushOfTheSameLoginStartsAndPostsNothingMore() {
        known("link.svcdemo.test")
        val s = live()
        val a = main()
        s.svcLogin(SvcLogin("tidal", uri, "link.svcdemo.test", "ABCDE", 300, 7))
        waitFor { a.get().shell.sheet.shown }
        assertEquals(uri, started(a)!!.dataString)
        a.pause()
        settle()
        val hellos = logs.count { "rx HELLO" in it }
        s.current!!.close("test")
        val end = System.currentTimeMillis() + 30_000
        while (logs.count { "rx HELLO" in it } <= hellos || Remote.phase != Phase.LIVE) {
            check(System.currentTimeMillis() < end) { "no second session" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            Thread.sleep(100)
        }
        s.svcLogin(SvcLogin("tidal", uri, "link.svcdemo.test", "ABCDE", 300, 7))
        waitFor { Remote.device.logins.shown != null }
        settle()
        a.resume()
        waitFor { a.get().shell.sheet.shown }
        settle()
        assertNull(started(a))
        assertTrue(notices().isEmpty())
        assertNull(notice())
    }

    @Test
    fun onlyAValidNonceArmsAndOnlyOnce() {
        live()
        val a = main()
        assertTrue(SvcNotice.post(app, "TIDAL"))
        val valid = tap()
        a.newIntent(Intent(app, Main::class.java).setAction(Main.ACTION_SIGN_IN).putExtra(Main.EXTRA_SIGN_IN_NONCE, "forged"))
        settle()
        assertFalse(Svc.spendTap())
        a.newIntent(valid)
        settle()
        assertTrue(Svc.spendTap())
        a.newIntent(valid)
        settle()
        assertFalse(Svc.spendTap())
    }
}
