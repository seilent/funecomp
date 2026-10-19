package net.seilent.funecomp

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.graphics.Bitmap
import android.media.VolumeProvider
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.state.MediaGate
import net.seilent.funecomp.state.RemoteVolume
import net.seilent.funecomp.ui.Control

class MediaLink(
    private val context: Context,
    private val control: Control,
    private val art: (Long, String?) -> Bitmap? = { _, _ -> null },
    private val open: () -> PendingIntent? = { null },
    private val stop: () -> Unit,
) {
    private val gate = MediaGate()
    private var session: MediaSession? = null
    private var shown: List<Any?>? = null
    internal var volume: VolumeProvider? = null
        private set
    private var volumeKey: List<Any?>? = null
    private var sending = false
    private var pending: Int? = null

    val active: Boolean get() = gate.mode != MediaGate.Mode.OFF

    val token: MediaSession.Token? get() = session?.sessionToken

    private val main = Handler(Looper.getMainLooper())

    fun live(device: DeviceState, setting: Boolean, sinkToDap: Boolean) {
        gate.live(device.playback.state, setting, sinkToDap)
        apply(device)
    }

    fun closed(device: DeviceState) {
        gate.closed()
        apply(device)
    }

    fun off() {
        gate.off()
        release()
    }

    fun notification(channel: String, open: PendingIntent, device: DeviceState): Notification? {
        val s = session ?: return null
        val st = device.playback.state
        return Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(st.title ?: "")
            .setContentText(artist(st))
            .setContentIntent(open)
            .setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken))
            .setOngoing(true)
            .build()
    }

    private fun apply(device: DeviceState) {
        if (gate.mode == MediaGate.Mode.OFF) {
            release()
            return
        }
        val s = session ?: MediaSession(context, TAG).also {
            it.setCallback(callback)
            open()?.let { p -> it.setSessionActivity(p) }
            session = it
        }
        val st = device.playback.state
        val image = st.track?.let { art(it, st.album) }
        val meta = listOf(st.track, st.title, artist(st), st.album, st.durationMs, image)
        if (meta != shown) {
            shown = meta
            s.setMetadata(metadata(st, image))
        }
        remote(s, st)
        s.setPlaybackState(state(device, gate.mode == MediaGate.Mode.ACTIVE && st.playing == true, SystemClock.elapsedRealtime()))
        if (!s.isActive) s.isActive = true
    }

    private fun release() {
        session?.let {
            it.isActive = false
            it.release()
        }
        session = null
        shown = null
        volume = null
        volumeKey = null
        pending = null
    }

    private fun remote(s: MediaSession, st: State) {
        val spec = RemoteVolume.spec(st)
        val key = listOf(spec.absolute, spec.max, st.volumeStepPct, st.maxVolumePct)
        val v = volume
        if (v == null || key != volumeKey) {
            val p = provider(spec, st)
            volume = p
            volumeKey = key
            s.setPlaybackToRemote(p)
        } else if (v.currentVolume != spec.current && !sending) {
            v.currentVolume = spec.current
        }
    }

    private fun provider(spec: RemoteVolume.Spec, st: State): VolumeProvider {
        val type = if (spec.absolute) VolumeProvider.VOLUME_CONTROL_ABSOLUTE else VolumeProvider.VOLUME_CONTROL_RELATIVE
        return object : VolumeProvider(type, spec.max, spec.current) {
            override fun onAdjustVolume(direction: Int) {
                if (direction == 0) return
                control.volume(if (direction > 0) 1 else -1)
            }

            override fun onSetVolumeTo(v: Int) {
                val pct = RemoteVolume.percent(v, st) ?: return
                currentVolume = v.coerceIn(0, spec.max)
                send(pct)
            }
        }
    }

    private fun send(pct: Int) {
        if (sending) {
            pending = pct
            return
        }
        sending = true
        control.volumePercent(pct) {
            main.post {
                sending = false
                pending?.let {
                    pending = null
                    send(it)
                }
            }
        }
    }

    internal val callback = object : MediaSession.Callback() {
        override fun onPlay() = control.transport(Enums.TransportCmd.play)

        override fun onPause() = control.transport(Enums.TransportCmd.pause)

        override fun onSkipToNext() = control.transport(Enums.TransportCmd.next)

        override fun onSkipToPrevious() = control.transport(Enums.TransportCmd.prev)

        override fun onSeekTo(pos: Long) = control.transport(Enums.TransportCmd.seek, seekMs = pos)

        override fun onCustomAction(action: String, extras: android.os.Bundle?) {
            if (action == STOP) stop()
        }
    }

    companion object {
        const val TAG = "funecomp"
        const val STOP = "stop"
        const val ART_PX = 320
        private const val PREFS = "media"
        private const val CONTROLS = "controls"
        const val ACTIONS = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO

        fun artist(s: State) = s.artist ?: s.artists.joinToString(", ")

        fun controls(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CONTROLS, true)

        fun controls(c: Context, on: Boolean) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(CONTROLS, on).apply()

        fun metadata(st: State, art: Bitmap? = null): MediaMetadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, st.title ?: "")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist(st))
            .putString(MediaMetadata.METADATA_KEY_ALBUM, st.album ?: "")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, st.durationMs ?: -1)
            .apply { art?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
            .build()

        fun state(device: DeviceState, playing: Boolean, now: Long): PlaybackState {
            val position = device.positionMs(now)
            val held = playing && device.playback.state.positionHeld == true
            val state = when {
                held -> PlaybackState.STATE_BUFFERING
                playing -> PlaybackState.STATE_PLAYING
                else -> PlaybackState.STATE_PAUSED
            }
            return PlaybackState.Builder()
                .setActions(ACTIONS)
                .addCustomAction(PlaybackState.CustomAction.Builder(STOP, STOP, android.R.drawable.ic_menu_close_clear_cancel).build())
                .setState(state, position, if (playing && !held) 1f else 0f, now)
                .build()
        }
    }
}
