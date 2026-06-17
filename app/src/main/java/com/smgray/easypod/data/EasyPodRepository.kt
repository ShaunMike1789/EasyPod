package com.smgray.easypod.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import androidx.room.withTransaction
import java.nio.charset.StandardCharsets
import java.util.UUID
import com.smgray.easypod.smartplay.SmartPlayRuleEngine
import com.smgray.easypod.smartplay.SmartPlayRuleSpec

data class LibrarySnapshot(
    val feedCount: Int = 0,
    val episodeCount: Int = 0,
    val unplayedCount: Int = 0,
    val feeds: List<FeedSummary> = emptyList(),
    val categories: List<CategorySummary> = emptyList(),
    val episodes: List<EpisodeSummary> = emptyList(),
    val queue: List<QueueEpisodeSummary> = emptyList(),
    val downloads: List<DownloadSummary> = emptyList(),
    val playedHistory: List<PlayedHistorySummary> = emptyList(),
    val automation: AutomationSettingsEntity = AutomationSettingsEntity(),
    val smartPlaylists: List<SmartPlaylistSummary> = emptyList(),
    val smartPlayRules: List<SmartPlayRuleSummary> = emptyList(),
    val playerSettings: PlayerSettingsEntity = PlayerSettingsEntity(),
    val persistedPlayback: PlaybackStateEntity? = null,
    val latestMigration: MigrationRunEntity? = null,
)

private data class QueueAndDownloads(
    val queue: List<QueueEpisodeSummary>,
    val downloads: List<DownloadSummary>,
)

private data class PlayerConfiguration(
    val settings: PlayerSettingsEntity,
    val playbackState: PlaybackStateEntity?,
)

private data class SmartPlayConfiguration(
    val playlists: List<SmartPlaylistSummary>,
    val rules: List<SmartPlayRuleSummary>,
)

class EasyPodRepository(private val database: EasyPodDatabase) {
    private val libraryDao = database.libraryDao()
    private val playbackDao = database.playbackDao()
    private val downloadDao = database.downloadDao()
    private val automationDao = database.automationDao()
    private val smartPlayDao = database.smartPlayDao()
    private val playerSettingsDao = database.playerSettingsDao()
    private val migrationDao = database.migrationDao()

    private val libraryCounts = combine(
        libraryDao.observeFeedCount(),
        libraryDao.observeEpisodeCount(),
        libraryDao.observeUnplayedCount(),
    ) { feedCount, episodeCount, unplayedCount ->
        LibrarySnapshot(
            feedCount = feedCount,
            episodeCount = episodeCount,
            unplayedCount = unplayedCount,
        )
    }

    private val libraryContentWithoutMigration: Flow<LibrarySnapshot> = combine(
        libraryCounts,
        libraryDao.observeFeedSummaries(limit = 100),
        libraryDao.observeEpisodeSummaries(limit = 100),
        libraryDao.observeCategories(),
        libraryDao.observePlayedHistory(limit = 100),
    ) { counts, feeds, episodes, categories, playedHistory ->
        counts.copy(
            feeds = feeds,
            episodes = episodes,
            categories = categories,
            playedHistory = playedHistory,
        )
    }

    private val libraryContent: Flow<LibrarySnapshot> = combine(
        libraryContentWithoutMigration,
        migrationDao.observeLatestRun(),
    ) { library, latestMigration ->
        library.copy(latestMigration = latestMigration)
    }

    private val queueAndDownloads = combine(
        playbackDao.observeQueue(),
        downloadDao.observeDownloads(),
        ::QueueAndDownloads,
    )

    private val playerConfiguration = combine(
        playerSettingsDao.observeSettings(),
        playbackDao.observePlaybackState(),
    ) { settings, playbackState ->
        PlayerConfiguration(
            settings = settings ?: PlayerSettingsEntity(),
            playbackState = playbackState,
        )
    }

    private val smartPlayConfiguration = combine(
        smartPlayDao.observePlaylists(),
        smartPlayDao.observeRules(),
        ::SmartPlayConfiguration,
    )

