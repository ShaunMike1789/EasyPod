package com.smgray.easypod.widgets

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import com.smgray.easypod.EasyPodApplication

data class PlaybackWidgetSnapshot(
    val episodeId: String? = null,
    val title: String = "Nothing playing",
    val feedTitle: String = "Open EasyPod to choose an episode",
    val isPlaying: Boolean = false,
    val playbackSpeed: Float = 1f,
    val backwardSkipSeconds: Int = 15,
    val forwardSkipSeconds: Int = 30,
)

internal val WIDGET_EPISODE_ID = stringPreferencesKey("episode-id")
internal val WIDGET_TITLE = stringPreferencesKey("title")
internal val WIDGET_FEED_TITLE = stringPreferencesKey("feed-title")
internal val WIDGET_IS_PLAYING = booleanPreferencesKey("is-playing")
internal val WIDGET_PLAYBACK_SPEED = floatPreferencesKey("playback-speed")
internal val WIDGET_BACKWARD_SKIP_SECONDS = intPreferencesKey("backward-skip-seconds")
internal val WIDGET_FORWARD_SKIP_SECONDS = intPreferencesKey("forward-skip-seconds")

object PlaybackWidgetStateStore {
    private const val PREFERENCES = "playback-widget"
    private const val EPISODE_ID = "episode-id"
    private const val TITLE = "title"
    private const val FEED_TITLE = "feed-title"
    private const val IS_PLAYING = "is-playing"
    private const val PLAYBACK_SPEED = "playback-speed"
    private const val BACKWARD_SKIP_SECONDS = "backward-skip-seconds"
    private const val FORWARD_SKIP_SECONDS = "forward-skip-seconds"

    fun read(context: Context): PlaybackWidgetSnapshot {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return PlaybackWidgetSnapshot(
            episodeId = preferences.getString(EPISODE_ID, null),
            title = preferences.getString(TITLE, null) ?: "Nothing playing",
            feedTitle = preferences.getString(FEED_TITLE, null)
                ?: "Open EasyPod to choose an episode",
            isPlaying = preferences.getBoolean(IS_PLAYING, false),
            playbackSpeed = preferences.getFloat(PLAYBACK_SPEED, 1f),
            backwardSkipSeconds = preferences.getInt(BACKWARD_SKIP_SECONDS, 15),
            forwardSkipSeconds = preferences.getInt(FORWARD_SKIP_SECONDS, 30),
        )
    }

    fun write(context: Context, snapshot: PlaybackWidgetSnapshot): Boolean {
        if (read(context) == snapshot) return false
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(EPISODE_ID, snapshot.episodeId)
            .putString(TITLE, snapshot.title)
            .putString(FEED_TITLE, snapshot.feedTitle)
            .putBoolean(IS_PLAYING, snapshot.isPlaying)
            .putFloat(PLAYBACK_SPEED, snapshot.playbackSpeed)
            .putInt(BACKWARD_SKIP_SECONDS, snapshot.backwardSkipSeconds)
            .putInt(FORWARD_SKIP_SECONDS, snapshot.forwardSkipSeconds)
            .apply()
        return true
    }
}

object EasyPodWidgets {
    suspend fun updateAll(context: Context) {
        EasyPodPlaybackWidget().updateAll(context)
        EasyPodEpisodesWidget().updateAll(context)
    }

    suspend fun updatePlayback(context: Context) {
        val appContext = context.applicationContext
        val widget = EasyPodPlaybackWidget()
        val snapshot = PlaybackWidgetStateStore.read(appContext)
        val glanceIds = GlanceAppWidgetManager(appContext)
            .getGlanceIds(EasyPodPlaybackWidget::class.java)
        glanceIds.forEach { glanceId ->
            updateAppWidgetState(appContext, glanceId) { preferences ->
                snapshot.episodeId?.let {
                    preferences[WIDGET_EPISODE_ID] = it
                } ?: preferences.remove(WIDGET_EPISODE_ID)
                preferences[WIDGET_TITLE] = snapshot.title
                preferences[WIDGET_FEED_TITLE] = snapshot.feedTitle
                preferences[WIDGET_IS_PLAYING] = snapshot.isPlaying
                preferences[WIDGET_PLAYBACK_SPEED] = snapshot.playbackSpeed
                preferences[WIDGET_BACKWARD_SKIP_SECONDS] =
                    snapshot.backwardSkipSeconds
                preferences[WIDGET_FORWARD_SKIP_SECONDS] =
                    snapshot.forwardSkipSeconds
            }
            widget.update(appContext, glanceId)
        }
    }

    internal fun database(context: Context) =
        (context.applicationContext as EasyPodApplication).container.database

    internal fun repository(context: Context) =
        (context.applicationContext as EasyPodApplication).container.repository
}

internal fun Preferences.toPlaybackWidgetSnapshot(
    fallback: PlaybackWidgetSnapshot,
): PlaybackWidgetSnapshot = PlaybackWidgetSnapshot(
    episodeId = this[WIDGET_EPISODE_ID] ?: fallback.episodeId,
    title = this[WIDGET_TITLE] ?: fallback.title,
    feedTitle = this[WIDGET_FEED_TITLE] ?: fallback.feedTitle,
    isPlaying = this[WIDGET_IS_PLAYING] ?: fallback.isPlaying,
    playbackSpeed = this[WIDGET_PLAYBACK_SPEED] ?: fallback.playbackSpeed,
    backwardSkipSeconds =
        this[WIDGET_BACKWARD_SKIP_SECONDS] ?: fallback.backwardSkipSeconds,
    forwardSkipSeconds =
        this[WIDGET_FORWARD_SKIP_SECONDS] ?: fallback.forwardSkipSeconds,
)
