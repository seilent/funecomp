package net.seilent.funecomp

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import net.seilent.funecomp.artist.Sources
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.Black
import net.seilent.funecomp.ui.Bonded
import net.seilent.funecomp.ui.Control
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Scale
import net.seilent.funecomp.ui.Shell

class Main : ComponentActivity() {
    private var started = false
    private var asked = 0
    private var granted by mutableStateOf(true)
    private var spec by mutableStateOf<String?>(null)
    private var sources by mutableStateOf(Sources())
    private var scale by mutableStateOf(Scale.MICRODAP)
    private var shell by mutableStateOf(Shell())
    private var name by mutableStateOf<String?>(null)
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = linkAllowed()
        name = deviceName(spec)
        if (granted && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startLink()
    }

    private val control = object : Control by Remote {
        override fun bonded(): List<Bonded> {
            if (!linkAllowed()) return emptyList()
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
            return try {
                adapter.bondedDevices.map { Bonded(it.name ?: it.address, it.address) }.sortedBy { it.name }
            } catch (_: SecurityException) {
                emptyList()
            }
        }

        override fun choose(address: String) {
            store("rfcomm:$address")
            Remote.userAction()
            startLink()
        }

        override fun askPermission() {
            if (asked >= 2) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                return
            }
            asked++
            permissions.launch(missing().toTypedArray())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        sources = sources(this)
        Remote.artist(this)
        remember(intent)
        setContent {
            Box(Modifier.fillMaxSize().background(Black).windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
                val link = LinkView(Remote.phase, name, Remote.bye, granted, Remote.everLive, Remote.board)
                App(Remote.device, link, control, shell, sources, { saveSources(it) }, scale, Remote.backdrop, Remote.credits) { e ->
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(e.credit?.page ?: return@App)))
                    } catch (_: ActivityNotFoundException) {
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        remember(intent)
        startLink()
    }

    override fun onStart() {
        super.onStart()
        granted = linkAllowed()
        startLink()
    }

    override fun onStop() {
        super.onStop()
        if (started && !isChangingConfigurations) {
            started = false
            startService(Intent(this, LinkService::class.java).setAction(LinkService.ACTION_STOP))
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val detents = detents(keyCode) ?: return super.onKeyDown(keyCode, event)
        Remote.volume(detents)
        shell.overlay.show()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (detents(keyCode) != null) true else super.onKeyUp(keyCode, event)

    private fun detents(keyCode: Int): Int? {
        if (Remote.device.hello == null) return null
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> 1
            KeyEvent.KEYCODE_VOLUME_DOWN -> -1
            else -> null
        }
    }

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private fun store(value: String) {
        prefs().edit().putString(EXTRA_LINK, value).apply()
        spec = value
        name = deviceName(value)
    }

    private fun remember(intent: Intent?) {
        spec = prefs().getString(EXTRA_LINK, null)
        name = deviceName(spec)
        if (!Remote.debuggable(this)) return
        intent?.getStringExtra(EXTRA_LINK)?.let { store(it) }
        intent?.getStringExtra(EXTRA_SCALE)?.let { s -> Scale.entries.firstOrNull { it.name.equals(s, ignoreCase = true) }?.let { scale = it } }
        intent?.getStringExtra(EXTRA_PAGE)?.let { p -> debugPage(p)?.let { shell = Shell(Nav(it)) } }
    }

    private fun debugPage(name: String): List<Page>? {
        val base = listOf(Page.Home)
        return when (name) {
            "home" -> base
            "nowplaying" -> base + Page.NowPlaying
            "queue" -> base + Page.NowPlaying + Page.Queue
            "music" -> base + Page.Music
            "playlists" -> base + Page.Playlists
            "settings" -> base + Page.Settings
            "eq" -> base + Page.Settings + Page.Eq(0)
            "connection" -> base + Page.Connection
            "art" -> base + Page.ArtistArt
            else -> when {
                name.startsWith("artist:") -> base + Page.Music + Page.Artist(name.removePrefix("artist:"))
                name.startsWith("album:") -> {
                    val p = name.removePrefix("album:").split('|')
                    base + Page.Music + Page.Album(p[0], p.getOrElse(1) { "" }, p.getOrElse(2) { "" })
                }
                else -> null
            }
        }
    }

    private fun deviceName(spec: String?): String? {
        val s = spec ?: return null
        if (!s.startsWith("rfcomm:")) return s
        val addr = s.removePrefix("rfcomm:")
        return control.bonded().firstOrNull { it.address.equals(addr, ignoreCase = true) }?.name ?: addr
    }

    private fun saveSources(s: Sources) {
        sources = s
        getSharedPreferences(ART_PREFS, MODE_PRIVATE).edit()
            .putBoolean("theaudiodb", s.theAudioDb)
            .putBoolean("discogs", s.discogs)
            .putBoolean("fanarttv", s.fanartTv)
            .putString("fanarttvKey", s.fanartTvKey)
            .putBoolean("deezer", s.deezer)
            .putBoolean("youtube", s.youtube)
            .apply()
        Remote.sourcesChanged()
    }

    private fun missing() = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    private fun linkAllowed() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun startLink() {
        val spec = prefs().getString(EXTRA_LINK, null) ?: return
        if (!linkAllowed()) {
            val need = missing()
            if (asked == 0 && need.isNotEmpty()) {
                asked = 1
                permissions.launch(need.toTypedArray())
            }
            return
        }
        started = true
        startForegroundService(Intent(this, LinkService::class.java).putExtra(EXTRA_LINK, spec))
    }

    companion object {
        const val EXTRA_LINK = "link"
        const val EXTRA_PAGE = "page"
        const val EXTRA_SCALE = "scale"
        const val PREFS = "link"
        const val ART_PREFS = "art"

        fun sources(context: Context): Sources {
            val p = context.getSharedPreferences(ART_PREFS, MODE_PRIVATE)
            return Sources(
                p.getBoolean("theaudiodb", false),
                p.getBoolean("discogs", false),
                p.getBoolean("fanarttv", false),
                p.getString("fanarttvKey", "") ?: "",
                p.getBoolean("deezer", false),
                p.getBoolean("youtube", false),
            )
        }
    }
}
