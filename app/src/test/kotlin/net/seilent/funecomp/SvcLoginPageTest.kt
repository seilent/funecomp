package net.seilent.funecomp

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.webkit.ClientCertRequest
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import net.seilent.funecomp.state.PkcePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate

@Implements(SslErrorHandler::class)
class RecordingSsl {
    @Implementation
    fun proceed() {
        HANDLED += "ssl proceed"
    }

    @Implementation
    fun cancel() {
        HANDLED += "ssl cancel"
    }
}

@Implements(HttpAuthHandler::class)
class RecordingAuth {
    @Implementation
    fun proceed(user: String?, password: String?) {
        HANDLED += "auth proceed"
    }

    @Implementation
    fun cancel() {
        HANDLED += "auth cancel"
    }
}

val HANDLED = ArrayList<String>()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [RecordingSsl::class, RecordingAuth::class])
class SvcLoginPageTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val ops = ArrayList<String>()
    private val caught = ArrayList<String>()
    private val hosts = ArrayList<String>()
    private var gone = 0
    private val start = "https://login.svcdemo.test/auth?state=s"
    private val redirect = "https://svcdemo.test/cb?code=c0de&state=s"
    private val page = SvcPage(PkcePolicy.prefix("https://svcdemo.test/cb")!!, { caught += it }, { hosts += it }, { gone++ }, {}) { ops += "wipe" }

    private class Rec(c: Context, val ops: MutableList<String>) : WebView(c) {
        override fun loadUrl(url: String) {
            ops += "load $url"
            super.loadUrl(url)
        }

        override fun clearCache(includeDiskFiles: Boolean) {
            ops += "cache"
            super.clearCache(includeDiskFiles)
        }

        override fun clearFormData() {
            ops += "form"
            super.clearFormData()
        }

        override fun clearHistory() {
            ops += "history"
            super.clearHistory()
        }

        override fun stopLoading() {
            ops += "stop"
            super.stopLoading()
        }
    }

    private fun view(): Rec = Rec(app, ops).also {
        page.setup(it)
        page.open(it, start)
    }

    private fun req(url: String, main: Boolean = true, redirect: Boolean = false, method: String = "GET") = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = main
        override fun isRedirect() = redirect
        override fun hasGesture() = false
        override fun getMethod() = method
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun theStoresAreClearedBeforeTheFirstLoadAndAfterClose() {
        val v = view()
        assertEquals(listOf("wipe", "cache", "form", "history", "load $start"), ops)
        ops.clear()
        page.close(v)
        assertEquals(listOf("stop", "wipe", "cache", "form", "history"), ops)
    }

    @Test
    fun theSettingsLockThePageDown() {
        val s = view().settings
        assertTrue(s.javaScriptEnabled)
        assertFalse(s.allowFileAccess)
        assertFalse(s.allowContentAccess)
        assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, s.mixedContentMode)
        assertFalse(s.supportMultipleWindows())
        assertFalse(s.javaScriptCanOpenWindowsAutomatically)
    }

    @Test
    fun theFirstLayerCatchesARedirectOnceAndNeverLoadsIt() {
        val v = view()
        assertFalse(page.client.shouldOverrideUrlLoading(v, req("https://login.svcdemo.test/next")))
        assertTrue(page.client.shouldOverrideUrlLoading(v, req(redirect, redirect = true)))
        assertTrue(page.client.shouldOverrideUrlLoading(v, req(redirect, redirect = false)))
        idle()
        assertEquals(listOf(redirect), caught)
        assertEquals(start, shadowOf(v).lastLoadedUrl)
        assertFalse(v.settings.javaScriptEnabled)
        assertTrue("stop" in ops)
    }

    @Test
    fun theSecondLayerCatchesAPostAndAFrameAndAnswersEmpty() {
        val v = view()
        assertNull(page.client.shouldInterceptRequest(v, req("https://login.svcdemo.test/app.js", main = false)))
        val r = page.client.shouldInterceptRequest(v, req(redirect, method = "POST"))
        assertNotNull(r)
        assertEquals(0, r!!.data.readBytes().size)
        idle()
        assertEquals(listOf(redirect), caught)
        page.client.onPageStarted(v, redirect, null)
        idle()
        assertEquals(listOf(redirect), caught)
    }

    @Test
    fun theThirdLayerCatchesStopsAndLeavesThePageInert() {
        val v = view()
        page.client.onPageStarted(v, "https://login.svcdemo.test/x", null)
        assertEquals(listOf("login.svcdemo.test"), hosts)
        page.client.onPageStarted(v, redirect, null)
        idle()
        assertEquals(listOf(redirect), caught)
        assertTrue(page.taken)
        assertFalse(v.settings.javaScriptEnabled)
        ops.clear()
        assertTrue(page.client.shouldOverrideUrlLoading(v, req("https://evil.test/steal")))
        assertEquals(0, page.client.shouldInterceptRequest(v, req("https://evil.test/pixel.gif", main = false))!!.data.readBytes().size)
        page.client.onPageStarted(v, "https://login.svcdemo.test/y", null)
        assertEquals(listOf("stop"), ops)
        assertEquals(listOf("login.svcdemo.test"), hosts)
        assertEquals(listOf(redirect), caught)
        assertEquals(start, shadowOf(v).lastLoadedUrl)
    }

    @Test
    fun otherSchemesAreBlockedAndStartNothing() {
        val v = view()
        for (u in listOf("intent://x#Intent;scheme=https;package=com.aspiro.tidal;end", "market://details?id=com.aspiro.tidal", "tel:123", "http://svcdemo.test/cb?code=x", "javascript:alert(1)")) {
            assertTrue(u, page.client.shouldOverrideUrlLoading(v, req(u)))
        }
        assertNull(shadowOf(app).nextStartedActivity)
        assertTrue(caught.isEmpty())
        assertNotNull(page.client.shouldInterceptRequest(v, req("http://svcdemo.test/x", main = false)))
    }

    @Test
    fun aRedirectPast2048BytesStillGoesToTheHostWhichRefusesIt() {
        val v = view()
        val long = redirect + "x".repeat(PkcePolicy.MAX_BYTES)
        assertTrue(page.client.shouldOverrideUrlLoading(v, req(long)))
        idle()
        assertEquals(listOf(long), caught)
    }

    @Test
    fun certificateErrorsAuthPromptsAndClientCertsAreRefused() {
        val v = view()
        HANDLED.clear()
        page.client.onReceivedSslError(v, Shadow.newInstanceOf(SslErrorHandler::class.java), null)
        page.client.onReceivedHttpAuthRequest(v, Shadow.newInstanceOf(HttpAuthHandler::class.java), "login.svcdemo.test", "r")
        var cert = ""
        page.client.onReceivedClientCertRequest(v, object : ClientCertRequest() {
            override fun getKeyTypes(): Array<String>? = null
            override fun getPrincipals(): Array<Principal>? = null
            override fun getHost() = "login.svcdemo.test"
            override fun getPort() = 443
            override fun proceed(privateKey: PrivateKey?, chain: Array<X509Certificate>?) {
                cert = "proceed"
            }
            override fun ignore() {
                cert = "ignore"
            }
            override fun cancel() {
                cert = "cancel"
            }
        })
        assertEquals(listOf("ssl cancel", "auth cancel"), HANDLED)
        assertEquals("cancel", cert)
    }

    @Test
    fun theRealWipeClearsTheCookies() {
        val c = CookieManager.getInstance()
        c.setCookie("https://login.svcdemo.test", "sid=1")
        assertNotNull(c.getCookie("https://login.svcdemo.test"))
        SvcPage.stores()
        assertNull(c.getCookie("https://login.svcdemo.test"))
    }

    @Test
    fun theChromeDeniesPermissionsAndWindowsAndSwallowsTheConsole() {
        val v = view()
        var denied = false
        page.chrome.onPermissionRequest(object : PermissionRequest() {
            override fun getOrigin(): Uri = Uri.parse("https://login.svcdemo.test")
            override fun getResources(): Array<String> = arrayOf(RESOURCE_VIDEO_CAPTURE)
            override fun grant(resources: Array<String>?) = throw AssertionError("granted")
            override fun deny() {
                denied = true
            }
        })
        assertTrue(denied)
        assertTrue(page.chrome.onConsoleMessage(ConsoleMessage("m", "s", 1, ConsoleMessage.MessageLevel.LOG)))
        assertFalse(page.chrome.onCreateWindow(v, false, true, android.os.Message.obtain()))
        var allowed: Boolean? = null
        page.chrome.onGeolocationPermissionsShowPrompt("https://login.svcdemo.test", GeolocationPermissions.Callback { _, allow, _ -> allowed = allow })
        assertEquals(false, allowed)
    }
}
