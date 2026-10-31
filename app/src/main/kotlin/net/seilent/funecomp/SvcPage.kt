package net.seilent.funecomp

import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.ClientCertRequest
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import net.seilent.funecomp.state.PkcePolicy
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

class SvcPage(
    private val prefix: PkcePolicy.Prefix,
    private val caught: (String) -> Unit,
    private val host: (String) -> Unit,
    private val gone: () -> Unit,
    private val log: (String) -> Unit,
    private val wipe: () -> Unit = ::stores,
) {
    private val main = Handler(Looper.getMainLooper())
    private val done = AtomicBoolean(false)

    val taken: Boolean get() = done.get()

    val client = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = first(view, request.url.toString())

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = second(view, request.url.toString())

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) = third(view, url.orEmpty())

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError?) = handler.cancel()

        override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String?, realm: String?) = handler.cancel()

        override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) = request.cancel()

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            log("svc page renderer gone")
            if (done.compareAndSet(false, true)) gone()
            return true
        }
    }

    val chrome = object : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) = request.deny()

        override fun onConsoleMessage(message: ConsoleMessage): Boolean = true

        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean = false

        override fun onCreateWindow(view: WebView, dialog: Boolean, gesture: Boolean, result: Message): Boolean = false

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) = callback.invoke(origin, false, false)
    }

    fun setup(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            setGeolocationEnabled(false)
        }
        WebView.setWebContentsDebuggingEnabled(false)
        view.setDownloadListener(null)
        view.webViewClient = client
        view.webChromeClient = chrome
    }

    fun open(view: WebView, url: String) {
        clear(view)
        view.loadUrl(url)
    }

    fun close(view: WebView) {
        view.stopLoading()
        clear(view)
    }

    private fun clear(view: WebView) {
        wipe()
        view.clearCache(true)
        view.clearFormData()
        view.clearHistory()
    }

    internal fun first(view: WebView, url: String): Boolean {
        if (done.get()) return true
        return when (PkcePolicy.seen(url, prefix)) {
            PkcePolicy.Seen.Pass -> false
            PkcePolicy.Seen.Blocked -> true.also { log("svc page blocked ${url.substringBefore(':')}") }
            else -> true.also { take(view, url, "L1") }
        }
    }

    internal fun second(view: WebView, url: String): WebResourceResponse? {
        if (done.get()) return empty()
        return when (PkcePolicy.seen(url, prefix)) {
            PkcePolicy.Seen.Pass -> null
            PkcePolicy.Seen.Blocked -> if (url.startsWith("http:", ignoreCase = true)) empty() else null
            else -> empty().also { take(view, url, "L2") }
        }
    }

    internal fun third(view: WebView, url: String) {
        if (done.get()) return view.stopLoading()
        when (PkcePolicy.seen(url, prefix)) {
            PkcePolicy.Seen.Pass -> Uri.parse(url).host?.let { host(it) }
            PkcePolicy.Seen.Blocked -> view.stopLoading()
            else -> {
                take(view, url, "L3")
                view.stopLoading()
            }
        }
    }

    private fun take(view: WebView, url: String, layer: String) {
        if (!done.compareAndSet(false, true)) return
        log("svc pkce caught $layer len=${url.length}")
        val inert = {
            view.settings.javaScriptEnabled = false
            view.stopLoading()
            caught(url)
        }
        if (Looper.myLooper() == main.looper) inert() else main.post(inert)
    }

    private fun empty() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    companion object {
        const val SUFFIX = "login"

        fun stores() {
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
            WebStorage.getInstance().deleteAllData()
        }
    }
}
