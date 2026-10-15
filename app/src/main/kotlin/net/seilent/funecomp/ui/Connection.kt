package net.seilent.funecomp.ui

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import net.seilent.funecomp.artist.Sources
import net.seilent.funecomp.proto.gen.Enums

private const val REMEDY = "settings > system > phone app"

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
    else -> "not set"
}

private class Line(val title: String, val value: String, val info: Boolean = false, val tag: String, val tap: () -> Unit = {})

@Composable
fun ConnectionPage(ctx: Ctx, link: LinkView) {
    val ui = LocalUi.current
    var focus by remember { mutableIntStateOf(0) }
    val hello = ctx.device.hello
    val lines = buildList {
        add(Line("device", link.device ?: "not set", tag = "device") {
            if (!link.permission) {
                ctx.control.askPermission()
            } else {
                val bonded = ctx.control.bonded()
                val at = bonded.indexOfFirst { it.name == link.device || it.address == link.device }.coerceAtLeast(0)
                ctx.sheet.open("device", bonded.map { it.name }, at) { ctx.control.choose(bonded[it].address) }
            }
        })
        add(Line("status", statusText(link), tag = "status") { if (!link.permission) ctx.control.askPermission() })
        if (link.bye == Enums.ByeReason.refused && link.phase != Phase.LIVE) add(Line(REMEDY, "", info = true, tag = "remedy"))
        if (hello != null) {
            add(Line("board", hello.board ?: "", tag = "board"))
            add(Line("version", hello.version ?: "", tag = "version"))
        }
    }
    HubPage("connection", { ctx.nav.pop() }, count = statusText(link)) {
        Rows(ui.contentTop, focus = focus) {
            itemsIndexed(lines, key = { _, l -> l.tag }) { i, l ->
                SettingsRow(l.title, l.value, Modifier.enter { i }.testTag("conn.${l.tag}"), focused = focus == i, info = l.info) {
                    focus = i
                    l.tap()
                }
            }
        }
    }
}

private class Toggle(val title: String, val on: Boolean, val tag: String, val flip: (Sources) -> Sources)

@Composable
fun ArtistArtPage(ctx: Ctx, sources: Sources, onSources: (Sources) -> Unit) {
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
        }
    }
}
