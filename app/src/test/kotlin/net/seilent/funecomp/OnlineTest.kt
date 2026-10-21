package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import net.seilent.funecomp.artist.Accounts
import net.seilent.funecomp.artist.ArtBlocks
import net.seilent.funecomp.artist.Sources
import net.seilent.funecomp.artist.Wikimedia
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.Control
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.ONLINE_LYRICS
import net.seilent.funecomp.ui.ONLINE_SCROBBLING
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.ScrobbleView
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.Ui
import net.seilent.funecomp.proto.gen.Tok
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class OnlineTest {
    @get:Rule
    val rule = createComposeRule()

    private var sources by mutableStateOf(Sources.NONE)
    private var link by mutableStateOf(LinkView(Phase.LIVE, "AP80", everLive = true))
    private val saved = ArrayList<Sources>()
    private val lyrics = ArrayList<Boolean>()
    private val scrobbles = ArrayList<Accounts>()

    private fun start(pages: List<Page>, gated: Boolean = false, scrobble: ScrobbleView = ScrobbleView(), w: Int = 411, h: Int = 960): Pair<SimControl, Shell> {
        val c = SimControl("ap80")
        val control = object : Control by c {
            override fun lyrics(on: Boolean) {
                lyrics += on
            }

            override fun scrobble(accounts: Accounts) {
                scrobbles += accounts
            }
        }
        val shell = Shell(Nav(pages), gated = gated)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(w.dp, h.dp).testTag("frame")) {
                App(c.device, link, control, shell, sources, {
                    saved += it
                    sources = it
                }, blocked = listOf(ArtBlocks.Reject("name:x", null, "x", "x", 1)), scrobble = scrobble)
            }
        }
        settle(1000)
        return c to shell
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun tap(tag: String) {
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("rows").performScrollToNode(hasTestTag(tag))
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(tag).performClick()
        settle(600)
    }

    private fun pick(i: Int) {
        rule.onNodeWithTag("sheet.item.$i").performClick()
        settle(600)
    }

    private fun shows(tag: String, text: String) {
        rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).assertExists()
    }

    @Test
    fun onlineHasThreePivotsAndNoCount() {
        start(listOf(Page.Home, Page.Online()))
        for (p in listOf("art", "lyrics", "scrobbling")) rule.onNodeWithTag("pivot.$p").assertExists()
        assertEquals(0, rule.onAllNodesWithTag("count").fetchSemanticsNodes().size)
        rule.onNodeWithTag("art.src.${Wikimedia.SOURCE}").assertExists()
    }

    @Test
    fun aPivotPageOpensOnItsPivot() {
        start(listOf(Page.Home, Page.Online(ONLINE_LYRICS)))
        rule.onNodeWithTag("lyr.src.lrclib").assertExists()
        assertEquals(0, rule.onAllNodesWithTag("art.src.${Wikimedia.SOURCE}").fetchSemanticsNodes().size)
    }

    @Test
    fun sourceRowsFollowTheLookupOrderWithTheirValues() {
        sources = Sources.NONE.copy(fanartTv = true, deezer = true)
        start(listOf(Page.Home, Page.Online()))
        val tops = sources.order.map { bounds("art.src.$it").top }
        assertEquals(tops.sorted(), tops)
        shows("art.src.${Wikimedia.SOURCE}", "ALWAYS")
        shows("art.src.${Sources.FANART_TV}", "NO KEY")
        shows("art.src.${Sources.DEEZER}", "ON")
        shows("art.src.${Sources.DISCOGS}", "OFF")
        rule.onNodeWithTag("art.blocked").assertExists()
        assertEquals(0, rule.onAllNodesWithTag("art.picked").fetchSemanticsNodes().size)
    }

    @Test
    fun eachSourceSheetHasOnlyTheMovesThatApply() {
        sources = Sources.NONE.copy(fanartTv = true)
        val (_, shell) = start(listOf(Page.Home, Page.Online()))
        tap("art.src.${Wikimedia.SOURCE}")
        assertEquals("commons", shell.sheet.title)
        assertEquals(listOf("move down"), shell.sheet.items)
        assertEquals(0, shell.sheet.focus)
        shell.sheet.close()
        settle(600)
        tap("art.src.${Sources.FANART_TV}")
        assertEquals("fanart.tv", shell.sheet.title)
        assertEquals(listOf("off", "on", "move up", "move down", "key"), shell.sheet.items)
        assertEquals(listOf("", "", "", "", "not set"), shell.sheet.itemValues)
        assertEquals(1, shell.sheet.focus)
        shell.sheet.close()
        settle(600)
        tap("art.src.${Sources.DEEZER}")
        assertEquals(listOf("off", "on", "move up"), shell.sheet.items)
        assertEquals(0, shell.sheet.focus)
        assertEquals(emptyList<Sources>(), saved)
    }

    @Test
    fun onAndOffSendOnlyAChange() {
        start(listOf(Page.Home, Page.Online()))
        tap("art.src.${Sources.DISCOGS}")
        pick(0)
        assertEquals(emptyList<Sources>(), saved)
        tap("art.src.${Sources.DISCOGS}")
        pick(1)
        assertEquals(listOf(Sources.NONE.copy(discogs = true)), saved)
        shows("art.src.${Sources.DISCOGS}", "ON")
    }

    @Test
    fun moveUpReordersTheList() {
        val (_, shell) = start(listOf(Page.Home, Page.Online()))
        tap("art.src.${Sources.DEEZER}")
        pick(2)
        assertEquals(listOf(Wikimedia.SOURCE, "theaudiodb", "fanarttv", "discogs", "deezer", "youtube"), sources.order)
        assertEquals(1, saved.size)
        tap("art.src.${Sources.DEEZER}")
        assertEquals(listOf("off", "on", "move up", "move down"), shell.sheet.items)
    }

    @Test
    fun keySavesTheTrimmedKey() {
        sources = Sources.NONE.copy(fanartTv = true)
        start(listOf(Page.Home, Page.Online()))
        tap("art.src.${Sources.FANART_TV}")
        pick(4)
        rule.onNodeWithTag("entry").performTextInput("  abc123 ")
        rule.onNodeWithTag("entry").performImeAction()
        settle(600)
        assertEquals("abc123", sources.fanartTvKey)
        shows("art.src.${Sources.FANART_TV}", "ON")
    }

    @Test
    fun lrclibSheetIsOffOnWithNoMoves() {
        val (_, shell) = start(listOf(Page.Home, Page.Online(ONLINE_LYRICS)))
        tap("lyr.src.lrclib")
        assertEquals("lrclib", shell.sheet.title)
        assertEquals(listOf("off", "on"), shell.sheet.items)
        assertEquals(0, shell.sheet.focus)
        pick(0)
        assertEquals(emptyList<Boolean>(), lyrics)
        tap("lyr.src.lrclib")
        pick(1)
        assertEquals(listOf(true), lyrics)
    }

    @Test
    fun scrobbleServicesOpenOnOffSheetsAndQueuedIsInfo() {
        val accounts = Accounts(lastFm = true)
        val (_, shell) = start(listOf(Page.Home, Page.Online(ONLINE_SCROBBLING)), scrobble = ScrobbleView(accounts, queued = 3))
        tap("scrobble.lastfm")
        assertEquals("last.fm", shell.sheet.title)
        assertEquals(listOf("off", "on"), shell.sheet.items)
        assertEquals(1, shell.sheet.focus)
        pick(1)
        assertEquals(emptyList<Accounts>(), scrobbles)
        tap("scrobble.listenbrainz")
        assertEquals(0, shell.sheet.focus)
        pick(1)
        assertEquals(listOf(accounts.copy(listenBrainz = true)), scrobbles)
        tap("scrobble.queued")
        assertFalse(shell.sheet.shown)
        shows("scrobble.queued", "3")
    }

    @Test
    fun theGatedRootListsOnlineUnderTheStatusAndLiveKeepsThePage() {
        link = LinkView(Phase.CONNECTING, "AP80")
        sources = Sources.NONE.copy(fanartTv = true)
        val (_, shell) = start(listOf(Page.Connection), gated = true)
        val status = bounds("conn.status")
        val online = bounds("conn.online")
        assertEquals(status.bottom, online.top, 1.5f)
        assertEquals(online.bottom, bounds("conn.media").top, 1.5f)
        rule.onNodeWithTag("conn.online").performClick()
        settle(1000)
        assertEquals(listOf(Page.Connection, Page.Online()), shell.nav.stack.toList())
        tap("art.src.${Sources.FANART_TV}")
        pick(4)
        assertTrue(shell.sheet.shown)
        rule.onNodeWithTag("entry").performTextInput("typed1")
        settle(300)
        link = LinkView(Phase.LIVE, "AP80", everLive = true)
        settle(1000)
        assertFalse(shell.gated)
        assertEquals(listOf(Page.Home, Page.Online()), shell.nav.stack.toList())
        assertTrue(shell.sheet.shown)
        assertEquals("fanart.tv key", shell.sheet.title)
        rule.onNodeWithTag("entry").performImeAction()
        settle(600)
        assertEquals("typed1", sources.fanartTvKey)
    }

    private fun barOn(tag: String): Boolean {
        val file = File.createTempFile("bar", ".png")
        rule.onRoot().captureRoboImage(file.path)
        val img = BitmapFactory.decodeFile(file.path)
        file.delete()
        val frame = bounds("frame")
        val ui = Ui.of(frame.width, frame.height)
        val b = bounds(tag)
        val x = (frame.left + ui.px(Tok.hubEdge - Tok.focus_barOffset + Tok.focus_barWidth / 2)).roundToInt()
        return Color.red(img.getPixel(x, b.center.y.roundToInt())) > 128
    }

    @Test
    fun eachPivotKeepsItsFocusAndScroll() {
        val (_, shell) = start(listOf(Page.Home, Page.Online()), w = 960, h = 411)
        tap("art.src.${Sources.DEEZER}")
        shell.sheet.close()
        settle(600)
        val top = bounds("art.src.${Sources.DEEZER}").top
        assertTrue(barOn("art.src.${Sources.DEEZER}"))
        rule.onNodeWithTag("hub.online").performTouchInput { swipeLeft() }
        settle(1000)
        rule.onNodeWithTag("lyr.src.lrclib").assertExists()
        rule.onNodeWithTag("hub.online").performTouchInput { swipeRight() }
        settle(1000)
        assertEquals(top, bounds("art.src.${Sources.DEEZER}").top, 1.5f)
        assertTrue(barOn("art.src.${Sources.DEEZER}"))
        assertFalse(barOn("art.src.${Sources.YOUTUBE}"))
    }

    @Test
    fun listenBrainzOnTheCoverScreenClearsThePanel() {
        val (_, shell) = start(listOf(Page.Home, Page.Online(ONLINE_SCROBBLING)), w = 361, h = 399)
        tap("scrobble.listenbrainz")
        assertTrue(shell.sheet.shown)
        val d = rule.density.density
        val gutter = Ui.of((361 * d).roundToInt().toFloat(), (399 * d).roundToInt().toFloat()).px(Tok.gutter)
        val row = bounds("scrobble.listenbrainz")
        assertTrue("${row.bottom} ${bounds("sheet.panel").top}", row.bottom <= bounds("sheet.panel").top - gutter)
    }

    @Test
    fun anEntryAfterAValueSheetOnAnotherPageStillRises() {
        val (_, shell) = start(listOf(Page.Home, Page.Settings), w = 960, h = 411)
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("rows").performScrollToNode(hasTestTag("set.resume"))
        rule.mainClock.autoAdvance = false
        settle(300)
        rule.onNodeWithTag("set.resume").performClick()
        settle(600)
        assertTrue(shell.sheet.shown)
        shell.sheet.close()
        settle(600)
        shell.nav.pop()
        settle(1000)
        shell.nav.push(Page.Online(ONLINE_SCROBBLING))
        settle(1000)
        tap("scrobble.key")
        assertTrue(shell.sheet.shown)
        val root = bounds("frame")
        val panel = bounds("sheet.panel")
        assertTrue("${panel.top} ${root.bottom}", panel.top >= root.top && panel.bottom <= root.bottom + 1.5f && panel.top < root.bottom - 1.5f)
        rule.onNodeWithTag("entry").assertExists()
    }

    @Test
    fun noOnlineRowOnceTheGateIsLifted() {
        start(listOf(Page.Home, Page.Connection))
        assertEquals(0, rule.onAllNodesWithTag("conn.online").fetchSemanticsNodes().size)
    }
}
