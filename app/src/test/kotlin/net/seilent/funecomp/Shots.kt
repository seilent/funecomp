package net.seilent.funecomp

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import net.seilent.funecomp.artist.Accounts
import net.seilent.funecomp.artist.AppPair
import net.seilent.funecomp.artist.ArtBlocks
import net.seilent.funecomp.artist.ArtCache
import net.seilent.funecomp.artist.ArtPicks
import net.seilent.funecomp.artist.Credit
import net.seilent.funecomp.artist.LyricSources
import net.seilent.funecomp.artist.Offer
import net.seilent.funecomp.artist.Sources
import net.seilent.funecomp.artist.Tile
import net.seilent.funecomp.proto.LyrLine
import net.seilent.funecomp.proto.Profile
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.state.AutoEq
import net.seilent.funecomp.state.AutoEqResult
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.state.LyricsFetch
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.BackdropArt
import net.seilent.funecomp.ui.Battery
import net.seilent.funecomp.ui.Bonded
import net.seilent.funecomp.ui.BrowseTile
import net.seilent.funecomp.ui.FirmwareView
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.LyricSlide
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.PickFor
import net.seilent.funecomp.ui.Preview
import net.seilent.funecomp.ui.Previewed
import net.seilent.funecomp.ui.ScrobbleView
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.TextEntry
import net.seilent.funecomp.ui.browseCols
import net.seilent.funecomp.ui.ONLINE_LYRICS
import net.seilent.funecomp.ui.ONLINE_SCROBBLING
import net.seilent.funecomp.ui.flatBand
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong

class Size(val name: String, val w: Int, val h: Int, val bars: WindowInsets = if (w > h) FLIP_LAND else FLIP_PORT)

private val FLIP_PORT = WindowInsets(0.dp, (109 / 2.625f).dp, 0.dp, (39 / 2.625f).dp)
private val FLIP_LAND = WindowInsets((109 / 2.625f).dp, (109 / 2.625f).dp, 0.dp, (39 / 2.625f).dp)
private val FLIP_COVER = WindowInsets(0.dp, (79 / 2.625f).dp, 0.dp, (220 / 2.625f).dp)

class Shot(
    val name: String,
    val board: String = "ap80",
    val pages: List<Page>,
    val link: LinkView = LinkView(Phase.LIVE, "AP80", everLive = true),
    val backdrop: Boolean = false,
    val after: Long = 1000,
    val credits: List<ArtCache.Entry> = emptyList(),
    val scrobble: ScrobbleView = ScrobbleView(),
    val firmware: FirmwareView = FirmwareView(),
    val art: Boolean = false,
    val artEntry: ArtCache.Entry? = null,
    val wrong: Boolean = false,
    val blocked: List<ArtBlocks.Record> = emptyList(),
    val sources: Sources = Sources(),
    val minor: Int = Proto.minor,
    val faults: List<String> = emptyList(),
    val cardEq: List<String> = emptyList(),
    val ime: Float = 0f,
    val lyrics: ((DeviceState) -> LyricsFetch.Show)? = null,
    val held: Boolean = false,
    val picks: List<ArtPicks.Pick> = emptyList(),
    val cols: ((Float) -> Int)? = null,
    val gated: Boolean = false,
    val labels: Boolean = true,
    val white: Boolean = false,
    val prep: (SimControl) -> Unit = {},
    val act: Shots.(Shell) -> Unit = {},
)

val SIZES = listOf(
    Size("360x640", 360, 640),
    Size("411x960", 411, 960),
    Size("cover361x399", 361, 399, FLIP_COVER),
    Size("600x960", 600, 960),
    Size("960x600", 960, 600),
    Size("flip960x411", 960, 411),
    Size("1280x800", 1280, 800),
)

private val HOME = listOf(Page.Home)
private const val CLOCK = "2026-10-21 12:00"
private val NP = HOME + Page.NowPlaying
private val ALBUM_KEY = java.lang.Long.toUnsignedString(net.seilent.funecomp.sim.Library.synthetic().tracks.first().id)

private fun noBond(c: SimControl) {
    c.bonds = listOf(Bonded("WH-1000XM4", "00:00:00:00:00:02"))
}

private fun playing(c: SimControl) {
    c.transport(Enums.TransportCmd.next)
    c.transport(Enums.TransportCmd.shuffle, on = true)
    c.transport(Enums.TransportCmd.repeat, repeat = Enums.Repeat.all)
    c.transport(Enums.TransportCmd.seek, seekMs = 61_000)
}

private fun sleep(minutes: Long, agoMs: Long): (SimControl) -> Unit = { c ->
    c.draft(Enums.DraftKind.sleep, minutes = minutes)
    val p = c.device.playback
    c.device = c.device.copy(playback = p.copy(receivedAt = SystemClock.elapsedRealtime() - agoMs))
}

private fun power(s: Shots) {
    for (p in listOf("library", "display", "power")) {
        s.rule.onNodeWithTag("pivot.$p").performClick()
        s.settle(1000)
    }
}

private fun favorite(c: SimControl) {
    playing(c)
    c.action(Enums.ActionCmd.favorite, c.device.playback.state.track)
}

private fun held(c: SimControl) {
    playing(c)
    val p = c.device.playback
    c.device = c.device.copy(playback = p.copy(state = p.state.copy(title = "Reckoner", artist = HELD.name, artists = listOf(HELD.name), mbids = emptyList(), album = "In Rainbows")))
}

private fun tsuki(vararg artists: String): (SimControl) -> Unit = { c ->
    playing(c)
    val p = c.device.playback
    c.device = c.device.copy(playback = p.copy(state = p.state.copy(title = "君の知らない物語", artist = artists.joinToString(", "), artists = artists.toList(), mbids = emptyList(), album = "歌ってみた")))
}

private fun tileImage(i: Int): ImageBitmap {
    val img = java.awt.image.BufferedImage(160, 160, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.paint = java.awt.GradientPaint(0f, 0f, java.awt.Color.getHSBColor(i * 0.11f, 0.5f, 0.8f), 160f, 160f, java.awt.Color.getHSBColor(i * 0.11f + 0.4f, 0.6f, 0.3f))
    g.fillRect(0, 0, 160, 160)
    g.color = java.awt.Color(255, 255, 255, 80)
    g.fillOval(40, 30, 80, 80)
    g.dispose()
    val out = java.io.ByteArrayOutputStream()
    javax.imageio.ImageIO.write(img, "jpg", out)
    return BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size()).asImageBitmap()
}

private val TILE_CAPTIONS = listOf(Sources.DISCOGS to "dnb, Jump up") +
    listOf("TSUKI(Billlie)", "TSUKI", "Suki Waterhouse", "Tsuki Nakamura", "Tsukiko Amano", "Billlie", "Tsukisheis", "Tsukimi Runa(CV: Sae Hiratsuka)", "Tsukino Amasato", "La Tsuki").map { Sources.DEEZER to it } +
    listOf("tuki.(17)", "tsuki ASMR", "月-Tsuki-", "TsukiUraya", "TSUKI TV", "月 / tsuki", "つき⭐︎", "Tsuki", "Tsuki", "tuki. - トピック").map { Sources.YOUTUBE to it }

private fun browsed(n: Int, end: Boolean?): (SimControl) -> Unit = { c ->
    c.tiles = TILE_CAPTIONS.take(n).mapIndexed { i, (src, cap) -> BrowseTile(Tile(src, cap, ByteArray(0), "https://example.org/$i.jpg", "$i", null), tileImage(i)) }
    c.browsed = end
}

private val PICK_OFFER = Offer(ArtPicks.IMAGE, Sources.YOUTUBE, "https://yt3.googleusercontent.com/0cNMCoNqAuBZzzxAwcjYYKMx_5KCtO0X1UYzzuVwaCP_Mf-EwDeYRZRebd-dvR3eJ_19szDpIQ=s900-c-k-c0x00ffffff-no-rj", "https://yt3.googleusercontent.com/0cNMCoNqAuBZzzxAwcjYYKMx_5KCtO0X1UYzzuVwaCP_Mf-EwDeYRZRebd-dvR3eJ_19szDpIQ", null, null, "月-Tsuki-", null)

private val PICKS = listOf(
    ArtPicks.Pick("name:tsuki", null, "TSUKI", ArtPicks.IMAGE, Sources.YOUTUBE, PICK_OFFER.url, PICK_OFFER.id, "s", null, null, 3),
    ArtPicks.Pick("name:むと", null, "むと", ArtPicks.IMAGE, Sources.LINK, "https://example.org/muto.jpg", "https://example.org/muto.jpg", "s", null, null, 2, 5),
    ArtPicks.Pick("name:空星きらめ", null, "空星きらめ/Sorahoshi Kirame【にじさんじ】", ArtPicks.NONE, Sources.YOUTUBE, "https://yt3.ggpht.com/k=s900", "https://yt3.ggpht.com/k", "s", null, null, 1, null, ArtPicks.IMAGE),
)

private val PICK_ENTRY = ArtCache.Entry("pick:name:tsuki", "TSUKI", null, Sources.YOUTUBE, null, PICK_OFFER.url, 0, PICK_OFFER.id, "s")

