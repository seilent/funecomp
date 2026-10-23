package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.state.CardView
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.Control
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class CardToastTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = ArrayList<String>()
    private lateinit var c: SimControl
    private lateinit var shell: Shell

    private fun start(card: Int? = null, phone: Boolean = false) {
        c = SimControl("v1")
        c.queuePage(0)
        val s = c.device.playback.state
        c.push(Msg.STATE, s.copy(card = card ?: s.card, source = if (phone) Enums.Source.phone else s.source).encode())
        val control = object : Control by c {
            override fun play(view: Int?, parent: String?, start: Long, shuffle: Boolean, tracks: List<Long>) {
                calls += "play"
            }

            override fun queueOp(cmd: Int, slot: Long?, to: Long?, tracks: List<Long>) {
                calls += "queue ${Enums.QueueCmd.names[cmd]}"
            }

            override fun queueAdd(cmd: Int, view: Int?, parent: String?, tracks: List<Long>, done: (Long?, Int?) -> Unit) {
                calls += "add ${Enums.QueueCmd.names[cmd]}"
            }

            override fun action(cmd: Int, track: Long?, done: (Int?, String?) -> Unit) {
                calls += "action ${Enums.ActionCmd.names[cmd]}"
            }
        }
        shell = Shell(Nav(listOf(Page.Home)))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) { App(c.device, LinkView(Phase.LIVE, "V1", everLive = true), control, shell) }
        }
        settle()
    }

    private fun settle() {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
    }

    private fun open(vararg pages: Page) {
        rule.runOnIdle { shell.nav.reset(listOf(Page.Home)) }
        settle()
        rule.runOnIdle { shell.nav.reset(listOf(Page.Home) + pages) }
        settle()
    }

    private fun tap(tag: String) {
        rule.onNodeWithTag(tag).performClick()
        settle()
    }

    private fun sheet(tag: String, item: Int) {
        rule.onNodeWithTag(tag).performTouchInput { longClick() }
        settle()
        rule.runOnIdle { shell.sheet.accept(item) }
        settle()
    }

    private fun songs() {
        open(Page.Music)
        for (p in listOf("albums", "songs")) tap("pivot.$p")
    }

    private fun albums() {
        open(Page.Music)
        tap("pivot.albums")
    }

    private class Entry(val name: String, val prep: () -> Unit, val act: () -> Unit)

    private val entries = listOf(
        Entry("home ring", { open() }) { tap("home.ring") },
        Entry("list play", { songs() }) { tap("row.0") },
        Entry("song sheet play next", { songs() }) { sheet("row.0", 1) },
        Entry("song sheet add to queue", { songs() }) { sheet("row.0", 2) },
        Entry("collection sheet play", { albums() }) { sheet("row.0", 0) },
        Entry("collection sheet play next", { albums() }) { sheet("row.0", 1) },
        Entry("collection sheet add to queue", { albums() }) { sheet("row.0", 2) },
        Entry("queue page tap", { open(Page.NowPlaying, Page.Queue) }) { tap("queue.1") },
    )

    private val adds = setOf("song sheet play next", "song sheet add to queue", "collection sheet play next", "collection sheet add to queue")

    private fun refusedEverywhere(line: String, at: (Entry) -> Boolean = { true }) {
        for (e in entries.filter(at)) {
            e.prep()
            val top = shell.nav.top
            val shows = shell.toast.shows
            e.act()
            assertEquals(e.name, emptyList<String>(), calls)
            assertEquals(e.name, shows + 1, shell.toast.shows)
            assertEquals(e.name, line, shell.toast.artist)
            assertEquals(e.name, "", shell.toast.title)
            assertEquals(e.name, top, shell.nav.top)
        }
    }

    @Test
    fun aCardRunToastsAtEveryPlayEntryAndSendsNothing() {
        start(card = Enums.Card.busy)
        refusedEverywhere(CardView.REFUSED)
    }

    @Test
    fun noCardToastsAtEveryPlayEntryAndSendsNothing() {
        start(card = Enums.Card.out)
        refusedEverywhere(CardView.REFUSED)
    }

    @Test
    fun aPhoneStreamToastsTheReceiverAtEveryPlayEntryAndSendsNothing() {
        start(phone = true)
        refusedEverywhere(DeviceState.RECEIVER) { it.name !in adds }
    }

    @Test
    fun aPhoneStreamSendsPlayNextAndAddToQueue() {
        start(phone = true)
        val sent = mapOf("song sheet play next" to "queue playNext", "song sheet add to queue" to "add add", "collection sheet play next" to "add playNext", "collection sheet add to queue" to "add add")
        for (e in entries.filter { it.name in adds }) {
            e.prep()
            calls.clear()
            e.act()
            assertEquals(e.name, listOf(sent.getValue(e.name)), calls)
            assertEquals(e.name, false, shell.toast.artist == DeviceState.RECEIVER)
        }
    }

    @Test
    fun aReadyCardSendsAtEveryPlayEntryAndAListPlayOpensNowPlayingBeforeAnyReply() {
        start(card = Enums.Card.ready)
        val sent = listOf("action shuffleLibrary", "play", "queue playNext", "add add", "play", "add playNext", "add add", "queue playAt")
        for ((i, e) in entries.withIndex()) {
            e.prep()
            calls.clear()
            e.act()
            assertEquals(e.name, listOf(sent[i]), calls)
            if (e.name == "list play" || e.name == "collection sheet play") assertEquals(e.name, Page.NowPlaying, shell.nav.top)
        }
    }

    @Test
    fun aSheetOpenedDuringTheRunSendsWhenTheCardIsReadyAtTheTap() {
        start(card = Enums.Card.busy)
        albums()
        rule.onNodeWithTag("row.0").performTouchInput { longClick() }
        settle()
        rule.runOnIdle { c.push(Msg.STATE, c.device.playback.state.copy(card = Enums.Card.ready).encode()) }
        settle()
        rule.runOnIdle { shell.sheet.accept(2) }
        settle()
        assertEquals(listOf("add add"), calls)
    }
}
