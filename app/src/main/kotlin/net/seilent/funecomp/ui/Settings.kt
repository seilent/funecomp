package net.seilent.funecomp.ui

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row as HRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import net.seilent.funecomp.proto.Band
import net.seilent.funecomp.proto.Profile
import net.seilent.funecomp.proto.Row
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Tok
import kotlinx.coroutines.delay
import net.seilent.funecomp.state.AutoEq
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.state.EqDefault
import net.seilent.funecomp.state.EqForm
import net.seilent.funecomp.state.EqSlots
import net.seilent.funecomp.state.EqStage
import net.seilent.funecomp.state.Rows as RowRules
import net.seilent.funecomp.state.SleepLeft
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

private const val BALANCE_KEY = "balance"
private const val ACCENT_KEY = "accentHue"
private const val ACCENT_OFF_KEY = "accentOff"
private const val EQ_KEY = "eq"
private const val EQ_EAR_KEY = "eqEar"
private const val CLOCK_KEY = "dateTime"
private const val SLEEP_KEY = "sleep"
private const val BT_CODEC_KEY = "btCodec"
private const val BT_CODEC_HEADPHONES_KEY = "btCodecHeadphones"
private const val REFUSED_ON_DAP = "refused on the dap"
private const val HALF_DB = 0.5f
private const val GRID_CENTER = 34
private const val GRID_STEPS_PER_OCTAVE = 6f
private const val GRID_REF_HZ = 1000f
private const val ADDED_GRID = 34
private const val ADDED_MILLI_HZ = 1_000_000
private const val ADDED_Q100 = 140
private val ADDED_BAND = Band(Enums.BandType.peak, ADDED_GRID, ADDED_MILLI_HZ, 0, ADDED_Q100)
private const val MILLI = 1000
private const val TYPE_VALUE = "type value"
private const val ABOUT_KEY = "about"
private const val SYSTEM = "system"
private const val CREDIT = "seilent"
private const val UPDATE_FIRMWARE = "update firmware"
private const val SUPPORT_ME = "support me"
private const val ABOUT_SHEET = "about.sheet"

fun Row.word(v: String?): String {
    if (v == null) return words.firstOrNull() ?: ""
    val i = values.indexOf(v)
    if (i >= 0 && i < words.size) return words[i]
    if (kind == Enums.RowKind.steps) {
        val n = v.toIntOrNull()
        if (n != null) {
            val j = values.indexOf(nearest(n)?.toString())
            if (j in words.indices) return words[j]
        }
    }
    return v
}

fun Row.nearest(n: Int): Int? = values.mapNotNull { it.toIntOrNull() }.minByOrNull { abs(it - n) }

fun sleepText(row: Row, minutes: Int): String = if (minutes > 0) "$minutes min left" else row.display(null)

fun sleepStart(row: Row, minutes: Int): String? = row.nearest(minutes)?.toString()

@Composable
fun sleepMinutes(device: DeviceState): Int {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(device.playback.receivedAt, device.playback.state.sleepLeftS) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = SystemClock.elapsedRealtime()
                val left = device.sleepLeftMs(now)
                if (left <= 0) break
                delay(SleepLeft.nextDropMs(left) + 1)
            }
        }
    }
    return SleepLeft.minutes(device.sleepLeftMs(now))
}

fun balanceText(v: Int): String {
    if (v == 0) return "center"
    val db = abs(v) * HALF_DB
    val n = if (db % 1f == 0f) db.toInt().toString() else db.toString()
    return (if (v < 0) "l " else "r ") + n + " db"
}

fun Row.display(v: String?, live: String? = null): String = when (kind) {
    Enums.RowKind.range -> {
        val n = v?.toIntOrNull() ?: 0
        if (key == BALANCE_KEY) balanceText(n) else if (key == ACCENT_KEY) "" else n.toString()
    }
    Enums.RowKind.info, Enums.RowKind.action -> live ?: words.firstOrNull() ?: ""
    Enums.RowKind.draft -> if (values.isEmpty() || words.size != values.size) live ?: words.firstOrNull() ?: "" else word(v ?: values.first())
    else -> if (key == ACCENT_KEY) "" else word(v)
}

fun Row.editable() = !RowRules.readOnly(this) && when (kind) {
    Enums.RowKind.choice, Enums.RowKind.steps, Enums.RowKind.toggle, Enums.RowKind.range -> true
    Enums.RowKind.draft -> values.isNotEmpty()
    else -> false
}

fun Row.step(v: String?, dir: Int): String {
    val lo = min ?: 0
    val st = (step ?: 1).coerceAtLeast(1)
    val at = (((v?.toIntOrNull() ?: lo) - lo).toFloat() / st).roundToInt()
    val count = max?.let { (it - lo) / st + 1 }
    val next = if (key == ACCENT_KEY && count != null) ((at + dir) % count + count) % count else at + dir
    return (lo + next * st).coerceIn(lo, max ?: Int.MAX_VALUE).toString()
}

