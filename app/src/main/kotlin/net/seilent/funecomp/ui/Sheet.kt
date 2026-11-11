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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.seilent.funecomp.proto.gen.Tok
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
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
    private var lineDrop by mutableStateOf<() -> List<Boolean>>({ emptyList() })
    private var values by mutableStateOf<() -> List<String>>({ emptyList() })
    private var itemCase by mutableStateOf<() -> List<Boolean>>({ emptyList() })
    var titleMixed by mutableStateOf(false)
        private set
    val info: List<String> get() = lines()
    val infoValues: List<String> get() = lineValues()
    val infoMixed: List<Boolean> get() = lineMixed()
    val infoDrop: List<Boolean> get() = lineDrop()
    val itemValues: List<String> get() = values()
    val itemMixed: List<Boolean> get() = itemCase()
    var focus by mutableIntStateOf(0)
    var entry by mutableStateOf<(@Composable () -> Unit)?>(null)
        private set
    private var pick: (Int) -> Unit = {}
    private var dismiss: () -> Unit = {}
    private var onPress: ((Int, Float) -> Boolean)? = null
    private var onHold: ((Int) -> Boolean)? = null
    private var onScrim: (() -> Boolean)? = null
    private var accent by mutableStateOf<() -> Int>({ -1 })
    var holds by mutableStateOf(false)
        private set
    val accentItem: Int get() = accent()
    var armed by mutableStateOf(false)
        private set
    var guard = 0L
        private set
    var opened by mutableIntStateOf(0)
        private set
    private var owner: Any? = null
    var keep: Keep? = null
        private set
    var page: () -> Any? = { null }
    var started: () -> Boolean = { true }
    private var openedOn: Any? = null
    var stoppedClose = false
        private set
    val pageChanged: Boolean get() = page() != openedOn
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
        infoDrop: () -> List<Boolean> = { emptyList() },
        itemMixed: () -> List<Boolean> = { emptyList() },
        titleMixed: Boolean = false,
        infoLines: () -> List<String> = { info },
        keep: Keep? = null,
        accent: () -> Int = { -1 },
        onPress: ((i: Int, x: Float) -> Boolean)? = null,
        onHold: ((i: Int) -> Boolean)? = null,
        onScrim: (() -> Boolean)? = null,
        guard: Long = 0L,
        pick: (Int) -> Unit,
    ) {
        this.owner = owner
        this.guard = guard
        armed = guard > 0
        openedOn = page()
        stoppedClose = false
        opened++
        this.accent = accent
        this.onPress = onPress
        this.onHold = onHold
        this.onScrim = onScrim
        holds = onHold != null
        this.keep = keep
        this.title = title
        this.lines = infoLines
        this.lineValues = infoValues
        this.lineMixed = infoMixed
        this.lineDrop = infoDrop
        this.itemCase = itemMixed
        this.titleMixed = titleMixed
        this.values = itemValues
        this.items = items
        this.focus = focus
        this.entry = null
        this.pick = pick
        this.dismiss = onDismiss
        shown = true
    }

    fun openEntry(title: String, owner: Any? = null, keep: Keep? = null, entry: @Composable () -> Unit) {
        this.owner = owner
        guard = 0L
        armed = false
        openedOn = page()
        stoppedClose = false
        opened++
        this.accent = { -1 }
        this.onPress = null
        this.onHold = null
        this.onScrim = null
        holds = false
        this.keep = keep
        this.title = title
        this.lines = { emptyList() }
        this.lineValues = { emptyList() }
        this.lineMixed = { emptyList() }
        this.lineDrop = { emptyList() }
        this.itemCase = { emptyList() }
        this.titleMixed = false
        this.values = { emptyList() }
        this.items = listOf("")
        this.focus = 0
        this.entry = entry
        this.pick = {}
        this.dismiss = {}
        shown = true
    }

    fun press(i: Int, x: Float) {
        if (!shown || entry != null || armed) return
        if (onPress?.invoke(i, x) != true && i >= 0) accept(i)
    }

    fun click(i: Int): Boolean {
        if (!shown || entry != null || armed || onPress != null) return false
        accept(i)
        return true
    }

    fun unguard() {
        armed = false
    }

    fun hold(i: Int) {
        if (shown && entry == null) onHold?.invoke(i)
    }

    fun scrim() {
        if (shown && !armed && onScrim?.invoke() != true) close()
    }

    fun accept(i: Int) {
        focus = i
        dismiss = {}
        val p = pick
        close()
        p(i)
    }

    fun owns(owner: Any): Boolean = shown && this.owner == owner

    fun closeIf(owner: Any) {
        if (shown && this.owner == owner) close(dismissed = false)
    }

    fun close(dismissed: Boolean = true) {
        if (!shown) return
        stoppedClose = !started()
        shown = false
        armed = false
        owner = null
        keep = null
        pick = {}
        onPress = null
        onHold = null
        onScrim = null
        val d = dismiss
        dismiss = {}
        if (dismissed) d()
    }
}