private fun photo(w: Int, h: Int, hue: Float): ByteArray {
    val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.paint = java.awt.GradientPaint(0f, 0f, java.awt.Color.getHSBColor(hue, 0.35f, 0.85f), w.toFloat(), h.toFloat(), java.awt.Color.getHSBColor(hue + 0.3f, 0.6f, 0.25f))
    g.fillRect(0, 0, w, h)
    val r = minOf(w, h) / 7
    g.color = java.awt.Color(235, 220, 205)
    g.fillOval(w / 2 - r, h / 3 - r, 2 * r, 2 * r)
    g.color = java.awt.Color(40, 30, 35)
    g.fillRoundRect(w / 2 - 2 * r, h / 3 + r + r / 4, 4 * r, 3 * r, r, r)
    g.color = java.awt.Color(250, 200, 40)
    g.fillRect(0, 0, w, maxOf(4, h / 60))
    g.color = java.awt.Color(40, 200, 250)
    g.fillRect(0, h - maxOf(4, h / 60), w, maxOf(4, h / 60))
    g.dispose()
    val out = java.io.ByteArrayOutputStream()
    javax.imageio.ImageIO.write(img, "jpg", out)
    return out.toByteArray()
}

private fun input(name: String, or: () -> ByteArray): ByteArray = System.getProperty("shots.input")?.let { File(it, name) }?.takeIf { it.isFile }?.readBytes() ?: or()

private fun shown(offer: Offer, src: ByteArray) = Preview(offer, Remote.preview(src, 240, 320) { b, _ -> net.seilent.funecomp.art.BitmapCodec().faced(b, 2048) { null } })

private fun ytOffer(title: String, url: String, label: String) = Offer(ArtPicks.IMAGE, Sources.YOUTUBE, url, url.substringBefore('='), null, null, title, null, label)

private fun one(src: () -> ByteArray, offer: Offer = PICK_OFFER): (SimControl) -> Unit = { c ->
    tsuki("TSUKI")(c)
    c.previewed = Previewed.Ok(listOf(shown(offer, src())))
}

private val ARU_TWO: (SimControl) -> Unit = { c ->
    tsuki("存流")(c)
    c.previewed = Previewed.Ok(
        listOf(
            shown(ytOffer("存流", "https://yt3.googleusercontent.com/aru-music=s1600-rj", "youtube music"), input("ytmusic.jpg") { photo(1600, 900, 0.08f) }),
            shown(ytOffer("存流 -ᴀʀᴜ-", "https://yt3.googleusercontent.com/aru-www=s900-c-k-c0x00ffffff-no-rj", "youtube"), input("youtube.jpg") { photo(900, 900, 0.58f) }),
        ),
    )
}

private const val LONG_ARTIST = "空星きらめ/Sorahoshi Kirame【にじさんじ】"

private val WIDE: (Float) -> Int = { w -> ((w - 2 * Tok.hubEdge + Tok.gutter) / (Tok.tileS + Tok.gutter)).toInt() }

private fun Shots.holdBackdrop() {
    rule.onNodeWithTag("np.backdrop").performTouchInput { longClick(Offset(width * 0.85f, height * 0.2f)) }
}

private fun Shots.pasteLink(shell: Shell, url: String) {
    holdBackdrop()
    settle(600)
    shell.sheet.accept(0)
    settle(600)
    if (rule.onAllNodesWithTag("entry").fetchSemanticsNodes().isNotEmpty()) rule.onNodeWithTag("entry").performTextInput(url)
}

private const val TSUKI_MB = "91352441-9f79-48c2-81ad-ccdd1e46915a"

private fun Shots.sheetItem(shell: Shell, i: Int) {
    holdBackdrop()
    settle(600)
    shell.sheet.accept(i)
}

private fun Shots.pasted(shell: Shell) {
    pasteLink(shell, "https://music.youtube.com/@tsukiniterasarete")
    rule.onNodeWithTag("entry").performImeAction()
}

private fun longTitle(c: SimControl) {
    val p = c.device.playback
    c.device = c.device.copy(playback = p.copy(state = p.state.copy(title = "Svefn-g-englar (Live at the Hammersmith Apollo, London, 2001 remaster)")))
}

private val SUNG = listOf(
    "Light on the water", "Every road leads back home", "I kept the porch light on for you", "Through the rain and the long night", "",
    "We were young and the city was loud", "Hold on, hold on", "Morning comes slow over the river and the bridge we crossed", "Say my name like you used to",
    "Every road leads back home", "Light on the water", "And I will wait", "Till the last train leaves", "Light on the water",
)

private val SUNG_JA = listOf(
    "夜明け前の街を歩いて", "君の声がまだ聞こえる", "遠く遠く光る川の向こうへ", "", "忘れないで", "朝が来るまでここにいるよ",
    "雨上がりの空に虹がかかる日を待っている", "ありがとう、またね", "夜明け前の街を歩いて", "君の声がまだ聞こえる", "さよならは言わないで", "光の中へ",
)

private fun stamped(texts: List<String>, from: Long = 30_000) = texts.mapIndexed { i, t -> LyrLine(from + 4_000L * i, t) }

internal fun lyricsOn(plain: Boolean = false): (SimControl) -> Unit = { c ->
    playing(c)
    val s = c.device.playback.state
    c.device = c.device.withState(s.copy(lyrics = !plain, lyricsPlain = plain, lyricsCrc = 1), SystemClock.elapsedRealtime())
}

private fun lyricsLast(c: SimControl) {
    lyricsOn()(c)
    val p = c.device.playback
    c.device = c.device.copy(playback = p.copy(state = p.state.copy(slot = (p.state.slots ?: 1) - 1)))
}

private fun lyricsAt(ms: Long): (SimControl) -> Unit = { c ->
    lyricsOn()(c)
    val p = c.device.playback
    c.device = c.device.copy(playback = p.copy(state = p.state.copy(positionMs = ms)))
}

private fun lyrFlags(lyrics: Boolean?, plain: Boolean?, source: Int? = null): (SimControl) -> Unit = { c ->
    val s = c.device.playback.state
    c.device = c.device.withState(s.copy(lyrics = lyrics, lyricsPlain = plain, lyricsCrc = 1, source = source ?: s.source), SystemClock.elapsedRealtime())
}

private fun lyrLongNext(c: SimControl) {
    lyricsOn()(c)
    val slot = c.device.playback.state.slot ?: return
    c.queuePage(slot + 1)
    val q = c.device.queue
    val next = q.items[slot + 1] ?: return
    c.device = c.device.copy(queue = q.copy(items = q.items + (slot + 1 to next.copy(title = "Everything In Its Right Place (Live in Paris, 2001, from the Amnesiac tour bonus disc remaster)"))))
}

private fun lyrLongTitle(c: SimControl) {
    lyricsOn()(c)
    longTitle(c)
}

private fun lyrHeart(c: SimControl) {
    favorite(c)
    lyrFlags(true, false)(c)
}

private fun shown(lines: List<LyrLine>, kind: Int = Enums.LyricsKind.synced): (DeviceState) -> LyricsFetch.Show = { d ->
    LyricsFetch.Show.Lines(d.playback.state.track!!, LyricsFetch.Copy(1, kind, lines))
}

private val PLAIN_TEXT = (SUNG.take(4) + "" + "[Chorus]" + SUNG.drop(5).take(4) + "" + SUNG + "" + SUNG).map { LyrLine(null, it) }

private val LONG_LINE = SUNG.take(7) + listOf("la ".repeat(1366).take(Proto.lyrLineMax)) + SUNG.drop(8)

private val LONG_CURRENT = SUNG.take(7) + listOf("Morning comes slow over the river and the bridge we crossed when the city was loud and we were young and every road led back home to you") + SUNG.drop(8)

private class Strip(val name: String, val lines: List<LyrLine>, val from: Long, val to: Long)

private fun lyr(vararg v: Pair<Long, String>) = v.map { LyrLine(it.first, it.second) }

private const val FOUR = "We kept on running through the rain until the city lights went out"

private const val FIVE = "$FOUR and the morning came slow over the quiet field where nothing was left"

private fun short(gap: Long) = lyr(
    0L to "Light on the water", 2000L to "Every road leads back home", 4000L to "Lights out", 6000L to "Say my name again",
    6000L + gap to "Under a paper moon", 7000L + gap to "Across the quiet field", 8000L + gap to "Nothing left to lose",
)

private val RAINY = lyr(
    25080L to "雨音が窓をたたいてるなぁ", 27660L to "夜の街角で待っちゃうなぁ", 29290L to "空色に溶けちゃってるみたいな", 31860L to "きょうはまだ駅前にいるのかな",
    36070L to "夕焼けが赤すぎて眩しいわ", 40540L to "延々と見たエミュレーション", 44090L to "カレーパン買いにいかないかね",
)

