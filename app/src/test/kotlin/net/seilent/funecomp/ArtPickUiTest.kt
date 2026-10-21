package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.artist.ArtCache
import net.seilent.funecomp.artist.ArtPicks
import net.seilent.funecomp.artist.Names
import net.seilent.funecomp.artist.Offer
import net.seilent.funecomp.artist.Offered
import net.seilent.funecomp.artist.Sources
import net.seilent.funecomp.artist.Tile
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.sim.Library
import net.seilent.funecomp.sim.Track
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.BackdropArt
import net.seilent.funecomp.ui.BrowseTile
import net.seilent.funecomp.ui.Control
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.PickFor
import net.seilent.funecomp.ui.Shell
import org.junit.Assert.assertEquals
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
class ArtPickUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val dj = "91352441-9f79-48c2-81ad-ccdd1e46915a"
    private val calls = ArrayList<String>()
    private val picked = ArrayList<Pair<PickFor, Offer>>()
    private var offered: Offered = Offered.Refused("unsupported link")
    private var taken: Offered = Offered.Refused("image too small")
    private var tile: ((BrowseTile) -> Unit)? = null
    private var ended: ((Boolean) -> Unit)? = null
    private val offer = Offer(ArtPicks.IMAGE, Sources.YOUTUBE, "https://yt3.ggpht.com/a=s900", "https://yt3.ggpht.com/a", null, null, "月-Tsuki-", ByteArray(1))
    private val lib = Library(listOf(Track(1, "TSUKI, Other", listOf("TSUKI", "Other"), "A", "TSUKI", "Song", 200_000, "a", "a.flac", listOf("", dj))))

    private var shownPicks by mutableStateOf<List<ArtPicks.Pick>>(emptyList())

    private fun start(pages: List<Page>, art: BackdropArt? = null, picks: List<ArtPicks.Pick> = emptyList(), library: Library = lib, link: Phase = Phase.LIVE): Pair<Shell, SimControl> {
        shownPicks = picks
        val c = SimControl("ap80", library)
        val control = object : Control by c {
            override fun pickLink(text: String, done: (Offered) -> Unit) {
                calls += "link $text"
                done(offered)
            }

            override fun browse(name: String, mbid: String?, px: Int, tile: (BrowseTile) -> Unit, done: (Boolean) -> Unit): () -> Unit {
                calls += "browse $name $mbid"
                this@ArtPickUiTest.tile = tile
                ended = done
                return { calls += "cancel $name" }
            }

            override fun takeTile(t: Tile, done: (Offered) -> Unit) {
                calls += "take ${t.caption}"
                done(taken)
            }

            override fun pick(target: PickFor, offer: Offer) {
                picked += target to offer
            }

            override fun undoPick(p: ArtPicks.Pick) {
                calls += "undo ${p.name}"
                shownPicks = shownPicks - p
            }

            override fun tryAgain(p: ArtPicks.Pick) {
                calls += "again ${p.name}"
            }
        }
        val shell = Shell(Nav(pages))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) {
                App(c.device, LinkView(link, "AP80", everLive = true), control, shell, Sources.NONE.copy(youtube = true), art = art, picks = shownPicks)
            }
        }
        settle(1000)
        return shell to c
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun tap(tag: String) {
        rule.onNodeWithTag(tag).performClick()
        settle(500)
    }

    private fun hold(tag: String) {
        rule.onNodeWithTag(tag).performTouchInput { longClick() }
        settle(500)
    }

    private fun pick(name: String, kind: String = ArtPicks.IMAGE, failedAt: Long? = null, at: Long = 1) =
        ArtPicks.Pick("name:${name.lowercase()}", null, name, kind, Sources.YOUTUBE, "https://yt3.ggpht.com/a=s900", "https://yt3.ggpht.com/a", "", null, null, at, failedAt)

    private fun tiles(n: Int) = (0 until n).map { BrowseTile(Tile(if (it % 2 == 0) Sources.DEEZER else Sources.YOUTUBE, "tile $it", ByteArray(1), "https://e/$it.jpg", "$it", null), ImageBitmap(8, 8)) }

    @Test
    fun aChainImageSheetAddsPickImageAndOpensThePickSheet() {
        val e = ArtCache.Entry("name:tsuki", "TSUKI", null, Sources.DEEZER, null, "u", 1)
        val (shell, _) = start(listOf(Page.Home, Page.NowPlaying), BackdropArt(ImageBitmap(8, 8), null, e))
        hold("np.backdrop")
        assertEquals(listOf("block image", "pick image"), shell.sheet.items)
        tap("sheet.item.1")
        assertEquals("TSUKI", shell.sheet.title)
        assertEquals(listOf("paste link", "browse"), shell.sheet.items)
    }

    @Test
    fun aPickedImageSheetOffersPickAndUndo() {
        val p = pick("TSUKI")
        val e = ArtCache.Entry(p.cacheKey, "TSUKI", null, Sources.YOUTUBE, null, "u", 1)
        val (shell, _) = start(listOf(Page.Home, Page.NowPlaying), BackdropArt(ImageBitmap(8, 8), null, e), listOf(p))
        hold("np.backdrop")
        assertEquals(listOf("pick image", "undo pick"), shell.sheet.items)
        assertEquals(listOf("youtube"), shell.sheet.info)
        tap("sheet.item.1")
        assertEquals(listOf("undo TSUKI"), calls)
    }

    @Test
    fun aBlackBackdropWithTwoArtistsChoosesTheArtistFirst() {
        val (shell, _) = start(listOf(Page.Home, Page.NowPlaying))
        hold("np.backdrop")
        assertEquals("pick image", shell.sheet.title)
        assertEquals(listOf("TSUKI", "Other"), shell.sheet.items)
        tap("sheet.item.1")
        assertEquals("Other", shell.sheet.title)
        tap("sheet.item.1")
        assertEquals(Page.Browse(PickFor("Other", dj, origin = Enums.ImgOrigin.library)), shell.nav.top)
        assertEquals("browse Other $dj", calls.single())
    }

    private fun phone(c: SimControl) {
        c.sim.source(Enums.Source.phone)
        c.device = c.device.reduce(Msg.STATE, Node.decode("STATE", c.sim.state()), 0)
        settle(300)
    }

    @Test
    fun anInAppPickOfAPlayingArtistTakesThePhoneSource() {
        val (shell, c) = start(listOf(Page.Home, Page.NowPlaying))
        phone(c)
        hold("np.backdrop")
        tap("sheet.item.1")
        tap("sheet.item.1")
        assertEquals(Page.Browse(PickFor("Other", dj, origin = Enums.ImgOrigin.phone)), shell.nav.top)
    }

    @Test
    fun theBackdropSheetPickTakesThePhoneSource() {
        val e = ArtCache.Entry("name:tsuki", "TSUKI", null, Sources.DEEZER, null, "u", 1)
        val (shell, c) = start(listOf(Page.Home, Page.NowPlaying), BackdropArt(ImageBitmap(8, 8), null, e))
        phone(c)
        hold("np.backdrop")
        tap("sheet.item.1")
        tap("sheet.item.1")
        assertEquals(Page.Browse(PickFor("TSUKI", "", origin = Enums.ImgOrigin.phone)), shell.nav.top)
    }

    @Test
    fun oneArtistSkipsTheChooser() {
        val one = Library(listOf(Track(1, "TSUKI", listOf("TSUKI"), "A", "TSUKI", "Song", 200_000, "a", "a.flac")))
        val (shell, _) = start(listOf(Page.Home, Page.NowPlaying), library = one)
        hold("np.backdrop")
        assertEquals("TSUKI", shell.sheet.title)
        assertEquals(listOf("paste link", "browse"), shell.sheet.items)
    }

    @Test
    fun aPastedLinkIsConfirmedThenPicked() {
        val (shell, _) = start(listOf(Page.Home, Page.NowPlaying))
        hold("np.backdrop")
        tap("sheet.item.0")
        tap("sheet.item.0")
        offered = Offered.Ok(offer)
        rule.onNodeWithTag("entry").performTextInput("https://www.youtube.com/@tsukiniterasarete")
        rule.onNodeWithTag("entry").performImeAction()
        settle(500)
        assertEquals("link https://www.youtube.com/@tsukiniterasarete", calls.single())
        assertEquals("TSUKI", shell.sheet.title)
        assertEquals(listOf("use image", "cancel"), shell.sheet.items)
        assertEquals(listOf("youtube, 月-Tsuki-"), shell.sheet.info)
        tap("sheet.item.0")
        assertEquals("TSUKI", picked.single().first.name)
        assertEquals(null, Names.mbid(picked.single().first.mbid))
        assertEquals(offer, picked.single().second)
    }

    @Test
    fun aRefusedLinkShowsTheReason() {
        val (shell, _) = start(listOf(Page.Home, Page.NowPlaying))
        hold("np.backdrop")
        tap("sheet.item.0")
        tap("sheet.item.0")
        offered = Offered.Refused("link not reachable")
        rule.onNodeWithTag("entry").performTextInput("https://example.org/x.jpg")
        rule.onNodeWithTag("entry").performImeAction()
        settle(300)
        assertEquals("TSUKI", shell.toast.artist)
        assertEquals("link not reachable", shell.toast.title)
        assertTrue(picked.isEmpty())
    }

    @Test
    fun theBrowsePageFillsThenATapPicksAndPops() {
        val target = PickFor("TSUKI")
        val (shell, _) = start(listOf(Page.Home, Page.NowPlaying, Page.Browse(target)))
        rule.onNodeWithTag("pick.state").assertExists()
        tiles(5).forEach { tile!!(it) }
        settle(500)
        rule.onNodeWithTag("pick.tile.4").assertExists()
        rule.onNodeWithTag("pick.state").assertExists()
        ended!!(true)
        settle(300)
        rule.onNodeWithTag("pick.state").assertDoesNotExist()
        tap("pick.tile.3")
        assertEquals("image too small", shell.toast.title)
        assertEquals(Page.Browse(target), shell.nav.top)
        taken = Offered.Ok(offer)
        tap("pick.tile.2")
        assertEquals(listOf(target to offer), picked)
        assertEquals(Page.NowPlaying, shell.nav.top)
        settle(1500)
        assertTrue("cancel TSUKI" in calls)
    }

    @Test
    fun anEmptyOrUnavailableBrowseShowsItsLine() {
        start(listOf(Page.Home, Page.Browse(PickFor("TSUKI"))))
        ended!!(false)
        settle(300)
        rule.onNodeWithTag("pick.state").assertExists()
        tiles(1).forEach { tile!!(it) }
        settle(300)
        rule.onNodeWithTag("pick.tile.0").assertExists()
        rule.onNodeWithTag("pick.state").assertExists()
    }

    @Test
    fun pickedRowsShowTheirStateAndABrokenOneTriesAgain() {
        val picks = listOf(pick("A", at = 3), pick("B", failedAt = 5, at = 2), pick("C", kind = ArtPicks.NONE, at = 1))
        val (shell, _) = start(listOf(Page.Home, Page.Online()), picks = picks)
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("rows").performScrollToNode(hasTestTag("art.picked"))
        rule.mainClock.autoAdvance = false
        tap("art.picked")
        assertEquals(Page.ArtPicked, shell.nav.top)
        settle(500)
        tap("art.picked.1")
        assertEquals("B", shell.sheet.title)
        assertEquals(listOf("undo pick", "try again"), shell.sheet.items)
        tap("sheet.item.1")
        tap("art.picked.2")
        assertEquals(listOf("undo pick"), shell.sheet.items)
        tap("sheet.item.0")
        assertEquals(listOf("again B", "undo C"), calls)
    }

    @Test
    fun theLastUndoKeepsThePageWithItsLine() {
        val (shell, _) = start(listOf(Page.Home, Page.Online(), Page.ArtPicked), picks = listOf(pick("A")))
        tap("art.picked.0")
        tap("sheet.item.0")
        assertEquals(listOf("undo A"), calls)
        assertEquals(Page.ArtPicked, shell.nav.top)
        rule.onNodeWithTag("art.picked.0").assertDoesNotExist()
        rule.onNodeWithTag("art.picked.empty").assertExists()
        shell.nav.pop()
        settle(1000)
        rule.onNodeWithTag("art.picked").assertDoesNotExist()
    }

    @Test
    fun theArtistsSheetEndsWithPickImage() {
        val (shell, _) = start(listOf(Page.Home, Page.Music))
        settle(1000)
        hold("row.1")
        assertEquals(listOf("play", "shuffle", "pick image"), shell.sheet.items)
        tap("sheet.item.2")
        assertEquals("Other", shell.sheet.title)
        tap("sheet.item.1")
        assertEquals(Page.Browse(PickFor("Other", dj, library = true)), shell.nav.top)
    }
}
