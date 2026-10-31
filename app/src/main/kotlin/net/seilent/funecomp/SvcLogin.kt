package net.seilent.funecomp

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.RemoteException
import android.util.Log
import android.view.WindowManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import net.seilent.funecomp.state.HostRule
import net.seilent.funecomp.state.PkcePolicy
import net.seilent.funecomp.ui.LoginPage
import java.io.File

class SvcLogin : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private var host: ISvcLoginHost? = null
    private var token: String? = null
    private var page: SvcPage? = null
    private var web: WebView? = null
    private var url: String? = null
    private var answered = false
    private var bound = false
    private var counted = false
    private var name by mutableStateOf("")
    private var at by mutableStateOf("")
    private var ready by mutableStateOf(false)

    private val self = object : ISvcLoginPage.Stub() {
        override fun ended() {
            main.post { leave("ended") }
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
            val h = ISvcLoginHost.Stub.asInterface(b ?: return leave("no host"))
            host = h
            val a = try {
                watch(b) { main.post { leave("host gone") } }
                h.ask(token, self)
            } catch (_: RemoteException) {
                null
            }
            if (a == null || a.size < 4) return leave("no ask")
            begin(a[0], a[1], a[2], a[3])
        }

        override fun onServiceDisconnected(n: ComponentName?) = leave("host gone")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (own()) {
            counted = true
            live++
            val fresh = try {
                dataSuffix(SvcPage.SUFFIX)
                true
            } catch (e: IllegalStateException) {
                Log.i(Remote.TAG, "svc page data dir already set")
                false
            }
            if (fresh && live == 1) {
                File(applicationInfo.dataDir, "app_webview_${SvcPage.SUFFIX}").deleteRecursively()
                File(cacheDir, "webview_${SvcPage.SUFFIX}").deleteRecursively()
                File(noBackupFilesDir, ".webview_${SvcPage.SUFFIX}").deleteRecursively()
            }
        }
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        token = intent.getStringExtra(Svc.EXTRA_TOKEN)
        bound = token != null && bindService(Intent(this, SvcLoginHost::class.java), conn, BIND_AUTO_CREATE)
        if (!bound) {
            answered = true
            finishAndRemoveTask()
            return
        }
        onBackPressedDispatcher.addCallback(this) { cancel() }
        setContent {
            LoginPage(name, at, { cancel() }, web = if (ready) ({ AndroidView({ c -> view(c) }, Modifier.fillMaxSize()) }) else null)
        }
    }

    private fun begin(authorizeUrl: String, host: String, redirectPrefix: String, title: String) {
        val prefix = PkcePolicy.prefix(redirectPrefix)
        if (prefix == null || !HostRule.link(authorizeUrl, host)) return cancel()
        page = SvcPage(prefix, { caught(it) }, { at = it }, { cancel() }, { Log.i(Remote.TAG, it) })
        url = authorizeUrl
        name = title
        at = host
        ready = true
    }

    private fun view(c: Context): WebView {
        val v = WebView(c)
        web = v
        page?.let {
            it.setup(v)
            it.open(v, url ?: return v)
        }
        return v
    }

    private fun caught(url: String) {
        if (answered) return
        answered = true
        try {
            host?.caught(token, url)
        } catch (_: RemoteException) {
        }
        finishAndRemoveTask()
    }

    private fun cancel() {
        if (!answered) {
            answered = true
            try {
                host?.cancelled(token)
            } catch (_: RemoteException) {
            }
        }
        finishAndRemoveTask()
    }

    private fun leave(why: String) {
        Log.i(Remote.TAG, "svc page closed $why")
        answered = true
        if (!isFinishing) finishAndRemoveTask()
    }

    override fun onDestroy() {
        if (counted) live--
        web?.let { v ->
            if (live == 0) page?.close(v) else v.stopLoading()
            v.destroy()
        }
        web = null
        if (!answered) {
            answered = true
            try {
                host?.closed(token)
            } catch (_: RemoteException) {
            }
        }
        if (bound) unbindService(conn)
        bound = false
        super.onDestroy()
        if (isFinishing && counted && live == 0) kill()
    }

    private fun own() = Application.getProcessName() != packageName

    companion object {
        private var live = 0
        internal var kill: () -> Unit = { Process.killProcess(Process.myPid()) }
        internal var dataSuffix: (String) -> Unit = { WebView.setDataDirectorySuffix(it) }
        internal var watch: (IBinder, () -> Unit) -> Unit = { b, gone -> b.linkToDeath({ gone() }, 0) }
    }
}