fun draftKind(key: String?): Pair<Int, Int?>? = when (key) {
    CLOCK_KEY -> Enums.DraftKind.clock to null
    BT_CODEC_KEY -> Enums.DraftKind.btCodec to Enums.BtRole.receiver
    BT_CODEC_HEADPHONES_KEY -> Enums.DraftKind.btCodec to Enums.BtRole.headphones
    else -> Enums.DraftKind.names.entries.firstOrNull { it.value == key }?.let { it.key to null }
}

private class Edit(val key: String, val original: String?)

class Selector(val items: List<String>, val focus: Int, val info: List<String>, val typed: Boolean)

sealed class Send {
    data class Set(val key: String, val value: String) : Send()
    data class Draft(val kind: Int, val value: String, val role: Int? = null) : Send()
    data class Minutes(val minutes: Long) : Send()
    data class Refuse(val field: String, val range: String) : Send()
}

private fun Row.sleep() = key == SLEEP_KEY && kind == Enums.RowKind.draft

fun Row.selects() = editable() && kind != Enums.RowKind.range

fun Row.selector(live: String?, minutes: Int): Selector {
    val items = values.map { word(it) }
    if (!sleep()) return Selector(items, values.indexOf(live).coerceAtLeast(0), emptyList(), false)
    val typed = max?.let { it > (min ?: 0) } == true
    val info = if (minutes > 0) listOf(sleepText(this, minutes)) else emptyList()
    return Selector(if (typed) items + TYPE_VALUE else items, values.indexOf(sleepStart(this, minutes)).coerceAtLeast(0), info, typed)
}

fun Row.picked(i: Int, live: String?, minutes: Int): Send? {
    val v = values.getOrNull(i) ?: return null
    if (sleep()) {
        val m = v.toLongOrNull() ?: return null
        return if (m == 0L && minutes == 0) null else Send.Minutes(m)
    }
    if (v == live) return null
    if (kind == Enums.RowKind.draft) return draftKind(key)?.let { (k, role) -> Send.Draft(k, v, role) }
    return key?.let { Send.Set(it, v) }
}

fun Row.refusal() = Send.Refuse((label ?: key ?: "").lowercase(), "${min ?: 0} to ${max ?: min ?: 0}")

fun Row.refused(code: Int?) = if (code == Err.refused) Send.Refuse((label ?: key ?: "").lowercase(), REFUSED_ON_DAP) else null

fun Row.typedMinutes(text: String, minutes: Int): Send? {
    val lo = min ?: 0
    val hi = max ?: lo
    val t = text.trim()
    val n = if (t.isNotEmpty() && t.all { it in '0'..'9' }) t.toIntOrNull() else null
    if (n == null || n !in lo..hi) return refusal()
    return if (n == 0 && minutes == 0) null else Send.Minutes(n.toLong())
}

fun Row.actionCmd(): Int? = Enums.ActionCmd.names.entries.firstOrNull { it.value == key }?.key

private class Opened(val token: Any, val row: Row)

private data class Spot(val key: String?, val index: Int)

private class Drawn(var pivot: String? = null, var rows: List<Row> = emptyList())

private val SpotsSaver = listSaver<Map<String, Spot>, Any>(
    save = { m -> m.flatMap { (p, s) -> listOf(p, s.key ?: "", s.index) } },
    restore = { l -> l.chunked(3).associate { (p, k, i) -> p as String to Spot((k as String).ifEmpty { null }, i as Int) } },
)

private fun Row.same(o: Row) = values == o.values && words == o.words && flags == o.flags && kind == o.kind && min == o.min && max == o.max

fun settingsRows(rows: List<Row>): List<Row> {
    val shown = rows.filter { it.kind != Enums.RowKind.action || it.actionCmd() != null }
    if (shown.isEmpty() || shown.any { it.key == ABOUT_KEY }) return shown
    return shown + Row(ABOUT_KEY, "ABOUT", SYSTEM, Enums.RowKind.info)
}

fun settingsPivots(rows: List<Row>) = settingsRows(rows).mapNotNull { it.pivot }.distinct()

private class AboutItem(val title: String, val value: () -> String = { "" }, val act: () -> Unit)

private fun aboutItems(ctx: Ctx, updater: Boolean?, installer: Boolean?, firmware: () -> FirmwareView) = buildList {
    if (updater != false) add(AboutItem(UPDATE_FIRMWARE, { firmwareText(firmware()) }) { ctx.nav.push(Page.Firmware) })
    if (installer != false) add(AboutItem(INSTALL_APP) { ctx.nav.push(Page.Apps) })
    add(AboutItem(SUPPORT_ME) { ctx.control.openSupport() })
}

