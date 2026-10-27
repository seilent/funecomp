package net.seilent.funecomp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import net.seilent.funecomp.artist.ArtBlocks
import net.seilent.funecomp.artist.ArtCache
import net.seilent.funecomp.artist.ArtPicks
import net.seilent.funecomp.artist.LyricSources
import net.seilent.funecomp.artist.Sources
import net.seilent.funecomp.artist.Wikimedia
import net.seilent.funecomp.proto.gen.Tok

val ONLINE_PIVOTS = listOf("art", "lyrics", "scrobbling")
private const val MOVE_UP = "move up"
private const val MOVE_DOWN = "move down"
private const val KEY = "key"
private const val COPYRIGHT = "lyrics © their owners"
private const val CLEAR_CACHE = "clear cache"
private const val KEEP_CACHE = "keep cache"
private const val CLEAR_PHOTOS = "clear photos"
private const val KEEP_PHOTOS = "keep photos"
private const val NO_PHOTOS = "no photos"

@Composable
fun SectionLabel(text: String, i: Int) {
    val ui = LocalUi.current
    val above = Tok.detailLabelBaseline - Tok.detailRingTop - Tok.detailRing
    Box(Modifier.enter { i }.size(ui.px(ui.w).dp(), ui.px(above + Tok.detailLabelDrop).dp())) {
        RoleText(text, Roles.sectionLabel, Modifier.atBaseline(ui.px(Tok.hubEdge), ui.px(above)))
    }
}

fun sourceSheet(ctx: Ctx, label: String, on: Boolean?, at: Int, size: Int, key: String?, keep: Keep?, set: (Boolean) -> Unit, move: (Int) -> Unit, onKey: () -> Unit = {}) {
    val rows = buildList<Triple<String, String, () -> Unit>> {
        if (on != null) {
            val pick = { v: Boolean -> { if (v != on) set(v) } }
            add(Triple(OFF, "", pick(false)))
            add(Triple(ON, "", pick(true)))
        }
        if (at > 0) add(Triple(MOVE_UP, "") { move(-1) })
        if (at < size - 1) add(Triple(MOVE_DOWN, "") { move(1) })
        if (key != null) add(Triple(KEY, key, onKey))
    }
    ctx.sheet.open(label, rows.map { it.first }, if (on == true) 1 else 0, itemValues = { rows.map { it.second } }, keep = keep) { rows[it].third() }
}

private fun Sources.on(id: String): Boolean? = when (id) {
    Sources.THE_AUDIO_DB -> theAudioDb
    Sources.DISCOGS -> discogs
    Sources.FANART_TV -> fanartTv
    Sources.YOUTUBE -> youtube
    Sources.DEEZER -> deezer
    Wikimedia.SOURCE -> commons
    else -> null
}

private fun Sources.with(id: String, v: Boolean): Sources = when (id) {
    Sources.THE_AUDIO_DB -> copy(theAudioDb = v)
    Sources.DISCOGS -> copy(discogs = v)
    Sources.FANART_TV -> copy(fanartTv = v)
    Sources.YOUTUBE -> copy(youtube = v)
    Sources.DEEZER -> copy(deezer = v)
    Wikimedia.SOURCE -> copy(commons = v)
    else -> this
}

private fun photos(u: ArtCache.Usage) = "${u.photos} ${if (u.photos == 1) "photo" else "photos"}, ${sizeText(u.bytes)}"

private fun clearSheet(ctx: Ctx, label: String, u: ArtCache.Usage?, keep: Keep?, clear: () -> Unit) {
    if (u == null || u.photos == 0) {
        ctx.sheet.open("clear $label?", listOf(KEEP_PHOTOS), info = listOf(NO_PHOTOS), keep = keep) {}
    } else {
        ctx.sheet.open("clear $label?", listOf(CLEAR_PHOTOS, KEEP_PHOTOS), 1, listOf(photos(u)), keep = keep) { if (it == 0) clear() }
    }
}

fun sourceValue(sources: Sources, id: String) = when {
    id == Sources.FANART_TV && sources.fanartTv && sources.fanartTvKey.isBlank() -> "no key"
    id in sources.enabled -> ON
    else -> OFF
}

@Composable
fun OnlinePage(
    ctx: Ctx,
    page: Page.Online,
    sources: Sources,
    onSources: (Sources) -> Unit,
    credits: List<ArtCache.Entry> = emptyList(),
    onCredit: (ArtCache.Entry) -> Unit = {},
    blocked: List<ArtBlocks.Record> = emptyList(),
    picks: List<ArtPicks.Pick> = emptyList(),
    scrobble: ScrobbleView = ScrobbleView(),
    lyrics: LyricSources = LyricSources(),
    labels: Boolean = true,
) {
    var pivot by rememberSaveable { mutableIntStateOf(page.pivot) }
    val pivots = rememberSaveableStateHolder()
    HubPage("online", { ctx.nav.pop() }, ONLINE_PIVOTS, pivot, { pivot = it }) { shown ->
        pivots.SaveableStateProvider(shown) {
            when (shown) {
                ONLINE_LYRICS -> LyricsRows(ctx, lyrics)
                ONLINE_SCROBBLING -> ScrobbleRows(ctx, scrobble, labels)
                else -> ArtRows(ctx, sources, onSources, credits, blocked, picks)
            }
        }
    }
}

