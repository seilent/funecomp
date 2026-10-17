package net.seilent.funecomp.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import net.seilent.funecomp.proto.Band
import net.seilent.funecomp.proto.Profile
import net.seilent.funecomp.proto.Row
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tok
import kotlinx.coroutines.delay
import net.seilent.funecomp.state.Rows as RowRules
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

private const val BALANCE_KEY = "balance"
private const val ACCENT_KEY = "accentHue"
private const val EQ_KEY = "eq"
private const val CLOCK_KEY = "dateTime"
private const val HALF_DB = 0.5f
private const val GRID_CENTER = 34
private const val GRID_STEPS_PER_OCTAVE = 6f
private const val GRID_REF_HZ = 1000f
private const val FLAT_FIRST_GRID = 4
private const val FLAT_GRID_STEP = 6
private const val FLAT_Q100 = 140
private const val MILLI = 1000

fun Row.word(v: String?): String {
    if (v == null) return words.firstOrNull() ?: ""
    val i = values.indexOf(v)
    if (i >= 0 && i < words.size) return words[i]
    if (kind == Enums.RowKind.steps) {
        val n = v.toIntOrNull()
        if (n != null) {
            val near = values.mapNotNull { it.toIntOrNull() }.minByOrNull { abs(it - n) }
            val j = values.indexOf(near?.toString())
            if (j in words.indices) return words[j]
        }
    }
    return v
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

fun Row.step(v: String?, dir: Int): String? {
    if (kind == Enums.RowKind.range) {
        val lo = min ?: 0
        val st = (step ?: 1).coerceAtLeast(1)
        val at = (((v?.toIntOrNull() ?: lo) - lo).toFloat() / st).roundToInt()
        val count = max?.let { (it - lo) / st + 1 }
        val next = if (key == ACCENT_KEY && count != null) ((at + dir) % count + count) % count else at + dir
        return (lo + next * st).coerceIn(lo, max ?: Int.MAX_VALUE).toString()
    }
    if (values.isEmpty()) return v
    val i = values.indexOf(v).let { if (it < 0) 0 else it }
    val j = i + dir
    if (key == ACCENT_KEY) return values[(j % values.size + values.size) % values.size]
    if (kind == Enums.RowKind.toggle) return values[(j % values.size + values.size) % values.size]
    return values[j.coerceIn(0, values.size - 1)]
}

fun draftKind(key: String?): Int? =
    if (key == CLOCK_KEY) Enums.DraftKind.clock else Enums.DraftKind.names.entries.firstOrNull { it.value == key }?.key

private class Edit(val key: String, val original: String?, val draft: Boolean)

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

@Composable
fun SettingsPage(ctx: Ctx) {
    val ui = LocalUi.current
    val rows = ctx.device.schema.rows.filter { it.kind != Enums.RowKind.action || it.actionCmd() != null }
    val pivots = rows.mapNotNull { it.pivot }.distinct()
    var pivot by rememberSaveable { mutableIntStateOf(0) }
    val values = ctx.device.settings.values
    var edit by remember { mutableStateOf<Edit?>(null) }
    var editValue by remember { mutableStateOf<String?>(null) }
    val eqOn = ctx.device.has("eq")
    fun confirm() {
        val e = edit ?: return
        val v = editValue
        if (e.draft && v != null && v != e.original) {
            val kind = draftKind(e.key)
            if (kind == Enums.DraftKind.sleep) ctx.control.draft(kind, minutes = v.toLongOrNull()) else if (kind != null) ctx.control.draft(kind, value = v)
        }
        edit = null
    }
    fun revert() {
        val e = edit ?: return
        if (!e.draft && e.original != null && editValue != e.original) ctx.control.set(e.key, e.original)
        edit = null
    }
    BackHandler(edit != null) { confirm() }
    val back = {
        if (edit != null) revert() else ctx.nav.pop()
        Unit
    }
    HubPage("settings", back, pivots, pivot, {
        revert()
        pivot = it
    }) { shown ->
        val active = pivots.getOrNull(shown)
        val shownRows = rows.filter { it.pivot == active }
        var focus by rememberSaveable(active) { mutableIntStateOf(0) }
        Box(Modifier.fillMaxSize()) {
            Rows(ui.contentTop, scroll = edit == null, focus = focus) {
                itemsIndexed(shownRows, key = { i, r -> r.key ?: "row$i" }) { i, r ->
                    val key = r.key ?: ""
                    val editing = edit?.key == key
                    val v = if (editing) editValue else values[key]
                    val eqRow = key == EQ_KEY && eqOn
                    SettingsRow(
                        r.label ?: key,
                        if (eqRow) profileName(ctx.device, activeEqOutput(ctx.device)) else r.display(v),
                        Modifier.enter { i }.testTag("set.$key"),
                        focused = focus == i,
                        editing = editing,
                        info = r.kind == Enums.RowKind.info && !eqRow,
                        swatch = if (key == ACCENT_KEY) accentFor(v?.toIntOrNull()) else null,
                    ) { x ->
                        if (edit != null && !editing) {
                            confirm()
                            return@SettingsRow
                        }
                        focus = i
                        when {
                            editing -> {
                                val next = r.step(editValue, if (x < 0.5f) -1 else 1)
                                if (next != editValue) {
                                    editValue = next
                                    if (r.kind != Enums.RowKind.draft && next != null) ctx.control.set(key, next)
                                }
                            }
                            eqRow -> ctx.nav.push(Page.Eq(activeEqOutput(ctx.device)))
                            key == CLOCK_KEY -> ctx.sheet.open("date/time", listOf("set from phone")) {
                                val now = System.currentTimeMillis()
                                ctx.control.draft(Enums.DraftKind.clock, unixS = now / MILLI, zoneS = java.util.TimeZone.getDefault().getOffset(now) / MILLI)
                            }
                            r.kind == Enums.RowKind.action -> r.actionCmd()?.let { ctx.control.action(it) }
                            r.editable() -> {
                                edit = Edit(key, values[key] ?: r.values.firstOrNull(), r.kind == Enums.RowKind.draft)
                                editValue = values[key] ?: r.values.firstOrNull()
                            }
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
        ctx.sheet.open("profile", names.map { it.name ?: it.key }, keys.indexOf(key).coerceAtLeast(0)) { ctx.control.eqSet(output, key = keys[it]) }
    }
    lines += EqLine("import", "import autoeq", "", false) { autoEqImport(ctx) }
    if (key == "custom") {
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
                            FIELD_TYPE -> ctx.sheet.open("type", TYPE_LONG.map { it.second }, TYPE_LONG.indexOfFirst { it.first == band.type }.coerceAtLeast(0)) {
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
                l.id == "profile" || l.id == "preamp" || l.id == "import" -> "eq.${l.id}"
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
fun Entry(start: String, unit: String = "", secret: Boolean = false, done: (String) -> Unit) {
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
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { done(text) }),
        )
        if (unit.isNotEmpty()) RoleText(" $unit", Roles.dialogItem, Modifier, alpha = Roles.dialogItem.dim)
    }
}
