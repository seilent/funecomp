package net.seilent.funecomp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import net.seilent.funecomp.ui.Black
import net.seilent.funecomp.ui.LocalUi
import net.seilent.funecomp.ui.NowPlaying
import net.seilent.funecomp.ui.Overlay
import net.seilent.funecomp.ui.OverlayActions
import net.seilent.funecomp.ui.Ui
import net.seilent.funecomp.ui.VolumeOverlay

class Main : ComponentActivity() {
    private val overlay = Overlay()
    private var started = false
    private var asked = false
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (linkAllowed() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startLink()
    }

    private val actions = object : OverlayActions {
        override fun volume(detents: Int) = Remote.volumeStep(detents)
        override fun transport(cmd: Int) = Remote.transport(cmd)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        remember(intent)
        setContent {
            BoxWithConstraints(
                Modifier.fillMaxSize().background(Black).windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                val ui = Ui.of(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
                CompositionLocalProvider(LocalUi provides ui) {
                    val device = Remote.device
                    NowPlaying(device)
                    VolumeOverlay(overlay, device, actions)
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
        Remote.volumeStep(detents)
        overlay.show()
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

    private fun remember(intent: Intent?) {
        val spec = intent?.getStringExtra(EXTRA_LINK) ?: return
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(EXTRA_LINK, spec).apply()
    }

    private fun missing() = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    private fun linkAllowed() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun startLink() {
        val spec = getSharedPreferences(PREFS, MODE_PRIVATE).getString(EXTRA_LINK, null) ?: return
        if (!linkAllowed()) {
            val need = missing()
            if (!asked && need.isNotEmpty()) {
                asked = true
                permissions.launch(need.toTypedArray())
            }
            return
        }
        started = true
        startForegroundService(Intent(this, LinkService::class.java).putExtra(EXTRA_LINK, spec))
    }

    companion object {
        const val EXTRA_LINK = "link"
        const val PREFS = "link"
    }
}
