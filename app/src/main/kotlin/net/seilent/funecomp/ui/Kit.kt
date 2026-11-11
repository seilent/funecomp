package net.seilent.funecomp.ui

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.FloatDecayAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.generateDecayAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActionScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.Stable
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import net.seilent.funecomp.proto.gen.Tok
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MS_PER_S = 1000f
private const val PLACEHOLDER = "type to search"
private const val ROW_PRIMARY = "primary"
private const val ROW_SECONDARY = "secondary"
private const val ROW_NUMBER = "number"
private const val ROW_TRAILING = "trailing"
private const val NS_PER_S = 1_000_000_000L

@Composable
fun Float.dp() = with(LocalDensity.current) { this@dp.toDp() }

fun Modifier.press(onTap: (Offset) -> Unit, onLong: (() -> Unit)? = null, press: PressState? = null, onDown: () -> Unit = {}, step: PressedStep? = null) = composed {
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLong)
    val down by rememberUpdatedState(onDown)
    val hasLong = onLong != null
    if (press == null && step == null) {
        pointerInput(hasLong) {
            detectTapGestures(
                onPress = {
                    down()
                    tryAwaitRelease()
                },
                onLongPress = if (hasLong) { _: Offset -> long?.invoke() } else null,
                onTap = { tap(it) },
            )
        }
    } else {
        val scrolling = LocalScrolling.current
        val gesture = pointerInput(hasLong, press, step, scrolling) { pressGesture(press, step, hasLong, scrolling, { long?.invoke() }, { down() }) { tap(it) } }
        if (press == null) gesture else gesture.pressGlow(LocalUi.current, press.word) { press }
    }
}

private sealed interface Outcome {
    class Up(val change: PointerInputChange) : Outcome

    class Slop(val at: Long) : Outcome

    data object Gone : Outcome
}

private suspend fun AwaitPointerEventScope.track(first: PointerInputChange, slop: Float): Outcome {
    while (true) {
        val c = awaitPointerEvent().changes.firstOrNull { it.id == first.id } ?: return Outcome.Gone
        if (!c.pressed) return if (c.isConsumed) Outcome.Gone else Outcome.Up(c)
        if (c.isConsumed || c.isOutOfBounds(size, extendedTouchPadding) || (c.position - first.position).getDistance() > slop) return Outcome.Slop(c.uptimeMillis)
        val f = awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull { it.id == first.id }
        if (f != null && f.isConsumed) return Outcome.Slop(f.uptimeMillis)
    }
}

private suspend fun AwaitPointerEventScope.untilUp(first: PointerInputChange, consume: Boolean) {
    while (true) {
        val c = awaitPointerEvent().changes.firstOrNull { it.id == first.id } ?: return
        if (consume) c.consume()
        if (!c.pressed) return
    }
}

private suspend fun PointerInputScope.pressGesture(press: PressState?, step: PressedStep?, hasLong: Boolean, scrolling: () -> Boolean, long: () -> Unit, down: () -> Unit, tap: (Offset) -> Unit) = awaitEachGesture {
    val early = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    val caught = scrolling()
    val first = awaitPointerEvent().changes.firstOrNull { it.id == early.id } ?: return@awaitEachGesture
    if (first.isConsumed || !first.pressed) return@awaitEachGesture
    first.consume()
    if (caught) {
        untilUp(first, false)
        return@awaitEachGesture
    }
    press?.down(first.uptimeMillis)
    step?.down(first.uptimeMillis)
    down()
    val slop = viewConfiguration.touchSlop
    val outcome = if (hasLong) withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { track(first, slop) } else track(first, slop)
    when (outcome) {
        null -> {
            press?.reset()
            step?.reset()
            long()
            untilUp(first, true)
        }
        is Outcome.Up -> {
            press?.tap(outcome.change.uptimeMillis)
            step?.up(outcome.change.uptimeMillis)
            outcome.change.consume()
            tap(outcome.change.position)
        }
        is Outcome.Slop -> {
            press?.cancel(outcome.at)
            step?.up(outcome.at)
            untilUp(first, false)
        }
        Outcome.Gone -> {
            press?.reset()
            step?.reset()
        }
    }
}

