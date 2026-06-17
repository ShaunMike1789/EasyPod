package com.smgray.easypod.widgets

import android.content.ComponentName
import android.content.Context
import androidx.concurrent.futures.await
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.smgray.easypod.playback.PlaybackService
import com.smgray.easypod.playback.toMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class TogglePlaybackWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val queue = EasyPodWidgets.repository(context).getQueue()
        withController(context) { controller ->
            if (controller.mediaItemCount == 0 && queue.isNotEmpty()) {
                val items = queue.mapNotNull { it.toMediaItem() }
                controller.setMediaItems(items)
                controller.prepare()
            }
            if (controller.isPlaying) controller.pause() else controller.play()
        }
        delay(300)
        EasyPodWidgets.updateAll(context)
    }
}

class PreviousWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        withController(context) { controller ->
            if (controller.currentPosition > 5_000) {
                controller.seekTo(0)
            } else if (controller.hasPreviousMediaItem()) {
                controller.seekToPreviousMediaItem()
            } else {
                controller.seekTo(0)
            }
        }
        delay(300)
        EasyPodWidgets.updateAll(context)
    }
}

class NextWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        withController(context) { controller ->
            if (controller.hasNextMediaItem()) {
                controller.seekToNextMediaItem()
            }
        }
        refreshPlaybackWidget(context)
    }
}

class RewindWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val seconds = EasyPodWidgets.database(context)
            .playerSettingsDao()
            .getSettings()
            ?.backwardSkipSeconds
            ?: 15
        withController(context) { controller ->
            controller.seekTo(
                (controller.currentPosition - seconds * 1_000L).coerceAtLeast(0),
            )
        }
        refreshPlaybackWidget(context)
    }
}

class ForwardWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val seconds = EasyPodWidgets.database(context)
            .playerSettingsDao()
            .getSettings()
            ?.forwardSkipSeconds
            ?: 30
        withController(context) { controller ->
            val duration = controller.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
            controller.seekTo(
                (controller.currentPosition + seconds * 1_000L)
                    .coerceAtMost(duration),
            )
        }
        refreshPlaybackWidget(context)
    }
}

class PlayEpisodeWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val episodeId = parameters[EPISODE_ID_PARAMETER] ?: return
        val repository = EasyPodWidgets.repository(context)
        val queue = repository.enqueueEpisode(episodeId)
        withController(context) { controller ->
            val playable = queue.mapNotNull { queued ->
                queued.toMediaItem()?.let { queued to it }
            }
            val index = playable.indexOfFirst { it.first.id == episodeId }
            if (index >= 0) {
                controller.setMediaItems(
                    playable.map { it.second },
                    index,
                    playable[index].first.positionMs,
                )
                controller.prepare()
                controller.play()
            }
        }
        delay(300)
        EasyPodWidgets.updateAll(context)
    }
}

private suspend fun withController(
    context: Context,
    action: (MediaController) -> Unit,
) {
    val applicationContext = context.applicationContext
    withContext(Dispatchers.Main.immediate) {
        val future = MediaController.Builder(
            applicationContext,
            SessionToken(
                applicationContext,
                ComponentName(applicationContext, PlaybackService::class.java),
            ),
        ).buildAsync()
        val controller = future.await()
        try {
            action(controller)
        } finally {
            controller.release()
        }
    }
}

private suspend fun refreshPlaybackWidget(context: Context) {
    delay(300)
    EasyPodWidgets.updatePlayback(context)
}
