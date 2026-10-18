package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.state.LibKey
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.Control
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.sourceStart
import net.seilent.funecomp.ui.startRandom
import org.junit.After
import org.junit.Assert.assertEquals
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
class SourceStartTest {
    @get:Rule
    val rule = createComposeRule()

    private val seed = 11
    private val starts = ArrayList<Long>()
    private val logs = ArrayList<String>()

    @After
    fun tearDown() {
        startRandom = Random.Default
    }

    private fun start(pages: List<Page>, shuffle: Boolean): SimControl {
        val c = SimControl("ap80")
        c.transport(Enums.TransportCmd.shuffle, on = shuffle)
        val control = object : Control by c {
            override fun play(view: Int?, parent: String?, start: Long, shuffle: Boolean, tracks: List<Long>) {
                starts += start
                c.play(view, parent, start, shuffle, tracks)
            }

            override fun log(message: String) {
                logs += message
            }
        }
        startRandom = Random(seed)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE), control, Shell(Nav(pages))) }
        }
        settle()
        return c
    }

    private fun settle() {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
    }

    @Test
    fun aShuffledSourceStartIsUniformOverTheSource() {
        val r = Random(3)
        val seen = IntArray(7)
        repeat(7000) { seen[sourceStart(7, true, r)!!.toInt()]++ }
        assertTrue(seen.joinToString(), seen.all { it in 850..1150 })
        assertEquals(0L, sourceStart(7, false, r))
        assertEquals(0L, sourceStart(0, true, r))
        assertNull(sourceStart(null, true, r))
        assertEquals(0L, sourceStart(null, false, r))
    }

    @Test
    fun theArtistPlayRingStartsAtARandomTrackWithShuffleOn() {
        start(listOf(Page.Home, Page.Music, Page.Artist("Utada Hikaru", 9)), shuffle = true)
        rule.onNodeWithTag("artist.play").performClick()
        settle()
        assertEquals(listOf(Random(seed).nextLong(9)), starts)
        assertTrue(starts.single() != 0L)
    }

    @Test
    fun withoutACountTheArtistsRowGivesTheCount() {
        val c = start(listOf(Page.Home, Page.Music, Page.Artist("Utada Hikaru")), shuffle = true)
        c.libGet(Enums.View.artists, "", 0)
        settle()
        val count = c.device.lib.pages.getValue(LibKey(Enums.View.artists, "")).rows.values.single { it.name == "Utada Hikaru" }.count!!
        assertTrue(count > 1)
        rule.onNodeWithTag("artist.play").performClick()
        settle()
        assertEquals(listOf(Random(seed).nextLong(count)), starts)
        assertEquals(emptyList<String>(), logs)
    }

    @Test
    fun anArtistMissingFromTheArtistsRowsStartsAtTheFirstTrack() {
        start(listOf(Page.Home, Page.Music, Page.Artist("Nobody")), shuffle = true)
        rule.onNodeWithTag("artist.play").performClick()
        settle()
        assertEquals(listOf(0L), starts)
        assertEquals(listOf("start 0 (no count)"), logs)
    }

    @Test
    fun withShuffleOffAWholeSourceStartsAtTheFirstTrack() {
        start(listOf(Page.Home, Page.Music, Page.Artist("Utada Hikaru", 9)), shuffle = false)
        rule.onNodeWithTag("artist.play").performClick()
        settle()
        assertEquals(listOf(0L), starts)
    }

    @Test
    fun aSongTapKeepsItsIndexWithShuffleOn() {
        val key = java.lang.Long.toUnsignedString(net.seilent.funecomp.sim.Library.synthetic().tracks.first().id)
        start(listOf(Page.Home, Page.Music, Page.Album(key, "First Love", "Utada Hikaru")), shuffle = true)
        rule.onNodeWithTag("row.2").performClick()
        settle()
        assertEquals(listOf(2L), starts)
    }
}