@Composable
fun Pressable(onTap: (Offset) -> Unit, modifier: Modifier = Modifier, onLong: (() -> Unit)? = null, onDown: () -> Unit = {}, press: PressState = rememberPress(), step: PressedStep? = null, content: @Composable () -> Unit) {
    Box(modifier.press(onTap, onLong, press, onDown, step)) { content() }
}

@Composable
fun PageSurface() {
    Box(
        Modifier.fillMaxSize().bleed(LocalBleed.current).background(Black).then(
            if (AccentOff) Modifier else Modifier.background(Brush.verticalGradient(listOf(Accent.copy(alpha = Tok.pageSurface_alphaTop), Accent.copy(alpha = 0f)))),
        ),
    )
}

@Composable
fun HeaderWord(text: String, role: Role, x: Float, tracking: Float, tag: String = "header", onBack: (() -> Unit)? = null) {
    val ui = LocalUi.current
    val s = ui.headerScale
    val scaled = role.copy(size = role.size * s)
    val advance = rememberTextMeasurer().measure(shown(text, role.case), textStyle(scaled, white(role.alpha), tracking * s)).size.width.toFloat()
    val left = max(0f, x)
    val right = min(ui.px(ui.w), x + advance)
    val step = rememberStep()
    Box(Modifier.fillMaxWidth().height(ui.px(ui.pivotTop).dp()).clipToBounds().testTag("$tag.clip")) {
        RoleText(text, scaled, Modifier.atBaseline(x, ui.px(ui.headerBaseline)).testTag("$tag.word"), color = white(if (step.on) Tok.alpha_full else role.alpha), tracking = tracking * s)
    }
    val tap = if (onBack == null) {
        Modifier
    } else {
        Modifier.press({
            step.hold(Held.Nav.motion_turnstileOut.toLong())
            onBack()
        }, step = step)
    }
    Box(Modifier.at(left, 0f).size(max(0f, right - left).dp(), ui.px(Tok.headerBand).dp()).then(tap).testTag(tag))
}

@Composable
fun CroppedHeader(text: String, onBack: (() -> Unit)? = null, x: Float = Tok.page_headerX, alpha: Float = Roles.pageHeader.alpha, case: Case = Roles.pageHeader.case) {
    val ui = LocalUi.current
    HeaderWord(text, Roles.pageHeader.copy(alpha = alpha, case = case), ui.px(x * ui.headerScale), Tok.trackingHeader, onBack = onBack)
}