fun aboutSheet(ctx: Ctx, device: () -> DeviceState, firmware: () -> FirmwareView) {
    val items = aboutItems(ctx, device().updater, device().installer, firmware)
    ctx.sheet.open(
        ABOUT_KEY,
        items.map { it.title },
        owner = ABOUT_SHEET,
        infoLines = {
            val h = device().hello
            if (h == null) listOf(CREDIT) else listOf("funeOS ${h.version ?: ""}".trim(), CREDIT, "board", "dap battery", "card")
        },
        infoValues = {
            val d = device()
            val h = d.hello
            val st = d.playback.state
            if (h == null) emptyList() else listOf("", "", h.board ?: "", if (st.battery != null) batteryText(st) else "", cardText(st) ?: "")
        },
        infoMixed = { if (device().hello == null) listOf(true) else listOf(true, true) },
        itemValues = { items.map { it.value() } },
    ) { items[it].act() }
}

@Composable
fun SettingsPage(ctx: Ctx, firmware: FirmwareView = FirmwareView()) {
    val ui = LocalUi.current
    val rows = settingsRows(ctx.device.schema.rows)
    val pivots = rows.mapNotNull { it.pivot }.distinct()
    val fw by rememberUpdatedState(firmware)
    var pivotName by rememberSaveable { mutableStateOf<String?>(null) }
    var pivotIndex by rememberSaveable { mutableIntStateOf(0) }
    val pivot = pivots.indexOf(pivotName).takeIf { it >= 0 } ?: pivotIndex.coerceIn(0, maxOf(0, pivots.lastIndex))
    SideEffect {
        pivots.getOrNull(pivot)?.let { if (it != pivotName) pivotName = it }
        if (pivots.isNotEmpty() && pivot != pivotIndex) pivotIndex = pivot
    }
    var spots by rememberSaveable(stateSaver = SpotsSaver) { mutableStateOf(emptyMap<String, Spot>()) }
    val values = ctx.device.settings.values
    var edit by remember { mutableStateOf<Edit?>(null) }
    var editValue by remember { mutableStateOf<String?>(null) }
    val live by rememberUpdatedState(ctx.device)
    val shownPivotName by rememberUpdatedState(pivots.getOrNull(pivot))
    var opened by remember { mutableStateOf<Opened?>(null) }
    var serial by remember { mutableIntStateOf(0) }
    val eqOn = ctx.device.has("eq")
    LaunchedEffect(ctx.device.schema.rows) {
        edit?.let { e -> if (live.schema.rows.none { it.key == e.key && it.pivot == shownPivotName }) edit = null }
        val o = opened ?: return@LaunchedEffect
        val now = live.schema.rows.firstOrNull { it.key == o.row.key }
        if (now == null || !now.same(o.row)) {
            ctx.sheet.closeIf(o.token)
            opened = null
        }
    }
    LaunchedEffect(ctx.device.updater, ctx.device.installer) { if (ctx.sheet.items != aboutItems(ctx, live.updater, live.installer, { fw }).map { it.title }) ctx.sheet.closeIf(ABOUT_SHEET) }
    fun minutesLeft() = SleepLeft.minutes(live.sleepLeftMs(SystemClock.elapsedRealtime()))
    fun send(s: Send?, row: Row) {
        when (s) {
            is Send.Set -> ctx.control.set(s.key, s.value) { code -> send(row.refused(code), row) }
            is Send.Draft -> ctx.control.draft(s.kind, value = s.value, role = s.role) { code, _ -> send(row.refused(code), row) }
            is Send.Minutes -> ctx.control.draft(Enums.DraftKind.sleep, minutes = s.minutes) { code, _ ->
                if (code == Err.badValue) row.refusal().let { ctx.toast.show(it.field, it.range) }
            }
            is Send.Refuse -> ctx.toast.show(s.field, s.range)
            null -> {}
        }
    }
    fun typeMinutes(r: Row, token: Any, keep: Keep) {
        val hint = minutesLeft().toString()
        ctx.sheet.openEntry((r.label ?: r.key ?: "").lowercase(), owner = token, keep = keep) {
            Entry("", unit = r.words.lastOrNull()?.substringAfterLast(' ', "")?.lowercase().orEmpty(), keyboard = KeyboardType.Number, hint = hint) { text ->
                val s = r.typedMinutes(text, minutesLeft())
                if (s !is Send.Refuse) ctx.sheet.close()
                send(s, r)
            }
        }
    }
    fun select(r: Row, keep: Keep) {
        val key = r.key ?: return
        val token = key to ++serial
        val sel = r.selector(values[key], minutesLeft())
        opened = Opened(token, r)
        ctx.sheet.open((r.label ?: key).lowercase(), sel.items, sel.focus, sel.info, owner = token, keep = keep) { i ->
            if (sel.typed && i == sel.items.lastIndex) typeMinutes(r, token, keep) else send(r.picked(i, live.settings.values[key], minutesLeft()), r)
        }
    }
    fun confirm() {
        val e = edit
        val v = editValue
        if (e != null && e.key == ACCENT_KEY && v != null && v != e.original) {
            if (live.settings.values[ACCENT_OFF_KEY] == "on") ctx.control.set(listOf(e.key to v, ACCENT_OFF_KEY to "off")) else ctx.control.set(e.key, v)
        }
        edit = null
    }
    fun revert() {
        val e = edit ?: return
        if (e.key != ACCENT_KEY && e.original != null && editValue != e.original) ctx.control.set(e.key, e.original)
        edit = null
    }
    BackHandler(edit != null) { if (edit?.key == ACCENT_KEY) revert() else confirm() }
    val back = {
        if (edit != null) revert() else ctx.nav.pop()
        Unit
    }
    HubPage("settings", back, pivots, pivot, {
        revert()
        pivotName = pivots.getOrNull(it)
        pivotIndex = it
    }) { shown ->
        val drawn = remember { Drawn() }
        val name = pivots.getOrNull(shown)
        val active = name ?: drawn.pivot
        val shownRows = if (name != null) rows.filter { it.pivot == name } else if (shown < 0) drawn.rows else emptyList()
        val spot = active?.let { spots[it] }
        val byKey = spot?.key?.let { k -> shownRows.indexOfFirst { it.key == k } } ?: -1
        val focus = when {
            byKey >= 0 -> byKey
            shownRows.isEmpty() -> -1
            else -> (spot?.index ?: 0).coerceIn(0, shownRows.lastIndex)
        }
        SideEffect {
            if (name != null) {
                drawn.pivot = name
                drawn.rows = shownRows
                if (focus >= 0) Spot(shownRows[focus].key, focus).let { if (spots[name] != it) spots = spots + (name to it) }
            }
        }
        val list = rememberLazyListState()
        Box(Modifier.fillMaxSize()) {
            Rows(ui.contentTop, list, scroll = edit == null, show = name, tail = if (edit != null) 1 else 0) {
                itemsIndexed(shownRows, key = { i, r -> r.key ?: "row$i" }) { i, r ->
                    val key = r.key ?: ""
                    val editing = edit?.key == key
                    val v = if (editing) editValue else values[key]
                    val eqStage = when {
                        !eqOn -> null
                        key == EQ_KEY -> EqStage.USER
                        key == EQ_EAR_KEY && ctx.device.eqForm == EqForm.STAGES -> EqStage.CORRECTION
                        else -> null
                    }
                    val eqRow = eqStage != null
                    val aboutRow = key == ABOUT_KEY
                    val sleepRow = r.sleep()
                    val minutes = if (sleepRow) sleepMinutes(ctx.device) else 0
                    SettingsRow(
                        r.label ?: key,
                        when {
                            eqStage != null -> EqSlots.value(ctx.device.playingEqSlot(eqStage)?.let { ctx.device.eq[it] })
                            aboutRow -> ctx.device.hello?.version ?: ""
                            sleepRow -> sleepText(r, minutes)
                            else -> r.display(v, ctx.device.liveWord(key))
                        },
                        Modifier.enter { i }.testTag("set.$key"),
                        focused = focus == i,
                        editing = editing,
                        info = r.kind == Enums.RowKind.info && !eqRow && !aboutRow,
                        swatch = if (key != ACCENT_KEY) null else if (editing && v != edit?.original) accentFor(v?.toIntOrNull()) else if (AccentOff) white(Tok.alpha_full) else accentFor(v?.toIntOrNull()),
                    ) { x ->
                        if (edit != null && !editing) {
                            confirm()
                            return@SettingsRow
                        }
                        active?.let { spots = spots + (it to Spot(r.key, i)) }
                        when {
                            editing -> {
                                val next = r.step(editValue, if (x < 0.5f) -1 else 1)
                                if (next != editValue) {
                                    editValue = next
                                    if (key != ACCENT_KEY) ctx.control.set(key, next)
                                }
                            }
                            eqStage != null -> ctx.nav.push(Page.Eq(ctx.device.playingEqSlot(eqStage)?.let { EqSlots.output(it) } ?: ctx.device.playingEqOutput, eqStage))
                            key == CLOCK_KEY -> ctx.sheet.open("date/time", listOf("set from phone")) {
                                val now = System.currentTimeMillis()
                                ctx.control.draft(Enums.DraftKind.clock, unixS = now / MILLI, zoneS = java.util.TimeZone.getDefault().getOffset(now) / MILLI)
                            }
                            r.kind == Enums.RowKind.action -> r.actionCmd()?.let { ctx.control.action(it) }
                            aboutRow -> aboutSheet(ctx, { live }, { fw })
                            !r.editable() -> {}
                            r.kind == Enums.RowKind.range -> {
                                edit = Edit(key, values[key])
                                editValue = values[key]
                            }
                            r.selects() -> select(r, Keep(list, i, ui.px(ui.contentTop)))
                        }
                    }
                }
                if (edit != null) item(key = "confirm") { Box(Modifier.fillParentMaxSize().press({ confirm() }).testTag("set.confirm")) }
            }
        }
    }
}

