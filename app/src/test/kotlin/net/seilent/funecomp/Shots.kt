package net.seilent.funecomp

import android.graphics.BitmapFactory
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
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
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
import net.seilent.funecomp.proto.Profile
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.state.DeviceState
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

private fun held(c: SimControl) {
    playing(c)
    val p = c.device.playback
    c.device = c.device.copy(playback = p.copy(state = p.state.copy(title = "Reckoner", artist = HELD.name, artists = listOf(HELD.name), mbids = emptyList(), album = "In Rainbows")))
}

private fun longTitle(c: SimControl) {
    val p = c.device.playback
    c.device = c.device.copy(playback = p.copy(state = p.state.copy(title = "Svefn-g-englar (Live at the Hammersmith Apollo, London, 2001 remaster)")))
}

private fun emptyQueue(c: SimControl) {
    val p = c.device.playback
    c.device = c.device.copy(playback = p.copy(state = p.state.copy(track = null, title = null, artist = null, slots = 0)))
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
    Shot("nowplaying", pages = NP, prep = ::playing),
    Shot("nowplaying-v1", board = "v1", pages = NP, prep = ::playing),
    Shot("nowplaying-last", pages = NP, prep = { it.queueOp(Enums.QueueCmd.playAt, slot = it.device.playback.state.slots!! - 1) }),
    Shot("nowplaying-art", pages = NP, prep = ::playing, art = true),
    Shot("nowplaying-art-v1", board = "v1", pages = NP, prep = ::playing, art = true),
    Shot("nowplaying-backdrop", pages = NP, prep = ::playing, backdrop = true),
    Shot("nowplaying-overlay", pages = NP, prep = ::playing, after = 450) { it.overlay.show() },
    Shot("nowplaying-linkdown", pages = NP, prep = ::playing, link = LinkView(Phase.DOWN, "AP80", everLive = true)),
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
    Shot("playlists", pages = HOME + Page.Playlists, prep = ::playing),
    Shot("artist", pages = HOME + Page.Music + Page.Artist("Utada Hikaru")),
    Shot("album-noartist", pages = HOME + Page.Music + Page.Album(ALBUM_KEY, "First Love", "")),
    Shot("album", pages = HOME + Page.Music + Page.Album(ALBUM_KEY, "First Love", "Utada Hikaru"), prep = ::playing),
    Shot("album-art", pages = HOME + Page.Music + Page.Album(ALBUM_KEY, "First Love", "Utada Hikaru"), prep = ::playing, art = true),
    Shot("settings-output", pages = HOME + Page.Settings),
    Shot("settings-output-v1", board = "v1", pages = HOME + Page.Settings),
    Shot("settings-edit", pages = HOME + Page.Settings) { rule.onNodeWithTag("set.volumeStep").performClick() },
    Shot("settings-display", pages = HOME + Page.Settings) {
        for (p in listOf("library", "display")) {
            rule.onNodeWithTag("pivot.$p").performClick()
            settle(1000)
        }
    },
    Shot("settings-power", pages = HOME + Page.Settings) {
        for (p in listOf("library", "display", "power")) {
            rule.onNodeWithTag("pivot.$p").performClick()
            settle(1000)
        }
    },
    Shot("settings-system", pages = HOME + Page.Settings) {
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
    Shot("autoeq-results", pages = EQ) {
        rule.onNodeWithTag("eq.import").performClick()
        settle(600)
        rule.onNodeWithTag("sheet.item.1").performClick()
        settle(600)
        rule.onNodeWithTag("entry").performTextInput("hd 650")
        rule.onNodeWithTag("entry").performImeAction()
    },
    Shot("autoeq-confirm-long", pages = EQ) {
        rule.onNodeWithTag("eq.import").performClick()
        settle(600)
        rule.onNodeWithTag("sheet.item.1").performClick()
        settle(600)
        rule.onNodeWithTag("entry").performTextInput("hd 650")
        rule.onNodeWithTag("entry").performImeAction()
        settle(600)
        rule.onNodeWithTag("sheet.item.2").performClick()
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
                    App(c.device, f.first.link, c, shell, f.first.sources, {}, held ?: if (f.first.backdrop) backdrop?.let { BackdropArt(it) } else null, f.first.credits, scrobble = f.first.scrobble, firmware = f.first.firmware, blocked = f.first.blocked, insets = f.second.bars)
                }
            }
        }
        val only = System.getProperty("shots.only").orEmpty()
        for (size in SIZES) {
            for (shot in SHOTS) {
                if (only.isNotEmpty() && only.split(',').none { "${shot.name}-${size.name}".contains(it) }) continue
                if (shot.backdrop && backdrop == null) continue
                val c = SimControl(shot.board, artDir = if (shot.art) covers else null)
                shot.prep(c)
                control = c
                shell = Shell(Nav(shot.pages))
                frame = shot to size
                settle(1000)
                shot.act(this, shell)
                settle(shot.after)
                val file = File(dir, "${shot.name}-${size.name}.png")
                rule.onNodeWithTag("frame").captureRoboImage(file.path)
            }
        }
    }
}
