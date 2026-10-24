package net.seilent.funecomp

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.seilent.funecomp.artist.ArtCache
import net.seilent.funecomp.proto.LyrLine
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.state.LyricsFetch
import net.seilent.funecomp.state.PhoneKey
import net.seilent.funecomp.state.SettingsMirror
import net.seilent.funecomp.ui.App
import net.seilent.funecomp.ui.BackdropArt
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
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h960dp-420dpi")
class RxNowPlayingTest {
    @get:Rule
    val rule = createComposeRule()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val owner = Owner()
    private val idol = PhoneKey("Idol", "YOASOBI")
    private val synced = (0 until 6).map { LyrLine(it * 10_000L, "rx $it") }
    private var lyrics by mutableStateOf<LyricsFetch.Show>(LyricsFetch.Show.Off)
    private lateinit var c: SimControl

    private fun phone(title: String = "Idol", artist: String = "YOASOBI", name: String? = "Pixel", no: Long? = 3, of: Long? = 12, duration: Long = 213_000, crc: Long? = null) {
        val s = c.device.playback.state
        c.device = c.device.withState(
            s.copy(
                source = Enums.Source.phone, output = Enums.Output.phoneReceiver, track = 0, title = title, artist = artist, artists = listOf(artist),
                album = "THE BOOK 3", durationMs = duration, positionMs = 25_000, playing = false, lyrics = null, lyricsPlain = null, lyricsCrc = null,
                favorite = null, receiver = true, phoneName = name, phoneTrackNo = no, phoneTracks = of, phoneArtCrc = crc,
            ),
            SystemClock.elapsedRealtime(),
        )
    }

    private fun local() {
        val s = c.device.playback.state
        c.device = c.device.withState(s.copy(source = Enums.Source.local, output = Enums.Output.wired, track = 7, title = "track 7", phoneName = null, phoneTrackNo = null, phoneTracks = null, phoneArtCrc = null, receiver = null), SystemClock.elapsedRealtime())
    }

