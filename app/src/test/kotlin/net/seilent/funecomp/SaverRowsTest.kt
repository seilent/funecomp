package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
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
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class SaverRowsTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var shell: Shell
    private lateinit var c: SimControl

    private fun start() {
        shell = Shell(Nav(listOf(Page.Home, Page.Settings)))
        c = SimControl("v1")
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE), c, shell) }
        }
        settle(1000)
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

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
        rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag))).assertExists()
    }

    private fun screens() = c.device.schema.rows.filter { it.pivot == "screens" }.map { it.key }

    @Test
    fun saverOffHidesTheDelayLyricsAndDriftRowsAndOnBringsThemBack() {
        start()
        rule.walkPivots(c.device.schema.rows, "screens") { settle(1000) }
        shows("set.saverMode", "ON")
        shows("set.saverDelay", "30 S")
        shows("set.saverLyrics", "ON")
        tap("set.saverMode")
        assertEquals("saver", shell.sheet.title)
        assertEquals(listOf("OFF", "ON", "CHARGE"), shell.sheet.items)
        assertEquals(1, shell.sheet.focus)
        pick(0)
        assertFalse(shell.sheet.shown)
        c.tick()
        settle(1000)
        assertEquals("off", c.device.settings.values["saverMode"])
        assertEquals("0", c.device.settings.values["saverSeconds"])
        shows("set.saverMode", "OFF")
        assertTrue(screens().none { it == "saverDelay" || it == "saverLyrics" || it == "saverDrift" })
        rule.onNodeWithTag("set.saverDelay").assertDoesNotExist()
        rule.onNodeWithTag("set.saverLyrics").assertDoesNotExist()
        rule.onNodeWithTag("set.saverDrift").assertDoesNotExist()
        tap("set.saverMode")
        assertEquals(0, shell.sheet.focus)
        pick(1)
        c.tick()
        settle(1000)
        assertEquals("on", c.device.settings.values["saverMode"])
        assertEquals("30", c.device.settings.values["saverSeconds"])
        val back = screens()
        assertEquals(back.indexOf("saverMode") + 1, back.indexOf("saverDelay"))
        assertEquals(back.indexOf("saverDelay") + 1, back.indexOf("saverLyrics"))
        assertEquals(back.indexOf("saverLyrics") + 1, back.indexOf("saverDrift"))
        shows("set.saverDelay", "30 S")
        shows("set.saverLyrics", "ON")
        tap("set.saverDelay")
        assertEquals("saver delay", shell.sheet.title)
        assertEquals(listOf("10 S", "15 S", "30 S", "1 MIN", "2 MIN", "5 MIN"), shell.sheet.items)
        assertEquals(2, shell.sheet.focus)
        pick(3)
        c.tick()
        settle(1000)
        assertEquals("60", c.device.settings.values["saverDelay"])
        assertEquals("60", c.device.settings.values["saverSeconds"])
        shows("set.saverDelay", "1 MIN")
        assertTrue(c.failed.isEmpty())
    }

    @Test
    fun screenLockBringsBackSaverLyricsAndSaverDriftWhileTheSaverIsOff() {
        start()
        rule.walkPivots(c.device.schema.rows, "screens") { settle(1000) }
        tap("set.saverMode")
        pick(0)
        c.tick()
        settle(1000)
        assertTrue(screens().none { it == "saverLyrics" || it == "saverDrift" })
        shows("set.screenLock", "OFF")
        tap("set.screenLock")
        assertEquals("screen lock", shell.sheet.title)
        assertEquals(listOf("OFF", "ON"), shell.sheet.items)
        assertEquals(0, shell.sheet.focus)
        pick(1)
        c.tick()
        settle(1000)
        assertEquals("on", c.device.settings.values["screenLock"])
        val back = screens()
        assertEquals(back.indexOf("saverMode") + 1, back.indexOf("saverLyrics"))
        assertEquals(back.indexOf("saverLyrics") + 1, back.indexOf("saverDrift"))
        assertEquals(back.indexOf("saverDrift") + 1, back.indexOf("screenLock"))
        shows("set.saverLyrics", "ON")
        shows("set.screenLock", "ON")
        tap("set.screenLock")
        pick(0)
        c.tick()
        settle(1000)
        assertTrue(screens().none { it == "saverLyrics" || it == "saverDrift" })
        rule.onNodeWithTag("set.saverLyrics").assertDoesNotExist()
        rule.onNodeWithTag("set.saverDrift").assertDoesNotExist()
        assertTrue(c.failed.isEmpty())
    }

    @Test
    fun screenOffAfterListsTenAndThirtySeconds() {
        start()
        rule.walkPivots(c.device.schema.rows, "power") { settle(1000) }
        shows("set.screenOffSeconds", "5 MIN")
        tap("set.screenOffSeconds")
        assertEquals("screen off after", shell.sheet.title)
        assertEquals(listOf("OFF", "10 S", "30 S", "1 MIN", "2 MIN", "5 MIN", "10 MIN", "30 MIN"), shell.sheet.items)
        assertEquals(5, shell.sheet.focus)
        pick(1)
        assertEquals("10", c.device.settings.values["screenOffSeconds"])
        shows("set.screenOffSeconds", "10 S")
        tap("set.screenOffSeconds")
        pick(2)
        assertEquals("30", c.device.settings.values["screenOffSeconds"])
        shows("set.screenOffSeconds", "30 S")
        assertTrue(c.failed.isEmpty())
    }
}
