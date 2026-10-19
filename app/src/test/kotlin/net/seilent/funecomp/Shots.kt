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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import net.seilent.funecomp.artist.Accounts
import net.seilent.funecomp.artist.ArtBlocks
import net.seilent.funecomp.artist.ArtCache
import net.seilent.funecomp.artist.Credit
import net.seilent.funecomp.artist.Sources
import net.seilent.funecomp.proto.LyrLine
import net.seilent.funecomp.proto.Profile
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.state.AutoEq
import net.seilent.funecomp.state.AutoEqResult
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.state.LyricsFetch
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.BackdropArt
import net.seilent.funecomp.ui.Battery
import net.seilent.funecomp.ui.Bonded
import net.seilent.funecomp.ui.FirmwareView
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.ScrobbleView
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.TextEntry
import net.seilent.funecomp.ui.flatBand
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

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

private fun lyricsOn(plain: Boolean = false): (SimControl) -> Unit = { c ->
    playing(c)
    val p = c.device.playback
    c.device = c.device.copy(playback = p.copy(state = p.state.copy(lyrics = !plain, lyricsPlain = plain, lyricsCrc = 1)))
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

private fun shown(lines: List<LyrLine>, kind: Int = Enums.LyricsKind.synced): (DeviceState) -> LyricsFetch.Show = { d ->
    LyricsFetch.Show.Lines(d.playback.state.track!!, LyricsFetch.Copy(1, kind, lines))
}

private val PLAIN_TEXT = (SUNG.take(4) + "" + "[Chorus]" + SUNG.drop(5).take(4) + "" + SUNG + "" + SUNG).map { LyrLine(null, it) }

private val LONG_LINE = SUNG.take(7) + listOf("la ".repeat(1366).take(Proto.lyrLineMax)) + SUNG.drop(8)

private val LONG_CURRENT = SUNG.take(7) + listOf("Morning comes slow over the river and the bridge we crossed when the city was loud and we were young and every road led back home to you") + SUNG.drop(8)

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

private val ORDERED = Sources(theAudioDb = true, fanartTv = true, deezer = true, order = Sources.order("deezer,commons,theaudiodb"))

private val SCROBBLE = ScrobbleView(Accounts(true, "0123456789abcdef0123456789abcdef", "secret", "seilent", "session", true, "token", "seilent"), queued = 3)

private val FW = HOME + Page.Connection + Page.Firmware

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
    Shot("firmware-downloading", board = "v1", pages = FW, firmware = FOUND.copy(downloading = true, download = "42 %", checkDaily = true)),
    Shot("firmware-paused", board = "v1", pages = FW, firmware = FOUND.copy(download = "paused 42 %")),
    Shot("firmware-mismatch", board = "v1", pages = FW, firmware = FOUND.copy(info = "checksum mismatch")),
    Shot("firmware-ready", board = "v1", pages = FW, firmware = FOUND.copy(ready = true)) { rule.onNodeWithTag("fw.release").performClick() },
    Shot("firmware-picked", board = "v1", pages = FW) { rule.onNodeWithTag("fw.pick").performClick() },
    Shot("firmware-sending", board = "v1", pages = FW, firmware = FOUND.copy(ready = true, send = "firmware 42 %")),
    Shot("firmware-sent", board = "v1", pages = FW, firmware = FOUND.copy(ready = true, info = "ready on the dap")),
    Shot("firmware-send-cancel", board = "v1", pages = FW, firmware = FOUND.copy(ready = true, send = "firmware 42 %")) { rule.onNodeWithTag("fw.send").performClick() },
    Shot("lyrics", pages = HOME + Page.Connection + Page.Lyrics),
    Shot("lyrics-on", pages = HOME + Page.Connection + Page.Lyrics, link = LinkView(Phase.LIVE, "AP80", everLive = true, lyrics = true)),
    Shot("connection", pages = HOME + Page.Connection),
    Shot("connection-refused", pages = HOME + Page.Connection, link = LinkView(Phase.STOPPED, "AP80", bye = Enums.ByeReason.refused)),
    Shot("connection-permission", pages = HOME + Page.Connection, link = LinkView(Phase.NONE, "AP80", permission = false)),
    Shot("connection-device", pages = HOME + Page.Connection) { rule.onNodeWithTag("conn.device").performClick() },
    Shot("connection-refused-nobond", pages = HOME + Page.Connection, link = LinkView(Phase.STOPPED, "AP80", bye = Enums.ByeReason.refused), prep = ::noBond),
    Shot("connection-nobond", pages = HOME + Page.Connection, link = LinkView(Phase.NONE), prep = ::noBond),
    Shot("connection-nobond-device", pages = HOME + Page.Connection, link = LinkView(Phase.NONE), prep = ::noBond) { rule.onNodeWithTag("conn.device").performClick() },
    Shot("connection-battery", board = "v1", pages = HOME + Page.Connection, link = LinkView(Phase.LIVE, "V1", everLive = true, battery = Battery.OPTIMIZED)),
    Shot("connection-restricted", board = "v1", pages = HOME + Page.Connection, link = LinkView(Phase.DOWN, "V1", everLive = true, battery = Battery.RESTRICTED)),
    Shot("connection-stopped", board = "v1", pages = HOME + Page.Connection, link = LinkView(Phase.NONE, "V1", battery = Battery.UNRESTRICTED), prep = { it.device = DeviceState() }),
    Shot("background", board = "v1", pages = NP, prep = ::playing, link = LinkView(Phase.LIVE, "V1", everLive = true, battery = Battery.OPTIMIZED, askBattery = true)),
    Shot("reconnect", board = "v1", pages = NP, prep = ::playing, link = LinkView(Phase.LIVE, "V1", everLive = true, battery = Battery.UNRESTRICTED, askReconnect = true)),
    Shot("connection-reconnect", board = "v1", pages = HOME + Page.Connection, link = LinkView(Phase.LIVE, "V1", everLive = true, battery = Battery.UNRESTRICTED, reconnect = true)) {
        scrollTo("conn.reconnect")
    },
    Shot("artistart", pages = HOME + Page.ArtistArt),
    Shot("scrobbling", pages = HOME + Page.Connection + Page.Scrobbling),
    Shot("scrobbling-set", pages = HOME + Page.Connection + Page.Scrobbling, scrobble = SCROBBLE),
    Shot("scrobbling-failed", pages = HOME + Page.Connection + Page.Scrobbling, scrobble = SCROBBLE.copy(accounts = SCROBBLE.accounts.copy(lastFmSession = ""), lastFm = "sign in failed", listenBrainz = "invalid")),
    Shot("scrobbling-entry", pages = HOME + Page.Connection + Page.Scrobbling, scrobble = SCROBBLE) { rule.onNodeWithTag("scrobble.key").performClick() },
    Shot("np-block", pages = NP, prep = ::held, artEntry = HELD) { rule.onNodeWithTag("np.backdrop").performTouchInput { longClick() } },
    Shot("np-block-wrong", pages = NP, prep = ::held, artEntry = HELD, wrong = true) { rule.onNodeWithTag("np.backdrop").performTouchInput { longClick() } },
    Shot("lyr-mark", pages = NP, prep = lyricsOn(), artEntry = HELD, lyrics = shown(stamped(SUNG))),
    Shot("lyr-mark-nonext", pages = NP, prep = ::lyricsLast, artEntry = HELD, lyrics = shown(stamped(SUNG))),
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
    Shot("artistart-blocked-1", pages = HOME + Page.ArtistArt, blocked = BLOCKED.take(1), credits = CREDITS) { scrollTo("art.blocked.0") },
    Shot("artistart-blocked-12", pages = HOME + Page.ArtistArt, blocked = BLOCKED, credits = CREDITS) { scrollTo("art.blocked.11") },
    Shot("artistart-order", pages = HOME + Page.ArtistArt, sources = ORDERED) { scrollTo("art.order.5") },
    Shot("artistart-order-first", pages = HOME + Page.ArtistArt, sources = ORDERED) {
        scrollTo("art.order.0")
        rule.onNodeWithTag("art.order.0").performClick()
    },
    Shot("artistart-order-middle", pages = HOME + Page.ArtistArt, sources = ORDERED) {
        scrollTo("art.order.2")
        rule.onNodeWithTag("art.order.2").performClick()
    },
    Shot("artistart-order-last", pages = HOME + Page.ArtistArt, sources = ORDERED) {
        scrollTo("art.order.5")
        rule.onNodeWithTag("art.order.5").performClick()
    },
    Shot("artistart-credits", pages = HOME + Page.ArtistArt, credits = CREDITS) {
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("rows").performScrollToNode(hasTestTag("art.credit.2"))
        rule.mainClock.autoAdvance = false
    },
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

    private val plain: ImageBitmap by lazy {
        val img = java.awt.image.BufferedImage(720, 960, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.paint = java.awt.GradientPaint(0f, 0f, java.awt.Color(90, 70, 60), 720f, 960f, java.awt.Color(20, 30, 50))
        g.fillRect(0, 0, 720, 960)
        g.dispose()
        val out = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(img, "jpg", out)
        BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size()).asImageBitmap()
    }

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
                    val held = f.first.artEntry?.let { BackdropArt(backdrop ?: plain, null, it, f.first.wrong) }
                    App(c.device, f.first.link, c, shell, f.first.sources, {}, held ?: if (f.first.backdrop) backdrop?.let { BackdropArt(it) } else null, f.first.credits, scrobble = f.first.scrobble, firmware = f.first.firmware, blocked = f.first.blocked, lyrics = f.first.lyrics?.invoke(c.device) ?: LyricsFetch.Show.Off, insets = f.second.bars, ime = WindowInsets(bottom = (f.second.h * f.first.ime).dp))
                }
            }
        }
        val only = System.getProperty("shots.only").orEmpty()
        for (size in SIZES) {
            for (shot in SHOTS) {
                if (only.isNotEmpty() && only.split(',').none { "${shot.name}-${size.name}".contains(it) }) continue
                if (shot.backdrop && backdrop == null) continue
                val c = SimControl(shot.board, artDir = if (shot.art) covers else null, minor = shot.minor, faults = shot.faults, cardEq = shot.cardEq)
                shot.prep(c)
                control = c
                shell = Shell(Nav(shot.pages))
                frame = shot to size
                settle(1000)
                shot.act(this, shell)
                settle(shot.after)
                val file = File(dir, "${shot.name}-${size.name}.png")
                rule.onNodeWithTag("frame").captureRoboImage(file.path)
                if (shot.held) rule.onNodeWithTag("frame").performTouchInput { cancel() }
            }
        }
    }
}
