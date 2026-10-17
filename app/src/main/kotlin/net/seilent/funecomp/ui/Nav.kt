package net.seilent.funecomp.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.seilent.funecomp.proto.gen.Tok

private const val SKIA_CAMERA_UNIT = 72f

sealed interface Page {
    data object NowPlaying : Page
    data object Home : Page
    data object Music : Page
    data object Queue : Page
    data object Playlists : Page
    data object Settings : Page
    data object Connection : Page
    data object ArtistArt : Page
    data object Scrobbling : Page
    data object Firmware : Page
    data class Artist(val name: String) : Page
    data class Album(val key: String, val title: String, val artist: String) : Page
    data class Eq(val output: Int) : Page
}

@Stable
class Nav(start: List<Page> = listOf(Page.Home, Page.NowPlaying)) {
    val stack = mutableStateListOf<Page>().apply { addAll(start) }
    var forward by mutableStateOf(true)
        private set

    val top: Page get() = stack.last()

    fun push(p: Page) {
        if (top == p) return
        forward = true
        stack += p
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        forward = false
        stack.removeAt(stack.size - 1)
        return true
    }

    fun reset(pages: List<Page>) {
        forward = true
        stack.clear()
        stack.addAll(pages)
    }

    fun popTo(p: Page) {
        val i = stack.lastIndexOf(p)
        if (i < 0) {
            push(p)
            return
        }
        if (i == stack.size - 1) return
        forward = false
        while (stack.size > i + 1) stack.removeAt(stack.size - 1)
    }
}

@Composable
fun Stack(nav: Nav, content: @Composable (Page) -> Unit) {
    val ui = LocalUi.current
    val top = nav.top
    var shown by remember { mutableStateOf(top) }
    var shownKey by remember { mutableStateOf("${nav.stack.lastIndex}/$top") }
    val angle = remember { Animatable(0f) }
    val alpha = remember { Animatable(1f) }
    val holder = rememberSaveableStateHolder()
    val kept = remember { HashSet<String>() }
    val keys = nav.stack.mapIndexed { i, p -> "$i/$p" }.toSet()
    SideEffect {
        val drop = kept - keys - shownKey
        drop.forEach { holder.removeState(it) }
        kept.removeAll(drop)
        kept.addAll(keys)
    }
    LaunchedEffect(top) {
        if (top == shown) {
            angle.snapTo(0f)
            alpha.snapTo(1f)
            return@LaunchedEffect
        }
        val push = nav.forward
        val fade = launch {
            delay(Tok.motion_turnstileOutHold.toLong())
            alpha.snapTo(0f)
        }
        angle.animateTo(if (push) Tok.motion_turnstileOutAngle else Tok.motion_turnstileInAngle, tween(Tok.motion_turnstileOut.toInt(), easing = ExpIn6))
        fade.join()
        shown = top
        shownKey = "${nav.stack.lastIndex}/$top"
        angle.snapTo(if (push) Tok.motion_turnstileInAngle else Tok.motion_turnstileOutAngle)
        launch { alpha.animateTo(1f, tween(Tok.motion_turnstileInFade.toInt(), easing = LinearEasing)) }
        angle.animateTo(0f, tween(Tok.motion_turnstileIn.toInt(), easing = ExpOut6))
    }
    val camera = Tok.motion_perspectiveRatio * ui.px(ui.w) / SKIA_CAMERA_UNIT
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            rotationY = angle.value
            this.alpha = alpha.value
            transformOrigin = TransformOrigin(0f, 0.5f)
            cameraDistance = camera
        },
    ) {
        key(shownKey) { holder.SaveableStateProvider(shownKey) { content(shown) } }
    }
}
