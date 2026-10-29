package org.sonora.android.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.sonora.android.MainActivity
import org.sonora.android.SonoraApp
import java.io.File

/**
 * Keeps playback alive in the background and gives Android its notification, lock-screen and
 * headset controls. The audio itself comes out of Rust; the player here only mirrors
 * [PlayerController] for the system and forwards its commands back.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val app = application as SonoraApp
        if (app.startupError != null) return
        val player = MirrorPlayer(app.player)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val app = application as SonoraApp
        if (app.startupError != null || !app.player.state.value.playing) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}

@OptIn(UnstableApi::class)
private class MirrorPlayer(private val controller: PlayerController) :
    SimpleBasePlayer(Looper.getMainLooper()) {

    init {
        controller.onChanged = { invalidateState() }
    }

    override fun getState(): State {
        val state = controller.state.value
        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
            )
            .build()
        val builder = State.Builder().setAvailableCommands(commands)
        if (state.queue.isEmpty() || state.index < 0) {
            return builder
                .setPlaybackState(Player.STATE_IDLE)
                .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .build()
        }
        val playlist = state.queue.mapIndexed { i, item ->
            val track = item.track
            val artwork = track.cover?.let { cover ->
                if (cover.startsWith("/")) Uri.fromFile(File(cover)) else Uri.parse(cover)
            }
            val metadata = MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artists)
                .setAlbumTitle(track.album)
                .setArtworkUri(artwork)
                .build()
            val mediaItem = MediaItem.Builder()
                .setMediaId("${item.key}")
                .setMediaMetadata(metadata)
                .build()
            val duration = if (i == state.index && state.durationMs > 0) state.durationMs else track.durationMs.toLong()
            MediaItemData.Builder(item.key)
                .setMediaItem(mediaItem)
                .setDurationUs(if (duration > 0) duration * 1000 else androidx.media3.common.C.TIME_UNSET)
                .build()
        }
        return builder
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(state.index)
            .setPlaybackState(if (state.loading) Player.STATE_BUFFERING else Player.STATE_READY)
            .setPlayWhenReady(state.playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs { controller.state.value.positionNow() }
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) controller.play() else controller.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> controller.next()
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> controller.previous()
            Player.COMMAND_SEEK_TO_MEDIA_ITEM -> controller.jumpTo(mediaItemIndex)
            else -> {
                val current = controller.state.value.index
                if (mediaItemIndex != current && mediaItemIndex >= 0) controller.jumpTo(mediaItemIndex)
                if (positionMs >= 0) controller.seekTo(positionMs)
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        controller.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        controller.onChanged = null
        return Futures.immediateVoidFuture()
    }
}
