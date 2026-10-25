package net.seilent.funecomp.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row as HRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import net.seilent.funecomp.state.Rows as RowRules
import net.seilent.funecomp.state.SleepLeft
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

private const val BALANCE_KEY = "balance"
private const val ACCENT_KEY = "accentHue"
private const val EQ_KEY = "eq"
private const val CLOCK_KEY = "dateTime"
private const val SLEEP_KEY = "sleep"
private const val HALF_DB = 0.5f
private const val GRID_CENTER = 34
private const val GRID_STEPS_PER_OCTAVE = 6f
private const val GRID_REF_HZ = 1000f
private const val FLAT_FIRST_GRID = 4
private const val FLAT_GRID_STEP = 6
private const val FLAT_Q100 = 140
private const val MILLI = 1000
private const val TYPE_VALUE = "type value"
private const val ABOUT_KEY = "about"
private const val SYSTEM = "system"
private const val CREDIT = "seilent"
private const val UPDATE_FIRMWARE = "update firmware"

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

fun Row.display(v: String?): String = when (kind) {
    Enums.RowKind.range -> {
        val n = v?.toIntOrNull() ?: 0
        if (key == BALANCE_KEY) balanceText(n) else if (key == ACCENT_KEY) "" else n.toString()
    }
    Enums.RowKind.info, Enums.RowKind.action -> words.firstOrNull() ?: ""
    Enums.RowKind.draft -> if (values.isEmpty() || words.size != values.size) words.firstOrNull() ?: "" else word(v ?: values.first())
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

fun draftKind(key: String?): Int? =
    if (key == CLOCK_KEY) Enums.DraftKind.clock else Enums.DraftKind.names.entries.firstOrNull { it.value == key }?.key

private class Edit(val key: String, val original: String?)

class Selector(val items: List<String>, val focus: Int, val info: List<String>, val typed: Boolean)

sealed class Send {
    data class Set(val key: String, val value: String) : Send()
    data class Draft(val kind: Int, val value: String) : Send()
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
    if (kind == Enums.RowKind.draft) return draftKind(key)?.let { Send.Draft(it, v) }
    return key?.let { Send.Set(it, v) }
}

fun Row.refusal() = Send.Refuse((label ?: key ?: "").lowercase(), "${min ?: 0} to ${max ?: min ?: 0}")

fun Row.typedMinutes(text: String, minutes: Int): Send? {
    val lo = min ?: 0
    val hi = max ?: lo
    val t = text.trim()
    val n = if (t.isNotEmpty() && t.all { it in '0'..'9' }) t.toIntOrNull() else null
    if (n == null || n !in lo..hi) return refusal()
    return if (n == 0 && minutes == 0) null else Send.Minutes(n.toLong())
}

fun Row.actionCmd(): Int? = Enums.ActionCmd.names.entries.firstOrNull { it.value == key }?.key

fun activeEqOutput(device: net.seilent.funecomp.state.DeviceState) =
    if (device.playback.state.output == Enums.Output.btHeadset) Enums.EqOutput.bt else Enums.EqOutput.wired

fun profileName(device: net.seilent.funecomp.state.DeviceState, output: Int): String {
    val eq = device.eq[output]
    return when (val k = eq?.key ?: "off") {
        "off" -> "off"
        "custom" -> "custom"
        else -> eq?.names?.firstOrNull { it.key == k }?.name ?: k.substringAfterLast('/').removeSuffix(".txt")
    }
}

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

fun aboutSheet(ctx: Ctx, device: () -> DeviceState, firmware: () -> FirmwareView) {
    ctx.sheet.open(
        ABOUT_KEY,
        listOf(UPDATE_FIRMWARE),
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
        itemValues = { listOf(firmwareText(firmware())) },
    ) { ctx.nav.push(Page.Firmware) }
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
    fun minutesLeft() = SleepLeft.minutes(live.sleepLeftMs(SystemClock.elapsedRealtime()))
    fun send(s: Send?, row: Row) {
        when (s) {
            is Send.Set -> ctx.control.set(s.key, s.value)
            is Send.Draft -> ctx.control.draft(s.kind, value = s.value)
            is Send.Minutes -> ctx.control.draft(Enums.DraftKind.sleep, minutes = s.minutes) { code, _ ->
                if (code == Err.badValue) row.refusal().let { ctx.toast.show(it.field, it.range) }
            }
            is Send.Refuse -> ctx.toast.show(s.field, s.range)
            null -> {}
        }
    }
    fun typeMinutes(r: Row, token: Any) {
        val hint = minutesLeft().toString()
        ctx.sheet.openEntry((r.label ?: r.key ?: "").lowercase(), owner = token) {
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
            if (sel.typed && i == sel.items.lastIndex) typeMinutes(r, token) else send(r.picked(i, live.settings.values[key], minutesLeft()), r)
        }
    }
    fun confirm() {
        edit = null
    }
    fun revert() {
        val e = edit ?: return
        if (e.original != null && editValue != e.original) ctx.control.set(e.key, e.original)
        edit = null
    }
    BackHandler(edit != null) { confirm() }
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
            Rows(ui.contentTop, list, scroll = edit == null, focus = focus, focusKey = shownRows.getOrNull(focus)?.key, rebuild = shownRows.map { it.key }) {
                itemsIndexed(shownRows, key = { i, r -> r.key ?: "row$i" }) { i, r ->
                    val key = r.key ?: ""
                    val editing = edit?.key == key
                    val v = if (editing) editValue else values[key]
                    val eqRow = key == EQ_KEY && eqOn
                    val aboutRow = key == ABOUT_KEY
                    val sleepRow = r.sleep()
                    val minutes = if (sleepRow) sleepMinutes(ctx.device) else 0
                    SettingsRow(
                        r.label ?: key,
                        when {
                            eqRow -> profileName(ctx.device, activeEqOutput(ctx.device))
                            aboutRow -> ctx.device.hello?.version ?: ""
                            sleepRow -> sleepText(r, minutes)
                            else -> r.display(v)
                        },
                        Modifier.enter { i }.testTag("set.$key"),
                        focused = focus == i,
                        editing = editing,
                        info = r.kind == Enums.RowKind.info && !eqRow && !aboutRow,
                        swatch = if (key == ACCENT_KEY) accentFor(v?.toIntOrNull()) else null,
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
                                    ctx.control.set(key, next)
                                }
                            }
                            eqRow -> ctx.nav.push(Page.Eq(activeEqOutput(ctx.device)))
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

fun flatBand(i: Int): Band {
    val grid = FLAT_FIRST_GRID + FLAT_GRID_STEP * i
    return Band(Enums.BandType.peak, grid, (gridHz(grid) * MILLI).roundToInt(), 0, FLAT_Q100)
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
    val ui = LocalUi.current
    val outputs = listOf(Enums.EqOutput.wired to "headphones", Enums.EqOutput.bt to "bluetooth")
    var pivot by rememberSaveable { mutableIntStateOf(outputs.indexOfFirst { it.first == page.output }.coerceAtLeast(0)) }
    HubPage("eq", { ctx.nav.pop() }, outputs.map { it.second }, pivot, { pivot = it }) { shown ->
        EqRows(ctx, outputs[shown].first)
    }
}

@Composable
private fun EqRows(ctx: Ctx, output: Int) {
    val ui = LocalUi.current
    val eq = ctx.device.eq[output]
    val key = eq?.key ?: "off"
    val ints = eq?.ints ?: Profile(0, emptyList())
    var open by rememberSaveable(output) { mutableIntStateOf(-1) }
    var focus by rememberSaveable(output) { mutableStateOf("profile") }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    fun bands() = (0 until Tok.eq_bands.toInt()).map { ints.bands.getOrNull(it) }
    fun send(p: Profile) = ctx.control.eqSet(output, ints = p)
    fun setBand(i: Int, b: Band) = send(Profile(ints.preamp10, bands().mapIndexed { j, x -> if (j == i) b else x ?: flatBand(j) }))
    val lines = ArrayList<EqLine>()
    fun typedAt(id: String, title: String, start: String, min: Float, max: Float, unit: String, done: (Float) -> Unit) {
        val index = lines.indexOfFirst { it.id == id }
        if (index >= 0) scope.launch { state.animateScrollToItem(index) }
        typed(ctx, title, start, min, max, unit, done)
    }
    lines += EqLine("profile", "profile", profileName(ctx.device, output), false) {
        val names = eq?.names.orEmpty()
        val keys = names.map { it.key }
        ctx.sheet.open("profile", names.map { it.name ?: it.key }, keys.indexOf(key).coerceAtLeast(0), keep = Keep(state, lines.indexOfFirst { it.id == "profile" }, ui.px(ui.contentTop))) { ctx.control.eqSet(output, key = keys[it]) }
    }
    val entry = eq?.names?.firstOrNull { it.key == key }
    val presets = ctx.device.has("eqPresets")
    if (presets && key == "custom") {
        lines += EqLine("save", SAVE_PRESET, "", false) {
            ctx.control.eqPreset(Enums.EqPresetOp.save, output, null, null, null, false) { code, _ ->
                when (code) {
                    null -> focus = "delete"
                    Err.noSpace -> ctx.toast.show(TOO_MANY, "")
                    else -> ctx.toast.show(NOT_SAVED, "")
                }
            }
        }
    }
    if (presets && entry?.preset == true) {
        val name = entry.name ?: key
        lines += EqLine("delete", DELETE_PRESET, "", false) {
            ctx.sheet.open("$DELETE_PRESET?", listOf(DELETE_PRESET, KEEP_PRESET), 1) {
                if (it == 0) ctx.control.eqPreset(Enums.EqPresetOp.remove, null, key, null, null, false) { code, _ ->
                    if (code == null) focus = "profile" else ctx.toast.show(NOT_DELETED, name)
                }
            }
        }
        fun rename(start: String) {
            ctx.sheet.openEntry(RENAME_PRESET) {
                TextEntry(start) { text ->
                    when {
                        text == name -> ctx.sheet.close()
                        !AutoEq.validName(text) -> ctx.toast.show(RENAME_PRESET, NAME_NOT_ALLOWED)
                        else -> {
                            ctx.sheet.close()
                            ctx.control.eqPreset(Enums.EqPresetOp.rename, null, key, text, null, false) { code, _ ->
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
    lines += EqLine("import", "import autoeq", "", false) { autoEqImport(ctx, output) }
    if (key == "custom" || entry != null && key != "off") {
        lines += EqLine("preamp", "preamp", signed(ints.preamp10 / 10f, false) + " db", false) {
            typedAt("preamp", "preamp", signed(ints.preamp10 / 10f, false), Tok.eq_preampMin, Tok.eq_preampMax, "db") { v ->
                send(Profile((v * 10).roundToInt(), bands().mapIndexed { j, b -> b ?: flatBand(j) }))
            }
        }
        bands().forEachIndexed { i, b ->
            lines += EqLine("band$i", "band ${i + 1}", bandText(b), false) { open = if (open == i) -1 else i }
            if (open == i) {
                val band = b ?: flatBand(i)
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
                            FIELD_TYPE -> ctx.sheet.open("type", TYPE_LONG.map { it.second }, TYPE_LONG.indexOfFirst { it.first == band.type }.coerceAtLeast(0), keep = Keep(state, lines.indexOfFirst { it.id == id }, ui.px(ui.contentTop))) {
                                setBand(i, band.copy(type = TYPE_LONG[it].first))
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
    }
    val fieldTags = listOf("type", "frequency", "gain", "q")
    Rows(ui.contentTop, state, focus = lines.indexOfFirst { it.id == focus }) {
        itemsIndexed(lines, key = { _, l -> l.id }) { i, l ->
            val tag = when {
                l.id in setOf("profile", "save", "delete", "rename", "import", "preamp") -> "eq.${l.id}"
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

fun typed(ctx: Ctx, title: String, start: String, min: Float, max: Float, unit: String, done: (Float) -> Unit) {
    ctx.sheet.openEntry(title) { Entry(start, unit = unit) { text ->
        val v = text.trim().replace(',', '.').toFloatOrNull()
        ctx.sheet.close()
        if (v != null) done(v.coerceIn(min, max))
    } }
}

@Composable
fun TextEntry(start: String, secret: Boolean = false, done: (String) -> Unit) = Entry(start, secret = secret, done = done)

@Composable
fun Entry(start: String, unit: String = "", secret: Boolean = false, keyboard: KeyboardType = KeyboardType.Ascii, hint: String = "", done: (String) -> Unit) {
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
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
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
