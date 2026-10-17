package net.seilent.funecomp

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import net.seilent.funecomp.proto.State
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.state.DeviceState
import net.seilent.funecomp.state.MediaGate
import net.seilent.funecomp.ui.Control

class MediaLink(private val context: Context, private val control: Control, private val stop: () -> Unit) {
    private val gate = MediaGate()
    private var session: MediaSession? = null
    private var shown: List<Any?>? = null

    val active: Boolean get() = gate.mode != MediaGate.Mode.OFF

    val token: MediaSession.Token? get() = session?.sessionToken

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
            session = it
        }
        val st = device.playback.state
        val meta = listOf(st.track, st.title, artist(st), st.album, st.durationMs)
        if (meta != shown) {
            shown = meta
            s.setMetadata(metadata(st))
        }
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
        private const val PREFS = "media"
        private const val CONTROLS = "controls"
        const val ACTIONS = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO

        fun artist(s: State) = s.artist ?: s.artists.joinToString(", ")

        fun controls(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CONTROLS, true)

        fun controls(c: Context, on: Boolean) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(CONTROLS, on).apply()

        fun metadata(st: State): MediaMetadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, st.title ?: "")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist(st))
            .putString(MediaMetadata.METADATA_KEY_ALBUM, st.album ?: "")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, st.durationMs ?: -1)
            .build()

        fun state(device: DeviceState, playing: Boolean, now: Long): PlaybackState {
            val position = device.positionMs(now)
            return PlaybackState.Builder()
                .setActions(ACTIONS)
                .addCustomAction(PlaybackState.CustomAction.Builder(STOP, STOP, android.R.drawable.ic_menu_close_clear_cancel).build())
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, position, if (playing) 1f else 0f, now)
                .build()
        }
    }
}
