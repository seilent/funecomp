package net.seilent.funecomp

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import net.seilent.funecomp.proto.LyrLine
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Tok
import net.seilent.funecomp.sim.Track
import net.seilent.funecomp.state.LyricsFetch
import net.seilent.funecomp.state.PhoneKey
import net.seilent.funecomp.state.SettingsMirror
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.BackdropArt
import net.seilent.funecomp.ui.CoverBlur
import net.seilent.funecomp.ui.LinkView
import net.seilent.funecomp.ui.LocalBackdropProbe
import net.seilent.funecomp.ui.Nav
import net.seilent.funecomp.ui.Page
import net.seilent.funecomp.ui.Phase
import net.seilent.funecomp.ui.PickFor
import net.seilent.funecomp.ui.Shell
import net.seilent.funecomp.ui.Ui
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.awt.image.BufferedImage
import java.io.File
import java.time.Duration
import javax.imageio.ImageIO

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-420dpi")
class LyricsBackgroundTest {
    @get:Rule
    val rule = createComposeRule()

    private val lib = net.seilent.funecomp.sim.Library.synthetic()
    private var lyrics by mutableStateOf<LyricsFetch.Show>(LyricsFetch.Show.Off)
    private var size by mutableStateOf(SIZES[1])
    private lateinit var c: SimControl
    private lateinit var shell: Shell
    private val alphas = HashMap<BackdropArt, Float>()
    private val covers = LinkedHashSet<BackdropArt>()
    private val dirs = ArrayList<File>()
    private val photo = BackdropArt(solid(0xFF0000), null, FADE_ENTRY)
    private val lines = (0 until 8).map { LyrLine(30_000L + it * 4_000L, "line $it") }

    @After
    fun tearDown() {
        dirs.forEach { it.deleteRecursively() }
    }

    private fun solid(rgb: Int, w: Int = 64, h: Int = 64): ImageBitmap = Bitmap.createBitmap(IntArray(w * h) { rgb or 0xFF000000.toInt() }, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()

    private fun dir(paint: (Int, Int) -> Int = { _, _ -> 0x808080 }): File {
        val d = kotlin.io.path.createTempDirectory("lyrbg").toFile()
        dirs += d
        lib.tracks.mapNotNull { lib.albumKey(it.id) }.distinct().forEachIndexed { i, key ->
            val img = BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until 512) for (x in 0 until 512) img.setRGB(x, y, if (i == 0) paint(x, y) else 0x204060 + i * 0x101010)
            ImageIO.write(img, "jpg", File(d, "$key.jpg"))
        }
        return d
    }

    private fun play(t: Track) {
        val s = c.device.playback.state
        c.device = c.device.withState(
            s.copy(
                source = Enums.Source.local, track = t.id, title = t.title, artist = t.artist, artists = t.artists, album = t.album, durationMs = t.durationMs,
                positionMs = 31_000, playing = false, lyrics = true, lyricsPlain = false, lyricsCrc = 1,
            ),
            SystemClock.elapsedRealtime(),
        )
        lyrics = LyricsFetch.Show.Lines(t.id, LyricsFetch.Copy(1, Enums.LyricsKind.synced, lines))
    }

    private fun start(mode: Boolean = true, photoOn: Boolean = true, art: BackdropArt? = photo, covers: File? = dir(), hold: Boolean = false, minor: Int? = null) {
        c = if (minor == null) SimControl("ap80", artDir = covers) else SimControl("ap80", artDir = covers, minor = minor)
        c.mode = mode
        c.holdArt = hold
        play(lib.tracks[0])
        if (!photoOn) c.device = c.device.copy(settings = SettingsMirror(1, "artistPhoto=off\n"))
        shell = Shell(Nav(listOf(Page.Home, Page.NowPlaying)))
        rule.mainClock.autoAdvance = false
        rule.setUptimeContent {
            CompositionLocalProvider(LocalBackdropProbe provides { a, v -> probe(a, v) }) {
                Box(Modifier.requiredSize(size.w.dp, size.h.dp).testTag("frame")) {
                    App(c.device, LinkView(Phase.LIVE, "AP80", everLive = true), c, shell, art = art, lyrics = lyrics, insets = size.bars, liveDevice = { c.device })
                }
            }
        }
        settle(3000)
    }

