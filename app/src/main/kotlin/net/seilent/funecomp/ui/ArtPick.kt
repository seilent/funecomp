package net.seilent.funecomp.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import net.seilent.funecomp.art.Forward
import net.seilent.funecomp.artist.ArtPusher
import net.seilent.funecomp.artist.ArtistArt
import net.seilent.funecomp.artist.Sources
import net.seilent.funecomp.proto.gen.Tok
import kotlin.math.roundToInt

const val PICK_IMAGE = "pick image"
const val UNDO_PICK = "undo pick"
const val BLOCK_IMAGE = "block image"
const val WRONG_ARTIST = "wrong artist"
const val TRY_AGAIN = "try again"
const val PICKED = "picked"
const val NO_IMAGE = "no image"
const val LINK_BROKEN = "link broken"
private const val PASTE_LINK = "paste link"
private const val BROWSE = "browse"
private const val PICK = "pick"
private const val PICK_X = 75f
private const val NEXT_IMAGE = "next image"
private const val FROM_GALLERY = "from gallery"
private const val NO_IMAGES = "no images"
private const val YOUTUBE_UNAVAILABLE = "youtube search unavailable"
private const val CANCEL = "cancel"
private const val STATE_TAG = "pick.state"
private const val YOUTUBE_SEARCH = ArtistArt.YOUTUBE_SEARCH

internal var browseCols: (Float) -> Int = { Tok.gridCols.toInt() }

fun artistChooser(ctx: Ctx) {
    val s = ctx.device.playback.state
    val artists = s.artists.withIndex().filter { (_, n) -> n.isNotBlank() && n.trim().lowercase() !in Forward.PLACEHOLDERS }.map { (i, n) -> PickFor(n, s.mbids.getOrNull(i), origin = ArtPusher.origin(s.source)) }
    when (artists.size) {
        0 -> {}
        1 -> pickSheet(ctx, artists.single())
        else -> ctx.sheet.open(PICK_IMAGE, artists.map { it.name }) { pickSheet(ctx, artists[it]) }
    }
}

fun pickSheet(ctx: Ctx, target: PickFor) = ctx.sheet.open(target.name, listOf(PASTE_LINK, BROWSE, FROM_GALLERY)) {
    when (it) {
        0 -> pasteLink(ctx, target)
        1 -> ctx.nav.push(Page.Browse(target))
        else -> resolving(ctx, target) { done -> ctx.control.pickGallery(done) }
    }
}

private fun pasteLink(ctx: Ctx, target: PickFor) = ctx.sheet.openEntry(PASTE_LINK) {
    Entry("", keyboard = KeyboardType.Uri) { text ->
        resolving(ctx, target) { done -> ctx.control.pickLink(text, target.name, done) }
    }
}

private fun resolving(ctx: Ctx, target: PickFor, fromBrowse: Boolean = false, start: ((Previewed) -> Unit) -> () -> Unit) {
    val token = Any()
    var cancel: () -> Unit = {}
    ctx.sheet.open(target.name, listOf(CANCEL), info = listOf(LOADING), onDismiss = { cancel() }, owner = token) { cancel() }
    cancel = start { p ->
        if (!ctx.sheet.owns(token)) return@start
        ctx.sheet.closeIf(token)
        when (p) {
            is Previewed.Ok -> ctx.nav.push(Page.PickPreview(target, p.previews, fromBrowse))
            is Previewed.Refused -> ctx.toast.show(target.name, p.reason)
            Previewed.Closed -> {}
        }
    }
}

@Composable
fun PickPreviewPage(ctx: Ctx, page: Page.PickPreview) {
    val ui = LocalUi.current
    var at by remember(page) { mutableIntStateOf(0) }
    val shown = page.previews[at.coerceIn(0, page.previews.lastIndex)]
    val two = page.previews.size > 1
    val measurer = rememberTextMeasurer()
    val style = textStyle(Roles.dialogItem, white(Roles.dialogItem.alpha))
    val pick = measurer.measure(shown(PICK, Roles.dialogItem.case), style)
    val x = ui.px(PICK_X)
    val baseline = ui.px(ui.h - Tok.edge)
    val top = if (two) baseline - ui.px(Tok.settingsPitch) else baseline
    val photoH = (top - pick.firstBaseline - ui.px(Tok.edge)).coerceAtLeast(0f)
    Box(Modifier.fillMaxSize().testTag("pick.page")) {
        Box(Modifier.fillMaxSize().bleed(LocalBleed.current).background(Black))
        shown.image?.let { Image(it, null, Modifier.size(ui.px(ui.w).dp(), photoH.dp()).testTag("pick.preview"), contentScale = ContentScale.Fit) }
        BackRing { ctx.nav.pop() }
        RoleText(
            PICK,
            Roles.dialogItem,
            Modifier.atBaseline(x, baseline).press({
                if (ctx.nav.top != page) return@press
                ctx.control.pick(page.target, shown.offer)
                ctx.nav.pop()
                if (page.fromBrowse && ctx.nav.top == Page.Browse(page.target)) ctx.nav.pop()
            }).testTag("pick.label"),
        )
        if (two) {
            RoleText(shown.offer.label, Roles.dialogItem, Modifier.atBaseline(x, top).testTag("pick.source"), alpha = Roles.dialogItem.dim)
            RoleText(NEXT_IMAGE, Roles.dialogItem, Modifier.atBaseline(x + pick.size.width + ui.px(Tok.pivotGap), baseline).press({ at = (at + 1) % page.previews.size }).testTag("pick.next"))
        }
    }
}

