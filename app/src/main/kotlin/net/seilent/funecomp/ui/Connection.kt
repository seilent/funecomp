package net.seilent.funecomp.ui

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlin.math.roundToInt
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.Layout
import androidx.compose.foundation.text.BasicText
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.LifecycleResumeEffect
import net.seilent.funecomp.artist.ArtBlocks
import net.seilent.funecomp.artist.ArtCache
import net.seilent.funecomp.artist.ArtPicks
import net.seilent.funecomp.artist.ArtPusher
import net.seilent.funecomp.artist.Names
import net.seilent.funecomp.artist.Sources
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import java.util.Locale

private const val REMEDY = "settings > system > pair phone app"
private const val PAIR_HINT = "pair on the device"
const val BLOCKED = "blocked"
const val CREDITS = "credits"
private const val NO_PICKED = "no picked images"
private const val NO_BLOCKED = "no blocked images"
private const val NO_CREDITS = "no credits"

fun byeText(reason: Int?) = when (reason) {
    Enums.ByeReason.refused -> "refused"
    Enums.ByeReason.companionOff -> "phone app off"
    Enums.ByeReason.replaced -> "replaced"
    Enums.ByeReason.version -> "version"
    Enums.ByeReason.shutdown -> "shut down"
    null -> "not connected"
    else -> "bye $reason"
}

fun statusText(link: LinkView) = when {
    !link.permission -> "no permission"
    link.phase == Phase.LIVE -> "connected"
    link.phase == Phase.CONNECTING -> "connecting"
    link.phase == Phase.STOPPED -> byeText(link.bye)
    link.phase == Phase.DOWN -> byeText(link.bye)
    link.device != null -> "stopped"
    else -> "not set"
}

fun batteryText(s: State) = "${s.battery}%" + if (s.charging == true) " charging" else ""

fun cardText(s: State): String? {
    val free = s.cardFreeMiB ?: return null
    val total = s.cardTotalMiB ?: return null
    return String.format(Locale.ROOT, "%.1f of %.1f gb free", free / MIB_PER_GB, total / MIB_PER_GB)
}

private const val MIB_PER_GB = 1024.0

private class Line(val title: String, val value: String, val info: Boolean = false, val tag: String, val tap: (Int) -> Unit = {})

@Composable
fun ConnectionPage(ctx: Ctx, link: LinkView, gated: Boolean = false) {
    val ui = LocalUi.current
    var focus by rememberSaveable { mutableStateOf("device") }
    val read = { ctx.control.bonded().sortedByDescending { it.companion } }
    var bonded by remember { mutableStateOf(read()) }
    LifecycleResumeEffect(link.permission) {
        bonded = read()
        onPauseOrDispose {}
    }
    val tryAgain: (Int) -> Unit = { if (link.permission) ctx.control.retry() else ctx.control.askPermission() }
    val scroll = rememberLazyListState()
    fun keep(i: Int) = Keep(scroll, i, ui.px(ui.contentTop))
    val lines = buildList {
        add(Line("device", link.device ?: "not set", tag = "device") { i ->
            if (!link.permission) {
                ctx.control.askPermission()
            } else {
                val list = read()
                bonded = list
                val at = list.indexOfFirst { it.name == link.device || it.address == link.device }.coerceAtLeast(0)
                ctx.sheet.open("device", list.map { it.name }, at, keep = keep(i)) { ctx.control.choose(list[it].address) }
            }
        })
        add(Line("status", statusText(link), tag = "status", tap = tryAgain))
        val remedy = link.bye == Enums.ByeReason.refused && link.phase != Phase.LIVE
        val hint = link.permission && link.phase != Phase.LIVE && bonded.none { it.companion || it.name == link.device || it.address == link.device }
        if (hint) add(Line(PAIR_HINT, "", info = true, tag = "pairhint"))
        if (remedy) add(Line(REMEDY, "", info = true, tag = "remedy", tap = tryAgain)) else if (hint) add(Line(REMEDY, "", info = true, tag = "pairpath"))
        if (hint) add(Line("bluetooth settings", "", tag = "btsettings") { ctx.control.openBluetoothSettings() })
        if (gated) add(Line("online", "", tag = "online") { ctx.nav.push(Page.Online()) })
        link.battery?.let { add(Line("background", it.word, tag = "background") { ctx.control.allowBackground() }) }
        add(Line("phone media controls", if (link.mediaControls) ON else OFF, tag = "media") { i -> onOffSheet(ctx, "phone media controls", link.mediaControls, keep(i)) { ctx.control.mediaControls(it) } })
        add(Line("reconnect on update", if (link.reconnect) ON else OFF, tag = "reconnect") { i -> onOffSheet(ctx, "reconnect on update", link.reconnect, keep(i)) { ctx.control.reconnect(it) } })
        if (link.phase != Phase.NONE) add(Line("disconnect", "", tag = "disconnect") { ctx.control.disconnect() })
    }
    val at = lines.indexOfFirst { it.tag == focus }.coerceAtLeast(0)
    HubPage("connection", { ctx.nav.pop() }) {
        Rows(ui.contentTop, scroll, focus = at) {
            itemsIndexed(lines, key = { _, l -> l.tag }) { i, l ->
                SettingsRow(l.title, l.value, Modifier.enter { i }.testTag("conn.${l.tag}"), focused = at == i, info = l.info) {
                    focus = l.tag
                    l.tap(i)
                }
            }
        }
    }
}