@Composable
fun PivotStrip(labels: List<String>, active: Int, onPick: (Int) -> Unit, land: Int = 0) {
    val ui = LocalUi.current
    val measurer = rememberTextMeasurer()
    val activeStyle = textStyle(Roles.pivotActive, white(Roles.pivotActive.alpha))
    val inactiveStyle = textStyle(Roles.pivotInactive, white(Roles.pivotInactive.alpha))
    val resolver = LocalFontFamilyResolver.current
    val positions = remember(labels, active, ui, resolver) {
        val shownLabels = labels.map { shown(it, Roles.pivotActive.case) }
        val widths = shownLabels.mapIndexed { i, l -> measurer.measure(l, if (i == active) activeStyle else inactiveStyle).size.width.toFloat() }
        val inks = shownLabels.mapIndexed { i, l -> ink(l, if (i == active) Roles.pivotActive else Roles.pivotInactive, ui, resolver) }
        val p = ArrayList<Float>()
        var x = 0f
        for (i in labels.indices) {
            p += x
            if (i == labels.lastIndex) break
            x += when (i) {
                active - 1 -> widths[i] + ui.px(Tok.pivotLeadGap)
                active -> inks[i].right + ui.px(Tok.pivotActiveGap) - inks[i + 1].left
                else -> inks[i].right + ui.px(Tok.pivotGap) - inks[i + 1].left
            }.roundToInt()
        }
        p
    }
    val rest = ui.px(Tok.hubEdge)
    val last = remember { intArrayOf(active) }
    val pitch = if (last[0] != active && last[0] in positions.indices && active in positions.indices) positions[active] - positions[last[0]] else 0f
    val shift = remember(active) { Animatable(pitch) }
    SideEffect { last[0] = active }
    LaunchedEffect(active) { shift.animateTo(0f, tween(Tok.motion_pivotStrip.toInt(), easing = ExpOut6)) }
    LaunchedEffect(land) { if (land > 0) shift.snapTo(0f) }
    val row = (rest - (positions.getOrNull(active) ?: 0f) + shift.value).roundToInt()
    Box(Modifier.fillMaxWidth().height(ui.px(ui.pivotBottom).dp()).clipToBounds()) {
        labels.forEachIndexed { i, l ->
            val role = if (i == active) Roles.pivotActive else Roles.pivotInactive
            val step = rememberStep()
            val lifted = remember { booleanArrayOf(false) }
            val pick = Modifier.press({
                lifted[0] = true
                step.hold(Tok.motion_pivotStrip.toLong())
                onPick(i)
            }, onDown = { lifted[0] = false }, step = step)
            RoleText(l, role, Modifier.atBaseline(row + positions[i], ui.px(ui.pivotBaseline)).then(pick).testTag("pivot.$l"), color = white(if (step.on && (i == active || !lifted[0])) Tok.alpha_full else role.alpha))
        }
    }
}

private fun ink(text: String, role: Role, ui: Ui, resolver: FontFamily.Resolver): RectF {
    val size = ui.fontPx(role.size)
    val paint = Paint().apply {
        typeface = resolver.resolve(Selawik, role.weight).value as Typeface
        textSize = size
        letterSpacing = ui.px(Tok.tracking) / size
    }
    val path = Path()
    paint.getTextPath(text, 0, text.length, 0f, 0f, path)
    return RectF().also { path.computeBounds(it, true) }
}

class MicroFling(private val k: Float, private val threshold: Float) : FloatDecayAnimationSpec {
    override val absVelocityThreshold: Float get() = threshold

    private fun t(nanos: Long) = nanos.toFloat() / NS_PER_S

    override fun getValueFromNanos(playTimeNanos: Long, initialValue: Float, initialVelocity: Float) =
        initialValue + initialVelocity / k * (1f - exp(-k * t(playTimeNanos)))

    override fun getDurationNanos(initialValue: Float, initialVelocity: Float): Long {
        if (abs(initialVelocity) <= threshold) return 0L
        return (ln(abs(initialVelocity) / threshold) / k * NS_PER_S).toLong()
    }

    override fun getVelocityFromNanos(playTimeNanos: Long, initialValue: Float, initialVelocity: Float) =
        initialVelocity * exp(-k * t(playTimeNanos))

    override fun getTargetValue(initialValue: Float, initialVelocity: Float) = initialValue + initialVelocity / k
}

@Composable
fun rememberFling(retention: Float = Tok.motion_flingRetention): FlingBehavior {
    val ui = LocalUi.current
    return remember(ui.u, retention) {
        val k = MS_PER_S / Tok.motion_flingTick * -ln(retention)
        val decay = MicroFling(k, ui.px(Tok.flingStop)).generateDecayAnimationSpec<Float>()
        val cap = ui.px(Tok.flingCap)
        val min = ui.px(Tok.flingMin)
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                val v0 = initialVelocity.coerceIn(-cap, cap)
                if (abs(v0) < min) return 0f
                var last = 0f
                var left = v0
                AnimationState(0f, v0).animateDecay(decay) {
                    val delta = value - last
                    val used = scrollBy(delta)
                    last = value
                    if (abs(delta - used) > 0.5f) cancelAnimation() else left = velocity
                }
                return left
            }
        }
    }
}