@Stable
class BrowseSession {
    var sections by mutableStateOf<List<String>>(emptyList())
    val tiles = mutableStateMapOf<String, SnapshotStateList<BrowseTile>>()
    var searchOk by mutableStateOf<Boolean?>(null)
    var ended by mutableStateOf<Boolean?>(null)
    var engaged by mutableStateOf(false)
    var focus by mutableStateOf<Pair<String, Int>?>(null)
    val list = LazyListState(0, 0)
    internal var drawn = 0
    internal var anchor: Pair<Any, Int>? = null
    internal val shown = HashSet<String>()
    var live by mutableStateOf(true)
        private set
    internal var cancel: () -> Unit = {}
    internal var begin: (BrowseSession) -> () -> Unit = { {} }

    fun add(section: String, t: BrowseTile) {
        tiles.getOrPut(section) { mutableStateListOf() } += t
    }

    fun end(ok: Boolean) {
        if (YOUTUBE_SEARCH in sections) searchOk = ok
        ended = ok
    }

    fun stop() {
        if (!live) return
        live = false
        cancel()
    }
}

@Stable
class BrowseSessions {
    private val map = mutableStateMapOf<String, BrowseSession>()

    operator fun get(key: String): BrowseSession? = map[key]

    fun start(key: String, begin: (BrowseSession) -> () -> Unit) {
        map[key]?.stop()
        val s = BrowseSession()
        s.begin = begin
        map[key] = s
        s.cancel = begin(s)
        if (!s.live) s.cancel()
    }

    fun sync(stack: List<Page>, keep: Set<String>) {
        val on = stack.indices.filter { runs(stack, it) }.map { "$it/${stack[it]}" }.toSet()
        for ((k, s) in map.toList()) {
            if (k !in on) s.stop() else if (!s.live) start(k, s.begin)
            if (k !in keep) map.remove(k)
        }
    }

    fun stopAll() {
        map.values.forEach { it.stop() }
        map.clear()
    }

    companion object {
        fun runs(stack: List<Page>, i: Int): Boolean {
            val p = stack.getOrNull(i) as? Page.Browse ?: return false
            if (i == stack.lastIndex) return true
            val over = stack.getOrNull(i + 1) as? Page.PickPreview ?: return false
            return i + 1 == stack.lastIndex && over.fromBrowse && over.target == p.target
        }
    }
}

private sealed interface BrowseItem {
    val key: String

    class Head(val section: String) : BrowseItem {
        override val key = "h:$section"
    }

    class Gap(val section: String) : BrowseItem {
        override val key = "g:$section"
    }

    class Row(val section: String, val row: Int, val tiles: List<BrowseTile>) : BrowseItem {
        override val key = "$section:$row"
    }

    class Line(val text: String) : BrowseItem {
        override val key = "l:$text"
    }
}

private fun browseItems(s: BrowseSession, cols: Int): List<BrowseItem> = buildList {
    for (section in s.sections) {
        val tiles = s.tiles[section].orEmpty()
        val search = section == YOUTUBE_SEARCH
        val head = if (search) null else if (section == Sources.YOUTUBE) Sources.YOUTUBE else section
        val group = if (section == Sources.YOUTUBE) tiles.isNotEmpty() || s.tiles[YOUTUBE_SEARCH].orEmpty().isNotEmpty() || s.searchOk == false else tiles.isNotEmpty()
        if (head != null && group) {
            if (isNotEmpty()) add(BrowseItem.Gap(head))
            add(BrowseItem.Head(head))
        }
        tiles.chunked(cols).forEachIndexed { r, row -> add(BrowseItem.Row(section, r, row)) }
        if (search && s.searchOk == false && tiles.isEmpty()) add(BrowseItem.Line(YOUTUBE_UNAVAILABLE))
    }
    if (s.ended == null && isNotEmpty()) add(BrowseItem.Line(LOADING))
}

