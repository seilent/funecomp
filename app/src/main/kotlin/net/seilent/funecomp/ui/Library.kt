package net.seilent.funecomp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random
import net.seilent.funecomp.art.Thumbs
import net.seilent.funecomp.proto.LibRow
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.state.Heart
import net.seilent.funecomp.state.LibKey
import net.seilent.funecomp.state.LibList
import net.seilent.funecomp.state.ListGone
import net.seilent.funecomp.state.ListLine
import net.seilent.funecomp.state.ListPlays

fun songs(n: Long) = "$n " + if (n == 1L) "song" else "songs"

fun LibRow.isTrack() = first?.let { key == java.lang.Long.toUnsignedString(it) } ?: false

@Composable
fun libList(device: DeviceState, control: Control, view: Int, parent: String): LibList? {
    val list = device.lib.pages[LibKey(view, parent)]
    LaunchedEffect(view, parent, device.lib.rev, list == null) {
        if (list == null && device.has("library")) control.libGet(view, parent, 0)
    }
    return list
}

@Composable
fun LibList.row(i: Int, control: Control, view: Int, parent: String): LibRow? {
    val r = rows[i.toLong()]
    LaunchedEffect(r == null, i) {
        if (r == null) control.libGet(view, parent, firstMissing(i / LIB_PAGE * LIB_PAGE, i + 1L) ?: i.toLong())
    }
    return r
}

class Ctx(val device: DeviceState, val control: Control, val nav: Nav, val sheet: SheetState, val toast: ToastState, val browse: BrowseSessions, val lists: ListPlays = ListPlays(LIB_PAGE), val live: () -> DeviceState = { device })

const val ADD_TO_NOW_PLAYING = "add to now playing"
private const val PLAY_NEXT = "play next"
private const val SONG_ADDED = "song added to now playing"
private const val SONGS_ADDED = "songs added to now playing"
private const val OPEN_PLAYLIST = "open playlist"
private const val UNREADABLE = "playlist not readable"

fun musicViews(device: DeviceState) = listOf(Enums.View.albums, Enums.View.artists, Enums.View.playlists, Enums.View.songs) +
    listOfNotNull(Enums.View.genres.takeIf { device.has("genres") }) + listOf(Enums.View.folders)

@Composable
fun Music(ctx: Ctx) {
    val views = musicViews(ctx.device)
    var saved by rememberSaveable { mutableIntStateOf(Enums.View.albums) }
    val pivot = views.indexOf(saved).coerceAtLeast(0)
    var folder by rememberSaveable { mutableStateOf("") }
    val inFolder = views[pivot] == Enums.View.folders && folder.isNotEmpty()
    val up = { folder = folder.substringBeforeLast('/', "") }
    BackHandler(inFolder) { up() }
    val back = {
        if (inFolder) up() else ctx.nav.pop()
        Unit
    }
    key(views) {
        HubPage("music", back, views.map { Enums.View.names.getValue(it) }, pivot, { saved = views[it] }, status = ListLine.card(ctx.device.playback.state.card)) { shown ->
            when (val view = views.getOrNull(shown) ?: return@HubPage) {
                Enums.View.playlists -> PlaylistRows(ctx)
                Enums.View.genres -> Pane(ctx, view, "", line = musicLine(ctx, view, "")) { _, r, _ -> genreRow(ctx, r) }
                Enums.View.folders -> Pane(ctx, view, folder, line = musicLine(ctx, view, folder)) { i, r, list -> folderRow(ctx, folder, i, r, list) { folder = it } }
                else -> Pane(ctx, view, "", line = musicLine(ctx, view, "")) { i, r, _ -> musicRow(ctx, view, i, r) }
            }
        }
    }
}

@Composable
private fun musicLine(ctx: Ctx, view: Int, parent: String): String? {
    val d = ctx.device
    val total = d.lib.pages[LibKey(view, parent)]?.total
    val scanning = ListLine.scanning(d)
    val songs = if (total == 0L && (view != Enums.View.genres || scanning)) libList(d, ctx.control, Enums.View.songs, "")?.total else null
    return ListLine.empty(d.playback.state.card, scanning, view, total, songs)
}