fun gridHz(grid: Int): Float = (GRID_REF_HZ * 2f.pow((grid - GRID_CENTER) / GRID_STEPS_PER_OCTAVE)).coerceIn(Tok.eq_freqMin, Tok.eq_freqMax)

fun Band.hz(): Float = if (grid >= 0) gridHz(grid) else milliHz / MILLI.toFloat()

fun signed(v: Float, plus: Boolean): String {
    val r = (v * 10f).roundToInt() / 10f
    val s = String.format(Locale.ROOT, "%.1f", abs(r))
    return if (r < 0) "-$s" else if (plus && r > 0) "+$s" else s
}

fun freqText(f: Float): String {
    val r = f.roundToInt()
    if (r < MILLI) return "$r hz"
    val tenths = (f / 100f).roundToInt()
    return (if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}") + " khz"
}

fun qText(q100: Int) = String.format(Locale.ROOT, "%.2f", q100 / 100f)

private val TYPE_SHORT = mapOf(Enums.BandType.peak to "pk", Enums.BandType.lowShelf to "ls", Enums.BandType.highShelf to "hs")
private val TYPE_LONG = listOf(Enums.BandType.off to "off", Enums.BandType.peak to "peak", Enums.BandType.lowShelf to "low shelf", Enums.BandType.highShelf to "high shelf")

