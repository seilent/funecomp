package net.seilent.funecomp.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.state.EqSlots
import net.seilent.funecomp.state.EqStage
import kotlin.math.roundToLong

private const val SKIA_CAMERA_UNIT = 72f
const val ONLINE_ART = 0
const val ONLINE_LYRICS = 1
const val ONLINE_SCROBBLING = 2
const val ONLINE_STREAMING = 3

sealed interface Page {
    data object NowPlaying : Page
    data object Home : Page
    data object Music : Page
    data object Queue : Page
    data object Playlists : Page
    data object Settings : Page
    data object Connection : Page
    data object ArtPicked : Page
    data object ArtBlocked : Page
    data object ArtCredits : Page
    data object Firmware : Page
    data object Apps : Page
    data class Online(val pivot: Int = ONLINE_ART) : Page
    data class AutoEq(val slot: String) : Page
    data class Artist(val name: String, val songs: Long? = null) : Page
    data class Album(val key: String, val title: String, val artist: String) : Page
    data class Eq(val output: Int, val stage: EqStage = EqStage.USER) : Page
    data class Browse(val target: PickFor) : Page
    data class PickPreview(val target: PickFor, val previews: List<Preview>, val fromBrowse: Boolean = false) : Page
}

val LocalEntry = staticCompositionLocalOf { "" }

class HomeReturn(val outEnd: Long, val frame: Long, val scale: Float)

val LocalHomeReturn = staticCompositionLocalOf<HomeReturn?> { null }

val LocalTurnProbe = staticCompositionLocalOf<((String, Float) -> Unit)?> { null }

@Stable
class Nav(start: List<Page> = listOf(Page.Home, Page.NowPlaying)) {
    val stack = mutableStateListOf<Page>().apply { addAll(start) }
    var forward by mutableStateOf(true)
        private set
    var turning by mutableStateOf(false)
        internal set

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

    fun replaceRoot(p: Page) {
        stack[0] = p
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

fun debugPages(name: String): List<Page>? {
    val base = listOf(Page.Home)
    return when (name) {
        "home" -> base
        "nowplaying" -> base + Page.NowPlaying
        "queue" -> base + Page.NowPlaying + Page.Queue
        "music" -> base + Page.Music
        "playlists" -> base + Page.Playlists
        "settings" -> base + Page.Settings
        "eq" -> base + Page.Settings + Page.Eq(0)
        "autoeq" -> base + Page.Settings + Page.Eq(0) + Page.AutoEq(EqSlots.HEADPHONES)
        "connection" -> base + Page.Connection
        "art" -> base + Page.Online()
        "artpicked" -> base + Page.Online() + Page.ArtPicked
        "artblocked" -> base + Page.Online() + Page.ArtBlocked
        "artcredits" -> base + Page.Online() + Page.ArtCredits
        "scrobbling" -> base + Page.Online(ONLINE_SCROBBLING)
        "firmware" -> base + Page.Settings + Page.Firmware
        "lyrics" -> base + Page.Online(ONLINE_LYRICS)
        "online" -> base + Page.Online()
        "streaming" -> base + Page.Online(ONLINE_STREAMING)
        else -> when {
            name.startsWith("artist:") -> base + Page.Music + Page.Artist(name.removePrefix("artist:"))
            name.startsWith("browse:") -> base + Page.Online() + Page.Browse(PickFor(name.removePrefix("browse:")))
            name.startsWith("album:") -> {
                val p = name.removePrefix("album:").split('|')
                base + Page.Music + Page.Album(p[0], p.getOrElse(1) { "" }, p.getOrElse(2) { "" })
            }
            else -> null
        }
    }
}

@Composable
fun Stack(nav: Nav, sessions: BrowseSessions, content: @Composable (Page) -> Unit) {
    val ui = LocalUi.current
    val top = nav.top
    var shown by remember { mutableStateOf(top) }
    var shownKey by remember { mutableStateOf("${nav.stack.lastIndex}/$top") }
    val angle = remember { Animatable(0f) }
    var pinned by remember { mutableStateOf<Float?>(null) }
    var homeReturn by remember { mutableStateOf<HomeReturn?>(null) }
    val holder = rememberSaveableStateHolder()
    val slots = LocalPressSlots.current
    val kept = remember { HashSet<String>() }
    val keys = nav.stack.mapIndexed { i, p -> "$i/$p" }.toSet()
    SideEffect {
        val drop = kept - keys - shownKey
        drop.forEach { holder.removeState(it) }
        kept.removeAll(drop)
        kept.addAll(keys)
        sessions.sync(nav.stack, keys + shownKey)
    }
    DisposableEffect(nav, sessions) { onDispose { sessions.stopAll() } }
    LaunchedEffect(top) {
        slots?.reset()
        if (pinned != null) {
            angle.snapTo(0f)
            pinned = null
        }
        if (top == shown) {
            angle.snapTo(0f)
            return@LaunchedEffect
        }
        val push = nav.forward
        val home = !push && top == Page.Home && nav.stack.size == 1
        val scale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
        var outStart = 0L
        if (home) launch { withFrameMillis { outStart = it } }
        val outAngle = if (push) Held.Nav.motion_turnstileOutAngle else Held.Nav.motion_turnstileInAngle
        angle.animateTo(outAngle, tween(Held.Nav.motion_turnstileOut.toInt(), easing = ExpIn6))
        if (home) {
            val outEnd = outStart + (Held.Nav.motion_turnstileOut * scale).roundToLong()
            withFrameMillis { f ->
                homeReturn = HomeReturn(outEnd, f, scale)
                pinned = 0f
                shown = top
                shownKey = "${nav.stack.lastIndex}/$top"
            }
            angle.snapTo(0f)
            pinned = null
            return@LaunchedEffect
        }
        pinned = outAngle
        angle.snapTo(if (push) Held.Nav.motion_turnstileInAngle else Held.Nav.motion_turnstileOutAngle)
        val entry = "${nav.stack.lastIndex}/$top"
        launch {
            withFrameMillis {
                homeReturn = null
                shown = top
                shownKey = entry
                pinned = null
            }
        }
        angle.animateTo(0f, tween(Held.Nav.motion_turnstileIn.toInt(), easing = ExpOut6))
    }
    val turning = angle.isRunning || pinned != null || top != shown
    SideEffect { nav.turning = turning }
    val camera = Tok.motion_perspectiveRatio * ui.px(ui.w) / SKIA_CAMERA_UNIT
    val probe = LocalTurnProbe.current
    val drawnKey = shownKey
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            rotationY = pinned ?: angle.value
            transformOrigin = TransformOrigin(0f, 0.5f)
            cameraDistance = camera
            probe?.invoke(drawnKey, rotationY)
        },
    ) {
        key(shownKey) { holder.SaveableStateProvider(shownKey) { CompositionLocalProvider(LocalEntry provides shownKey, LocalHomeReturn provides homeReturn) { content(shown) } } }
    }
}