@Composable
private fun LineRow(text: String, width: Float) {
    val ui = LocalUi.current
    Box(Modifier.enter().size(width.dp(), ui.px(Tok.rowHeight).dp()).testTag("line")) {
        RoleText(text, Roles.quickplayHint, Modifier.atBaseline(ui.px(Tok.hubEdge), ui.px(Tok.qpHintDrop)))
    }
}

class RowSpec(
    val primary: String,
    val secondary: String = "",
    val number: String = "",
    val bold: Boolean = false,
    val countLine: Boolean = false,
    val ring: Boolean = false,
    val playing: Boolean = false,
    val libSub: Boolean = false,
    val tap: () -> Unit,
    val long: (() -> Unit)? = null,
)

@Composable
fun Pane(
    ctx: Ctx,
    view: Int,
    parent: String,
    top: Float? = null,
    x: Float = 0f,
    state: LazyListState = rememberLazyListState(),
    head: Int = 0,
    lead: Float = 0f,
    line: String? = null,
    list: LibList? = libList(ctx.device, ctx.control, view, parent),
    spec: (Int, LibRow, LibList) -> RowSpec,
) {
    val ui = LocalUi.current
    var focus by rememberSaveable(view, parent) { mutableIntStateOf(0) }
    Rows(top ?: ui.contentTop, state, x, show = view to parent, head = head, lead = lead) {
        repeat(head) { item { Spacer(Modifier.size((ui.px(ui.w) - ui.px(x)).dp(), ui.px(lead).dp())) } }
        if (line != null) item { LineRow(line, ui.px(ui.w) - ui.px(x)) }
        val n = if (line != null) 0 else (list?.total ?: 0L).toInt()
        items(n) { i ->
            val r = list?.row(i, ctx.control, view, parent)
            Box(Modifier.enter()) {
                if (r == null) {
                    Box(Modifier.size((ui.px(ui.w) - ui.px(x)).dp(), ui.px(Tok.rowHeight).dp()))
                } else {
                    val s = spec(i, r, list)
                    ListRow(
                        s.primary, Modifier.testTag("row.$i"), s.secondary, s.number, "", s.bold, s.countLine, s.ring,
                        playing = s.playing, libSub = s.libSub, focused = focus == i, width = ui.px(ui.w) - ui.px(x),
                        onTap = {
                            focus = i
                            s.tap()
                        },
                        onLong = s.long?.let { f -> { focus = i; f() } },
                    )
                }
            }
        }
    }
}

internal var startRandom: Random = Random.Default

fun sourceStart(count: Long?, shuffle: Boolean, random: Random = startRandom): Long? = when {
    !shuffle -> 0L
    count == null -> null
    count > 0 -> random.nextLong(count)
    else -> 0L
}

internal fun playRefused(device: DeviceState, toast: ToastState): Boolean = refusal(device.playRefusal, toast)

private fun queueRefused(device: DeviceState, toast: ToastState): Boolean = refusal(device.queueRefusal, toast)

private fun refusal(line: String?, toast: ToastState): Boolean {
    line ?: return false
    toast.show(line, "")
    return true
}

private fun playAndShow(ctx: Ctx, view: Int, parent: String, start: Long, shuffle: Boolean = false) {
    if (playRefused(ctx.live(), ctx.toast)) return
    ctx.control.play(view, parent, start, shuffle)
    ctx.nav.popTo(Page.NowPlaying)
}

private fun playSource(ctx: Ctx, view: Int, parent: String, count: Long?, shuffle: Boolean = false) {
    val start = sourceStart(count, shuffle || ctx.device.playback.state.shuffle == true)
    if (start == null) ctx.control.log("start 0 (no count)")
    playAndShow(ctx, view, parent, start ?: 0L, shuffle)
}

private fun queued(ctx: Ctx, title: String, one: Boolean, added: Long?, code: Int?) {
    when {
        code == null -> ctx.toast.show(if (added == 1L || (added == null && one)) SONG_ADDED else SONGS_ADDED, title)
        code == Err.noCard -> ctx.toast.show(Heart.CARD, "")
        else -> ctx.control.log("queue add error $code")
    }
}

private fun queueAdd(ctx: Ctx, cmd: Int, title: String, view: Int?, parent: String?, tracks: List<Long> = emptyList()) {
    if (queueRefused(ctx.live(), ctx.toast)) return
    ctx.control.queueAdd(cmd, view, parent, tracks) { added, code -> queued(ctx, title, tracks.size == 1, added, code) }
}

