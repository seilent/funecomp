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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import net.seilent.funecomp.art.Forward
import net.seilent.funecomp.artist.ArtPusher
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
    val tiles = mutableStateListOf<BrowseTile>()
    var ended by mutableStateOf<Boolean?>(null)
    var focus by mutableIntStateOf(-1)
    val list = LazyListState(0, 0)
    var live by mutableStateOf(true)
        private set
    internal var cancel: () -> Unit = {}
    internal var begin: (BrowseSession) -> () -> Unit = { {} }

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
            ctx.browse.start(key) { b -> ctx.control.browse(target.name, target.mbid, px, {}, { _, t -> if (b.live) b.tiles += t }, {}) { if (b.live) b.ended = it } }
        }
        onDispose {}
    }
    val tiles = s?.tiles ?: emptyList()
    val line = when (s?.ended) {
        null -> LOADING
        false -> YOUTUBE_UNAVAILABLE
        true -> null
    }
    HubPage(target.name, { ctx.nav.pop() }) {
        if (s == null || tiles.isEmpty()) {
            StateLine(line ?: NO_IMAGES, STATE_TAG)
        } else {
            Rows(ui.contentTop, state = s.list) {
                itemsIndexed(tiles.chunked(cols)) { r, row ->
                    Box(Modifier.enter { r }.size(ui.px(ui.w).dp(), ui.px(Tok.tileRowPitch).dp())) {
                        row.forEachIndexed { c, b ->
                            val i = r * cols + c
                            ArtTile(b, s.focus == i, Modifier.at(ui.px(Tok.hubEdge + c * (Tok.tileS + Tok.gutter)), 0f).testTag("pick.tile.$i")) {
                                s.focus = i
                                resolving(ctx, target, fromBrowse = true) { done -> ctx.control.takeTile(b.tile, done) }
                            }
                        }
                    }
                }
                if (line != null) item(key = STATE_TAG) { StateRow(line, STATE_TAG) }
            }
        }
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
            RoleText(Sources.label(b.tile.source), Roles.gridCaption, Modifier.atBaseline(0f, ui.px(Tok.tileCaptionDrop), side), color = c)
            RoleText(b.tile.caption, Roles.gridCaption, Modifier.atBaseline(0f, ui.px(Tok.tileCaptionDrop + Tok.tileCaptionPitch), side))
        }
    }
}