@Composable
fun Rows(
    top: Float,
    state: LazyListState = rememberLazyListState(),
    x: Float = 0f,
    scroll: Boolean = true,
    show: Any? = null,
    size: ((Int) -> Float)? = null,
    tail: Int = 0,
    under: WindowInsets? = null,
    head: Int = 0,
    lead: Float = 0f,
    content: LazyListScope.() -> Unit,
) {
    val ui = LocalUi.current
    Box(
        Modifier.offset { IntOffset(ui.px(x).roundToInt(), ui.px(top).roundToInt()) }
            .size((ui.px(ui.w) - ui.px(x)).dp(), (ui.px(ui.h) - ui.px(top)).dp()).clipToBounds(),
    ) {
        val sheet = LocalSheet.current
        val past = if (sheet != null && sheet.pastList === state) sheet.past else 0f
        val scrolling = remember(state) { { state.isScrollInProgress } }
        CompositionLocalProvider(LocalScrolling provides scrolling) {
            LazyColumn(
                Modifier.fillMaxSize().testTag("rows"),
                state = state,
                contentPadding = PaddingValues(bottom = past.dp()),
                flingBehavior = rememberFling(Tok.motion_listFlingRetention),
                userScrollEnabled = scroll,
                content = content,
            )
        }
        val span = remember(state, show) { ListSpan() }
        val density = LocalDensity.current
        val leadPx = ui.px(lead).roundToInt()
        ScrollThumb(show, { state.isScrollInProgress }, { span.of(state, size, tail, under?.getBottom(density) ?: 0, past.roundToInt(), head, leadPx) })
    }
}

@Composable
fun StateLine(text: String, tag: String, onTap: (() -> Unit)? = null) {
    val ui = LocalUi.current
    Box(Modifier.at(0f, ui.px(ui.contentTop))) { StateRow(text, tag, onTap) }
}

@Composable
fun StateRow(text: String, tag: String, onTap: (() -> Unit)? = null) {
    val ui = LocalUi.current
    val h = ui.px(Tok.rowHeight)
    val step = rememberStep()
    val tap = if (onTap == null) Modifier else Modifier.press({ onTap() }, step = step)
    Box(Modifier.size(ui.px(ui.w).dp(), h.dp()).then(tap).testTag(tag)) {
        RoleText(text, Roles.listPrimary, Modifier.at(ui.px(Tok.hubEdge), h / 2, ay = 0.5f), alpha = if (step.on) Tok.alpha_full else Tok.alpha_low)
    }
}

@Stable
class Dip(initial: String) {
    var shown by mutableStateOf(initial)
    val alpha = Animatable(1f)
}

@Composable
fun rememberDip(value: String): Dip {
    val d = remember { Dip(value) }
    LaunchedEffect(value) {
        if (value == d.shown) {
            d.alpha.animateTo(1f, tween(Tok.motion_valueChange.toInt(), easing = LinearEasing))
            return@LaunchedEffect
        }
        d.alpha.animateTo(Tok.motion_valueDip, tween(Tok.motion_valueChange.toInt(), easing = LinearEasing))
        d.shown = value
        d.alpha.animateTo(1f, tween(Tok.motion_valueChange.toInt(), easing = LinearEasing))
    }
    return d
}

fun Modifier.dip(d: Dip) = graphicsLayer { alpha = d.alpha.value }