private fun listSource(ctx: Ctx, view: Int, key: String, name: String, count: Long?, shuffle: Boolean = false) {
    if (count == 0L) return
    val random = shuffle || ctx.device.playback.state.shuffle == true
    val start = sourceStart(count, random)
    if (start == null) ctx.control.log("start 0 (no count)")
    listPlay(ctx, ListPlays.Want(view, key, name, start = start ?: 0L, shuffle = shuffle, random = random))
}

private fun listPlay(ctx: Ctx, want: ListPlays.Want) {
    val live = ctx.live()
    if (playRefused(live, ctx.toast)) return
    val pushed = Page.NowPlaying !in ctx.nav.stack
    ctx.nav.popTo(Page.NowPlaying)
    val w = want.copy(rev = want.rev ?: live.lib.libRev(want.view))
    playList(ctx, ctx.lists.tap(w, live.playback.state.queueRev, pushed, ctx.nav.top, ctx.nav.stack.size), w.start, w.rev)
}

private fun playList(ctx: Ctx, t: ListPlays.Tap, start: Long, libRev: Long?) {
    val w = t.want
    ctx.control.play(w.view, w.key, start, w.shuffle, libRev = libRev) { code -> listStep(ctx, ctx.lists.replied(t, code, ctx.live().playback.state.queueRev)) }
}

private fun listAdd(ctx: Ctx, cmd: Int, name: String, view: Int, key: String) {
    val live = ctx.live()
    if (queueRefused(live, ctx.toast)) return
    addList(ctx, ctx.lists.tap(ListPlays.Want(view, key, name, rev = live.lib.libRev(view), add = cmd), null, false, ctx.nav.top, ctx.nav.stack.size))
}

private fun addList(ctx: Ctx, t: ListPlays.Tap) {
    val w = t.want
    val cmd = w.add ?: return
    ctx.control.queueAdd(cmd, w.view, w.key) { added, code ->
        val step = ctx.lists.replied(t, code, null)
        if (code == Err.staleRev) listStep(ctx, step) else queued(ctx, w.name, false, added, code)
    }
}

fun listStep(ctx: Ctx, step: ListPlays.Step) {
    when (step) {
        is ListPlays.Step.Back -> {
            val t = step.tap
            if (t.want.add == null && t.pushed && ctx.nav.top == Page.NowPlaying && ctx.nav.stack.size == t.depth) ctx.nav.pop()
            step.gone?.let { ctx.toast.show(UNREADABLE, it) }
        }
        is ListPlays.Step.Again -> {
            val t = step.tap
            if (t.want.add != null) addList(ctx, t) else playList(ctx, t, step.at ?: if (t.want.random) sourceStart(step.count, true) ?: 0L else t.want.start, null)
        }
        ListPlays.Step.Stay -> Unit
    }
}

private fun listSheet(ctx: Ctx, title: String, view: Int, key: String, count: Long?) {
    val items = buildList<Pair<String, () -> Unit>> {
        if (count != 0L) {
            add("play" to { listSource(ctx, view, key, title, count) })
            if (ctx.device.has("queueAdd")) {
                add(PLAY_NEXT to { listAdd(ctx, Enums.QueueCmd.playNext, title, view, key) })
                add(ADD_TO_NOW_PLAYING to { listAdd(ctx, Enums.QueueCmd.add, title, view, key) })
            }
            add("shuffle" to { listSource(ctx, view, key, title, count, shuffle = true) })
        }
        add(OPEN_PLAYLIST to { ctx.nav.push(Page.List(view, key, title)) })
    }
    ctx.sheet.open(title, items.map { it.first }) { items[it].second() }
}

private fun songSheet(ctx: Ctx, r: LibRow, play: () -> Unit) {
    val items = listOf("play", PLAY_NEXT) + if (ctx.device.has("queueAdd")) listOf(ADD_TO_NOW_PLAYING) else emptyList()
    ctx.sheet.open(r.name ?: "", items) {
        when (it) {
            0 -> play()
            1 -> r.first?.let { id -> queueAdd(ctx, Enums.QueueCmd.playNext, r.name ?: "", null, null, listOf(id)) }
            2 -> r.first?.let { id -> queueAdd(ctx, Enums.QueueCmd.add, r.name ?: "", null, null, listOf(id)) }
        }
    }
}

