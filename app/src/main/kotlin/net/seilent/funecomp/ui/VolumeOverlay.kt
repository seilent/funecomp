package net.seilent.funecomp.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.state.DeviceState
import kotlin.math.roundToInt

private const val CONTROL_MAX = 255f

class Overlay {
    var shows by mutableIntStateOf(0)
        private set

    var hides by mutableIntStateOf(0)
        private set

    var until by mutableLongStateOf(0L)
        private set

    val visible get() = SystemClock.uptimeMillis() < until

    fun show() {
        until = SystemClock.uptimeMillis() + Tok.motion_volumeHide.toLong() + Tok.motion_volumeFade.toLong()
        shows++
    }

    fun hide() {
        until = SystemClock.uptimeMillis()
        hides++
    }
}

@Composable
fun VolumeOverlay(overlay: Overlay, device: DeviceState, actions: Control) {
    val ui = LocalUi.current
    val d = LocalDensity.current
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(overlay.shows, overlay.hides) {
        if (overlay.shows == 0) return@LaunchedEffect
        if (!overlay.visible) {
            alpha.animateTo(0f, tween(Tok.motion_volumeFade.toInt(), easing = Decel))
            return@LaunchedEffect
        }
        launch { alpha.animateTo(1f, tween(Tok.motion_volumeShow.toInt(), easing = LinearEasing)) }
        delay(Tok.motion_volumeHide.toLong())
        alpha.animateTo(0f, tween(Tok.motion_volumeFade.toInt(), easing = Decel))
    }
    if (alpha.value <= 0f) return

    val s = device.playback.state
    val edge = ui.px(Tok.edge)
    val cx = ui.px(ui.w) / 2
    val ringY = ui.px(ui.overlayRingY)
    val minusY = ui.px(ui.overlayMinusY)
    val off = ui.px(Tok.overlayGlyphOffset)
    val skip = ui.px(Tok.vol_skipX)
    val lit = white(Tok.overlayLit)
    val textW = ui.px(ui.w) - 2 * edge
    val number = s.volumePct ?: ((s.volume ?: 0) * 100f / CONTROL_MAX).roundToInt()
    val hit = with(d) { ui.px(Tok.overlayRing).toDp() }

    Box(Modifier.fillMaxSize().alpha(alpha.value).testTag("overlay")) {
        Box(Modifier.fillMaxSize().bleed(LocalBleed.current).background(Black.copy(alpha = Tok.scrim_overlay_alpha)))
        Glyph(GlyphName.PLUS, Tok.vol_plus, lit, Modifier.at(cx, ui.px(ui.volPlusY), 0.5f, 0.5f).testTag("overlay.plus.glyph"))
        RoleText(
            number.toString(),
            Roles.overlayNumber,
            Modifier.atBaseline(edge, ui.px(ui.overlayNumberBaseline)).testTag("overlay.number"),
            alpha = Tok.overlayLit,
        )
        Glyph(GlyphName.SKIP_PREVIOUS, Held.Vol.vol_skip, white(Tok.alpha_full), Modifier.at(cx - skip, ringY, 0.5f, 0.5f).testTag("overlay.prev.glyph"))
        Ring(Tok.vol_ring, if (s.playing == true) GlyphName.PAUSE_WIDE else GlyphName.PLAY, Look.Touch, Modifier.at(cx, ringY, 0.5f, 0.5f), tag = "overlay.ring", stroke = Tok.vol_ringStroke, glyphSize = Tok.vol_pause)
        Glyph(GlyphName.SKIP_NEXT, Held.Vol.vol_skip, white(Tok.alpha_full), Modifier.at(cx + skip, ringY, 0.5f, 0.5f).testTag("overlay.next.glyph"))
        Box(Modifier.fillMaxSize().alpha(Tok.overlayDim)) {
            RoleText("exit", Roles.volExit, Modifier.at(ui.px(ui.volExitX), ui.px(ui.volExitY)).testTag("overlay.exit"), alpha = Tok.alpha_full)
            RoleText("volume", Roles.volLabel, Modifier.at(cx, minusY + ui.px(Tok.overlayGlyph) / 2, ax = 0.5f).testTag("overlay.volume"), alpha = Tok.alpha_full)
            Lines(s.artist ?: "", s.title ?: "", textW)
        }
        Glyph(GlyphName.MINUS, Tok.vol_plus, lit, Modifier.at(cx, minusY, 0.5f, 0.5f).testTag("overlay.minus.glyph"))

        val touch = remember { MutableInteractionSource() }
        fun target(x: Float, y: Float, tag: String, act: () -> Unit) = Modifier.at(x, y, 0.5f, 0.5f).size(hit).testTag(tag)
            .clickable(touch, null) {
                act()
                overlay.show()
            }
        Box(target(cx, ui.px(Tok.overlayPlusY), "overlay.plus") { actions.volume(1) })
        Box(target(cx, minusY, "overlay.minus") { actions.volume(-1) })
        Box(target(cx - off, ringY, "overlay.prev") { actions.transport(Enums.TransportCmd.prev) })
        Box(target(cx, ringY, "overlay.ring") { actions.transport(Enums.TransportCmd.toggle) })
        Box(target(cx + off, ringY, "overlay.next") { actions.transport(Enums.TransportCmd.next) })
    }
}

@Composable
private fun Lines(artist: String, title: String, width: Float) {
    val ui = LocalUi.current
    Layout(
        content = {
            RoleText(artist, Roles.toastArtist, Modifier.testTag("overlay.artist"), alpha = Tok.type_toastArtist_alpha)
            RoleText(title, Roles.toastTitle, Modifier.testTag("overlay.title"), alpha = Tok.type_toastTitle_alpha)
        },
        modifier = Modifier.fillMaxSize(),
    ) { m, c ->
        val limit = Constraints(maxWidth = width.roundToInt().coerceAtLeast(0))
        val a = m[0].measure(limit)
        val t = m[1].measure(limit)
        layout(c.maxWidth, c.maxHeight) {
            val edge = ui.px(Tok.edge).roundToInt()
            val titleY = c.maxHeight - ui.px(Tok.overlayBottomMargin).roundToInt() - t.height
            val titleBaseline = titleY + t[FirstBaseline]
            t.place(edge, titleY)
            a.place(edge, titleBaseline - ui.px(Tok.overlayTextPitch).roundToInt() - a[FirstBaseline])
        }
    }
}