@Composable
fun ListRow(
    primary: String,
    modifier: Modifier = Modifier,
    secondary: String = "",
    number: String = "",
    trailing: String = "",
    bold: Boolean = false,
    countLine: Boolean = false,
    ring: Boolean = false,
    playing: Boolean = false,
    focused: Boolean = false,
    width: Float? = null,
    onTap: () -> Unit = {},
    onLong: (() -> Unit)? = null,
) {
    val ui = LocalUi.current
    val rowW = width ?: ui.px(ui.w)
    val rowH = ui.px(Tok.rowHeight)
    val indented = number.isNotEmpty() || ring
    val textX = ui.px(Tok.hubEdge + if (indented) Tok.songIndent else 0f)
    val primaryRole = (if (bold) Roles.listPlaylist else Roles.listPrimary).let { if (playing && AccentOff) it.copy(weight = FontWeight.Bold) else it }
    val secondaryRole = if (bold || countLine) Roles.listCount else Roles.listSecondary
    val c by animateColorAsState(
        if (playing) Accent else white(if (focused) primaryRole.alpha else primaryRole.dim),
        tween(Tok.motion_focusAlpha.toInt()),
        label = "row",
    )
    val press = rememberPress()
    Pressable({ onTap() }, modifier.size(rowW.dp(), rowH.dp()), onLong, press = press) {
        if (ring) Ring(Tok.rowRing, GlyphName.PLAY, Look.Focus(focused), Modifier.at(ui.px(Tok.hubEdge), rowH / 2, ay = 0.5f))
        Layout(
            content = {
                RoleText(primary, primaryRole, Modifier.layoutId(ROW_PRIMARY).testTag(ROW_PRIMARY), color = c, glow = if (playing && !AccentOff) null else press.word)
                if (secondary.isNotEmpty()) RoleText(secondary, secondaryRole, Modifier.layoutId(ROW_SECONDARY))
                if (number.isNotEmpty()) RoleText(number, Roles.listCount, Modifier.layoutId(ROW_NUMBER))
                if (trailing.isNotEmpty()) RoleText(trailing, Roles.listCount, Modifier.layoutId(ROW_TRAILING))
            },
            modifier = Modifier.fillMaxSize(),
        ) { m, cons ->
            fun part(id: String) = m.firstOrNull { it.layoutId == id }
            val trailingP = part(ROW_TRAILING)?.measure(Constraints())
            val trailingX = rowW - ui.px(Tok.hubEdge) - (trailingP?.width ?: 0)
            val primaryW = if (trailingP != null) trailingX - ui.px(Tok.gutter) - textX else rowW - ui.px(Tok.hubEdge) - textX
            val p = part(ROW_PRIMARY)!!.measure(Constraints(maxWidth = primaryW.roundToInt().coerceAtLeast(0)))
            val s = part(ROW_SECONDARY)?.measure(Constraints(maxWidth = (rowW - ui.px(Tok.hubEdge) - textX).roundToInt().coerceAtLeast(0)))
            val n = part(ROW_NUMBER)?.measure(Constraints())
            layout(cons.maxWidth, cons.maxHeight) {
                val py = if (s != null) ui.px(Tok.rowTextInset).roundToInt() else ((rowH - p.height) / 2).roundToInt()
                val baseline = py + p[FirstBaseline]
                p.place(textX.roundToInt(), py)
                s?.place(textX.roundToInt(), (rowH - ui.px(Tok.rowTextInset) - s.height).roundToInt())
                n?.place(ui.px(Tok.hubEdge).roundToInt(), baseline - n[FirstBaseline])
                trailingP?.place(trailingX.roundToInt(), baseline - trailingP[FirstBaseline])
            }
        }
    }
}

