package net.seilent.funecomp.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import net.seilent.funecomp.proto.gen.Tok
import kotlin.math.roundToInt

@Stable
class SheetState {
    var shown by mutableStateOf(false)
        private set
    var title by mutableStateOf("")
        private set
    var items by mutableStateOf(emptyList<String>())
        private set
    var info by mutableStateOf(emptyList<String>())
        private set
    var focus by mutableIntStateOf(0)
    var entry by mutableStateOf<(@Composable () -> Unit)?>(null)
        private set
    private var pick: (Int) -> Unit = {}
    private var dismiss: () -> Unit = {}

    fun open(title: String, items: List<String>, focus: Int = 0, info: List<String> = emptyList(), onDismiss: () -> Unit = {}, pick: (Int) -> Unit) {
        this.title = title
        this.info = info
        this.items = items
        this.focus = focus
        this.entry = null
        this.pick = pick
        this.dismiss = onDismiss
        shown = true
    }

    fun openEntry(title: String, entry: @Composable () -> Unit) {
        this.title = title
        this.info = emptyList()
        this.items = listOf("")
        this.focus = 0
        this.entry = entry
        this.pick = {}
        this.dismiss = {}
        shown = true
    }

    fun accept(i: Int) {
        focus = i
        dismiss = {}
        close()
        pick(i)
    }

    fun close(dismissed: Boolean = true) {
        if (!shown) return
        shown = false
        val d = dismiss
        dismiss = {}
        if (dismissed) d()
    }
}

@Composable
fun Sheet(state: SheetState) {
    val ui = LocalUi.current
    val progress = remember { Animatable(0f) }
    LaunchedEffect(state.shown) {
        progress.animateTo(if (state.shown) 1f else 0f, tween(Tok.motion_sheet.toInt(), easing = Decel))
    }
    if (progress.value <= 0f && !state.shown) return
    val measurer = rememberTextMeasurer()
    val edge = ui.px(Tok.edge)
    val pitch = ui.px(Tok.settingsPitch)
    val titleH = if (state.title.isEmpty()) 0f else measurer.measure(shown(state.title, Roles.dialogTitle.case), textStyle(Roles.dialogTitle, white(1f))).size.height.toFloat()
    val density = LocalDensity.current
    val lift = (WindowInsets.ime.getBottom(density) - WindowInsets.systemBars.getBottom(density)).coerceAtLeast(0).toFloat()
    val screenH = ui.px(ui.h) - lift
    val lead = state.info.size
    val wanted = edge + titleH + (lead + state.items.size) * pitch + edge
    val panelH = minOf(wanted, screenH)
    val p = progress.value
    val panelY = screenH - panelH + (1f - p) * panelH
    val list = remember(state.title, state.items, state.shown) { LazyListState(state.focus) }
    Box(Modifier.fillMaxSize().testTag("sheet")) {
        val b = LocalBleed.current
        Box(Modifier.fillMaxSize().bleed(b).background(Color(Tok.color_bg).copy(alpha = Tok.scrim_sheet_alpha * p)).press({ if (state.shown) state.close() }))
        Box(
            Modifier.offset { IntOffset(-b.left, panelY.roundToInt()) }.size((ui.px(ui.w) + b.left + b.right).dp(), (panelH + b.bottom).dp())
                .alpha(p).background(Color(Tok.color_bgElevated)).press({}),
        )
        Box(
            Modifier.offset { IntOffset(0, panelY.roundToInt()) }.size(ui.px(ui.w).dp(), panelH.dp())
                .alpha(p).clipToBounds(),
        ) {
            if (state.title.isNotEmpty()) RoleText(state.title, Roles.dialogTitle, Modifier.at(edge, edge).width((ui.px(ui.w) - 2 * edge).dp()).clipToBounds())
            Box(Modifier.offset { IntOffset(0, (edge + titleH).roundToInt()) }.size(ui.px(ui.w).dp(), (panelH - edge - titleH).coerceAtLeast(0f).dp())) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = list,
                flingBehavior = rememberFling(),
            ) {
                itemsIndexed(state.info, key = { i, _ -> "info$i" }) { i, line ->
                    Box(Modifier.size(ui.px(ui.w).dp(), pitch.dp()).testTag(if (i == 0) "sheet.info" else "sheet.info.$i")) {
                        RoleText(line, Roles.dialogItem, Modifier.at(edge, pitch / 2, ay = 0.5f).width((ui.px(ui.w) - 2 * edge).dp()).clipToBounds(), color = white(Roles.dialogItem.dim))
                    }
                }
                itemsIndexed(state.items) { i, item ->
                    val on = i == state.focus
                    Box(
                        Modifier.size(ui.px(ui.w).dp(), pitch.dp()).press({ if (state.shown && state.entry == null) state.accept(i) }).testTag("sheet.item.$i"),
                    ) {
                        val entry = state.entry
                        if (entry != null) {
                            Box(Modifier.at(edge, pitch / 2, ay = 0.5f).width((ui.px(ui.w) - 2 * edge).dp())) { entry() }
                        } else {
                            val c by animateColorAsState(white(if (on) Roles.dialogItem.alpha else Roles.dialogItem.dim), tween(Tok.motion_focusAlpha.toInt()), label = "item")
                            RoleText(item, Roles.dialogItem, Modifier.at(edge, pitch / 2, ay = 0.5f).width((ui.px(ui.w) - 2 * edge).dp()).clipToBounds(), color = c)
                        }
                    }
                }
            }
            ListBar(list, state.focus + lead, edge - ui.px(Tok.focus_barOffset))
            }
        }
    }
}