private val STRIPS = listOf(1000L, 350L, 200L, 120L, 80L).map { Strip("short-$it", short(it), 4000, 6000) } + listOf(
    Strip("long4-1000", lyr(0L to "Light on the water", 2000L to "Every road leads back home", 4000L to "Under a paper moon", 6000L to FOUR, 9000L to "Lights out", 10000L to "Across the quiet field"), 4000, 6000),
    Strip("fromlong-1000", lyr(0L to "Light on the water", 2000L to "Every road leads back home", 4000L to FOUR, 6000L to "Under a paper moon", 9000L to "Lights out", 10000L to "Across the quiet field"), 4000, 6000),
    Strip("first-1000", lyr(6000L to "Light on the water", 8000L to "Every road leads back home", 10000L to "Hold on until the morning", 12000L to "Under a paper moon"), 5000, 6000),
    Strip("cjk-1000", lyr(0L to "夜明けの空", 2000L to "君の声が聞こえる", 4000L to "紙の月の下で", 6000L to "遠くの街の灯りが消えるまで、僕らは雨の中を走り続けた", 9000L to "もう一度名前を呼んで", 12000L to "静かな野原"), 4000, 6000),
    Strip("empty-1000", lyr(0L to "Light on the water", 2000L to "Every road leads back home", 4000L to "Under a paper moon", 6000L to "", 8000L to "Say my name again", 10000L to "Across the quiet field"), 4000, 6000),
    Strip("prevhide-1000", lyr(0L to "Light on the water", 2000L to "Every road leads back home", 4000L to "Under a paper moon", 6000L to FIVE, 9000L to "Lights out", 10000L to "Across the quiet field"), 4000, 6000),
    Strip("last-1000", lyr(0L to "Light on the water", 2000L to "Every road leads back home", 4000L to "Under a paper moon", 6000L to "Nothing left to lose"), 4000, 6000),
    Strip("cue-1000", lyr(0L to "Every road leads back home", 500L to "Hold on until the morning", 1000L to "Under a paper moon", 1500L to "We were young and the night was ours"), 100, 500),
    Strip("updown-a", RAINY, 27660, 29290),
    Strip("updown-b", RAINY, 29290, 31860),
    Strip("updown-c", lyr(6000L to "Light on the water", 8000L to "Every road leads back home tonight", 10000L to "Hold on until the morning", 12000L to "Under a paper moon", 14000L to "Across the quiet field", 16000L to "Nothing left to lose"), 6000, 8000),
    Strip("snap-seek2", short(1000), 4000, 7000),
    Strip("snap-back", short(1000), 6000, 4000),
    Strip("snap-late300", short(1000), 4000, 6300),
)

private val POOL = mapOf(
    'a' to listOf("Light on the water", "Lights out", "Say my name again", "Nothing left to lose", "Under the last light", "Hold on", "Paper moon", "Quiet field", "Run with me", "One more mile"),
    'b' to listOf("Every road leads back home", "Under a paper moon", "Across the quiet field", "Hold on until the morning"),
    'c' to listOf("We were young and the night was ours", "We kept on running through the rain"),
    'd' to listOf("Light on the water, we were young and the night was ours", "Under a paper moon, across the quiet field"),
    'e' to listOf(FOUR, "Every road leads back home, hold on until the morning comes"),
)

private fun pooled(name: String, code: String, from: Int): Strip {
    val used = HashMap<Char, Int>()
    var t = if (from < 0) 6000L else 0L
    val lines = code.mapIndexed { k, ch ->
        val pool = POOL.getValue(ch)
        val n = used.getOrDefault(ch, 0)
        used[ch] = n + 1
        LyrLine(t, pool[n % pool.size]).also { t += if (k > from) 1000L else 2000L }
    }
    val stamps = lines.map { it.ms ?: 0 }
    return Strip(name, lines, if (from < 0) stamps[0] - 1000 else stamps[from], stamps[from + 1])
}

private val HOLDS = STRIPS.filter { it.name.endsWith("-1000") && it.name != "cue-1000" }.map { Strip(it.name.removeSuffix("-1000"), it.lines, it.from, it.to) } + listOf(
    pooled("oneto1", "aaaaaaaaa", 2),
    pooled("oneto2", "aaabaaaaa", 2),
    pooled("line01", "abaaaaaa", 0),
    pooled("twoto1", "aabaaaaaa", 2),
    pooled("twoto2", "aabbaaaaa", 2),
    pooled("first2", "baaaaaaa", -1),
    pooled("partfit", "aabcaaebeaac", 2),
)

private fun Shots.lyricsMode() {
    rule.onNodeWithTag("np.art").performClick()
}

private fun emptyQueue(c: SimControl) {
    val p = c.device.playback
    c.device = c.device.copy(playback = p.copy(state = p.state.copy(track = null, title = null, artist = null, slots = 0)))
}

private fun preset(name: String, output: Int = Enums.EqOutput.wired): (SimControl) -> Unit = { c ->
    c.eqPreset(Enums.EqPresetOp.save, output, null, name, AutoEq.profile((AutoEq.parse(SimControl.HD650, name) as AutoEqResult.Ok).eq), false) { _, _ -> }
}

private const val LONG_NAME = "Sennheiser HD 650 (2020, worn earpads, oratory1990 target)"

private const val NAME_64 = "Beyerdynamic DT 1990 PRO (B pads, analytical, 2024 measure)abcde"

private fun Shots.import() {
    rule.onNodeWithTag("eq.import").performClick()
    settle(600)
    rule.onNodeWithTag("sheet.item.0").performClick()
    settle(600)
}

private fun Shots.apply() {
    import()
    rule.onNodeWithTag("sheet.item.0").performClick()
    settle(600)
}

private fun custom(c: SimControl) {
    c.eqSet(Enums.EqOutput.wired, ints = Profile(-64, (0 until 10).map { i ->
        val b = flatBand(i)
        if (i == 0) b.copy(type = Enums.BandType.lowShelf, gain10 = 55, q100 = 70) else b
    }))
}

private val CREDITS = listOf(
    ArtCache.Entry("0de4d19f-05c8-4562-a3c0-7abdc144f1d5", "Barack Obama", null, "commons", Credit("Official White House Photo by Pete Souza", "Public domain", "https://commons.wikimedia.org/"), "", 0),
    ArtCache.Entry("a74b1b7f-71a5-4011-9441-d0b5e4122711", "Radiohead", null, "commons", Credit("Raph_PH", "CC BY 4.0", "https://commons.wikimedia.org/"), "", 0),
    ArtCache.Entry("b539e453-c4fe-47e3-8a07-8517eac74429", "Utada Hikaru", null, "commons", Credit("Goldnrush Podcast", "CC BY 4.0", "https://commons.wikimedia.org/"), "", 0),
)

private val HELD = ArtCache.Entry("a74b1b7f-71a5-4011-9441-d0b5e4122711", "Radiohead", "a74b1b7f-71a5-4011-9441-d0b5e4122711", "commons", Credit("Raph_PH", "CC BY 4.0", "https://commons.wikimedia.org/wiki/File:RadioheadO2211125_composite.jpg", "https://creativecommons.org/licenses/by/4.0"), "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a1/RadioheadO2211125_composite.jpg/500px-RadioheadO2211125_composite.jpg", 0, "File:RadioheadO2211125 composite.jpg", "s", "Radiohead")

private val BLOCKED: List<ArtBlocks.Record> = (0 until 12).map { i ->
    if (i % 4 == 3) ArtBlocks.Reject("m$i", "m$i", "Muto $i", "むと and a very long artist name number $i", 100L - i)
    else ArtBlocks.Block("k$i", null, listOf("deezer", "commons", "theaudiodb")[i % 3], "id$i", "s$i", listOf("むと", "Radiohead", "空星きらめ/Sorahoshi Kirame【にじさんじ】")[i % 3] + " $i", 100L - i)
}

private val ORDERED = Sources.NONE.copy(theAudioDb = true, fanartTv = true, deezer = true, order = Sources.order("deezer,commons,theaudiodb"))

private val SCROBBLE = ScrobbleView(Accounts(true, "", "", "seilent", "session", true, "token", "seilent"), queued = 3, app = ZEROS)
private val SCROBBLE_OUT = ScrobbleView(Accounts(true, listenBrainz = true), app = ZEROS)

private val FW = HOME + Page.Settings + Page.Firmware

private val ONLINE = HOME + Page.Online()
private val GATED = LinkView(Phase.CONNECTING, "AP80")
private const val LONG_VERSION = "0.4.0-dev-118-g3c887a9-uirework-dirty+20261021"

private fun Shots.tapRow(tag: String) {
    scrollTo(tag)
    rule.onNodeWithTag(tag).performClick()
    settle(600)
}

private fun Shots.about() {
    for (p in listOf("library", "display", "power", "system")) {
        rule.onNodeWithTag("pivot.$p").performClick()
        settle(1000)
    }
    tapRow("set.about")
}

private fun state(edit: (net.seilent.funecomp.proto.State) -> net.seilent.funecomp.proto.State): (SimControl) -> Unit = { c ->
    val p = c.device.playback
    c.device = c.device.copy(playback = p.copy(state = edit(p.state)))
}

private val FOUND = FirmwareView(tag = "v0.4.0", size = 28_043_264, newer = true)

private val EQ = HOME + Page.Settings + Page.Eq(Enums.EqOutput.wired)

private val AEQ = EQ + Page.AutoEq(Enums.EqOutput.wired)

private const val AEQ_IME = 0.4f

private val CLAMPED = SimControl.HD650.replace("Preamp: -6.1 dB", "Preamp: 1.5 dB").replace("Gain 6.4 dB", "Gain 14.0 dB").replace("Fc 5332 Hz Gain -1.1 dB Q 5.75", "Fc 22000 Hz Gain -30.0 dB Q 12.00")