private fun groupSheet(ctx: Ctx, title: String, view: Int, parent: String, count: Long?, artist: LibRow? = null) {
    val add = ctx.device.has("queueAdd")
    val items = buildList<Pair<String, () -> Unit>> {
        add("play" to { playSource(ctx, view, parent, count) })
        if (add) {
            add(PLAY_NEXT to { queueAdd(ctx, Enums.QueueCmd.playNext, title, view, parent) })
            add(ADD_TO_NOW_PLAYING to { queueAdd(ctx, Enums.QueueCmd.add, title, view, parent) })
        }
        add("shuffle" to { playSource(ctx, view, parent, count, shuffle = true) })
        if (artist != null) add(PICK_IMAGE to { pickSheet(ctx, PickFor(title, artist.mbid, library = true)) })
    }
    ctx.sheet.open(title, items.map { it.first }) { items[it].second() }
}

private fun genreRow(ctx: Ctx, r: LibRow): RowSpec {
    val name = r.name ?: ""
    val key = r.key ?: name
    return RowSpec(name, songs(r.count ?: 0), bold = true, tap = { playSource(ctx, Enums.View.genres, key, r.count) }) {
        val items = listOf("play") + if (ctx.device.has("queueAdd")) listOf(ADD_TO_NOW_PLAYING) else emptyList()
        ctx.sheet.open(name, items) {
            if (it == 0) playSource(ctx, Enums.View.genres, key, r.count) else queueAdd(ctx, Enums.QueueCmd.add, name, Enums.View.genres, key)
        }
    }
}

private fun musicRow(ctx: Ctx, view: Int, i: Int, r: LibRow): RowSpec {
    val name = r.name ?: ""
    return when (view) {
        Enums.View.artists -> RowSpec(name, songs(r.count ?: 0), countLine = true, tap = { ctx.nav.push(Page.Artist(r.key ?: name, r.count)) }) {
            groupSheet(ctx, name, Enums.View.artistAlbums, r.key ?: name, r.count, r)
        }
        Enums.View.albums -> RowSpec(name, songs(r.count ?: 0), countLine = true, tap = { ctx.nav.push(Page.Album(r.key ?: "", name, "")) }) {
            groupSheet(ctx, name, Enums.View.albumTracks, r.key ?: "", r.count)
        }
        else -> {
            val play = { playAndShow(ctx, view, "", i.toLong()) }
            RowSpec(name, number = "${i + 1}", playing = r.first != null && r.first == ctx.device.playback.state.library, tap = play) { songSheet(ctx, r, play) }
        }
    }
}

fun LibList.folderCount(): Int {
    var n = 0
    while (true) {
        val r = rows[n.toLong()] ?: return n
        if (r.isTrack()) return n
        n++
    }
}

fun LibList.folderStart(i: Int): Long {
    val folders = folderCount()
    return (0 until folders).sumOf { rows[it.toLong()]?.count ?: 0L } + (i - folders)
}

private fun folderRow(ctx: Ctx, folder: String, i: Int, r: LibRow, list: LibList, open: (String) -> Unit): RowSpec {
    val name = r.name ?: ""
    if (!r.isTrack()) {
        return RowSpec(name, songs(r.count ?: 0), bold = true, tap = { open(r.key ?: name) }) {
            groupSheet(ctx, name, Enums.View.folders, r.key ?: name, r.count)
        }
    }
    val song = i - list.folderCount()
    val play = { playAndShow(ctx, Enums.View.folders, folder, list.folderStart(i)) }
    return RowSpec(name, number = "${song + 1}", playing = r.first != null && r.first == ctx.device.playback.state.library, tap = play) { songSheet(ctx, r, play) }
}

