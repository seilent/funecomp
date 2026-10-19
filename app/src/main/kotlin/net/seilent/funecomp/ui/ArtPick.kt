package net.seilent.funecomp.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import net.seilent.funecomp.art.Forward
import net.seilent.funecomp.artist.Offer
import net.seilent.funecomp.artist.Offered
import net.seilent.funecomp.artist.Sources
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tok
import kotlin.math.roundToInt

const val PICK_IMAGE = "pick image"
const val UNDO_PICK = "undo pick"
const val BLOCK_IMAGE = "block image"
const val WRONG_ARTIST = "wrong artist"
const val TRY_AGAIN = "try again"
const val PICKED = "picked"
const val NO_LIBRARY = "no library"
const val NO_IMAGE = "no image"
const val LINK_BROKEN = "link broken"
private const val PASTE_LINK = "paste link"
private const val BROWSE = "browse"
private const val USE_IMAGE = "use image"
private const val NO_IMAGES = "no images"
private const val YOUTUBE_UNAVAILABLE = "youtube search unavailable"
private const val CANCEL = "cancel"
private const val STATE_TAG = "pick.state"

internal var browseCols: (Float) -> Int = { Tok.gridCols.toInt() }

fun artistChooser(ctx: Ctx) {
    val s = ctx.device.playback.state
    val artists = s.artists.withIndex().filter { (_, n) -> n.isNotBlank() && n.trim().lowercase() !in Forward.PLACEHOLDERS }.map { (i, n) -> PickFor(n, s.mbids.getOrNull(i)) }
    when (artists.size) {
        0 -> {}
        1 -> pickSheet(ctx, artists.single())
        else -> ctx.sheet.open(PICK_IMAGE, artists.map { it.name }) { pickSheet(ctx, artists[it]) }
    }
}

fun pickSheet(ctx: Ctx, target: PickFor) = ctx.sheet.open(target.name, listOf(PASTE_LINK, BROWSE)) {
    if (it == 0) pasteLink(ctx, target) else ctx.nav.push(Page.Browse(target))
}

private fun pasteLink(ctx: Ctx, target: PickFor) = ctx.sheet.openEntry(PASTE_LINK) {
    Entry("", keyboard = KeyboardType.Uri) { text ->
        ctx.sheet.close()
        ctx.control.pickLink(text) { o ->
            when (o) {
                is Offered.Ok -> confirmPick(ctx, target, o.offer)
                is Offered.Refused -> ctx.toast.show(target.name, o.reason)
            }
        }
    }
}

fun confirmPick(ctx: Ctx, target: PickFor, offer: Offer) {
    val info = listOf(Sources.label(offer.source), offer.title).filter { it.isNotBlank() }.joinToString(", ")
    ctx.sheet.open(target.name, listOf(USE_IMAGE, CANCEL), info = listOf(info)) { if (it == 0) ctx.control.pick(target, offer) }
}

@Composable
fun PickArtistPage(ctx: Ctx) {
    HubPage(PICK_IMAGE, { ctx.nav.pop() }) {
        Pane(ctx, Enums.View.artists, "") { _, r, _ ->
            val name = r.name ?: ""
            RowSpec(name, songs(r.count ?: 0), countLine = true, tap = { pickSheet(ctx, PickFor(name, r.mbid, library = true)) })
        }
    }
}

@Composable
fun BrowsePage(ctx: Ctx, page: Page.Browse) {
    val ui = LocalUi.current
    val cols = browseCols(ui.w)
    val target = page.target
    val tiles = remember { mutableStateListOf<BrowseTile>() }
    var ended by remember { mutableStateOf<Boolean?>(null) }
    var focus by remember { mutableIntStateOf(-1) }
    val px = ui.px(Tok.tileS).roundToInt()
    DisposableEffect(target, px) {
        tiles.clear()
        ended = null
        val cancel = ctx.control.browse(target.name, target.mbid, px, { tiles += it }) { ended = it }
        onDispose { cancel() }
    }
    val line = when (ended) {
        null -> LOADING
        false -> YOUTUBE_UNAVAILABLE
        true -> null
    }
    HubPage(target.name, { ctx.nav.pop() }) {
        if (tiles.isEmpty()) {
            StateLine(line ?: NO_IMAGES, STATE_TAG)
        } else {
            Rows(ui.contentTop) {
                itemsIndexed(tiles.chunked(cols)) { r, row ->
                    Box(Modifier.enter { r }.size(ui.px(ui.w).dp(), ui.px(Tok.tileRowPitch).dp())) {
                        row.forEachIndexed { c, b ->
                            val i = r * cols + c
                            ArtTile(b, focus == i, Modifier.at(ui.px(Tok.hubEdge + c * (Tok.tileS + Tok.gutter)), 0f).testTag("pick.tile.$i")) {
                                focus = i
                                ctx.control.takeTile(b.tile) { o ->
                                    when (o) {
                                        is Offered.Ok -> {
                                            ctx.control.pick(target, o.offer)
                                            if (ctx.nav.top == page) ctx.nav.pop()
                                        }
                                        is Offered.Refused -> ctx.toast.show(target.name, o.reason)
                                    }
                                }
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
        if (focused) Box(Modifier.at(-edge, -edge, unbounded = true).size((side + 2 * edge).dp()).border(edge.dp(), white(Tok.alpha_full)))
        Box(Modifier.at(0f, side).size(side.dp(), ui.px(Tok.tileCaptionBand).dp()).clipToBounds()) {
            RoleText(Sources.label(b.tile.source), Roles.gridCaption, Modifier.atBaseline(0f, ui.px(Tok.tileCaptionDrop), side), color = c)
            RoleText(b.tile.caption, Roles.gridCaption, Modifier.atBaseline(0f, ui.px(Tok.tileCaptionDrop + Tok.tileCaptionPitch), side))
        }
    }
}
