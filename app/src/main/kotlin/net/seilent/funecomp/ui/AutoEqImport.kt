package net.seilent.funecomp.ui

import androidx.compose.runtime.key
import net.seilent.funecomp.state.AutoEq
import net.seilent.funecomp.state.AutoEqResult
import net.seilent.funecomp.state.Clamp
import kotlin.math.roundToInt

private const val TITLE = "import autoeq"
private const val FILE = "file"
private const val SEARCH = "search"
private const val CANCEL = "cancel"
private const val NO_PRESETS = "device has no eq presets"

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
    if (i == 0) ctx.control.eqFile { name, text -> autoEqConfirm(ctx, AutoEq.name(name), text) } else autoEqSearch(ctx)
}

private fun autoEqSearch(ctx: Ctx) = ctx.sheet.openEntry(SEARCH) {
    key(SEARCH) {
        TextEntry("") { t ->
            ctx.sheet.close()
            val q = t.trim()
            if (q.isNotEmpty()) ctx.control.autoEqFind(q) { found ->
                when {
                    found == null -> ctx.sheet.open(q, listOf(CANCEL), info = listOf("search failed")) {}
                    found.isEmpty() -> ctx.sheet.open(q, listOf(CANCEL), info = listOf("no results")) {}
                    else -> ctx.sheet.open(q, found.map { it.name }) { i ->
                        val e = found[i]
                        ctx.control.autoEqProfile(e) { text -> autoEqConfirm(ctx, AutoEq.name(e.name), text) }
                    }
                }
            }
        }
    }
}

fun autoEqConfirm(ctx: Ctx, name: String, text: String?) {
    val r = if (text == null) AutoEqResult.Refused("not readable") else AutoEq.parse(text, name)
    ctx.sheet.open(name.ifEmpty { TITLE }, listOf(CANCEL), info = autoEqLines(r)) {}
}
