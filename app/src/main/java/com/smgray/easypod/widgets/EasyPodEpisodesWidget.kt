package com.smgray.easypod.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
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
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.smgray.easypod.MainActivity
import com.smgray.easypod.data.CarEpisodeSummary

internal val EPISODE_ID_PARAMETER =
    ActionParameters.Key<String>("episode-id")

class EasyPodEpisodesWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val episodes = EasyPodWidgets.database(context)
            .libraryDao()
            .getCarUnplayedEpisodes(4)
        provideContent {
            EpisodesWidgetContent(episodes)
        }
    }
}

class EasyPodEpisodesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = EasyPodEpisodesWidget()
}

@Composable
private fun EpisodesWidgetContent(episodes: List<CarEpisodeSummary>) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(EPISODES_BACKGROUND)
            .cornerRadius(20.dp)
            .padding(16.dp),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Text(
                text = "UP NEXT",
                modifier = GlanceModifier
                    .defaultWeight()
                    .clickable(actionStartActivity<MainActivity>()),
                style = TextStyle(
                    color = EPISODES_ACCENT,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text(
                text = "EasyPod",
                style = TextStyle(
                    color = EPISODES_SECONDARY,
                    fontSize = 11.sp,
                ),
            )
        }
        Spacer(GlanceModifier.height(8.dp))
        if (episodes.isEmpty()) {
            Text(
                text = "No unplayed episodes",
                style = TextStyle(
                    color = EPISODES_PRIMARY,
                    fontSize = 14.sp,
                ),
            )
        } else {
            episodes.forEach { episode ->
                EpisodeWidgetRow(episode)
            }
        }
    }
}

@Composable
private fun EpisodeWidgetRow(episode: CarEpisodeSummary) {
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .cornerRadius(10.dp)
            .clickable(
                actionRunCallback<PlayEpisodeWidgetAction>(
                    actionParametersOf(EPISODE_ID_PARAMETER to episode.id),
                ),
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            text = episode.title,
            style = TextStyle(
                color = EPISODES_PRIMARY,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
        Text(
            text = episode.feedTitle ?: "EasyPod",
            style = TextStyle(
                color = EPISODES_SECONDARY,
                fontSize = 10.sp,
            ),
            maxLines = 1,
        )
    }
}

private val EPISODES_BACKGROUND = ColorProvider(
    day = Color(0xFFFFF8EE),
    night = Color(0xFF211B15),
)
private val EPISODES_PRIMARY = ColorProvider(
    day = Color(0xFF2C2118),
    night = Color(0xFFFFF8F1),
)
private val EPISODES_SECONDARY = ColorProvider(
    day = Color(0xFF6E5B4B),
    night = Color(0xFFD4C0AF),
)
private val EPISODES_ACCENT = ColorProvider(
    day = Color(0xFFB64D00),
    night = Color(0xFFFFB77D),
)
