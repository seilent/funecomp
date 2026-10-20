package net.seilent.funecomp

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import net.seilent.funecomp.artist.ArtBlocks
import net.seilent.funecomp.artist.ArtCache
import net.seilent.funecomp.artist.Credit
import net.seilent.funecomp.artist.Http
import net.seilent.funecomp.artist.Names
import net.seilent.funecomp.artist.Response
import net.seilent.funecomp.artist.Sources
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.BackdropArt
import net.seilent.funecomp.ui.Control
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.Ui
import net.seilent.funecomp.proto.gen.Tok
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class ArtBlockUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = ArrayList<String>()
    private val entry = ArtCache.Entry("name:むと", "むと", null, "deezer", null, "https://cdn-images.dzcdn.net/images/artist/0d58cfbc90f2e776608bcdc0c45a4711/1000x1000.jpg", 1, sha = "s")
    private var sources by mutableStateOf(Sources(deezer = true))
    private var shownBlocked by mutableStateOf<List<ArtBlocks.Record>>(emptyList())
    private val opened = ArrayList<String>()

    private fun start(pages: List<Page>, art: BackdropArt? = null, blocked: List<ArtBlocks.Record> = emptyList(), credits: List<ArtCache.Entry> = emptyList()): Shell {
        shownBlocked = blocked
        val c = SimControl("ap80")
        val control = object : Control by c {
            override fun blockArt(e: ArtCache.Entry) {
                calls += "block ${e.name}"
            }

            override fun wrongArtist(e: ArtCache.Entry) {
                calls += "wrong ${e.name}"
            }

            override fun unblock(r: ArtBlocks.Record) {
                calls += "unblock ${r.name}"
                shownBlocked = shownBlocked - r
            }
        }
        val shell = Shell(Nav(pages))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) {
                App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), control, shell, sources, { sources = it }, art = art, credits = credits, onCredit = { opened += it.name }, blocked = shownBlocked)
            }
        }
        settle(1000)
        return shell
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun backdrop(e: ArtCache.Entry, wrong: Boolean) = BackdropArt(ImageBitmap(8, 8), null, e, wrong)

    @After
    fun tearDown() = Remote.resetArtist()

    private fun sheetFor(slot: String, key: String, name: String, by: String, vararg names: Pair<String, String?>): List<String> {
        val app = RuntimeEnvironment.getApplication()
        val c = ArtCache(File(app.filesDir, "art"))
        names.forEach { (n, m) -> c.putMbid(n, m, System.currentTimeMillis()) }
        c.putHit(ArtCache.Entry(key, name, names.firstNotNullOf { it.second }, "commons", null, "u", 1, "File:A.jpg", "s", by), byteArrayOf(1))
        val a = Remote.artist(app, Http { _, _ -> Response(404, ByteArray(0)) })
        val e = a.withSha(c.hit(key)!!)
        val shell = start(listOf(Page.Home, Page.NowPlaying), backdrop(e, a.wrongArtistOffered(e, slot)))
        rule.onNodeWithTag("np.backdrop").performTouchInput { longClick() }
        settle(500)
        return shell.sheet.items
    }

    private val utada = "b539e453-c4fe-47e3-8a07-8517eac74429"

    @Test
    fun theSecondNameOnASearchedMbidGetsOnlyBlockImage() {
        assertEquals(listOf("block image", "pick image"), sheetFor("Utada Hikaru", utada, "宇多田ヒカル", "宇多田ヒカル", "宇多田ヒカル" to utada, "Utada Hikaru" to utada))
    }

    @Test
    fun theResolverNameGetsWrongArtist() {
        assertEquals(listOf("block image", "wrong artist", "pick image"), sheetFor("宇多田ヒカル", utada, "宇多田ヒカル", "宇多田ヒカル", "宇多田ヒカル" to utada, "Utada Hikaru" to utada))
    }

    @Test
    fun aVariantWinGetsWrongArtist() {
        val vtuber = "空星きらめ/Sorahoshi Kirame【にじさんじ】"
        val items = sheetFor(vtuber, "name:${Names.fold(vtuber)}", vtuber, "空星きらめ", "空星きらめ" to "0e7b3c4f-1f2a-4b5c-8d6e-7f8091a2b3c4", vtuber to null)
        assertEquals(listOf("block image", "wrong artist", "pick image"), items)
    }

    @Test
    fun aLongPressOnTheBackdropOpensTheBlockSheet() {
        val shell = start(listOf(Page.Home, Page.NowPlaying), backdrop(entry, wrong = false))
        rule.onNodeWithTag("np.backdrop").performTouchInput { longClick() }
        settle(500)
        assertEquals("むと", shell.sheet.title)
        assertEquals(listOf("block image", "pick image"), shell.sheet.items)
        assertEquals(listOf("deezer"), shell.sheet.info)
        rule.onNodeWithTag("sheet.item.0").performClick()
        settle(500)
        assertEquals(listOf("block むと"), calls)
    }

    @Test
    fun wrongArtistIsOfferedOnlyWhenTheMbidCameFromASearch() {
        val e = ArtCache.Entry("b539e453-c4fe-47e3-8a07-8517eac74429", "宇多田ヒカル", "b539e453-c4fe-47e3-8a07-8517eac74429", "commons", Credit("Goldnrush Podcast", "CC BY 4.0", ""), "u", 1, "File:A.jpg", "s", "宇多田ヒカル")
        val shell = start(listOf(Page.Home, Page.NowPlaying), backdrop(e, wrong = true))
        rule.onNodeWithTag("np.backdrop").performTouchInput { longClick() }
        settle(500)
        assertEquals(listOf("block image", "wrong artist", "pick image"), shell.sheet.items)
        assertEquals(listOf("commons, Goldnrush Podcast"), shell.sheet.info)
        rule.onNodeWithTag("sheet.item.1").performClick()
        settle(500)
        assertEquals(listOf("wrong 宇多田ヒカル"), calls)
    }

    private val records = listOf(ArtBlocks.Block("name:むと", null, "deezer", "x", "s", "むと", 2), ArtBlocks.Reject("m", "m", "Muto", "Muto", 1))

    private val credited = listOf(
        ArtCache.Entry("a74b1b7f-71a5-4011-9441-d0b5e4122711", "Radiohead", null, "commons", Credit("Raph_PH", "CC BY 4.0", "https://commons.wikimedia.org/"), "", 0),
        ArtCache.Entry("b539e453-c4fe-47e3-8a07-8517eac74429", "Utada Hikaru", null, "commons", Credit("Goldnrush Podcast", "CC BY 4.0", "https://commons.wikimedia.org/"), "", 0),
    )

    private fun tap(tag: String) {
        rule.onNodeWithTag(tag).performClick()
        settle(1000)
    }

    private fun scrollTo(tag: String) {
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("rows").performScrollToNode(hasTestTag(tag))
        rule.mainClock.autoAdvance = false
        settle(100)
    }

    @Test
    fun theArtPivotListsBlocksAndUndoes() {
        val shell = start(listOf(Page.Home, Page.Online()), blocked = records)
        scrollTo("art.blocked")
        tap("art.blocked")
        assertEquals(Page.ArtBlocked, shell.nav.top)
        rule.onNodeWithTag("art.blocked.1").assertExists()
        tap("art.blocked.0")
        assertEquals(listOf("unblock"), shell.sheet.items)
        tap("sheet.item.0")
        assertEquals(listOf("unblock むと"), calls)
    }

    @Test
    fun emptyListsHaveNoRowAndARowShowsItsSize() {
        start(listOf(Page.Home, Page.Online()), blocked = records, credits = credited.take(1))
        scrollTo("art.blocked")
        rule.onNode(hasText("2") and hasAnyAncestor(hasTestTag("art.blocked"))).assertExists()
        rule.onNode(hasText("1") and hasAnyAncestor(hasTestTag("art.credits"))).assertExists()
        rule.onNodeWithTag("art.picked").assertDoesNotExist()
    }

    @Test
    fun aCreditRowOpensItsPage() {
        val shell = start(listOf(Page.Home, Page.Online()), credits = credited)
        scrollTo("art.credits")
        tap("art.credits")
        assertEquals(Page.ArtCredits, shell.nav.top)
        tap("art.credit.1")
        assertEquals(listOf("Utada Hikaru"), opened)
    }

    @Test
    fun theLastUnblockKeepsThePageWithItsLine() {
        val shell = start(listOf(Page.Home, Page.Online(), Page.ArtBlocked), blocked = records.take(1), credits = credited)
        tap("art.blocked.0")
        tap("sheet.item.0")
        assertEquals(Page.ArtBlocked, shell.nav.top)
        rule.onNodeWithTag("art.blocked.0").assertDoesNotExist()
        rule.onNodeWithTag("art.blocked.empty").assertExists()
        shell.nav.pop()
        settle(1000)
        rule.onNodeWithTag("art.blocked").assertDoesNotExist()
        scrollTo("art.credits")
        rule.onNodeWithTag("art.credits").assertExists()
    }

    @Test
    fun anUnblockFocusesTheNextRow() {
        val three = records + ArtBlocks.Block("name:radiohead", null, "commons", "y", "s", "Radiohead", 0)
        val shell = start(listOf(Page.Home, Page.Online(), Page.ArtBlocked), blocked = three)
        tap("art.blocked.1")
        tap("sheet.item.0")
        assertEquals(listOf("unblock Muto"), calls)
        assertEquals(Page.ArtBlocked, shell.nav.top)
        assertTrue(barOn("art.blocked.1"))
        assertFalse(barOn("art.blocked.0"))
    }

    @Test
    fun theFocusFollowsItsRecordWhenARowAboveGoes() {
        val four = records + ArtBlocks.Block("name:radiohead", null, "commons", "y", "s", "Radiohead", 0) + ArtBlocks.Block("name:utada", null, "deezer", "z", "s", "Utada", -1)
        val shell = start(listOf(Page.Home, Page.Online(), Page.ArtBlocked), blocked = four)
        tap("art.blocked.2")
        shell.sheet.close()
        settle(1000)
        shownBlocked = shownBlocked.drop(1)
        settle(1000)
        rule.onNode(hasText("Radiohead") and hasAnyAncestor(hasTestTag("art.blocked.1"))).assertExists()
        assertTrue(barOn("art.blocked.1"))
        assertFalse(barOn("art.blocked.2"))
    }

    @Test
    fun anEmptyCreditsPageShowsItsLine() {
        start(listOf(Page.Home, Page.Online(), Page.ArtCredits))
        rule.onNodeWithTag("art.credits.empty").assertExists()
    }

    @Test
    fun aPopKeepsTheFocusOnTheListRow() {
        val shell = start(listOf(Page.Home, Page.Online()), blocked = records, credits = credited)
        scrollTo("art.blocked")
        tap("art.blocked")
        assertEquals(Page.ArtBlocked, shell.nav.top)
        shell.nav.pop()
        settle(1000)
        assertTrue(barOn("art.blocked"))
        assertFalse(barOn("art.credits"))
    }

    private fun barOn(tag: String): Boolean {
        val file = File.createTempFile("bar", ".png")
        rule.onRoot().captureRoboImage(file.path)
        val img = BitmapFactory.decodeFile(file.path)
        file.delete()
        val ui = Ui.of(img.width.toFloat(), img.height.toFloat())
        val b = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val x = ui.px(Tok.hubEdge - Tok.focus_barOffset + Tok.focus_barWidth / 2).roundToInt()
        return Color.red(img.getPixel(x, b.center.y.roundToInt())) > 128
    }

    @Test
    fun theSourceSheetMovesASource() {
        val shell = start(listOf(Page.Home, Page.Online()))
        tap("art.src.commons")
        assertEquals(listOf("move down"), shell.sheet.items)
        tap("sheet.item.0")
        assertEquals(listOf("theaudiodb", "commons", "fanarttv", "discogs", "youtube", "deezer"), sources.order)
        scrollTo("art.src.deezer")
        tap("art.src.deezer")
        assertEquals(listOf("off", "on", "move up"), shell.sheet.items)
        assertEquals(1, shell.sheet.focus)
        tap("sheet.item.2")
        assertEquals(listOf("theaudiodb", "commons", "fanarttv", "discogs", "deezer", "youtube"), sources.order)
        assertTrue(barOn("art.src.deezer"))
        assertFalse(barOn("art.src.youtube"))
    }
}
