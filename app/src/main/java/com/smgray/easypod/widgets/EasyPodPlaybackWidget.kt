package com.smgray.easypod.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.currentState
import androidx.glance.layout.Box
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.smgray.easypod.MainActivity

class EasyPodPlaybackWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val stored = PlaybackWidgetStateStore.read(context)
        val database = EasyPodWidgets.database(context)
        val settings = database.playerSettingsDao().getSettings()
        val snapshot = if (stored.episodeId != null) {
            stored.copy(
                backwardSkipSeconds =
                    settings?.backwardSkipSeconds ?: stored.backwardSkipSeconds,
                forwardSkipSeconds =
                    settings?.forwardSkipSeconds ?: stored.forwardSkipSeconds,
            )
        } else {
            val playback = database.playbackDao().getPlaybackState()
            val episode = playback?.currentEpisodeId
                ?.let { database.libraryDao().findEpisode(it) }
            val feed = episode?.feedId?.let { database.libraryDao().findFeed(it) }
            if (episode == null) {
                stored
            } else {
                stored.copy(
                    episodeId = episode.id,
                    title = episode.title,
                    feedTitle = feed?.title ?: "EasyPod",
                    playbackSpeed = playback.playbackSpeed,
                    backwardSkipSeconds =
                        settings?.backwardSkipSeconds ?: stored.backwardSkipSeconds,
                    forwardSkipSeconds =
                        settings?.forwardSkipSeconds ?: stored.forwardSkipSeconds,
                )
            }
        }
        provideContent {
            PlaybackWidgetContent(
                currentState<androidx.datastore.preferences.core.Preferences>()
                    .toPlaybackWidgetSnapshot(snapshot),
            )
        }
    }
}

class EasyPodPlaybackWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = EasyPodPlaybackWidget()
}

@Composable
private fun PlaybackWidgetContent(snapshot: PlaybackWidgetSnapshot) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(WIDGET_BACKGROUND)
            .cornerRadius(20.dp)
            .padding(16.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Text(
            text = snapshot.title,
            modifier = GlanceModifier
                .fillMaxWidth()
                .clickable(actionStartActivity<MainActivity>()),
            style = TextStyle(
                color = PRIMARY_TEXT,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
        Text(
            text = snapshot.feedTitle,
            style = TextStyle(
                color = SECONDARY_TEXT,
                fontSize = 12.sp,
            ),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(10.dp))
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            WidgetControl("Prev", actionRunCallback<PreviousWidgetAction>())
            WidgetControl(
                "-${snapshot.backwardSkipSeconds}",
                actionRunCallback<RewindWidgetAction>(),
            )
            WidgetControl(
                if (snapshot.isPlaying) "Pause" else "Play",
                actionRunCallback<TogglePlaybackWidgetAction>(),
            )
            WidgetControl(
                "+${snapshot.forwardSkipSeconds}",
                actionRunCallback<ForwardWidgetAction>(),
            )
            WidgetControl("Next", actionRunCallback<NextWidgetAction>())
        }
        Text(
            text = "${snapshot.playbackSpeed}x",
            modifier = GlanceModifier.fillMaxWidth(),
            style = TextStyle(
                color = ACCENT_TEXT,
                fontSize = 11.sp,
            ),
        )
    }
}

@Composable
private fun RowScope.WidgetControl(
    label: String,
    action: androidx.glance.action.Action,
) {
    Box(
        modifier = GlanceModifier
            .defaultWeight()
            .padding(horizontal = 2.dp, vertical = 6.dp)
            .cornerRadius(10.dp)
            .background(CONTROL_BACKGROUND)
            .clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = TextStyle(
                color = PRIMARY_TEXT,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}

private val WIDGET_BACKGROUND = ColorProvider(
    day = Color(0xFFF1F4F8),
    night = Color(0xFF18202A),
)
private val CONTROL_BACKGROUND = ColorProvider(
    day = Color(0xFFDDE8F5),
    night = Color(0xFF263A50),
)
private val PRIMARY_TEXT = ColorProvider(
    day = Color(0xFF15202B),
    night = Color(0xFFF5F8FC),
)
private val SECONDARY_TEXT = ColorProvider(
    day = Color(0xFF52606D),
    night = Color(0xFFB8C4D1),
)
private val ACCENT_TEXT = ColorProvider(
    day = Color(0xFF1565C0),
    night = Color(0xFF8CC5FF),
)
