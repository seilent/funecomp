package net.seilent.funecomp

import android.Manifest
import android.app.ActivityManager
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
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
import net.seilent.funecomp.artist.Accounts
import net.seilent.funecomp.artist.AutoEqIndex
import net.seilent.funecomp.artist.Sources
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.Battery
import net.seilent.funecomp.ui.Black
import net.seilent.funecomp.ui.Bonded
import net.seilent.funecomp.ui.Control
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.PickedFile
import net.seilent.funecomp.ui.Shell
import java.util.UUID

class Main : ComponentActivity() {
    private var launch = false
    private var asked = 0
    private var battery by mutableStateOf<Battery?>(null)
    private var batteryAsked by mutableStateOf(false)
    private var granted by mutableStateOf(true)
    private var mediaControls by mutableStateOf(true)
    private var spec by mutableStateOf<String?>(null)
    private var sources by mutableStateOf(Sources())
    internal var shell by mutableStateOf(Shell(Nav(listOf(Page.Connection)), gated = true))
        private set
    private var name by mutableStateOf<String?>(null)
    private val companionUuid = UUID.fromString(Proto.uuid)
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = linkAllowed()
        name = deviceName(spec)
        if (granted && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startLink()
    }

    private var picked: ((String, String?) -> Unit)? = null
    private val eqPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val done = picked ?: return@registerForActivityResult
        picked = null
        if (uri != null) AutoEqs.read(uri, done)
    }

    private var firmwarePicked: ((PickedFile) -> Unit)? = null
    private val firmwarePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val done = firmwarePicked ?: return@registerForActivityResult
        firmwarePicked = null
        if (uri != null) Firmwares.read(uri, done)
    }

    private val control = object : Control by Remote {
        override fun bonded(): List<Bonded> {
            if (!linkAllowed()) return emptyList()
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
            return try {
                adapter.bondedDevices.map { d -> Bonded(d.name ?: d.address, d.address, d.uuids?.any { it.uuid == companionUuid } == true) }.sortedBy { it.name }
            } catch (_: SecurityException) {
                emptyList()
            }
        }

        override fun choose(address: String) {
            store("rfcomm:$address")
            Remote.userAction()
            startLink()
        }

        override fun retry() {
            Remote.userAction()
            startLink()
        }

        override fun openBluetoothSettings() {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }

        override fun disconnect() {
            startService(Intent(this@Main, LinkService::class.java).setAction(LinkService.ACTION_STOP))
        }

        override fun allowBackground() {
            backgroundLater()
            val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            try {
                startActivity(if (battery == Battery.OPTIMIZED) request else details)
            } catch (_: ActivityNotFoundException) {
                startActivity(details)
            }
        }

        override fun backgroundLater() {
            prefs().edit().putBoolean(BATTERY_ASKED, true).apply()
            batteryAsked = true
        }

        override fun scrobble(accounts: Accounts) = Scrobbles.set(accounts)

        override fun lastFmLogin(user: String, password: String) = Scrobbles.lastFmLogin(user, password)

        override fun listenBrainzToken(token: String) = Scrobbles.listenBrainzToken(token)

        override fun eqFile(done: (String, String?) -> Unit) {
            picked = done
            try {
                eqPicker.launch(arrayOf("text/*"))
            } catch (_: ActivityNotFoundException) {
                picked = null
            }
        }

        override fun autoEqFind(query: String, done: (List<AutoEqIndex.Entry>?) -> Unit) = AutoEqs.find(query, done)

        override fun autoEqProfile(entry: AutoEqIndex.Entry, done: (String?) -> Unit) = AutoEqs.profile(entry, done)

        override fun mediaControls(on: Boolean) {
            MediaLink.controls(this@Main, on)
            mediaControls = on
            if (Remote.phase != Phase.NONE) startService(Intent(this@Main, LinkService::class.java).setAction(LinkService.ACTION_MEDIA))
        }

        override fun lyrics(on: Boolean) = Lyrics.set(on)

        override fun firmwareCheck() = Firmwares.check()

        override fun firmwareDownload() = Firmwares.download()

        override fun firmwareCancel() = Firmwares.cancel()

        override fun firmwareFile(done: (PickedFile) -> Unit) {
            firmwarePicked = done
            try {
                firmwarePicker.launch(arrayOf("*/*"))
            } catch (_: ActivityNotFoundException) {
                firmwarePicked = null
            }
        }

        override fun firmwareSettings(checkDaily: Boolean, prereleases: Boolean) = Firmwares.settings(checkDaily, prereleases)

        override fun firmwareSend(picked: Boolean) = Firmwares.send(picked)

        override fun firmwareStopSend() = Firmwares.stopSend()

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
        Scrobbles.init(this)
        Lyrics.init(this)
        AutoEqs.init(this)
        Firmwares.init(this)
        Remote.logExit(this)
        batteryAsked = prefs().getBoolean(BATTERY_ASKED, false)
        mediaControls = MediaLink.controls(this)
        remember(intent)
        launch = opens(intent, Remote.debuggable(this))
        setContent {
            Box(Modifier.fillMaxSize().background(Black).windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
                val b = battery
                val link = LinkView(Remote.phase, name, Remote.bye, granted, Remote.everLive, Remote.board, b, b != null && b != Battery.UNRESTRICTED && !batteryAsked, mediaControls, Lyrics.on)
                App(Remote.device, link, control, shell, sources, { saveSources(it) }, Remote.backdrop, Remote.credits, { e ->
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(e.credit?.page ?: return@App)))
                    } catch (_: ActivityNotFoundException) {
                    }
                }, Scrobbles.view, Firmwares.view)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val paged = remember(intent)
        val open = opens(intent, Remote.debuggable(this))
        Log.i(Remote.TAG, "onNewIntent action=${intent.action} open=$open")
        if (!open) return
        if (!shell.gated && !paged) {
            shell.sheet.close(dismissed = false)
            shell.nav.reset(listOf(Page.Home, Page.NowPlaying))
        }
        launch = true
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) launched()
    }

    override fun onStart() {
        super.onStart()
        granted = linkAllowed()
        Remote.shown(true)
        launched()
    }

    override fun onResume() {
        super.onResume()
        battery = readBattery()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) Remote.shown(false)
    }

    private fun launched() {
        if (!launch) return
        launch = false
        startLink()
    }

    private fun readBattery(): Battery = when {
        getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true -> Battery.RESTRICTED
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true -> Battery.UNRESTRICTED
        else -> Battery.OPTIMIZED
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val detents = detents(keyCode) ?: return super.onKeyDown(keyCode, event)
        if (shell.overlay.visible || event.repeatCount > 0) Remote.volume(detents)
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

    private fun remember(intent: Intent?): Boolean {
        spec = prefs().getString(EXTRA_LINK, null)
        name = deviceName(spec)
        if (!Remote.debuggable(this)) return false
        intent?.getStringExtra(EXTRA_LINK)?.let { store(it) }
        val pages = intent?.getStringExtra(EXTRA_PAGE)?.let { debugPage(it) } ?: return false
        shell = Shell(Nav(pages))
        return true
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
            "scrobbling" -> base + Page.Connection + Page.Scrobbling
            "firmware" -> base + Page.Connection + Page.Firmware
            "lyrics" -> base + Page.Connection + Page.Lyrics
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
        add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    private fun linkAllowed() = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

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
        startForegroundService(Intent(this, LinkService::class.java).putExtra(EXTRA_LINK, spec))
    }

    companion object {
        const val EXTRA_LINK = "link"
        const val EXTRA_PAGE = "page"
        const val ACTION_OPEN = "net.seilent.funecomp.OPEN"
        private const val BATTERY_ASKED = "batteryAsked"
        const val PREFS = "link"
        const val ART_PREFS = "art"

        fun opens(intent: Intent?, debuggable: Boolean): Boolean {
            if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return false
            return intent.action == Intent.ACTION_MAIN || intent.action == ACTION_OPEN || (debuggable && intent.hasExtra(EXTRA_LINK))
        }

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
