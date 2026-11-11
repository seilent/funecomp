package net.seilent.funecomp.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.seilent.funecomp.proto.gen.Tok
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MS_PER_S = 1000f
private const val SWIPE_DX = 10
private const val SWIPE_DY = 14

@Stable
class Entrance {
    val clock = Animatable(Float.MAX_VALUE)
    var dir by mutableIntStateOf(1)

    suspend fun run(dir: Int) {
        this.dir = dir
        clock.snapTo(0f)
        val total = maxOf(Tok.motion_pivotRows, Tok.motion_pivotRowFade)
        clock.animateTo(total, tween(total.toInt(), easing = LinearEasing))
    }

    suspend fun settle() = clock.snapTo(Float.MAX_VALUE)
}

val LocalEntrance = staticCompositionLocalOf<Entrance?> { null }

@Composable
fun Modifier.enter(): Modifier {
    val e = LocalEntrance.current ?: return this
    val shift = LocalUi.current.px(Tok.motion_pivotRowInShift)
    return graphicsLayer {
        val t = e.clock.value
        translationX = shift * e.dir * (1f - EaseOut.transform((t / Tok.motion_pivotRows).coerceIn(0f, 1f)))
        alpha = (t / Tok.motion_pivotRowFade).coerceIn(0f, 1f)
    }
}

@Stable
class PaneDrag(private val scope: CoroutineScope) {
    var x by mutableFloatStateOf(0f)
        private set
    private val back = Animatable(0f)
    internal var land: () -> Unit = {}

    val offset: Float get() = x + back.value

    fun follow(dx: Float) {
        x = dx
    }

    fun release() {
        val from = x
        x = 0f
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            back.snapTo(from)
            back.animateTo(0f, tween(Tok.motion_pivotStrip.toInt(), easing = ExpOut6))
        }
    }

    fun down() {
        if (back.isRunning) scope.launch(start = CoroutineStart.UNDISPATCHED) { back.snapTo(0f) }
        land()
    }

    fun settle() {
        x = 0f
    }
}

fun Modifier.pivotSwipe(slop: Float, swipe: Float, count: Int, active: () -> Int, slots: PressSlots?, pane: PaneDrag, onPivot: (Int) -> Unit) = pointerInput(count, slots, pane) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        pane.down()
        var dx = 0f
        var dy = 0f
        var decided = false
        var lifted = false
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            val c = e.changes.firstOrNull { it.id == down.id } ?: break
            if (!c.pressed) {
                lifted = true
                if (decided) {
                    val t = c.position - down.position
                    val next = active() + if (t.x < 0) 1 else -1
                    if (abs(t.x) > swipe && abs(t.x) * SWIPE_DX > abs(t.y) * SWIPE_DY && next in 0 until count) onPivot(next) else pane.release()
                    c.consume()
                }
                break
            }
            val d = c.positionChange()
            dx += d.x
            dy += d.y
            if (!decided && abs(dx) > slop && abs(dx) > abs(dy)) {
                decided = true
                slots?.cut()
            }
            if (decided) {
                pane.follow(dx)
                c.consume()
            }
            if (!decided && abs(dy) > slop) break
        }
        if (decided && !lifted) pane.release()
    }
}

