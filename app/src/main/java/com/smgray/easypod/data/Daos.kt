package com.smgray.easypod.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Upsert
    suspend fun putFeeds(feeds: List<FeedEntity>)

    @Upsert
    suspend fun putEpisodes(episodes: List<EpisodeEntity>)

    @Upsert
    suspend fun putCategories(categories: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putFeedCategories(crossRefs: List<FeedCategoryCrossRef>)

    @Upsert
    suspend fun putSchedules(schedules: List<ScheduledUpdateEntity>)

    @Upsert
    suspend fun putSmartPlaylists(playlists: List<SmartPlaylistEntity>)

    @Upsert
    suspend fun putEpisodeHistory(history: List<EpisodeHistoryEntity>)

    @Query("SELECT * FROM feeds WHERE feedUrl = :url LIMIT 1")
    suspend fun findFeedByUrl(url: String): FeedEntity?

    @Query("SELECT * FROM episodes WHERE id = :episodeId LIMIT 1")
    suspend fun findEpisode(episodeId: String): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE feedId = :feedId")
    suspend fun getEpisodesForFeed(feedId: String): List<EpisodeEntity>

    @Query("SELECT * FROM feeds WHERE id = :feedId LIMIT 1")
    suspend fun findFeed(feedId: String): FeedEntity?

    @Query("SELECT * FROM feeds WHERE feedUrl IS NOT NULL ORDER BY title COLLATE NOCASE")
    suspend fun getRefreshableFeeds(): List<FeedEntity>

    @Query("SELECT * FROM feeds ORDER BY title COLLATE NOCASE")
    suspend fun getAllFeeds(): List<FeedEntity>

    @Query(
        """
        SELECT episodes.id AS id,
               episodes.feedId AS feedId,
               episodes.title AS title,
               feeds.title AS feedTitle,
               episodes.mediaUrl AS mediaUrl,
               episodes.mimeType AS mimeType,
               downloads.localPath AS localDownloadPath,
               COALESCE(episodes.imagePath, feeds.imageUrl) AS imageUrl,
               episodes.durationMs AS durationMs,
               episodes.positionMs AS positionMs,
               episodes.publishedAt AS publishedAt
        FROM episodes
        LEFT JOIN feeds ON feeds.id = episodes.feedId
        LEFT JOIN downloads ON downloads.episodeId = episodes.id
        WHERE episodes.id = :episodeId
        LIMIT 1
        """,
    )
    suspend fun getCarEpisode(episodeId: String): CarEpisodeSummary?

    @Query(
        """
        SELECT episodes.id AS id,
               episodes.feedId AS feedId,
               episodes.title AS title,
               feeds.title AS feedTitle,
               episodes.mediaUrl AS mediaUrl,
               episodes.mimeType AS mimeType,
               downloads.localPath AS localDownloadPath,
               COALESCE(episodes.imagePath, feeds.imageUrl) AS imageUrl,
               episodes.durationMs AS durationMs,
               episodes.positionMs AS positionMs,
               episodes.publishedAt AS publishedAt
        FROM episodes
        LEFT JOIN feeds ON feeds.id = episodes.feedId
        LEFT JOIN downloads ON downloads.episodeId = episodes.id
        WHERE episodes.feedId = :feedId
          AND (episodes.mediaUrl IS NOT NULL OR downloads.localPath IS NOT NULL)
        ORDER BY episodes.publishedAt DESC, episodes.title COLLATE NOCASE
        LIMIT :limit
        """,
    )
    suspend fun getCarEpisodesForFeed(
        feedId: String,
        limit: Int,
    ): List<CarEpisodeSummary>

    @Query(
        """
        SELECT episodes.id AS id,
               episodes.feedId AS feedId,
               episodes.title AS title,
               feeds.title AS feedTitle,
               episodes.mediaUrl AS mediaUrl,
               episodes.mimeType AS mimeType,
               downloads.localPath AS localDownloadPath,
               COALESCE(episodes.imagePath, feeds.imageUrl) AS imageUrl,
               episodes.durationMs AS durationMs,
               episodes.positionMs AS positionMs,
               episodes.publishedAt AS publishedAt
        FROM episodes
        LEFT JOIN feeds ON feeds.id = episodes.feedId
        LEFT JOIN downloads ON downloads.episodeId = episodes.id
        WHERE episodes.played = 0
          AND (episodes.mediaUrl IS NOT NULL OR downloads.localPath IS NOT NULL)
        ORDER BY episodes.publishedAt DESC, episodes.title COLLATE NOCASE
        LIMIT :limit
        """,
    )
    suspend fun getCarUnplayedEpisodes(limit: Int): List<CarEpisodeSummary>

    @Query(
        """
        SELECT episodes.id AS id,
               episodes.feedId AS feedId,
               episodes.title AS title,
               feeds.title AS feedTitle,
               episodes.mediaUrl AS mediaUrl,
               episodes.mimeType AS mimeType,
               downloads.localPath AS localDownloadPath,
               COALESCE(episodes.imagePath, feeds.imageUrl) AS imageUrl,
               episodes.durationMs AS durationMs,
               episodes.positionMs AS positionMs,
               episodes.publishedAt AS publishedAt
        FROM downloads
        JOIN episodes ON episodes.id = downloads.episodeId
        LEFT JOIN feeds ON feeds.id = episodes.feedId
        WHERE downloads.state = 'COMPLETE'
          AND downloads.localPath IS NOT NULL
        ORDER BY downloads.completedAt DESC, episodes.title COLLATE NOCASE
        LIMIT :limit
        """,
    )
    suspend fun getCarDownloadedEpisodes(limit: Int): List<CarEpisodeSummary>

    @Query(
        """
        SELECT episodes.id AS id,
               episodes.feedId AS feedId,
               episodes.title AS title,
               feeds.title AS feedTitle,
               episodes.mediaUrl AS mediaUrl,
               episodes.mimeType AS mimeType,
               downloads.localPath AS localDownloadPath,
               COALESCE(episodes.imagePath, feeds.imageUrl) AS imageUrl,
               episodes.durationMs AS durationMs,
               episodes.positionMs AS positionMs,
               episodes.publishedAt AS publishedAt
        FROM queue_entries
        JOIN episodes ON episodes.id = queue_entries.episodeId
        LEFT JOIN feeds ON feeds.id = episodes.feedId
        LEFT JOIN downloads ON downloads.episodeId = episodes.id
        ORDER BY queue_entries.position
        LIMIT :limit
        """,
    )
    suspend fun getCarQueue(limit: Int): List<CarEpisodeSummary>

    @Query(
        """
        SELECT episodes.id AS id,
               episodes.feedId AS feedId,
               episodes.title AS title,
               feeds.title AS feedTitle,
               episodes.mediaUrl AS mediaUrl,
               episodes.mimeType AS mimeType,
               downloads.localPath AS localDownloadPath,
               COALESCE(episodes.imagePath, feeds.imageUrl) AS imageUrl,
               episodes.durationMs AS durationMs,
               episodes.positionMs AS positionMs,
               episodes.publishedAt AS publishedAt
        FROM episodes
        LEFT JOIN feeds ON feeds.id = episodes.feedId
        LEFT JOIN downloads ON downloads.episodeId = episodes.id
        WHERE (episodes.mediaUrl IS NOT NULL OR downloads.localPath IS NOT NULL)
          AND (
              episodes.title LIKE '%' || :query || '%' ESCAPE '\'
              OR feeds.title LIKE '%' || :query || '%' ESCAPE '\'
          )
        ORDER BY episodes.publishedAt DESC, episodes.title COLLATE NOCASE
        LIMIT :limit
        """,
    )
    suspend fun searchCarEpisodes(
        query: String,
        limit: Int,
    ): List<CarEpisodeSummary>

    @Query("SELECT * FROM feed_categories WHERE feedId = :feedId ORDER BY slot")
    suspend fun getFeedCategories(feedId: String): List<FeedCategoryCrossRef>

    @Query(
        """
        DELETE FROM feed_categories
        WHERE feedId = :feedId AND categoryId = :categoryId
        """,
    )
    suspend fun removeFeedCategory(feedId: String, categoryId: String)

    @Query("DELETE FROM categories WHERE id = :categoryId")
    suspend fun deleteCategory(categoryId: String)

    @Query(
        """
        UPDATE feeds
        SET customDownload = CASE WHEN :enabled THEN 1 ELSE 0 END,
            maxDownloads = :maxDownloads
        WHERE id = :feedId
        """,
    )
    suspend fun setFeedAutoDownload(
        feedId: String,
        enabled: Boolean,
        maxDownloads: Int,
    )

    @Query(
        """
        UPDATE episodes
        SET played = :played,
            positionMs = CASE
                WHEN :played = 0 THEN 0
                WHEN durationMs > 0 THEN durationMs
                ELSE positionMs
            END
        WHERE id = :episodeId
        """,
    )
    suspend fun setEpisodePlayed(episodeId: String, played: Boolean)

    @Query("UPDATE episodes SET locked = :locked WHERE id = :episodeId")
    suspend fun setEpisodeLocked(episodeId: String, locked: Boolean)

    @Query(
        """
        UPDATE episodes
        SET positionMs = :positionMs,
            durationMs = CASE WHEN :durationMs > 0 THEN :durationMs ELSE durationMs END,
            played = CASE WHEN :played THEN 1 ELSE played END
        WHERE id = :episodeId
        """,
    )
    suspend fun updateEpisodeProgress(
        episodeId: String,
        positionMs: Long,
        durationMs: Long,
        played: Boolean,
    )

    @Query("SELECT COUNT(*) FROM feeds")
    fun observeFeedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM episodes")
    fun observeEpisodeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM episodes WHERE played = 0")
    fun observeUnplayedCount(): Flow<Int>

    @Query(
        """
        SELECT id,
               title,
               feedUrl,
               imageUrl,
               CASE WHEN customDownload IN (1, 4) THEN 1 ELSE 0 END AS autoDownload,
               maxDownloads,
               (
                   SELECT GROUP_CONCAT(feed_categories.categoryId, ',')
                   FROM feed_categories
                   WHERE feed_categories.feedId = feeds.id
               ) AS categoryIds,
               (
                   SELECT GROUP_CONCAT(categories.name, ', ')
                   FROM feed_categories
                   JOIN categories
                     ON categories.id = feed_categories.categoryId
                   WHERE feed_categories.feedId = feeds.id
               ) AS categoryNames
        FROM feeds
        ORDER BY title COLLATE NOCASE
        LIMIT :limit
        """,
    )
    fun observeFeedSummaries(limit: Int): Flow<List<FeedSummary>>

    @Query(
        """
        SELECT categories.id AS id,
               categories.name AS name,
               categories.color AS color,
               COUNT(feed_categories.feedId) AS feedCount
        FROM categories
        LEFT JOIN feed_categories
          ON feed_categories.categoryId = categories.id
        GROUP BY categories.id
        ORDER BY categories.name COLLATE NOCASE
        """,
    )
    fun observeCategories(): Flow<List<CategorySummary>>

    @Query(
        """
        SELECT feeds.title AS title,
               feeds.feedUrl AS feedUrl,
               (
                   SELECT GROUP_CONCAT(categories.name, ',')
                   FROM feed_categories
                   JOIN categories
                     ON categories.id = feed_categories.categoryId
                   WHERE feed_categories.feedId = feeds.id
               ) AS categoryNames
        FROM feeds
        WHERE feeds.feedUrl IS NOT NULL
        ORDER BY feeds.title COLLATE NOCASE
        """,
    )
    suspend fun getOpmlExportFeeds(): List<OpmlExportFeed>

    @Query(
        """
        SELECT episodes.id AS id,
               episodes.title AS title,
               feeds.title AS feedTitle,
               episodes.played AS played,
               episodes.locked AS locked,
               episodes.publishedAt AS publishedAt,
               episodes.mediaUrl AS mediaUrl,
               episodes.postUrl AS postUrl,
               episodes.positionMs AS positionMs,
               episodes.durationMs AS durationMs,
               episodes.description AS description,
               episodes.showNotes AS showNotes,
               downloads.localPath AS localDownloadPath,
               CASE WHEN queue_entries.episodeId IS NULL THEN 0 ELSE 1 END AS inQueue,
               downloads.state AS downloadState,
               CASE
                   WHEN downloads.totalBytes > 0
                   THEN CAST((downloads.bytesDownloaded * 100) / downloads.totalBytes AS INTEGER)
                   ELSE 0
               END AS downloadProgress
        FROM episodes
        LEFT JOIN feeds ON feeds.id = episodes.feedId
        LEFT JOIN downloads ON downloads.episodeId = episodes.id
        LEFT JOIN queue_entries ON queue_entries.episodeId = episodes.id
        ORDER BY episodes.publishedAt DESC, episodes.title COLLATE NOCASE
        LIMIT :limit
        """,
    )
    fun observeEpisodeSummaries(limit: Int): Flow<List<EpisodeSummary>>
}

