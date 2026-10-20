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
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import net.seilent.funecomp.proto.gen.Tok
import kotlin.math.floor
import kotlin.math.roundToInt

internal const val OFF = "off"
internal const val ON = "on"
private const val LINE_MAX = 2

class Keep(val list: LazyListState, val index: Int, val top: Float)

val LocalSheet = staticCompositionLocalOf<SheetState?> { null }

@Stable
class SheetState {
    var shown by mutableStateOf(false)
        private set
    var title by mutableStateOf("")
        private set
    var items by mutableStateOf(emptyList<String>())
        private set
    private var lines by mutableStateOf<() -> List<String>>({ emptyList() })
    private var lineValues by mutableStateOf<() -> List<String>>({ emptyList() })
    private var lineMixed by mutableStateOf<() -> List<Boolean>>({ emptyList() })
    private var values by mutableStateOf<() -> List<String>>({ emptyList() })
    val info: List<String> get() = lines()
    val infoValues: List<String> get() = lineValues()
    val infoMixed: List<Boolean> get() = lineMixed()
    val itemValues: List<String> get() = values()
    var focus by mutableIntStateOf(0)
    var entry by mutableStateOf<(@Composable () -> Unit)?>(null)
        private set
    private var pick: (Int) -> Unit = {}
    private var dismiss: () -> Unit = {}
    private var owner: Any? = null
    var keep: Keep? = null
        private set
    var past by mutableFloatStateOf(0f)
    var pastList by mutableStateOf<LazyListState?>(null)

    fun open(
        title: String,
        items: List<String>,
        focus: Int = 0,
        info: List<String> = emptyList(),
        onDismiss: () -> Unit = {},
        owner: Any? = null,
        infoValues: () -> List<String> = { emptyList() },
        itemValues: () -> List<String> = { emptyList() },
        infoMixed: () -> List<Boolean> = { emptyList() },
        infoLines: () -> List<String> = { info },
        keep: Keep? = null,
        pick: (Int) -> Unit,
    ) {
        this.owner = owner
        this.keep = keep
        this.title = title
        this.lines = infoLines
        this.lineValues = infoValues
        this.lineMixed = infoMixed
        this.values = itemValues
        this.items = items
        this.focus = focus
        this.entry = null
        this.pick = pick
        this.dismiss = onDismiss
        shown = true
    }

