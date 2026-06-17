package com.smgray.easypod.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.smgray.easypod.data.QueueEpisodeSummary
import com.smgray.easypod.data.PlaybackStateEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackUiState(
    val connected: Boolean = false,
    val episodeId: String? = null,
    val title: String? = null,
    val feedTitle: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playbackSpeed: Float = 1f,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val isCasting: Boolean = false,
)

class PlaybackConnection(context: Context) : Player.Listener {
    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val state = MutableStateFlow(PlaybackUiState())
    private var controller: MediaController? = null
    private var pendingQueue: Pair<List<QueueEpisodeSummary>, String>? = null

    val uiState: StateFlow<PlaybackUiState> = state.asStateFlow()

    init {
        val token = SessionToken(
            applicationContext,
            ComponentName(applicationContext, PlaybackService::class.java),
        )
        val future = MediaController.Builder(applicationContext, token).buildAsync()
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { connectedController ->
                        controller = connectedController
                        connectedController.addListener(this)
                        updateState()
                        pendingQueue?.let { (queue, episodeId) ->
                            pendingQueue = null
                            playQueue(queue, episodeId)
                        }
                    }
            },
            ContextCompat.getMainExecutor(applicationContext),
        )
        scope.launch {
            while (isActive) {
                delay(1_000)
                updateState()
            }
        }
    }

    fun playQueue(queue: List<QueueEpisodeSummary>, episodeId: String) {
        val activeController = controller
        if (activeController == null) {
            pendingQueue = queue to episodeId
            return
        }
        val playable = queue.mapNotNull { queued ->
            queued.toMediaItem()?.let { queued to it }
        }
        val index = playable.indexOfFirst { it.first.id == episodeId }
        if (index < 0) return
        activeController.setMediaItems(
            playable.map { it.second },
            index,
            playable[index].first.positionMs,
        )
        activeController.prepare()
        activeController.play()
        updateState()
    }

    fun syncQueue(queue: List<QueueEpisodeSummary>) {
        val activeController = controller ?: return
        val currentId = activeController.currentMediaItem?.mediaId
        val currentPosition = activeController.currentPosition
        val wasPlaying = activeController.isPlaying
        val playable = queue.mapNotNull { queued ->
            queued.toMediaItem()?.let { queued to it }
        }
        if (playable.isEmpty()) {
            activeController.clearMediaItems()
            updateState()
            return
        }
        val index = currentId
            ?.let { id -> playable.indexOfFirst { it.first.id == id } }
            ?.takeIf { it >= 0 }
            ?: 0
        val position = if (playable[index].first.id == currentId) {
            currentPosition
        } else {
            playable[index].first.positionMs
        }
        activeController.setMediaItems(playable.map { it.second }, index, position)
        activeController.prepare()
        if (wasPlaying) activeController.play()
        updateState()
    }

    fun restoreQueue(
        queue: List<QueueEpisodeSummary>,
        playbackState: PlaybackStateEntity?,
    ) {
        val activeController = controller ?: return
        val playable = queue.mapNotNull { queued ->
            queued.toMediaItem()?.let { queued to it }
        }
        if (playable.isEmpty()) {
            activeController.clearMediaItems()
            updateState()
            return
        }
        val index = playbackState?.currentEpisodeId
            ?.let { episodeId ->
                playable.indexOfFirst { it.first.id == episodeId }
            }
            ?.takeIf { it >= 0 }
            ?: 0
        val position = if (
            playable[index].first.id == playbackState?.currentEpisodeId
        ) {
            playbackState.positionMs
        } else {
            playable[index].first.positionMs
        }
        activeController.pause()
        activeController.setMediaItems(
            playable.map { it.second },
            index,
            position.coerceAtLeast(0),
        )
        activeController.setPlaybackSpeed(
            playbackState?.playbackSpeed?.coerceIn(0.5f, 3f) ?: 1f,
        )
        activeController.prepare()
        updateState()
    }

    fun togglePlayPause() {
        controller?.let {
            if (it.isPlaying) it.pause() else it.play()
        }
    }

    fun skipNext() {
        controller?.let {
            if (it.hasNextMediaItem()) it.seekToNextMediaItem()
        }
    }

    fun skipPrevious() {
        controller?.let {
            if (it.currentPosition > RESTART_THRESHOLD_MS) {
                it.seekTo(0)
            } else if (it.hasPreviousMediaItem()) {
                it.seekToPreviousMediaItem()
            } else {
                it.seekTo(0)
            }
        }
    }

    fun seekBy(offsetMs: Long) {
        controller?.let {
            val duration = it.duration.takeIf { value -> value > 0 } ?: Long.MAX_VALUE
            it.seekTo((it.currentPosition + offsetMs).coerceIn(0L, duration))
        }
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun setPlaybackSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed.coerceIn(0.5f, 3f))
    }

    fun setSleepTimer(minutes: Int) {
        applicationContext.startService(
            Intent(applicationContext, PlaybackService::class.java)
                .setAction(PlaybackService.ACTION_SET_SLEEP_TIMER)
                .putExtra(
                    PlaybackService.EXTRA_SLEEP_DURATION_MS,
                    minutes.coerceAtLeast(1) * 60_000L,
                ),
        )
    }

    fun cancelSleepTimer() {
        applicationContext.startService(
            Intent(applicationContext, PlaybackService::class.java)
                .setAction(PlaybackService.ACTION_CANCEL_SLEEP_TIMER),
        )
    }

    override fun onEvents(player: Player, events: Player.Events) {
        updateState()
    }

    private fun updateState() {
        val activeController = controller
        val metadata = activeController?.mediaMetadata
        state.value = PlaybackUiState(
            connected = activeController != null,
            episodeId = activeController?.currentMediaItem?.mediaId,
            title = metadata?.title?.toString(),
            feedTitle = metadata?.artist?.toString(),
            isPlaying = activeController?.isPlaying == true,
            positionMs = activeController?.currentPosition?.coerceAtLeast(0L) ?: 0L,
            durationMs = activeController?.duration?.takeIf { it > 0 } ?: 0L,
            playbackSpeed = activeController?.playbackParameters?.speed ?: 1f,
            hasPrevious = activeController?.hasPreviousMediaItem() == true,
            hasNext = activeController?.hasNextMediaItem() == true,
            isCasting = activeController?.deviceInfo?.playbackType ==
                DeviceInfo.PLAYBACK_TYPE_REMOTE,
        )
    }

    private companion object {
        const val RESTART_THRESHOLD_MS = 5_000L
    }
}