    private fun start(show: LyricsFetch.Show, stored: Boolean = false, seed: Boolean = stored, art: BackdropArt? = null, photo: String? = null): Shell {
        c = SimControl("v1", minor = 15)
        c.mode = stored
        c.phoneMode = seed
        phone()
        photo?.let { c.device = c.device.copy(settings = SettingsMirror(1, "artistPhoto=$it\n")) }
        lyrics = show
        val shell = Shell(Nav(listOf(Page.Home, Page.NowPlaying)))
        owner.registry.currentState = Lifecycle.State.RESUMED
        rule.mainClock.autoAdvance = false
        rule.setUptimeContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                Box(Modifier.requiredSize(411.dp, 960.dp)) {
                    App(c.device, LinkView(Phase.LIVE, "V1", everLive = true), c, shell, art = art, lyrics = lyrics, liveDevice = { c.device })
                }
            }
        }
        settle(1000)
        return shell
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

    private fun exists(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun copy() = LyricsFetch.Show.Phone(idol, true, LyricsFetch.Copy(1, Enums.LyricsKind.synced, synced))

    private val yoasobi = ArtCache.Entry("name:yoasobi", "YOASOBI", null, "deezer", null, "https://cdn-images.dzcdn.net/images/artist/y/1000x1000.jpg", 1, sha = "s")

    private fun longPress(shell: Shell): List<String> {
        rule.onNodeWithTag("np.backdrop").performTouchInput { longClick() }
        settle(500)
        return shell.sheet.items
    }

    @Test
    fun aPhoneTrackDrawsTheArtistPhotoAndALongPressOpensItsSheet() {
        val shell = start(LyricsFetch.Show.Phone(idol, false, null), art = BackdropArt(ImageBitmap(8, 8), null, yoasobi))
        assertTrue(exists("backdrop.photo"))
        assertEquals("block image", longPress(shell).first())
    }

    @Test
    fun artistPhotoOffDrawsNoPhotoOnAPhoneTrack() {
        val shell = start(LyricsFetch.Show.Phone(idol, false, null), art = BackdropArt(ImageBitmap(8, 8), null, yoasobi), photo = "off")
        assertFalse(exists("backdrop.photo"))
        assertFalse("block image" in longPress(shell))
    }

    @Test
    fun artistPhotoOffDrawsNoPhotoOnALocalTrack() {
        val shell = start(LyricsFetch.Show.Phone(idol, false, null), art = BackdropArt(ImageBitmap(8, 8), null, yoasobi), photo = "off")
        local()
        settle(2000)
        assertFalse(exists("backdrop.photo"))
        assertFalse("block image" in longPress(shell))
    }

    private fun localPhoto(photo: String?) {
        val shell = start(LyricsFetch.Show.Phone(idol, false, null), art = BackdropArt(ImageBitmap(8, 8), null, yoasobi), photo = photo)
        local()
        settle(2000)
        assertTrue(exists("backdrop.photo"))
        assertEquals("block image", longPress(shell).first())
    }

    @Test
    fun aLocalTrackDrawsThePhotoWithTheRowOn() = localPhoto("on")

    @Test
    fun aLocalTrackDrawsThePhotoBeforeSettingsArrive() = localPhoto(null)

    @Test
    fun aPhoneTrackDrawsTheBluetoothVariant() {
        start(LyricsFetch.Show.Phone(idol, false, null))
        assertTrue(exists("np.from"))
        rule.onNodeWithText("FROM PIXEL").fetchSemanticsNode()
        rule.onNodeWithText("3 OF 12").fetchSemanticsNode()
        assertTrue(exists("np.art.bluetooth"))
        assertFalse(exists("np.art.image"))
        assertTrue(exists("np.progress"))
        assertFalse(exists("np.seek"))
        assertFalse(exists("np.next.0"))
        assertFalse(exists("np.heart"))
        assertFalse(exists("np.mark"))
    }

    @Test
    fun noNameNoCountAndNoDurationLeaveTheirRowsOut() {
        start(LyricsFetch.Show.Phone(idol, false, null))
        phone(name = null, no = null, of = null, duration = 0)
        settle(100)
        assertFalse(exists("np.from"))
        assertFalse(exists("np.count"))
        assertFalse(exists("np.progress"))
        assertFalse(exists("np.elapsed"))
    }

    @Test
    fun theCoverReplacesTheGlyphOnceItsCrcIsHeld() {
        start(LyricsFetch.Show.Phone(idol, false, null))
        phone(crc = 77)
        settle(100)
        assertTrue(exists("np.art.bluetooth"))
        c.phoneCovers = mapOf(77L to ImageBitmap(8, 8))
        settle(100)
        assertTrue(exists("np.art.image"))
        assertFalse(exists("np.art.bluetooth"))
        phone(crc = 78)
        settle(100)
        assertTrue(exists("np.art.bluetooth"))
    }

    @Test
    fun aPendingPhoneTrackArtTapTogglesOnlyThePhoneMode() {
        val shell = start(LyricsFetch.Show.Phone(idol, null, null))
        rule.onNodeWithTag("np.art").performClick()
        settle(100)
        assertTrue(c.phoneMode)
        assertFalse(c.mode)
        assertEquals(1, shell.toast.shows)
        assertFalse(exists("np.lyrics"))
    }

    @Test
    fun aSyncedPhoneCopyFollowsThePhoneMode() {
        start(copy(), stored = false, seed = true)
        settle(1000)
        assertTrue(exists("np.lyrics"))
        rule.onNodeWithText("rx 2").fetchSemanticsNode()
        assertFalse(c.mode)
    }

    @Test
    fun theMarkShowsWithAPhoneCopyAndTheKeyTurnsThePhoneModeOn() {
        start(copy())
        assertTrue(exists("np.mark"))
        rule.onNodeWithTag("np.mark.hit").performClick()
        settle(1000)
        assertTrue(c.phoneMode)
        assertFalse(c.mode)
        assertTrue(exists("np.lyrics"))
    }

    @Test
    fun aShowForAnotherKeyIsNotDrawn() {
        start(LyricsFetch.Show.Phone(PhoneKey("Other", "YOASOBI"), true, LyricsFetch.Copy(1, Enums.LyricsKind.synced, synced)), seed = true)
        settle(1000)
        assertFalse(exists("np.mark"))
        assertFalse(exists("np.lyrics"))
    }

    @Test
    fun phoneToLocalReadsTheStoredModeAgain() {
        start(copy(), stored = false, seed = true)
        settle(1000)
        assertTrue(exists("np.lyrics"))
        local()
        lyrics = LyricsFetch.Show.Lines(7, LyricsFetch.Copy(1, Enums.LyricsKind.synced, synced))
        c.device = c.device.withState(c.device.playback.state.copy(lyrics = true, lyricsPlain = false, lyricsCrc = 1), SystemClock.elapsedRealtime())
        settle(3000)
        assertFalse(exists("np.lyrics"))
        assertTrue(c.phoneMode)
    }

    @Test
    fun theLyricsTapOnAPhoneCopyOpensTheOffsetSheetInMemory() {
        val shell = start(copy(), seed = true)
        settle(1000)
        rule.onNodeWithTag("np.lyrics").performClick()
        settle(500)
        assertTrue(shell.sheet.shown)
        assertTrue(c.phoneOffsetAsks.isNotEmpty() && c.phoneOffsetAsks.all { it == null })
        assertTrue(shell.offset?.phone == idol)
    }

    @Test
    fun theHeartIsHiddenOnAPhoneTrack() {
        start(copy())
        assertFalse(exists("np.heart"))
    }

    @Test
    fun theMarkIsHiddenWhilePendingAndForAMiss() {
        start(LyricsFetch.Show.Phone(idol, null, null))
        assertFalse(exists("np.mark"))
        lyrics = LyricsFetch.Show.Phone(idol, false, null)
        settle(100)
        assertFalse(exists("np.mark"))
        lyrics = copy()
        settle(100)
        assertTrue(exists("np.mark"))
    }

    @Test
    fun aMissTrackArtTapTogglesOnlyThePhoneMode() {
        val shell = start(LyricsFetch.Show.Phone(idol, false, null))
        rule.onNodeWithTag("np.art").performClick()
        settle(100)
        assertTrue(c.phoneMode)
        assertFalse(c.mode)
        assertEquals(1, shell.toast.shows)
        rule.onNodeWithTag("np.art").performClick()
        settle(100)
        assertFalse(c.phoneMode)
    }

    @Test
    fun localToPhoneTurnsTheViewOffUntilThePhoneKeySettles() {
        start(copy(), stored = true, seed = true)
        local()
        lyrics = LyricsFetch.Show.Lines(7, LyricsFetch.Copy(1, Enums.LyricsKind.synced, synced))
        c.device = c.device.withState(c.device.playback.state.copy(lyrics = true, lyricsPlain = false, lyricsCrc = 1), SystemClock.elapsedRealtime())
        settle(3000)
        assertTrue(exists("np.lyrics"))
        phone()
        lyrics = LyricsFetch.Show.Off
        settle(3000)
        assertFalse(exists("np.lyrics"))
        assertTrue(exists("np.title"))
        lyrics = copy()
        settle(3000)
        assertTrue(exists("np.lyrics"))
    }

    @Test
    fun aPhoneToPhoneChangeTurnsTheViewOffUntilTheNewKeySettles() {
        start(copy(), seed = true)
        settle(1000)
        assertTrue(exists("np.lyrics"))
        phone(title = "Racing")
        settle(2000)
        assertFalse(exists("np.lyrics"))
        assertTrue(exists("np.title"))
        lyrics = LyricsFetch.Show.Phone(PhoneKey("Racing", "YOASOBI"), true, LyricsFetch.Copy(2, Enums.LyricsKind.synced, synced))
        settle(3000)
        assertTrue(exists("np.lyrics"))
        assertTrue(c.phoneMode)
    }

    @Test
    fun aSourceChangeHoldsTheOldContentThroughItsOutLeg() {
        start(LyricsFetch.Show.Phone(idol, false, null))
        phone(crc = 77)
        c.phoneCovers = mapOf(77L to ImageBitmap(8, 8))
        settle(100)
        assertTrue(exists("np.art.image"))
        local()
        c.phoneCovers = emptyMap()
        settle(150)
        assertTrue(exists("np.from"))
        assertTrue(exists("np.art.image"))
        settle(400)
        assertFalse(exists("np.from"))
        rule.onNodeWithText("track 7", ignoreCase = true).fetchSemanticsNode()
        phone()
        settle(150)
        assertFalse(exists("np.from"))
        settle(300)
        assertTrue(exists("np.from"))
        assertTrue(exists("np.art.bluetooth"))
    }

    @Test
    fun aBackDuringTheOutLegClosesNowPlayingAndKeepsBothModes() {
        val shell = start(copy(), stored = true, seed = true)
        settle(1000)
        assertTrue(exists("np.lyrics"))
        local()
        settle(100)
        assertTrue(exists("np.lyrics"))
        rule.onNodeWithTag("back").performClick()
        settle(1000)
        assertEquals(Page.Home, shell.nav.top)
        assertTrue(c.mode)
        assertTrue(c.phoneMode)
    }

    @Test
    fun aPauseKeepsTheKeyAndTheView() {
        start(copy(), seed = true)
        settle(1000)
        assertTrue(exists("np.lyrics"))
        c.device = c.device.withState(c.device.playback.state.copy(playing = false, positionMs = 30_000), SystemClock.elapsedRealtime())
        settle(100)
        assertTrue(exists("np.lyrics"))
        rule.onNodeWithText("rx 3").fetchSemanticsNode()
    }

    @Test
    fun aLateArtistAndAlbumUpdateTheRows() {
        start(LyricsFetch.Show.Phone(idol, false, null))
        phone(artist = "")
        settle(100)
        val s = c.device.playback.state
        c.device = c.device.withState(s.copy(artist = "Creepy Nuts", artists = listOf("Creepy Nuts"), album = "Late Album"), SystemClock.elapsedRealtime())
        settle(100)
        rule.onNodeWithText("Creepy Nuts", ignoreCase = true).fetchSemanticsNode()
        rule.onNodeWithText("Late Album", ignoreCase = true).fetchSemanticsNode()
    }
}