    fun openEntry(title: String, owner: Any? = null, entry: @Composable () -> Unit) {
        this.owner = owner
        this.keep = null
        this.title = title
        this.lines = { emptyList() }
        this.lineValues = { emptyList() }
        this.lineMixed = { emptyList() }
        this.values = { emptyList() }
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

    fun closeIf(owner: Any) {
        if (shown && this.owner == owner) close(dismissed = false)
    }

    fun close(dismissed: Boolean = true) {
        if (!shown) return
        shown = false
        owner = null
        keep = null
        val d = dismiss
        dismiss = {}
        if (dismissed) d()
    }
}

private fun firstRow(at: Int, total: Int, items: Int, rows: Int): Int = (if (items > rows) at - rows + 1 else minOf(at, total - rows)).coerceAtLeast(0)

fun onOffSheet(ctx: Ctx, title: String, on: Boolean, keep: Keep? = null, set: (Boolean) -> Unit) {
    ctx.sheet.open(title, listOf(OFF, ON), if (on) 1 else 0, keep = keep) { if ((it == 1) != on) set(it == 1) }
}

private suspend fun SheetState.keepRow(k: Keep, panelTop: Float, gutter: Float) {
    past = 0f
    pastList = null
    val info = k.list.layoutInfo
    val row = info.visibleItemsInfo.firstOrNull { it.index == k.index } ?: return
    val rowTop = k.top + row.offset
    val d = rowTop + row.size - (panelTop - gutter)
    if (d <= 0f || rowTop - d < k.top) return
    val last = info.visibleItemsInfo.last()
    val left = if (last.index == info.totalItemsCount - 1) (last.offset + last.size - info.viewportEndOffset).toFloat() else 0f
    if (d > left) {
        pastList = k.list
        past = d - left
        withTimeoutOrNull(Tok.motion_sheet.toLong()) { snapshotFlow { k.list.layoutInfo.afterContentPadding }.first { it >= past - 1f } }
    }
    k.list.animateScrollBy(d, tween(Tok.motion_scroll.toInt(), easing = EaseIn))
}

private fun LazyListState.pastEnd(): Float {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return 0f
    if (last.index != info.totalItemsCount - 1) return 0f
    return (info.viewportEndOffset - last.offset - last.size).coerceAtLeast(0).toFloat()
}

private fun TextMeasurer.wrapped(text: String, style: TextStyle, width: Int): String {
    val l = measure(text, style, softWrap = true, maxLines = LINE_MAX, constraints = Constraints(maxWidth = width))
    if (!l.didOverflowHeight || l.lineCount < LINE_MAX) return text
    var k = l.getLineEnd(0)
    while (k < text.length) {
        val next = text.offsetByCodePoints(k, 1)
        if (measure(text.substring(0, next), style, softWrap = false).size.width > width) break
        k = next
    }
    return text.substring(0, k) + "\n" + text.substring(k)
}

@Composable
private fun SheetLine(text: String, value: String, layout: TextLayoutResult, h: Float, tag: String) {
    val ui = LocalUi.current
    val top = (ui.px(Tok.settingsPitch) - layout.getLineBottom(0)) / 2
    Box(Modifier.size(ui.px(ui.w).dp(), h.dp()).testTag(tag)) {
        BasicText(
            text,
            Modifier.at(ui.px(Tok.edge), top).width(layout.layoutInput.constraints.maxWidth.toFloat().dp()).clipToBounds(),
            style = textStyle(Roles.dialogItem, white(Roles.dialogItem.dim)),
            overflow = TextOverflow.Clip,
            softWrap = true,
            maxLines = LINE_MAX,
        )
        if (value.isNotEmpty()) SheetValue(value, top + layout.firstBaseline, Tok.settingsValueDim)
    }
}

@Composable
private fun SheetValue(value: String, baseline: Float, alpha: Float) {
    val ui = LocalUi.current
    val measurer = rememberTextMeasurer()
    val c by animateColorAsState(white(alpha), tween(Tok.motion_focusAlpha.toInt()), label = "value")
    val w = measurer.measure(shown(value, Roles.settingsValue.case), textStyle(Roles.settingsValue, c)).size.width
    RoleText(value, Roles.settingsValue, Modifier.atBaseline(ui.px(ui.w) - ui.px(Tok.edge) - w, baseline), color = c)
}

@Composable
fun Sheet(state: SheetState) {
    val ui = LocalUi.current
    val progress = remember { Animatable(0f) }
    val panelTop = remember { floatArrayOf(0f) }
    LaunchedEffect(state.shown) {
        if (state.shown) {
            state.keep?.let { state.keepRow(it, panelTop[0], ui.px(Tok.gutter)) }
            progress.animateTo(1f, tween(Tok.motion_sheet.toInt(), easing = Decel))
        } else {
            progress.animateTo(0f, tween(Tok.motion_sheet.toInt(), easing = Decel))
            val back = state.pastList
            val over = back?.pastEnd() ?: 0f
            if (back != null && over > 0f) back.animateScrollBy(-over, tween(Tok.motion_scroll.toInt(), easing = EaseIn))
            state.past = 0f
            state.pastList = null
        }
    }
    if (progress.value <= 0f && !state.shown) return
    val measurer = rememberTextMeasurer()
    val edge = ui.px(Tok.edge)
    val pitch = ui.px(Tok.settingsPitch)
    val gutter = ui.px(Tok.gutter)
    val w = ui.px(ui.w)
    val titleH = if (state.title.isEmpty()) 0f else measurer.measure(shown(state.title, Roles.dialogTitle.case), textStyle(Roles.dialogTitle, white(1f))).size.height.toFloat()
    val density = LocalDensity.current
    val lift = (WindowInsets.ime.getBottom(density) - WindowInsets.systemBars.getBottom(density)).coerceAtLeast(0).toFloat()
    val screenH = ui.px(ui.h) - lift
    val lines = state.info
    val lineValues = state.infoValues
    val mixed = state.infoMixed
    val itemValues = state.itemValues
    val itemStyle = textStyle(Roles.dialogItem, white(1f))
    val valueStyle = textStyle(Roles.settingsValue, white(1f))
    fun slot(value: String) = (if (value.isEmpty()) w - 2 * edge else w - 2 * edge - measurer.measure(shown(value, Roles.settingsValue.case), valueStyle).size.width - gutter).coerceAtLeast(0f)
    val widths = lines.indices.map { slot(lineValues.getOrElse(it) { "" }).roundToInt() }
    val texts = lines.mapIndexed { i, line -> measurer.wrapped(shown(line, if (mixed.getOrElse(i) { false }) Case.MIXED else Roles.dialogItem.case), itemStyle, widths[i]) }
    val layouts = texts.mapIndexed { i, t -> measurer.measure(t, itemStyle, overflow = TextOverflow.Clip, softWrap = true, maxLines = LINE_MAX, constraints = Constraints(maxWidth = widths[i])) }
    val heights = layouts.map { pitch + it.getLineBottom(it.lineCount - 1) - it.getLineBottom(0) }
    val wanted = edge + titleH + heights.sum() + state.items.size * pitch + edge
    val panelH = minOf(wanted, screenH)
    panelTop[0] = screenH - panelH
    val p = progress.value
    val panelY = screenH - panelH + (1f - p) * panelH
    val room = (panelH - edge - titleH).coerceAtLeast(0f)
    val rows = floor(room / pitch).toInt()
    val cap = maxOf(1, rows - minOf(state.items.size, 2)) * pitch
    var fixed = 0
    var fixedH = 0f
    while (fixed < heights.size && (fixed == 0 || fixedH + heights[fixed] <= cap)) {
        fixedH += heights[fixed]
        fixed++
    }
    val listH = (if (wanted <= screenH) room - fixedH - edge else floor((room - fixedH) / pitch) * pitch).coerceAtLeast(0f)
    val listRows = floor(listH / pitch).toInt()
    val scrolled = lines.size - fixed
    val list = remember(state.title, state.items, state.shown) { LazyListState(firstRow(state.focus + scrolled, scrolled + state.items.size, state.items.size, listRows)) }
    Box(Modifier.fillMaxSize().testTag("sheet")) {
        val b = LocalBleed.current
        Box(Modifier.fillMaxSize().bleed(b).background(Color(Tok.color_bg).copy(alpha = Tok.scrim_sheet_alpha * p)).press({ if (state.shown) state.close() }))
        Box(
            Modifier.offset { IntOffset(0, panelY.roundToInt()) }.size(w.dp(), panelH.dp()).bleed(Bleed(b.left, 0, b.right, b.bottom))
                .alpha(p).background(Color(Tok.color_bgElevated)).press({}),
        )
        Box(
            Modifier.offset { IntOffset(0, panelY.roundToInt()) }.size(w.dp(), panelH.dp())
                .alpha(p).clipToBounds().testTag("sheet.panel"),
        ) {
            if (state.title.isNotEmpty()) RoleText(state.title, Roles.dialogTitle, Modifier.at(edge, edge).width((w - 2 * edge).dp()).clipToBounds())
            var y = edge + titleH
            for (i in 0 until fixed) {
                val at = y
                Box(Modifier.offset { IntOffset(0, at.roundToInt()) }) {
                    SheetLine(texts[i], lineValues.getOrElse(i) { "" }, layouts[i], heights[i], if (i == 0) "sheet.info" else "sheet.info.$i")
                }
                y += heights[i]
            }
            Box(Modifier.offset { IntOffset(0, (edge + titleH + fixedH).roundToInt()) }.size(w.dp(), listH.dp())) {
                LazyColumn(
                    Modifier.fillMaxSize().testTag("sheet.list"),
                    state = list,
                    flingBehavior = rememberFling(),
                ) {
                    items(scrolled, key = { "info${fixed + it}" }) { j ->
                        val i = fixed + j
                        SheetLine(texts[i], lineValues.getOrElse(i) { "" }, layouts[i], heights[i], if (i == 0) "sheet.info" else "sheet.info.$i")
                    }
                    itemsIndexed(state.items) { i, item ->
                        val on = i == state.focus
                        val value = itemValues.getOrElse(i) { "" }
                        Box(
                            Modifier.size(w.dp(), pitch.dp()).press({ if (state.shown && state.entry == null) state.accept(i) }).testTag("sheet.item.$i"),
                        ) {
                            val entry = state.entry
                            if (entry != null) {
                                Box(Modifier.at(edge, pitch / 2, ay = 0.5f).width((w - 2 * edge).dp())) { entry() }
                            } else {
                                val c by animateColorAsState(white(if (on) Roles.dialogItem.alpha else Roles.dialogItem.dim), tween(Tok.motion_focusAlpha.toInt()), label = "item")
                                RoleText(item, Roles.dialogItem, Modifier.at(edge, pitch / 2, ay = 0.5f).width(slot(value).dp()).clipToBounds(), color = c)
                                if (value.isNotEmpty()) {
                                    val t = measurer.measure(shown(item, Roles.dialogItem.case), itemStyle)
                                    SheetValue(value, (pitch - t.size.height) / 2 + t.firstBaseline, if (on) Tok.settingsValueFocused else Tok.settingsValueDim)
                                }
                            }
                        }
                    }
                }
                ListBar(list, state.focus + scrolled, edge - ui.px(Tok.focus_barOffset))
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
