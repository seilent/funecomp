package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.artist.Sources
import net.seilent.funecomp.proto.Lib
import net.seilent.funecomp.proto.PageInfo
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.ui.App
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class LibRowFetchTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun aMissingRowIsAskedFromTheFirstMissingRowOfItsBlock() {
        val songs = Enums.View.songs
        val c = SimControl("ap80", Library((1..300).map { Library.track("Artist", "Album", "Song %03d".format(it), 1, 200_000) }))
        val l = c.sim.lib
        val rows = l.rows(songs, "")!!
        c.device = c.device.withLib(Lib(l.rev, songs, "", PageInfo(0, rows.size.toLong(), true), rows.take(5), l.snapRev))
        val shell = Shell(Nav(listOf(Page.Home, Page.Music)))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) {
                App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), c, shell, Sources.COMMONS, {})
            }
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        rule.onNodeWithTag("pivot.songs").performClick()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        val asked = c.sim.libAsks.filter { it.view == songs }
        assertTrue(asked.isNotEmpty())
        assertEquals(setOf(5L), asked.map { it.offset }.toSet())
    }
}
