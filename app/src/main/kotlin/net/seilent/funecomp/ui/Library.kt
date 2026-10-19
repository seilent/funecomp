package net.seilent.funecomp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import kotlin.math.roundToInt
import kotlin.random.Random
import net.seilent.funecomp.art.Thumbs
import net.seilent.funecomp.proto.LibRow
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.state.LibKey
import net.seilent.funecomp.state.LibList

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
        if (r == null) control.libGet(view, parent, i / LIB_PAGE * LIB_PAGE)
    }
    return r
}

class Ctx(val device: DeviceState, val control: Control, val nav: Nav, val sheet: SheetState, val toast: ToastState)

private val MUSIC_VIEWS = listOf(Enums.View.artists, Enums.View.albums, Enums.View.songs, Enums.View.playlists, Enums.View.folders)

@Composable
fun Music(ctx: Ctx) {
    var pivot by rememberSaveable { mutableIntStateOf(0) }
    var folder by rememberSaveable { mutableStateOf("") }
    val inFolder = MUSIC_VIEWS[pivot] == Enums.View.folders && folder.isNotEmpty()
    val up = { folder = folder.substringBeforeLast('/', "") }
    BackHandler(inFolder) { up() }
    val back = {
        if (inFolder) up() else ctx.nav.pop()
        Unit
    }
    HubPage("music", back, MUSIC_VIEWS.map { Enums.View.names.getValue(it) }, pivot, { pivot = it }) { shown ->
        when (val view = MUSIC_VIEWS[shown]) {
            Enums.View.playlists -> PlaylistRows(ctx)
            Enums.View.folders -> Pane(ctx, view, folder) { i, r, list -> folderRow(ctx, folder, i, r, list) { folder = it } }
            else -> Pane(ctx, view, "") { i, r, _ -> musicRow(ctx, view, i, r) }
        }
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
    val tap: () -> Unit,
    val long: (() -> Unit)? = null,
)

@Composable
fun Pane(ctx: Ctx, view: Int, parent: String, top: Float? = null, x: Float = 0f, spec: (Int, LibRow, LibList) -> RowSpec) {
    val ui = LocalUi.current
    val list = libList(ctx.device, ctx.control, view, parent)
    var focus by rememberSaveable(view, parent) { mutableIntStateOf(0) }
    val state = rememberLazyListState()
    Rows(top ?: ui.contentTop, state, x, focus = focus) {
        val n = (list?.total ?: 0L).toInt()
        items(n) { i ->
            val r = list?.row(i, ctx.control, view, parent)
            Box(Modifier.enter { i - state.firstVisibleItemIndex }) {
                if (r == null) {
                    Box(Modifier.size((ui.px(ui.w) - ui.px(x)).dp(), ui.px(Tok.rowHeight).dp()))
                } else {
                    val s = spec(i, r, list)
                    ListRow(
                        s.primary, Modifier.testTag("row.$i"), s.secondary, s.number, "", s.bold, s.countLine, s.ring,
                        playing = s.playing, focused = focus == i, width = ui.px(ui.w) - ui.px(x),
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

private fun playAndShow(ctx: Ctx, view: Int, parent: String, start: Long, shuffle: Boolean = false) {
    ctx.control.play(view, parent, start, shuffle)
    ctx.nav.popTo(Page.NowPlaying)
}

private fun playSource(ctx: Ctx, view: Int, parent: String, count: Long?, shuffle: Boolean = false) {
    val start = sourceStart(count, shuffle || ctx.device.playback.state.shuffle == true)
    if (start == null) ctx.control.log("start 0 (no count)")
    playAndShow(ctx, view, parent, start ?: 0L, shuffle)
}

private fun songSheet(ctx: Ctx, r: LibRow, play: () -> Unit) {
    ctx.sheet.open(r.name ?: "", listOf("play", "play next")) {
        when (it) {
            0 -> play()
            1 -> r.first?.let { id ->
                ctx.control.queueOp(Enums.QueueCmd.playNext, tracks = listOf(id))
                ctx.toast.show("added 1 song", r.name ?: "")
            }
        }
    }
}

private fun groupSheet(ctx: Ctx, title: String, view: Int, parent: String, count: Long?, artist: LibRow? = null) {
    val items = listOf("play", "shuffle") + if (artist != null) listOf(PICK_IMAGE) else emptyList()
    ctx.sheet.open(title, items) {
        if (it == 2) pickSheet(ctx, PickFor(title, artist?.mbid, library = true)) else playSource(ctx, view, parent, count, shuffle = it == 1)
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
            RowSpec(name, number = "${i + 1}", playing = r.first != null && r.first == ctx.device.playback.state.track, tap = play) { songSheet(ctx, r, play) }
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
    return RowSpec(name, number = "${song + 1}", playing = r.first != null && r.first == ctx.device.playback.state.track, tap = play) { songSheet(ctx, r, play) }
}

@Composable
fun PlaylistRows(ctx: Ctx, top: Float? = null) {
    val ui = LocalUi.current
    val list = libList(ctx.device, ctx.control, Enums.View.playlists, "")
    val fav = libList(ctx.device, ctx.control, Enums.View.favorites, "")
    var focus by rememberSaveable { mutableIntStateOf(0) }
    val state = rememberLazyListState()
    val slots = ctx.device.playback.state.slots ?: 0L
    Rows(top ?: ui.contentTop, state, focus = focus) {
        item {
            ListRow("now playing", Modifier.enter { 0 }.testTag("row.nowplaying"), songs(slots), bold = true, ring = true, focused = focus == 0) {
                focus = 0
                ctx.nav.popTo(Page.NowPlaying)
            }
        }
        item {
            ListRow("favorites", Modifier.enter { 1 }.testTag("row.favorites"), songs(fav?.total ?: 0), bold = true, ring = true, focused = focus == 1, onTap = {
                focus = 1
                if ((fav?.total ?: 0L) > 0L) playSource(ctx, Enums.View.favorites, "", fav?.total)
            }, onLong = { groupSheet(ctx, "favorites", Enums.View.favorites, "", fav?.total) })
        }
        val n = (list?.total ?: 0L).toInt()
        items(n) { i ->
            val r = list?.row(i, ctx.control, Enums.View.playlists, "")
            val name = r?.name ?: ""
            ListRow(name, Modifier.enter { i + 2 - state.firstVisibleItemIndex }.testTag("row.playlist.$i"), songs(r?.count ?: 0), bold = true, ring = true, focused = focus == i + 2, onTap = {
                focus = i + 2
                if (r != null) playSource(ctx, Enums.View.playlists, r.key ?: name, r.count)
            }, onLong = { if (r != null) groupSheet(ctx, name, Enums.View.playlists, r.key ?: name, r.count) })
        }
    }
}

@Composable
fun Playlists(ctx: Ctx) {
    val n = libList(ctx.device, ctx.control, Enums.View.playlists, "")?.total ?: 0L
    HubPage("playlists", { ctx.nav.pop() }, count = if (n == 1L) "1 playlist" else "$n playlists") { PlaylistRows(ctx) }
}

@Composable
fun ArtistDetail(ctx: Ctx, page: Page.Artist) {
    val ui = LocalUi.current
    var ring by rememberSaveable { mutableIntStateOf(0) }
    HubPage("artist", { ctx.nav.pop() }, listOf("albums"), 0, {}) {
        val nameW = ui.px(ui.detailNameRight - Tok.hubEdge)
        RoleText(page.name, Roles.detailName, Modifier.atBaseline(ui.px(Tok.hubEdge), ui.px(ui.detailNameBaseline), nameW).testTag("artist.name"))
        val rings = listOf(GlyphName.PLAY to false, GlyphName.SHUFFLE to true)
        rings.forEachIndexed { i, (g, shuffle) ->
            val idx = if (shuffle) 2 else 0
            var down by remember { mutableStateOf(false) }
            Box(
                Modifier.at(ui.px(detailRingX(idx)) - ui.px(Tok.detailRingGap) / 2, ui.px(DETAIL_RING_TOP) - ui.px(Tok.detailRingGap) / 2)
                    .size(ui.px(Tok.detailRing + Tok.detailRingGap).dp())
                    .press({
                        ring = i
                        playSource(ctx, Enums.View.artistAlbums, page.name, page.songs ?: ctx.device.lib.pages[LibKey(Enums.View.artists, "")]?.rows?.values?.firstOrNull { (it.key ?: it.name) == page.name }?.count, shuffle)
                    }) { down = it }.testTag(if (shuffle) "artist.shuffle" else "artist.play"),
            ) {
                Box(Modifier.at(ui.px(Tok.detailRingGap) / 2, ui.px(Tok.detailRingGap) / 2)) { Ring(Tok.detailRing, g, ring == i, pressed = down) }
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

@Composable
fun AlbumDetail(ctx: Ctx, page: Page.Album) {
    val ui = LocalUi.current
    Box(Modifier.fillMaxSize().background(Black).testTag("album")) {
        Pane(ctx, Enums.View.albumTracks, page.key, ui.albumRowsTop, ui.albumRowsX) { i, r, _ ->
            val play = { playAndShow(ctx, Enums.View.albumTracks, page.key, i.toLong()) }
            RowSpec(r.name ?: "", number = "${i + 1}", playing = r.first != null && r.first == ctx.device.playback.state.track, tap = play) { songSheet(ctx, r, play) }
        }
        Box(Modifier.at(0f, 0f).size(ui.px(ui.w).dp(), ui.px(ui.albumRowsTop).dp()).background(Black).press({}))
        val textX = ui.px(ui.albumTextX)
        val textW = ui.px(ui.w - Tok.edge) - textX
        RoleText(page.artist, Roles.npArtist, Modifier.atBaseline(textX, ui.px(ui.albumArtistBaseline), textW))
        RoleText(page.title, Roles.npAlbum, Modifier.atBaseline(textX, ui.px(ui.albumTitleBaseline), textW).testTag("album.title"))
        val track = page.key.toULongOrNull()?.toLong()
        val px = ui.px(Tok.tileL).roundToInt()
        LaunchedEffect(track, px, ctx.device.hello) { if (track != null) ctx.control.wantArt(Thumbs.TILE, track, px, page.title) }
        Box(Modifier.at(ui.px(Tok.albumArtX), ui.px(ui.albumArtTop)).size(ui.px(Tok.tileL).dp()).background(Color(Tok.color_tile)).testTag("album.art")) {
            track?.let { ctx.control.art(it, px) }?.let { Image(it, null, Modifier.fillMaxSize().testTag("album.art.image"), contentScale = ContentScale.Crop) }
        }
        Status(ctx.device)
        BackRing { ctx.nav.pop() }
    }
}

@Composable
fun Queue(ctx: Ctx) {
    val ui = LocalUi.current
    val s = ctx.device.playback.state
    val q = ctx.device.queue
    val total = (s.slots ?: q.total).toInt()
    val current = s.slot?.toInt() ?: -1
    LaunchedEffect(q.rev, s.queueRev) {
        if (ctx.device.has("queue") && q.items.isEmpty() && total > 0) ctx.control.queuePage(0)
    }
    var focus by rememberSaveable { mutableIntStateOf(current) }
    val visible = ((ui.h - ui.contentTop) / Tok.rowHeight).toInt().coerceAtLeast(1)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (current - visible + 1).coerceAtLeast(0))
    val count = "${if (total > 0 && current >= 0) current + 1 else 0} of $total"
    HubPage("queue", { ctx.nav.pop() }, count = count) {
        Rows(ui.contentTop, state, focus = focus) {
            items(total) { i ->
                val item = q.items[i.toLong()]
                LaunchedEffect(item == null, i) {
                    if (item == null && ctx.device.has("queue")) ctx.control.queuePage(i / LIB_PAGE * LIB_PAGE)
                }
                val title = item?.title ?: ""
                ListRow(
                    title, Modifier.enter { i - state.firstVisibleItemIndex }.testTag("queue.$i"), number = "${i + 1}", playing = i == current, focused = focus == i,
                    onTap = {
                        focus = i
                        ctx.control.queueOp(Enums.QueueCmd.playAt, slot = i.toLong())
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
