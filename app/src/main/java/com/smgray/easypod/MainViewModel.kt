package com.smgray.easypod

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.smgray.easypod.automation.FeedRefreshManager
import com.smgray.easypod.backup.EasyPodBackupService
import com.smgray.easypod.data.EpisodeSummary
import com.smgray.easypod.data.LibrarySnapshot
import com.smgray.easypod.data.PlayerSettingsEntity
import com.smgray.easypod.data.EasyPodRepository
import com.smgray.easypod.data.SmartPlayRuleDraft
import com.smgray.easypod.downloads.DownloadState
import com.smgray.easypod.downloads.EpisodeDownloadManager
import com.smgray.easypod.feeds.FeedIngestionService
import com.smgray.easypod.feeds.PodcastDirectoryResult
import com.smgray.easypod.migration.LegacyBackupImporter
import com.smgray.easypod.playback.PlaybackConnection
import com.smgray.easypod.playback.PlaybackUiState
import com.smgray.easypod.smartplay.SmartPlayRuleEngine
import com.smgray.easypod.sync.EasyPodSyncManager
import com.smgray.easypod.sync.EasyPodSyncSettings
import com.smgray.easypod.sync.SyncNeedsResolutionException
import com.smgray.easypod.sync.SyncResolution
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MainUiState(
    val library: LibrarySnapshot = LibrarySnapshot(),
    val importState: ImportState = ImportState.Idle,
    val feedActionState: FeedActionState = FeedActionState.Idle,
    val backupActionState: BackupActionState = BackupActionState.Idle,
    val syncSettings: EasyPodSyncSettings = EasyPodSyncSettings(),
    val syncActionState: SyncActionState = SyncActionState.Idle,
    val playback: PlaybackUiState = PlaybackUiState(),
)

sealed interface ImportState {
    data object Idle : ImportState
    data object Running : ImportState
    data class Complete(val summary: String) : ImportState
    data class Failed(val message: String) : ImportState
}

sealed interface FeedActionState {
    data object Idle : FeedActionState
    data object Running : FeedActionState
    data class Complete(val summary: String) : FeedActionState
    data class Failed(val message: String) : FeedActionState
}

sealed interface BackupActionState {
    data object Idle : BackupActionState
    data object Running : BackupActionState
    data class Complete(val summary: String) : BackupActionState
    data class Failed(val message: String) : BackupActionState
}

sealed interface SyncActionState {
    data object Idle : SyncActionState
    data object Running : SyncActionState
    data class Complete(val summary: String) : SyncActionState
    data class NeedsResolution(val message: String) : SyncActionState
    data class Failed(val message: String) : SyncActionState
}