    private fun probe(a: BackdropArt, v: Float) {
        alphas[a] = v
        if (a.pic != null) covers += a
    }

    private fun settle(ms: Long) {
        rule.waitForIdle()
        var left = ms
        while (left > 0) {
            val step = minOf(16L, left)
            val target = SystemClock.elapsedRealtime() + step
            rule.mainClock.advanceTimeBy(step)
            val now = SystemClock.elapsedRealtime()
            if (now < target) ShadowSystemClock.advanceBy(Duration.ofMillis(target - now))
            left -= step
        }
        rule.waitForIdle()
    }

    private fun count(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

    private fun exists(tag: String) = count(tag) > 0

    private fun cover() = covers.lastOrNull()

    private fun enter() {
        rule.onNodeWithTag("np.art").performClick()
    }

    private fun leave() {
        rule.onNodeWithTag("back").performClick()
    }

    @Test
    fun theLyricsViewDrawsTheSongArtAndTheNormalViewTheArtistPhoto() {
        start(mode = false)
        assertTrue(exists("backdrop.photo"))
        assertFalse(exists("backdrop.cover"))
        enter()
        settle(2500)
        assertTrue(exists("backdrop.cover"))
        assertFalse(exists("backdrop.photo"))
        assertTrue(exists("np.shade"))
        leave()
        settle(2500)
        assertTrue(exists("backdrop.photo"))
        assertFalse(exists("backdrop.cover"))
    }

    @Test
    fun noSongArtDrawsTheArtistPhoto() {
        start(covers = null)
        assertTrue(exists("np.lyrics"))
        assertTrue(exists("backdrop.photo"))
        assertFalse(exists("backdrop.cover"))
        assertTrue(exists("np.shade"))
    }

    @Test
    fun neitherDrawsNoBackdropAndNoShade() {
        start(covers = null, art = null)
        assertTrue(exists("np.lyrics"))
        assertFalse(exists("backdrop"))
        assertFalse(exists("np.shade"))
    }

    @Test
    fun artistPhotoOffKeepsTheSongArt() {
        start(photoOn = false)
        assertTrue(exists("backdrop.cover"))
        assertFalse(exists("backdrop.photo"))
        assertTrue(exists("np.shade"))
    }

    @Test
    fun artistPhotoOffWithNoSongArtIsPlain() {
        start(photoOn = false, covers = null)
        assertTrue(exists("np.lyrics"))
        assertFalse(exists("backdrop"))
        assertFalse(exists("np.shade"))
    }

    @Test
    fun aLeaveWithArtistPhotoOffRampsTheShadeAndFadesTheCover() {
        start(photoOn = false)
        leave()
        settle(100)
        assertTrue("the shade ramps", exists("np.shade"))
        assertTrue("the cover fades", exists("backdrop.cover"))
        settle(Tok.motion_variantFade.toLong() * 2 + 100)
        assertFalse("the shade is down", exists("np.shade"))
        assertTrue("the cover still fades", exists("backdrop.cover"))
        settle(Tok.motion_backdropFade.toLong())
        assertFalse(exists("backdrop"))
    }

    @Test
    fun enteringAndLeavingCrossfadeOverTwoSeconds() {
        start(mode = false)
        enter()
        settle(1000)
        val k = cover()!!
        assertEquals("cover at +1000", 0.5f, alphas.getValue(k), 0.05f)
        assertEquals("photo at +1000", 0.5f, alphas.getValue(photo), 0.05f)
        settle(1100)
        assertEquals(1f, alphas.getValue(k), 0.001f)
        assertFalse(exists("backdrop.photo"))
        leave()
        settle(1000)
        assertEquals("cover at +1000 out", 0.5f, alphas.getValue(k), 0.05f)
        assertEquals("photo at +1000 out", 0.5f, alphas.getValue(photo), 0.05f)
        settle(1100)
        assertFalse(exists("backdrop.cover"))
        assertEquals(1f, alphas.getValue(photo), 0.001f)
    }

    @Test
    fun aSameAlbumTrackChangeKeepsTheCoverWithNoFade() {
        start()
        val k = cover()!!
        c.holdArt = true
        play(lib.tracks[1])
        repeat(40) {
            settle(64)
            assertFalse("held: no photo", exists("backdrop.photo"))
            assertEquals(1, count("backdrop.cover"))
        }
        c.releaseArt()
        repeat(40) {
            settle(64)
            assertFalse("released: no photo", exists("backdrop.photo"))
            assertEquals(1, count("backdrop.cover"))
        }
        assertSame(k, cover())
        assertEquals(1, covers.size)
    }

    @Test
    fun aDifferentPictureShowsThePhotoAtOnceThenFadesItsCoverIn() {
        start()
        val first = cover()!!
        c.holdArt = true
        play(lib.tracks[5])
        settle(64)
        assertTrue("the photo at once", exists("backdrop.photo"))
        settle(Tok.motion_backdropFade.toLong() + 100)
        assertTrue(exists("backdrop.photo"))
        assertFalse("never the previous cover", exists("backdrop.cover"))
        c.releaseArt()
        settle(1000)
        val second = cover()!!
        assertNotSame(first, second)
        assertEquals(0.5f, alphas.getValue(second), 0.06f)
        settle(1100)
        assertFalse(exists("backdrop.photo"))
        assertEquals(1f, alphas.getValue(second), 0.001f)
    }

    @Test
    fun aLongPressOnTheCoverOpensNothingAndItsReleaseIsTheLyricsTap() {
        start(minor = 15)
        assertTrue(exists("backdrop.cover"))
        assertFalse("no backdrop long press over the cover", exists("np.backdrop"))
        rule.onNodeWithTag("np.lyrics").performTouchInput { longClick() }
        settle(500)
        assertTrue(shell.sheet.shown)
        assertEquals("lyrics", shell.sheet.title)
    }

    @Test
    fun aLongPressOnTheArtistPhotoFallbackOpensItsSheet() {
        start(covers = null)
        rule.onNodeWithTag("np.lyrics").performTouchInput { longClick() }
        settle(500)
        assertTrue(shell.sheet.shown)
        assertEquals("block image", shell.sheet.items.first())
    }

    @Test
    fun aCoverLandingClosesAnOpenArtSheet() {
        start(hold = true)
        assertTrue(exists("backdrop.photo"))
        rule.onNodeWithTag("np.backdrop").performTouchInput { longClick() }
        settle(500)
        assertEquals("block image", shell.sheet.items.first())
        c.releaseArt()
        settle(500)
        assertTrue(exists("backdrop.cover"))
        assertFalse(shell.sheet.shown)
    }

    @Test
    fun aCoverLandingLeavesAPickSheetTheDeviceAskedFor() {
        start(hold = true)
        assertTrue(exists("backdrop.photo"))
        shell.pick = PickFor("Utada Hikaru")
        settle(500)
        assertTrue(shell.sheet.shown)
        assertEquals("Utada Hikaru", shell.sheet.title)
        c.releaseArt()
        settle(500)
        assertTrue(exists("backdrop.cover"))
        assertTrue("the asked pick sheet stays", shell.sheet.shown)
        assertEquals("Utada Hikaru", shell.sheet.title)
    }

    @Test
    fun aWindowSizeChangeSwapsTheGridWithNoFade() {
        start()
        val k = cover()!!
        size = SIZES[3]
        repeat(40) {
            settle(64)
            assertFalse(exists("backdrop.photo"))
            assertEquals(1, count("backdrop.cover"))
        }
        val n = cover()!!
        assertNotSame(k, n)
        assertEquals(1f, alphas.getValue(n), 0.001f)
    }

    @Test
    fun theReceiverPageDrawsThePhoneCover() {
        start(covers = null)
        c.phoneCovers = mapOf(77L to solid(0x2050A0))
        c.phoneMode = true
        val s = c.device.playback.state
        c.device = c.device.withState(
            s.copy(
                source = Enums.Source.phone, output = Enums.Output.phoneReceiver, track = 0, title = "Idol", artist = "YOASOBI", artists = listOf("YOASOBI"),
                album = "THE BOOK 3", durationMs = 213_000, positionMs = 25_000, playing = false, lyrics = null, lyricsPlain = null, lyricsCrc = null,
                receiver = true, phoneName = "Pixel", phoneTrackNo = 3, phoneTracks = 12, phoneArtCrc = 77,
            ),
            SystemClock.elapsedRealtime(),
        )
        lyrics = LyricsFetch.Show.Phone(PhoneKey("Idol", "YOASOBI"), true, LyricsFetch.Copy(1, Enums.LyricsKind.synced, lines))
        settle(4000)
        assertTrue(exists("np.lyrics"))
        assertTrue(exists("backdrop.cover"))
    }

    private fun median(img: BufferedImage, y: Int, x0: Int = 0, x1: Int = img.width): Int {
        val v = (x0 until x1).map { img.getRGB(it, y) shr 16 and 0xFF }.sorted()
        return v[v.size / 2]
    }

    @Test
    fun aWhiteCoverReadsTheShadeAtTheTopAndBottomOfEveryWindow() {
        start(covers = dir { _, _ -> 0xFFFFFF }, art = null)
        val bg = (Tok.color_bg shr 16 and 0xFF).toFloat() * Tok.scrim_sheet_alpha
        val wantTop = 255f * (1f - Tok.backdrop_stop0_alpha) * (1f - Tok.scrim_sheet_alpha) + bg
        val wantBottom = 255f * (1f - Tok.backdrop_stop2_alpha) * (1f - Tok.scrim_sheet_alpha) + bg
        val want = mapOf("360x640" to 4, "411x960" to 5, "cover474x419" to 5, "600x960" to 7, "960x600" to 7, "flip960x411" to 5, "1280x800" to 10, "sn411x438" to 5, "wm361x343" to 4)
        for (s in SIZES) {
            size = s
            settle(3000)
            val np = rule.onNodeWithTag("np").fetchSemanticsNode().boundsInRoot
            assertEquals("${s.name} radius", want.getValue(s.name), CoverBlur.radius(CoverBlur.sigma(Ui.of(np.width, np.height).u)))
            val img = rule.onNodeWithTag("frame").pixels()
            System.gc()
            val top = median(img, 0, img.width / 4, img.width * 3 / 4)
            val bottom = median(img, img.height - 1, img.width / 4, img.width * 3 / 4)
            assertEquals("${s.name} top", wantTop, top.toFloat(), 2f)
            assertEquals("${s.name} bottom", wantBottom, bottom.toFloat(), 2f)
        }
    }

    @Test
    fun aSplitCoverRisesOverAboutTwoAndAHalfSigma() {
        start(covers = dir { x, _ -> if (x < 256) 0x000000 else 0xFFFFFF }, art = null)
        for (s in SIZES) {
            size = s
            settle(3000)
            val np = rule.onNodeWithTag("np").fetchSemanticsNode().boundsInRoot
            val sigma = Tok.backdrop_coverSigma * Ui.of(np.width, np.height).u
            val img = rule.onNodeWithTag("frame").pixels()
            System.gc()
            val row = (img.width / 4 until img.width * 3 / 4).map { img.getRGB(it, 0) shr 16 and 0xFF }
            val hi = row.subList(row.size * 7 / 8, row.size).average()
            val lo = row.subList(0, row.size / 8).average()
            val x10 = row.indexOfFirst { it >= lo + (hi - lo) * 0.1 }
            val x90 = row.indexOfFirst { it >= lo + (hi - lo) * 0.9 }
            val rise = (x90 - x10).toFloat()
            assertEquals("${s.name} centred", row.size / 2f, (x10 + x90) / 2f, row.size * 0.04f)
            assertEquals("${s.name} rise $rise for sigma $sigma", 2.56f * sigma, rise, 2.56f * sigma * 0.2f)
        }
    }
}