fun artSheet(ctx: Ctx, held: BackdropArt?, picks: List<ArtPicks.Pick> = emptyList()) {
    val e = held?.entry ?: return artistChooser(ctx)
    val s = ctx.device.playback.state
    val target = PickFor(e.name, s.mbids.getOrNull(s.artists.indexOfFirst { Names.fold(it) == Names.fold(e.name) }), origin = ArtPusher.origin(s.source))
    val pick = picks.firstOrNull { it.cacheKey == e.key }
    val items = if (pick != null) listOf(PICK_IMAGE, UNDO_PICK) else listOf(BLOCK_IMAGE) + (if (held.wrongArtist) listOf(WRONG_ARTIST) else emptyList()) + PICK_IMAGE
    val info = listOf(listOfNotNull(Sources.label(e.source), e.credit?.author?.takeIf { it.isNotBlank() }).joinToString(", "))
    ctx.sheet.open(e.name, items, info = info) {
        when (items[it]) {
            BLOCK_IMAGE -> ctx.control.blockArt(e)
            WRONG_ARTIST -> ctx.control.wrongArtist(e)
            PICK_IMAGE -> pickSheet(ctx, target)
            UNDO_PICK -> pick?.let { p -> ctx.control.undoPick(p) }
        }
    }
}

@Composable
private fun ArtList(ctx: Ctx, title: String, keys: List<String>, empty: String, row: @Composable (Int, Modifier, Boolean, () -> Unit) -> Unit) {
    val ui = LocalUi.current
    var focus by rememberSaveable { mutableStateOf<String?>(null) }
    var last by rememberSaveable { mutableIntStateOf(0) }
    val found = keys.indexOf(focus)
    val at = if (found >= 0) found else last.coerceAtMost(keys.size - 1)
    SideEffect {
        if (at >= 0) {
            last = at
            focus = keys[at]
        }
    }
    HubPage(title, { ctx.nav.pop() }) {
        if (keys.isEmpty()) {
            StateLine(empty, "art.$title.empty")
        } else {
            Rows(ui.contentTop, focus = at) {
                itemsIndexed(keys, key = { _, k -> k }) { i, k ->
                    row(i, Modifier.enter { i }, at == i) { focus = k }
                }
            }
        }
    }
}

@Composable
fun ArtPickedPage(ctx: Ctx, picks: List<ArtPicks.Pick>, missing: Set<String> = emptySet()) {
    ArtList(ctx, PICKED, picks.map { "picked ${it.key} ${it.at}" }, NO_PICKED) { j, m, focused, take ->
        val p = picks[j]
        val broken = p.kind != ArtPicks.NONE && p.failedAt != null
        val line = when {
            p.kind == ArtPicks.NONE || (p.source == Sources.GALLERY && p.sha in missing) -> NO_IMAGE
            broken -> LINK_BROKEN
            else -> Sources.label(p.source)
        }
        CreditRow(p.name, listOf(line), m.testTag("art.picked.$j"), focused = focused) {
            take()
            val items = listOf(UNDO_PICK) + if (broken) listOf(TRY_AGAIN) else emptyList()
            ctx.sheet.open(p.name, items) { k -> if (k == 0) ctx.control.undoPick(p) else ctx.control.tryAgain(p) }
        }
    }
}

@Composable
fun ArtBlockedPage(ctx: Ctx, blocked: List<ArtBlocks.Record>) {
    ArtList(ctx, BLOCKED, blocked.map { "blocked ${it.javaClass.simpleName} ${it.key} ${it.at}" }, NO_BLOCKED) { j, m, focused, take ->
        val r = blocked[j]
        val lines = when (r) {
            is ArtBlocks.Block -> listOf(Sources.label(r.source))
            is ArtBlocks.Reject -> listOf("wrong artist")
            is ArtBlocks.Drop -> emptyList()
        }
        CreditRow(r.name, lines, m.testTag("art.blocked.$j"), focused = focused) {
            take()
            ctx.sheet.open(r.name, listOf("unblock")) { ctx.control.unblock(r) }
        }
    }
}

@Composable
fun ArtCreditsPage(ctx: Ctx, credits: List<ArtCache.Entry>, onCredit: (ArtCache.Entry) -> Unit) {
    val shown = credits.mapNotNull { e -> e.credit?.let { e to it } }
    ArtList(ctx, CREDITS, shown.map { "credit ${it.first.key}" }, NO_CREDITS) { j, m, focused, take ->
        val (e, c) = shown[j]
        CreditRow(e.name, listOf(c.author, c.license), m.testTag("art.credit.$j"), focused = focused) {
            take()
            if (c.page.startsWith("https://")) onCredit(e)
        }
    }
}

@Composable
fun CreditRow(primary: String, lines: List<String>, modifier: Modifier = Modifier, focused: Boolean = false, onTap: () -> Unit) {
    val ui = LocalUi.current
    val rowW = ui.px(ui.w)
    val textX = ui.px(Tok.hubEdge)
    val textW = (rowW - 2 * textX).roundToInt().coerceAtLeast(0)
    val inset = ui.px(Tok.rowTextInset).roundToInt()
    val c by animateColorAsState(white(if (focused) Roles.listPrimary.alpha else Roles.listPrimary.dim), tween(Tok.motion_focusAlpha.toInt()), label = "credit")
    val secondary = textStyle(Roles.listSecondary, white(Roles.listSecondary.alpha))
    Pressable({ onTap() }, modifier) {
        Layout(
            content = {
                RoleText(primary, Roles.listPrimary, Modifier, color = c)
                lines.forEach { BasicText(shown(it, Roles.listSecondary.case), style = secondary) }
            },
        ) { m, _ ->
            val ps = m.map { it.measure(Constraints(maxWidth = textW)) }
            val h = maxOf(ui.px(Tok.rowHeight).roundToInt(), 2 * inset + ps.sumOf { it.height })
            layout(rowW.roundToInt(), h) {
                var y = inset
                ps.forEach {
                    it.place(textX.roundToInt(), y)
                    y += it.height
                }
            }
        }
    }
}
