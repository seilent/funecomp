package net.seilent.funecomp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import net.seilent.funecomp.proto.gen.Tags
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class LibDeleteFlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val lib = Library.synthetic()
    private val adan = lib.tracks.filter { it.album == "Windswept Adan" }
    private val adanKey = java.lang.Long.toUnsignedString(adan[0].id)
    private val adanPage = Page.Album(adanKey, "Windswept Adan", "Ichiko Aoba")
    private val aoba = Page.Artist("Ichiko Aoba")
    private var t = 1_000L
    private var control by mutableStateOf<SimControl?>(null)
    private var shell by mutableStateOf(Shell())
    private var composed = false
    private val c: SimControl get() = control!!

    @Before
    fun setUp() = BindTokens.init(RuntimeEnvironment.getApplication(), JvmSeal(), Executor { it.run() })

    @After
    fun tearDown() = BindTokens.reset()

    private fun settle(ms: Long = 1000) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun start(vararg faults: String, pages: List<Page> = listOf(Page.Home, Page.Music), minor: Int = Proto.minor, seed: Boolean = true, library: Library = lib): SimControl {
        BindTokens.get(SimControl.DELETE_KEY)?.let { BindTokens.drop(SimControl.DELETE_KEY, it) }
        val s = SimControl("ap80", library, now = { t }, minor = minor, faults = listOf("bind-window") + faults)
        if (seed) s.sim.bindToken?.let { BindTokens.put(SimControl.DELETE_KEY, it) }
        shell = Shell(Nav(pages))
        control = s
        if (!composed) {
            composed = true
            rule.mainClock.autoAdvance = false
            rule.setContent {
                val sc = control ?: return@setContent
                key(sc, shell) { Box(Modifier.requiredSize(411.dp, 960.dp)) { App(sc.device, LinkView(Phase.LIVE), sc, shell) } }
            }
        }
        settle()
        return s
    }

    private fun pivot(name: String) = rule.pivotTo("music", name) { settle() }

    private fun hold(tag: String) {
        rule.onNodeWithTag(tag).performTouchInput { longClick() }
        settle()
    }

    private fun tap(tag: String) {
        rule.onNodeWithTag(tag).performClick()
        settle()
    }

    private fun pick(text: String) {
        val i = shell.sheet.items.indexOf(text)
        check(i >= 0) { "$text not in ${shell.sheet.items}" }
        rule.onNodeWithTag("sheet.item.$i").performClick()
        rule.waitForIdle()
    }

    private fun close() {
        rule.runOnUiThread { shell.sheet.close() }
        settle()
    }

    private fun confirm(row: String, what: String = "song") {
        hold(row)
        pick("delete")
        settle()
        pick("delete $what")
        settle()
    }

    private fun items(row: String): List<String> {
        hold(row)
        return shell.sheet.items.also { close() }
    }

    private fun sent() = c.deleteSent.map { it.first }

    private fun last(name: String): Node = c.deleteSent.last { it.first == name }.second

    private fun toast() = shell.toast.artist to shell.toast.title

    private fun snapMove() {
        rule.runOnUiThread {
            c.sim.rescan(null)
            c.push(Msg.STATE, c.sim.state())
        }
        settle()
    }

    @Test
    fun theItemIsOnSongAndAlbumRowsOnly() {
        start()
        pivot("albums")
        assertEquals("delete", items("row.0").last())
        pivot("artists")
        assertFalse("delete" in items("row.0"))
        pivot("playlists")
        assertFalse("delete" in items("row.favorites"))
        pivot("songs")
        assertEquals("delete", items("row.0").last())
        pivot("genres")
        assertFalse("delete" in items("row.0"))
        pivot("folders")
        assertFalse("delete" in items("row.0"))
        tap("row.0")
        assertFalse("delete" in items("row.0"))
        tap("row.0")
        assertEquals("delete", items("row.0").last())
        start(pages = listOf(Page.Home, Page.Music, aoba))
        assertEquals("delete", items("row.0").last())
        start(pages = listOf(Page.Home, Page.Music, aoba, adanPage))
        assertEquals("delete", items("row.1").last())
        start(pages = listOf(Page.Home, Page.Queue))
        assertFalse("delete" in items("queue.0"))
    }

    @Test
    fun noDeleteAndAMinor17DapLeaveTheItemOut() {
        start("no-delete=${java.lang.Long.toUnsignedString(lib.tracks[0].id)}")
        pivot("albums")
        assertFalse("delete" in items("row.0"))
        assertEquals("delete", items("row.1").last())
        start(minor = 17)
        pivot("albums")
        assertFalse("delete" in items("row.0"))
        pivot("songs")
        assertFalse("delete" in items("row.0"))
    }

    @Test
    fun theConfirmsAreTheDevicesWordsWithKeepFocused() {
        start(pages = listOf(Page.Home, Page.Music, aoba, adanPage))
        hold("row.0")
        assertFalse(shell.sheet.oneLine)
        pick("delete")
        settle()
        assertEquals("delete song?", shell.sheet.title)
        assertTrue(shell.sheet.oneLine)
        assertEquals(listOf("Prologue"), shell.sheet.info)
        assertEquals(listOf(true), shell.sheet.infoMixed)
        assertEquals(listOf("delete song", "keep song"), shell.sheet.items)
        assertEquals(1, shell.sheet.focus)
        close()
        start(pages = listOf(Page.Home, Page.Music, aoba))
        hold("row.0")
        pick("delete")
        settle()
        assertEquals("delete album?", shell.sheet.title)
        assertTrue(shell.sheet.oneLine)
        assertEquals(listOf("Windswept Adan", "4 songs"), shell.sheet.info)
        assertEquals(listOf(true, false), shell.sheet.infoMixed)
        assertEquals(listOf("delete album", "keep album"), shell.sheet.items)
        assertEquals(1, shell.sheet.focus)
        pick("keep album")
        settle()
        assertTrue(sent().isEmpty())
    }

    @Test
    fun aLongTitleConfirmDrawsItsTitleOnOneLine() {
        val title = "Sometimes It Snows in April, the Long Version From the Parade Sessions"
        val long = Library(lib.tracks + Library.track("Prince", "Sign o' the Times", title, 1, 400_000))
        val key = java.lang.Long.toUnsignedString(long.tracks.last().id)
        fun infoHeight(): Float {
            hold("row.0")
            pick("delete")
            settle()
            return rule.onNodeWithTag("sheet.info").fetchSemanticsNode().boundsInRoot.height
        }
        start(pages = listOf(Page.Home, Page.Music, adanPage))
        val short = infoHeight()
        close()
        start(pages = listOf(Page.Home, Page.Music, Page.Album(key, "Sign o' the Times", "Prince")), library = long)
        val longHeight = infoHeight()
        assertEquals(listOf(title), shell.sheet.info)
        assertEquals(short, longHeight, 0.5f)
    }

    @Test
    fun aPressInsideTheDialogRiseDoesNothing() {
        start(pages = listOf(Page.Home, Page.Music, adanPage))
        hold("row.0")
        pick("delete")
        rule.mainClock.advanceTimeBy(100)
        rule.waitForIdle()
        pick("delete song")
        settle(0)
        assertTrue(shell.sheet.shown)
        assertTrue(sent().isEmpty())
        rule.mainClock.advanceTimeBy(250)
        rule.waitForIdle()
        pick("delete song")
        settle()
        assertFalse(shell.sheet.shown)
        assertEquals(listOf("LIB_DELETE"), sent())
    }

    @Test
    fun aDownCardToastsAndSendsNothing() {
        start(pages = listOf(Page.Home, Page.Music, adanPage))
        rule.runOnUiThread { c.push(Msg.STATE, c.device.playback.state.copy(card = Enums.Card.busy).encode()) }
        settle()
        confirm("row.0")
        assertEquals("card not available" to "", toast())
        assertTrue(sent().isEmpty())
    }

    @Test
    fun aSeededTokenSendsTheSongAndAlbumTags() {
        start(pages = listOf(Page.Home, Page.Music, aoba, adanPage))
        confirm("row.0")
        val song = last("LIB_DELETE")
        assertEquals(adan[0].id, song.long(Tags.LibDelete.track))
        assertNull(song.long(Tags.LibDelete.album))
        assertNull(song.long(Tags.LibDelete.count))
        assertEquals(c.device.lib.snap, song.long(Tags.LibDelete.snapRev))
        assertArrayEquals(c.sim.bindToken, song.bytes(Tags.LibDelete.bindToken))
        assertEquals(c.device.hello!!.bootId, song.long(Tags.LibDelete.bootId))
        assertEquals(listOf("LIB_DELETE"), c.parkedNames)
        c.answer()
        settle()
        start(pages = listOf(Page.Home, Page.Music, aoba))
        confirm("row.0", "album")
        val album = last("LIB_DELETE")
        assertNull(album.long(Tags.LibDelete.track))
        assertEquals(adan[0].id, album.long(Tags.LibDelete.album))
        assertEquals(4L, album.long(Tags.LibDelete.count))
        assertArrayEquals(c.sim.bindToken, album.bytes(Tags.LibDelete.bindToken))
        assertEquals(Proto.libDeleteMs + Proto.requestTimeoutMs.toLong(), c.deleteTimeouts.last())
    }

    @Test
    fun theDeleteAnswersToastInTheDevicesWords() {
        val cases = listOf(
            listOf("del-failed=1") to ("1 song not deleted" to "Windswept Adan"),
            listOf("del-failed=4") to ("album not deleted" to "Windswept Adan"),
            listOf("del-cue=${java.lang.Long.toUnsignedString(adan[1].id)}") to ("album not deleted" to "part of a cue image"),
            listOf("del-readonly") to ("album not deleted" to "card is read only"),
            listOf("del-changed") to ("album not deleted" to "Windswept Adan"),
            listOf("del-path") to ("album not deleted" to "Windswept Adan"),
        )
        for ((faults, line) in cases) {
            start(*faults.toTypedArray(), pages = listOf(Page.Home, Page.Music, aoba))
            confirm("row.0", "album")
            if (c.parkedNames.isNotEmpty()) c.answer()
            settle()
            assertEquals(faults.toString(), line, toast())
        }
        start("del-failed=1", pages = listOf(Page.Home, Page.Music, adanPage))
        confirm("row.0")
        c.answer()
        settle()
        assertEquals("song not deleted" to "Prologue", toast())
    }

    @Test
    fun withNoTokenTheDapIsAskedAndTheAllowSendsOneDelete() {
        start(pages = listOf(Page.Home, Page.Music, adanPage), seed = false)
        confirm("row.0")
        assertEquals("confirm on the dap" to "Prologue", toast())
        assertEquals(listOf("BIND_ASK"), sent())
        assertEquals(Proto.svcReplyMs + Proto.requestTimeoutMs.toLong(), c.deleteTimeouts.last())
        c.answer()
        settle()
        assertArrayEquals(c.sim.bindToken, BindTokens.get(SimControl.DELETE_KEY))
        assertEquals(listOf("BIND_ASK", "LIB_DELETE"), sent())
        c.answer()
        settle()
        assertEquals(listOf("BIND_ASK", "LIB_DELETE"), sent())
    }

    @Test
    fun aRefusedTokenIsDroppedAndOneAskSendsTheDelete() {
        start(pages = listOf(Page.Home, Page.Music, adanPage), seed = false)
        val wrong = ByteArray(16) { 7 }
        BindTokens.put(SimControl.DELETE_KEY, wrong)
        confirm("row.0")
        assertEquals(listOf("LIB_DELETE", "BIND_ASK"), sent())
        c.answer()
        settle()
        assertEquals(listOf("LIB_DELETE", "BIND_ASK", "LIB_DELETE"), sent())
        assertArrayEquals(c.sim.bindToken, last("LIB_DELETE").bytes(Tags.LibDelete.bindToken))
    }

    @Test
    fun aPageChangeThenAllowSendsTheDeleteAndTheExitsReadTheTop() {
        start(pages = listOf(Page.Home, Page.Music, aoba), seed = false)
        confirm("row.0", "album")
        assertEquals(listOf("BIND_ASK"), sent())
        rule.runOnUiThread { shell.nav.push(adanPage) }
        settle()
        c.answer()
        settle()
        assertEquals(listOf("BIND_ASK", "LIB_DELETE"), sent())
        assertEquals(adanKey, java.lang.Long.toUnsignedString(last("LIB_DELETE").long(Tags.LibDelete.album)!!))
        c.answer()
        settle()
        t += 2_000
        rule.runOnUiThread { c.tick() }
        settle()
        assertEquals(Page.Music, shell.nav.top)
        assertNull(c.deleteOwed)
        assertFalse(c.deleteWaiting)
    }

    @Test
    fun aPageChangeThenAllowToastsTheOutcomeWithTheItemsName() {
        start("del-failed=1", pages = listOf(Page.Home, Page.Music, aoba, adanPage), seed = false)
        confirm("row.0")
        rule.runOnUiThread { shell.nav.pop() }
        settle()
        c.answer()
        settle()
        c.answer()
        settle()
        assertEquals(listOf("BIND_ASK", "LIB_DELETE"), sent())
        assertEquals("song not deleted" to "Prologue", toast())
    }

    @Test
    fun aStopThenAllowSendsTheDeleteAndShowsItsOutcomeAtTheStart() {
        start("del-failed=1", pages = listOf(Page.Home, Page.Music, adanPage), seed = false)
        confirm("row.0")
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        c.answer()
        c.answer()
        assertEquals(listOf("BIND_ASK", "LIB_DELETE"), sent())
        assertEquals("confirm on the dap" to "Prologue", toast())
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        settle()
        assertEquals("song not deleted" to "Prologue", toast())
        assertFalse(c.deleteWaiting)
    }

    @Test
    fun aDeclineAfterAPageChangeStillToasts() {
        start("bind=keep", pages = listOf(Page.Home, Page.Music, aoba, adanPage), seed = false)
        confirm("row.0")
        rule.runOnUiThread { shell.nav.pop() }
        settle()
        c.answer()
        settle()
        assertEquals("song not deleted" to "declined on the dap", toast())
        assertEquals(listOf("BIND_ASK"), sent())
    }

    @Test
    fun theItemStaysOutWhileTheWaitRunsAcrossAPageChange() {
        start(pages = listOf(Page.Home, Page.Music, aoba, adanPage), seed = false)
        confirm("row.0")
        assertFalse("delete" in items("row.1"))
        rule.runOnUiThread { shell.nav.pop() }
        settle()
        rule.runOnUiThread { shell.nav.push(adanPage) }
        settle()
        assertFalse("delete" in items("row.1"))
        c.answer()
        settle()
        assertEquals(listOf("BIND_ASK", "LIB_DELETE"), sent())
        assertEquals(adan[0].id, last("LIB_DELETE").long(Tags.LibDelete.track))
        c.answer()
        settle()
        assertEquals("delete", items("row.1").last())
    }

    @Test
    fun aCaptureGoneStaleDuringTheWaitSendsNoDelete() {
        start(pages = listOf(Page.Home, Page.Music, adanPage), seed = false)
        confirm("row.0")
        snapMove()
        c.answer()
        settle()
        assertEquals(listOf("BIND_ASK"), sent())
        assertEquals("song not deleted" to "Prologue", toast())
    }

    @Test
    fun theBindAskAnswersShowTheSignedLinesAndSendNoDelete() {
        for ((fault, line) in listOf("bind=screen" to "wake and unlock the dap", "bind=keep" to "declined on the dap", "bind=busy" to "dap busy, send again later")) {
            start(fault, pages = listOf(Page.Home, Page.Music, adanPage), seed = false)
            confirm("row.0")
            if (c.parkedNames.isNotEmpty()) c.answer()
            settle()
            assertEquals(fault, "song not deleted" to line, toast())
            assertEquals(fault, listOf("BIND_ASK"), sent())
        }
        start(pages = listOf(Page.Home, Page.Music, adanPage), seed = false)
        confirm("row.0")
        rule.runOnUiThread { c.unlink() }
        settle()
        assertEquals("song not deleted" to "no answer from the dap", toast())
        assertEquals(listOf("BIND_ASK"), sent())
    }

    @Test
    fun aSnapMoveAHelloAndAnUnlinkCloseTheSheets() {
        start(pages = listOf(Page.Home, Page.Music, adanPage))
        hold("row.0")
        snapMove()
        assertFalse(shell.sheet.shown)
        hold("row.0")
        pick("delete")
        settle()
        rule.runOnUiThread { c.rehello() }
        settle()
        assertFalse(shell.sheet.shown)
        hold("row.0")
        pick("delete")
        settle()
        rule.runOnUiThread { c.unlink() }
        settle()
        assertFalse(shell.sheet.shown)
    }

    @Test
    fun theItemIsGoneWhileADeleteWaitsAndNothingIsSentAgainAfterAClose() {
        start(pages = listOf(Page.Home, Page.Music, adanPage))
        confirm("row.0")
        assertFalse("delete" in items("row.1"))
        rule.runOnUiThread { c.unlink() }
        settle()
        assertEquals(listOf("LIB_DELETE"), sent())
        assertFalse(c.deleteWaiting)
    }

    @Test
    fun aOneAlbumArtistPageLeavesAtTheRescanNotAtTheReply() {
        start(pages = listOf(Page.Home, Page.Music, aoba))
        confirm("row.0", "album")
        c.answer()
        settle()
        assertEquals(aoba, shell.nav.top)
        rule.runOnUiThread { shell.nav.push(adanPage) }
        settle()
        t += 2_000
        rule.runOnUiThread { c.tick() }
        settle()
        assertEquals(Page.Music, shell.nav.top)
        assertEquals(listOf("LIB_DELETE", "LIB_GET"), sent())
        assertNull(c.deleteOwed)
    }

    @Test
    fun anArtistPageLeftBeforeItsAnswerAndAPageUnderNowPlayingStay() {
        start(pages = listOf(Page.Home, Page.Music, aoba))
        c.holdLists = true
        confirm("row.0", "album")
        c.answer()
        settle()
        t += 2_000
        rule.runOnUiThread { c.tick() }
        settle()
        assertEquals(listOf("LIB_GET"), c.parkedNames)
        rule.runOnUiThread { shell.nav.pop() }
        settle()
        c.answer()
        settle()
        assertEquals(Page.Music, shell.nav.top)
        assertNull(c.deleteOwed)
        start(pages = listOf(Page.Home, Page.Music, aoba))
        confirm("row.0", "album")
        c.answer()
        settle()
        rule.runOnUiThread {
            shell.nav.push(adanPage)
            shell.nav.push(Page.NowPlaying)
        }
        settle()
        t += 2_000
        rule.runOnUiThread { c.tick() }
        settle()
        assertEquals(listOf(Page.Home, Page.Music, aoba, adanPage, Page.NowPlaying), shell.nav.stack.toList())
        assertNull(c.deleteOwed)
    }
}
