package com.smgray.easypod.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.smgray.easypod.AppContainer
import com.smgray.easypod.EasyPodApplication
import com.smgray.easypod.data.PlayerSettingsEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ExternalCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in EasyPodCommands.SupportedActions) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                handle(context.applicationContext, intent)
            } catch (error: Exception) {
                Log.w(TAG, "Unable to handle EasyPod command ${intent.action}", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handle(appContext: Context, intent: Intent) {
        val container = (appContext as EasyPodApplication).container
        when (intent.action) {
            EasyPodCommands.ACTION_START_SYNC -> container.syncManager.sync()
            EasyPodCommands.ACTION_UPDATE_SMART_PLAY ->
                updateSmartPlayQueue(container, intent, play = false)

            EasyPodCommands.ACTION_START_SMART_PLAY ->
                updateSmartPlayQueue(container, intent, play = true)

            else -> handlePlaybackCommand(container, intent)
        }
    }

    private suspend fun updateSmartPlayQueue(
        container: AppContainer,
        intent: Intent,
        play: Boolean,
    ) {
        val playlistId = intent
            .getLongExtra(EasyPodCommands.EXTRA_PLAYLIST_ID, NO_PLAYLIST_ID)
            .takeIf { it != NO_PLAYLIST_ID }
            ?: container.repository.firstSmartPlaylistId()
            ?: return

        val queue = container.repository.generateSmartPlaylist(playlistId)
        withContext(Dispatchers.Main.immediate) {
            if (play && queue.isNotEmpty()) {
                container.playbackConnection.playQueue(queue, queue.first().id)
            } else {
                container.playbackConnection.syncQueue(queue)
            }
        }
    }

    private suspend fun handlePlaybackCommand(
        container: AppContainer,
        intent: Intent,
    ) {
        val playback = container.playbackConnection
        when (intent.action) {
            EasyPodCommands.ACTION_PLAY -> withContext(Dispatchers.Main.immediate) {
                playback.play()
            }

            EasyPodCommands.ACTION_PAUSE -> withContext(Dispatchers.Main.immediate) {
                playback.pause()
            }

            EasyPodCommands.ACTION_PLAY_PAUSE -> withContext(Dispatchers.Main.immediate) {
                playback.togglePlayPause()
            }

            EasyPodCommands.ACTION_PLAY_NEXT -> withContext(Dispatchers.Main.immediate) {
                playback.skipNext()
            }

            EasyPodCommands.ACTION_PLAY_PREVIOUS -> withContext(Dispatchers.Main.immediate) {
                playback.skipPrevious()
            }

            EasyPodCommands.ACTION_SKIP_FORWARD -> {
                val seconds = playerSettings(container).forwardSkipSeconds
                withContext(Dispatchers.Main.immediate) {
                    playback.seekBy(seconds * 1_000L)
                }
            }

            EasyPodCommands.ACTION_SKIP_BACKWARD -> {
                val seconds = playerSettings(container).backwardSkipSeconds
                withContext(Dispatchers.Main.immediate) {
                    playback.seekBy(-seconds * 1_000L)
                }
            }

            EasyPodCommands.ACTION_SKIP_TO_END -> withContext(Dispatchers.Main.immediate) {
                playback.seekToEnd()
            }

            EasyPodCommands.ACTION_SET_PLAYBACK_SPEED -> {
                val speed = intent.getFloatExtra(EasyPodCommands.EXTRA_PLAYBACK_SPEED, 1f)
                withContext(Dispatchers.Main.immediate) {
                    playback.setPlaybackSpeed(speed)
                }
            }

            EasyPodCommands.ACTION_SET_PLAYBACK_SPEED_NORMAL,
            EasyPodCommands.ACTION_SET_PLAYBACK_SPEED_1,
            -> withContext(Dispatchers.Main.immediate) {
                playback.setPlaybackSpeed(1f)
            }

            EasyPodCommands.ACTION_SET_PLAYBACK_SPEED_2 ->
                withContext(Dispatchers.Main.immediate) {
                    playback.setPlaybackSpeed(2f)
                }
        }
    }

    private suspend fun playerSettings(container: AppContainer): PlayerSettingsEntity =
        withContext(Dispatchers.IO) {
            container.database.playerSettingsDao().getSettings() ?: PlayerSettingsEntity()
        }

    companion object {
        private const val TAG = "ExternalCommandReceiver"
        private const val NO_PLAYLIST_ID = Long.MIN_VALUE
    }
}
