package net.seilent.funecomp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import net.seilent.funecomp.artist.ArtCache
import net.seilent.funecomp.artist.ArtPicks
import net.seilent.funecomp.artist.Names
import net.seilent.funecomp.artist.Offer
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
import net.seilent.funecomp.ui.Preview
import net.seilent.funecomp.ui.Previewed
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
    private var offered: Previewed? = Previewed.Refused("unsupported link")
    private var taken: Previewed? = Previewed.Refused("image too small")
    private var galleried: Previewed? = null
    private val late = ArrayList<(Previewed) -> Unit>()
    private var tile: ((String, BrowseTile) -> Unit)? = null
    private var ended: ((Boolean) -> Unit)? = null
    private val offer = Offer(ArtPicks.IMAGE, Sources.YOUTUBE, "https://yt3.ggpht.com/a=s900", "https://yt3.ggpht.com/a", null, null, "月-Tsuki-", ByteArray(1))
    private val lib = Library(listOf(Track(1, "TSUKI, Other", listOf("TSUKI", "Other"), "A", "TSUKI", "Song", 200_000, "a", "a.flac", listOf("", dj))))

    private var shownPicks by mutableStateOf<List<ArtPicks.Pick>>(emptyList())

    private fun start(pages: List<Page>, art: BackdropArt? = null, picks: List<ArtPicks.Pick> = emptyList(), library: Library = lib, link: Phase = Phase.LIVE, missing: Set<String> = emptySet()): Pair<Shell, SimControl> {
        shownPicks = picks
        val c = SimControl("ap80", library)
        val control = object : Control by c {
            override fun pickLink(text: String, name: String, done: (Previewed) -> Unit): () -> Unit {
                calls += "link $text $name"
                offered?.let(done) ?: late.add(done)
                return { calls += "cancel link" }
            }

            override fun pickGallery(done: (Previewed) -> Unit): () -> Unit {
                calls += "gallery"
                galleried?.let(done) ?: late.add(done)
                return { calls += "cancel gallery" }
            }

            override fun browse(name: String, mbid: String?, px: Int, sections: (List<String>) -> Unit, tile: (String, BrowseTile) -> Unit, sectionDone: (String) -> Unit, done: (Boolean) -> Unit): () -> Unit {
                calls += "browse $name $mbid"
                this@ArtPickUiTest.tile = tile
                ended = done
                return { calls += "cancel $name" }
            }

            override fun takeTile(t: Tile, done: (Previewed) -> Unit): () -> Unit {
                calls += "take ${t.caption}"
                taken?.let(done) ?: late.add(done)
                return { calls += "cancel take" }
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
                App(c.device, LinkView(link, "AP80", everLive = true), control, shell, Sources.NONE.copy(youtube = true), art = art, picks = shownPicks, galleryMissing = missing)
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
        assertEquals(listOf("paste link", "browse", "from gallery"), shell.sheet.items)
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
        assertEquals(listOf("paste link", "browse", "from gallery"), shell.sheet.items)
    }

    private fun ok(vararg offers: Offer) = Previewed.Ok(offers.map { Preview(it, ImageBitmap(30, 40)) })

    private fun paste(shell: Shell, url: String = "https://music.youtube.com/@tsukiniterasarete") {
        hold("np.backdrop")
        tap("sheet.item.0")
        tap("sheet.item.0")
        rule.onNodeWithTag("entry").performTextInput(url)
        rule.onNodeWithTag("entry").performImeAction()
        settle(500)
        assertEquals("TSUKI", shell.sheet.title)
    }

    private fun loading(shell: Shell) {
        assertTrue(shell.sheet.shown)
        assertEquals(listOf("cancel"), shell.sheet.items)
        assertEquals(listOf("loading"), shell.sheet.info)
    }

    private fun previewing(shell: Shell, n: Int = 1): Page.PickPreview {
        settle(1500)
        val p = shell.nav.top as Page.PickPreview
        assertEquals(n, p.previews.size)
        assertTrue(!shell.sheet.shown)
        rule.onNodeWithTag("pick.label").assertExists()
        rule.onNodeWithTag("back").assertExists()
        return p
    }

    @Test
    fun aPastedLinkLoadsThenPreviewsThenPicks() {
        val (shell, _) = start(listOf(Page.Home, Page.NowPlaying))
        offered = null
        paste(shell)
        assertEquals("link https://music.youtube.com/@tsukiniterasarete TSUKI", calls.single())
        loading(shell)
        late.single()(ok(offer))
        settle(300)
        assertEquals("TSUKI", previewing(shell).target.name)
        assertTrue(picked.isEmpty())
        rule.onNodeWithTag("pick.source").assertDoesNotExist()
        rule.onNodeWithTag("pick.next").assertDoesNotExist()
        tap("pick.label")
        assertEquals("TSUKI", picked.single().first.name)
        assertEquals(null, Names.mbid(picked.single().first.mbid))
        assertEquals(offer, picked.single().second)
        settle(1500)
        assertEquals(Page.NowPlaying, shell.nav.top)
    }

    @Test
    fun aSecondTapOnPickWhileThePageLeavesPicksOnce() {
        val (shell, _) = start(listOf(Page.Home, Page.NowPlaying))
        offered = ok(offer)
        paste(shell)
        previewing(shell)
        rule.onNodeWithTag("pick.label").performClick()
        rule.mainClock.advanceTimeBy(50)
        rule.onNodeWithTag("pick.label").performClick()
        settle(1500)
        assertEquals(1, picked.size)
        assertEquals(Page.NowPlaying, shell.nav.top)
    }

    @Test
    fun theBackRingAndBackLeaveThePreviewWithNothingPicked() {
        val (shell, _) = start(listOf(Page.Home, Page.NowPlaying))
        offered = ok(offer)
        paste(shell)
        previewing(shell)
        tap("back")
        settle(1500)
        assertEquals(Page.NowPlaying, shell.nav.top)
        paste(shell)
        previewing(shell)
        Espresso.pressBack()
        settle(1500)
        assertEquals(Page.NowPlaying, shell.nav.top)
        assertTrue(picked.isEmpty())
    }

    @Test
    fun twoImagesShowOneAtATimeWithTheirSource() {
        val (shell, _) = start(listOf(Page.Home, Page.NowPlaying))
        val www = Offer(ArtPicks.IMAGE, Sources.YOUTUBE, "https://yt3.ggpht.com/b=s900", "https://yt3.ggpht.com/b", null, null, "月-Tsuki-", ByteArray(1))
        val music = Offer(ArtPicks.IMAGE, Sources.YOUTUBE, "https://yt3.ggpht.com/a=s1600-rj", "https://yt3.ggpht.com/a", null, null, "月-Tsuki-", ByteArray(1), "youtube music")
        offered = ok(music, www)
        paste(shell)
        previewing(shell, 2)
        fun source() = rule.onNode(hasTestTag("pick.source"), useUnmergedTree = true).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString().lowercase()
        assertEquals("youtube music", source())
        tap("pick.next")
        assertEquals("youtube", source())
        tap("pick.next")
        assertEquals("youtube music", source())
        tap("pick.next")
        tap("pick.label")
        assertEquals(www, picked.single().second)
    }

    @Test
    fun aCancelEndsTheResolveAndALateResultDoesNothing() {
        val (shell, _) = start(listOf(Page.Home, Page.NowPlaying))
        offered = null
        paste(shell)
        tap("sheet.item.0")
        assertTrue("cancel link" in calls)
        late.removeAt(0)(ok(offer))
        settle(1500)
        assertEquals(Page.NowPlaying, shell.nav.top)
        paste(shell)
        rule.onNodeWithTag("sheet").performTouchInput { click(androidx.compose.ui.geometry.Offset(width / 2f, 10f)) }
        settle(500)
        assertEquals(2, calls.count { it == "cancel link" })
        late.removeAt(0)(Previewed.Refused("link not reachable"))
        settle(500)
        assertEquals("", shell.toast.title)
        paste(shell)
        Espresso.pressBack()
        settle(500)
        assertEquals(3, calls.count { it == "cancel link" })
        late.removeAt(0)(ok(offer))
        settle(1500)
        assertEquals(Page.NowPlaying, shell.nav.top)
        assertTrue(picked.isEmpty())
    }

    @Test
    fun aLateResultLeavesAnotherSheetAsItWas() {
        val (shell, _) = start(listOf(Page.Home, Page.NowPlaying))
        offered = null
        paste(shell)
        shell.sheet.open("background", listOf("allow", "not now")) {}
        settle(300)
        late.single()(ok(offer))
        settle(1500)
        assertEquals("background", shell.sheet.title)
        assertEquals(listOf("allow", "not now"), shell.sheet.items)
        assertEquals(Page.NowPlaying, shell.nav.top)
    }

    @Test
    fun aRefusalWhileLoadingClosesTheSheetAndShowsTheReason() {
        val (shell, _) = start(listOf(Page.Home, Page.NowPlaying))
        for (reason in listOf("link not reachable", "not an image")) {
            offered = null
            paste(shell)
            late.removeAt(0)(Previewed.Refused(reason))
            settle(300)
            assertTrue(!shell.sheet.shown)
            assertEquals("TSUKI", shell.toast.artist)
            assertEquals(reason, shell.toast.title)
            settle(1000)
        }
        assertTrue(picked.isEmpty())
        assertEquals(Page.NowPlaying, shell.nav.top)
    }

    @Test
    fun aMusicBrainzLinkWithNoImageStillPicks() {
        val (shell, _) = start(listOf(Page.Home, Page.NowPlaying))
        val mb = Offer(ArtPicks.ARTIST, Sources.MUSICBRAINZ, "https://musicbrainz.org/artist/$dj", dj, null, dj, "Tsuki", null)
        offered = Previewed.Ok(listOf(Preview(mb, null)))
        paste(shell, "https://musicbrainz.org/artist/$dj")
        previewing(shell)
        rule.onNodeWithTag("pick.preview").assertDoesNotExist()
        tap("pick.label")
        assertEquals(mb, picked.single().second)
    }

    @Test
    fun fromGalleryPreviewsAndAClosedPickerEndsTheLoading() {
        val (shell, _) = start(listOf(Page.Home, Page.NowPlaying))
        hold("np.backdrop")
        tap("sheet.item.0")
        tap("sheet.item.2")
        assertEquals("gallery", calls.single())
        loading(shell)
        late.removeAt(0)(Previewed.Closed)
        settle(300)
        assertTrue(!shell.sheet.shown)
        assertEquals("", shell.toast.title)
        settle(1000)
        val g = Offer(ArtPicks.IMAGE, Sources.GALLERY, "", "gallery:aa", null, null, "IMG_1.jpg", ByteArray(1))
        galleried = ok(g)
        hold("np.backdrop")
        tap("sheet.item.0")
        tap("sheet.item.2")
        previewing(shell)
        tap("pick.label")
        assertEquals(g, picked.single().second)
    }

    @Test
    fun aSheetItemStillRunsAfterTheSheetCloses() {
        val (shell, _) = start(listOf(Page.Home, Page.NowPlaying))
        hold("np.backdrop")
        tap("sheet.item.0")
        tap("sheet.item.1")
        assertTrue(shell.nav.top is Page.Browse)
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun focusedOn(tag: String) {
        val t = bounds(tag)
        val f = rule.onNodeWithTag("pick.focus", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val edge = (f.width - t.width) / 2
        assertTrue(edge > 0f)
        assertEquals(t.left, f.left + edge, 0.5f)
        assertEquals(t.top, f.top + edge, 0.5f)
    }

    private fun browseFilled(n: Int = 12): Pair<Shell, PickFor> {
        val target = PickFor("TSUKI")
        val (shell, _) = start(listOf(Page.Home, Page.NowPlaying, Page.Browse(target)))
        tiles(n).forEach { tile!!(it.tile.source, it) }
        settle(500)
        return shell to target
    }

    @Test
    fun aBrowseTileTapIsPreviewedThenPicked() {
        val (shell, target) = browseFilled()
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("rows").performScrollToNode(hasTestTag("pick.tile.10"))
        rule.mainClock.autoAdvance = false
        settle(300)
        val at = bounds("pick.tile.10")
        taken = Previewed.Refused("image too small")
        tap("pick.tile.9")
        assertEquals("image too small", shell.toast.title)
        assertEquals(Page.Browse(target), shell.nav.top)
        taken = null
        tap("pick.tile.10")
        loading(shell)
        assertEquals(null, shell.sheet.keep)
        late.removeAt(0)(ok(offer))
        settle(300)
        val p = previewing(shell)
        assertTrue(p.fromBrowse)
        assertTrue(picked.isEmpty())
        tiles(14).drop(12).forEach { tile!!(it.tile.source, it) }
        settle(300)
        tap("back")
        settle(1500)
        assertEquals(Page.Browse(target), shell.nav.top)
        assertEquals(at, bounds("pick.tile.10"))
        focusedOn("pick.tile.10")
        assertTrue("cancel TSUKI" !in calls)
        assertEquals(1, calls.count { it == "browse TSUKI null" })
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("rows").performScrollToNode(hasTestTag("pick.tile.13"))
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("pick.tile.13").assertExists()
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("rows").performScrollToNode(hasTestTag("pick.tile.10"))
        rule.mainClock.autoAdvance = false
        settle(300)
        taken = ok(offer)
        tap("pick.tile.10")
        settle(1500)
        tap("pick.label")
        settle(1500)
        assertEquals(listOf(target to offer), picked)
        assertEquals(Page.NowPlaying, shell.nav.top)
        settle(1500)
        assertEquals(1, calls.count { it == "cancel TSUKI" })
    }

    @Test
    fun aPastePickOverBrowsePopsThePreviewOnly() {
        val (shell, target) = browseFilled()
        offered = ok(offer)
        shell.pick = target
        settle(500)
        tap("sheet.item.0")
        rule.onNodeWithTag("entry").performTextInput("https://example.org/a.jpg")
        rule.onNodeWithTag("entry").performImeAction()
        settle(1500)
        assertTrue(!(shell.nav.top as Page.PickPreview).fromBrowse)
        tap("pick.label")
        settle(1500)
        assertEquals(Page.Browse(target), shell.nav.top)
    }

    @Test
    fun everyWayOutOfABrowseCancelsItOnce() {
        val (shell, target) = browseFilled()
        taken = ok(offer)
        tap("pick.tile.2")
        settle(1500)
        shell.nav.reset(listOf(Page.Home, Page.NowPlaying))
        settle(1500)
        assertEquals(1, calls.count { it == "cancel TSUKI" })
        calls.clear()
        shell.nav.push(Page.Browse(target))
        settle(1500)
        shell.nav.pop()
        settle(100)
        shell.nav.push(Page.Browse(target))
        settle(1500)
        assertEquals(listOf("browse TSUKI null", "cancel TSUKI", "browse TSUKI null"), calls)
        rule.onNodeWithTag("pick.state").assertExists()
    }

    @Test
    fun aSecondBrowseUnderThePreviewStopsTheFirstAndItRestartsOnReturn() {
        val (shell, target) = browseFilled()
        taken = ok(offer)
        tap("pick.tile.2")
        settle(1500)
        val other = PickFor("Other")
        shell.nav.push(Page.Browse(other))
        settle(1500)
        assertEquals(listOf("cancel TSUKI", "browse Other null"), calls.filter { it.startsWith("cancel") || it.startsWith("browse") }.drop(1))
        shell.nav.pop()
        settle(1500)
        assertTrue(shell.nav.top is Page.PickPreview)
        assertEquals(2, calls.count { it == "browse TSUKI null" })
        tiles(1).single().let { tile!!(it.tile.source, it) }
        shell.nav.pop()
        settle(1500)
        assertEquals(Page.Browse(target), shell.nav.top)
        assertEquals(2, calls.count { it == "browse TSUKI null" })
        rule.onNodeWithTag("pick.tile.0").assertExists()
    }

    @Test
    fun aNewNavOrTheStackLeavingCancelsTheBrowse() {
        val target = PickFor("TSUKI")
        var nav by mutableStateOf(Nav(listOf(Page.Home, Page.Browse(target))))
        var shown by mutableStateOf(true)
        val c = SimControl("ap80", lib)
        val control = object : Control by c {
            override fun browse(name: String, mbid: String?, px: Int, sections: (List<String>) -> Unit, tile: (String, BrowseTile) -> Unit, sectionDone: (String) -> Unit, done: (Boolean) -> Unit): () -> Unit {
                calls += "browse $name"
                return { calls += "cancel $name" }
            }
        }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) {
                val shell = remember(nav) { Shell(nav) }
                if (shown) App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), control, shell, Sources.NONE)
            }
        }
        settle(1000)
        assertEquals(listOf("browse TSUKI"), calls)
        nav = Nav(listOf(Page.Home, Page.Browse(target)))
        settle(1000)
        assertEquals(listOf("browse TSUKI", "cancel TSUKI", "browse TSUKI"), calls)
        shown = false
        settle(500)
        assertEquals(listOf("browse TSUKI", "cancel TSUKI", "browse TSUKI", "cancel TSUKI"), calls)
    }

    @Test
    fun aSizeChangeUnderThePreviewKeepsTheBrowse() {
        val target = PickFor("TSUKI")
        var w by mutableStateOf(411)
        val shell = Shell(Nav(listOf(Page.Home, Page.NowPlaying, Page.Browse(target))))
        val c = SimControl("ap80", lib)
        val control = object : Control by c {
            override fun browse(name: String, mbid: String?, px: Int, sections: (List<String>) -> Unit, tile: (String, BrowseTile) -> Unit, sectionDone: (String) -> Unit, done: (Boolean) -> Unit): () -> Unit {
                calls += "browse $name"
                this@ArtPickUiTest.tile = tile
                return { calls += "cancel $name" }
            }

            override fun takeTile(t: Tile, done: (Previewed) -> Unit): () -> Unit {
                done(ok(offer))
                return {}
            }
        }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(w.dp, 960.dp)) {
                App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), control, shell, Sources.NONE)
            }
        }
        settle(1000)
        tiles(6).forEach { tile!!(it.tile.source, it) }
        settle(500)
        tap("pick.tile.4")
        settle(1500)
        assertTrue(shell.nav.top is Page.PickPreview)
        w = 380
        settle(1000)
        tap("back")
        settle(1500)
        assertEquals(listOf("browse TSUKI"), calls)
        rule.onNodeWithTag("pick.tile.5").assertExists()
        focusedOn("pick.tile.4")
    }

    @Test
    fun anEmptyOrUnavailableBrowseShowsItsLine() {
        start(listOf(Page.Home, Page.Browse(PickFor("TSUKI"))))
        ended!!(false)
        settle(300)
        rule.onNodeWithTag("pick.state").assertExists()
        tiles(1).forEach { tile!!(it.tile.source, it) }
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
    fun aGalleryPickWithItsFileGoneReadsNoImageWithUndoOnly() {
        val gone = pick("A").copy(source = Sources.GALLERY, url = "", sha = "aa")
        val kept = pick("B", at = 0).copy(source = Sources.GALLERY, url = "", sha = "bb")
        val (shell, _) = start(listOf(Page.Home, Page.Online(), Page.ArtPicked), picks = listOf(gone, kept), missing = setOf("aa"))
        rule.onNode(hasText("no image", ignoreCase = true) and hasAnyAncestor(hasTestTag("art.picked.0")), useUnmergedTree = true).assertExists()
        rule.onNode(hasText("gallery", ignoreCase = true) and hasAnyAncestor(hasTestTag("art.picked.1")), useUnmergedTree = true).assertExists()
        tap("art.picked.0")
        assertEquals(listOf("undo pick"), shell.sheet.items)
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
