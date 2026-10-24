package net.seilent.funecomp

import android.Manifest
import android.app.ActivityManager
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import net.seilent.funecomp.art.GalleryRead
import net.seilent.funecomp.artist.Accounts
import net.seilent.funecomp.artist.AppPair
import net.seilent.funecomp.artist.AutoEqIndex
import net.seilent.funecomp.artist.AutoEqList
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
import net.seilent.funecomp.ui.PickFor
import net.seilent.funecomp.ui.PickedFile
import net.seilent.funecomp.ui.Previewed
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.debugPages
import java.util.UUID

class Main : ComponentActivity() {
    private var launch = false
    private var asked = 0
    private var battery by mutableStateOf<Battery?>(null)
    private var batteryAsked by mutableStateOf(false)
    private var reconnect by mutableStateOf(false)
    private var reconnectAsk by mutableStateOf(false)
    private var granted by mutableStateOf(true)
    private var mediaControls by mutableStateOf(true)
    private var lyricsMode by mutableStateOf(false)
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

    private var galleryPicked: ((Previewed) -> Unit)? = null
    private val galleryPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val done = galleryPicked ?: return@registerForActivityResult
        galleryPicked = null
        if (uri == null) done(Previewed.Closed) else Remote.galleryPicked({ GalleryRead.read(contentResolver, uri) }, done)
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

        override fun lastFmSignIn() = Scrobbles.lastFmStart()

        override fun signOut(service: String) = Scrobbles.signOut(service)

        override fun openListenBrainzToken() = Scrobbles.listenBrainzOpen()

        override fun listenBrainzToken(token: String) = Scrobbles.listenBrainzToken(token)

        override fun eqFile(done: (String, String?) -> Unit) {
            picked = done
            try {
                eqPicker.launch(arrayOf("text/*"))
            } catch (_: ActivityNotFoundException) {
                picked = null
            }
        }

        override fun pickGallery(done: (Previewed) -> Unit): () -> Unit {
            galleryPicked = done
            try {
                galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            } catch (_: ActivityNotFoundException) {
                galleryPicked = null
                done(Previewed.Closed)
            }
            return {
                galleryPicked = null
                Remote.cancelPick()
            }
        }

        override fun autoEqList(done: (AutoEqList?) -> Unit) = AutoEqs.list(done)

        override fun autoEqProfile(entry: AutoEqIndex.Entry, done: (String?) -> Unit) = AutoEqs.profile(entry, done)

        override fun mediaControls(on: Boolean) {
            MediaLink.controls(this@Main, on)
            mediaControls = on
            if (Remote.phase != Phase.NONE) startService(Intent(this@Main, LinkService::class.java).setAction(LinkService.ACTION_MEDIA))
        }

        override fun lyricsMode() = lyricsMode

        override fun lyricsMode(on: Boolean) {
            LyricsMode.on(this@Main, on)
            lyricsMode = on
        }

        override fun lyricSource(id: String, on: Boolean) = Lyrics.set(id, on)

        override fun lyricMove(id: String, by: Int) = Lyrics.move(id, by)

