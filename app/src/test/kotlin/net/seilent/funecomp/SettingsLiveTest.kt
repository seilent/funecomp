package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.FrameReader
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Schema
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.state.LiveMirror
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class SettingsLiveTest {
    @get:Rule
    val rule = createComposeRule()

    private val shell = Shell(Nav(listOf(Page.Home, Page.Settings)))
    private var t = 0L

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun start(c: SimControl) {
        rule.mainClock.autoAdvance = false
        rule.setContent { Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE), c, shell) } }
        settle(1000)
    }

    private fun pivot(p: String) {
        rule.onNodeWithTag("pivot.$p").performClick()
        settle(1000)
    }

    private fun scrollTo(tag: String) {
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("rows").performScrollToNode(hasTestTag(tag))
        rule.mainClock.autoAdvance = false
        settle(600)
    }

    private fun shows(tag: String, text: String) = rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag))).assertExists()

    private fun body(name: String): ByteArray {
        val hex = File("../third_party/funeos/vectors.txt").readLines().filter { it.startsWith("hex $name ") }.joinToString("") { it.substringAfter("hex $name ") }
        val b = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return FrameReader().feed(b, 0, b.size).single().payload
    }

    private fun vectorDevice(c: SimControl, live: Boolean): DeviceState {
        val page = Schema.from(Node.decode("SCHEMA", body("schema_page_live")))
        val rows = if (live) page.rows else page.rows.map { if (it.key == "dateTime") it.copy(words = listOf("2026-10-16 23:58")) else it }
        val d = DeviceState().withHello(c.device.hello!!).withSchema(page.copy(pivotIndex = page.pivotCount!! - 1, rows = rows))
        return if (live) d.reduce(Msg.LIVE, Node.decode("LIVE", body("live")), 0) else d
    }

    @Test
    fun liveRowsShowTheLiveMapAndEqAndAboutKeepTheirSources() {
        val c = SimControl("ap80")
        val n = c.sim.lib.tracks.size
        assertTrue(listOf("rescan", "folders", "sampleRate", "scale", "eq", "about", "dateTime").all { k -> c.device.schema.rows.single { it.key == k }.words.isEmpty() })
        val kept = c.device.liveRows.text.lines().filter { it.isNotEmpty() && !it.startsWith("eq=") && !it.startsWith("about=") }
        c.device = c.device.copy(liveRows = LiveMirror(c.device.liveRows.rev + 1, (kept + "eq=LIVE EQ" + "about=LIVE ABOUT").joinToString("\n", postfix = "\n")))
        start(c)
        scrollTo("set.sampleRate")
        shows("set.sampleRate", "AUTO")
        scrollTo("set.eq")
        shows("set.eq", "OFF")
        pivot("library")
        shows("set.folders", "1 ON")
        shows("set.rescan", "$n SONGS")
        pivot("display")
        scrollTo("set.scale")
        shows("set.scale", "1.5")
        pivot("power")
        pivot("system")
        scrollTo("set.about")
        shows("set.about", c.device.hello!!.version!!.uppercase())
    }

    private fun vectorShows(live: Boolean, text: String) {
        val c = SimControl("ap80")
        c.device = vectorDevice(c, live)
        start(c)
        shows("set.dateTime", text)
    }

    @Test
    fun theVectorPageShowsTheLiveTime() = vectorShows(true, "2026-10-16 23:59")

    @Test
    fun anOldPeerShowsTheWord() = vectorShows(false, "2026-10-16 23:58")

    @Test
    fun aRangeEditStaysOpenAcrossAMinute() {
        val c = SimControl("ap80", now = { t })
        c.draft(Enums.DraftKind.clock, unixS = 1767225600, zoneS = 0) { _, _ -> }
        c.tick()
        start(c)
        rule.onNodeWithTag("set.balance").performClick()
        settle(600)
        val was = c.device.settings.values["balance"]
        val rev = c.device.liveRows.rev
        t += 60_000
        c.tick()
        settle(600)
        assertEquals(rev + 1, c.device.liveRows.rev)
        rule.onNodeWithTag("set.balance").performTouchInput { click(Offset(width * 0.9f, height / 2f)) }
        settle(600)
        assertNotEquals(was, c.device.settings.values["balance"])
    }
}