fun bandText(b: Band?): String {
    if (b == null || b.type == Enums.BandType.off) return "off"
    return "${TYPE_SHORT[b.type] ?: "pk"} ${freqText(b.hz())} ${signed(b.gain10 / 10f, true)} db q ${qText(b.q100)}"
}

private const val FIELD_TYPE = 0
private const val FIELD_FREQ = 1
private const val FIELD_GAIN = 2
private const val FIELD_Q = 3
private val FIELDS = listOf("type", "frequency", "gain", "q")
private const val DELETE_PRESET = "delete preset"
private const val RENAME_PRESET = "rename preset"
private const val KEEP_PRESET = "keep preset"
private const val NOT_DELETED = "preset not deleted"
private const val NOT_RENAMED = "preset not renamed"

private class EqLine(val id: String, val title: String, val value: String, val sub: Boolean, val tap: () -> Unit)

@Composable
fun EqPage(ctx: Ctx, page: Page.Eq) {
    val slots = ctx.device.eqSlots.filter { EqSlots.stage(it) == page.stage }
    val labels = ctx.device.hello?.eqSlots.orEmpty().associate { it.key to it.label }
    val pivots = slots.map { s ->
        when (s) {
            EqSlots.HEADPHONES, EqSlots.EAR_HEADPHONES -> "headphones"
            EqSlots.BLUETOOTH, EqSlots.EAR_BLUETOOTH -> "bluetooth"
            else -> (labels[s] ?: s).lowercase()
        }
    }
    var pivot by rememberSaveable { mutableIntStateOf(slots.indexOf(EqSlots.of(page.stage, page.output)).coerceAtLeast(0)) }
    HubPage(if (page.stage == EqStage.CORRECTION) "correction eq" else "eq", { ctx.nav.pop() }, pivots, pivot, { pivot = it }) { shown ->
        slots.getOrNull(shown)?.let { EqRows(ctx, it) }
    }
}

