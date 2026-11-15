package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.HelloReply
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.sim.Playlist
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.Control
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import org.junit.Assert.assertEquals
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
class FavoritesRemoveTest {
    @get:Rule
    val rule = createComposeRule()

    private val lib = Library((0 until 60).map { Library.track("Artist $it", "Album ${it / 10}", "Song $it", it % 10 + 1, 180_000) })
    private val ids = lib.tracks.map { it.id }
    private val favorites = Page.List(Enums.View.favorites, "", "favorites")
    private val roadKey = "/Playlists/road.m3u8"
    private lateinit var c: SimControl
    private lateinit var shell: Shell
    private var hold = false
    private val sent = ArrayList<Long?>()
    private val replies = ArrayList<(Int?, String?) -> Unit>()

    private fun start(favs: Int = 3, top: Page = favorites, faults: List<String> = emptyList()) {
        val l = Library(lib.tracks, playlists = listOf(Playlist("road", roadKey, ids.take(5))))
        c = SimControl("v1", l, faults = faults)
        for (id in ids.take(favs)) l.favorites += id
        val control = object : Control by c {
            override fun action(cmd: Int, track: Long?, done: (Int?, String?) -> Unit) {
                assertEquals(Enums.ActionCmd.favorite, cmd)
                sent += track
                if (hold) replies += done else c.action(cmd, track, done)
            }
        }
        shell = Shell(Nav(listOf(Page.Home, Page.Playlists, top)))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, "V1", everLive = true), control, shell) }
        }
        settle()
    }

    private fun settle(ms: Long = 1500) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun title(i: Int): String {
        val out = ArrayList<TextLayoutResult>()
        rule.onNode(hasTestTag("primary") and hasAnyAncestor(hasTestTag("row.$i")), useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(out)
        return out.single().layoutInput.text.text
    }

    private fun song(id: Long) = lib.byId.getValue(id).title

    private fun remove(i: Int) {
        rule.onNodeWithTag("row.$i").performTouchInput { longClick() }
        settle(600)
        assertEquals(listOf("remove"), shell.sheet.items)
        rule.runOnIdle { shell.sheet.accept(0) }
        settle()
    }

    private val row = SemanticsMatcher("a list row") { it.config.getOrNull(SemanticsProperties.TestTag)?.matches(Regex("row\\.\\d+")) == true }

    private fun rows() = rule.onAllNodes(row, useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun removeSendsTheFavoriteActionAndTheRowLeavesAtTheOk() {
        hold = true
        start()
        assertEquals(listOf(song(ids[2]), song(ids[1]), song(ids[0])), (0..2).map { title(it) })
        rule.onNodeWithTag("row.1").performTouchInput { longClick() }
        settle(600)
        assertEquals(song(ids[1]), shell.sheet.title)
        rule.runOnIdle { shell.sheet.accept(0) }
        settle()
        assertEquals(listOf<Long?>(ids[1]), sent)
        assertEquals(3, rows())
        rule.runOnIdle { replies.single()(null, null) }
        settle()
        assertEquals(listOf(song(ids[2]), song(ids[0])), (0..1).map { title(it) })
        assertEquals(2, rows())
        assertEquals("2 SONGS", layoutText("count"))
        assertEquals(0, shell.toast.shows)
        rule.runOnIdle { c.action(Enums.ActionCmd.favorite, ids[1]) }
        settle()
        assertEquals(listOf(song(ids[2]), song(ids[0])), (0..1).map { title(it) })
        assertEquals(2, rows())
    }

    private fun layoutText(tag: String): String {
        val out = ArrayList<TextLayoutResult>()
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(out)
        return out.single().layoutInput.text.text
    }

    @Test
    fun aRemoveAtRow30Of60KeepsTheFirstVisibleRowAndNeverDrawsNoRows() {
        start(60)
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("rows").performScrollToIndex(30)
        rule.mainClock.autoAdvance = false
        settle()
        val y = rule.onNodeWithTag("row.30").fetchSemanticsNode().positionInRoot.y
        val next = title(31)
        rule.onNodeWithTag("row.30").performTouchInput { longClick() }
        settle(600)
        rule.runOnIdle { shell.sheet.accept(0) }
        repeat(30) {
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            assertTrue("frame $it", rows() > 0)
        }
        settle()
        assertEquals(listOf<Long?>(ids[29]), sent)
        assertEquals(next, title(30))
        assertTrue(abs(y - rule.onNodeWithTag("row.30").fetchSemanticsNode().positionInRoot.y) < 1f)
        assertEquals("59 SONGS", layoutText("count"))
    }

    @Test
    fun aLostReplyKeepsTheMarkUntilTheNextMove() {
        hold = true
        start()
        remove(1)
        rule.runOnIdle { replies.removeAt(0)(-1, null) }
        settle()
        assertEquals(3, rows())
        assertEquals(0, shell.toast.shows)
        remove(1)
        assertEquals(listOf<Long?>(ids[1]), sent)
        rule.runOnIdle { c.action(Enums.ActionCmd.favorite, ids[5]) }
        settle()
        assertEquals(song(ids[1]), title(2))
        remove(2)
        assertEquals(listOf<Long?>(ids[1], ids[1]), sent)
    }

    @Test
    fun aRemoveWaitsForTheLinkAndALostReplyEndsAtANewHello() {
        hold = true
        start()
        val rev = c.device.lib.rev
        remove(1)
        rule.runOnIdle { replies.removeAt(0)(-1, null) }
        settle()
        rule.runOnIdle { c.device = c.device.unlinked() }
        settle()
        remove(1)
        assertEquals(listOf<Long?>(ids[1]), sent)
        rule.runOnIdle { c.device = c.device.withHello(HelloReply.from(Node.decode("HELLO.reply", c.sim.helloReply().encode())), LINK_FEATURES) }
        settle()
        assertEquals(rev, c.device.lib.rev)
        assertEquals(song(ids[1]), title(1))
        remove(1)
        assertEquals(listOf<Long?>(ids[1], ids[1]), sent)
    }

    @Test
    fun anUnrelatedMoveBeforeTheOkKeepsTheRowOut() {
        hold = true
        start()
        remove(1)
        rule.runOnIdle { c.action(Enums.ActionCmd.favorite, ids[5]) }
        settle()
        assertEquals(4, rows())
        rule.runOnIdle { replies.single()(null, null) }
        rule.waitForIdle()
        repeat(10) {
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            assertEquals("frame $it", 3, rows())
        }
        assertEquals(listOf(song(ids[5]), song(ids[2]), song(ids[0])), (0..2).map { title(it) })
    }

    @Test
    fun twoRemovesBeforeTheReplySendOneAction() {
        hold = true
        start()
        remove(1)
        remove(1)
        assertEquals(listOf<Long?>(ids[1]), sent)
    }

    @Test
    fun aFailureThenARetrySendsAgain() {
        hold = true
        start()
        remove(1)
        rule.runOnIdle { replies.removeAt(0)(Err.staleRev, "stale rev") }
        settle()
        assertEquals(3, rows())
        assertEquals(0, shell.toast.shows)
        remove(1)
        assertEquals(listOf<Long?>(ids[1], ids[1]), sent)
    }

    @Test
    fun aSheetOpenedBeforeTheDapRemovedTheSongClosesAndSendsNothing() {
        start()
        rule.onNodeWithTag("row.1").performTouchInput { longClick() }
        settle(600)
        rule.runOnIdle { c.action(Enums.ActionCmd.favorite, ids[1]) }
        settle()
        rule.runOnIdle { shell.sheet.accept(0) }
        settle()
        assertEquals(emptyList<Long?>(), sent)
        assertEquals(false, shell.sheet.shown)
        assertEquals(2, rows())
    }

    @Test
    fun noCardToastsCardNotAvailable() {
        start(faults = listOf("fail=ACTION:14:1"))
        remove(0)
        assertEquals("card not available", shell.toast.artist)
        assertEquals(3, rows())
    }

    @Test
    fun aBusyFavoritesNotReadableToastsIt() {
        start(faults = listOf("favs-busy=1:favorites not readable"))
        remove(0)
        assertEquals("favorites not readable", shell.toast.artist)
        assertEquals(3, rows())
    }

    @Test
    fun aPlaylistRowLongPressOpensNothing() {
        start(top = Page.List(Enums.View.playlists, roadKey, "road"))
        rule.onNodeWithTag("row.1").performTouchInput { longClick() }
        settle(600)
        assertEquals(false, shell.sheet.shown)
    }
}