@Composable
fun PlaylistRows(ctx: Ctx, top: Float? = null) {
    val ui = LocalUi.current
    val list = libList(ctx.device, ctx.control, Enums.View.playlists, "")
    val fav = libList(ctx.device, ctx.control, Enums.View.favorites, "")
    var focus by rememberSaveable { mutableIntStateOf(0) }
    val state = rememberLazyListState()
    val slots = ctx.device.playback.state.slots ?: 0L
    Rows(top ?: ui.contentTop, state) {
        item {
            ListRow("now playing", Modifier.enter().testTag("row.nowplaying"), songs(slots), bold = true, focused = focus == 0) {
                focus = 0
                ctx.nav.popTo(Page.NowPlaying)
            }
        }
        item {
            ListRow("favorites", Modifier.enter().testTag("row.favorites"), songs(fav?.total ?: 0), bold = true, focused = focus == 1, onTap = {
                focus = 1
                if ((fav?.total ?: 0L) > 0L) listSource(ctx, Enums.View.favorites, "", "favorites", fav?.total)
            }, onLong = { listSheet(ctx, "favorites", Enums.View.favorites, "", fav?.total) })
        }
        val n = (list?.total ?: 0L).toInt()
        items(n) { i ->
            val r = list?.row(i, ctx.control, Enums.View.playlists, "")
            val name = r?.name ?: ""
            ListRow(name, Modifier.enter().testTag("row.playlist.$i"), songs(r?.count ?: 0), bold = true, focused = focus == i + 2, onTap = {
                focus = i + 2
                if (r != null) listSource(ctx, Enums.View.playlists, r.key ?: name, name, r.count)
            }, onLong = { if (r != null) listSheet(ctx, name, Enums.View.playlists, r.key ?: name, r.count) })
        }
    }
}

@Composable
fun Playlists(ctx: Ctx) {
    val n = libList(ctx.device, ctx.control, Enums.View.playlists, "")?.total ?: 0L
    HubPage("playlists", { ctx.nav.pop() }, count = if (n == 1L) "1 playlist" else "$n playlists", status = ListLine.card(ctx.device.playback.state.card)) { PlaylistRows(ctx) }
}

class Shown(val rev: Long?, val list: LibList)

@Composable
fun ListPage(ctx: Ctx, page: Page.List, held: MutableMap<LibKey, Shown>) {
    val d = ctx.device
    val key = LibKey(page.view, page.key)
    val state = rememberLazyListState()
    val list = libList(d, ctx.control, page.view, page.key)
    val card = d.playback.state.card
    val root = d.lib.pages[LibKey(Enums.View.playlists, "")]
    val first by remember(state) { derivedStateOf { state.firstVisibleItemIndex.toLong() } }
    val last by remember(state) { derivedStateOf { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.toLong() ?: state.firstVisibleItemIndex.toLong() } }
    val ready = list != null && (list.total == 0L || (list.firstMissing(0, 1) == null && list.firstMissing(first, minOf(last + 1, list.total)) == null))
    SideEffect { if (ready && list != null && page in ctx.nav.stack && held[key]?.list !== list) held[key] = Shown(d.lib.rev, list) }
    val drawn = if (ready) list else held[key]?.list ?: list
    val line = ListLine.list(card, drawn?.total, ListGone.gone(page.view, page.key, card, root))
    val drawnRev = if (ready) d.lib.rev else held[key]?.rev ?: d.lib.rev
    LaunchedEffect(list?.rows?.size, first, last) {
        val m = list ?: return@LaunchedEffect
        if (!ready) m.firstMissing(first, minOf(last + 1, m.total))?.let { ctx.control.libGet(page.view, page.key, it) }
    }
    val walk = page.view == Enums.View.playlists && (list == null || list.total == 0L) && root != null
    LaunchedEffect(walk, root?.rows?.size, d.lib.rev) {
        if (walk && root != null) root.firstMissing(0, root.total)?.let { ctx.control.libGet(Enums.View.playlists, "", it) }
    }
    var pending by remember { mutableStateOf(false) }
    val seen = remember { arrayOf(card) }
    LaunchedEffect(card) {
        val down = card == Enums.Card.out || card == Enums.Card.error
        pending = down && (pending || (seen[0] != Enums.Card.out && seen[0] != Enums.Card.error))
        seen[0] = card
    }
    LaunchedEffect(pending, ctx.sheet.shown, ctx.nav.turning) {
        if (pending && !ctx.sheet.shown && !ctx.nav.turning && ctx.nav.top == page) {
            pending = false
            ctx.nav.pop()
        }
    }
    val count = drawn?.total?.takeIf { line == null }?.let { songs(it) }
    HubPage(page.name, { ctx.nav.pop() }, count = count, headerCase = case(Tok.type_listCollection_case), headerWeight = weight(Tok.type_listCollection_style), status = ListLine.card(card)) {
        Pane(ctx, page.view, page.key, state = state, line = line, list = drawn) { i, r, _ ->
            RowSpec(r.name ?: "", r.artist ?: "", "${i + 1}", playing = r.first != null && r.first == d.playback.state.library, libSub = true, tap = { listPlay(ctx, ListPlays.Want(page.view, page.key, page.name, r.first, i.toLong(), rev = drawnRev)) })
        }
    }
}