@Composable
private fun EqRows(ctx: Ctx, slot: String) {
    val ui = LocalUi.current
    val device = ctx.device
    val eq = device.eq[slot]
    val graphics = device.has("eqGraphic") && device.eqForm != EqForm.OUTPUT
    val graphic = graphics && eq?.graphic == true
    val key = eq?.key ?: "off"
    val ints = eq?.ints ?: Profile(0, emptyList())
    val token = remember(slot) { Any() }
    CloseOnNewTarget(ctx, slot, token)
    var open by rememberSaveable(slot) { mutableIntStateOf(-1) }
    var focus by rememberSaveable(slot) { mutableStateOf(if (device.eqForm == EqForm.STAGES) "enable" else if (graphics) "mode" else "profile") }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val headset = eq?.headset?.takeIf { slot == EqSlots.EAR_BLUETOOTH }
    val pickTitle = if (headset != null) "profile $headset" else null
    val bands = ints.bands
    fun send(p: Profile) = ctx.control.eqSet(slot, ints = p)
    fun setBand(i: Int, b: Band) = send(Profile(ints.preamp10, bands.mapIndexed { j, x -> if (j == i) b else x }))
    val lines = ArrayList<EqLine>()
    fun keep(id: String) = Keep(state, lines.indexOfFirst { it.id == id }, ui.px(ui.contentTop))
    fun typedAt(id: String, title: String, start: String, min: Float, max: Float, unit: String, done: (Float) -> Unit) {
        val index = lines.indexOfFirst { it.id == id }
        if (index >= 0) scope.launch { state.animateScrollToItem(index) }
        typed(ctx, title, start, min, max, unit, token, done)
    }
    if (device.eqForm == EqForm.STAGES) {
        val title = if (EqSlots.stage(slot) == EqStage.CORRECTION) "correction eq" else "eq"
        val on = eq?.on != false
        lines += EqLine("enable", title, if (on) "on" else "off", false) {
            ctx.sheet.open(title, listOf(ON, OFF), if (on) 0 else 1, owner = token, keep = keep("enable")) { if ((it == 0) != on) ctx.control.eqSet(slot, on = it == 0) }
        }
    }
    if (graphics) {
        lines += EqLine("mode", "mode", if (graphic) "graphic" else "parametric", false) {
            ctx.sheet.open("mode", listOf("PARAMETRIC", "GRAPHIC"), if (graphic) 1 else 0, owner = token, keep = keep("mode")) {
                ctx.control.eqSet(slot, mode = if (it == 1) Enums.EqMode.graphic else Enums.EqMode.parametric)
            }
        }
    }
    val presets = device.has("eqPresets")
    if (graphic) {
        val gkey = eq?.graphicKey ?: "off"
        val gnames = eq?.graphicNames.orEmpty()
        lines += EqLine("preset", "preset", EqSlots.presetText(eq), false) {
            val keys = gnames.map { it.key }
            ctx.sheet.open(pickTitle ?: "preset", gnames.map { it.name ?: it.key }, keys.indexOf(gkey).coerceAtLeast(0), owner = token, keep = keep("preset"), itemMixed = { keys.map { '/' in it } }) {
                ctx.control.eqSet(slot, mode = Enums.EqMode.graphic, graphicKey = keys[it])
            }
        }
        val gentry = gnames.firstOrNull { it.key == gkey }
        if (presets && gkey == "custom") {
            lines += EqLine("save", SAVE_PRESET, "", false) {
                ctx.control.eqPreset(Enums.EqPresetOp.save, slot, null, null, null, false, Enums.EqMode.graphic) { code, _ ->
                    when (code) {
                        null -> focus = "delete"
                        Err.staleRev -> {}
                        Err.noSpace -> ctx.toast.show(TOO_MANY, "")
                        else -> ctx.toast.show(NOT_SAVED, "")
                    }
                }
            }
        }
        if (presets && gentry?.preset == true) presetRows(ctx, lines, token, gkey, gentry.name ?: gkey, Enums.EqMode.graphic) { focus = it }
        val gains = eq?.gains
        val shown = gains != null && gkey != "off" && gentry != null && (gkey != EqSlots.DEFAULT || eq.defaultForm() == EqDefault.GRAPHIC)
        if (shown) {
            lines += EqLine("gpreamp", "preamp", signed(gains!!.preamp10 / 10f, false) + " db", false) {}
            gains.bands.forEachIndexed { i, b ->
                lines += EqLine("gband$i", freqText(gridHz(b.grid)), signed(b.gain10 / 10f, true) + " db", false) {
                    typedAt("gband$i", freqText(gridHz(b.grid)), signed(b.gain10 / 10f, true), Tok.eq_gainMin, Tok.eq_gainMax, "db") { v ->
                        val edited = gains.bands.mapIndexed { j, x -> if (j == i) x.copy(gain10 = (v * 10).roundToInt()) else x }
                        ctx.control.eqSet(slot, mode = Enums.EqMode.graphic, gains = edited)
                    }
                }
            }
        }
    } else {
        lines += EqLine("profile", "profile", EqSlots.profileText(eq), false) {
            val names = eq?.names.orEmpty()
            val keys = names.map { it.key }
            ctx.sheet.open(pickTitle ?: "profile", names.map { it.name ?: it.key }, keys.indexOf(key).coerceAtLeast(0), owner = token, keep = keep("profile"), itemMixed = { keys.map { '/' in it } }) {
                ctx.control.eqSet(slot, key = keys[it])
            }
        }
        val entry = eq?.names?.firstOrNull { it.key == key }
        if (presets && key == "custom") {
            lines += EqLine("save", SAVE_PRESET, "", false) {
                ctx.control.eqPreset(Enums.EqPresetOp.save, slot, null, null, null, false) { code, _ ->
                    when (code) {
                        null -> focus = "delete"
                        Err.staleRev -> {}
                        Err.noSpace -> ctx.toast.show(TOO_MANY, "")
                        else -> ctx.toast.show(NOT_SAVED, "")
                    }
                }
            }
        }
        if (presets && entry?.preset == true) presetRows(ctx, lines, token, key, entry.name ?: key, null) { focus = it }
        lines += EqLine("import", "import autoeq", "", false) { autoEqImport(ctx, slot, token) }
        val listed = if (key == EqSlots.DEFAULT) eq?.defaultForm() == EqDefault.CUSTOM else key == "custom" || entry != null && key != "off"
        if (listed) {
            lines += EqLine("preamp", "preamp", signed(ints.preamp10 / 10f, false) + " db", false) {
                typedAt("preamp", "preamp", signed(ints.preamp10 / 10f, false), Tok.eq_preampMin, Tok.eq_preampMax, "db") { v ->
                    send(Profile((v * 10).roundToInt(), bands))
                }
            }
            bands.forEachIndexed { i, band ->
                lines += EqLine("band$i", "${i + 1}", bandText(band), false) { open = if (open == i) -1 else i }
                if (open == i) {
                    FIELDS.indices.forEach { f ->
                        val id = "band$i.$f"
                        val value = when (f) {
                            FIELD_TYPE -> TYPE_LONG.firstOrNull { it.first == band.type }?.second ?: "off"
                            FIELD_FREQ -> freqText(band.hz())
                            FIELD_GAIN -> signed(band.gain10 / 10f, true) + " db"
                            else -> qText(band.q100)
                        }
                        lines += EqLine(id, FIELDS[f], value, true) {
                            when (f) {
                                FIELD_TYPE -> {
                                    val types = TYPE_LONG.filter { t -> device.hello?.bandTypes.orEmpty().let { it.isEmpty() || t.first in it } }
                                    ctx.sheet.open("type", types.map { it.second }, types.indexOfFirst { it.first == band.type }.coerceAtLeast(0), owner = token, keep = keep(id)) {
                                        setBand(i, band.copy(type = types[it].first))
                                    }
                                }
                                FIELD_FREQ -> typedAt(id, "frequency", band.hz().roundToInt().toString(), Tok.eq_freqMin, Tok.eq_freqMax, "hz") { v ->
                                    setBand(i, band.copy(grid = -1, milliHz = (v * MILLI).roundToInt()))
                                }
                                FIELD_GAIN -> typedAt(id, "gain", signed(band.gain10 / 10f, true), Tok.eq_gainMin, Tok.eq_gainMax, "db") { v ->
                                    setBand(i, band.copy(gain10 = (v * 10).roundToInt()))
                                }
                                else -> typedAt(id, "q", qText(band.q100), Tok.eq_qMin, Tok.eq_qMax, "") { v ->
                                    setBand(i, band.copy(q100 = (v * 100).roundToInt()))
                                }
                            }
                        }
                    }
                }
            }
            if (bands.size < Tok.eq_bands.toInt()) {
                lines += EqLine("add", "add band", "", false) {
                    open = bands.size
                    focus = "band${bands.size}"
                    send(Profile(ints.preamp10, bands + ADDED_BAND))
                }
            }
            if (bands.size > 1) {
                lines += EqLine("remove", "remove band", "${bands.size}", false) {
                    if (open == bands.lastIndex) open = -1
                    if (bands.size == 2) focus = "add"
                    send(Profile(ints.preamp10, bands.dropLast(1)))
                }
            }
        }
    }
    val fieldTags = listOf("type", "frequency", "gain", "q")
    Rows(ui.contentTop, state, show = slot) {
        itemsIndexed(lines, key = { _, l -> l.id }) { i, l ->
            val tag = when {
                l.id in setOf("enable", "mode", "profile", "preset", "save", "delete", "rename", "import", "preamp", "gpreamp", "add", "remove") -> "eq.${l.id}"
                l.id.startsWith("gband") -> "eq.gband.${l.id.removePrefix("gband")}"
                l.sub -> "eq.band.${l.id.removePrefix("band").substringBefore('.')}.${fieldTags[l.id.substringAfter('.').toInt()]}"
                else -> "eq.band.${l.id.removePrefix("band")}"
            }
            val m = if (l.sub) Modifier.enterSub(l.id.substringAfter('.').toInt()) else Modifier.enter { i }
            SettingsRow(if (l.sub) l.title.uppercase() else l.title, l.value, m.testTag(tag), focused = focus == l.id, sub = l.sub, eqSlot = true) {
                focus = l.id
                l.tap()
            }
        }
    }
}