@Composable
fun SettingsRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    editing: Boolean = false,
    info: Boolean = false,
    sub: Boolean = false,
    swatch: Color? = null,
    eqSlot: Boolean = false,
    onLong: (() -> Unit)? = null,
    onTap: (Float) -> Unit = {},
) {
    val ui = LocalUi.current
    val rowW = ui.px(ui.w)
    val rowH = ui.px(Tok.settingsPitch)
    val titleRole = when {
        sub -> Roles.settingsSub
        info -> Roles.settingsValue
        else -> Roles.pageRowTitle
    }
    val tc by animateColorAsState(white(if (focused) Roles.pageRowTitle.alpha else Roles.pageRowTitle.dim), tween(Tok.motion_focusAlpha.toInt()), label = "title")
    val vc by animateColorAsState(
        if (editing) Accent else white(if (focused) Tok.settingsValueFocused else Tok.settingsValueDim),
        tween(Tok.motion_focusAlpha.toInt()),
        label = "value",
    )
    val dip = rememberDip(value)
    val shownValue = dip.shown
    val measurer = rememberTextMeasurer()
    val t = measurer.measure(shown(title, titleRole.case), textStyle(titleRole, tc))
    val bold = Roles.settingsValue.copy(weight = FontWeight.Bold)
    val valueRole = if (editing && AccentOff) bold else Roles.settingsValue
    val v = measurer.measure(shown(shownValue, valueRole.case), textStyle(valueRole, vc))
    val room = if (AccentOff) measurer.measure(shown(shownValue, bold.case), textStyle(bold, vc)) else v
    val edgeX = ui.px(Tok.hubEdge)
    val right = rowW - edgeX
    val baseline = (rowH - t.size.height) / 2 + t.firstBaseline
    val sw = ui.px(Tok.settingsSwatch)
    val press = rememberPress()
    val glow = if (info) null else press.word
    val step = rememberStep()
    val titleColor = if (step.on) white(Tok.alpha_full) else tc
    Pressable({
        if (!focused && !info) step.hold(Tok.motion_focusAlpha.toLong())
        onTap(it.x / rowW)
    }, modifier.size(rowW.dp(), rowH.dp()), onLong, press = press, step = if (info) null else step) {
        val valueColor = vc
        if (eqSlot) {
            RoleText(title, titleRole, Modifier.atBaseline(edgeX, baseline).testTag("settings.title"), color = titleColor, glow = glow)
            val slotX = edgeX + t.size.width + ui.px(Tok.eqValueGap)
            val slotW = (right - slotX).coerceAtLeast(0f)
            Box(Modifier.at(slotX, 0f).size(slotW.dp(), rowH.dp()).clipToBounds()) {
                RoleText(shownValue, valueRole, Modifier.atBaseline(slotW - v.size.width, baseline).dip(dip), color = valueColor)
            }
        } else {
            val limit = when {
                swatch != null -> right - sw - ui.px(Tok.gutter) - edgeX
                shownValue.isNotEmpty() -> right - room.size.width - ui.px(Tok.gutter) - edgeX
                else -> right - edgeX
            }
            RoleText(title, titleRole, Modifier.atBaseline(edgeX, baseline, limit).testTag("settings.title"), color = titleColor, glow = glow)
            if (swatch != null) {
                Box(Modifier.at(right - sw, (rowH - sw) / 2).size(sw.dp()).background(swatch.copy(alpha = (if (focused) Tok.settingsValueFocused else Tok.settingsValueDim) / Tok.settingsValueFocused)))
            } else if (shownValue.isNotEmpty()) {
                RoleText(shownValue, valueRole, Modifier.atBaseline(right - v.size.width, baseline).dip(dip), color = valueColor)
            }
        }
    }
}

@Composable
fun SearchField(text: String, onChange: (String) -> Unit, tag: String, ime: ImeAction, modifier: Modifier = Modifier, onAction: () -> Unit = {}) {
    val ui = LocalUi.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val act: KeyboardActionScope.() -> Unit = {
        keyboard?.hide()
        focus.clearFocus()
        onAction()
    }
    BasicTextField(
        text,
        onChange,
        Modifier.atBaseline(ui.px(Tok.hubEdge), ui.px(ui.pivotBaseline)).width(ui.px(ui.w - 2 * Tok.hubEdge).dp()).then(modifier).testTag(tag),
        textStyle = textStyle(Roles.listPrimary, white(Roles.listPrimary.alpha)),
        singleLine = true,
        cursorBrush = SolidColor(Accent),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Text, imeAction = ime),
        keyboardActions = KeyboardActions(onDone = act, onSearch = act),
        decorationBox = { inner ->
            Box {
                if (text.isEmpty()) RoleText(PLACEHOLDER, Roles.listPrimary, Modifier, alpha = Tok.alpha_low)
                inner()
            }
        },
    )
}