val SHOTS = listOf(
    Shot("home", pages = HOME, prep = ::playing),
    Shot("home-long", pages = HOME, prep = ::longTitle),
    Shot("home-empty", pages = HOME, prep = ::emptyQueue),
    Shot("home-down", pages = HOME, prep = ::playing, link = LinkView(Phase.DOWN, "AP80", everLive = true)),
    Shot("home-end", pages = HOME, prep = ::playing) {
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("home.column").performTouchInput { swipeUp() }
        rule.mainClock.autoAdvance = false
    },
    Shot("home-ring-down", pages = HOME, prep = ::playing, held = true) { rule.onNodeWithTag("home.ring").performTouchInput { down(center) } },
    Shot("nowplaying", pages = NP, prep = ::playing),
    Shot("nowplaying-v1", board = "v1", pages = NP, prep = ::playing),
    Shot("nowplaying-last", pages = NP, prep = { it.queueOp(Enums.QueueCmd.playAt, slot = it.device.playback.state.slots!! - 1) }),
    Shot("nowplaying-art", pages = NP, prep = ::playing, art = true),
    Shot("nowplaying-art-v1", board = "v1", pages = NP, prep = ::playing, art = true),
    Shot("nowplaying-backdrop", pages = NP, prep = ::playing, backdrop = true),
    Shot("nowplaying-overlay", pages = NP, prep = ::playing, after = 450) { it.overlay.show() },
    Shot("nowplaying-linkdown", pages = NP, prep = ::playing, link = LinkView(Phase.DOWN, "AP80", everLive = true)),
    Shot("nowplaying-playing", pages = NP, prep = { c -> playing(c); c.transport(Enums.TransportCmd.toggle) }),
    Shot("nowplaying-long", pages = NP, prep = { c -> playing(c); longTitle(c) }),
    Shot("nowplaying-idle", pages = NP, prep = ::playing, after = (Tok.motion_idle + Tok.motion_idleFade).toLong() + 100),
    Shot("heart-unlit", pages = NP, prep = ::playing),
    Shot("heart-lit", pages = NP, prep = ::favorite),
    Shot("heart-unknown", pages = NP, prep = ::playing, faults = listOf("favs-busy=1")),
    Shot("heart-none", pages = NP, prep = ::emptyQueue),
    Shot("heart-12", pages = NP, prep = ::playing, minor = 2),
    Shot("heart-added", pages = NP, prep = ::playing, after = 600) { rule.onNodeWithTag("np.heart").performClick() },
    Shot("heart-removed", pages = NP, prep = ::favorite, after = 600) { rule.onNodeWithTag("np.heart").performClick() },
    Shot("heart-busy", pages = NP, prep = ::playing, faults = listOf("favs-busy=1"), after = 600) { rule.onNodeWithTag("np.heart").performClick() },
    Shot("heart-unreadable", pages = NP, prep = ::playing, faults = listOf("favs-busy=1:favorites not readable"), after = 600) { rule.onNodeWithTag("np.heart").performClick() },
    Shot("nav-quickback", pages = HOME) {
        rule.onNodeWithTag("home.music").performClick()
        settle(100)
        it.nav.pop()
    },
    Shot("nowplaying-empty", pages = NP, link = LinkView(Phase.CONNECTING, "AP80"), prep = { it.device = DeviceState() }),
    Shot("queue", pages = NP + Page.Queue, prep = ::playing),
    Shot("queue-sheet", pages = NP + Page.Queue, prep = ::playing) { rule.onNodeWithTag("queue.2").performTouchInput { longClick() } },
    Shot("music-artists", pages = HOME + Page.Music),
    Shot("music-albums", pages = HOME + Page.Music) { rule.onNodeWithTag("pivot.albums").performClick() },
    Shot("music-songs", pages = HOME + Page.Music, prep = ::playing) { rule.onNodeWithTag("pivot.songs").performClick() },
    Shot("music-folders", pages = HOME + Page.Music) {
        for (p in listOf("albums", "songs", "playlists", "folders")) {
            rule.onNodeWithTag("pivot.$p").performClick()
            settle(1000)
        }
        rule.onNodeWithTag("row.0").performClick()
    },
    Shot("music-songs-play", pages = HOME + Page.Music, prep = { it.queueOp(Enums.QueueCmd.playAt, slot = 13) }) {
        rule.onNodeWithTag("pivot.songs").performClick()
        settle(1000)
        scrollTo("row.13")
    },
    Shot("music-folders-play", pages = HOME + Page.Music, prep = ::playing) {
        for (p in listOf("albums", "songs", "playlists", "folders")) {
            rule.onNodeWithTag("pivot.$p").performClick()
            settle(1000)
        }
        rule.onNodeWithTag("row.2").performClick()
        settle(1000)
        rule.onNodeWithTag("row.1").performClick()
    },
    Shot("playlists", pages = HOME + Page.Playlists, prep = ::playing),
    Shot("artist", pages = HOME + Page.Music + Page.Artist("Utada Hikaru")),
    Shot("album-noartist", pages = HOME + Page.Music + Page.Album(ALBUM_KEY, "First Love", "")),
    Shot("album", pages = HOME + Page.Music + Page.Album(ALBUM_KEY, "First Love", "Utada Hikaru"), prep = ::playing),
    Shot("album-art", pages = HOME + Page.Music + Page.Album(ALBUM_KEY, "First Love", "Utada Hikaru"), prep = ::playing, art = true),
    Shot("album-play-focused", pages = HOME + Page.Music + Page.Album(ALBUM_KEY, "First Love", "Utada Hikaru")) {
        rule.onNodeWithTag("row.2").performClick()
        settle(1000)
        it.nav.pop()
    },
    Shot("settings-output", pages = HOME + Page.Settings),
    Shot("settings-output-v1", board = "v1", pages = HOME + Page.Settings),
    Shot("settings-crossfeed", pages = HOME + Page.Settings) { scrollTo("set.crossfeed") },
    Shot("settings-edit", pages = HOME + Page.Settings) {
        rule.onNodeWithTag("set.balance").performClick()
        settle(300)
        rule.onNodeWithTag("set.balance").performTouchInput { click(centerRight) }
    },
    Shot("settings-pick-volumestep", pages = HOME + Page.Settings) { rule.onNodeWithTag("set.volumeStep").performClick() },
    Shot("settings-pick-maxvolume", pages = HOME + Page.Settings) { rule.onNodeWithTag("set.maxVolume").performClick() },
    Shot("settings-pick-poweron", pages = HOME + Page.Settings, prep = { it.set("powerOnVolume", "70") }) { rule.onNodeWithTag("set.powerOnVolume").performClick() },
    Shot("settings-pick-crossfeed", pages = HOME + Page.Settings) {
        scrollTo("set.crossfeed")
        rule.onNodeWithTag("set.crossfeed").performClick()
    },
    Shot("settings-pick-filter-v1", board = "v1", pages = HOME + Page.Settings, prep = { it.set("digitalFilter", "3") }) {
        scrollTo("set.digitalFilter")
        rule.onNodeWithTag("set.digitalFilter").performClick()
    },
    Shot("settings-pick-usbmode", pages = HOME + Page.Settings) {
        for (p in listOf("library", "display", "power", "system")) {
            rule.onNodeWithTag("pivot.$p").performClick()
            settle(1000)
        }
        scrollTo("set.usbMode")
        rule.onNodeWithTag("set.usbMode").performClick()
    },
    Shot("settings-toggle", pages = HOME + Page.Settings) {
        for (p in listOf("library", "display")) {
            rule.onNodeWithTag("pivot.$p").performClick()
            settle(1000)
        }
        scrollTo("set.quickplay")
        rule.onNodeWithTag("set.quickplay").performClick()
    },
    Shot("settings-display", pages = HOME + Page.Settings) {
        for (p in listOf("library", "display")) {
            rule.onNodeWithTag("pivot.$p").performClick()
            settle(1000)
        }
    },
    Shot("settings-display-v1", board = "v1", pages = HOME + Page.Settings) {
        for (p in listOf("library", "display")) {
            rule.onNodeWithTag("pivot.$p").performClick()
            settle(1000)
        }
    },
    Shot("settings-artistphoto-v1", board = "v1", pages = HOME + Page.Settings) {
        for (p in listOf("library", "display")) {
            rule.onNodeWithTag("pivot.$p").performClick()
            settle(1000)
        }
        scrollTo("set.artistPhoto")
    },
    Shot("settings-power", pages = HOME + Page.Settings) {
        for (p in listOf("library", "display", "power")) {
            rule.onNodeWithTag("pivot.$p").performClick()
            settle(1000)
        }
    },
    Shot("settings-sleep", pages = HOME + Page.Settings, prep = sleep(45, 61_000)) { power(this) },
    Shot("settings-sleep-v1", board = "v1", pages = HOME + Page.Settings, prep = sleep(45, 61_000)) { power(this) },
    Shot("settings-pick-sleep", pages = HOME + Page.Settings, prep = sleep(45, 61_000)) {
        power(this)
        rule.onNodeWithTag("set.sleep").performClick()
    },
    Shot("settings-pick-sleep-v1", board = "v1", pages = HOME + Page.Settings, prep = sleep(45, 61_000)) {
        power(this)
        rule.onNodeWithTag("set.sleep").performClick()
    },
    Shot("settings-sleep-entry", pages = HOME + Page.Settings, prep = sleep(45, 61_000), ime = AEQ_IME) {
        power(this)
        rule.onNodeWithTag("set.sleep").performClick()
        settle(600)
        it.sheet.accept(it.sheet.items.lastIndex)
    },
    Shot("settings-sleep-refused", pages = HOME + Page.Settings, prep = sleep(45, 61_000), ime = AEQ_IME) {
        power(this)
        rule.onNodeWithTag("set.sleep").performClick()
        settle(600)
        it.sheet.accept(it.sheet.items.lastIndex)
        settle(600)
        rule.onNodeWithTag("entry").performTextInput("1000")
        rule.onNodeWithTag("entry").performImeAction()
    },
    Shot("settings-sleep-wide", pages = HOME + Page.Settings, prep = sleep(120, 0)) { power(this) },
    Shot("settings-sleep-wide-v1", board = "v1", pages = HOME + Page.Settings, prep = sleep(120, 0)) { power(this) },
    Shot("settings-system", pages = HOME + Page.Settings) {
        for (p in listOf("library", "display", "power", "system")) {
            rule.onNodeWithTag("pivot.$p").performClick()
            settle(1000)
        }
    },
    Shot("settings-system-v1", board = "v1", pages = HOME + Page.Settings) {
        for (p in listOf("library", "display", "power", "system")) {
            rule.onNodeWithTag("pivot.$p").performClick()
            settle(1000)
        }
    },
    Shot("eq", pages = HOME + Page.Settings + Page.Eq(Enums.EqOutput.wired)),
    Shot("eq-custom", pages = HOME + Page.Settings + Page.Eq(Enums.EqOutput.wired), prep = ::custom) { rule.onNodeWithTag("eq.band.0").performClick() },
    Shot("eq-entry", pages = HOME + Page.Settings + Page.Eq(Enums.EqOutput.wired), prep = ::custom) {
        rule.onNodeWithTag("eq.band.0").performClick()
        settle(600)
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("rows").performScrollToNode(hasTestTag("eq.band.0.gain"))
        rule.mainClock.autoAdvance = false
        settle(100)
        rule.onNodeWithTag("eq.band.0.gain").performClick()
    },
    Shot("eq-profile", pages = HOME + Page.Settings + Page.Eq(Enums.EqOutput.wired)) { rule.onNodeWithTag("eq.profile").performClick() },
    Shot("autoeq-source", pages = EQ) { rule.onNodeWithTag("eq.import").performClick() },
    Shot("autoeq-list", pages = AEQ),
    Shot("autoeq-dividers", pages = AEQ) {
        jump(25)
        scrollTo("aeq.div.z")
    },
    Shot("autoeq-jump", pages = AEQ) {
        jump(19)
        rule.onNodeWithTag("aeq.div.s").performClick()
    },
    Shot("autoeq-hd650", pages = AEQ) { type("hd 650") },
    Shot("autoeq-oratory1990", pages = AEQ) { type("oratory1990") },
    Shot("autoeq-rode", pages = AEQ) { type("rode") },
    Shot("autoeq-none", pages = AEQ) { type("zzqx") },
    Shot("autoeq-loading", pages = AEQ, prep = { it.holdList = true }),
    Shot("autoeq-failed", pages = AEQ, prep = { it.autoEq = null }),
    Shot("autoeq-long", pages = AEQ) { type("tungsten double") },
    Shot("autoeq-ime", pages = AEQ, ime = AEQ_IME) {
        jump(26)
        scrollTo("aeq.row.${SimControl.FULL.rows.last().entry.name}")
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("rows").performTouchInput { swipeUp() }
        rule.mainClock.autoAdvance = false
    },
    Shot("autoeq-sheetfield", pages = AEQ) {
        type("hd 650")
        it.sheet.openEntry("search") { TextEntry("hd 650") {} }
    },
    Shot("autoeq-confirm-long", pages = AEQ) {
        type("hd 650")
        rule.onNodeWithTag("aeq.row.Sennheiser HD 650 (2020, worn earpads)").performClick()
    },
    Shot("autoeq-confirm", pages = EQ) {
        rule.onNodeWithTag("eq.import").performClick()
        settle(600)
        rule.onNodeWithTag("sheet.item.0").performClick()
    },
    Shot("autoeq-clamped", pages = EQ, prep = { it.eqText = CLAMPED }) {
        rule.onNodeWithTag("eq.import").performClick()
        settle(600)
        rule.onNodeWithTag("sheet.item.0").performClick()
    },
    Shot("autoeq-refused", pages = EQ, prep = { it.eqText = (1..11).joinToString("\n") { i -> "Filter $i: ON PK Fc ${i * 100} Hz Gain 1 dB Q 1" } }) {
        rule.onNodeWithTag("eq.import").performClick()
        settle(600)
        rule.onNodeWithTag("sheet.item.0").performClick()
    },
    Shot("eqp-custom", pages = EQ, prep = ::custom),
    Shot("eqp-custom-bt", pages = HOME + Page.Settings + Page.Eq(Enums.EqOutput.bt), prep = { c -> c.eqSet(Enums.EqOutput.bt, key = "custom") }),
    Shot("eqp-preset", pages = EQ, prep = preset("Sennheiser HD 650")),
    Shot("eqp-preset-bt", pages = HOME + Page.Settings + Page.Eq(Enums.EqOutput.bt), prep = preset("Sennheiser HD 650", Enums.EqOutput.bt)),
    Shot("eqp-preset-long", pages = EQ, prep = preset(LONG_NAME)),
    Shot("eqp-preset-64", pages = EQ, prep = preset(NAME_64)),
    Shot("eqp-delete", pages = EQ, prep = preset("Sennheiser HD 650")) { rule.onNodeWithTag("eq.delete").performClick() },
    Shot("eqp-rename", pages = EQ, prep = preset("Sennheiser HD 650")) { rule.onNodeWithTag("eq.rename").performClick() },
    Shot("eqp-apply", pages = EQ) { import() },
    Shot("eqp-apply-long", pages = EQ, prep = { it.eqFileName = "$LONG_NAME ParametricEQ.txt" }) { import() },
    Shot("eqp-replace", pages = EQ, prep = preset("Sennheiser HD 650")) { apply() },
    Shot("eqp-replace-64", pages = EQ, prep = { c -> c.eqFileName = "$NAME_64.txt"; preset(NAME_64)(c) }) { apply() },
    Shot("eqp-clash", pages = EQ, cardEq = listOf("Sennheiser HD 650")) { apply() },
    Shot("eqp-saveas", pages = EQ, cardEq = listOf("Sennheiser HD 650")) {
        apply()
        rule.onNodeWithTag("sheet.item.1").performClick()
    },
    Shot("eqp-old", pages = EQ, minor = 4) { import() },
    Shot("eqp-toast-taken", pages = EQ, faults = listOf("eq-hidden=Sennheiser HD 650"), after = 300) { apply() },
    Shot("eqp-toast-full", pages = EQ, prep = { c -> (1..200).forEach { preset("P$it")(c) } }, after = 300) { apply() },
    Shot("firmware", board = "v1", pages = FW),
    Shot("firmware-private", board = "v1", pages = FW, firmware = FirmwareView(info = "no public releases")),
    Shot("firmware-found", board = "v1", pages = FW, firmware = FOUND),
    Shot("firmware-dev", board = "v1", pages = FW, firmware = FOUND.copy(newer = null, info = "device runs a dev build")),
    Shot("firmware-download", board = "v1", pages = FW, firmware = FOUND) { rule.onNodeWithTag("fw.release").performClick() },
    Shot("firmware-downloading", board = "v1", pages = FW, firmware = FOUND.copy(downloading = true, download = "42%", checkDaily = true)),
    Shot("firmware-paused", board = "v1", pages = FW, firmware = FOUND.copy(download = "paused 42%")),
    Shot("firmware-mismatch", board = "v1", pages = FW, firmware = FOUND.copy(info = "checksum mismatch")),
    Shot("firmware-ready", board = "v1", pages = FW, firmware = FOUND.copy(ready = true)) { rule.onNodeWithTag("fw.release").performClick() },
    Shot("firmware-picked", board = "v1", pages = FW) { rule.onNodeWithTag("fw.pick").performClick() },
    Shot("firmware-sending", board = "v1", pages = FW, firmware = FOUND.copy(ready = true, send = "firmware 42%")),
    Shot("firmware-sent", board = "v1", pages = FW, firmware = FOUND.copy(ready = true, info = "ready on the dap")),
    Shot("firmware-autocheck-sheet", board = "v1", pages = FW) { tapRow("fw.daily") },
    Shot("firmware-prereleases-sheet", board = "v1", pages = FW) { tapRow("fw.pre") },
    Shot("firmware-send-cancel", board = "v1", pages = FW, firmware = FOUND.copy(ready = true, send = "firmware 42%")) { rule.onNodeWithTag("fw.send").performClick() },
    Shot("connection", pages = HOME + Page.Connection),
    Shot("connection-refused", pages = HOME + Page.Connection, link = LinkView(Phase.STOPPED, "AP80", bye = Enums.ByeReason.refused)),
    Shot("connection-permission", pages = HOME + Page.Connection, link = LinkView(Phase.NONE, "AP80", permission = false)),
    Shot("connection-device", pages = HOME + Page.Connection) { rule.onNodeWithTag("conn.device").performClick() },
    Shot("connection-refused-nobond", pages = HOME + Page.Connection, link = LinkView(Phase.STOPPED, "AP80", bye = Enums.ByeReason.refused), prep = ::noBond),
    Shot("connection-nobond", pages = HOME + Page.Connection, link = LinkView(Phase.NONE), prep = ::noBond),
    Shot("connection-nobond-device", pages = HOME + Page.Connection, link = LinkView(Phase.NONE), prep = ::noBond) { rule.onNodeWithTag("conn.device").performClick() },
    Shot("connection-background", board = "v1", pages = HOME + Page.Connection, link = LinkView(Phase.LIVE, "V1", everLive = true, battery = Battery.OPTIMIZED)),
    Shot("connection-restricted", board = "v1", pages = HOME + Page.Connection, link = LinkView(Phase.DOWN, "V1", everLive = true, battery = Battery.RESTRICTED)),
    Shot("connection-stopped", board = "v1", pages = HOME + Page.Connection, link = LinkView(Phase.NONE, "V1", battery = Battery.UNRESTRICTED), prep = { it.device = DeviceState() }),
    Shot("background", board = "v1", pages = NP, prep = ::playing, link = LinkView(Phase.LIVE, "V1", everLive = true, battery = Battery.OPTIMIZED, askBattery = true)),
    Shot("reconnect", board = "v1", pages = NP, prep = ::playing, link = LinkView(Phase.LIVE, "V1", everLive = true, battery = Battery.UNRESTRICTED, askReconnect = true)),
    Shot("connection-reconnect-sheet", board = "v1", pages = HOME + Page.Connection, link = LinkView(Phase.LIVE, "V1", everLive = true, battery = Battery.UNRESTRICTED, reconnect = true)) { tapRow("conn.reconnect") },
    Shot("connection-media-sheet", board = "v1", pages = HOME + Page.Connection, link = LinkView(Phase.LIVE, "V1", everLive = true, battery = Battery.UNRESTRICTED)) { tapRow("conn.media") },
    Shot("np-block", pages = NP, prep = ::held, artEntry = HELD) { holdBackdrop() },
    Shot("np-block-wrong", pages = NP, prep = ::held, artEntry = HELD, wrong = true) { holdBackdrop() },
    Shot("lyr-mark", pages = NP, prep = lyricsOn(), artEntry = HELD, lyrics = shown(stamped(SUNG))),
    Shot("lyr-mark-nonext", pages = NP, prep = ::lyricsLast, artEntry = HELD, lyrics = shown(stamped(SUNG))),
    Shot("lyrmark-short", pages = NP, prep = lyricsOn(), art = true, artEntry = HELD, lyrics = shown(stamped(SUNG))),
    Shot("lyrmark-longtitle", pages = NP, prep = ::lyrLongTitle, art = true, artEntry = HELD, lyrics = shown(stamped(SUNG))),
    Shot("lyrmark-longnext", pages = NP, prep = ::lyrLongNext, art = true, artEntry = HELD, lyrics = shown(stamped(SUNG))),
    Shot("lyrmark-nonext", pages = NP, prep = ::lyricsLast, art = true, artEntry = HELD, lyrics = shown(stamped(SUNG))),
    Shot("lyrmark-noart", pages = NP, prep = lyricsOn(), artEntry = HELD, lyrics = shown(stamped(SUNG))),
    Shot("lyrmark-plain", pages = NP, prep = lyricsOn(plain = true), art = true, artEntry = HELD, lyrics = shown(PLAIN_TEXT, Enums.LyricsKind.plain)),
    Shot("lyrmark-pending", pages = NP, prep = { c -> playing(c); lyrFlags(null, null)(c) }, art = true, artEntry = HELD),
    Shot("lyrmark-none", pages = NP, prep = { c -> playing(c); lyrFlags(false, false)(c) }, art = true, artEntry = HELD, lyrics = { LyricsFetch.Show.None }),
    Shot("lyrmark-mode", pages = NP, prep = lyricsOn(), art = true, artEntry = HELD, lyrics = shown(stamped(SUNG))) { lyricsMode() },
    Shot("lyrmark-phone", pages = NP, prep = { c -> playing(c); lyrFlags(true, false, Enums.Source.phone)(c) }, art = true, artEntry = HELD, lyrics = shown(stamped(SUNG))),
    Shot("lyrmark-white", pages = NP, prep = lyricsOn(), art = true, artEntry = HELD, white = true, lyrics = shown(stamped(SUNG))),
    Shot("lyrmark-idle", pages = NP, prep = lyricsOn(), art = true, artEntry = HELD, lyrics = shown(stamped(SUNG)), after = (Tok.motion_idle + Tok.motion_idleFade).toLong() + 100),
    Shot("lyrmark-heart", pages = NP, prep = ::lyrHeart, art = true, artEntry = HELD, lyrics = shown(stamped(SUNG))),
    Shot("lyr-synced", pages = NP, prep = lyricsOn(), artEntry = HELD, lyrics = shown(stamped(SUNG))) { lyricsMode() },
    Shot("lyr-before", pages = NP, prep = lyricsOn(), artEntry = HELD, lyrics = shown(stamped(SUNG, 70_000))) { lyricsMode() },
    Shot("lyr-plain", pages = NP, prep = lyricsOn(plain = true), artEntry = HELD, lyrics = shown(PLAIN_TEXT, Enums.LyricsKind.plain)) { lyricsMode() },
    Shot("lyr-plain-scrolled", pages = NP, prep = lyricsOn(plain = true), artEntry = HELD, lyrics = shown(PLAIN_TEXT, Enums.LyricsKind.plain), after = 3000) {
        lyricsMode()
        settle(1000)
        rule.onNodeWithTag("np.lyrics").performTouchInput { swipeUp() }
    },
    Shot("lyr-none", pages = NP, prep = lyricsOn(), artEntry = HELD, lyrics = { LyricsFetch.Show.None }) { lyricsMode() },
    Shot("lyr-long", pages = NP, prep = lyricsOn(), artEntry = HELD, lyrics = shown(stamped(LONG_CURRENT))) { lyricsMode() },
    Shot("lyr-long4096", pages = NP, prep = lyricsOn(), artEntry = HELD, lyrics = shown(stamped(LONG_LINE))) { lyricsMode() },
    Shot("lyr-cjk", pages = NP, prep = lyricsOn(), artEntry = HELD, lyrics = shown(stamped(SUNG_JA))) { lyricsMode() },
    Shot("lyr-cue", pages = NP, prep = lyricsAt(2_000), artEntry = HELD, lyrics = shown(listOf(LyrLine(0, "Till the last train leaves")) + stamped(SUNG.drop(1), 6_000))) { lyricsMode() },
    Shot("lyr-nobackdrop", pages = NP, prep = lyricsOn(), lyrics = shown(stamped(SUNG))) { lyricsMode() },
    Shot("artblocked-1", pages = ONLINE + Page.ArtBlocked, blocked = BLOCKED.take(1), credits = CREDITS),
    Shot("artblocked-12", pages = ONLINE + Page.ArtBlocked, blocked = BLOCKED, credits = CREDITS),
    Shot("artblocked-12-end", pages = ONLINE + Page.ArtBlocked, blocked = BLOCKED, credits = CREDITS) { scrollTo("art.blocked.11") },
    Shot("artblocked-empty", pages = ONLINE + Page.ArtBlocked, credits = CREDITS),
    Shot("artpicked", pages = ONLINE + Page.ArtPicked, picks = PICKS, blocked = BLOCKED.take(1)),
    Shot("artpicked-empty", pages = ONLINE + Page.ArtPicked, blocked = BLOCKED.take(1)),
    Shot("artcredits", pages = ONLINE + Page.ArtCredits, credits = CREDITS),
    Shot("artcredits-empty", pages = ONLINE + Page.ArtCredits),
    Shot("pick-np-second", pages = NP, prep = ::held, artEntry = HELD) { shell ->
        holdBackdrop()
        settle(600)
        shell.sheet.accept(1)
    },
    Shot("pick-np-black", pages = NP, prep = tsuki("TSUKI")) { holdBackdrop() },
    Shot("pick-np-chooser", pages = NP, prep = tsuki("TSUKI", "Kagura Nana")) { holdBackdrop() },
    Shot("pick-np-picked", pages = NP, prep = tsuki("TSUKI"), artEntry = PICK_ENTRY, picks = PICKS) { holdBackdrop() },
    Shot("pick-entry", pages = NP, prep = tsuki("TSUKI"), ime = AEQ_IME) { shell -> pasteLink(shell, "https://www.youtube.com/@tsukiniterasarete") },
    Shot("pick-confirm", pages = NP, prep = one({ photo(1200, 1200, 0.58f) })) { shell -> pasted(shell) },
    Shot("pick-confirm-wide", pages = NP, prep = one({ photo(1600, 900, 0.08f) })) { shell -> pasted(shell) },
    Shot("pick-confirm-tall", pages = NP, prep = one({ photo(900, 1200, 0.33f) })) { shell -> pasted(shell) },
    Shot("pick-confirm-long", pages = NP, prep = { c -> one({ photo(1200, 1200, 0.58f) }, ytOffer("$LONG_ARTIST Official Channel", "https://yt3.googleusercontent.com/long=s1600-rj", "youtube"))(c); tsuki(LONG_ARTIST)(c) }) { shell -> pasted(shell) },
    Shot("pick-confirm-two", pages = NP, prep = ARU_TWO) { shell -> pasted(shell) },
    Shot("pick-confirm-two-next", pages = NP, prep = ARU_TWO) { shell ->
        pasted(shell)
        settle(1000)
        rule.onNodeWithTag("pick.next").performClick()
    },
    Shot("pick-confirm-two-back", pages = NP, prep = ARU_TWO) { shell ->
        pasted(shell)
        settle(1000)
        rule.onNodeWithTag("pick.next").performClick()
        settle(300)
        rule.onNodeWithTag("pick.next").performClick()
    },
    Shot("pick-confirm-mb", pages = NP, prep = { c ->
        tsuki("TSUKI")(c)
        c.previewed = Previewed.Ok(listOf(Preview(Offer(ArtPicks.ARTIST, Sources.MUSICBRAINZ, "https://musicbrainz.org/artist/$TSUKI_MB", TSUKI_MB, null, TSUKI_MB, "Tsuki", null), null)))
    }) { shell -> pasted(shell) },
    Shot("pick-confirm-gallery", pages = NP, prep = { c ->
        tsuki("TSUKI")(c)
        c.gallery = Previewed.Ok(listOf(shown(Offer(ArtPicks.IMAGE, Sources.GALLERY, "", "gallery:0", null, null, "IMG_20261021_183012.jpg", null), photo(900, 1200, 0.33f))))
    }) { shell -> sheetItem(shell, 2) },
    Shot("pick-loading", pages = NP, prep = tsuki("TSUKI")) { shell -> pasted(shell) },
    Shot("pick-refused", pages = NP, prep = { c -> tsuki("TSUKI")(c); c.previewed = Previewed.Refused("image too small") }, after = 400) { shell -> pasted(shell) },
    Shot("pick-artists-sheet", pages = HOME + Page.Music) { rule.onNodeWithTag("row.0").performTouchInput { longClick() } },
    Shot("pick-browse-loading", pages = NP + Page.Browse(PickFor("TSUKI"))),
    Shot("pick-browse-more", pages = NP + Page.Browse(PickFor("TSUKI")), prep = browsed(5, null)),
    Shot("pick-browse", pages = NP + Page.Browse(PickFor("TSUKI")), prep = browsed(TILE_CAPTIONS.size, true)),
    Shot("pick-browse-wide", pages = NP + Page.Browse(PickFor("TSUKI")), prep = browsed(TILE_CAPTIONS.size, true), cols = WIDE),
    Shot("pick-browse-focus", pages = NP + Page.Browse(PickFor("TSUKI")), prep = browsed(TILE_CAPTIONS.size, true)) { rule.onNodeWithTag("pick.tile.4").performClick() },
    Shot("pick-confirm-browse", pages = NP + Page.Browse(PickFor("TSUKI")), prep = { c -> browsed(TILE_CAPTIONS.size, true)(c); c.taken = Previewed.Ok(listOf(shown(PICK_OFFER, photo(1200, 1200, 0.58f)))) }) { rule.onNodeWithTag("pick.tile.4").performClick() },
    Shot("pick-browse-empty", pages = NP + Page.Browse(PickFor("TSUKI")), prep = browsed(0, true)),
    Shot("pick-browse-unavailable", pages = NP + Page.Browse(PickFor("TSUKI")), prep = browsed(0, false)),
    Shot("pick-browse-unavailable-tiles", pages = NP + Page.Browse(PickFor("TSUKI")), prep = browsed(11, false)),
    Shot("connection-gated", pages = listOf(Page.Connection), link = GATED, gated = true),
    Shot("online-art", pages = ONLINE),
    Shot("online-art-ordered", pages = ONLINE, sources = ORDERED),
    Shot("online-art-sheet-commons", pages = ONLINE) { tapRow("art.src.commons") },
    Shot("online-art-sheet-middle", pages = ONLINE, sources = ORDERED) { tapRow("art.src.theaudiodb") },
    Shot("online-art-sheet-last", pages = ONLINE, sources = ORDERED) { tapRow("art.src.youtube") },
    Shot("online-art-sheet-fanart-nokey", pages = ONLINE, sources = ORDERED) { tapRow("art.src.fanarttv") },
    Shot("online-art-sheet-fanart-key", pages = ONLINE, sources = ORDERED.copy(fanartTvKey = "0123456789abcdef")) { tapRow("art.src.fanarttv") },
    Shot("online-art-moved", pages = ONLINE, sources = ORDERED) {
        tapRow("art.src.theaudiodb")
        rule.onNodeWithTag("sheet.item.2").performClick()
    },
    Shot("online-art-key", pages = ONLINE, sources = ORDERED, ime = AEQ_IME) {
        tapRow("art.src.fanarttv")
        rule.onNodeWithTag("sheet.item.4").performClick()
    },
    Shot("online-art-gated", pages = listOf(Page.Connection), link = GATED, gated = true) { rule.onNodeWithTag("conn.online").performClick() },
    Shot("online-art-lists", pages = ONLINE, picks = PICKS, blocked = BLOCKED, credits = CREDITS) { scrollTo("art.credits") },
    Shot("online-art-lists-blocked-1", pages = ONLINE, blocked = BLOCKED.take(1), credits = CREDITS) { scrollTo("art.credits") },
    Shot("online-art-lists-creditsonly", pages = ONLINE, credits = CREDITS) { scrollTo("art.credits") },
    Shot("online-lyrics", pages = HOME + Page.Online(ONLINE_LYRICS)),
    Shot("online-lyrics-sheet", pages = HOME + Page.Online(ONLINE_LYRICS)) { tapRow("lyr.src.lrclib") },
    Shot("online-lyrics-sheet-netease", pages = HOME + Page.Online(ONLINE_LYRICS)) { tapRow("lyr.src.netease") },
    Shot("online-lyrics-off", pages = HOME + Page.Online(ONLINE_LYRICS), link = LinkView(Phase.LIVE, "AP80", everLive = true, lyrics = LyricSources(lrclib = false, order = listOf(LyricSources.NETEASE, LyricSources.LRCLIB)))),
    Shot("online-scrobbling", pages = HOME + Page.Online(ONLINE_SCROBBLING)),
    Shot("online-scrobbling-set", pages = HOME + Page.Online(ONLINE_SCROBBLING), scrobble = SCROBBLE),
    Shot("online-scrobbling-failed", pages = HOME + Page.Online(ONLINE_SCROBBLING), scrobble = SCROBBLE_OUT.copy(lastFm = "sign in failed", listenBrainz = "invalid")),
    Shot("online-scrobbling-out", pages = HOME + Page.Online(ONLINE_SCROBBLING), scrobble = SCROBBLE_OUT),
    Shot("online-scrobbling-waiting", pages = HOME + Page.Online(ONLINE_SCROBBLING), scrobble = SCROBBLE_OUT.copy(pending = true)),
    Shot("online-scrobbling-notapproved", pages = HOME + Page.Online(ONLINE_SCROBBLING), scrobble = SCROBBLE_OUT.copy(pending = true, lastFm = "not approved")),
    Shot("online-scrobbling-nobrowser", pages = HOME + Page.Online(ONLINE_SCROBBLING), scrobble = SCROBBLE_OUT.copy(lastFm = "no browser")),
    Shot("online-scrobbling-tokennobrowser", pages = HOME + Page.Online(ONLINE_SCROBBLING), scrobble = SCROBBLE_OUT.copy(tokenPage = "no browser")),
    Shot("online-scrobbling-keyrefused", pages = HOME + Page.Online(ONLINE_SCROBBLING), scrobble = SCROBBLE.copy(keyRefused = setOf(Accounts.LAST_FM))),
    Shot("online-scrobbling-debug", pages = HOME + Page.Online(ONLINE_SCROBBLING), scrobble = SCROBBLE_OUT.copy(app = AppPair.EMPTY)),
    Shot("online-scrobbling-account", pages = HOME + Page.Online(ONLINE_SCROBBLING), scrobble = SCROBBLE) { tapRow("scrobble.account") },
    Shot("online-scrobbling-signout", pages = HOME + Page.Online(ONLINE_SCROBBLING), scrobble = SCROBBLE) { shell ->
        tapRow("scrobble.account")
        settle(600)
        shell.sheet.accept(0)
    },
    Shot("online-scrobbling-lbsheet", pages = HOME + Page.Online(ONLINE_SCROBBLING), scrobble = SCROBBLE) { tapRow("scrobble.token") },
    Shot("online-scrobbling-entry", pages = HOME + Page.Online(ONLINE_SCROBBLING), scrobble = SCROBBLE_OUT) { tapRow("scrobble.token") },
    Shot("online-scrobbling-plain", pages = HOME + Page.Online(ONLINE_SCROBBLING), scrobble = SCROBBLE, labels = false),
    Shot("settings-about", pages = HOME + Page.Settings) { about() },
    Shot("settings-about-v1", board = "v1", pages = HOME + Page.Settings) { about() },
    Shot("settings-about-sending", board = "v1", pages = HOME + Page.Settings, firmware = FOUND.copy(ready = true, send = "firmware 42%")) { about() },
    Shot("settings-about-nohello", pages = HOME + Page.Settings, prep = { it.device = it.device.copy(hello = null) }) { about() },
    Shot("settings-about-fallback", pages = HOME + Page.Settings, prep = { it.device = it.device.copy(schema = it.device.schema.copy(rows = it.device.schema.rows.filter { r -> r.key != "about" })) }) { about() },
    Shot("settings-about-scrolled", pages = HOME + Page.Settings) {
        about()
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("sheet.list").performScrollToIndex(0)
        rule.mainClock.autoAdvance = false
    },
    Shot("settings-about-nostate", pages = HOME + Page.Settings, prep = state { it.copy(battery = null, charging = null, cardFreeMiB = null, cardTotalMiB = null) }) { about() },
    Shot("settings-about-longversion", pages = HOME + Page.Settings, prep = { it.device = it.device.copy(hello = it.device.hello?.copy(version = LONG_VERSION)) }) { about() },
)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class Shots {
    @get:Rule
    val rule = createComposeRule()

    private var frame by mutableStateOf<Pair<Shot, Size>?>(null)
    private var control by mutableStateOf<SimControl?>(null)
    private var shell by mutableStateOf(Shell())
    private var sources by mutableStateOf(Sources())

    fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    fun type(text: String) {
        rule.onNodeWithTag("aeq.field").performClick()
        rule.onNodeWithTag("aeq.field").performTextInput(text)
        settle(300)
    }

    fun jump(letter: Int) {
        rule.onAllNodesWithTag("aeq.div.#")[0].performClick()
        settle(300)
        rule.onNodeWithTag("jump.$letter").performClick()
        settle(300)
    }

    fun scrollTo(tag: String) {
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("rows").performScrollToNode(hasTestTag(tag))
        rule.mainClock.autoAdvance = false
        settle(100)
    }

    private val covers: File by lazy {
        val dir = kotlin.io.path.createTempDirectory("covers").toFile()
        val lib = net.seilent.funecomp.sim.Library.synthetic()
        lib.tracks.mapNotNull { lib.albumKey(it.id) }.distinct().forEachIndexed { i, key ->
            val img = java.awt.image.BufferedImage(600, 600, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.paint = java.awt.GradientPaint(0f, 0f, java.awt.Color.getHSBColor(i * 0.13f, 0.6f, 0.8f), 600f, 600f, java.awt.Color.getHSBColor(i * 0.13f + 0.5f, 0.7f, 0.3f))
            g.fillRect(0, 0, 600, 600)
            g.color = java.awt.Color(255, 255, 255, 90)
            g.fillOval(150, 150, 300, 300)
            g.dispose()
            javax.imageio.ImageIO.write(img, "jpg", File(dir, "$key.jpg"))
        }
        dir
    }

    private fun fill(paint: java.awt.Paint): ImageBitmap {
        val img = java.awt.image.BufferedImage(720, 960, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.paint = paint
        g.fillRect(0, 0, 720, 960)
        g.dispose()
        val out = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(img, "jpg", out)
        return BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size()).asImageBitmap()
    }

    private val plain: ImageBitmap by lazy { fill(java.awt.GradientPaint(0f, 0f, java.awt.Color(90, 70, 60), 720f, 960f, java.awt.Color(20, 30, 50))) }

    private val whiteBg: ImageBitmap by lazy { fill(java.awt.Color(255, 255, 255)) }

    private val backdrop: ImageBitmap? by lazy {
        System.getProperty("shots.backdrop")?.let { File(it) }?.takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
    }

    @Test
    fun shots() {
        assumeTrue(System.getProperty("shots.run") == "true")
        val dir = File(System.getProperty("shots.dir") ?: "build/shots")
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val f = frame ?: return@setContent
            val c = control ?: return@setContent
            key(f) {
                Box(Modifier.requiredSize(f.second.w.dp, f.second.h.dp).testTag("frame")) {
                    val held = f.first.artEntry?.let { BackdropArt(if (f.first.white) whiteBg else backdrop ?: plain, null, it, f.first.wrong) }
                    App(c.device, f.first.link, c, shell, sources, { sources = it }, held ?: if (f.first.backdrop) backdrop?.let { BackdropArt(it) } else null, f.first.credits, scrobble = f.first.scrobble, firmware = f.first.firmware, blocked = f.first.blocked, picks = f.first.picks, scrobbleLabels = f.first.labels, lyrics = f.first.lyrics?.invoke(c.device) ?: LyricsFetch.Show.Off, insets = f.second.bars, ime = WindowInsets(bottom = (f.second.h * f.first.ime).dp))
                }
            }
        }
        val only = System.getProperty("shots.only").orEmpty()
        for (size in SIZES) {
            for (shot in SHOTS) {
                if (only.isNotEmpty() && only.split(',').none { "${shot.name}-${size.name}".contains(it) }) continue
                if (shot.backdrop && backdrop == null) continue
                val c = SimControl(shot.board, artDir = if (shot.art) covers else null, minor = shot.minor, faults = shot.faults, cardEq = shot.cardEq)
                c.device = c.device.copy(schema = c.device.schema.copy(rows = c.device.schema.rows.map { if (it.key == "dateTime") it.copy(words = listOf(CLOCK)) else it }))
                shot.prep(c)
                if (shot.gated) c.device = DeviceState()
                sources = shot.sources
                browseCols = shot.cols ?: { Tok.gridCols.toInt() }
                control = c
                shell = Shell(Nav(shot.pages), gated = shot.gated)
                frame = shot to size
                settle(1000)
                shot.act(this, shell)
                settle(shot.after)
                val file = File(dir, "${shot.name}-${size.name}.png")
                rule.onNodeWithTag("frame").captureRoboImage(file.path)
                if (shot.held) rule.onNodeWithTag("frame").performTouchInput { cancel() }
            }
        }
        if (only.isEmpty()) return
        for (size in SIZES) {
            for (strip in STRIPS) {
                val name = "lyrslide-${strip.name}-${size.name}"
                if (only.split(',').none { name.contains(it) }) continue
                val c = SimControl("ap80")
                lyricsOn()(c)
                pause(c, strip.from)
                control = c
                shell = Shell(Nav(NP))
                frame = Shot(name, pages = NP, artEntry = HELD, lyrics = shown(strip.lines)) to size
                settle(1000)
                lyricsMode()
                settle(1000)
                val stamps = strip.lines.map { it.ms ?: 0 }
                val d = LyricSlide.start(LyricsFetch.index(strip.lines, strip.from), LyricsFetch.index(strip.lines, strip.to), stamps, strip.to, 0)?.d
                val es = ((0L..(d ?: Tok.motion_scroll.toLong()) + 25) step 25).toList() + listOfNotNull(d?.let { (it * HALF_T).roundToLong() })
                val shot = { at: Long -> rule.onNodeWithTag("frame").captureRoboImage(File(dir, "$name/e%03d.png".format(at)).path) }
                val before = column()
                pause(c, strip.to)
                rule.mainClock.advanceTimeByFrame()
                rule.mainClock.advanceTimeByFrame()
                assertEquals(name, d != null, column() == before)
                val start = rule.mainClock.currentTime
                for (at in es.sorted().distinct()) {
                    rule.mainClock.advanceTimeBy(start + at - rule.mainClock.currentTime, ignoreFrameDuration = true)
                    shot(at)
                }
            }
        }
        for (size in SIZES) {
            for (hold in HOLDS) {
                val name = "lyrhold-${hold.name}-${size.name}"
                if (only.split(',').none { name.contains(it) }) continue
                val c = SimControl("ap80")
                lyricsOn()(c)
                pause(c, hold.from)
                control = c
                shell = Shell(Nav(NP))
                frame = Shot(name, pages = NP, artEntry = HELD, lyrics = shown(hold.lines)) to size
                settle(1000)
                lyricsMode()
                settle(1000)
                val shot = { at: Long -> rule.onNodeWithTag("frame").captureRoboImage(File(dir, "$name/e%03d.png".format(at)).path) }
                val hold0 = rule.mainClock.currentTime
                for (at in 0L until 200L step 25) {
                    rule.mainClock.advanceTimeBy(hold0 + at - rule.mainClock.currentTime, ignoreFrameDuration = true)
                    shot(at)
                }
                val before = column()
                pause(c, hold.to)
                rule.mainClock.advanceTimeByFrame()
                rule.mainClock.advanceTimeByFrame()
                assertEquals(name, before, column())
                val start = rule.mainClock.currentTime
                for (at in 0L..550L step 25) {
                    rule.mainClock.advanceTimeBy(start + at - rule.mainClock.currentTime, ignoreFrameDuration = true)
                    shot(200 + at)
                }
            }
        }
    }
}

private fun Shots.column() = rule.onAllNodes(hasAnyAncestor(hasTestTag("np.lyrics")) and hasText("", substring = true)).fetchSemanticsNodes().map { it.config.getOrNull(SemanticsProperties.Text).toString() to it.positionInRoot.y }

private val HALF_T = 1f - ln(1f + (exp(6f) - 1f) / 2f) / 6f

private fun pause(c: SimControl, ms: Long) {
    val p = c.device.playback
    c.device = c.device.copy(playback = p.copy(state = p.state.copy(playing = false, positionMs = ms)))
}
