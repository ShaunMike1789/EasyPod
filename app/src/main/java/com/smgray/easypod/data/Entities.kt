package com.smgray.easypod.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "feeds",
    indices = [
        Index("legacyFeedId"),
        Index("feedUrl"),
    ],
)
data class FeedEntity(
    @androidx.room.PrimaryKey val id: String,
    val legacyRowId: Long?,
    val legacyFeedId: String?,
    val title: String,
    val feedUrl: String?,
    val imageUrl: String?,
    val localPath: String?,
    val categoryAssignmentRaw: String?,
    val autoDelete: Boolean,
    val viewMode: Int,
    val feedType: Int,
    val hasUnread: Boolean,
    val publishedAt: Long?,
    val serverPublishedAt: Long?,
    val customDownload: Int,
    val forceUniqueNames: Boolean,
    val truncateLongNames: Boolean,
    val forceItemSort: Boolean,
    val lastItemId: String?,
    val trackSort: Int,
    val feedPlayer: Int,
    val username: String?,
    val maxDownloads: Int,
    val maxSyncedItems: Int,
    val maxEpisodes: Int,
    val maxEpisodeAge: Int,
    val savePlayedPosition: Boolean,
    val fingerprintType: Int,
    val audioSettings: String?,
)

@Entity(
    tableName = "episodes",
    foreignKeys = [
        ForeignKey(
            entity = FeedEntity::class,
            parentColumns = ["id"],
            childColumns = ["feedId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("feedId"),
        Index("legacyParentFeedId"),
        Index("mediaUrl"),
        Index("publishedAt"),
    ],
)
data class EpisodeEntity(
    @androidx.room.PrimaryKey val id: String,
    val legacyRowId: Long?,
    val legacyItemId: String?,
    val legacyRssItemId: String?,
    val legacyParentFeedId: String?,
    val feedId: String?,
    val title: String,
    val mediaUrl: String?,
    val postUrl: String?,
    val localPath: String?,
    val imagePath: String?,
    val mimeType: String?,
    val protocol: String?,
    val durationMs: Long,
    val positionMs: Long,
    val publishedAt: Long?,
    val downloadSizeBytes: Long,
    val downloadPortionRaw: Long,
    val played: Boolean,
    val locked: Boolean,
    val description: String?,
    val showNotes: String?,
)

@Entity(
    tableName = "categories",
    indices = [Index(value = ["name"], unique = true)],
)
data class CategoryEntity(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    val color: Int,
)

@Entity(
    tableName = "feed_categories",
    primaryKeys = ["feedId", "categoryId"],
    foreignKeys = [
        ForeignKey(
            entity = FeedEntity::class,
            parentColumns = ["id"],
            childColumns = ["feedId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("categoryId")],
)
data class FeedCategoryCrossRef(
    val feedId: String,
    val categoryId: String,
    val slot: Int,
)

@Entity(tableName = "scheduled_updates")
data class ScheduledUpdateEntity(
    @androidx.room.PrimaryKey val id: String,
    val legacyRowId: Long?,
    val legacyTaskId: String?,
    val active: Boolean,
    val minimumBatteryPercent: Int,
    val startTimeRaw: Long,
    val recurrencePeriodRaw: Int,
    val recurrenceIntervalRaw: Int,
    val operationIdRaw: String?,
    val stateRaw: String?,
)

@Entity(tableName = "smart_playlists")
data class SmartPlaylistEntity(
    @androidx.room.PrimaryKey val id: String,
    val legacyRowId: Long?,
    val legacyPlaylistId: Long?,
    val name: String,
    val legacyFeedId: String?,
    val sortOrderRaw: Int,
    val categoryIdRaw: String?,
    val episodeCount: Int,
    val episodeFilterRaw: Int,
    val playbackTypeRaw: Int,
    val configRaw: String?,
)

@Entity(
    tableName = "episode_history",
    indices = [Index("episodeUrl"), Index("feedUrl")],
)
data class EpisodeHistoryEntity(
    @androidx.room.PrimaryKey val id: String,
    val legacyRowId: Long?,
    val episodeUrl: String?,
    val feedUrl: String?,
    val timestamp: Long,
    val entryTypeRaw: Int,
)

@Entity(tableName = "migration_runs")
data class MigrationRunEntity(
    @androidx.room.PrimaryKey val id: String,
    val sourceName: String,
    val sourceUri: String,
    val startedAt: Long,
    val completedAt: Long?,
    val status: String,
    val feedCount: Int,
    val episodeCount: Int,
    val categoryCount: Int,
    val message: String?,
)

@Entity(
    tableName = "queue_entries",
    foreignKeys = [
        ForeignKey(
            entity = EpisodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["episodeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["position"], unique = true),
    ],
)
data class QueueEntryEntity(
    @androidx.room.PrimaryKey val episodeId: String,
    val position: Int,
    val addedAt: Long,
)

@Entity(
    tableName = "downloads",
    foreignKeys = [
        ForeignKey(
            entity = EpisodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["episodeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DownloadEntity(
    @androidx.room.PrimaryKey val episodeId: String,
    val state: String,
    val localPath: String?,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val requestedAt: Long,
    val completedAt: Long?,
    val errorMessage: String?,
)

@Entity(tableName = "playback_state")
data class PlaybackStateEntity(
    @androidx.room.PrimaryKey val id: Int = SINGLETON_ID,
    val currentEpisodeId: String?,
    val positionMs: Long,
    val playbackSpeed: Float,
    val updatedAt: Long,
    val sleepTimerEndsAt: Long? = null,
    val sleepAtEpisodeEnd: Boolean = false,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@Entity(tableName = "player_settings")
data class PlayerSettingsEntity(
    @androidx.room.PrimaryKey val id: Int = SINGLETON_ID,
    val forwardSkipSeconds: Int = 30,
    val backwardSkipSeconds: Int = 15,
    val loudnessBoostEnabled: Boolean = false,
    val pauseOnHeadsetDisconnect: Boolean = true,
    val defaultSleepMinutes: Int = 30,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@Entity(tableName = "automation_settings")
data class AutomationSettingsEntity(
    @androidx.room.PrimaryKey val id: Int = SINGLETON_ID,
    val refreshEnabled: Boolean = false,
    val refreshIntervalHours: Int = 12,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val autoDownloadEnabled: Boolean = true,
    val defaultMaxDownloads: Int = 3,
    val lastRunAt: Long? = null,
    val lastStatus: String = "Never run",
    val lastMessage: String? = null,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

data class FeedSummary(
    val id: String,
    val title: String,
    val feedUrl: String?,
    val imageUrl: String?,
    val autoDownload: Boolean,
    val maxDownloads: Int,
    val categoryIds: String?,
    val categoryNames: String?,
)

data class CategorySummary(
    val id: String,
    val name: String,
    val color: Int,
    val feedCount: Int,
)

data class OpmlExportFeed(
    val title: String,
    val feedUrl: String,
    val categoryNames: String?,
)

data class EpisodeSummary(
    val id: String,
    val title: String,
    val feedTitle: String?,
    val played: Boolean,
    val locked: Boolean,
    val publishedAt: Long?,
    val mediaUrl: String?,
    val mimeType: String?,
    val postUrl: String?,
    val positionMs: Long,
    val durationMs: Long,
    val description: String?,
    val showNotes: String?,
    val localDownloadPath: String?,
    val inQueue: Boolean,
    val downloadState: String?,
    val downloadProgress: Int,
)

data class QueueEpisodeSummary(
    val id: String,
    val title: String,
    val feedTitle: String?,
    val mediaUrl: String?,
    val mimeType: String?,
    val localDownloadPath: String?,
    val imageUrl: String?,
    val positionMs: Long,
    val durationMs: Long,
    val queuePosition: Int,
)

data class DownloadSummary(
    val episodeId: String,
    val title: String,
    val feedTitle: String?,
    val state: String,
    val localPath: String?,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val errorMessage: String?,
)

data class PlayedHistorySummary(
    val id: String,
    val episodeId: String?,
    val title: String,
    val feedTitle: String?,
    val episodeUrl: String?,
    val feedUrl: String?,
    val timestamp: Long,
    val entryTypeRaw: Int,
)

data class EpisodeDownloadSource(
    val episodeId: String,
    val title: String,
    val mediaUrl: String?,
    val mimeType: String?,
)

data class SmartPlaylistSummary(
    val playlistId: Long,
    val name: String,
    val ruleCount: Int,
)

data class SmartPlayRuleSummary(
    val id: String,
    val playlistId: Long,
    val name: String,
    val sourceName: String,
    val feedId: String?,
    val categoryId: String?,
    val position: Int,
    val episodeCount: Int,
    val mediaType: Int,
    val sortMode: Int,
)

data class SmartPlayRuleDraft(
    val feedId: String? = null,
    val categoryId: String? = null,
    val episodeCount: Int = 5,
    val mediaType: Int = -2,
    val sortMode: Int = 3,
)

data class SmartPlayCandidate(
    val id: String,
    val title: String,
    val feedTitle: String?,
    val mediaUrl: String?,
    val mimeType: String?,
    val localDownloadPath: String?,
    val downloadState: String?,
    val feedDownloadAction: Int,
    val publishedAt: Long?,
    val positionMs: Long,
    val durationMs: Long,
)

data class CarEpisodeSummary(
    val id: String,
    val feedId: String?,
    val title: String,
    val feedTitle: String?,
    val mediaUrl: String?,
    val mimeType: String?,
    val localDownloadPath: String?,
    val imageUrl: String?,
    val durationMs: Long,
    val positionMs: Long,
    val publishedAt: Long?,
)