@Composable
fun HubPage(
    header: String,
    onBack: () -> Unit,
    pivots: List<String> = emptyList(),
    active: Int = 0,
    onPivot: (Int) -> Unit = {},
    count: String? = null,
    keepEnd: Boolean = false,
    content: @Composable (Int) -> Unit,
) {
    val entrance = remember { Entrance() }
    var shownPivot by rememberSaveable { mutableIntStateOf(active) }
    var shownName by rememberSaveable { mutableStateOf(pivots.getOrNull(active)) }
    val out = remember { Animatable(0f) }
    var dir by remember { mutableIntStateOf(1) }
    val scope = rememberCoroutineScope()
    val pane = remember { PaneDrag(scope) }
    val landed = remember { booleanArrayOf(false) }
    var land by remember { mutableIntStateOf(0) }
    pane.land = {
        if (out.isRunning) {
            landed[0] = true
            scope.launch(start = CoroutineStart.UNDISPATCHED) { out.snapTo(1f) }
            land++
        } else if (entrance.clock.isRunning) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) { entrance.settle() }
            land++
        }
    }
    val target = pivots.getOrNull(active)
    val named = pivots.isNotEmpty()
    val shownAt = if (!named) shownPivot else shownName?.let { pivots.indexOf(it) } ?: active
    SideEffect { if (named && shownAt >= 0 && shownAt != shownPivot) shownPivot = shownAt }
    LaunchedEffect(if (named) target else active) {
        val from = if (named) shownName?.let { pivots.indexOf(it) }?.takeIf { it >= 0 } ?: shownPivot else shownPivot
        if (if (named) target == shownName || shownName == null else active == shownPivot) {
            shownPivot = active
            shownName = target
            out.snapTo(0f)
            pane.settle()
            entrance.settle()
            return@LaunchedEffect
        }
        dir = if (active > from) 1 else -1
        landed[0] = false
        try {
            out.animateTo(1f, tween(Tok.motion_pivotOut.toInt(), easing = LinearEasing))
        } catch (e: CancellationException) {
            if (!landed[0] || !isActive) throw e
        }
        shownPivot = active
        shownName = target
        out.snapTo(0f)
        pane.settle()
        if (landed[0]) entrance.settle() else launch { entrance.run(dir) }
    }
    val ui = LocalUi.current
    val current by rememberUpdatedState(active)
    val slots = LocalPressSlots.current
    val swipe = if (pivots.size > 1) Modifier.pivotSwipe(ui.px(Tok.touchSlop), ui.px(Tok.gesture_swipe), pivots.size, { current }, slots, pane, onPivot) else Modifier
    Box(Modifier.fillMaxSize().then(swipe).testTag("hub.$header")) {
        PageSurface()
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                translationX = pane.offset - dir * ui.px(Tok.motion_pivotPaneSpeed * Tok.motion_pivotOut / MS_PER_S) * out.value
                alpha = 1f - out.value
            },
        ) {
            CompositionLocalProvider(LocalEntrance provides entrance) { content(shownAt) }
        }
        CroppedHeader(header, onBack = onBack)
        if (pivots.isNotEmpty()) PivotStrip(pivots, active, onPivot, land)
        if (count != null) {
            val d = rememberDip(count)
            val x = ui.px(Tok.hubEdge)
            val room = ui.px(ui.w) - 2 * x
            val measurer = rememberTextMeasurer()
            if (keepEnd && measurer.measure(shown(d.shown, Roles.listCount.case), textStyle(Roles.listCount, white(Roles.listCount.alpha))).size.width > room) {
                Box(Modifier.at(x, 0f).size(room.dp(), ui.px(ui.h).dp()).clipToBounds()) {
                    RoleText(d.shown, Roles.listCount, Modifier.atBaseline(room, ui.px(ui.pivotBaseline), ax = 1f).dip(d).testTag("count"))
                }
            } else {
                RoleText(d.shown, Roles.listCount, Modifier.atBaseline(x, ui.px(ui.pivotBaseline)).dip(d).testTag("count"))
            }
        }
    }
}

