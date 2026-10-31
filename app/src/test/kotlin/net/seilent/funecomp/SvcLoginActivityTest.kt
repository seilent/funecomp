package net.seilent.funecomp

import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import android.os.Process
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebView
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.SvcInfo
import net.seilent.funecomp.proto.SvcLoginEnd
import net.seilent.funecomp.proto.SvcPkce
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
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
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SvcLoginActivityTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val answers = CopyOnWriteArrayList<String>()
    private val hello = HelloReply(1, 16, "v1", "t", 240, 320, 240, 320, 16384, 30_000, 5_000, Groups.all, 1, 1, 4, services = listOf(SvcInfo("tidal", "TIDAL", listOf("client_id"), true)))
    private val ask = SvcPkce("tidal", "https://login.svcdemo.test/auth?state=s", "login.svcdemo.test", "https://svcdemo.test/cb", 7)

    @Before
    fun setUp() {
        Svc.reset()
        Svc.watch = { _, _ -> }
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, SvcLoginHost::class.java), Svc.binder)
    }

    @After
    fun tearDown() {
        ShadowApplication.setProcessName(app.packageName)
        SvcLogin.dataSuffix = { WebView.setDataDirectorySuffix(it) }
        SvcLogin.watch = { b, gone -> b.linkToDeath({ gone() }, 0) }
        SvcLogin.kill = { Process.killProcess(Process.myPid()) }
        Svc.reset()
    }

    private fun record(a: Svc.Answer) {
        answers += when (a) {
            is Svc.Answer.Redirect -> "redirect ${a.url}"
            is Svc.Answer.Error -> "error ${a.code}"
        }
    }

    private fun idle() = repeat(3) { shadowOf(Looper.getMainLooper()).idle() }

    private fun open(token: String? = null): ActivityController<SvcLogin> {
        Svc.pkce(ask, hello, ::record)
        val t = token ?: Svc.begin()!!
        return Robolectric.buildActivity(SvcLogin::class.java, Intent(app, SvcLogin::class.java).putExtra(Svc.EXTRA_TOKEN, t)).create().start().resume().visible().also { idle() }
    }

    private fun touch(dir: File, path: String) = File(dir, path).apply {
        parentFile!!.mkdirs()
        writeText("x")
    }

    private fun loginCaches() = listOf(touch(app.cacheDir, "webview_login/Default/HTTP Cache/Cache_Data/index"), touch(app.noBackupFilesDir, ".webview_login/BrowserMetrics-spare.pma"))

    private fun web(v: View): WebView? = when (v) {
        is WebView -> v
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { web(v.getChildAt(it)) }
        else -> null
    }

    @Test
    fun thePageIsSecureAndLoadsTheAuthorizeUrl() {
        val a = open()
        assertNotEquals(0, a.get().window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        assertEquals(ask.authorizeUrl, shadowOf(web(a.get().window.decorView)!!).lastLoadedUrl)
        assertTrue(answers.isEmpty())
        a.pause().stop()
        assertTrue(answers.isEmpty())
        a.destroy()
    }

    @Test
    fun backAnswersCancelledOnceAndFinishes() {
        val a = open()
        a.get().onBackPressedDispatcher.onBackPressed()
        idle()
        assertTrue(a.get().isFinishing)
        a.pause().stop().destroy()
        assertEquals(listOf("error ${Err.cancelled}"), answers)
    }

    @Test
    fun aPageClosedByTheSystemAnswersCancelled() {
        val a = open()
        a.pause().stop().destroy()
        assertEquals(listOf("error ${Err.cancelled}"), answers)
    }

    @Test
    fun theDeviceEndClosesThePageWithNoAnswer() {
        val a = open()
        Svc.ended(SvcLoginEnd("tidal", 7, Enums.SvcLoginResult.cancelled))
        idle()
        assertTrue(a.get().isFinishing)
        a.pause().stop().destroy()
        assertTrue(answers.isEmpty())
        assertEquals(Svc.ENDED, Svc.view.line?.line)
    }

    @Test
    fun inTheLoginProcessTheSuffixIsSetAndOldLoginDataIsDeletedFirst() {
        val suffixes = ArrayList<String>()
        SvcLogin.dataSuffix = { suffixes += it }
        val old = File(app.applicationInfo.dataDir, "app_webview_login/Default/Cookies").apply {
            parentFile!!.mkdirs()
            writeText("sid=1")
        }
        val other = File(app.applicationInfo.dataDir, "app_webview/Default/Cookies").apply {
            parentFile!!.mkdirs()
            writeText("x")
        }
        val caches = loginCaches()
        val kept = listOf(touch(app.cacheDir, "WebView/Default/HTTP Cache/Cache_Data/index"), touch(app.noBackupFilesDir, "phonelib.tsv"))
        ShadowApplication.setProcessName("${app.packageName}:login")
        val a = open()
        ShadowApplication.setProcessName(app.packageName)
        assertEquals(listOf("login"), suffixes)
        assertFalse(old.exists())
        assertFalse(File(app.applicationInfo.dataDir, "app_webview_login").exists())
        assertFalse(File(app.cacheDir, "webview_login").exists())
        assertFalse(File(app.noBackupFilesDir, ".webview_login").exists())
        assertTrue(caches.none { it.exists() })
        assertTrue(other.exists())
        assertTrue(kept.all { it.exists() })
        a.pause().stop().destroy()
    }

    @Test
    fun aNewerPageKeepsTheLoginProcessItsDataAndItsCookies() {
        val kills = ArrayList<String>()
        SvcLogin.kill = { kills += "kill" }
        SvcLogin.dataSuffix = {}
        ShadowApplication.setProcessName("${app.packageName}:login")
        val first = open()
        val data = File(app.applicationInfo.dataDir, "app_webview_login/Default/Cookies").apply {
            parentFile!!.mkdirs()
            writeText("sid=1")
        }
        val caches = loginCaches()
        Svc.ended(SvcLoginEnd("tidal", 7, Enums.SvcLoginResult.cancelled))
        idle()
        assertTrue(first.get().isFinishing)
        Svc.pkce(ask.copy(seq = 8), hello, ::record)
        val second = Robolectric.buildActivity(SvcLogin::class.java, Intent(app, SvcLogin::class.java).putExtra(Svc.EXTRA_TOKEN, Svc.begin()!!)).create().start().resume().visible().also { idle() }
        assertTrue(data.exists())
        assertTrue(caches.all { it.exists() })
        val cookies = CookieManager.getInstance()
        cookies.setCookie("https://login.svcdemo.test", "sid=2")
        first.pause().stop().destroy()
        assertTrue(kills.isEmpty())
        assertNotNull(cookies.getCookie("https://login.svcdemo.test"))
        assertFalse(second.get().isFinishing)
        second.get().onBackPressedDispatcher.onBackPressed()
        idle()
        second.pause().stop().destroy()
        assertEquals(listOf("kill"), kills)
        assertNull(cookies.getCookie("https://login.svcdemo.test"))
        assertEquals(listOf("error ${Err.cancelled}"), answers)
    }

    @Test
    fun aProcessWhoseWebViewIsUpDeletesNothing() {
        SvcLogin.dataSuffix = { throw IllegalStateException("up") }
        val old = File(app.applicationInfo.dataDir, "app_webview_login/Default/Cookies").apply {
            parentFile!!.mkdirs()
            writeText("sid=1")
        }
        val caches = loginCaches()
        ShadowApplication.setProcessName("${app.packageName}:login")
        val a = open()
        ShadowApplication.setProcessName(app.packageName)
        assertTrue(old.exists())
        assertTrue(caches.all { it.exists() })
        a.pause().stop().destroy()
    }

    @Test
    fun theMainProcessDeletesNothing() {
        val old = File(app.applicationInfo.dataDir, "app_webview_login/Default/Cookies").apply {
            parentFile!!.mkdirs()
            writeText("sid=1")
        }
        open().pause().stop().destroy()
        assertTrue(old.exists())
    }

    @Test
    fun aHostDeathClosesThePageWithNoAnswer() {
        val deaths = ArrayList<() -> Unit>()
        SvcLogin.watch = { _, gone -> deaths += gone }
        val a = open()
        deaths.single()()
        idle()
        assertTrue(a.get().isFinishing)
        a.pause().stop().destroy()
        assertTrue(answers.isEmpty())
    }

    @Test
    fun aHostDisconnectClosesThePage() {
        val a = open()
        shadowOf(app).boundServiceConnections.single().onServiceDisconnected(ComponentName(app, SvcLoginHost::class.java))
        idle()
        assertTrue(a.get().isFinishing)
        a.pause().stop().destroy()
        assertTrue(answers.isEmpty())
    }

    @Test
    fun aWrongTokenFinishesWithNoAnswer() {
        val a = open("other")
        assertTrue(a.get().isFinishing)
        a.pause().stop().destroy()
        assertTrue(answers.isEmpty())
    }
}
