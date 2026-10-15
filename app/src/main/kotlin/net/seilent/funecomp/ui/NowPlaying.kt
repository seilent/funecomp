package net.seilent.funecomp.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.state.DeviceState

private const val NP_ART_MAX = 168f
private const val NP_ART_STACK = 311f
private const val NP_TEXT_TOP = 117f
private const val NP_ALBUM_BASELINE = 141f
private const val NP_PROGRESS_FROM_BOTTOM = 156f
private const val NP_TITLE_FROM_BOTTOM = 118f

fun formatTime(ms: Long): String {
    val total = maxOf(0L, ms / 1000)
    val s = total % 60
    return "${total / 60}:${if (s < 10) "0" else ""}$s"
}

@Composable
fun NowPlaying(device: DeviceState) {
    val ui = LocalUi.current
    val d = LocalDensity.current
    val s = device.playback.state
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val playing = s.playing == true
    LaunchedEffect(playing, device.playback.receivedAt) {
        now = SystemClock.elapsedRealtime()
        if (!playing) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = SystemClock.elapsedRealtime()
                delay(Tok.motion_progress.toLong())
            }
        }
    }
    val textX = ui.px(Tok.edge)
    val textW = ui.px(ui.w - 2 * Tok.edge)
    val artSide = minOf(NP_ART_MAX, ui.w - 2 * Tok.edge, ui.h - NP_ART_STACK)
    val progressW = ui.px(artSide)
    val progressY = ui.px(ui.h - NP_PROGRESS_FROM_BOTTOM)
    val timeBaseline = progressY + ui.px(Tok.npTimeDrop)
    val duration = s.durationMs ?: 0L
    val position = device.positionMs(now)
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val accent = Color(Tok.accent_default)

    Box(Modifier.fillMaxSize()) {
        Status(device)
        RoleText(s.artist ?: "", Roles.npArtist, Modifier.atBaseline(textX, ui.px(NP_TEXT_TOP), textW).testTag("np.artist"))
        RoleText(s.album ?: "", Roles.npAlbum, Modifier.atBaseline(textX, ui.px(NP_ALBUM_BASELINE), textW).testTag("np.album"))
        if (s.track != null) {
            val thick = with(d) { ui.px(Tok.npProgressThick).toDp() }
            Box(Modifier.at(textX, progressY).size(with(d) { progressW.toDp() }, thick).background(white(Tok.alpha_low)))
            Box(Modifier.at(textX, progressY).size(with(d) { (progressW * fraction).toDp() }, thick).background(accent).testTag("np.progress"))
            RoleText(formatTime(position), Roles.time, Modifier.atBaseline(textX, timeBaseline).testTag("np.elapsed"))
            val remaining = if (duration > 0) "-" + formatTime(duration - position) else ""
            RoleText(remaining, Roles.time, Modifier.atBaseline(textX + progressW, timeBaseline, ax = 1f).testTag("np.remaining"))
        }
        RoleText(s.title ?: "", Roles.npTitle, Modifier.atBaseline(textX, ui.px(ui.h - NP_TITLE_FROM_BOTTOM), textW).testTag("np.title"))
    }
}

@Composable
private fun Status(device: DeviceState) {
    val ui = LocalUi.current
    val s = device.playback.state
    val top = ui.px(Tok.statusTop)
    var right = ui.px(ui.w - Tok.edge)
    val step = ui.px(Tok.statusHeight + Tok.statusGap)
    val battery = s.battery
    if (battery != null) {
        val low = battery < Tok.batteryLowPercent
        Glyph(
            GlyphName.BATTERY,
            Tok.statusHeight,
            if (low) Color(Tok.accent_default) else white(Tok.alpha_full),
            Modifier.at(right, top, ax = 1f),
            level = battery / 100f,
        )
        right -= step
    }
    val playing = s.playing == true
    val paused = !playing && (s.slots ?: 0L) > 0L
    if (playing || paused) {
        Glyph(
            if (playing) GlyphName.PLAY else GlyphName.PAUSE,
            Tok.statusHeight,
            white(Tok.alpha_full),
            Modifier.at(right, top, ax = 1f).testTag(if (playing) "status.play" else "status.pause"),
        )
    }
}