@Composable
fun Home(items: List<HomeItem>, onShuffle: () -> Unit, onOpen: (Page) -> Unit) {
    val ui = LocalUi.current
    var focus by rememberSaveable { mutableIntStateOf(0) }
    val now = LocalHomeReturn.current
    val ret = remember { now }
    val enter = remember { Animatable(0f) }
    val ringEnter = remember { Animatable(0f) }
    var back by remember { mutableFloatStateOf(ret?.let { if (it.scale > 0f) (it.frame - it.outEnd) / it.scale else Float.MAX_VALUE } ?: 0f) }
    val stagger = if (ret != null) 0f else Tok.motion_homeEnterStagger
    fun enterMs() = if (ret != null) back else enter.value
    fun ringMs() = if (ret != null) back else ringEnter.value
    LaunchedEffect(Unit) {
        if (ret != null) {
            val end = maxOf(Tok.motion_homeEnter, Tok.motion_homeEnterPeek)
            if (ret.scale > 0f) while (back < end) withFrameMillis { t -> back = (t - ret.outEnd) / ret.scale }
            return@LaunchedEffect
        }
        launch { ringEnter.animateTo(Tok.motion_homeEnterPeek, tween(Tok.motion_homeEnterPeek.toInt(), easing = LinearEasing)) }
        val total = Tok.motion_homeEnter + Tok.motion_homeEnterStagger * items.size
        enter.animateTo(total, tween(total.toInt(), easing = LinearEasing))
    }
    val music = items.indexOfFirst { it.page == Page.Music }
    val measurer = rememberTextMeasurer()
    val wordStyle = textStyle(Roles.homeItem, white(Roles.homeItem.alpha))
    val ink = remember(items, wordStyle, ui) { items.maxOfOrNull { val t = shown(it.name, Roles.homeItem.case); measurer.measure(t, wordStyle, softWrap = false, maxLines = 1).getPathForRange(0, t.length).getBounds().right } ?: 0f }
    val fit = if (ui.shape == Shape.L) minOf(0f, ui.w - Tok.edge - ui.homeTextX - ink / ui.u) else 0f
    val pitch = ui.homePitch
    val x = ui.px(ui.homeTextX + fit)
    val margin = LayoutTok.homeFirstBaseline - LayoutTok.homeBarTop
    val column = rememberScrollState()
    val scrolling = remember(column) { { column.isScrollInProgress } }
    Box(Modifier.fillMaxSize().testTag("home")) {
        Box(Modifier.fillMaxSize().verticalScroll(column, flingBehavior = rememberFling(Tok.motion_listFlingRetention)).testTag("home.column")) {
            val height = maxOf(ui.h, 2 * margin + items.size * pitch)
            CompositionLocalProvider(LocalScrolling provides scrolling) {
                Box(Modifier.size(ui.px(ui.w).dp(), ui.px(height).dp())) {
                    val barX = ui.homeMenuX + LayoutTok.homeBarX + fit
                    val ring = rememberPress()
                    if (music >= 0) {
                        val left = ui.px(ui.homeMenuX + fit)
                        val top = ui.px(ui.homeSlotTop(music))
                        Box(
                            Modifier.at(left, top)
                                .size((ui.px(ui.homeRingHitRight + fit) - left).dp(), (ui.px(ui.homeRingHitBottom(music)) - top).dp())
                                .graphicsLayer { alpha = homeRingEnter(ringMs()) }
                                .press({ onShuffle() }, press = ring)
                                .testTag("home.ring"),
                        ) {
                            Box(Modifier.at(ui.px(ui.homeRingX + fit) - left, ui.px(ui.homeRingY(music)) - top, 0.5f, 0.5f)) { Ring(LayoutTok.homeRingSize, GlyphName.PLAY, if (ring.held) Look.Touch else Look.Focus(false), press = ring, tag = "home.ring", stroke = LayoutTok.homeRingStroke) }
                        }
                    }
                    items.forEachIndexed { i, item ->
                        val baseline = ui.px(LayoutTok.homeFirstBaseline + i * pitch)
                        val on = focus == i
                        val c by animateColorAsState(white(if (on) Roles.homeItem.alpha else Roles.homeItem.dim), tween(Tok.motion_focusAlpha.toInt(), easing = LinearEasing), label = "home")
                        val subBaseline = baseline + ui.px(LayoutTok.homeSubtitleDrop + Tok.capHeight * Roles.homeSubtitle.size)
                        val top = baseline - ui.px(LayoutTok.homeBarTop)
                        val shiftPx = ui.px(Tok.motion_homeEnterShift)
                        Box(
                            Modifier.fillMaxSize().graphicsLayer {
                                val p = Decel.transform(((enterMs() - stagger * i) / Tok.motion_homeEnter).coerceIn(0f, 1f))
                                translationX = shiftPx * (1f - p)
                                alpha = p
                            },
                        ) {
                            val subW = ui.px(ui.w - Tok.edge) - x - ui.px(LayoutTok.homeSubtitleX)
                            val subStyle = textStyle(Roles.homeSubtitle, white(Roles.homeSubtitle.alpha))
                            val sub = if (item.names.isEmpty()) item.subtitle else remember(item.names, subW, subStyle) { wholeNames(item.names) { measurer.measure(shown(it, Roles.homeSubtitle.case), subStyle, softWrap = false, maxLines = 1).size.width <= subW.roundToInt() } }
                            val press = rememberPress()
                            val step = rememberStep()
                            Pressable(
                                {
                                    step.hold(Held.Nav.motion_turnstileOut.toLong())
                                    onOpen(item.page)
                                },
                                Modifier.at(ui.px(barX), top).size((ui.px(ui.w) - ui.px(barX)).dp(), ui.px(pitch).dp()).testTag("home.${item.name}"),
                                onDown = {
                                    if (focus != i) step.fade(Tok.motion_focusAlpha.toLong())
                                    focus = i
                                },
                                press = press,
                                step = step,
                            ) {
                                val word = when {
                                    i == music && ring.held -> white(Tok.homeRingPressDim)
                                    step.on -> white(Tok.alpha_full)
                                    else -> c
                                }
                                RoleText(item.name, Roles.homeItem, Modifier.atBaseline(x - ui.px(barX), baseline - top), color = word, glow = press.word)
                                RoleText(sub, Roles.homeSubtitle, Modifier.atBaseline(x - ui.px(barX) + ui.px(LayoutTok.homeSubtitleX), subBaseline - top, subW))
                            }
                        }
                    }
                    RoleText("funecomp", Roles.watermark, Modifier.atBaseline(x, ui.px(height - Tok.edge)).testTag("home.watermark"))
                }
            }
        }
    }
}

data class HomeItem(val name: String, val subtitle: String, val page: Page, val names: List<String> = emptyList()) {
    constructor(name: String, page: Page, names: List<String>) : this(name, names.joinToString(" "), page, names)
}

fun wholeNames(names: List<String>, fits: (String) -> Boolean): String {
    var line = names.firstOrNull() ?: return ""
    for (n in names.drop(1)) {
        if (!fits("$line $n")) break
        line = "$line $n"
    }
    return line
}