class MainViewModel(
    private val repository: EasyPodRepository,
    private val backupService: EasyPodBackupService,
    private val importer: LegacyBackupImporter,
    private val feedIngestionService: FeedIngestionService,
    private val episodeDownloadManager: EpisodeDownloadManager,
    private val feedRefreshManager: FeedRefreshManager,
    private val playbackConnection: PlaybackConnection,
    private val syncManager: EasyPodSyncManager,
) : ViewModel() {
    private val importState = MutableStateFlow<ImportState>(ImportState.Idle)
    private val feedActionState = MutableStateFlow<FeedActionState>(FeedActionState.Idle)
    private val backupActionState =
        MutableStateFlow<BackupActionState>(BackupActionState.Idle)
    private val syncActionState =
        MutableStateFlow<SyncActionState>(SyncActionState.Idle)

    private val baseUiState = combine(
        repository.librarySnapshot,
        importState,
        feedActionState,
        backupActionState,
        playbackConnection.uiState,
    ) { library, migration, feedAction, backupAction, playback ->
        MainUiState(
            library = library,
            importState = migration,
            feedActionState = feedAction,
            backupActionState = backupAction,
            playback = playback,
        )
    }

    val uiState = combine(
        baseUiState,
        syncManager.settings,
        syncActionState,
    ) { state, syncSettings, syncAction ->
        state.copy(
            syncSettings = syncSettings,
            syncActionState = syncAction,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState(),
    )

    fun importLegacyBackup(uri: Uri) {
        if (importState.value == ImportState.Running) return
        viewModelScope.launch {
            importState.value = ImportState.Running
            importState.value = try {
                val result = importer.import(uri)
                ImportState.Complete(
                    "Imported ${result.feeds} feeds and ${result.episodes} episodes",
                )
            } catch (error: Exception) {
                ImportState.Failed(error.message ?: "The import could not be completed")
            }
        }
    }

    fun handleIncomingIntent(intent: Intent) {
        when (val request = IncomingPodcastIntents.from(intent)) {
            is IncomingPodcastRequest.ImportLegacyBackup -> importLegacyBackup(request.uri)
            is IncomingPodcastRequest.ImportOpml -> importOpml(request.uri)
            is IncomingPodcastRequest.RestoreBackup -> restoreBackup(request.uri)
            is IncomingPodcastRequest.Subscribe -> addFeed(request.feedUrl)
            null -> Unit
        }
    }

    fun dismissImportMessage() {
        importState.value = ImportState.Idle
    }

    fun addFeed(url: String) {
        if (feedActionState.value == FeedActionState.Running) return
        viewModelScope.launch {
            feedActionState.value = FeedActionState.Running
            feedActionState.value = try {
                val result = feedIngestionService.addOrRefresh(url)
                FeedActionState.Complete(
                    "Added ${result.feedTitle} with ${result.episodeCount} episodes",
                )
            } catch (error: Exception) {
                FeedActionState.Failed(error.message ?: "The feed could not be added")
            }
        }
    }

    fun refreshFeed(url: String) {
        if (feedActionState.value == FeedActionState.Running) return
        viewModelScope.launch {
            feedActionState.value = FeedActionState.Running
            feedActionState.value = try {
                val result = feedIngestionService.addOrRefresh(url)
                FeedActionState.Complete(
                    "Updated ${result.feedTitle} with ${result.episodeCount} episodes" +
                        if (result.newEpisodeIds.isNotEmpty()) {
                            " (${result.newEpisodeIds.size} new)"
                        } else {
                            ""
                        },
                )
            } catch (error: Exception) {
                FeedActionState.Failed(error.message ?: "The feed could not be refreshed")
            }
        }
    }

    suspend fun searchPodcastDirectory(query: String): List<PodcastDirectoryResult> =
        feedIngestionService.searchDirectory(query)

    fun importOpml(uri: Uri) {
        if (feedActionState.value == FeedActionState.Running) return
        viewModelScope.launch {
            feedActionState.value = FeedActionState.Running
            feedActionState.value = try {
                val result = feedIngestionService.importOpml(uri)
                FeedActionState.Complete(
                    "Imported ${result.imported} subscriptions" +
                        if (result.failed > 0) " (${result.failed} could not refresh)" else "",
                )
            } catch (error: Exception) {
                FeedActionState.Failed(error.message ?: "The OPML file could not be imported")
            }
        }
    }

    fun exportOpml(uri: Uri) {
        if (feedActionState.value == FeedActionState.Running) return
        viewModelScope.launch {
            feedActionState.value = FeedActionState.Running
            feedActionState.value = try {
                val result = feedIngestionService.exportOpml(uri)
                FeedActionState.Complete(
                    "Exported ${result.exported} subscriptions",
                )
            } catch (error: Exception) {
                FeedActionState.Failed(error.message ?: "The OPML file could not be exported")
            }
        }
    }

    fun dismissFeedAction() {
        feedActionState.value = FeedActionState.Idle
    }

    fun exportBackup(uri: Uri) {
        if (backupActionState.value == BackupActionState.Running) return
        viewModelScope.launch {
            backupActionState.value = BackupActionState.Running
            backupActionState.value = try {
                val result = backupService.export(uri)
                BackupActionState.Complete(
                    "Backed up ${result.feeds} feeds and ${result.episodes} episodes",
                )
            } catch (error: Exception) {
                BackupActionState.Failed(
                    error.message ?: "The EasyPod backup could not be created",
                )
            }
        }
    }

    fun restoreBackup(uri: Uri) {
        if (backupActionState.value == BackupActionState.Running) return
        viewModelScope.launch {
            backupActionState.value = BackupActionState.Running
            backupActionState.value = try {
                val result = backupService.restore(uri)
                feedRefreshManager.initialize()
                playbackConnection.restoreQueue(
                    repository.getQueue(),
                    repository.getPlaybackState(),
                )
                BackupActionState.Complete(
                    "Restored ${result.feeds} feeds and ${result.episodes} episodes",
                )
            } catch (error: Exception) {
                BackupActionState.Failed(
                    error.message ?: "The EasyPod backup could not be restored",
                )
            }
        }
    }

    fun dismissBackupAction() {
        backupActionState.value = BackupActionState.Idle
    }

    fun saveSyncConfiguration(
        enabled: Boolean,
        endpoint: String,
        username: String,
        password: String?,
        intervalHours: Int,
        wifiOnly: Boolean,
        chargingOnly: Boolean,
    ) {
        syncActionState.value = try {
            syncManager.saveConfiguration(
                enabled = enabled,
                endpoint = endpoint,
                username = username,
                password = password,
                intervalHours = intervalHours,
                wifiOnly = wifiOnly,
                chargingOnly = chargingOnly,
            )
            SyncActionState.Complete("WebDAV sync settings saved")
        } catch (error: Exception) {
            SyncActionState.Failed(
                error.message ?: "The sync settings could not be saved",
            )
        }
    }

    fun syncNow(resolution: SyncResolution = SyncResolution.NORMAL) {
        if (syncActionState.value == SyncActionState.Running) return
        viewModelScope.launch {
            syncActionState.value = SyncActionState.Running
            syncActionState.value = try {
                val result = syncManager.sync(resolution)
                SyncActionState.Complete(result.summary)
            } catch (error: SyncNeedsResolutionException) {
                SyncActionState.NeedsResolution(
                    error.message ?: "Choose how to resolve the sync conflict",
                )
            } catch (error: Exception) {
                SyncActionState.Failed(
                    error.message ?: "The WebDAV sync could not be completed",
                )
            }
        }
    }

    fun dismissSyncAction() {
        syncActionState.value = SyncActionState.Idle
    }

    fun playEpisode(episode: EpisodeSummary) {
        viewModelScope.launch {
            val queue = repository.enqueueEpisode(episode.id)
            playbackConnection.playQueue(queue, episode.id)
        }
    }

    fun playQueuedEpisode(episodeId: String) {
        viewModelScope.launch {
            playbackConnection.playQueue(repository.getQueue(), episodeId)
        }
    }

    fun playHistoryEpisode(episodeId: String) {
        viewModelScope.launch {
            val queue = repository.enqueueEpisode(episodeId)
            playbackConnection.playQueue(queue, episodeId)
        }
    }

    fun addToQueue(episodeId: String) {
        viewModelScope.launch {
            playbackConnection.syncQueue(repository.enqueueEpisode(episodeId))
        }
    }

    fun removeFromQueue(episodeId: String) {
        viewModelScope.launch {
            playbackConnection.syncQueue(repository.removeFromQueue(episodeId))
        }
    }

    fun moveQueueItem(episodeId: String, direction: Int) {
        viewModelScope.launch {
            playbackConnection.syncQueue(
                repository.moveQueueItem(episodeId, direction),
            )
        }
    }

    fun clearQueue() {
        viewModelScope.launch {
            playbackConnection.syncQueue(repository.clearQueue())
        }
    }

    fun requestDownload(episodeId: String) {
        viewModelScope.launch {
            episodeDownloadManager.request(episodeId)
        }
    }

    fun cancelDownload(episodeId: String) {
        viewModelScope.launch {
            episodeDownloadManager.cancel(episodeId)
        }
    }

    fun deleteDownload(episodeId: String) {
        viewModelScope.launch {
            episodeDownloadManager.delete(episodeId)
        }
    }

    fun toggleDownload(episode: EpisodeSummary) {
        when (episode.downloadState) {
            DownloadState.QUEUED,
            DownloadState.RUNNING,
            -> cancelDownload(episode.id)

            DownloadState.COMPLETE -> deleteDownload(episode.id)
            else -> requestDownload(episode.id)
        }
    }

    fun setEpisodePlayed(episodeId: String, played: Boolean) {
        viewModelScope.launch {
            val playback = uiState.value.playback
            if (playback.episodeId == episodeId) {
                playbackConnection.seekTo(
                    if (played) playback.durationMs else 0L,
                )
            }
            repository.setEpisodePlayed(episodeId, played)
        }
    }

    fun setEpisodeLocked(episodeId: String, locked: Boolean) {
        viewModelScope.launch {
            repository.setEpisodeLocked(episodeId, locked)
        }
    }

    fun setFeedAutoDownload(feedId: String, enabled: Boolean) {
        viewModelScope.launch {
            repository.setFeedAutoDownload(
                feedId = feedId,
                enabled = enabled,
                maxDownloads = uiState.value.library.automation.defaultMaxDownloads,
            )
        }
    }

    fun createCategory(name: String) {
        viewModelScope.launch {
            repository.createCategory(name)
        }
    }

    fun setFeedCategory(
        feedId: String,
        categoryId: String,
        selected: Boolean,
    ) {
        viewModelScope.launch {
            repository.setFeedCategory(feedId, categoryId, selected)
        }
    }

    fun deleteCategory(categoryId: String) {
        viewModelScope.launch {
            repository.deleteCategory(categoryId)
        }
    }

    fun setAutomaticRefreshEnabled(enabled: Boolean) {
        updateAutomation { it.copy(refreshEnabled = enabled) }
    }

    fun setRefreshInterval(hours: Int) {
        updateAutomation { it.copy(refreshIntervalHours = hours) }
    }

    fun setRefreshWifiOnly(enabled: Boolean) {
        updateAutomation { it.copy(wifiOnly = enabled) }
    }

    fun setRefreshChargingOnly(enabled: Boolean) {
        updateAutomation { it.copy(chargingOnly = enabled) }
    }

    fun setAutoDownloadEnabled(enabled: Boolean) {
        updateAutomation { it.copy(autoDownloadEnabled = enabled) }
    }

    fun setDefaultMaxDownloads(count: Int) {
        updateAutomation { it.copy(defaultMaxDownloads = count) }
    }

    fun refreshAllFeeds() {
        viewModelScope.launch {
            feedRefreshManager.refreshNow()
        }
    }

    fun loadSmartPlaylist(playlistId: Long, play: Boolean) {
        viewModelScope.launch {
            val queue = repository.generateSmartPlaylist(playlistId)
            if (play && queue.isNotEmpty()) {
                playbackConnection.playQueue(queue, queue.first().id)
            } else {
                playbackConnection.syncQueue(queue)
            }
        }
    }

    fun createSmartPlaylist(
        name: String,
        feedId: String?,
        episodeCount: Int,
        sortMode: Int,
    ) {
        viewModelScope.launch {
            repository.createSmartPlaylist(
                name = name,
                feedId = feedId,
                episodeCount = episodeCount,
                sortMode = sortMode.takeIf {
                    it in setOf(
                        SmartPlayRuleEngine.SORT_LATEST,
                        SmartPlayRuleEngine.SORT_OLDEST,
                        SmartPlayRuleEngine.SORT_RANDOM,
                    )
                } ?: SmartPlayRuleEngine.SORT_LATEST,
            )
        }
    }

    fun saveSmartPlaylist(
        playlistId: Long?,
        name: String,
        rules: List<SmartPlayRuleDraft>,
    ) {
        viewModelScope.launch {
            repository.saveSmartPlaylist(playlistId, name, rules)
        }
    }

    fun deleteSmartPlaylist(playlistId: Long) {
        viewModelScope.launch {
            repository.deleteSmartPlaylist(playlistId)
        }
    }

    private fun updateAutomation(
        transform: (com.smgray.easypod.data.AutomationSettingsEntity) ->
            com.smgray.easypod.data.AutomationSettingsEntity,
    ) {
        viewModelScope.launch {
            feedRefreshManager.save(
                transform(uiState.value.library.automation),
            )
        }
    }

    private fun updatePlayerSettings(
        transform: (PlayerSettingsEntity) -> PlayerSettingsEntity,
    ) {
        viewModelScope.launch {
            repository.savePlayerSettings(
                transform(uiState.value.library.playerSettings),
            )
        }
    }

    fun togglePlayPause() {
        playbackConnection.togglePlayPause()
    }

    fun skipPrevious() {
        playbackConnection.skipPrevious()
    }

    fun skipNext() {
        playbackConnection.skipNext()
    }

    fun seekBy(offsetMs: Long) {
        playbackConnection.seekBy(offsetMs)
    }

    fun seekTo(positionMs: Long) {
        playbackConnection.seekTo(positionMs)
    }

    fun rewind() {
        playbackConnection.seekBy(
            -uiState.value.library.playerSettings.backwardSkipSeconds * 1_000L,
        )
    }

    fun forward() {
        playbackConnection.seekBy(
            uiState.value.library.playerSettings.forwardSkipSeconds * 1_000L,
        )
    }

    fun setForwardSkip(seconds: Int) {
        updatePlayerSettings { it.copy(forwardSkipSeconds = seconds) }
    }

    fun setBackwardSkip(seconds: Int) {
        updatePlayerSettings { it.copy(backwardSkipSeconds = seconds) }
    }

    fun setLoudnessBoost(enabled: Boolean) {
        updatePlayerSettings { it.copy(loudnessBoostEnabled = enabled) }
    }

    fun setPauseOnHeadsetDisconnect(enabled: Boolean) {
        updatePlayerSettings { it.copy(pauseOnHeadsetDisconnect = enabled) }
    }

    fun setDefaultSleepMinutes(minutes: Int) {
        updatePlayerSettings { it.copy(defaultSleepMinutes = minutes) }
    }

    fun setSleepTimer(minutes: Int) {
        playbackConnection.setSleepTimer(minutes)
    }

    fun cancelSleepTimer() {
        playbackConnection.cancelSleepTimer()
    }

    fun cyclePlaybackSpeed() {
        val current = uiState.value.playback.playbackSpeed
        val next = PLAYBACK_SPEEDS.firstOrNull { it > current + 0.01f }
            ?: PLAYBACK_SPEEDS.first()
        playbackConnection.setPlaybackSpeed(next)
    }

    fun setPlaybackSpeed(speed: Float) {
        playbackConnection.setPlaybackSpeed(speed.coerceIn(0.5f, 3f))
    }

    companion object {
        private val PLAYBACK_SPEEDS = listOf(1f, 1.25f, 1.5f, 2f)

        fun factory(
            repository: EasyPodRepository,
            backupService: EasyPodBackupService,
            importer: LegacyBackupImporter,
            feedIngestionService: FeedIngestionService,
            episodeDownloadManager: EpisodeDownloadManager,
            feedRefreshManager: FeedRefreshManager,
            playbackConnection: PlaybackConnection,
            syncManager: EasyPodSyncManager,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MainViewModel(
                    repository,
                    backupService,
                    importer,
                    feedIngestionService,
                    episodeDownloadManager,
                    feedRefreshManager,
                    playbackConnection,
                    syncManager,
                )
            }
        }
    }
}