    val librarySnapshot: Flow<LibrarySnapshot> = combine(
        libraryContent,
        queueAndDownloads,
        automationDao.observeSettings(),
        smartPlayConfiguration,
        playerConfiguration,
    ) { library, queueState, automation, smartPlay, playerConfig ->
        library.copy(
            queue = queueState.queue,
            downloads = queueState.downloads,
            automation = automation ?: AutomationSettingsEntity(),
            smartPlaylists = smartPlay.playlists,
            smartPlayRules = smartPlay.rules,
            playerSettings = playerConfig.settings,
            persistedPlayback = playerConfig.playbackState,
        )
    }

    suspend fun enqueueEpisode(episodeId: String): List<QueueEpisodeSummary> =
        database.withTransaction {
            requireNotNull(libraryDao.findEpisode(episodeId)) {
                "Episode no longer exists"
            }
            val entries = playbackDao.getQueueEntries()
            if (entries.none { it.episodeId == episodeId }) {
                playbackDao.putQueueEntry(
                    QueueEntryEntity(
                        episodeId = episodeId,
                        position = entries.size,
                        addedAt = System.currentTimeMillis(),
                    ),
                )
            }
            playbackDao.getQueue()
        }

    suspend fun removeFromQueue(episodeId: String): List<QueueEpisodeSummary> =
        database.withTransaction {
            val ids = playbackDao.getQueueEntries()
                .map(QueueEntryEntity::episodeId)
                .filterNot { it == episodeId }
            rewriteQueue(ids)
            playbackDao.getQueue()
        }

    suspend fun moveQueueItem(episodeId: String, direction: Int): List<QueueEpisodeSummary> =
        database.withTransaction {
            val ids = playbackDao.getQueueEntries()
                .map(QueueEntryEntity::episodeId)
                .toMutableList()
            val from = ids.indexOf(episodeId)
            if (from >= 0) {
                val to = (from + direction).coerceIn(ids.indices)
                if (to != from) {
                    val moved = ids.removeAt(from)
                    ids.add(to, moved)
                    rewriteQueue(ids)
                }
            }
            playbackDao.getQueue()
        }

    suspend fun clearQueue(): List<QueueEpisodeSummary> =
        database.withTransaction {
            playbackDao.clearQueue()
            playbackDao.getQueue()
        }

    suspend fun getQueue(): List<QueueEpisodeSummary> = playbackDao.getQueue()

    suspend fun getPlaybackState(): PlaybackStateEntity? =
        playbackDao.getPlaybackState()

    suspend fun setEpisodePlayed(episodeId: String, played: Boolean) {
        database.withTransaction {
            val episode = requireNotNull(libraryDao.findEpisode(episodeId)) {
                "Episode no longer exists"
            }
            libraryDao.setEpisodePlayed(episodeId, played)
            if (played) {
                recordPlayedHistory(episode)
            }
        }
    }

    suspend fun updatePlaybackProgress(
        episodeId: String,
        positionMs: Long,
        durationMs: Long,
        played: Boolean,
    ) {
        database.withTransaction {
            val episode = libraryDao.findEpisode(episodeId) ?: return@withTransaction
            libraryDao.updateEpisodeProgress(
                episodeId = episodeId,
                positionMs = positionMs,
                durationMs = durationMs,
                played = played,
            )
            if (played && !episode.played) {
                recordPlayedHistory(episode)
            }
        }
    }

    suspend fun setEpisodeLocked(episodeId: String, locked: Boolean) {
        libraryDao.setEpisodeLocked(episodeId, locked)
    }

    suspend fun setFeedAutoDownload(
        feedId: String,
        enabled: Boolean,
        maxDownloads: Int,
    ) {
        libraryDao.setFeedAutoDownload(
            feedId = feedId,
            enabled = enabled,
            maxDownloads = maxDownloads.coerceIn(1, 20),
        )
    }

