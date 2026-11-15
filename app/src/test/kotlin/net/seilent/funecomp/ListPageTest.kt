package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.sim.Playlist
import net.seilent.funecomp.state.LibKey
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.Control
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.Ui
import net.seilent.funecomp.ui.accentFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class ListPageTest {
    @get:Rule
    val rule = createComposeRule()

    private val base = Library.synthetic()
    private val road = base.tracks.take(12).map { it.id }
    private val roadKey = "/Playlists/road.m3u8"
    private val longKey = "/Playlists/long.m3u8"
    private val nightKey = "/Playlists/night.m3u8"
    private val emptyKey = "/Playlists/empty.m3u8"
    private val roadPage = Page.List(Enums.View.playlists, roadKey, "road")
    private val under = listOf(Page.Home, Page.Playlists)
    private lateinit var c: SimControl
    private lateinit var shell: Shell
    private var holdRows = false
    private val heldRows = ArrayList<() -> Unit>()
    private val plays = ArrayList<Pair<Long, Long?>>()

    private fun start(top: Page = roadPage) {
        val lists = listOf(
            Playlist("road", roadKey, road),
            Playlist("long", longKey, base.tracks.map { it.id }),
            Playlist("night", nightKey, base.tracks.takeLast(3).map { it.id }),
            Playlist("empty", emptyKey, emptyList()),
        )
        c = SimControl("v1", Library(base.tracks, playlists = lists))
        val control = object : Control by c {
            override fun libGet(view: Int, parent: String, offset: Long) {
                if (holdRows && parent.isNotEmpty()) heldRows += { c.libGet(view, parent, offset) } else c.libGet(view, parent, offset)
            }

            override fun play(view: Int?, parent: String?, start: Long, shuffle: Boolean, tracks: List<Long>, libRev: Long?, done: (Int?) -> Unit) {
                plays += start to libRev
                c.play(view, parent, start, shuffle, tracks, libRev, done)
            }
        }
        shell = Shell(Nav(under + top))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, "V1", everLive = true), control, shell) }
        }
        settle(1500)
    }

    private fun settle(ms: Long = 1500) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun open(top: Page) {
        rule.runOnIdle { shell.nav.reset(under + top) }
        settle()
    }

    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val out = ArrayList<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(out)
        return out.single()
    }

    private fun text(tag: String) = layout(rule.onNodeWithTag(tag, useUnmergedTree = true)).layoutInput.text.text

    private fun words(tag: String): List<String> =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))), useUnmergedTree = true)
            .fetchSemanticsNodes().flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text.lowercase() } }

    private fun inRow(i: Int, m: SemanticsMatcher) = rule.onNode(m and hasAnyAncestor(hasTestTag("row.$i")), useUnmergedTree = true)

    private fun nodes(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun title(id: Long) = base.byId.getValue(id).title

    private fun artists(id: Long) = base.byId.getValue(id).artists.joinToString(", ")

    private fun card(v: Int) = rule.runOnIdle { c.push(Msg.STATE, c.device.playback.state.copy(card = v).encode()) }

    private fun assertNothingHere(header: String) {
        assertEquals(listOf("nothing here"), words("line"))
        assertEquals(header, text("header.word"))
        assertEquals(0, nodes("row.0") + nodes("count"))
    }

    @Test
    fun aPlaylistPageShowsItsNameCountAndNumberedRowsWithTheArtistLine() {
        start()
        val header = layout(rule.onNodeWithTag("header.word", useUnmergedTree = true))
        assertEquals("ROAD", header.layoutInput.text.text)
        assertEquals(FontWeight.Bold, header.layoutInput.style.fontWeight)
        assertEquals("12 SONGS", text("count"))
        val frame = rule.onNodeWithTag("hub.road").fetchSemanticsNode().boundsInRoot
        val ui = Ui.of(frame.width, frame.height)
        val drawn = listOf(0, 5, 11).filter { nodes("row.$it") > 0 }
        assertTrue("$drawn", 0 in drawn)
        for (i in drawn) {
            assertEquals(title(road[i]), layout(inRow(i, hasTestTag("primary"))).layoutInput.text.text)
            assertEquals("${i + 1}", layout(inRow(i, hasText("${i + 1}"))).layoutInput.text.text)
            val sub = layout(inRow(i, hasText(artists(road[i])))).layoutInput.style
            assertEquals(Tok.type_listLibSecondary_alpha, sub.color.alpha, 0.01f)
            assertEquals(ui.fontPx(Tok.type_listSecondary_size), with(rule.density) { sub.fontSize.toPx() }, 0.5f)
        }
        rule.onNodeWithTag("row.2").performClick()
        settle()
        assertEquals(Page.NowPlaying, shell.nav.top)
        assertEquals(title(road[2]), c.device.playback.state.title)
        assertEquals(12L, c.device.playback.state.slots)
        rule.runOnIdle { shell.nav.pop() }
        settle()
        settle(Tok.motion_focusAlpha.toLong() + 16)
        val a = accentFor(Tok.accent_hueDefault.toInt())
        val lit = layout(inRow(2, hasTestTag("primary"))).layoutInput.style.color
        assertTrue("$lit", abs(lit.red - a.red) < 0.02f && abs(lit.green - a.green) < 0.02f && abs(lit.blue - a.blue) < 0.02f)
        val plain = layout(inRow(1, hasTestTag("primary"))).layoutInput.style.color
        assertTrue("$plain", abs(plain.red - a.red) > 0.02f || abs(plain.green - a.green) > 0.02f || abs(plain.blue - a.blue) > 0.02f)
    }

    @Test
    fun favoritesOpenThroughView7NewestFirst() {
        start(Page.Playlists)
        val (a, b) = base.tracks[3].id to base.tracks[8].id
        rule.runOnIdle {
            c.action(Enums.ActionCmd.favorite, a)
            c.action(Enums.ActionCmd.favorite, b)
        }
        open(Page.List(Enums.View.favorites, "", "favorites"))
        assertEquals("FAVORITES", text("header.word"))
        assertEquals("2 SONGS", text("count"))
        assertEquals(listOf(title(b), title(a)), (0..1).map { layout(inRow(it, hasTestTag("primary"))).layoutInput.text.text })
        rule.onNodeWithTag("row.1").performClick()
        settle()
        assertEquals(title(a), c.device.playback.state.title)
        assertEquals(2L, c.device.playback.state.slots)
    }

    @Test
    fun anEmptyListSaysNothingHereWithNoCount() {
        start(Page.List(Enums.View.playlists, emptyKey, "empty"))
        assertNothingHere("EMPTY")
    }

    @Test
    fun aRenameAHideAndADeleteTurnThePageToNothingHereUnderTheOldName() {
        start()
        rule.runOnIdle { c.lists { rename(roadKey, "/Playlists/trip.m3u8", "trip") } }
        settle()
        assertNothingHere("ROAD")
        open(Page.List(Enums.View.playlists, longKey, "long"))
        assertTrue(nodes("row.0") > 0)
        rule.runOnIdle { c.lists { hide(longKey) } }
        settle()
        assertNothingHere("LONG")
        open(Page.List(Enums.View.playlists, nightKey, "night"))
        assertEquals("3 SONGS", text("count"))
        rule.runOnIdle { c.lists { delete(nightKey) } }
        settle()
        assertNothingHere("NIGHT")
        assertEquals(Page.List(Enums.View.playlists, nightKey, "night"), shell.nav.top)
    }

    @Test
    fun aMoveThatBringsTheListBackShowsItsRows() {
        start()
        rule.runOnIdle { c.lists { hide(roadKey) } }
        settle()
        assertNothingHere("ROAD")
        rule.runOnIdle { c.lists { hide(roadKey, false) } }
        settle()
        assertEquals("12 SONGS", text("count"))
        assertEquals(title(road[0]), layout(inRow(0, hasTestTag("primary"))).layoutInput.text.text)
        assertEquals(0, nodes("line"))
    }

    @Test
    fun aFavoriteToggleKeepsThePlaylistRowsDrawnAtTheirScroll() {
        start(Page.List(Enums.View.playlists, longKey, "long"))
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("rows").performScrollToIndex(10)
        rule.mainClock.autoAdvance = false
        settle()
        val y = rule.onNodeWithTag("row.10").fetchSemanticsNode().positionInRoot.y
        val gets = c.sent.count { it == "LIB_GET" }
        rule.runOnIdle { c.action(Enums.ActionCmd.favorite, base.tracks[0].id) }
        assertNull(c.device.lib.pages[LibKey(Enums.View.playlists, longKey)])
        repeat(30) {
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            assertEquals("frame $it", 1, nodes("row.10"))
            assertEquals("frame $it", y, rule.onNodeWithTag("row.10").fetchSemanticsNode().positionInRoot.y, 0.5f)
        }
        assertTrue(c.sent.count { it == "LIB_GET" } > gets)
        assertTrue(c.device.lib.pages[LibKey(Enums.View.playlists, longKey)] != null)
    }

    @Test
    fun aCardGoneUnderNowPlayingLeavesTheViewPageWithItsLine() {
        start()
        rule.runOnIdle { shell.nav.push(Page.NowPlaying) }
        settle()
        card(Enums.Card.out)
        settle()
        rule.runOnIdle { shell.nav.pop() }
        settle()
        settle()
        assertEquals(roadPage, shell.nav.top)
        assertEquals(listOf("no card"), words("status"))
    }

    @Test
    fun openPlaylistPushesTheViewPage() {
        start(Page.Playlists)
        rule.onNodeWithTag("row.playlist.0").performTouchInput { longClick() }
        settle()
        assertEquals("open playlist", shell.sheet.items.last())
        rule.runOnIdle { shell.sheet.accept(shell.sheet.items.lastIndex) }
        settle()
        assertEquals(roadPage, shell.nav.top)
        assertEquals("12 SONGS", text("count"))
        open(Page.Playlists)
        rule.onNodeWithTag("row.favorites").performTouchInput { longClick() }
        settle()
        assertEquals(listOf("open playlist"), shell.sheet.items)
        rule.runOnIdle { shell.sheet.accept(0) }
        settle()
        assertEquals(Page.List(Enums.View.favorites, "", "favorites"), shell.nav.top)
        assertNothingHere("FAVORITES")
    }

    @Test
    fun aRowTapReadBeforeAMoveSendsItsOwnRevAndPlaysItsTrack() {
        start()
        val rev = c.device.lib.rev
        holdRows = true
        rule.runOnIdle { c.lists { edit(roadKey, road - road[2]) } }
        settle()
        assertNull(c.device.lib.pages[LibKey(Enums.View.playlists, roadKey)])
        assertEquals(title(road[5]), layout(inRow(5, hasTestTag("primary"))).layoutInput.text.text)
        rule.onNodeWithTag("row.5").performClick()
        settle()
        assertEquals(listOf(5L to rev), plays)
        assertEquals(Page.NowPlaying, shell.nav.top)
        holdRows = false
        rule.runOnIdle { heldRows.toList().forEach { it() } }
        settle()
        assertEquals(listOf(5L to rev, 4L to null), plays)
        assertEquals(title(road[5]), c.device.playback.state.title)
        assertEquals(Page.NowPlaying, shell.nav.top)
    }

    @Test
    fun theCardEdgePopsToThePageUnderAndWaitsForAnOpenSheet() {
        start()
        card(Enums.Card.out)
        settle()
        assertEquals(Page.Playlists, shell.nav.top)
        card(Enums.Card.ready)
        open(roadPage)
        rule.runOnIdle { shell.sheet.open("road", listOf("play")) {} }
        settle()
        card(Enums.Card.error)
        settle()
        assertEquals(roadPage, shell.nav.top)
        rule.runOnIdle { shell.sheet.close() }
        settle()
        assertEquals(Page.Playlists, shell.nav.top)
        card(Enums.Card.ready)
        open(roadPage)
        rule.runOnIdle { shell.sheet.open("road", listOf("play")) {} }
        settle()
        card(Enums.Card.out)
        settle()
        card(Enums.Card.ready)
        settle()
        rule.runOnIdle { shell.sheet.close() }
        settle()
        assertEquals(roadPage, shell.nav.top)
    }
}
