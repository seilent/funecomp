package net.seilent.funecomp

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import net.seilent.funecomp.artist.ArtCache
import net.seilent.funecomp.artist.Credit
import net.seilent.funecomp.artist.Sources
import net.seilent.funecomp.proto.Profile
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.BackdropArt
import net.seilent.funecomp.ui.Bonded
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
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

class Size(val name: String, val w: Int, val h: Int)

class Shot(
    val name: String,
    val board: String = "ap80",
    val pages: List<Page>,
    val link: LinkView = LinkView(Phase.LIVE, "AP80", everLive = true),
    val backdrop: Boolean = false,
    val after: Long = 1000,
    val credits: List<ArtCache.Entry> = emptyList(),
    val prep: (SimControl) -> Unit = {},
    val act: Shots.(Shell) -> Unit = {},
)

val SIZES = listOf(
    Size("360x640", 360, 640),
    Size("411x960", 411, 960),
    Size("cover361x399", 361, 399),
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

val SHOTS = listOf(
    Shot("home", pages = HOME, prep = ::playing),
    Shot("nowplaying", pages = NP, prep = ::playing),
    Shot("nowplaying-v1", board = "v1", pages = NP, prep = ::playing),
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
    Shot("connection", pages = HOME + Page.Connection),
    Shot("connection-refused", pages = HOME + Page.Connection, link = LinkView(Phase.STOPPED, "AP80", bye = Enums.ByeReason.refused)),
    Shot("connection-permission", pages = HOME + Page.Connection, link = LinkView(Phase.NONE, "AP80", permission = false)),
    Shot("connection-device", pages = HOME + Page.Connection) { rule.onNodeWithTag("conn.device").performClick() },
    Shot("connection-refused-nobond", pages = HOME + Page.Connection, link = LinkView(Phase.STOPPED, "AP80", bye = Enums.ByeReason.refused), prep = ::noBond),
    Shot("connection-nobond", pages = HOME + Page.Connection, link = LinkView(Phase.NONE), prep = ::noBond),
    Shot("connection-nobond-device", pages = HOME + Page.Connection, link = LinkView(Phase.NONE), prep = ::noBond) { rule.onNodeWithTag("conn.device").performClick() },
    Shot("artistart", pages = HOME + Page.ArtistArt),
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
                    App(c.device, f.first.link, c, shell, Sources(), {}, if (f.first.backdrop) backdrop?.let { BackdropArt(it) } else null, f.first.credits)
                }
            }
        }
        val only = System.getProperty("shots.only").orEmpty()
        for (size in SIZES) {
            for (shot in SHOTS) {
                if (only.isNotEmpty() && only.split(',').none { "${shot.name}-${size.name}".contains(it) }) continue
                if (shot.backdrop && backdrop == null) continue
                val c = SimControl(shot.board)
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
