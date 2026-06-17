package com.smgray.easypod.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService
import com.smgray.easypod.EasyPodApplication
import com.smgray.easypod.data.PlaybackStateEntity
import com.smgray.easypod.data.PlayerSettingsEntity
import com.smgray.easypod.widgets.PlaybackWidgetSnapshot
import com.smgray.easypod.widgets.PlaybackWidgetStateStore
import com.smgray.easypod.widgets.EasyPodWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.collectLatest

@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService(), Player.Listener {
    private lateinit var localPlayer: ExoPlayer
    private lateinit var player: CastPlayer
    private lateinit var mediaSession: MediaLibrarySession
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var progressJob: Job? = null
    private var sleepJob: Job? = null
    private var widgetUpdateJob: Job? = null
    private var playerSettings = PlayerSettingsEntity()
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var audioSessionId = C.AUDIO_SESSION_ID_UNSET
    private var lastWidgetSnapshot: PlaybackWidgetSnapshot? = null
    private val noisyAudioReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (
                intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY &&
                playerSettings.pauseOnHeadsetDisconnect
            ) {
                player.pause()
            }
        }
    }

    private val database
        get() = (application as EasyPodApplication).container.database
    private val repository
        get() = (application as EasyPodApplication).container.repository

    override fun onCreate() {
        super.onCreate()
        localPlayer = ExoPlayer.Builder(this).build()
        val remotePlayer = RemoteCastPlayer.Builder(this)
            .setMediaItemConverter(EasyPodCastMediaItemConverter())
            .build()
        player = CastPlayer.Builder(this)
            .setLocalPlayer(localPlayer)
            .setRemotePlayer(remotePlayer)
            .build()
        player.addListener(this)
        localPlayer.addListener(localPlayerListener)
        mediaSession = MediaLibrarySession.Builder(
            this,
            player,
            EasyPodMediaLibraryCallback(database, repository, serviceScope),
        ).build()
        ContextCompat.registerReceiver(
            this,
            noisyAudioReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        observePlayerSettings()
        restoreQueue()
        startProgressPersistence()
        publishWidgetState()
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo,
    ): MediaLibrarySession? {
        val accepted = shouldAcceptMediaController(
            controllerPackage = controllerInfo.packageName,
            isTrusted = controllerInfo.isTrusted,
            isSystemApplication =
                isSystemApplication(this, controllerInfo.packageName),
            isLegacyController =
                controllerInfo.packageName ==
                    MediaSession.ControllerInfo.LEGACY_CONTROLLER_PACKAGE_NAME,
        )
        return mediaSession.takeIf { accepted }
    }

    override fun onDestroy() {
        progressSnapshot()?.let { snapshot ->
            runBlocking(Dispatchers.IO) {
                saveProgress(snapshot)
            }
        }
        unregisterReceiver(noisyAudioReceiver)
        publishWidgetState(forceStopped = true)
        loudnessEnhancer?.release()
        serviceScope.cancel()
        mediaSession.release()
        player.release()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        persistProgress()
        super.onTaskRemoved(rootIntent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SET_SLEEP_TIMER -> {
                val durationMs = intent.getLongExtra(EXTRA_SLEEP_DURATION_MS, 0L)
                setSleepTimer(
                    if (durationMs > 0) System.currentTimeMillis() + durationMs else null,
                )
            }

            ACTION_CANCEL_SLEEP_TIMER -> setSleepTimer(null)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
            currentSleepState()?.sleepAtEpisodeEnd == true
        ) {
            player.pause()
            clearSleepState()
        }
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (
            events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
            events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) ||
            events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) ||
            events.contains(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED) ||
            events.contains(Player.EVENT_MEDIA_METADATA_CHANGED) ||
            events.contains(Player.EVENT_TIMELINE_CHANGED)
        ) {
            persistProgress()
            publishWidgetState()
        }
    }

    private fun restoreQueue() {
        serviceScope.launch {
            val playbackDao = database.playbackDao()
            val queue = withContext(Dispatchers.IO) { playbackDao.getQueue() }
            val saved = withContext(Dispatchers.IO) { playbackDao.getPlaybackState() }
            restoreSleepTimer(saved)
            val items = queue.mapNotNull { queued ->
                queued.toMediaItem()?.let { queued to it }
            }
            if (items.isEmpty()) return@launch

            val currentIndex = saved?.currentEpisodeId
                ?.let { current -> items.indexOfFirst { it.first.id == current } }
                ?.takeIf { it >= 0 }
                ?: 0
            val queuePosition = items[currentIndex].first.positionMs.coerceAtLeast(
                saved?.positionMs ?: 0L,
            )
            player.setMediaItems(
                items.map { it.second },
                currentIndex,
                queuePosition,
            )
            player.setPlaybackSpeed(saved?.playbackSpeed ?: 1f)
            player.prepare()
        }
    }

    private fun startProgressPersistence() {
        progressJob?.cancel()
        progressJob = serviceScope.launch {
            while (isActive) {
                delay(PROGRESS_SAVE_INTERVAL_MS)
                persistProgress()
            }
        }
    }

    private fun observePlayerSettings() {
        serviceScope.launch {
            database.playerSettingsDao().observeSettings()
                .filterNotNull()
                .collectLatest {
                    playerSettings = it
                    updateLoudnessBoost()
                    publishWidgetState()
                }
        }
    }

    private fun updateLoudnessBoost() {
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        if (
            !playerSettings.loudnessBoostEnabled ||
            audioSessionId == C.AUDIO_SESSION_ID_UNSET
        ) {
            return
        }
        loudnessEnhancer = runCatching {
            LoudnessEnhancer(audioSessionId).apply {
                setTargetGain(BOOST_GAIN_MILLIBELS)
                enabled = true
            }
        }.getOrNull()
    }

    private val localPlayerListener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            this@PlaybackService.audioSessionId = audioSessionId
            updateLoudnessBoost()
        }
    }

    private fun restoreSleepTimer(saved: PlaybackStateEntity?) {
        when {
            saved?.sleepAtEpisodeEnd == true -> Unit
            saved?.sleepTimerEndsAt != null -> setSleepTimer(saved.sleepTimerEndsAt)
        }
    }

    private fun setSleepTimer(endsAt: Long?) {
        sleepJob?.cancel()
        sleepJob = null
        persistSleepState(endsAt = endsAt, atEpisodeEnd = false)
        if (endsAt == null) return
        val delayMs = endsAt - System.currentTimeMillis()
        if (delayMs <= 0) {
            player.pause()
            clearSleepState()
            return
        }
        sleepJob = serviceScope.launch {
            delay(delayMs)
            player.pause()
            clearSleepState()
        }
    }

    private fun clearSleepState() {
        sleepJob?.cancel()
        sleepJob = null
        persistSleepState(endsAt = null, atEpisodeEnd = false)
    }

    private fun persistSleepState(endsAt: Long?, atEpisodeEnd: Boolean) {
        serviceScope.launch(Dispatchers.IO) {
            val dao = database.playbackDao()
            val updated = dao.updateSleepState(
                endsAt = endsAt,
                atEpisodeEnd = atEpisodeEnd,
                updatedAt = System.currentTimeMillis(),
            )
            if (updated == 0) {
                dao.putPlaybackState(
                    PlaybackStateEntity(
                        currentEpisodeId = player.currentMediaItem?.mediaId,
                        positionMs = player.currentPosition.coerceAtLeast(0L),
                        playbackSpeed = player.playbackParameters.speed,
                        updatedAt = System.currentTimeMillis(),
                        sleepTimerEndsAt = endsAt,
                        sleepAtEpisodeEnd = atEpisodeEnd,
                    ),
                )
            }
        }
    }

    private fun currentSleepState(): PlaybackStateEntity? =
        runBlocking(Dispatchers.IO) {
            database.playbackDao().getPlaybackState()
        }

    private fun persistProgress() {
        val snapshot = progressSnapshot() ?: return
        serviceScope.launch(Dispatchers.IO) {
            saveProgress(snapshot)
        }
    }

    private fun publishWidgetState(forceStopped: Boolean = false) {
        if (!::player.isInitialized) return
        val metadata = player.mediaMetadata
        val snapshot = PlaybackWidgetSnapshot(
            episodeId = player.currentMediaItem?.mediaId,
            title = metadata.title?.toString() ?: "Nothing playing",
            feedTitle = metadata.artist?.toString()
                ?: "Open EasyPod to choose an episode",
            isPlaying = !forceStopped && player.isPlaying,
            playbackSpeed = player.playbackParameters.speed,
            backwardSkipSeconds = playerSettings.backwardSkipSeconds,
            forwardSkipSeconds = playerSettings.forwardSkipSeconds,
        )
        if (snapshot == lastWidgetSnapshot) return
        lastWidgetSnapshot = snapshot
        if (!PlaybackWidgetStateStore.write(this, snapshot)) return
        widgetUpdateJob?.cancel()
        widgetUpdateJob = serviceScope.launch(Dispatchers.IO) {
            delay(WIDGET_UPDATE_DEBOUNCE_MS)
            EasyPodWidgets.updatePlayback(this@PlaybackService)
        }
    }

    private fun progressSnapshot(): ProgressSnapshot? {
        if (!::player.isInitialized) return null
        val episodeId = player.currentMediaItem?.mediaId?.takeIf(String::isNotBlank)
            ?: return null
        val positionMs = player.currentPosition.coerceAtLeast(0L)
        val durationMs = player.duration.takeIf { it > 0 } ?: 0L
        val played = player.playbackState == Player.STATE_ENDED ||
            (durationMs > 0 && positionMs >= durationMs - PLAYED_THRESHOLD_MS)
        val speed = player.playbackParameters.speed
        val now = System.currentTimeMillis()
        return ProgressSnapshot(
            episodeId = episodeId,
            positionMs = positionMs,
            durationMs = durationMs,
            played = played,
            speed = speed,
            updatedAt = now,
        )
    }

    private suspend fun saveProgress(snapshot: ProgressSnapshot) {
        repository.updatePlaybackProgress(
            episodeId = snapshot.episodeId,
            positionMs = snapshot.positionMs,
            durationMs = snapshot.durationMs,
            played = snapshot.played,
        )
        val playbackDao = database.playbackDao()
        val updated = playbackDao.updatePlaybackProgressState(
            currentEpisodeId = snapshot.episodeId,
            positionMs = snapshot.positionMs,
            playbackSpeed = snapshot.speed,
            updatedAt = snapshot.updatedAt,
        )
        if (updated == 0) {
            playbackDao.putPlaybackState(
                PlaybackStateEntity(
                    currentEpisodeId = snapshot.episodeId,
                    positionMs = snapshot.positionMs,
                    playbackSpeed = snapshot.speed,
                    updatedAt = snapshot.updatedAt,
                ),
            )
        }
    }

    private data class ProgressSnapshot(
        val episodeId: String,
        val positionMs: Long,
        val durationMs: Long,
        val played: Boolean,
        val speed: Float,
        val updatedAt: Long,
    )

    companion object {
        const val ACTION_SET_SLEEP_TIMER =
            "com.smgray.easypod.action.SET_SLEEP_TIMER"
        const val ACTION_CANCEL_SLEEP_TIMER =
            "com.smgray.easypod.action.CANCEL_SLEEP_TIMER"
        const val EXTRA_SLEEP_DURATION_MS = "sleep_duration_ms"

        const val PROGRESS_SAVE_INTERVAL_MS = 5_000L
        const val PLAYED_THRESHOLD_MS = 30_000L
        const val BOOST_GAIN_MILLIBELS = 800
        const val WIDGET_UPDATE_DEBOUNCE_MS = 750L
    }
}
