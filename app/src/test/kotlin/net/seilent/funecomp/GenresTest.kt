package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Tags
import net.seilent.funecomp.state.Heart
import net.seilent.funecomp.state.QueueMirror
import net.seilent.funecomp.ui.ADD_TO_QUEUE
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.Control
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.musicViews
import net.seilent.funecomp.ui.startRandom
import org.junit.After
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
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class GenresTest {
    @get:Rule
    val rule = createComposeRule()

    private class Play(val view: Int?, val parent: String?, val start: Long)

    private val plays = ArrayList<Play>()
    private val gets = ArrayList<Int>()
    private val logs = ArrayList<String>()
    private var answer: Pair<Long?, Int?>? = null
    private val shell = Shell(Nav(listOf(Page.Home, Page.Music)))

    @After
    fun tearDown() {
        startRandom = Random.Default
    }

    private fun start(minor: Int = 15, faults: List<String> = emptyList(), shuffle: Boolean = false): SimControl {
        val c = SimControl("ap80", minor = minor, faults = faults)
        c.transport(Enums.TransportCmd.shuffle, on = shuffle)
        val control = object : Control by c {
            override fun play(view: Int?, parent: String?, start: Long, shuffle: Boolean, tracks: List<Long>) {
                plays += Play(view, parent, start)
                c.play(view, parent, start, shuffle, tracks)
            }

            override fun libGet(view: Int, parent: String, offset: Long) {
                gets += view
                c.libGet(view, parent, offset)
            }

            override fun queueAdd(cmd: Int, view: Int?, parent: String?, tracks: List<Long>, done: (Long?, Int?) -> Unit) {
                val a = answer
                if (a == null) c.queueAdd(cmd, view, parent, tracks, done) else done(a.first, a.second)
            }

            override fun log(message: String) {
                logs += message
            }
        }
        startRandom = Random(5)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE), control, shell) }
        }
        settle()
        return c
    }

    private fun settle() {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
    }

    private fun pivot(name: String) {
        for (p in listOf("albums", "songs", "genres", "playlists", "folders")) {
            rule.onNodeWithTag("pivot.$p").performClick()
            settle()
            if (p == name) return
        }
    }

    private fun hold(tag: String) {
        rule.onNodeWithTag(tag).performTouchInput { longClick() }
        settle()
    }

    private fun lastAdd(c: SimControl) = Node.decode("QUEUE_OP", c.adds.last())

    @Test
    fun genresShowsOnlyWithBit24AndTheSavedViewIsKeptByView() {
        val c = SimControl("ap80")
        assertEquals(listOf(Enums.View.artists, Enums.View.albums, Enums.View.songs, Enums.View.genres, Enums.View.playlists, Enums.View.folders), musicViews(c.device))
        val old = SimControl("ap80", minor = 14)
        assertEquals(listOf(Enums.View.artists, Enums.View.albums, Enums.View.songs, Enums.View.playlists, Enums.View.folders), musicViews(old.device))
        val s = start()
        pivot("folders")
        gets.clear()
        s.device = s.device.withHello(s.device.hello!!.copy(features = Groups.all and Groups.mask("genres").inv()))
        settle()
        assertEquals(0, rule.onAllNodesWithTag("pivot.genres").fetchSemanticsNodes().size)
        assertEquals(Enums.View.folders, gets.last())
    }

    @Test
    fun aGenreTapPlaysItFromTheFirstSongWithShuffleOff() {
        start()
        pivot("genres")
        rule.onNodeWithTag("row.3").performClick()
        settle()
        val p = plays.single()
        assertEquals(Enums.View.genres, p.view)
        assertEquals("J-Pop", p.parent)
        assertEquals(0L, p.start)
    }

    @Test
    fun aGenreTapStartsBelowTheCountWithShuffleOn() {
        start(shuffle = true)
        pivot("genres")
        rule.onNodeWithTag("row.3").performClick()
        settle()
        assertEquals(Random(5).nextLong(10), plays.single().start)
    }

    @Test
    fun addToQueueSendsTheViewSourceWithNoRevAndToastsTheCount() {
        val c = start()
        pivot("genres")
        hold("row.3")
        assertEquals(listOf("play", ADD_TO_QUEUE), shell.sheet.items)
        shell.sheet.accept(1)
        settle()
        val b = lastAdd(c)
        assertEquals(Enums.QueueCmd.add, b.int(Tags.QueueOp.cmd))
        assertEquals(Enums.View.genres, b.int(Tags.QueueOp.view))
        assertEquals("J-Pop", b.str(Tags.QueueOp.parent))
        assertNull(b.long(Tags.QueueOp.rev))
        assertEquals("added 10 songs", shell.toast.artist)
        assertEquals("J-Pop", shell.toast.title)
    }

    @Test
    fun withoutBit25TheSheetsKeepTodaysItems() {
        start(faults = listOf("features=${Groups.all and Groups.mask("queueAdd").inv()}"))
        pivot("albums")
        hold("row.0")
        assertEquals(listOf("play", "shuffle"), shell.sheet.items)
        shell.sheet.close()
        settle()
        pivot("genres")
        hold("row.3")
        assertEquals(listOf("play"), shell.sheet.items)
    }

    @Test
    fun groupSheetsGainPlayNextAndAddToQueueInTheDapOrder() {
        start()
        hold("row.0")
        assertEquals(listOf("play", "play next", ADD_TO_QUEUE, "shuffle", "pick image"), shell.sheet.items)
        shell.sheet.close()
        settle()
        pivot("songs")
        hold("row.0")
        assertEquals(listOf("play", "play next", ADD_TO_QUEUE), shell.sheet.items)
    }

    @Test
    fun aReplyRevResetsTheQueueMirrorAndTheNextQueueOpSendsIt() {
        val c = start()
        c.device = c.device.copy(queue = QueueMirror(c.device.queue.rev ?: c.device.playback.state.queueRev, 3))
        val before = c.device.playback.state.queueRev!!
        pivot("albums")
        hold("row.0")
        shell.sheet.accept(1)
        settle()
        val rev = c.device.queue.rev!!
        assertTrue(rev > before)
        assertEquals(0L, c.device.queue.total)
        c.queueOp(Enums.QueueCmd.playAt, slot = 0)
        assertFalse(c.failed.contains(Err.staleRev))
    }

    @Test
    fun aCardRefusalToastsCardNotAvailableAndBusyAndStaleOnlyLog() {
        start()
        pivot("genres")
        answer = null to Err.noCard
        hold("row.3")
        shell.sheet.accept(1)
        settle()
        assertEquals(Heart.CARD, shell.toast.artist)
        val shows = shell.toast.shows
        for ((code, line) in listOf(Err.busy to "queue add error 3", Err.staleRev to "queue add error 7")) {
            answer = null to code
            hold("row.3")
            shell.sheet.accept(1)
            settle()
            assertEquals(shows, shell.toast.shows)
            assertTrue(logs.contains(line))
        }
    }
}