@Composable
fun CloseOnNewTarget(ctx: Ctx, slot: String, token: Any) {
    val target = ctx.device.eq[slot]?.target
    var seen by remember(slot) { mutableStateOf(target) }
    LaunchedEffect(target) {
        if (target != seen) {
            ctx.sheet.closeIf(token)
            seen = target
        }
    }
}

private fun presetRows(ctx: Ctx, lines: MutableList<EqLine>, token: Any, key: String, name: String, mode: Int?, focus: (String) -> Unit) {
    lines += EqLine("delete", DELETE_PRESET, "", false) {
        ctx.sheet.open("$DELETE_PRESET?", listOf(DELETE_PRESET, KEEP_PRESET), 1, owner = token) {
            if (it == 0) ctx.control.eqPreset(Enums.EqPresetOp.remove, null, key, null, null, false, mode) { code, _ ->
                if (code == null) focus(if (mode == null) "profile" else "preset") else ctx.toast.show(NOT_DELETED, name)
            }
        }
    }
    fun rename(start: String) {
        ctx.sheet.openEntry(RENAME_PRESET, token) {
            TextEntry(start) { text ->
                when {
                    text == name -> ctx.sheet.close()
                    !AutoEq.validName(text) -> ctx.toast.show(RENAME_PRESET, NAME_NOT_ALLOWED)
                    else -> {
                        ctx.sheet.close()
                        ctx.control.eqPreset(Enums.EqPresetOp.rename, null, key, text, null, false, mode) { code, _ ->
                            when (code) {
                                null -> {}
                                Err.exists, Err.badValue -> {
                                    ctx.toast.show(RENAME_PRESET, if (code == Err.exists) NAME_TAKEN else NAME_NOT_ALLOWED)
                                    rename(text)
                                }
                                else -> ctx.toast.show(NOT_RENAMED, name)
                            }
                        }
                    }
                }
            }
        }
    }
    lines += EqLine("rename", RENAME_PRESET, "", false) { rename(name) }
}