@Composable
private fun ArtRows(ctx: Ctx, sources: Sources, onSources: (Sources) -> Unit, credits: List<ArtCache.Entry>, blocked: List<ArtBlocks.Record>, picks: List<ArtPicks.Pick>) {
    val ui = LocalUi.current
    val lists = listOf(Triple(PICKED, picks, Page.ArtPicked), Triple(BLOCKED, blocked, Page.ArtBlocked), Triple(CREDITS, credits, Page.ArtCredits)).filter { it.second.isNotEmpty() }
    val tags = sources.order.map { "src.$it" } + lists.map { it.first } + CLEAR_CACHE
    var usage by remember { mutableStateOf<Map<String?, ArtCache.Usage>?>(null) }
    val reload = { ctx.control.artUsage { usage = it } }
    LaunchedEffect(credits) { reload() }
    fun clear(source: String?) {
        ctx.control.clearArt(source)
        reload()
    }
    var focus by rememberSaveable { mutableStateOf("src.${sources.order.first()}") }
    var last by rememberSaveable { mutableIntStateOf(0) }
    val found = tags.indexOf(focus)
    val at = if (found >= 0) found else last.coerceAtMost(tags.size - 1)
    SideEffect {
        last = at
        focus = tags[at]
    }
    val list = rememberLazyListState()
    Rows(ui.contentTop, list, focus = at) {
        itemsIndexed(sources.order, key = { _, id -> "src $id" }) { i, id ->
            val keep = Keep(list, i, ui.px(ui.contentTop))
            SettingsRow(Sources.label(id), sourceValue(sources, id), Modifier.enter { i }.testTag("art.src.$id"), focused = at == i, onLong = {
                focus = tags[i]
                usage?.let { u -> clearSheet(ctx, Sources.label(id), u[id], keep) { clear(id) } }
            }) {
                focus = tags[i]
                val key = if (id == Sources.FANART_TV) (if (sources.fanartTvKey.isBlank()) "not set" else "set") else null
                sourceSheet(ctx, Sources.label(id), sources.on(id), i, sources.order.size, key, keep, { on ->
                    onSources(sources.with(id, on))
                    val u = usage?.get(id)
                    if (!on && u != null && u.photos > 0) clearSheet(ctx, Sources.label(id), u, keep) { clear(id) }
                }, { onSources(sources.moved(id, it)) }) {
                    ctx.sheet.openEntry("fanart.tv key") {
                        TextEntry(sources.fanartTvKey, secret = true) { k ->
                            ctx.sheet.close()
                            onSources(sources.copy(fanartTvKey = k.trim()))
                        }
                    }
                }
            }
        }
        lists.forEachIndexed { k, (title, entries, target) ->
            item(key = title) {
                val i = sources.order.size + k
                SettingsRow(title, "${entries.size}", Modifier.enter { i }.testTag("art.$title"), focused = at == i) {
                    focus = title
                    ctx.nav.push(target)
                }
            }
        }
        item(key = CLEAR_CACHE) {
            val i = tags.size - 1
            val total = usage?.get(null) ?: usage?.let { ArtCache.Usage(0, 0) }
            SettingsRow(CLEAR_CACHE, total?.let { sizeText(it.bytes) } ?: "", Modifier.enter { i }.testTag("art.clear"), focused = at == i) {
                focus = CLEAR_CACHE
                val u = total ?: return@SettingsRow
                val keep = Keep(list, i, ui.px(ui.contentTop))
                if (u.bytes == 0L) {
                    ctx.sheet.open("$CLEAR_CACHE?", listOf(KEEP_CACHE), info = listOf(NO_PHOTOS), keep = keep) {}
                } else {
                    ctx.sheet.open("$CLEAR_CACHE?", listOf(CLEAR_CACHE, KEEP_CACHE), 1, listOf(photos(u)), keep = keep) { if (it == 0) clear(null) }
                }
            }
        }
    }
}

@Composable
fun LyricsRows(ctx: Ctx, sources: LyricSources) {
    val ui = LocalUi.current
    val ids = sources.order
    var focus by rememberSaveable { mutableStateOf(ids.first()) }
    val at = ids.indexOf(focus).coerceAtLeast(0)
    val list = rememberLazyListState()
    Rows(ui.contentTop, list, focus = at) {
        itemsIndexed(ids, key = { _, id -> "lyr $id" }) { i, id ->
            val on = sources.on(id)
            SettingsRow(id, if (on) ON else OFF, Modifier.enter { i }.testTag("lyr.src.$id"), focused = at == i) {
                focus = id
                sourceSheet(ctx, id, on, i, ids.size, null, Keep(list, i, ui.px(ui.contentTop)), { ctx.control.lyricSource(id, it) }, { ctx.control.lyricMove(id, it) })
            }
        }
        item(key = COPYRIGHT) { SettingsRow(COPYRIGHT, "", Modifier.enter { ids.size }.testTag("lyrics.copyright"), info = true) }
    }
}