@Dao
interface PlaybackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putQueueEntry(entry: QueueEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putQueueEntries(entries: List<QueueEntryEntity>)

    @Query("SELECT * FROM queue_entries ORDER BY position")
    suspend fun getQueueEntries(): List<QueueEntryEntity>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM queue_entries")
    suspend fun nextQueuePosition(): Int

    @Query("DELETE FROM queue_entries WHERE episodeId = :episodeId")
    suspend fun removeQueueEntry(episodeId: String)

    @Query("DELETE FROM queue_entries")
    suspend fun clearQueue()

    @Query(
        """
        SELECT episodes.id AS id,
               episodes.title AS title,
               feeds.title AS feedTitle,
               episodes.mediaUrl AS mediaUrl,
               episodes.mimeType AS mimeType,
               downloads.localPath AS localDownloadPath,
               COALESCE(episodes.imagePath, feeds.imageUrl) AS imageUrl,
               episodes.positionMs AS positionMs,
               episodes.durationMs AS durationMs,
               queue_entries.position AS queuePosition
        FROM queue_entries
        JOIN episodes ON episodes.id = queue_entries.episodeId
        LEFT JOIN feeds ON feeds.id = episodes.feedId
        LEFT JOIN downloads ON downloads.episodeId = episodes.id
        ORDER BY queue_entries.position
        """,
    )
    fun observeQueue(): Flow<List<QueueEpisodeSummary>>

    @Query(
        """
        SELECT episodes.id AS id,
               episodes.title AS title,
               feeds.title AS feedTitle,
               episodes.mediaUrl AS mediaUrl,
               episodes.mimeType AS mimeType,
               downloads.localPath AS localDownloadPath,
               COALESCE(episodes.imagePath, feeds.imageUrl) AS imageUrl,
               episodes.positionMs AS positionMs,
               episodes.durationMs AS durationMs,
               queue_entries.position AS queuePosition
        FROM queue_entries
        JOIN episodes ON episodes.id = queue_entries.episodeId
        LEFT JOIN feeds ON feeds.id = episodes.feedId
        LEFT JOIN downloads ON downloads.episodeId = episodes.id
        ORDER BY queue_entries.position
        """,
    )
    suspend fun getQueue(): List<QueueEpisodeSummary>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPlaybackState(state: PlaybackStateEntity)

    @Query(
        """
        UPDATE playback_state
        SET currentEpisodeId = :currentEpisodeId,
            positionMs = :positionMs,
            playbackSpeed = :playbackSpeed,
            updatedAt = :updatedAt
        WHERE id = 1
        """,
    )
    suspend fun updatePlaybackProgressState(
        currentEpisodeId: String?,
        positionMs: Long,
        playbackSpeed: Float,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE playback_state
        SET sleepTimerEndsAt = :endsAt,
            sleepAtEpisodeEnd = :atEpisodeEnd,
            updatedAt = :updatedAt
        WHERE id = 1
        """,
    )
    suspend fun updateSleepState(
        endsAt: Long?,
        atEpisodeEnd: Boolean,
        updatedAt: Long,
    ): Int

    @Query("SELECT * FROM playback_state WHERE id = 1")
    suspend fun getPlaybackState(): PlaybackStateEntity?

    @Query("SELECT * FROM playback_state WHERE id = 1")
    fun observePlaybackState(): Flow<PlaybackStateEntity?>
}

@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDownload(download: DownloadEntity)

    @Query("SELECT * FROM downloads WHERE episodeId = :episodeId")
    suspend fun getDownload(episodeId: String): DownloadEntity?

    @Query(
        """
        SELECT episodes.id AS episodeId,
               episodes.title AS title,
               episodes.mediaUrl AS mediaUrl,
               episodes.mimeType AS mimeType
        FROM episodes
        WHERE episodes.id = :episodeId
        LIMIT 1
        """,
    )
    suspend fun getDownloadSource(episodeId: String): EpisodeDownloadSource?

    @Query(
        """
        UPDATE downloads
        SET state = :state,
            bytesDownloaded = :bytesDownloaded,
            totalBytes = :totalBytes,
            localPath = :localPath,
            completedAt = :completedAt,
            errorMessage = :errorMessage
        WHERE episodeId = :episodeId
        """,
    )
    suspend fun updateDownload(
        episodeId: String,
        state: String,
        bytesDownloaded: Long,
        totalBytes: Long,
        localPath: String?,
        completedAt: Long?,
        errorMessage: String?,
    )

    @Query("DELETE FROM downloads WHERE episodeId = :episodeId")
    suspend fun deleteDownload(episodeId: String)

    @Query(
        """
        SELECT downloads.episodeId AS episodeId,
               episodes.title AS title,
               feeds.title AS feedTitle,
               downloads.state AS state,
               downloads.localPath AS localPath,
               downloads.bytesDownloaded AS bytesDownloaded,
               downloads.totalBytes AS totalBytes,
               downloads.errorMessage AS errorMessage
        FROM downloads
        JOIN episodes ON episodes.id = downloads.episodeId
        LEFT JOIN feeds ON feeds.id = episodes.feedId
        ORDER BY downloads.requestedAt DESC
        """,
    )
    fun observeDownloads(): Flow<List<DownloadSummary>>
}

@Dao
interface AutomationDao {
    @Upsert
    suspend fun putSettings(settings: AutomationSettingsEntity)

    @Query("SELECT * FROM automation_settings WHERE id = 1")
    suspend fun getSettings(): AutomationSettingsEntity?

    @Query("SELECT * FROM automation_settings WHERE id = 1")
    fun observeSettings(): Flow<AutomationSettingsEntity?>

    @Query(
        """
        UPDATE automation_settings
        SET lastRunAt = :lastRunAt,
            lastStatus = :status,
            lastMessage = :message
        WHERE id = 1
        """,
    )
    suspend fun updateRunStatus(
        lastRunAt: Long?,
        status: String,
        message: String?,
    )
}

@Dao
interface SmartPlayDao {
    @Query(
        """
        SELECT COALESCE(legacyPlaylistId, legacyRowId, 1) AS playlistId,
               COALESCE(
                   MAX(CASE WHEN name != 'SmartPlay' THEN name END),
                   MAX(name),
                   'SmartPlay'
               ) AS name,
               COUNT(*) AS ruleCount
        FROM smart_playlists
        GROUP BY COALESCE(legacyPlaylistId, legacyRowId, 1)
        ORDER BY playlistId
        """,
    )
    fun observePlaylists(): Flow<List<SmartPlaylistSummary>>

    @Query(
        """
        SELECT smart_playlists.id AS id,
               COALESCE(
                   smart_playlists.legacyPlaylistId,
                   smart_playlists.legacyRowId,
                   1
               ) AS playlistId,
               smart_playlists.name AS name,
               COALESCE(feeds.title, categories.name, 'All feeds') AS sourceName,
               feeds.id AS feedId,
               categories.id AS categoryId,
               smart_playlists.sortOrderRaw AS position,
               smart_playlists.episodeCount AS episodeCount,
               smart_playlists.episodeFilterRaw AS mediaType,
               smart_playlists.playbackTypeRaw AS sortMode
        FROM smart_playlists
        LEFT JOIN feeds
          ON feeds.id = smart_playlists.legacyFeedId
          OR feeds.legacyFeedId = smart_playlists.legacyFeedId
        LEFT JOIN categories
          ON categories.id = smart_playlists.categoryIdRaw
          OR categories.name = smart_playlists.categoryIdRaw COLLATE NOCASE
        ORDER BY playlistId, smart_playlists.sortOrderRaw
        """,
    )
    fun observeRules(): Flow<List<SmartPlayRuleSummary>>

    @Query(
        """
        SELECT smart_playlists.id AS id,
               COALESCE(
                   smart_playlists.legacyPlaylistId,
                   smart_playlists.legacyRowId,
                   1
               ) AS playlistId,
               smart_playlists.name AS name,
               COALESCE(feeds.title, categories.name, 'All feeds') AS sourceName,
               feeds.id AS feedId,
               categories.id AS categoryId,
               smart_playlists.sortOrderRaw AS position,
               smart_playlists.episodeCount AS episodeCount,
               smart_playlists.episodeFilterRaw AS mediaType,
               smart_playlists.playbackTypeRaw AS sortMode
        FROM smart_playlists
        LEFT JOIN feeds
          ON feeds.id = smart_playlists.legacyFeedId
          OR feeds.legacyFeedId = smart_playlists.legacyFeedId
        LEFT JOIN categories
          ON categories.id = smart_playlists.categoryIdRaw
          OR categories.name = smart_playlists.categoryIdRaw COLLATE NOCASE
        WHERE COALESCE(
            smart_playlists.legacyPlaylistId,
            smart_playlists.legacyRowId,
            1
        ) = :playlistId
        ORDER BY smart_playlists.sortOrderRaw
        """,
    )
    suspend fun getRules(playlistId: Long): List<SmartPlayRuleSummary>

    @Query(
        """
        SELECT episodes.id AS id,
               episodes.title AS title,
               feeds.title AS feedTitle,
               episodes.mediaUrl AS mediaUrl,
               episodes.mimeType AS mimeType,
               downloads.localPath AS localDownloadPath,
               downloads.state AS downloadState,
               feeds.customDownload AS feedDownloadAction,
               episodes.publishedAt AS publishedAt,
               episodes.positionMs AS positionMs,
               episodes.durationMs AS durationMs
        FROM episodes
        JOIN feeds ON feeds.id = episodes.feedId
        LEFT JOIN downloads ON downloads.episodeId = episodes.id
        WHERE episodes.played = 0
          AND (episodes.mediaUrl IS NOT NULL OR downloads.localPath IS NOT NULL)
          AND (:feedId IS NULL OR feeds.id = :feedId)
        """,
    )
    suspend fun getCandidatesForFeed(feedId: String?): List<SmartPlayCandidate>

    @Query(
        """
        SELECT DISTINCT episodes.id AS id,
               episodes.title AS title,
               feeds.title AS feedTitle,
               episodes.mediaUrl AS mediaUrl,
               episodes.mimeType AS mimeType,
               downloads.localPath AS localDownloadPath,
               downloads.state AS downloadState,
               feeds.customDownload AS feedDownloadAction,
               episodes.publishedAt AS publishedAt,
               episodes.positionMs AS positionMs,
               episodes.durationMs AS durationMs
        FROM episodes
        JOIN feeds ON feeds.id = episodes.feedId
        JOIN feed_categories ON feed_categories.feedId = feeds.id
        JOIN categories ON categories.id = feed_categories.categoryId
        LEFT JOIN downloads ON downloads.episodeId = episodes.id
        WHERE episodes.played = 0
          AND (episodes.mediaUrl IS NOT NULL OR downloads.localPath IS NOT NULL)
          AND categories.id = :categoryId
        """,
    )
    suspend fun getCandidatesForCategory(categoryId: String): List<SmartPlayCandidate>

    @Upsert
    suspend fun putRule(rule: SmartPlaylistEntity)

    @Query("DELETE FROM smart_playlists WHERE id = :ruleId")
    suspend fun deleteRule(ruleId: String)

    @Query(
        """
        DELETE FROM smart_playlists
        WHERE COALESCE(legacyPlaylistId, legacyRowId, 1) = :playlistId
        """,
    )
    suspend fun deletePlaylist(playlistId: Long)

    @Query("SELECT COALESCE(MAX(legacyPlaylistId), 0) + 1 FROM smart_playlists")
    suspend fun nextPlaylistId(): Long
}

@Dao
interface PlayerSettingsDao {
    @Upsert
    suspend fun putSettings(settings: PlayerSettingsEntity)

    @Query("SELECT * FROM player_settings WHERE id = 1")
    suspend fun getSettings(): PlayerSettingsEntity?

    @Query("SELECT * FROM player_settings WHERE id = 1")
    fun observeSettings(): Flow<PlayerSettingsEntity?>
}

@Dao
interface MigrationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRun(run: MigrationRunEntity)

    @Query(
        """
        SELECT * FROM migration_runs
        ORDER BY startedAt DESC
        LIMIT 1
        """,
    )
    fun observeLatestRun(): Flow<MigrationRunEntity?>
}