@Composable
fun BrowsePage(ctx: Ctx, page: Page.Browse) {
    val ui = LocalUi.current
    val cols = browseCols(ui.w)
    val target = page.target
    val key = LocalEntry.current
    val runs = BrowseSessions.runs(ctx.nav.stack, key.substringBefore('/').toIntOrNull() ?: -1)
    val s = ctx.browse[key]
    val px = ui.px(Tok.tileS).roundToInt()
    DisposableEffect(key, runs, s, s?.live) {
        if (runs && ctx.browse[key]?.live != true) {
            ctx.browse.start(key) { b ->
                ctx.control.browse(target.name, target.mbid, px, { if (b.live) b.sections = it }, { section, t -> if (b.live) b.add(section, t) }, {}) { if (b.live) b.end(it) }
            }
        }
        onDispose {}
    }
    HubPage(target.name, { ctx.nav.pop() }) {
        val items = s?.let { browseItems(it, cols) } ?: emptyList()
        if (s == null || items.none { it is BrowseItem.Row }) {
            StateLine(
                when {
                    s?.ended == null -> LOADING
                    s.searchOk == false -> YOUTUBE_UNAVAILABLE
                    else -> NO_IMAGES
                },
                STATE_TAG,
            )
        } else {
            val heads = items.count { it is BrowseItem.Head }
            if (heads != s.drawn) {
                val held = s.anchor?.takeIf { s.engaged }?.let { (k, off) -> items.indexOfFirst { it.key == k }.takeIf { it >= 0 }?.to(off) }
                if (held != null) s.list.requestScrollToItem(held.first, held.second) else if (!s.engaged) s.list.requestScrollToItem(0)
                s.drawn = heads
            }
            LaunchedEffect(s) {
                snapshotFlow { s.list.layoutInfo.visibleItemsInfo.firstOrNull()?.let { it.key to s.list.firstVisibleItemScrollOffset } }.collect { s.anchor = it }
            }
            LaunchedEffect(s) {
                snapshotFlow { s.list.isScrollInProgress }.collect { if (it) s.engaged = true }
            }
            SideEffect {
                items.filterIsInstance<BrowseItem.Head>().forEach { h -> if (s.shown.add(h.section)) ctx.control.browseShown(h.section) }
            }
            val engage = Modifier.pointerInput(s) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.changes.any { it.pressed }) s.engaged = true
                    }
                }
            }
            Box(engage) {
                Rows(ui.contentTop, state = s.list) {
                    itemsIndexed(items, key = { _, it -> it.key }) { i, item ->
                        when (item) {
                            is BrowseItem.Head -> BrowseHead(item.section, i)
                            is BrowseItem.Gap -> Box(Modifier.size(ui.px(ui.w).dp(), ui.px(Tok.qpSectionGap - Tok.qpFirstBaseline - (Tok.tileRowPitch - Tok.tileCellH)).dp()))
                            is BrowseItem.Row -> Box(Modifier.enter { i }.size(ui.px(ui.w).dp(), ui.px(Tok.tileRowPitch).dp())) {
                                item.tiles.forEachIndexed { c, b ->
                                    val n = item.row * cols + c
                                    val at = item.section to n
                                    ArtTile(b, s.focus == at, Modifier.at(ui.px(Tok.hubEdge + c * (Tok.tileS + Tok.gutter)), 0f).testTag("pick.tile.${item.section}.$n")) {
                                        s.focus = at
                                        resolving(ctx, target, fromBrowse = true) { done -> ctx.control.takeTile(b.tile, done) }
                                    }
                                }
                            }
                            is BrowseItem.Line -> StateRow(item.text, STATE_TAG)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowseHead(section: String, i: Int) {
    val ui = LocalUi.current
    Box(Modifier.enter { i }.size(ui.px(ui.w).dp(), ui.px(Tok.qpFirstBaseline + Tok.qpContentDrop).dp()).testTag("pick.head.$section")) {
        RoleText(Sources.label(section), Roles.quickplayHeader, Modifier.atBaseline(ui.px(Tok.hubEdge), ui.px(Tok.qpFirstBaseline)))
    }
}

@Composable
fun ArtTile(b: BrowseTile, focused: Boolean, modifier: Modifier = Modifier, onTap: () -> Unit) {
    val ui = LocalUi.current
    val side = ui.px(Tok.tileS)
    val edge = ui.px(Tok.focus_border)
    val c by animateColorAsState(white(if (focused) Tok.alpha_full else Roles.gridCaption.alpha), tween(Tok.motion_focusAlpha.toInt()), label = "caption")
    Pressable({ onTap() }, modifier.size(side.dp(), ui.px(Tok.tileCellH).dp())) {
        Box(Modifier.size(side.dp()).background(Color(Tok.color_tile))) {
            Image(b.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        if (focused) Box(Modifier.at(-edge, -edge, unbounded = true).size((side + 2 * edge).dp()).border(edge.dp(), white(Tok.alpha_full)).testTag("pick.focus"))
        Box(Modifier.at(0f, side).size(side.dp(), ui.px(Tok.tileCaptionBand).dp()).clipToBounds()) {
            RoleText(b.tile.caption, Roles.gridCaption, Modifier.atBaseline(0f, ui.px(Tok.tileCaptionDrop), side), color = c)
        }
    }
}