private suspend fun AwaitPointerEventScope.held(state: SheetState) {
    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    if (!state.armed) return
    down.consume()
    do {
        val e = awaitPointerEvent(PointerEventPass.Initial)
        e.changes.forEach { it.consume() }
    } while (e.changes.any { it.pressed })
}

private fun firstRow(at: Int, total: Int, items: Int, rows: Int): Int = (if (items > rows) at - rows + 1 else minOf(at, total - rows)).coerceAtLeast(0)

fun onOffSheet(ctx: Ctx, title: String, on: Boolean, keep: Keep? = null, owner: Any? = null, set: (Boolean) -> Unit) {
    ctx.sheet.open(title, listOf(OFF, ON), if (on) 1 else 0, keep = keep, owner = owner) { if ((it == 1) != on) set(it == 1) }
}

private suspend fun SheetState.keepRow(k: Keep, panelTop: Float, gutter: Float) {
    val lifted = if (pastList === k.list) past else 0f
    if (lifted == 0f) {
        past = 0f
        pastList = null
    }
    val info = k.list.layoutInfo
    val row = info.visibleItemsInfo.firstOrNull { it.index == k.index } ?: return
    val top = k.top.roundToInt().toFloat()
    val rowTop = top + row.offset
    val d = ceil(rowTop + row.size - (panelTop.roundToInt() - gutter))
    if (d < 0f && lifted > 0f) {
        val back = max(d, -lifted)
        k.list.animateScrollBy(back, tween(Tok.motion_scroll.toInt(), easing = EaseIn))
        past = lifted + back
        if (past == 0f) pastList = null
        return
    }
    if (d <= 0f || rowTop - d < top) return
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
private fun SheetValue(value: String, baseline: Float, alpha: Float, editing: Boolean = false) {
    val ui = LocalUi.current
    val measurer = rememberTextMeasurer()
    val c by animateColorAsState(if (editing) Accent else white(alpha), tween(Tok.motion_focusAlpha.toInt()), label = "value")
    val role = if (editing && AccentOff) Roles.settingsValue.copy(weight = FontWeight.Bold) else Roles.settingsValue
    val w = measurer.measure(shown(value, role.case), textStyle(role, c)).size.width
    RoleText(value, role, Modifier.atBaseline(ui.px(ui.w) - ui.px(Tok.edge) - w, baseline), color = c)
}

@Composable
fun Sheet(state: SheetState, ime: WindowInsets) {
    val ui = LocalUi.current
    val progress = remember { Animatable(0f) }
    val panelTop = remember { floatArrayOf(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(state, lifecycle) {
        state.started = { lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        onDispose { state.started = { true } }
    }
    LaunchedEffect(state.shown, state.opened) {
        if (state.shown) {
            if (state.pastList != null && state.pastList !== state.keep?.list) {
                state.past = 0f
                state.pastList = null
            }
            state.keep?.let { state.keepRow(it, panelTop[0], ui.px(Tok.gutter)) }
            progress.animateTo(1f, tween(Tok.motion_sheet.toInt(), easing = Decel))
        } else if (progress.value == 0f || state.stoppedClose) {
            progress.snapTo(0f)
            state.past = 0f
            state.pastList = null
        } else if (state.pageChanged) {
            state.past = 0f
            state.pastList = null
            progress.animateTo(0f, tween(Tok.motion_sheet.toInt(), easing = Decel))
        } else {
            val back = state.pastList
            val over = back?.pastEnd() ?: 0f
            coroutineScope {
                launch { progress.animateTo(0f, tween(Tok.motion_sheet.toInt(), easing = Decel)) }
                if (back != null && over > 0f) back.animateScrollBy(-over, tween(Tok.motion_scroll.toInt(), easing = Decel))
                state.past = 0f
                state.pastList = null
            }
        }
    }
    LaunchedEffect(state.opened) {
        if (state.armed) {
            delay(state.guard)
            state.unguard()
        }
    }
    if (progress.value <= 0f && !state.shown) return
    val measurer = rememberTextMeasurer()
    val edge = ui.px(Tok.edge)
    val pitch = ui.px(Tok.settingsPitch)
    val gutter = ui.px(Tok.gutter)
    val w = ui.px(ui.w)
    val titleRole = if (state.titleMixed) Roles.dialogTitle.copy(case = Case.MIXED) else Roles.dialogTitle
    val titleH = if (state.title.isEmpty()) 0f else measurer.measure(shown(state.title, titleRole.case), textStyle(titleRole, white(1f))).size.height.toFloat()
    val density = LocalDensity.current
    val lift = ime.getBottom(density).toFloat()
    val screenH = ui.px(ui.h) - lift
    val itemValues = state.itemValues
    val itemMixed = state.itemMixed
    val itemStyle = textStyle(Roles.dialogItem, white(1f))
    val valueStyle = textStyle(Roles.settingsValue, white(1f))
    fun slot(value: String) = (if (value.isEmpty()) w - 2 * edge else w - 2 * edge - measurer.measure(shown(value, Roles.settingsValue.case), valueStyle).size.width - gutter).coerceAtLeast(0f)
    val all = state.info
    val drop = state.infoDrop
    val keep = all.indices.filter { i ->
        !drop.getOrElse(i) { false } || measurer.measure(shown(all[i], if (state.infoMixed.getOrElse(i) { false }) Case.MIXED else Roles.dialogItem.case), itemStyle, softWrap = true, constraints = Constraints(maxWidth = slot(state.infoValues.getOrElse(i) { "" }).roundToInt())).lineCount <= LINE_MAX
    }
    val lines = keep.map { all[it] }
    val lineValues = keep.map { state.infoValues.getOrElse(it) { "" } }
    val mixed = keep.map { state.infoMixed.getOrElse(it) { false } }
    val widths = lines.indices.map { slot(lineValues.getOrElse(it) { "" }).roundToInt() }
    val texts = lines.mapIndexed { i, line -> measurer.wrapped(shown(line, if (mixed.getOrElse(i) { false }) Case.MIXED else Roles.dialogItem.case), itemStyle, widths[i]) }
    val layouts = texts.mapIndexed { i, t -> measurer.measure(t, itemStyle, overflow = TextOverflow.Clip, softWrap = true, maxLines = LINE_MAX, constraints = Constraints(maxWidth = widths[i])) }
    val heights = layouts.map { pitch + it.getLineBottom(it.lineCount - 1) - it.getLineBottom(0) }
    val wanted = edge + titleH + heights.sum() + state.items.size * pitch + edge
    val panelH = minOf(wanted, screenH)
    val full = if (state.entry != null) wanted else panelH
    val short = full - panelH
    val shift = if (short <= edge) short else edge + titleH
    panelTop[0] = screenH - panelH
    val p = progress.value
    val panelY = screenH - panelH + (1f - p) * panelH
    val room = (full - edge - titleH).coerceAtLeast(0f)
    val rows = floor(room / pitch).toInt()
    val cap = maxOf(1, rows - minOf(state.items.size, 2)) * pitch
    var fixed = 0
    var fixedH = 0f
    while (fixed < heights.size && (fixed == 0 || fixedH + heights[fixed] <= cap)) {
        fixedH += heights[fixed]
        fixed++
    }
    val listH = (if (full == wanted) room - fixedH - edge else floor((room - fixedH) / pitch) * pitch).coerceAtLeast(0f)
    val listRows = floor(listH / pitch).toInt()
    val scrolled = lines.size - fixed
    val list = remember(state.title, state.items, state.shown) { LazyListState(firstRow(state.focus + scrolled, scrolled + state.items.size, state.items.size, listRows)) }
    Box(Modifier.fillMaxSize().testTag("sheet").pointerInput(state) { awaitEachGesture { held(state) } }) {
        val b = LocalBleed.current
        Box(Modifier.fillMaxSize().bleed(b).background(Color(Tok.color_bg).copy(alpha = Tok.scrim_sheet_alpha * p)).press({ state.scrim() }))
        Box(
            Modifier.offset { IntOffset(0, panelY.roundToInt()) }.size(w.dp(), panelH.dp()).bleed(Bleed(b.left, 0, b.right, b.bottom))
                .alpha(p).background(Color(Tok.color_bgElevated)).press({ state.press(-1, 0f) }),
        )
        Box(
            Modifier.offset { IntOffset(0, panelY.roundToInt()) }.size(w.dp(), panelH.dp())
                .alpha(p).clipToBounds().testTag("sheet.panel"),
        ) {
            if (state.title.isNotEmpty()) RoleText(state.title, titleRole, Modifier.at(edge, edge - shift).width((w - 2 * edge).dp()).clipToBounds().testTag("sheet.title"))
            var y = edge + titleH - shift
            for (i in 0 until fixed) {
                val at = y
                Box(Modifier.offset { IntOffset(0, at.roundToInt()) }) {
                    SheetLine(texts[i], lineValues.getOrElse(i) { "" }, layouts[i], heights[i], if (i == 0) "sheet.info" else "sheet.info.$i")
                }
                y += heights[i]
            }
            Box(Modifier.offset { IntOffset(0, (edge + titleH + fixedH - shift).roundToInt()) }.size(w.dp(), listH.dp())) {
                val scrolling = remember(list) { { list.isScrollInProgress } }
                CompositionLocalProvider(LocalScrolling provides scrolling) {
                    LazyColumn(
                        Modifier.fillMaxSize().testTag("sheet.list"),
                        state = list,
                        flingBehavior = rememberFling(Tok.motion_listFlingRetention),
                    ) {
                        items(scrolled, key = { "info${fixed + it}" }) { j ->
                            val i = fixed + j
                            SheetLine(texts[i], lineValues.getOrElse(i) { "" }, layouts[i], heights[i], if (i == 0) "sheet.info" else "sheet.info.$i")
                        }
                        itemsIndexed(state.items) { i, item ->
                            val on = i == state.focus
                            val value = itemValues.getOrElse(i) { "" }
                            val press = rememberPress()
                            Box(
                                Modifier.size(w.dp(), pitch.dp()).press({ o -> state.press(i, o.x / w) }, if (state.holds) ({ state.hold(i) }) else null, press).semantics { onClick { state.click(i) } }.testTag("sheet.item.$i"),
                            ) {
                                val entry = state.entry
                                if (entry != null) {
                                    Box(Modifier.at(edge, pitch / 2, ay = 0.5f).width((w - 2 * edge).dp())) { entry() }
                                } else {
                                    val c by animateColorAsState(white(if (on) Roles.dialogItem.alpha else Roles.dialogItem.dim), tween(Tok.motion_focusAlpha.toInt()), label = "item")
                                    val role = if (itemMixed.getOrElse(i) { false }) Roles.dialogItem.copy(case = Case.MIXED) else Roles.dialogItem
                                    RoleText(item, role, Modifier.at(edge, pitch / 2, ay = 0.5f).width(slot(value).dp()).clipToBounds(), color = c, glow = press.word)
                                    if (value.isNotEmpty()) {
                                        val t = measurer.measure(shown(item, role.case), itemStyle)
                                        SheetValue(value, (pitch - t.size.height) / 2 + t.firstBaseline, if (on) Tok.settingsValueFocused else Tok.settingsValueDim, i == state.accentItem)
                                    }
                                }
                            }
                        }
                    }
                }
                val span = remember(list) { ListSpan() }
                ScrollThumb(list, { list.isScrollInProgress }, { span.of(list, { j -> if (j < scrolled) heights[fixed + j] else pitch }) })
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
    var grid by mutableStateOf(false)
        internal set

    fun show(artist: String, title: String) {
        this.artist = artist
        this.title = title
        shows++
    }
}

@Composable
fun Toast(state: ToastState, held: Pair<String, String>? = null, covered: Boolean = false) {
    val ui = LocalUi.current
    val progress = remember { Animatable(0f) }
    var leaving by remember { mutableStateOf(false) }
    var risen by remember { mutableStateOf(false) }
    val cover by rememberUpdatedState(covered)
    LaunchedEffect(covered) {
        if (covered) risen = true
    }
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
        risen = cover
        progress.animateTo(1f, tween(Tok.motion_toastRise.toInt(), easing = LinearEasing))
        if (held != null) return@LaunchedEffect
        delay(Tok.motion_toastHold.toLong())
        leaving = true
        progress.animateTo(0f, tween(Tok.motion_toastOut.toInt(), easing = LinearEasing))
    }
    if (progress.value <= 0f || covered) return
    val e = when {
        leaving -> 1f - ExpOut6.transform(1f - progress.value)
        risen -> 1f
        else -> progress.value
    }
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