    suspend fun createCategory(name: String) {
        val normalized = name.trim()
        require(normalized.isNotEmpty()) { "Category name is required" }
        libraryDao.putCategories(
            listOf(
                CategoryEntity(
                    id = UUID.nameUUIDFromBytes(
                        "category:$normalized".toByteArray(StandardCharsets.UTF_8),
                    ).toString(),
                    name = normalized,
                    color = categoryColor(normalized),
                ),
            ),
        )
    }

    suspend fun setFeedCategory(
        feedId: String,
        categoryId: String,
        selected: Boolean,
    ) {
        database.withTransaction {
            val current = libraryDao.getFeedCategories(feedId)
            if (!selected) {
                libraryDao.removeFeedCategory(feedId, categoryId)
                return@withTransaction
            }
            if (current.any { it.categoryId == categoryId }) return@withTransaction
            require(current.size < MAX_FEED_CATEGORIES) {
                "A feed can belong to up to two categories"
            }
            val occupied = current.map(FeedCategoryCrossRef::slot).toSet()
            val slot = (0 until MAX_FEED_CATEGORIES).first { it !in occupied }
            libraryDao.putFeedCategories(
                listOf(
                    FeedCategoryCrossRef(
                        feedId = feedId,
                        categoryId = categoryId,
                        slot = slot,
                    ),
                ),
            )
        }
    }

    suspend fun deleteCategory(categoryId: String) {
        libraryDao.deleteCategory(categoryId)
    }

    suspend fun generateSmartPlaylist(
        playlistId: Long,
    ): List<QueueEpisodeSummary> {
        val rules = smartPlayDao.getRules(playlistId)
        val selected = mutableListOf<SmartPlayCandidate>()
        val selectedIds = mutableSetOf<String>()
        rules.forEach { rule ->
            val candidates = when {
                rule.feedId != null ->
                    smartPlayDao.getCandidatesForFeed(rule.feedId)

                rule.categoryId != null ->
                    smartPlayDao.getCandidatesForCategory(rule.categoryId)

                else -> smartPlayDao.getCandidatesForFeed(null)
            }
            val matches = SmartPlayRuleEngine.select(
                rule = SmartPlayRuleSpec(
                    episodeCount = rule.episodeCount,
                    mediaType = rule.mediaType,
                    sortMode = rule.sortMode,
                ),
                candidates = candidates,
                excludedEpisodeIds = selectedIds,
            )
            selected += matches
            selectedIds += matches.map(SmartPlayCandidate::id)
        }
        return database.withTransaction {
            rewriteQueue(selected.map(SmartPlayCandidate::id))
            playbackDao.getQueue()
        }
    }

    suspend fun firstSmartPlaylistId(): Long? = smartPlayDao.firstPlaylistId()

    suspend fun createSmartPlaylist(
        name: String,
        feedId: String?,
        episodeCount: Int,
        sortMode: Int,
    ): Long {
        val playlistId = smartPlayDao.nextPlaylistId()
        smartPlayDao.putRule(
            SmartPlaylistEntity(
                id = UUID.randomUUID().toString(),
                legacyRowId = null,
                legacyPlaylistId = playlistId,
                name = name.trim().ifBlank { "SmartPlay" },
                legacyFeedId = feedId,
                sortOrderRaw = 0,
                categoryIdRaw = null,
                episodeCount = episodeCount.coerceAtLeast(0),
                episodeFilterRaw = SmartPlayRuleEngine.MEDIA_ANY,
                playbackTypeRaw = sortMode,
                configRaw = null,
            ),
        )
        return playlistId
    }