        override fun reconnect(on: Boolean) {
            prefs().edit().putBoolean(RECONNECT, on).remove(RECONNECT_ASK).apply()
            reconnect = on
            reconnectAsk = false
        }

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
        init(this)
        windowManager.maximumWindowMetrics.bounds.let { Remote.window(this, it.width(), it.height()) }
        batteryAsked = prefs().getBoolean(BATTERY_ASKED, false)
        mediaControls = MediaLink.controls(this)
        lyricsMode = LyricsMode.on(this)
        remember(intent)
        launch = opens(intent, Remote.debuggable(this), Remote.phase != Phase.NONE)
        if (launch) pickFrom(intent)
        setContent {
            Box(Modifier.fillMaxSize().background(Black)) {
                val b = battery
                val link = LinkView(Remote.phase, name, Remote.bye, granted, Remote.everLive, Remote.board, b, askBattery(), mediaControls, Lyrics.sources, reconnect, reconnectAsk)
                App(Remote.device, link, control, shell, sources, { saveSources(it) }, Remote.backdrop, Remote.credits, { e ->
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(e.credit?.page ?: return@App)))
                    } catch (_: ActivityNotFoundException) {
                    }
                }, Scrobbles.view, Firmwares.view, Remote.blocked, Remote.dapLyrics.show, Remote.picks, galleryMissing = Remote.galleryMissing, liveDevice = { Remote.device })
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        windowManager.maximumWindowMetrics.bounds.let { Remote.window(this, it.width(), it.height()) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val paged = remember(intent)
        val open = opens(intent, Remote.debuggable(this), Remote.phase != Phase.NONE)
        Log.i(Remote.TAG, "onNewIntent action=${intent.action} open=$open")
        if (!open) return
        if (!shell.gated && !paged) {
            Remote.cancelPick()
            shell.sheet.close(dismissed = false)
            shell.nav.reset(listOf(Page.Home, Page.NowPlaying))
        }
        pickFrom(intent)
        launch = true
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) launched()
    }

    override fun onStart() {
        super.onStart()
        granted = linkAllowed()
        reconnect = prefs().getBoolean(RECONNECT, false)
        reconnectAsk = prefs().getBoolean(RECONNECT_ASK, false)
        Remote.shown(true)
        Remote.pickAsk = { target ->
            (!shell.gated && !shell.sheet.shown && shell.pick == null && !askBattery() && !reconnectAsk).also { if (it) shell.pick = target }
        }
        Scrobbles.opener = { browse(it) }
        launched()
    }

    override fun onResume() {
        super.onResume()
        battery = readBattery()
        Scrobbles.resumed()
    }

    override fun onStop() {
        super.onStop()
        Remote.pickAsk = null
        Scrobbles.opener = null
        if (!isChangingConfigurations) Remote.shown(false)
    }

    private fun launched() {
        if (!launch) return
        launch = false
        startLink()
    }

    private fun browse(url: String): Boolean = try {
        CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(url))
        true
    } catch (_: ActivityNotFoundException) {
        false
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
        val pages = intent?.getStringExtra(EXTRA_PAGE)?.let { debugPages(it) } ?: return false
        Remote.cancelPick()
        shell = Shell(Nav(pages))
        return true
    }

    private fun askBattery() = battery.let { it != null && it != Battery.UNRESTRICTED } && !batteryAsked

    private fun pickFrom(intent: Intent?) {
        if (intent?.action != ACTION_PICK_ART || !PickNotice.take(this, intent.getStringExtra(EXTRA_PICK_NONCE))) return
        val name = intent.getStringExtra(EXTRA_PICK_NAME)?.takeIf { it.isNotBlank() } ?: return
        shell.pick = PickFor(name, intent.getStringExtra(EXTRA_PICK_MBID), origin = intent.getIntExtra(EXTRA_PICK_ORIGIN, -1).takeIf { it >= 0 })
    }

    private fun deviceName(spec: String?): String? {
        val s = spec ?: return null
        if (!s.startsWith("rfcomm:")) return s
        val addr = s.removePrefix("rfcomm:")
        return control.bonded().firstOrNull { it.address.equals(addr, ignoreCase = true) }?.name ?: addr
    }

    private fun saveSources(s: Sources) {
        val reordered = s.order != sources.order
        writeSources(this, sources, s)
        sources = s
        if (reordered) Remote.reordered() else Remote.sourcesChanged()
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
        const val ACTION_PICK_ART = "net.seilent.funecomp.PICK_ART"
        const val EXTRA_PICK_NAME = "pickName"
        const val EXTRA_PICK_MBID = "pickMbid"
        const val EXTRA_PICK_NONCE = "pickNonce"
        const val EXTRA_PICK_ORIGIN = "pickOrigin"
        private const val BATTERY_ASKED = "batteryAsked"
        const val PREFS = "link"
        const val WANTED = "wanted"
        const val RECONNECT = "reconnect"
        const val RECONNECT_ASK = "reconnectAsk"
        const val ART_PREFS = "art"
        private const val FANART_KEY = "fanarttvKey"
        private const val ORDER = "order"
        private val upgrade = Any()

        fun init(c: Context) {
            Remote.artist(c)
            Scrobbles.init(c, AppPair(BuildConfig.LASTFM_KEY, BuildConfig.LASTFM_SECRET))
            Lyrics.init(c)
            AutoEqs.init(c)
            Firmwares.init(c)
            Remote.logExit(c)
        }

        fun opens(intent: Intent?, debuggable: Boolean, linked: Boolean = false): Boolean {
            if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return false
            return intent.action == Intent.ACTION_MAIN || intent.action == ACTION_OPEN || (linked && intent.action == ACTION_PICK_ART) || (debuggable && intent.hasExtra(EXTRA_LINK))
        }

        internal fun writeSources(context: Context, was: Sources, s: Sources) {
            val e = context.getSharedPreferences(ART_PREFS, MODE_PRIVATE).edit()
            if (s.theAudioDb != was.theAudioDb) e.putBoolean(Sources.THE_AUDIO_DB, s.theAudioDb)
            if (s.discogs != was.discogs) e.putBoolean(Sources.DISCOGS, s.discogs)
            if (s.fanartTv != was.fanartTv) e.putBoolean(Sources.FANART_TV, s.fanartTv)
            if (s.fanartTvKey != was.fanartTvKey) e.putString(FANART_KEY, s.fanartTvKey)
            if (s.deezer != was.deezer) e.putBoolean(Sources.DEEZER, s.deezer)
            if (s.youtube != was.youtube) e.putBoolean(Sources.YOUTUBE, s.youtube)
            if (s.order != was.order) e.putString(ORDER, s.order.joinToString(","))
            e.apply()
        }

        fun sources(context: Context): Sources {
            val p = context.getSharedPreferences(ART_PREFS, MODE_PRIVATE)
            if (p.getInt(Sources.DEFAULTS, 0) < Sources.DEFAULTS_REV) synchronized(upgrade) {
                val edit = Sources.upgrade(p.all)
                if (edit.write) p.edit().apply { edit.remove.forEach { remove(it) } }.putInt(Sources.DEFAULTS, Sources.DEFAULTS_REV).commit()
            }
            return Sources(
                p.getBoolean(Sources.THE_AUDIO_DB, true),
                p.getBoolean(Sources.DISCOGS, true),
                p.getBoolean(Sources.FANART_TV, true),
                p.getString(FANART_KEY, "") ?: "",
                p.getBoolean(Sources.DEEZER, true),
                p.getBoolean(Sources.YOUTUBE, true),
                Sources.order(p.getString(ORDER, null)),
            )
        }
    }
}
