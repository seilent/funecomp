package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.sim.Playlist
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class ListPageShots {
    @get:Rule
    val rule = createComposeRule()

    private var size by mutableStateOf<Size?>(null)
    private var gen by mutableIntStateOf(0)
    private var shell by mutableStateOf(Shell())
    private lateinit var c: SimControl

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun load(sz: Size, top: Page, lib: Library = Library(BASE.tracks, playlists = LISTS), minor: Int = Proto.minor, prep: (SimControl) -> Unit = {}) {
        c = SimControl("v1", lib, minor = minor, stateNow = SHOT_CLOCK).also {
            shotClock(it)
            prep(it)
        }
        shell = Shell(Nav(UNDER + top))
        size = sz
        gen++
        settle(1000)
    }

    private fun shoot(dir: File, name: String) {
        rule.onNodeWithTag("frame").captureRoboImage(File(dir, "$name-${size!!.name}.png").path)
        System.gc()
    }

    private fun shots(set: String, body: (File) -> Unit) {
        assumeTrue(System.getProperty("shots.run") == "true")
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val sz = size ?: return@setContent
            key(gen) {
                Box(Modifier.requiredSize(sz.w.dp, sz.h.dp).testTag("frame")) {
                    App(c.device, LIVE, c, shell, insets = sz.bars, ime = WindowInsets(0.dp))
                }
            }
        }
        body(File(System.getProperty("shots.dir") ?: "build/shots", "listpage/$set").apply { mkdirs() })
    }

    private fun card(v: Int) = rule.runOnIdle { c.push(Msg.STATE, c.device.playback.state.copy(card = v).encode()) }

    @Test
    fun edges() = shots("edges") { dir ->
        for (sz in SHAPES) {
            load(sz, LONG_PAGE, edgeLib()) { it.play(Enums.View.playlists, LONG_KEY, 1) }
            shoot(dir, "long")
            load(sz, CJK_PAGE, edgeLib())
            shoot(dir, "cjk-header")
            load(sz, MANY_PAGE, edgeLib())
            rule.mainClock.autoAdvance = true
            rule.onNodeWithTag("rows").performScrollToIndex(MANY.size - 10)
            rule.mainClock.autoAdvance = false
            settle(1000)
            shoot(dir, "past-100")
            load(sz, ROAD, minor = 17) { it.play(Enums.View.playlists, ROAD_KEY, 4) }
            shoot(dir, "no-artist")
            load(sz, ROAD)
            rule.runOnIdle { shell.sheet.open("road", listOf("play")) {} }
            settle(600)
            card(Enums.Card.out)
            settle(1000)
            shoot(dir, "card-out-sheet")
            rule.runOnIdle { shell.sheet.close() }
            settle(1500)
            shoot(dir, "card-out-popped")
            load(sz, ROAD) {
                it.set("accentOff", "on")
                it.play(Enums.View.playlists, ROAD_KEY, 4)
            }
            shoot(dir, "accent-off")
        }
    }

    @Test
    fun page() = shots("page") { dir ->
        for (sz in SHAPES) {
            load(sz, ROAD) { it.play(Enums.View.playlists, ROAD_KEY, 4) }
            shoot(dir, "playlist")
            load(sz, Page.List(Enums.View.favorites, "", "favorites")) { s -> for (i in listOf(3, 8, 14)) s.action(Enums.ActionCmd.favorite, BASE.tracks[i].id) }
            shoot(dir, "favorites")
            load(sz, Page.List(Enums.View.playlists, EMPTY_KEY, "empty"))
            shoot(dir, "empty")
            load(sz, ROAD)
            rule.runOnIdle { c.lists { delete(ROAD_KEY) } }
            settle(1000)
            shoot(dir, "gone")
        }
    }

    private companion object {
        val SHAPES = listOf("411x960", "sn411x438", "wm361x343", "flip960x411").map { n -> SIZES.single { it.name == n } }
        val UNDER = listOf(Page.Home, Page.Playlists)
        val LIVE = LinkView(Phase.LIVE, "V1", everLive = true)
        val BASE = Library.synthetic()
        const val ROAD_KEY = "/Playlists/road.m3u8"
        const val EMPTY_KEY = "/Playlists/empty.m3u8"
        val ROAD = Page.List(Enums.View.playlists, ROAD_KEY, "road")
        val LISTS = listOf(Playlist("road", ROAD_KEY, BASE.tracks.take(12).map { it.id }), Playlist("empty", EMPTY_KEY, emptyList()))
        const val LONG_KEY = "/Playlists/long.m3u8"
        const val CJK_KEY = "/Playlists/cjk.m3u8"
        const val MANY_KEY = "/Playlists/many.m3u8"
        val LONG_PAGE = Page.List(Enums.View.playlists, LONG_KEY, "an unusually long synthetic playlist name for the header")
        val CJK_PAGE = Page.List(Enums.View.playlists, CJK_KEY, "架空の夜行リスト")
        val MANY_PAGE = Page.List(Enums.View.playlists, MANY_KEY, "many")
        val LONG_TRACK = Library.track("Northbound Harbor Lights Ensemble, Quiet Station Choir, Lantern Street Players", "Synthetic Long Album", "An Unusually Long Synthetic Song Title That Runs Past The Row Edge", 1, 200_000)
        val MANY = (1..150).map { Library.track("Synthetic Artist ${it % 7 + 1}", "Synthetic Album ${it / 12 + 1}", "Synthetic Song $it", it % 12 + 1, 180_000) }

        fun edgeLib() = Library(
            listOf(LONG_TRACK) + MANY,
            playlists = listOf(
                Playlist(LONG_PAGE.name, LONG_KEY, MANY.take(1).map { it.id } + LONG_TRACK.id + MANY.drop(1).take(6).map { it.id }),
                Playlist(CJK_PAGE.name, CJK_KEY, MANY.take(8).map { it.id }),
                Playlist(MANY_PAGE.name, MANY_KEY, MANY.map { it.id }),
            ),
        )
    }
}