@Stable
class ToastState {
    var artist by mutableStateOf("")
        private set
    var title by mutableStateOf("")
        private set
    var shows by mutableIntStateOf(0)
        private set

    fun show(artist: String, title: String) {
        this.artist = artist
        this.title = title
        shows++
    }
}

@Composable
fun Toast(state: ToastState, held: Pair<String, String>? = null) {
    val ui = LocalUi.current
    val progress = remember { Animatable(0f) }
    var leaving by remember { mutableStateOf(false) }
    var artist by remember { mutableStateOf(held?.first ?: "") }
    var title by remember { mutableStateOf(held?.second ?: "") }
    LaunchedEffect(held) {
        if (held != null) {
            artist = held.first
            title = held.second
        }
    }
    val handled = remember { intArrayOf(state.shows) }
    LaunchedEffect(state.shows, held != null) {
        val fresh = state.shows != handled[0]
        handled[0] = state.shows
        if (fresh && held == null) {
            artist = state.artist
            title = state.title
        }
        if (held == null && !fresh) {
            if (progress.value > 0f) {
                leaving = true
                progress.animateTo(0f, tween(Tok.motion_toastOut.toInt(), easing = LinearEasing))
            }
            return@LaunchedEffect
        }
        leaving = false
        progress.animateTo(1f, tween(Tok.motion_toastIn.toInt(), easing = LinearEasing))
        if (held != null) return@LaunchedEffect
        delay(Tok.motion_toastHold.toLong())
        leaving = true
        progress.animateTo(0f, tween(Tok.motion_toastOut.toInt(), easing = LinearEasing))
    }
    if (progress.value <= 0f) return
    val e = if (leaving) 1f - ExpOut6.transform(1f - progress.value) else ExpOut6.transform(progress.value)
    val travel = ui.px(Tok.motion_toastTravel)
    val edge = ui.px(Tok.edge)
    Box(
        Modifier.offset { IntOffset(0, (-travel * (1f - e)).roundToInt()) }.size(ui.px(ui.w).dp(), ui.px(Tok.toastHeight).dp())
            .alpha(e).background(Black.copy(alpha = Tok.scrim_overlay_alpha)).testTag("toast"),
    ) {
        Box(Modifier.at(edge, 0f).size((ui.px(ui.w) - 2 * edge).dp(), ui.px(Tok.toastHeight).dp()).clipToBounds()) {
            RoleText(artist, Roles.toastArtist, Modifier.atBaseline(0f, ui.px(Tok.toastArtistBaseline)))
            RoleText(title, Roles.toastTitle, Modifier.atBaseline(0f, ui.px(Tok.toastTitleBaseline)).testTag("toast.title"))
        }
    }
}
