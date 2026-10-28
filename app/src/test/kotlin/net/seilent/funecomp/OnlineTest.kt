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
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import net.seilent.funecomp.artist.Accounts
import net.seilent.funecomp.artist.ArtBlocks
import net.seilent.funecomp.artist.ArtCache
import net.seilent.funecomp.artist.LyricSources
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
import net.seilent.funecomp.ui.TextEntry
import net.seilent.funecomp.ui.Ui
import net.seilent.funecomp.proto.gen.Tok
import android.graphics.BitmapFactory
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.graphics.Color
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class OnlineTest {
    @get:Rule
    val rule = createComposeRule()

    private var sources by mutableStateOf(Sources.COMMONS)
    private var link by mutableStateOf(LinkView(Phase.LIVE, "AP80", everLive = true))
    private val saved = ArrayList<Sources>()
    private val lyrics = ArrayList<Pair<String, Boolean>>()
    private val moves = ArrayList<Pair<String, Int>>()
    private val scrobbles = ArrayList<Accounts>()
    private var holdUsage = false

    private fun start(pages: List<Page>, gated: Boolean = false, scrobble: ScrobbleView = ScrobbleView(), w: Int = 411, h: Int = 960, usage: Map<String?, ArtCache.Usage> = emptyMap(), ime: Dp = 0.dp): Pair<SimControl, Shell> {
        val c = SimControl("ap80")
        c.usage = usage
        val control = object : Control by c {
            override fun lyricSource(id: String, on: Boolean) {
                lyrics += id to on
            }

            override fun lyricMove(id: String, by: Int) {
                moves += id to by
                link = link.copy(lyrics = link.lyrics.moved(id, by))
            }

            override fun scrobble(accounts: Accounts) {
                scrobbles += accounts
            }

            override fun artUsage(done: (Map<String?, ArtCache.Usage>) -> Unit) {
                if (!holdUsage) c.artUsage(done)
            }
        }
        val shell = Shell(Nav(pages), gated = gated)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(w.dp, h.dp).testTag("frame")) {
                App(c.device, link, control, shell, sources, {
                    saved += it
                    sources = it
                }, blocked = listOf(ArtBlocks.Reject("name:x", null, "x", "x", 1)), scrobble = scrobble, ime = WindowInsets(bottom = ime))
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
        sources = Sources.COMMONS.copy(fanartTv = true, deezer = true)
        start(listOf(Page.Home, Page.Online()))
        val tops = sources.order.map { bounds("art.src.$it").top }
        assertEquals(tops.sorted(), tops)
        shows("art.src.${Wikimedia.SOURCE}", "ON")
        shows("art.src.${Sources.FANART_TV}", "NO KEY")
        shows("art.src.${Sources.DEEZER}", "ON")
        shows("art.src.${Sources.DISCOGS}", "OFF")
        rule.onNodeWithTag("art.blocked").assertExists()
        assertEquals(0, rule.onAllNodesWithTag("art.picked").fetchSemanticsNodes().size)
    }

    @Test
    fun eachSourceSheetHasOnlyTheMovesThatApply() {
        sources = Sources.COMMONS.copy(fanartTv = true)
        val (_, shell) = start(listOf(Page.Home, Page.Online()))
        tap("art.src.${Wikimedia.SOURCE}")
        assertEquals("commons", shell.sheet.title)
        assertEquals(listOf("off", "on", "move down"), shell.sheet.items)
        assertEquals(1, shell.sheet.focus)
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
        assertEquals(listOf(Sources.COMMONS.copy(discogs = true)), saved)
        shows("art.src.${Sources.DISCOGS}", "ON")
    }

    @Test
    fun commonsTurnsOffAndOnLikeTheOtherSources() {
        val (_, shell) = start(listOf(Page.Home, Page.Online()))
        tap("art.src.${Wikimedia.SOURCE}")
        pick(1)
        assertEquals(emptyList<Sources>(), saved)
        tap("art.src.${Wikimedia.SOURCE}")
        pick(0)
        assertEquals(listOf(Sources.NONE), saved)
        shows("art.src.${Wikimedia.SOURCE}", "OFF")
        tap("art.src.${Wikimedia.SOURCE}")
        assertEquals(listOf("off", "on", "move down"), shell.sheet.items)
        assertEquals(0, shell.sheet.focus)
        pick(1)
        assertEquals(listOf(Sources.NONE, Sources.COMMONS), saved)
        shows("art.src.${Wikimedia.SOURCE}", "ON")
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

    private fun assertSecret(field: EditText) {
        assertTrue(field.isFocused)
        val info = EditorInfo()
        field.onCreateInputConnection(info)
        assertEquals(InputType.TYPE_CLASS_TEXT, info.inputType and InputType.TYPE_MASK_CLASS)
        assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, info.inputType and InputType.TYPE_MASK_VARIATION)
        assertEquals(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        assertEquals(0, info.inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        assertEquals(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertEquals(EditorInfo.IME_ACTION_DONE, info.imeOptions and EditorInfo.IME_MASK_ACTION)
        assertFalse(field.isImportantForAutofill)
        assertEquals(View.AUTOFILL_TYPE_NONE, field.autofillType)
        assertFalse(field.isSaveEnabled)
    }

    private fun keyEntry(): EditText {
        sources = Sources.COMMONS.copy(fanartTv = true)
        start(listOf(Page.Home, Page.Online()))
        tap("art.src.${Sources.FANART_TV}")
        pick(4)
        return secretField()
    }

    @Test
    fun aSecretNeverShowsATypedCharacter() {
        Settings.System.putInt(ApplicationProvider.getApplicationContext<Context>().contentResolver, Settings.System.TEXT_SHOW_PASSWORD, 1)
        val field = keyEntry()
        for (c in "abc123") field.append(c.toString())
        assertEquals("\u2022".repeat(6), field.layout.text.toString())
    }

    @Test
    fun aHardwareEnterSavesTheKeyOnce() {
        val field = keyEntry()
        field.append("abc123")
        val before = saved.size
        field.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        field.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        settle(600)
        assertEquals("abc123", sources.fanartTvKey)
        assertEquals(before + 1, saved.size)
    }

    @Test
    fun aClosedSecretEntryHidesTheKeyboard() {
        val field = keyEntry()
        val ime = shadowOf(field.context.getSystemService(InputMethodManager::class.java))
        assertTrue(ime.isSoftInputVisible)
        field.onEditorAction(EditorInfo.IME_ACTION_DONE)
        settle(1000)
        assertFalse(ime.isSoftInputVisible)
    }

    @Test
    fun theFanartKeyEntryIsAPasswordFieldTheKeyboardDoesNotLearn() {
        sources = Sources.COMMONS.copy(fanartTv = true)
        start(listOf(Page.Home, Page.Online()))
        tap("art.src.${Sources.FANART_TV}")
        pick(4)
        assertSecret(secretField())
    }

    @Test
    fun theListenBrainzTokenEntryIsAPasswordFieldTheKeyboardDoesNotLearn() {
        start(listOf(Page.Home, Page.Online(ONLINE_SCROBBLING)))
        tap("scrobble.token")
        assertSecret(secretField())
    }

    @Test
    fun aPlainEntryKeepsTheComposeField() {
        val (_, shell) = start(listOf(Page.Home, Page.Online()))
        shell.sheet.openEntry("search") { TextEntry("hd 650") {} }
        settle(600)
        assertNull(resumedRoot().findViewWithTag<EditText>("entry"))
        val info = EditorInfo()
        resumedRoot().findFocus().onCreateInputConnection(info)
        assertEquals(InputType.TYPE_CLASS_TEXT, info.inputType and InputType.TYPE_MASK_CLASS)
        assertEquals(0, info.inputType and InputType.TYPE_MASK_VARIATION)
        assertEquals(EditorInfo.IME_FLAG_FORCE_ASCII, info.imeOptions and EditorInfo.IME_FLAG_FORCE_ASCII)
        assertEquals(0, info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
    }

    @Test
    fun aClosedSecretEntryDropsItsText() {
        sources = Sources.COMMONS.copy(fanartTv = true)
        start(listOf(Page.Home, Page.Online()))
        tap("art.src.${Sources.FANART_TV}")
        pick(4)
        val field = secretField()
        field.append("abc123")
        field.onEditorAction(EditorInfo.IME_ACTION_DONE)
        settle(1000)
        assertEquals("abc123", sources.fanartTvKey)
        assertEquals("", field.text.toString())
        assertNull(resumedRoot().findViewWithTag<EditText>("entry"))
    }

    @Test
    fun keySavesTheTrimmedKey() {
        sources = Sources.COMMONS.copy(fanartTv = true)
        start(listOf(Page.Home, Page.Online()))
        tap("art.src.${Sources.FANART_TV}")
        pick(4)
        secretField().append("  abc123 ")
        secretField().onEditorAction(EditorInfo.IME_ACTION_DONE)
        settle(600)
        assertEquals("abc123", sources.fanartTvKey)
        shows("art.src.${Sources.FANART_TV}", "ON")
    }

    private val cached = mapOf(null to ArtCache.Usage(5, 12_300_000), Sources.DEEZER to ArtCache.Usage(3, 7_000_000), Wikimedia.SOURCE to ArtCache.Usage(2, 5_000_000))

    private fun hold(tag: String) {
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("rows").performScrollToNode(hasTestTag(tag))
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(tag).performTouchInput { longClick() }
        settle(600)
    }

    @Test
    fun theClearRowShowsTheCacheSizeAndClearsOnlyOnClear() {
        sources = Sources.COMMONS.copy(deezer = true)
        val (c, shell) = start(listOf(Page.Home, Page.Online()), usage = cached)
        shows("art.clear", "12.3 MB")
        tap("art.clear")
        assertEquals("clear cache?", shell.sheet.title)
        assertEquals(listOf("clear cache", "keep cache"), shell.sheet.items)
        assertEquals(listOf("5 photos, 12.3 mb"), shell.sheet.info)
        assertEquals(1, shell.sheet.focus)
        pick(1)
        tap("art.clear")
        shell.sheet.close()
        settle(600)
        assertEquals(emptyList<String?>(), c.cleared)
        tap("art.clear")
        pick(0)
        assertEquals(listOf<String?>(null), c.cleared)
        shows("art.clear", "0.0 MB")
        assertEquals(emptyList<Sources>(), saved)
    }

    @Test
    fun aLongPressOnASourceOffersToClearItsPhotos() {
        sources = Sources.COMMONS.copy(deezer = true)
        val (c, shell) = start(listOf(Page.Home, Page.Online()), usage = cached)
        hold("art.src.${Sources.DEEZER}")
        assertEquals("clear deezer?", shell.sheet.title)
        assertEquals(listOf("clear photos", "keep photos"), shell.sheet.items)
        assertEquals(listOf("3 photos, 7.0 mb"), shell.sheet.info)
        assertEquals(1, shell.sheet.focus)
        pick(0)
        assertEquals(listOf<String?>(Sources.DEEZER), c.cleared)
        shows("art.clear", "5.3 MB")
        assertEquals(emptyList<Sources>(), saved)
        hold("art.src.${Sources.DISCOGS}")
        assertEquals("clear discogs?", shell.sheet.title)
        assertEquals(listOf("keep photos"), shell.sheet.items)
        assertEquals(listOf("no photos"), shell.sheet.info)
        pick(0)
        assertEquals(listOf<String?>(Sources.DEEZER), c.cleared)
    }

    @Test
    fun turningASourceOffWithPhotosAsksAndKeepIsFocused() {
        sources = Sources.COMMONS.copy(deezer = true)
        val (c, shell) = start(listOf(Page.Home, Page.Online()), usage = cached)
        tap("art.src.${Sources.DEEZER}")
        pick(0)
        assertEquals(listOf(Sources.COMMONS), saved)
        assertEquals("clear deezer?", shell.sheet.title)
        assertEquals(1, shell.sheet.focus)
        pick(1)
        assertEquals(emptyList<String?>(), c.cleared)
        tap("art.src.${Wikimedia.SOURCE}")
        pick(0)
        assertEquals("clear commons?", shell.sheet.title)
        shell.sheet.close()
        settle(600)
        assertEquals(emptyList<String?>(), c.cleared)
        tap("art.src.${Wikimedia.SOURCE}")
        pick(1)
        tap("art.src.${Wikimedia.SOURCE}")
        pick(0)
        pick(0)
        assertEquals(listOf<String?>(Wikimedia.SOURCE), c.cleared)
    }

    @Test
    fun noClearSheetOpensBeforeTheCountsArrive() {
        holdUsage = true
        sources = Sources.COMMONS.copy(deezer = true)
        val (c, shell) = start(listOf(Page.Home, Page.Online()), usage = cached)
        hold("art.src.${Sources.DEEZER}")
        assertFalse(shell.sheet.shown)
        tap("art.src.${Sources.DEEZER}")
        pick(0)
        assertEquals(listOf(Sources.COMMONS), saved)
        assertFalse(shell.sheet.shown)
        tap("art.clear")
        assertFalse(shell.sheet.shown)
        assertEquals(emptyList<String?>(), c.cleared)
    }

    @Test
    fun turningASourceWithNoPhotosOffAsksNothing() {
        sources = Sources.COMMONS.copy(discogs = true)
        val (c, shell) = start(listOf(Page.Home, Page.Online()), usage = cached)
        tap("art.src.${Sources.DISCOGS}")
        pick(0)
        assertEquals(listOf(Sources.COMMONS), saved)
        assertFalse(shell.sheet.shown)
        assertEquals(emptyList<String?>(), c.cleared)
    }

    @Test
    fun theLyricsRowsFollowTheSourceOrderWithTheirValues() {
        link = link.copy(lyrics = LyricSources(netease = false))
        start(listOf(Page.Home, Page.Online(ONLINE_LYRICS)))
        assertTrue(bounds("lyr.src.lrclib").top < bounds("lyr.src.netease").top)
        assertTrue(bounds("lyr.src.netease").top < bounds("lyrics.copyright").top)
        shows("lyr.src.lrclib", "ON")
        shows("lyr.src.netease", "OFF")
    }

    @Test
    fun eachLyricsSheetHasOnlyTheMovesThatApply() {
        val (_, shell) = start(listOf(Page.Home, Page.Online(ONLINE_LYRICS)))
        tap("lyr.src.lrclib")
        assertEquals("lrclib", shell.sheet.title)
        assertEquals(listOf("off", "on", "move down"), shell.sheet.items)
        assertEquals(1, shell.sheet.focus)
        shell.sheet.close()
        settle(600)
        tap("lyr.src.netease")
        assertEquals("netease", shell.sheet.title)
        assertEquals(listOf("off", "on", "move up"), shell.sheet.items)
        assertEquals(emptyList<Pair<String, Boolean>>(), lyrics)
    }

    @Test
    fun lyricsOnAndOffSendOnlyAChange() {
        start(listOf(Page.Home, Page.Online(ONLINE_LYRICS)))
        tap("lyr.src.netease")
        pick(1)
        assertEquals(emptyList<Pair<String, Boolean>>(), lyrics)
        tap("lyr.src.netease")
        pick(0)
        assertEquals(listOf("netease" to false), lyrics)
    }

    @Test
    fun aLyricsMoveKeepsTheFocusOnTheMovedSource() {
        val (_, shell) = start(listOf(Page.Home, Page.Online(ONLINE_LYRICS)))
        tap("lyr.src.netease")
        pick(2)
        assertEquals(listOf("netease" to -1), moves)
        assertTrue(bounds("lyr.src.netease").top < bounds("lyr.src.lrclib").top)
        tap("lyr.src.netease")
        assertEquals(listOf("off", "on", "move down"), shell.sheet.items)
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
        sources = Sources.COMMONS.copy(fanartTv = true)
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
        secretField().append("typed1")
        settle(300)
        link = LinkView(Phase.LIVE, "AP80", everLive = true)
        settle(1000)
        assertFalse(shell.gated)
        assertEquals(listOf(Page.Home, Page.Online()), shell.nav.stack.toList())
        assertTrue(shell.sheet.shown)
        assertEquals("fanart.tv key", shell.sheet.title)
        secretField().onEditorAction(EditorInfo.IME_ACTION_DONE)
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
        tap("scrobble.token")
        assertTrue(shell.sheet.shown)
        val root = bounds("frame")
        val panel = bounds("sheet.panel")
        assertTrue("${panel.top} ${root.bottom}", panel.top >= root.top && panel.bottom <= root.bottom + 1.5f && panel.top < root.bottom - 1.5f)
        rule.onNodeWithTag("entry").assertExists()
    }

    private val mix2: Pair<Int, Int> get() = (MIX2_W / rule.density.density).roundToInt() to (MIX2_H / rule.density.density).roundToInt()

    private val mix2Ui: Ui get() = rule.density.density.let { d -> Ui.of((mix2.first * d).roundToInt().toFloat(), (mix2.second * d).roundToInt().toFloat()) }

    private fun entryAbove(room: Float): Triple<Rect, Rect, Float> {
        val d = rule.density.density
        val keyboard = (mix2.second * d).roundToInt() - room
        start(listOf(Page.Home, Page.Online(ONLINE_SCROBBLING)), w = mix2.first, h = mix2.second, ime = (keyboard / d).dp)
        tap("scrobble.token")
        rule.onNodeWithTag("entry").assertExists()
        return Triple(bounds("sheet.panel"), bounds("sheet.item.0"), bounds("frame").bottom - keyboard)
    }

    private fun assertWholeRow(panel: Rect, row: Rect, top: Float) {
        val pitch = mix2Ui.px(Tok.settingsPitch)
        assertTrue("$row $panel $top", row.height >= pitch - 1f && row.top >= panel.top - 1f && row.bottom <= panel.bottom + 1f && panel.bottom <= top + 1f)
    }

    @Test
    fun anEntryRowStaysAboveAKeyboardThatLeavesRoomForOneRow() {
        val (panel, row, top) = entryAbove(mix2Ui.px(Tok.settingsPitch) + mix2Ui.px(Tok.edge))
        assertWholeRow(panel, row, top)
    }

    @Test
    fun aTitleThatCannotFitWholeLeavesThePanelWhole() {
        val (panel, row, top) = entryAbove(mix2Ui.px(Tok.settingsPitch) + mix2Ui.px(Tok.edge) + TITLE_PART)
        assertWholeRow(panel, row, top)
        assertEquals(panel.top, row.top, 1f)
    }

    @Test
    fun aKeyboardLeavingLessThanARowAndItsEdgeKeepsTheRowWhole() {
        val (panel, row, top) = entryAbove(mix2Ui.px(Tok.settingsPitch) + TITLE_PART)
        assertWholeRow(panel, row, top)
    }

    @Test
    fun noOnlineRowOnceTheGateIsLifted() {
        start(listOf(Page.Home, Page.Connection))
        assertEquals(0, rule.onAllNodesWithTag("conn.online").fetchSemanticsNodes().size)
    }
}

private const val MIX2_W = 2160f
private const val MIX2_H = 1080f - 66f
private const val TITLE_PART = 10f
