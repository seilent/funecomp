package net.seilent.funecomp

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import net.seilent.funecomp.artist.ArtCache
import net.seilent.funecomp.proto.LyrLine
import net.seilent.funecomp.proto.LyrOffsetReply
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.sim.Answer
import net.seilent.funecomp.sim.Out
import net.seilent.funecomp.state.LyricsFetch
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.BackdropArt
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.zip.CRC32

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class LyricsSheetTest {
    @get:Rule
    val rule = createComposeRule()

    private var control by mutableStateOf<SimControl?>(null)
    private var shellState by mutableStateOf<Shell?>(null)
    private val c: SimControl get() = control!!
    private val shell: Shell get() = shellState!!
    private var content = false
    private val entry = ArtCache.Entry("name:むと", "むと", null, "deezer", null, "u", 1, sha = "s")
    private var kind by mutableStateOf<Int?>(Enums.LyricsKind.synced)
    private val body = (0 until 12).joinToString("") { "[00:%02d.00]line %d\n".format(it * 4, it) }

    private val sink = object : Out {
        override fun push(type: Int, payload: ByteArray) = c.push(type, payload)
    }

    private fun shown(): LyricsFetch.Show {
        val s = c.device.playback.state
        val t = s.track ?: return LyricsFetch.Show.Off
        val k = kind ?: return LyricsFetch.Show.None
        val off = s.lyricsOffsetMs ?: 0
        val lines = (0 until 12).map { LyrLine(if (k == Enums.LyricsKind.synced) (it * 4000L - off).coerceAtLeast(0) else null, "line $it") }
        return LyricsFetch.Show.Lines(t, LyricsFetch.Copy(s.lyricsCrc ?: 1, k, lines))
    }

    private fun put(text: String) = text.toByteArray().let {
        c.sim.request(Msg.LRC_PUT, Node.decode("LRC_PUT", Req.lrcPut(c.device.playback.state.track!!, c.device.playback.state.libRev, it, CRC32().apply { update(it) }.value)), sink)
    }

    private fun refresh() = c.push(Msg.STATE, c.sim.state())

    private fun start(minor: Int = 15, plain: Boolean = false, source: Int = Enums.Source.local, offset: Int = 0, enter: Boolean = true) {
        val sim = SimControl("ap80", now = { SystemClock.elapsedRealtime() }, minor = minor)
        control = sim
        kind = if (plain) Enums.LyricsKind.plain else Enums.LyricsKind.synced
        put(if (plain) "just words\nmore words\n" else body)
        c.transport(Enums.TransportCmd.seek, seekMs = 9_950)
        if (offset != 0) c.sim.request(Msg.LYR_OFFSET, Node.decode("LYR_OFFSET", Req.lyrOffset(c.device.playback.state.track!!, offset)), sink)
        refresh()
        if (source != Enums.Source.local) c.device = c.device.withState(c.device.playback.state.copy(source = source), SystemClock.elapsedRealtime())
        c.offsets.clear()
        shellState = Shell(Nav(listOf(Page.Home, Page.NowPlaying)))
        rule.mainClock.autoAdvance = false
        if (!content) {
            content = true
            rule.setUptimeContent {
                val sh = shellState ?: return@setUptimeContent
                val sc = control ?: return@setUptimeContent
                key(sh) {
                    Box(Modifier.requiredSize(411.dp, 960.dp)) {
                        App(sc.device, LinkView(Phase.LIVE, "AP80", everLive = true), sc, sh, art = BackdropArt(ImageBitmap(8, 8), null, entry), lyrics = shown(), liveDevice = { sc.device })
                    }
                }
            }
        }
        settle(1000)
        if (enter) {
            tap("np.art")
            settle(1000)
            assertTrue(exists("np.lyrics"))
        }
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        var left = ms
        while (left > 0) {
            val step = minOf(16L, left)
            val target = SystemClock.elapsedRealtime() + step
            rule.mainClock.advanceTimeBy(step)
            val now = SystemClock.elapsedRealtime()
            if (now < target) ShadowSystemClock.advanceBy(Duration.ofMillis(target - now))
            left -= step
        }
        rule.waitForIdle()
    }

    private fun exists(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun tap(tag: String) {
        rule.onNodeWithTag(tag).performClick()
        settle(400)
    }

    private fun half(right: Boolean) {
        rule.onNodeWithTag("sheet.item.0").performTouchInput { click(if (right) centerRight.copy(x = width * 0.75f) else centerLeft.copy(x = width * 0.25f)) }
        settle(300)
    }

    private fun back() {
        Espresso.pressBack()
        settle(400)
    }

    private fun opened() = shell.sheet.shown && shell.sheet.title == "lyrics" && shell.offset != null

    private fun open() {
        tap("np.lyrics")
        assertTrue(opened())
    }

    private fun state(f: (State) -> State) {
        c.device = c.device.withState(f(c.device.playback.state), SystemClock.elapsedRealtime())
        settle(200)
    }

    @Test
    fun aTapOnSyncedLyricsOpensTheSheetOnTheState38Value() {
        start(offset = 500)
        open()
        assertEquals(listOf("offset"), shell.sheet.items)
        assertEquals(listOf("+500 ms"), shell.sheet.itemValues)
        assertEquals(listOf<Int?>(null), c.offsets)
        assertEquals(500, shell.offset?.stored)
        assertTrue(exists("np.lyrics"))
    }

    @Test
    fun plainBlankNoneNoBitAndSourcePhoneOpenNothing() {
        start(plain = true)
        tap("np.lyrics")
        assertFalse(shell.sheet.shown)
        kind = null
        settle(200)
        tap("np.lyrics")
        assertFalse(shell.sheet.shown)
        start(minor = 14)
        tap("np.lyrics")
        assertFalse(shell.sheet.shown)
        start()
        state { it.copy(source = Enums.Source.phone) }
        tap("np.lyrics")
        assertFalse(shell.sheet.shown)
        assertTrue(c.offsets.isEmpty())
    }

    @Test
    fun aTapDuringTheEnterFadeOpensNothing() {
        start(enter = false)
        rule.onNodeWithTag("np.art").performClick()
        settle(550)
        rule.onNodeWithTag("np.lyrics").performClick()
        settle(100)
        assertFalse(shell.sheet.shown)
        settle(1000)
        open()
    }

    @Test
    fun aTapWhileIdleOpensAndLongPressStaysTheBlockSheet() {
        start()
        settle((Tok.motion_idle + Tok.motion_idleFade).toLong() + 200)
        open()
        back()
        assertFalse(shell.sheet.shown)
        settle(600)
        rule.onNodeWithTag("np.lyrics").performTouchInput { longClick() }
        settle(600)
        assertTrue(shell.sheet.shown)
        assertFalse(shell.sheet.title == "lyrics")
        assertNull(shell.offset)
    }

    @Test
    fun halfTapsStepLiveAndKeepTheSheet() {
        start()
        open()
        tap("sheet.item.0")
        assertTrue(shell.offset!!.editing)
        half(true)
        half(true)
        half(false)
        assertEquals(100, shell.offset!!.shown)
        assertEquals(listOf<Int?>(null, 100, 200, 100), c.offsets)
        assertEquals(100, c.device.playback.state.lyricsOffsetMs)
        assertTrue(opened())
        assertEquals(listOf("+100 ms"), shell.sheet.itemValues)
        assertEquals(0, shell.sheet.accentItem)
    }

    @Test
    fun theTitleLineAndTheEdgeBandsKeep() {
        start()
        open()
        tap("sheet.item.0")
        half(true)
        rule.onNodeWithTag("sheet.panel").performTouchInput { click(topCenter.copy(y = 4f)) }
        settle(300)
        assertTrue(opened())
        assertFalse(shell.offset!!.editing)
        assertEquals(100, shell.offset!!.shown)
        back()
        assertFalse(shell.sheet.shown)
        assertEquals(listOf<Int?>(null, 100), c.offsets)
    }

    @Test
    fun backAndScrimRestoreAndTheSheetStays() {
        start(offset = 500)
        open()
        tap("sheet.item.0")
        half(true)
        half(true)
        back()
        assertTrue(opened())
        assertFalse(shell.offset!!.editing)
        assertEquals(500, shell.offset!!.shown)
        assertEquals(500, c.device.playback.state.lyricsOffsetMs)
        tap("sheet.item.0")
        half(false)
        rule.onNodeWithTag("sheet").performTouchInput { click(topCenter.copy(y = 40f)) }
        settle(400)
        assertTrue(opened())
        assertEquals(500, c.device.playback.state.lyricsOffsetMs)
        assertEquals(listOf<Int?>(null, 600, 700, 500, 400, 500), c.offsets)
        rule.onNodeWithTag("sheet").performTouchInput { click(topCenter.copy(y = 40f)) }
        settle(400)
        assertFalse(shell.sheet.shown)
        assertNull(shell.offset)
        assertTrue(exists("np.lyrics"))
    }

    @Test
    fun backWithoutAnEditClosesTheSheetThenTheView() {
        start()
        open()
        back()
        assertFalse(shell.sheet.shown)
        assertNull(shell.offset)
        assertNull(c.edit)
        assertTrue(exists("np.lyrics"))
        back()
        settle(1000)
        assertFalse(exists("np.lyrics"))
        assertTrue(exists("np.title"))
    }

    @Test
    fun lyricsFalseAndAbsentFlagsKeepTheSheetAndPlainCloses() {
        start()
        open()
        tap("sheet.item.0")
        state { it.copy(lyrics = false, lyricsPlain = false, lyricsCrc = null) }
        assertTrue(opened())
        assertTrue(shell.offset!!.editing)
        state { it.copy(lyrics = null, lyricsPlain = null, lyricsCrc = null) }
        assertTrue(opened())
        shell.offset!!.keep()
        state { it.copy(lyrics = false, lyricsPlain = false) }
        assertTrue(opened())
        state { it.copy(lyrics = false, lyricsPlain = true, lyricsCrc = 5) }
        assertFalse(shell.sheet.shown)
        assertNull(shell.offset)
    }

    @Test
    fun theCoarseStepMovesByOneSecond() {
        start()
        open()
        tap("sheet.item.0")
        shell.offset!!.coarse(1)
        settle(200)
        assertEquals(1000, c.device.playback.state.lyricsOffsetMs)
        shell.offset!!.coarse(-1)
        shell.offset!!.coarse(-1)
        settle(200)
        assertEquals(-1000, c.device.playback.state.lyricsOffsetMs)
    }

    private fun entry(text: String) {
        rule.onNodeWithTag("entry").performTextReplacement(text)
        rule.onNodeWithTag("entry").performImeAction()
        settle(400)
    }

    @Test
    fun theTypedEntryKeepsTheEditOwnerAndChecksTheText() {
        start(offset = 500)
        open()
        val e = shell.offset
        rule.onNodeWithTag("sheet.item.0").performTouchInput { longClick() }
        settle(600)
        assertEquals("offset", shell.sheet.title)
        assertNotNull(shell.sheet.entry)
        assertTrue(e === shell.offset)
        rule.onNodeWithText("500").assertExists()
        for (bad in listOf("70000", "+500", "5-00", "1234567")) {
            val shows = shell.toast.shows
            entry(bad)
            assertEquals(bad, shows + 1, shell.toast.shows)
            assertEquals("-60000 to +60000 ms", shell.toast.title)
            assertNotNull(shell.sheet.entry)
        }
        entry("500")
        assertTrue(opened())
        assertEquals(listOf<Int?>(null), c.offsets)
        rule.onNodeWithTag("sheet.item.0").performTouchInput { longClick() }
        settle(600)
        entry("-1200")
        assertTrue(opened())
        assertTrue(e === shell.offset)
        assertEquals(-1200, c.device.playback.state.lyricsOffsetMs)
        assertEquals(listOf("-1200 ms"), shell.sheet.itemValues)
        rule.onNodeWithTag("sheet.item.0").performTouchInput { longClick() }
        settle(600)
        back()
        assertTrue(opened())
        assertTrue(e === shell.offset)
    }

    @Test
    fun aRunningEditEndsRestoredBeforeTheEntry() {
        start(offset = 500)
        open()
        tap("sheet.item.0")
        half(true)
        assertEquals(600, shell.offset!!.stored)
        c.holdOffsets = true
        rule.onNodeWithTag("sheet.item.0").performTouchInput { longClick() }
        settle(600)
        assertFalse(shell.offset!!.editing)
        assertNotNull(shell.sheet.entry)
        rule.onNodeWithText("500").assertExists()
        c.holdOffsets = false
        c.replyOffset()
        settle(200)
        assertEquals(500, c.device.playback.state.lyricsOffsetMs)
        assertEquals(listOf<Int?>(null, 600, 500), c.offsets)
    }

    @Test
    fun anEntryWhileTheStartIsOwedStartsAtTheSentValue() {
        start(offset = 500)
        open()
        tap("sheet.item.0")
        c.holdOffsets = true
        half(true)
        rule.onNodeWithTag("sheet.panel").performTouchInput { click(topCenter.copy(y = 4f)) }
        settle(300)
        tap("sheet.item.0")
        half(true)
        assertEquals(700, shell.offset!!.shown)
        rule.onNodeWithTag("sheet.item.0").performTouchInput { longClick() }
        settle(600)
        assertFalse(shell.offset!!.editing)
        assertEquals(600, shell.offset!!.shown)
        assertNotNull(shell.sheet.entry)
        rule.onNodeWithText("600").assertExists()
        c.holdOffsets = false
        c.replyOffset()
        settle(200)
        assertEquals(600, c.device.playback.state.lyricsOffsetMs)
        assertEquals(600, shell.offset!!.shown)
        assertEquals(listOf<Int?>(null, 600), c.offsets)
    }

    @Test
    fun aTrackChangeRestoresToTheOldTrackAndCloses() {
        start(offset = 500)
        open()
        val old = c.device.playback.state.track!!
        tap("sheet.item.0")
        half(true)
        c.holdOffsets = true
        half(true)
        c.transport(Enums.TransportCmd.next)
        settle(300)
        assertFalse(shell.sheet.shown)
        assertNull(shell.offset)
        c.holdOffsets = false
        c.replyOffset()
        assertEquals(listOf<Int?>(null, 600, 700, 500), c.offsets)
        val a = c.sim.request(Msg.LYR_OFFSET, Node.decode("LYR_OFFSET", Req.lyrOffset(old)), sink) as Answer.Ok
        assertEquals(500, LyrOffsetReply.from(Node.decode("LYR_OFFSET.reply", a.payload)).ms)
    }

    @Test
    fun aSheetClosedElsewhereRestoresAndTheSessionEndingSendsNothing() {
        start()
        open()
        tap("sheet.item.0")
        half(true)
        shell.sheet.close(dismissed = false)
        settle(1000)
        assertNull(shell.offset)
        assertEquals(listOf<Int?>(null, 100, 0), c.offsets)
        open()
        tap("sheet.item.0")
        half(true)
        c.device = c.device.copy(hello = null)
        settle(200)
        assertFalse(shell.sheet.shown)
        assertNull(shell.offset)
        assertEquals(listOf<Int?>(null, 100, 0, null, 100), c.offsets)
    }

    @Test
    fun errors7And8CloseTheSheetAndOthersToast() {
        for ((code, words) in listOf(Err.staleRev to "song not in library", Err.noLibrary to "card not available")) {
            start()
            open()
            tap("sheet.item.0")
            c.offsetFault = code
            half(true)
            assertEquals(words, shell.toast.title)
            assertFalse(shell.sheet.shown)
            assertNull(shell.offset)
        }
        for ((code, words) in listOf(Err.unknown to "not supported", Err.refused to "refused", Err.busy to "error 3", -1 to "dap did not answer")) {
            start()
            open()
            tap("sheet.item.0")
            c.offsetFault = code
            half(true)
            assertEquals("offset", shell.toast.artist)
            assertEquals(words, shell.toast.title)
            assertTrue(opened())
        }
    }

    @Test
    fun anOkPutForTheTrackEndsTheEditWithoutARestore() {
        start(offset = 500)
        open()
        tap("sheet.item.0")
        half(true)
        put("[00:01.00]other\n")
        shell.offset!!.replaced(c.device.playback.state.track!!)
        refresh()
        settle(300)
        assertTrue(opened())
        assertFalse(shell.offset!!.editing)
        assertEquals(0, shell.offset!!.shown)
        back()
        assertEquals(listOf<Int?>(null, 600, null), c.offsets)
    }
}
