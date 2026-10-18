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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import kotlinx.coroutines.launch
import net.seilent.funecomp.proto.gen.Tok
import kotlin.math.abs

@Stable
class Entrance {
    val clock = Animatable(Float.MAX_VALUE)
    var dir by mutableIntStateOf(1)

    suspend fun run(dir: Int) {
        this.dir = dir
        clock.snapTo(0f)
        val total = Tok.motion_pivotRows + Tok.motion_pivotRowStagger * Tok.motion_pivotRowCap
        clock.animateTo(total, tween(total.toInt(), easing = LinearEasing))
    }

    suspend fun settle() = clock.snapTo(Float.MAX_VALUE)
}

val LocalEntrance = staticCompositionLocalOf<Entrance?> { null }

@Composable
fun Modifier.enter(index: () -> Int): Modifier {
    val e = LocalEntrance.current ?: return this
    val shift = LocalUi.current.px(Tok.motion_pivotShift)
    return graphicsLayer {
        val delay = Tok.motion_pivotRowStagger * minOf(index().coerceAtLeast(0), Tok.motion_pivotRowCap.toInt())
        val p = Strip.transform(((e.clock.value - delay) / Tok.motion_pivotRows).coerceIn(0f, 1f))
        translationX = shift * e.dir * (1f - p)
        alpha = p
    }
}

fun Modifier.pivotSwipe(slop: Float, count: Int, active: () -> Int, onPivot: (Int) -> Unit) = pointerInput(count) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var dx = 0f
        var dy = 0f
        var decided = false
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            val c = e.changes.firstOrNull { it.id == down.id } ?: break
            if (!c.pressed) break
            val d = c.positionChange()
            dx += d.x
            dy += d.y
            if (!decided && abs(dx) > slop && abs(dx) > abs(dy)) {
                decided = true
                val next = active() + if (dx < 0) 1 else -1
                if (next in 0 until count) onPivot(next)
            }
            if (decided) c.consume()
            if (!decided && abs(dy) > slop) break
        }
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
    content: @Composable (Int) -> Unit,
) {
    var shift by remember { mutableFloatStateOf(0f) }
    val entrance = remember { Entrance() }
    var shownPivot by rememberSaveable { mutableIntStateOf(active) }
    val out = remember { Animatable(0f) }
    var dir by remember { mutableIntStateOf(1) }
    LaunchedEffect(active) {
        if (active == shownPivot) {
            out.snapTo(0f)
            entrance.settle()
            return@LaunchedEffect
        }
        dir = if (active > shownPivot) 1 else -1
        out.animateTo(1f, tween(Tok.motion_pivotOut.toInt(), easing = Strip))
        shownPivot = active
        out.snapTo(0f)
        launch { entrance.run(dir) }
    }
    val ui = LocalUi.current
    val current by rememberUpdatedState(active)
    val swipe = if (pivots.size > 1) Modifier.pivotSwipe(ui.px(Tok.touchSlop), pivots.size, { current }, onPivot) else Modifier
    Box(Modifier.fillMaxSize().then(swipe).testTag("hub.$header")) {
        PageSurface()
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                translationX = -dir * ui.px(Tok.motion_pivotShift) * out.value
                alpha = 1f - out.value
            },
        ) {
            CompositionLocalProvider(LocalEntrance provides entrance) { content(shownPivot) }
        }
        CroppedHeader(header, shift, onBack)
        if (pivots.isNotEmpty()) PivotStrip(pivots, active, onPivot) { shift = it }
        if (count != null) {
            val d = rememberDip(count)
            RoleText(d.shown, Roles.listCount, Modifier.atBaseline(ui.px(Tok.hubEdge), ui.px(ui.pivotBaseline)).dip(d).testTag("count"))
        }
    }
}

@Composable
fun Home(items: List<HomeItem>, onOpen: (Page) -> Unit) {
    val ui = LocalUi.current
    var focus by rememberSaveable { mutableIntStateOf(0) }
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        val total = Tok.motion_homeEnter + Tok.motion_homeEnterStagger * items.size
        enter.animateTo(total, tween(total.toInt(), easing = LinearEasing))
    }
    val measurer = rememberTextMeasurer()
    val pitch = ui.homePitch
    val x = ui.px(ui.homeTextX)
    val margin = Tok.homeFirstBaseline - Tok.homeBarTop
    Box(Modifier.fillMaxSize().testTag("home")) {
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("home.column")) {
            val height = maxOf(ui.h, 2 * margin + items.size * pitch)
            Box(Modifier.size(ui.px(ui.w).dp(), ui.px(height).dp())) {
                val barX = ui.homeTextX - Tok.focus_barOffset
                val first = ui.px(Tok.homeFirstBaseline)
                FocusBar(ui.px(Tok.homeBarH), ui.px(barX), first + ui.px(focus * pitch) - ui.px(Tok.homeBarTop))
                items.forEachIndexed { i, item ->
                    val baseline = ui.px(Tok.homeFirstBaseline + i * pitch)
                    val on = focus == i
                    val c by animateColorAsState(white(if (on) Roles.homeItem.alpha else Roles.homeItem.dim), tween(Tok.motion_focusAlpha.toInt()), label = "home")
                    val subBaseline = baseline + ui.px(Tok.homeSubtitleDrop + Tok.capHeight * Roles.homeSubtitle.size)
                    val top = baseline - ui.px(Tok.homeBarTop)
                    val shiftPx = ui.px(Tok.motion_homeEnterShift)
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            val p = Decel.transform(((enter.value - Tok.motion_homeEnterStagger * i) / Tok.motion_homeEnter).coerceIn(0f, 1f))
                            translationX = shiftPx * (1f - p)
                            alpha = p
                        },
                    ) {
                        val labelW = measurer.measure(shown(item.name, Roles.homeItem.case), textStyle(Roles.homeItem, c)).size.width
                        Pressable(
                            {
                                focus = i
                                onOpen(item.page)
                            },
                            Modifier.at(ui.px(barX), top).size((x - ui.px(barX) + labelW).dp(), ui.px(pitch).dp()).testTag("home.${item.name}"),
                        ) {
                            RoleText(item.name, Roles.homeItem, Modifier.atBaseline(x - ui.px(barX), baseline - top), color = c)
                            RoleText(item.subtitle, Roles.homeSubtitle, Modifier.atBaseline(x - ui.px(barX) + ui.px(Tok.homeSubtitleX), subBaseline - top, ui.px(ui.w - Tok.edge) - x - ui.px(Tok.homeSubtitleX)))
                        }
                    }
                }
                RoleText("funecomp", Roles.watermark, Modifier.atBaseline(x, ui.px(height - Tok.edge)))
            }
        }
    }
}

data class HomeItem(val name: String, val subtitle: String, val page: Page)
