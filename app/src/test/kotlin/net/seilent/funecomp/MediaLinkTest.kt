package net.seilent.funecomp

import android.app.PendingIntent
import android.content.Intent
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.PlaybackState
import android.os.Looper
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.state.Playback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaLinkTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val sim = SimControl("ap80")
    private var stops = 0
    private val link by lazy { MediaLink(app, sim) { stops++ } }

    private val st = State(
        source = Enums.Source.local, track = 7, title = "Airbag", artist = "Radiohead", album = "OK Computer",
        durationMs = 284_000, positionMs = 61_000, sampleMs = 5_000, sentMs = 5_040, playing = true,
    )

    private fun device(s: State = st, at: Long = 10_000) = DeviceState(playback = Playback(s, at), rttMs = 20)

    @Test
    fun metadataAndPlaybackState() {
        link.live(device(), setting = true, sinkToDap = false)
        assertTrue(link.active)
        assertNotNull(link.token)
        val m = MediaLink.metadata(st)
        assertEquals("Airbag", m.getString(MediaMetadata.METADATA_KEY_TITLE))
        assertEquals("Radiohead", m.getString(MediaMetadata.METADATA_KEY_ARTIST))
        assertEquals("OK Computer", m.getString(MediaMetadata.METADATA_KEY_ALBUM))
        assertEquals(284_000L, m.getLong(MediaMetadata.METADATA_KEY_DURATION))
        val p = MediaLink.state(device(), playing = true, now = 10_000)
        assertEquals(PlaybackState.STATE_PLAYING, p.state)
        assertEquals(1f, p.playbackSpeed)
        assertEquals(MediaLink.ACTIONS, p.actions)
        assertEquals(listOf("stop"), p.customActions.map { it.action })
    }

    @Test
    fun positionMapsThroughSentMs() {
        val p = MediaLink.state(device(at = 10_000), playing = true, now = 12_000)
        assertEquals(61_000L + 40 + 10 + 2_000, p.position)
        assertEquals(12_000L, p.lastPositionUpdateTime)
        val paused = MediaLink.state(device(st.copy(playing = false), at = 10_000), playing = false, now = 12_000)
        assertEquals(PlaybackState.STATE_PAUSED, paused.state)
        assertEquals(61_000L, paused.position)
        assertEquals(0f, paused.playbackSpeed)
    }

    @Test
    fun callbacksSendTransport() {
        link.live(device(), true, false)
        val t = link.callback
        t.onPause()
        t.onPlay()
        t.onSkipToNext()
        t.onSkipToPrevious()
        t.onSeekTo(30_000)
        assertEquals(5, sim.sent.count { it == "TRANSPORT" })
        t.onCustomAction("other", null)
        assertEquals(0, stops)
        t.onCustomAction("stop", null)
        assertEquals(1, stops)
    }

    @Test
    fun dropHoldsPausedThenReleases() {
        link.live(device(), true, false)
        link.closed(device())
        assertTrue(link.active)
        assertNotNull(link.token)
        link.live(device(), true, false)
        link.closed(device())
        link.closed(device())
        assertFalse(link.active)
        assertNull(link.token)
    }

    @Test
    fun inactiveRulesReleaseTheSession() {
        link.live(device(st.copy(source = Enums.Source.phone)), true, false)
        assertNull(link.token)
        link.live(device(), true, sinkToDap = true)
        assertNull(link.token)
        link.live(device(), setting = false, sinkToDap = false)
        assertNull(link.token)
        link.live(device(), true, false)
        assertNotNull(link.token)
        link.off()
        assertNull(link.token)
    }

    @Test
    fun notificationOnlyWhileActive() {
        val open = PendingIntent.getActivity(app, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
        assertNull(link.notification("media", open, device()))
        link.live(device(), true, false)
        val n = link.notification("media", open, device())!!
        assertEquals("media", n.channelId)
        assertEquals("Airbag", n.extras.getString("android.title"))
        assertEquals("Radiohead", n.extras.getString("android.text"))
    }

    private val v12 = st.copy(volume = 123, volumePct = 4, volumeStepPct = 2, maxVolumePct = 5)

    @Test
    fun absoluteVolumeFromThe12Tags() {
        link.live(device(v12), true, false)
        val p = link.volume!!
        assertEquals(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, p.volumeControl)
        assertEquals(3, p.maxVolume)
        assertEquals(2, p.currentVolume)
        p.onSetVolumeTo(3)
        assertEquals(listOf(5), sim.percents)
        shadowOf(Looper.getMainLooper()).idle()
        p.onSetVolumeTo(1)
        assertEquals(listOf(5, 2), sim.percents)
        p.onAdjustVolume(0)
        assertEquals(0, sim.sent.count { it == "VOLUME" } - 2)
        p.onAdjustVolume(1)
        assertEquals(3, sim.sent.count { it == "VOLUME" })
    }

    @Test
    fun dragsCoalesceToTheLastValue() {
        link.live(device(v12), true, false)
        sim.hold = true
        val p = link.volume!!
        p.onSetVolumeTo(1)
        p.onSetVolumeTo(2)
        p.onSetVolumeTo(3)
        assertEquals(listOf(2), sim.percents)
        sim.held!!()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(2, 5), sim.percents)
    }

    @Test
    fun relativeWithoutThe12TagsAndRebuildOnAStepChange() {
        link.live(device(), true, false)
        assertEquals(VolumeProvider.VOLUME_CONTROL_RELATIVE, link.volume!!.volumeControl)
        link.live(device(v12), true, false)
        val a = link.volume!!
        assertEquals(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, a.volumeControl)
        link.live(device(v12.copy(volumePct = 2)), true, false)
        assertTrue(a === link.volume)
        assertEquals(1, a.currentVolume)
        link.live(device(v12.copy(volumeStepPct = 1)), true, false)
        assertEquals(5, link.volume!!.maxVolume)
    }
}