@Composable
fun ArtistDetail(ctx: Ctx, page: Page.Artist) {
    val ui = LocalUi.current
    HubPage("artists", { ctx.nav.pop() }, listOf("albums"), 0, {}, headerCase = Roles.pivotActive.case) {
        val nameW = ui.px(ui.detailNameRight - Tok.hubEdge)
        RoleText(page.name, Roles.detailName, Modifier.atBaseline(ui.px(Tok.hubEdge), ui.px(ui.detailNameBaseline), nameW).testTag("artist.name"))
        val rings = listOf(GlyphName.PLAY to false, GlyphName.SHUFFLE to true)
        rings.forEach { (g, shuffle) ->
            val idx = if (shuffle) 2 else 0
            val press = rememberPress()
            Box(
                Modifier.at(ui.px(detailRingX(idx)) - ui.px(Tok.detailRingGap) / 2, ui.px(ui.detailRingTop) - ui.px(Tok.detailRingGap) / 2)
                    .size(ui.px(Tok.detailRing + Tok.detailRingGap).dp())
                    .press({
                        playSource(ctx, Enums.View.artistAlbums, page.name, page.songs ?: ctx.device.lib.pages[LibKey(Enums.View.artists, "")]?.rows?.values?.firstOrNull { (it.key ?: it.name) == page.name }?.count, shuffle)
                    }, press = press).testTag(if (shuffle) "artist.shuffle" else "artist.play"),
            ) {
                Box(Modifier.at(ui.px(Tok.detailRingGap) / 2, ui.px(Tok.detailRingGap) / 2)) { Ring(Tok.detailRing, g, Look.Touch, press = press, tag = if (shuffle) "artist.shuffle" else "artist.play") }
            }
        }
        RoleText("in collection", Roles.sectionLabel, Modifier.atBaseline(ui.px(ui.detailListX + Tok.hubEdge), ui.px(ui.detailLabelBaseline)))
        Pane(ctx, Enums.View.artistAlbums, page.name, ui.detailRowsTop, ui.detailListX) { _, r, _ ->
            val name = r.name ?: ""
            RowSpec(name, songs(r.count ?: 0), countLine = true, tap = { ctx.nav.push(Page.Album(r.key ?: "", name, page.name)) }) {
                groupSheet(ctx, name, Enums.View.albumTracks, r.key ?: "", r.count)
            }
        }
    }
}

private const val ALBUM_HEAD = 1
private const val DESCENT_PROBE = "x"

