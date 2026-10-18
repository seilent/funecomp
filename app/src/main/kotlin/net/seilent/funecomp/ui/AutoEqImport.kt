package net.seilent.funecomp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import net.seilent.funecomp.artist.AutoEqList
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.state.AutoEq
import net.seilent.funecomp.state.AutoEqResult
import net.seilent.funecomp.state.Clamp
import net.seilent.funecomp.state.Section
import kotlin.math.roundToInt

private const val TITLE = "import autoeq"
private const val FILE = "file"
private const val SEARCH = "search"
private const val CANCEL = "cancel"
private const val NO_PRESETS = "device has no eq presets"
private const val HEADER = "autoeq"
private const val LOADING = "loading"
private const val FAILED = "search failed"
private const val NO_RESULTS = "no results"
private const val PLACEHOLDER = "type to search"
private const val IME_KEY = "ime"

fun clampText(c: Clamp): String {
    val band = if (c.filter == AutoEq.PREAMP) "" else "band ${c.filter} "
    return band + when (c.field) {
        AutoEq.FIELD_FC -> "fc ${freqText(c.from)} to ${freqText(c.to)}"
        AutoEq.FIELD_Q -> "q ${qText((c.from * 100).roundToInt())} to ${qText((c.to * 100).roundToInt())}"
        AutoEq.FIELD_GAIN -> "gain ${signed(c.from, true)} to ${signed(c.to, true)}"
        else -> "preamp ${signed(c.from, true)} to ${signed(c.to, true)}"
    }
}

fun autoEqLines(r: AutoEqResult): List<String> = when (r) {
    is AutoEqResult.Refused -> listOf(r.reason)
    is AutoEqResult.Ok -> buildList {
        val eq = r.eq
        add("preamp ${signed(eq.preamp, false)} db")
        add(if (eq.filters.size == 1) "1 band" else "${eq.filters.size} bands")
        eq.clamps.forEach { add(clampText(it)) }
        if (!AutoEq.validName(eq.name)) add("invalid name")
        add(NO_PRESETS)
    }
}

fun autoEqImport(ctx: Ctx) = ctx.sheet.open(TITLE, listOf(FILE, SEARCH)) { i ->
    if (i == 0) ctx.control.eqFile { name, text -> autoEqConfirm(ctx, AutoEq.name(name), text) } else ctx.nav.push(Page.AutoEq)
}

@Composable
fun AutoEqPage(ctx: Ctx, ime: WindowInsets) {
    val ui = LocalUi.current
    val query = rememberSaveable { mutableStateOf("") }
    var focus by rememberSaveable { mutableStateOf("") }
    var grid by rememberSaveable { mutableStateOf<Int?>(null) }
    var letters by remember { mutableStateOf(emptySet<Int>()) }
    var list by remember { mutableStateOf<AutoEqList?>(null) }
    var failed by remember { mutableStateOf(false) }
    var tries by remember { mutableIntStateOf(0) }
    LaunchedEffect(tries) {
        ctx.control.autoEqList { l ->
            list = l
            failed = l == null
        }
    }
    var view by remember { mutableStateOf<AutoEqList.View?>(null) }
    val state = rememberLazyListState()
    LaunchedEffect(list) {
        val l = list ?: return@LaunchedEffect
        snapshotFlow { query.value }.collectLatest { q ->
            val v = ctx.control.autoEqView(l, q)
            if (v.items != view?.items) {
                view = v
                state.scrollToItem(0)
            }
        }
    }
    LaunchedEffect(grid, view) {
        val v = view
        if (grid != null && v != null) letters = v.letters
    }
    val scope = rememberCoroutineScope()
    BackHandler(grid != null) { grid = null }
    Box(Modifier.fillMaxSize()) {
        HubPage(HEADER, { ctx.nav.pop() }) {
            val shown = view
            when {
                failed -> StateLine(FAILED) {
                    failed = false
                    tries++
                }
                shown == null -> StateLine(LOADING)
                shown.items.isEmpty() -> StateLine(NO_RESULTS)
                else -> Rows(ui.contentTop, state, focus = shown.index[focus] ?: -1) {
                    items(shown.items, contentType = { it is AutoEqList.Divider }) { item ->
                        when (item) {
                            is AutoEqList.Divider -> DividerRow(item.letter) {
                                letters = shown.letters
                                grid = item.letter
                            }
                            is AutoEqList.Hit -> {
                                val e = item.row.entry
                                ListRow(e.name, Modifier.testTag("aeq.row.${e.name}"), e.source, focused = focus == item.key, onTap = {
                                    focus = item.key
                                    ctx.control.autoEqProfile(e) { text -> autoEqConfirm(ctx, AutoEq.name(e.name), text) }
                                })
                            }
                        }
                    }
                    item(IME_KEY, IME_KEY) { Spacer(Modifier.windowInsetsBottomHeight(ime)) }
                }
            }
            SearchField(query)
        }
        if (list != null) {
            JumpGrid(letters, grid ?: JUMP_NONE, grid != null, { letter ->
                view?.index?.get(AutoEqList.Divider(letter).key)?.let { scope.launch { state.scrollToItem(it) } }
                grid = null
            }) { grid = null }
        }
    }
}

@Composable
private fun StateLine(text: String, onTap: (() -> Unit)? = null) {
    val ui = LocalUi.current
    val h = ui.px(Tok.rowHeight)
    val tap = if (onTap == null) Modifier else Modifier.press({ onTap() })
    Box(Modifier.at(0f, ui.px(ui.contentTop)).size(ui.px(ui.w).dp(), h.dp()).then(tap).testTag("aeq.state")) {
        RoleText(text, Roles.listPrimary, Modifier.at(ui.px(Tok.hubEdge), h / 2, ay = 0.5f), alpha = Tok.alpha_low)
    }
}

@Composable
private fun DividerRow(letter: Int, onTap: () -> Unit) {
    val ui = LocalUi.current
    val h = ui.px(Tok.rowHeight)
    val l = Section.letter(letter)
    Box(Modifier.size(ui.px(ui.w).dp(), h.dp()).press({ onTap() }).testTag("aeq.div.$l")) {
        RoleText(l, Roles.letterGlyph, Modifier.atBaseline(ui.px(Tok.hubEdge), h - ui.px(Tok.letterInsetBaseline)))
    }
}

@Composable
private fun SearchField(query: MutableState<String>) {
    val ui = LocalUi.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    BasicTextField(
        query.value,
        { query.value = it },
        Modifier.atBaseline(ui.px(Tok.hubEdge), ui.px(ui.pivotBaseline)).width(ui.px(ui.w - 2 * Tok.hubEdge).dp()).testTag("aeq.field"),
        textStyle = textStyle(Roles.listPrimary, white(Roles.listPrimary.alpha)),
        singleLine = true,
        cursorBrush = SolidColor(Accent),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            keyboard?.hide()
            focus.clearFocus()
        }),
        decorationBox = { inner ->
            Box {
                if (query.value.isEmpty()) RoleText(PLACEHOLDER, Roles.listPrimary, Modifier, alpha = Tok.alpha_low)
                inner()
            }
        },
    )
}

fun autoEqConfirm(ctx: Ctx, name: String, text: String?) {
    val r = if (text == null) AutoEqResult.Refused("not readable") else AutoEq.parse(text, name)
    ctx.sheet.open(name.ifEmpty { TITLE }, listOf(CANCEL), info = autoEqLines(r)) {}
}