@Composable
fun Modifier.enterSub(index: Int): Modifier {
    val ui = LocalUi.current
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((Tok.motion_listChangeStagger * minOf(index, Tok.motion_listChangeCap.toInt())).toLong())
        t.animateTo(1f, tween(Tok.motion_listChange.toInt(), easing = Decel))
    }
    val shift = ui.px(Tok.motion_listChangeShift)
    return graphicsLayer {
        translationX = shift * (1f - t.value)
        alpha = t.value
    }
}

fun typed(ctx: Ctx, title: String, start: String, min: Float, max: Float, unit: String, owner: Any? = null, done: (Float) -> Unit) {
    ctx.sheet.openEntry(title, owner) { Entry(start, unit = unit) { text ->
        val v = text.trim().replace(',', '.').toFloatOrNull()
        ctx.sheet.close()
        if (v != null) done(v.coerceIn(min, max))
    } }
}

@Composable
fun TextEntry(start: String, secret: Boolean = false, done: (String) -> Unit) = if (secret) SecretEntry(start, done) else Entry(start, done = done)

@Composable
fun SecretEntry(start: String, done: (String) -> Unit) {
    val ui = LocalUi.current
    val role = Roles.dialogItem
    val size = ui.fontPx(role.size)
    val tracking = ui.px(Tok.tracking) / size
    val color = Accent.toArgb()
    val face = LocalFontFamilyResolver.current.resolve(Selawik, role.weight).value as Typeface
    val cursor = with(LocalDensity.current) { CURSOR.roundToPx() }
    val drawable = remember(color, cursor) {
        GradientDrawable().apply {
            setColor(color)
            setSize(cursor, 0)
        }
    }
    val latest by rememberUpdatedState(done)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AndroidView(
        factory = { c ->
            object : EditText(c) {
                override fun getAutofillType() = View.AUTOFILL_TYPE_NONE
            }.apply {
                tag = "entry"
                background = null
                setPadding(0, 0, 0, 0)
                includeFontPadding = false
                inputType = SECRET_INPUT
                imeOptions = SECRET_IME
                transformationMethod = Dots
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                isSaveEnabled = false
                setText(start)
                setOnEditorActionListener { v, action, event ->
                    if (if (event == null) action == EditorInfo.IME_ACTION_DONE else event.action == KeyEvent.ACTION_DOWN) {
                        val text = v.text.toString()
                        v.clearFocus()
                        latest(text)
                    }
                    event != null || action == EditorInfo.IME_ACTION_DONE
                }
                setOnFocusChangeListener { v, has -> if (has) v.windowInsetsController?.show(WindowInsets.Type.ime()) }
                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {}

                    override fun onViewDetachedFromWindow(v: View) {
                        v.context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0)
                    }
                })
            }
        },
        modifier = Modifier.focusRequester(focus).testTag("entry"),
        onRelease = { it.text.clear() },
        update = {
            it.typeface = face
            it.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
            it.letterSpacing = tracking
            it.setTextColor(color)
            it.minWidth = cursor
            it.textCursorDrawable = drawable
        },
    )
}

const val SECRET_INPUT = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
const val SECRET_IME = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_FULLSCREEN
private val CURSOR = 2.dp

private object Dots : PasswordTransformationMethod() {
    override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
}

@Composable
fun Entry(start: String, unit: String = "", keyboard: KeyboardType = KeyboardType.Ascii, hint: String = "", done: (String) -> Unit) {
    var text by remember { mutableStateOf(start) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val style = textStyle(Roles.dialogItem, Accent)
    HRow(verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            text,
            { text = it },
            Modifier.focusRequester(focus).width(IntrinsicSize.Min).testTag("entry"),
            textStyle = style,
            singleLine = true,
            cursorBrush = SolidColor(Accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { done(text) }),
            decorationBox = { field ->
                Box {
                    if (text.isEmpty() && hint.isNotEmpty()) RoleText(hint, Roles.dialogItem, Modifier, alpha = Tok.alpha_low)
                    field()
                }
            },
        )
        if (unit.isNotEmpty()) RoleText(" $unit", Roles.dialogItem, Modifier, alpha = Roles.dialogItem.dim)
    }
}