@Composable
fun AlbumDetail(ctx: Ctx, page: Page.Album) {
    val ui = LocalUi.current
    val state = rememberLazyListState()
    val lead = ui.albumLead
    val prior = remember { floatArrayOf(lead) }
    if (prior[0] == 0f && lead > 0f && state.firstVisibleItemIndex == ALBUM_HEAD && state.firstVisibleItemScrollOffset < ui.px(lead)) state.requestScrollToItem(0, state.firstVisibleItemScrollOffset)
    SideEffect { prior[0] = lead }
    val y by remember(state, ui) { derivedStateOf { if (state.firstVisibleItemIndex == 0) min(state.firstVisibleItemScrollOffset / ui.u, lead) else lead } }
    val dock = albumDock(y)
    val side = albumSide(y)
    val measurer = rememberTextMeasurer()
    val albumStyle = textStyle(Roles.npAlbum, white(Roles.npAlbum.alpha))
    val rise = remember(albumStyle, ui) {
        val m = measurer.measure(DESCENT_PROBE, albumStyle)
        max(1f, ui.albumArtTop - ui.albumTitleBaseline - (m.size.height - m.firstBaseline) / ui.u)
    }
    Box(Modifier.fillMaxSize().background(Black).testTag("album")) {
        Pane(ctx, Enums.View.albumTracks, page.key, ui.albumRowsTop - lead, ui.albumRowsX, state, ALBUM_HEAD, lead) { i, r, _ ->
            val play = { playAndShow(ctx, Enums.View.albumTracks, page.key, i.toLong()) }
            RowSpec(r.name ?: "", number = "${i + 1}", playing = r.first != null && r.first == ctx.device.playback.state.library, tap = play) { songSheet(ctx, r, play) }
        }
        Box(Modifier.at(0f, 0f).size(ui.px(ui.w).dp(), ui.px(ui.albumRowsTop - y).dp()).background(Black).press({}))
        HeaderWord("albums", Roles.albumHeader, ui.px(Tok.page_albumHeaderX * ui.headerScale), albumHeaderTracking, "album.header") { ctx.nav.pop() }
        val textX = ui.px(ui.albumLineX(dock, rise))
        val textW = ui.px(ui.w - Tok.edge) - textX
        RoleText(page.artist, Roles.npArtist, Modifier.atBaseline(textX, ui.px(ui.albumArtistBaseline), textW).testTag("album.artist"))
        RoleText(page.title, Roles.npAlbum, Modifier.atBaseline(textX, ui.px(ui.albumTitleBaseline), textW).testTag("album.title"))
        val track = page.key.toULongOrNull()?.toLong()
        val px = ui.px(Tok.tileL).roundToInt()
        LaunchedEffect(track, px, ctx.device.hello) { if (track != null) ctx.control.wantArt(Thumbs.TILE, track, px, page.title) }
        Box(Modifier.at(ui.px(Tok.albumArtX), ui.px(ui.albumArtTop - dock)).size(ui.px(side).dp()).background(Color(Tok.color_tile)).testTag("album.art")) {
            track?.let { ctx.control.art(it, px) }?.let { Image(it, null, Modifier.fillMaxSize().testTag("album.art.image"), contentScale = ContentScale.Crop) }
        }
    }
}

@Composable
fun Queue(ctx: Ctx) {
    val ui = LocalUi.current
    val s = ctx.device.playback.state
    val q = ctx.device.queue
    val total = (s.slots ?: q.total).toInt()
    val current = s.slot?.toInt() ?: -1
    val epoch = ctx.device.card.epoch
    LaunchedEffect(q.rev, s.queueRev, epoch) {
        if (ctx.device.has("queue") && q.items.isEmpty() && total > 0) ctx.control.queuePage(0)
    }
    var focus by rememberSaveable { mutableIntStateOf(current) }
    val visible = ((ui.h - ui.contentTop) / Tok.rowHeight).toInt().coerceAtLeast(1)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (current - visible + 1).coerceAtLeast(0))
    val count = "${if (total > 0 && current >= 0) current + 1 else 0} of $total"
    val line = ListLine.queue(s.card, s.slots)
    HubPage("queue", { ctx.nav.pop() }, count = count.takeIf { line == null }, status = ListLine.card(s.card)) {
        Rows(ui.contentTop, state) {
            if (line != null) item { LineRow(line, ui.px(ui.w)) }
            items(total) { i ->
                val item = q.items[i.toLong()]
                LaunchedEffect(item == null, i, epoch) {
                    if (item == null && ctx.device.has("queue")) ctx.control.queuePage(i / LIB_PAGE * LIB_PAGE)
                }
                val title = item?.title ?: ""
                ListRow(
                    title, Modifier.enter().testTag("queue.$i"), secondary = item?.artists?.joinToString(", ") ?: "", number = "${i + 1}", playing = i == current, focused = focus == i,
                    onTap = {
                        focus = i
                        if (!playRefused(ctx.live(), ctx.toast)) ctx.control.queueOp(Enums.QueueCmd.playAt, slot = i.toLong())
                    },
                    onLong = {
                        focus = i
                        val items = buildList {
                            add("remove" to { ctx.control.queueOp(Enums.QueueCmd.remove, slot = i.toLong()) })
                            if (i != current && i != current + 1 && item != null) {
                                val to = if (i < current) current else current + 1
                                add("play next" to { ctx.control.queueOp(Enums.QueueCmd.move, slot = i.toLong(), to = to.toLong()) })
                            }
                            if (i > 0) add("move up" to { ctx.control.queueOp(Enums.QueueCmd.move, slot = i.toLong(), to = i - 1L) })
                            if (i < total - 1) add("move down" to { ctx.control.queueOp(Enums.QueueCmd.move, slot = i.toLong(), to = i + 1L) })
                        }
                        ctx.sheet.open(title, items.map { it.first }) { items[it].second() }
                    },
                )
            }
        }
    }
}
