package com.smgray.easypod

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.cast.Cast
import androidx.media3.common.util.UnstableApi
import com.smgray.easypod.automation.FeedRefreshManager
import com.smgray.easypod.backup.EasyPodBackupService
import com.smgray.easypod.data.EasyPodDatabase
import com.smgray.easypod.data.EasyPodRepository
import com.smgray.easypod.feeds.FeedIngestionService
import com.smgray.easypod.downloads.EpisodeDownloadManager
import com.smgray.easypod.migration.LegacyBackupImporter
import com.smgray.easypod.playback.PlaybackConnection
import com.smgray.easypod.data.PlayerSettingsEntity
import com.smgray.easypod.sync.EasyPodSyncManager
import com.smgray.easypod.widgets.EasyPodWidgets
import com.smgray.easypod.widgets.PlaybackWidgetStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EasyPodApplication : Application() {
    val container by lazy {
        AppContainer(this)
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        Cast.getSingletonInstance(this).initialize()
        container.start()
    }
}

class AppContainer(private val application: Application) {
    private val applicationScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database = EasyPodDatabase.create(application)
    val repository = EasyPodRepository(database)
    val backupService = EasyPodBackupService(application, database)
    val legacyBackupImporter = LegacyBackupImporter(application, database)
    val feedIngestionService = FeedIngestionService(application, database)
    val episodeDownloadManager = EpisodeDownloadManager(application, database)
    val feedRefreshManager = FeedRefreshManager(application, database)
    val playbackConnection = PlaybackConnection(application)
    val syncManager = EasyPodSyncManager(
        application,
        backupService,
        repository,
        feedRefreshManager,
        playbackConnection,
    )

    fun start() {
        applicationScope.launch {
            PlaybackWidgetStateStore.write(
                application,
                PlaybackWidgetStateStore.read(application).copy(isPlaying = false),
            )
            if (database.playerSettingsDao().getSettings() == null) {
                database.playerSettingsDao().putSettings(PlayerSettingsEntity())
            }
            feedRefreshManager.initialize()
            syncManager.initialize()
            EasyPodWidgets.updateAll(application)
        }
    }
}