    suspend fun saveSmartPlaylist(
        playlistId: Long?,
        name: String,
        rules: List<SmartPlayRuleDraft>,
    ): Long = database.withTransaction {
        require(rules.isNotEmpty()) { "A SmartPlay list needs at least one rule" }
        val savedPlaylistId = playlistId ?: smartPlayDao.nextPlaylistId()
        if (playlistId != null) {
            smartPlayDao.deletePlaylist(playlistId)
        }
        val savedName = name.trim().ifBlank { "SmartPlay" }
        rules.forEachIndexed { index, rule ->
            require(rule.feedId == null || rule.categoryId == null) {
                "A SmartPlay rule cannot target both a feed and a category"
            }
            smartPlayDao.putRule(
                SmartPlaylistEntity(
                    id = UUID.randomUUID().toString(),
                    legacyRowId = null,
                    legacyPlaylistId = savedPlaylistId,
                    name = savedName,
                    legacyFeedId = rule.feedId,
                    sortOrderRaw = index,
                    categoryIdRaw = rule.categoryId,
                    episodeCount = rule.episodeCount.coerceIn(1, 50),
                    episodeFilterRaw = rule.mediaType.takeIf {
                        it in setOf(
                            SmartPlayRuleEngine.MEDIA_ANY,
                            SmartPlayRuleEngine.MEDIA_AUDIO,
                            SmartPlayRuleEngine.MEDIA_VIDEO,
                        )
                    } ?: SmartPlayRuleEngine.MEDIA_ANY,
                    playbackTypeRaw = rule.sortMode.takeIf {
                        it in setOf(
                            SmartPlayRuleEngine.SORT_LATEST,
                            SmartPlayRuleEngine.SORT_OLDEST,
                            SmartPlayRuleEngine.SORT_RANDOM,
                        )
                    } ?: SmartPlayRuleEngine.SORT_LATEST,
                    configRaw = null,
                ),
            )
        }
        savedPlaylistId
    }

    suspend fun deleteSmartPlaylist(playlistId: Long) {
        smartPlayDao.deletePlaylist(playlistId)
    }

    suspend fun savePlayerSettings(settings: PlayerSettingsEntity) {
        playerSettingsDao.putSettings(
            settings.copy(
                forwardSkipSeconds = settings.forwardSkipSeconds.coerceIn(5, 300),
                backwardSkipSeconds = settings.backwardSkipSeconds.coerceIn(5, 300),
                defaultSleepMinutes = settings.defaultSleepMinutes.coerceIn(5, 180),
            ),
        )
    }

    private suspend fun rewriteQueue(episodeIds: List<String>) {
        val now = System.currentTimeMillis()
        playbackDao.clearQueue()
        if (episodeIds.isNotEmpty()) {
            playbackDao.putQueueEntries(
                episodeIds.mapIndexed { index, episodeId ->
                    QueueEntryEntity(
                        episodeId = episodeId,
                        position = index,
                        addedAt = now + index,
                    )
                },
            )
        }
    }

    private suspend fun recordPlayedHistory(episode: EpisodeEntity) {
        val feedUrl = episode.feedId
            ?.let { libraryDao.findFeed(it) }
            ?.feedUrl
        libraryDao.putEpisodeHistory(
            listOf(
                EpisodeHistoryEntity(
                    id = UUID.nameUUIDFromBytes(
                        "played:${episode.id}".toByteArray(StandardCharsets.UTF_8),
                    ).toString(),
                    legacyRowId = null,
                    episodeUrl = episode.mediaUrl ?: episode.localPath ?: episode.id,
                    feedUrl = feedUrl,
                    timestamp = System.currentTimeMillis(),
                    entryTypeRaw = HISTORY_TYPE_PLAYED,
                ),
            ),
        )
    }

    private companion object {
        const val HISTORY_TYPE_PLAYED = 1
        const val MAX_FEED_CATEGORIES = 2

        fun categoryColor(name: String): Int {
            val palette = intArrayOf(
                0xFF1565C0.toInt(),
                0xFF2E7D32.toInt(),
                0xFF6A1B9A.toInt(),
                0xFFAD1457.toInt(),
                0xFFEF6C00.toInt(),
                0xFF00838F.toInt(),
            )
            return palette[(name.hashCode() and Int.MAX_VALUE) % palette.size]
        }
    }
}
