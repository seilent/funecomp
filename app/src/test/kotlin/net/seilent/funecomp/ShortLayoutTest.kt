package net.seilent.funecomp

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.LyrLine
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.state.LyricsFetch
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.Held
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.NP_THREE_NEXT_H
import net.seilent.funecomp.ui.NP_WIDE_ROW_H
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.Shape
import net.seilent.funecomp.ui.Ui
import net.seilent.funecomp.ui.npArt
import net.seilent.funecomp.ui.npHeartFits
import net.seilent.funecomp.ui.npMarkCorner
import net.seilent.funecomp.ui.npMarkFits
import net.seilent.funecomp.ui.npNextCount
import net.seilent.funecomp.ui.npPlayRing
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.ceil

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class ShortLayoutTest {
    @get:Rule
    val rule = createComposeRule()

    private enum class Kind { LOCAL, PHONE, SERVICE }

    private class Canvas(val name: String, val w: Float, val h: Float, val bars: WindowInsets = NONE)

    private var canvas by mutableStateOf(CANVASES.first())

    private val findings = ArrayList<String>()

    private fun start(c: SimControl, pages: List<Page>, lyrics: () -> LyricsFetch.Show = { LyricsFetch.Show.Off }) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val s = canvas
            Box(Modifier.requiredSize(s.w.dp, s.h.dp)) {
                App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), c, Shell(Nav(pages)), lyrics = lyrics(), insets = s.bars)
            }
        }
        settle(1000)
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun box(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()?.boundsInRoot

    private fun text(t: String) = rule.onAllNodesWithText(t, ignoreCase = true).fetchSemanticsNodes().firstOrNull()?.let(::inkOf)

    private fun ink(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()?.let(::inkOf)

    private fun inkOf(node: SemanticsNode): Rect {
        val out = ArrayList<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(out)
        val t = out.firstOrNull() ?: return node.boundsInRoot
        val o = node.positionInRoot
        val px = with(rule.density) { t.layoutInput.style.fontSize.toPx() }
        return Rect(o.x + t.getLineLeft(0), o.y + t.firstBaseline - Tok.capHeight * px, o.x + t.getLineRight(0), o.y + t.size.height)
    }

    private fun need(boxes: List<Pair<String, Rect?>>, tags: List<String>) {
        val shown = boxes.filter { it.second != null }.map { it.first }.toSet()
        for (t in tags) if (t !in shown) findings += "${canvas.name} $t missing"
    }

    private fun crosses(a: Rect, b: Rect) = minOf(a.right, b.right) - maxOf(a.left, b.left) > 1f && minOf(a.bottom, b.bottom) - maxOf(a.top, b.top) > 1f

    private fun found(boxes: List<Pair<String, Rect?>>, frame: Rect, allowed: Set<Set<String>> = emptySet(), bottomEdge: Boolean = true) {
        val shown = boxes.mapNotNull { (n, r) -> r?.let { n to it } }
        for ((n, r) in shown) {
            if (r.width <= 0f || r.height <= 0f) findings += "${canvas.name} $n zero size"
            val out = r.left < frame.left - 1f || r.top < frame.top - 1f || r.right > frame.right + 1f || (bottomEdge && r.bottom > frame.bottom + 1f)
            if (out) findings += "${canvas.name} $n outside $r in $frame"
        }
        for (i in shown.indices) for (j in i + 1 until shown.size) {
            val (a, ra) = shown[i]
            val (b, rb) = shown[j]
            if (setOf(a, b) !in allowed && crosses(ra, rb)) findings += "${canvas.name} $a crosses $b: $ra $rb"
        }
    }

    private fun ring(tag: String, side: Float, ui: Ui): Pair<String, Rect?> {
        val hit = box(tag) ?: return "$tag.ring" to null
        val half = side * ui.u / 2
        return "$tag.ring" to Rect(hit.center.x - half, hit.center.y - half, hit.center.x + half, hit.center.y + half)
    }

    private fun nowPlaying(kind: Kind, extra: List<String> = emptyList()) {
        val frame = box("np") ?: return run { findings += "${canvas.name} no np" }
        val ui = Ui.of(frame.width, frame.height)
        val art = kind != Kind.SERVICE
        val seek = kind != Kind.PHONE
        val heart = kind == Kind.LOCAL && npHeartFits(ui.npArt)
        val mark = kind == Kind.LOCAL && npMarkFits(ui.npArt, ui.npMarkCorner)
        val next = if (kind == Kind.LOCAL) (0 until ui.npNextCount).map { "np.next.$it" } else emptyList()
        val taps = ROW + listOf("np.titleBlock", "np.seek", "np.heart", "np.mark.hit")
        val tapBoxes = taps.map { it to box(it) }
        need(tapBoxes, ROW + listOfNotNull("np.titleBlock".takeIf { kind != Kind.PHONE }, "np.seek".takeIf { seek }, "np.heart".takeIf { heart }, "np.mark.hit".takeIf { mark }))
        found(tapBoxes, frame)
        val texts = listOf("np.elapsed", "np.remaining", "np.title", "np.artist", "np.album", "np.next.0", "np.next.1", "np.next.2") + extra
        val heartRing = listOf("np.heart.off", "np.heart.on").firstNotNullOfOrNull { box(it) }
        val visuals = texts.map { it to ink(it) } + listOf("np.art" to box("np.art"), "np.mark" to box("np.mark"), "np.heart.ring" to heartRing) +
            listOf(ring("np.shuffle", Held.Np.npRing, ui), ring("np.repeat", Held.Np.npRing, ui), ring("np.toggle", ui.npPlayRing, ui), ring("np.prev", Tok.overlayGlyph, ui), ring("np.nextTrack", Tok.overlayGlyph, ui))
        need(visuals, listOf("np.title", "np.artist", "np.album") + next + extra + listOfNotNull("np.elapsed".takeIf { seek }, "np.remaining".takeIf { seek }, "np.art".takeIf { art }, "np.heart.ring".takeIf { heart }, "np.mark".takeIf { mark }))
        val onArt = when (ui.shape) {
            Shape.P, Shape.SN -> HEART_ON_ART
            Shape.SW, Shape.L -> HEART_ON_ART + MARK_ON_ART
        }
        found(visuals, frame, onArt)
        timesApart()
    }

    private fun timesApart() {
        val e = ink("np.elapsed") ?: return
        val r = ink("np.remaining") ?: return
        if (e.right > r.left) findings += "${canvas.name} np.elapsed touches np.remaining: ${e.right} > ${r.left}"
    }

    private fun each(check: () -> Unit) {
        for (c in CANVASES) {
            canvas = c
            settle(1000)
            check()
        }
        assertEquals(findings.take(30).joinToString("\n"), 0, findings.size)
    }

    private fun times(c: SimControl, s: State = c.device.playback.state) {
        c.device = c.device.withState(s.copy(durationMs = 233_000, positionMs = 5_000, playing = false), SystemClock.elapsedRealtime())
    }

    @Test
    fun nowPlayingHasNoCrossingBoxes() {
        val c = SimControl("ap80")
        lyricsOn()(c)
        times(c)
        start(c, listOf(Page.Home, Page.NowPlaying))
        each { nowPlaying(Kind.LOCAL) }
    }

    @Test
    fun theTimesFitTheArtAtTheStepsOnEveryWidth() {
        val c = SimControl("ap80")
        lyricsOn()(c)
        times(c)
        start(c, listOf(Page.Home, Page.NowPlaying))
        for (w in MIN_W..MAX_W) {
            for (h in listOf(NP_THREE_NEXT_H, NP_WIDE_ROW_H)) {
                canvas = Canvas("w$w-h$h", w / PX, ceil(h * w / 320f) / PX)
                settle(1000)
                val art = box("np.art")
                val r = box("np.remaining")
                need(listOf("np.elapsed" to ink("np.elapsed"), "np.remaining" to r, "np.art" to art), listOf("np.elapsed", "np.remaining", "np.art"))
                if (art != null && r != null && abs(r.right - art.right) > 1.5f) findings += "${canvas.name} remaining at ${r.right}, art right ${art.right}"
                timesApart()
            }
        }
        assertEquals(findings.take(30).joinToString("\n"), 0, findings.size)
    }

    @Test
    fun aLongTrackPutsTheRemainingTimeAtTheEdgeOnNarrowArtForTheWholeTrack() {
        val c = SimControl("ap80")
        lyricsOn()(c)
        times(c)
        start(c, listOf(Page.Home, Page.NowPlaying))
        val canvases = listOf(
            Canvas("sn960x986", 960f / PX, 986f / PX), Canvas("sn1600x1643", 1600f / PX, 1643f / PX), CANVASES.first(),
            Canvas("p960x1440", 960f / PX, 1440f / PX), Canvas("l1503x1000", 1503f / PX, 1000f / PX),
        )
        for (s in canvases) {
            canvas = s
            for ((duration, positions) in LONG_TRACKS) {
                val places = positions.map { at ->
                    c.device = c.device.withState(c.device.playback.state.copy(durationMs = duration, positionMs = at, playing = false), SystemClock.elapsedRealtime())
                    settle(1000)
                    val frame = box("np")!!
                    val ui = Ui.of(frame.width, frame.height)
                    val r = box("np.remaining")!!
                    val right = if (ui.shape == Shape.SN) frame.left + ui.px(ui.w - Tok.edge) else box("np.art")!!.right
                    if (abs(r.right - right) > 1.5f) findings += "${s.name} $duration at $at: remaining at ${r.right}, want $right"
                    timesApart()
                    nowPlaying(Kind.LOCAL)
                    r.right
                }
                if (places.any { abs(it - places.first()) > 1.5f }) findings += "${s.name} $duration moves: $places"
            }
        }
        assertEquals(findings.take(30).joinToString("\n"), 0, findings.size)
    }

    @Test
    fun theReceiverPageHasNoCrossingBoxes() {
        val c = SimControl("v1", minor = 15)
        val s = c.device.playback.state
        times(
            c,
            s.copy(
                source = Enums.Source.phone, output = Enums.Output.phoneReceiver, track = 0, title = "Idol", artist = "YOASOBI", artists = listOf("YOASOBI"),
                album = "THE BOOK 3", lyrics = null, lyricsPlain = null, lyricsCrc = null, favorite = null, receiver = true, phoneName = "Pixel", phoneTrackNo = 3,
                phoneTracks = 12,
            ),
        )
        start(c, listOf(Page.Home, Page.NowPlaying))
        each { nowPlaying(Kind.PHONE, listOf("np.from", "np.count")) }
    }

    @Test
    fun aServiceRowHasNoCrossingBoxes() {
        val c = SimControl("v1")
        start(c, listOf(Page.Home, Page.NowPlaying))
        c.push(
            Msg.STATE,
            State(
                source = Enums.Source.local, output = Enums.Output.wired, track = null, title = "Song", artist = "Band", artists = listOf("Band"), album = "Album",
                durationMs = 233_000, positionMs = 5_000, playing = false, slot = 0, slots = 2, lyrics = false, lyricsPlain = false,
                service = "tidal", serviceKey = 167L,
            ).encode(),
        )
        settle(1000)
        each { nowPlaying(Kind.SERVICE) }
    }

    @Test
    fun theLyricsBandClearsTheRow() {
        val c = SimControl("ap80")
        c.mode = true
        lyricsOn()(c)
        times(c)
        val track = c.device.playback.state.track!!
        val lines = (0 until 12).map { LyrLine(it * 4_000L, "line $it") }
        start(c, listOf(Page.Home, Page.NowPlaying)) { LyricsFetch.Show.Lines(track, LyricsFetch.Copy(1, Enums.LyricsKind.synced, lines)) }
        settle(2000)
        each {
            val frame = box("np") ?: return@each run { findings += "${canvas.name} no np" }
            val band = box("np.lyrics") ?: return@each run { findings += "${canvas.name} no lyrics" }
            val boxes = listOf("np.lyrics" to band) + (ROW + "np.seek").map { it to box(it) }
            need(boxes, ROW)
            found(boxes, frame)
        }
    }

    @Test
    fun homeHasNoCrossingBoxes() {
        val c = SimControl("ap80")
        start(c, listOf(Page.Home))
        each {
            val frame = box("home") ?: return@each run { findings += "${canvas.name} no home" }
            val items = listOf("now playing", "music", "connection", "playlists", "online", "settings").map { "home.$it" to box("home.$it") } + ("home.ring" to box("home.ring"))
            need(items, items.map { it.first })
            found(items, frame, RING_IN_MUSIC, bottomEdge = false)
        }
    }

    @Test
    fun artistDetailRingsClearTheNameAndTheLabel() {
        val c = SimControl("ap80")
        start(c, listOf(Page.Home, Page.Music, Page.Artist("Utada Hikaru")))
        each {
            val frame = rootFrame()
            val ui = Ui.of(frame.width, frame.height)
            val taps = listOf("artist.play", "artist.shuffle", "row.0").map { it to box(it) }
            val visuals = listOf("artist.name" to ink("artist.name"), "in collection" to text("in collection"), ring("artist.play", Tok.detailRing, ui), ring("artist.shuffle", Tok.detailRing, ui))
            need(taps + visuals, (taps + visuals).map { it.first })
            found(taps, frame)
            found(visuals, frame)
        }
    }

    @Test
    fun albumDetailArtLinesAndRowsDoNotCross() {
        val c = SimControl("ap80")
        start(c, listOf(Page.Home, Page.Music, Page.Album(ALBUM, "First Love", "Utada Hikaru")))
        each {
            val frame = box("album") ?: return@each run { findings += "${canvas.name} no album" }
            val boxes = listOf("album.art" to box("album.art"), "album.title" to ink("album.title"), "row.0" to box("row.0"), "artist line" to text("Utada Hikaru"))
            need(boxes, boxes.map { it.first })
            found(boxes, frame)
        }
    }

    private fun rootFrame(): Rect {
        val s = canvas
        val d = rule.density.density
        return Rect(0f, 0f, s.w * d, s.h * d)
    }

    private companion object {
        val NONE = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp)
        val ROW = listOf("np.shuffle", "np.prev", "np.toggle", "np.nextTrack", "np.repeat")
        val HEART_ON_ART = setOf(setOf("np.heart.ring", "np.art"))
        val MARK_ON_ART = setOf(setOf("np.mark", "np.art"))
        const val MIN_W = 640
        val LONG_TRACKS = listOf(3_600_000L to listOf(5_000L), 720_000L to listOf(5_000L, 300_000L, 660_000L))
        const val MAX_W = 1920
        val RING_IN_MUSIC = setOf(setOf("home.ring", "home.music"))
        const val PX = 2.625f
        val ALBUM: String = java.lang.Long.toUnsignedString(net.seilent.funecomp.sim.Library.synthetic().tracks.first().id)
        val COLUMNS = listOf(960f to 1440f, 960f to 1395f, 960f to 1347f, 960f to 1200f, 1600f to 1823f, 1600f to 1822f, 1600f to 1643f, 1600f to 1642f, 960f to 960f)
        val BOUNDARIES = listOf(
            960f to 1440f, 960f to 1439f, 960f to 1094f, 960f to 1093f, 960f to 986f, 960f to 985f, 1000f to 1001f, 1001f to 1000f,
            1061f to 960f, 1063f to 960f, 1497f to 1000f, 1503f to 1000f,
        )
        val CANVASES = SIZES.filter { it.name == "cover474x419" || it.name == "sn411x438" || it.name == "wm361x343" }.map { Canvas(it.name, it.w, it.h, it.bars) } +
            BOUNDARIES.map { (w, h) -> Canvas("${w.toInt()}x${h.toInt()}", w / PX, h / PX) } +
            COLUMNS.map { (w, h) -> Canvas("sn${w.toInt()}x${h.toInt()}", w / PX, h / PX) }
    }
}
