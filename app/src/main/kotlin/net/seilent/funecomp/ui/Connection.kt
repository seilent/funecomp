package net.seilent.funecomp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import net.seilent.funecomp.artist.ArtCache
import net.seilent.funecomp.artist.Sources
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.proto.gen.Enums

private const val REMEDY = "settings > system > pair phone app"
private const val PAIR_HINT = "pair on the device"

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

private class Line(val title: String, val value: String, val info: Boolean = false, val tag: String, val tap: () -> Unit = {})

@Composable
fun ConnectionPage(ctx: Ctx, link: LinkView, scrobble: ScrobbleView = ScrobbleView()) {
    val ui = LocalUi.current
    var focus by remember { mutableStateOf("device") }
    val read = { ctx.control.bonded().sortedByDescending { it.companion } }
    var bonded by remember { mutableStateOf(read()) }
    LifecycleResumeEffect(link.permission) {
        bonded = read()
        onPauseOrDispose {}
    }
    val hello = ctx.device.hello
    val tryAgain = { if (link.permission) ctx.control.retry() else ctx.control.askPermission() }
    val lines = buildList {
        add(Line("device", link.device ?: "not set", tag = "device") {
            if (!link.permission) {
                ctx.control.askPermission()
            } else {
                val list = read()
                bonded = list
                val at = list.indexOfFirst { it.name == link.device || it.address == link.device }.coerceAtLeast(0)
                ctx.sheet.open("device", list.map { it.name }, at) { ctx.control.choose(list[it].address) }
            }
        })
        add(Line("status", statusText(link), tag = "status", tap = tryAgain))
        val remedy = link.bye == Enums.ByeReason.refused && link.phase != Phase.LIVE
        val hint = link.permission && link.phase != Phase.LIVE && bonded.none { it.companion || it.name == link.device || it.address == link.device }
        if (hint) add(Line(PAIR_HINT, "", info = true, tag = "pairhint"))
        if (remedy) add(Line(REMEDY, "", info = true, tag = "remedy", tap = tryAgain)) else if (hint) add(Line(REMEDY, "", info = true, tag = "pairpath"))
        if (hint) add(Line("bluetooth settings", "", tag = "btsettings") { ctx.control.openBluetoothSettings() })
        if (hello != null) {
            add(Line("board", hello.board ?: "", tag = "board"))
            add(Line("version", hello.version ?: "", tag = "version"))
        }
        link.battery?.let { add(Line("battery", it.word, tag = "battery") { ctx.control.allowBackground() }) }
        add(Line("scrobbling", scrobbleText(scrobble), tag = "scrobbling") { ctx.nav.push(Page.Scrobbling) })
        if (link.phase != Phase.NONE) add(Line("disconnect", "", tag = "disconnect") { ctx.control.disconnect() })
    }
    val at = lines.indexOfFirst { it.tag == focus }.coerceAtLeast(0)
    HubPage("connection", { ctx.nav.pop() }, count = statusText(link)) {
        Rows(ui.contentTop, focus = at) {
            itemsIndexed(lines, key = { _, l -> l.tag }) { i, l ->
                SettingsRow(l.title, l.value, Modifier.enter { i }.testTag("conn.${l.tag}"), focused = at == i, info = l.info) {
                    focus = l.tag
                    l.tap()
                }
            }
        }
    }
}

private class Toggle(val title: String, val on: Boolean, val tag: String, val flip: (Sources) -> Sources)

@Composable
fun ArtistArtPage(ctx: Ctx, sources: Sources, onSources: (Sources) -> Unit, credits: List<ArtCache.Entry> = emptyList(), onCredit: (ArtCache.Entry) -> Unit = {}) {
    val ui = LocalUi.current
    var focus by remember { mutableIntStateOf(0) }
    val toggles = listOf(
        Toggle("theaudiodb", sources.theAudioDb, Sources.THE_AUDIO_DB) { it.copy(theAudioDb = !it.theAudioDb) },
        Toggle("discogs", sources.discogs, Sources.DISCOGS) { it.copy(discogs = !it.discogs) },
        Toggle("fanart.tv", sources.fanartTv, Sources.FANART_TV) { it.copy(fanartTv = !it.fanartTv) },
        Toggle("youtube", sources.youtube, Sources.YOUTUBE) { it.copy(youtube = !it.youtube) },
        Toggle("deezer", sources.deezer, Sources.DEEZER) { it.copy(deezer = !it.deezer) },
    )
    HubPage("artist art", { ctx.nav.pop() }, count = sources.enabled.sorted().joinToString(" ")) {
        Rows(ui.contentTop, focus = focus) {
            itemsIndexed(toggles, key = { _, t -> t.tag }) { i, t ->
                SettingsRow(t.title, if (t.on) "on" else "off", Modifier.enter { i }.testTag("art.${t.tag}"), focused = focus == i) {
                    focus = i
                    onSources(t.flip(sources))
                }
            }
            item(key = "key") {
                val i = toggles.size
                SettingsRow("fanart.tv key", if (sources.fanartTvKey.isBlank()) "not set" else "set", Modifier.enter { i }.testTag("art.key"), focused = focus == i) {
                    focus = i
                    ctx.sheet.openEntry("fanart.tv key") {
                        TextEntry(sources.fanartTvKey, secret = true) { k ->
                            ctx.sheet.close()
                            onSources(sources.copy(fanartTvKey = k.trim()))
                        }
                    }
                }
            }
            if (credits.isNotEmpty()) {
                item(key = "credits") {
                    val above = Tok.detailLabelBaseline - Tok.detailRingTop - Tok.detailRing
                    Box(Modifier.enter { toggles.size + 1 }.size(ui.px(ui.w).dp(), ui.px(above + Tok.detailLabelDrop).dp())) {
                        RoleText("credits", Roles.sectionLabel, Modifier.atBaseline(ui.px(Tok.hubEdge), ui.px(above)))
                    }
                }
                itemsIndexed(credits, key = { _, e -> "credit ${e.key}" }) { j, e ->
                    val i = toggles.size + 2 + j
                    val c = e.credit ?: return@itemsIndexed
                    CreditRow(e.name, listOf(c.author, c.license), Modifier.enter { i }.testTag("art.credit.$j"), focused = focus == i) {
                        focus = i
                        if (c.page.startsWith("https://")) onCredit(e)
                    }
                }
            }
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
