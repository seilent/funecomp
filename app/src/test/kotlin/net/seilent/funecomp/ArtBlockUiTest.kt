package net.seilent.funecomp

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
import androidx.compose.ui.unit.dp
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
import org.junit.After
import org.junit.Assert.assertEquals
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

    private fun start(pages: List<Page>, art: BackdropArt? = null, blocked: List<ArtBlocks.Record> = emptyList()): Shell {
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
            }
        }
        val shell = Shell(Nav(pages))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 960.dp)) {
                App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), control, shell, sources, { sources = it }, art = art, blocked = blocked)
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
        assertEquals(listOf("block image"), sheetFor("Utada Hikaru", utada, "宇多田ヒカル", "宇多田ヒカル", "宇多田ヒカル" to utada, "Utada Hikaru" to utada))
    }

    @Test
    fun theResolverNameGetsWrongArtist() {
        assertEquals(listOf("block image", "wrong artist"), sheetFor("宇多田ヒカル", utada, "宇多田ヒカル", "宇多田ヒカル", "宇多田ヒカル" to utada, "Utada Hikaru" to utada))
    }

    @Test
    fun aVariantWinGetsWrongArtist() {
        val vtuber = "空星きらめ/Sorahoshi Kirame【にじさんじ】"
        val items = sheetFor(vtuber, "name:${Names.fold(vtuber)}", vtuber, "空星きらめ", "空星きらめ" to "0e7b3c4f-1f2a-4b5c-8d6e-7f8091a2b3c4", vtuber to null)
        assertEquals(listOf("block image", "wrong artist"), items)
    }

    @Test
    fun aLongPressOnTheBackdropOpensTheBlockSheet() {
        val shell = start(listOf(Page.Home, Page.NowPlaying), backdrop(entry, wrong = false))
        rule.onNodeWithTag("np.backdrop").performTouchInput { longClick() }
        settle(500)
        assertEquals("むと", shell.sheet.title)
        assertEquals(listOf("block image"), shell.sheet.items)
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
        assertEquals(listOf("block image", "wrong artist"), shell.sheet.items)
        assertEquals(listOf("commons, Goldnrush Podcast"), shell.sheet.info)
        rule.onNodeWithTag("sheet.item.1").performClick()
        settle(500)
        assertEquals(listOf("wrong 宇多田ヒカル"), calls)
    }

    @Test
    fun theArtistArtPageListsBlocksAndUndoes() {
        val records = listOf(ArtBlocks.Block("name:むと", null, "deezer", "x", "s", "むと", 2), ArtBlocks.Reject("m", "m", "Muto", "Muto", 1))
        val shell = start(listOf(Page.Home, Page.ArtistArt), blocked = records)
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("rows").performScrollToNode(hasTestTag("art.blocked.1"))
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("art.blocked.0").performClick()
        settle(500)
        assertEquals(listOf("unblock"), shell.sheet.items)
        rule.onNodeWithTag("sheet.item.0").performClick()
        settle(500)
        assertEquals(listOf("unblock むと"), calls)
    }

    @Test
    fun theOrderSheetMovesASource() {
        val shell = start(listOf(Page.Home, Page.ArtistArt))
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("rows").performScrollToNode(hasTestTag("art.order.5"))
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("art.order.0").performClick()
        settle(500)
        assertEquals(listOf("move down"), shell.sheet.items)
        rule.onNodeWithTag("sheet.item.0").performClick()
        settle(500)
        assertEquals(listOf("theaudiodb", "commons", "fanarttv", "discogs", "youtube", "deezer"), sources.order)
        rule.onNodeWithTag("art.order.5").performClick()
        settle(500)
        assertEquals(listOf("move up"), shell.sheet.items)
        rule.onNodeWithTag("sheet.item.0").performClick()
        settle(500)
        assertEquals(listOf("theaudiodb", "commons", "fanarttv", "discogs", "deezer", "youtube"), sources.order)
    }
}
