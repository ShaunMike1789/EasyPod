package com.smgray.easypod.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.smgray.easypod.automation.FeedRefreshManager
import com.smgray.easypod.backup.EasyPodBackupService
import com.smgray.easypod.data.EasyPodRepository
import com.smgray.easypod.playback.PlaybackConnection
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SyncResolution {
    NORMAL,
    DOWNLOAD_AND_MERGE,
    REPLACE_REMOTE,
}

data class EasyPodSyncResult(
    val summary: String,
    val restoredFeeds: Int = 0,
    val restoredEpisodes: Int = 0,
)

class SyncNeedsResolutionException(message: String) : IllegalStateException(message)

internal enum class SyncPlan {
    CREATE_REMOTE,
    UPLOAD_LOCAL,
    DOWNLOAD_AND_MERGE,
    UP_TO_DATE,
    NEEDS_RESOLUTION,
}

internal fun chooseSyncPlan(
    remoteEtag: String?,
    localFingerprint: String,
    baselineRemoteEtag: String?,
    baselineLocalFingerprint: String?,
): SyncPlan {
    if (remoteEtag == null) return SyncPlan.CREATE_REMOTE
    if (baselineRemoteEtag == null || baselineLocalFingerprint == null) {
        return SyncPlan.NEEDS_RESOLUTION
    }
    val remoteChanged = remoteEtag != baselineRemoteEtag
    val localChanged = localFingerprint != baselineLocalFingerprint
    return when {
        remoteChanged && localChanged -> SyncPlan.NEEDS_RESOLUTION
        remoteChanged -> SyncPlan.DOWNLOAD_AND_MERGE
        localChanged -> SyncPlan.UPLOAD_LOCAL
        else -> SyncPlan.UP_TO_DATE
    }
}

class EasyPodSyncManager private constructor(
    context: Context,
    private val backupService: EasyPodBackupService,
    private val repository: EasyPodRepository,
    private val feedRefreshManager: FeedRefreshManager,
    private val playbackConnection: PlaybackConnection,
    private val client: WebDavSyncClient,
) {
    constructor(
        context: Context,
        backupService: EasyPodBackupService,
        repository: EasyPodRepository,
        feedRefreshManager: FeedRefreshManager,
        playbackConnection: PlaybackConnection,
    ) : this(
        context,
        backupService,
        repository,
        feedRefreshManager,
        playbackConnection,
        WebDavSyncClient(),
    )

    private val appContext = context.applicationContext
    private val settingsStore = EasyPodSyncSettingsStore(appContext)
    private val workManager = WorkManager.getInstance(appContext)
    private val syncMutex = Mutex()

    val settings: StateFlow<EasyPodSyncSettings> = settingsStore.settings

    fun initialize() {
        reconcileSchedule(settings.value)
    }

    fun saveConfiguration(
        enabled: Boolean,
        endpoint: String,
        username: String,
        password: String?,
        intervalHours: Int,
        wifiOnly: Boolean,
        chargingOnly: Boolean,
    ): EasyPodSyncSettings {
        val updated = settingsStore.saveConfiguration(
            enabled = enabled,
            endpoint = endpoint,
            username = username,
            password = password,
            intervalHours = intervalHours,
            wifiOnly = wifiOnly,
            chargingOnly = chargingOnly,
        )
        reconcileSchedule(updated)
        return updated
    }

    suspend fun sync(
        resolution: SyncResolution = SyncResolution.NORMAL,
    ): EasyPodSyncResult = syncMutex.withLock {
        val current = settings.value
        require(current.endpoint.isNotBlank()) {
            "Configure a WebDAV backup file URL first"
        }
        val endpoint = normalizeSyncEndpoint(current.endpoint)
        val credentials = settingsStore.credentials()
        settingsStore.updateResult("Syncing", "Checking the remote backup")
        try {
            withTemporaryDirectory { directory ->
                val localFingerprint = backupService.fingerprint()
                val remote = client.inspect(endpoint, credentials)
                val result = when (resolution) {
                    SyncResolution.DOWNLOAD_AND_MERGE -> {
                        requireNotNull(remote) { "There is no remote backup to download" }
                        downloadMergeAndUpload(
                            endpoint,
                            credentials,
                            directory,
                        )
                    }

                    SyncResolution.REPLACE_REMOTE -> {
                        val archive = File(directory, LOCAL_ARCHIVE_NAME)
                        backupService.export(archive)
                        val updatedRemote = client.upload(
                            endpoint,
                            credentials,
                            archive,
                            remote?.etag,
                        )
                        finishSuccess(
                            summary = "Uploaded this device's EasyPod library",
                            remote = updatedRemote,
                            localFingerprint = localFingerprint,
                        )
                    }

                    SyncResolution.NORMAL -> syncNormally(
                        endpoint = endpoint,
                        credentials = credentials,
                        remote = remote,
                        localFingerprint = localFingerprint,
                        directory = directory,
                    )
                }
                result
            }
        } catch (error: SyncNeedsResolutionException) {
            settingsStore.updateResult("Sync conflict", error.message)
            throw error
        } catch (error: Exception) {
            settingsStore.updateResult(
                "Sync failed",
                error.message ?: "The WebDAV sync could not be completed",
            )
            throw error
        }
    }

    private suspend fun syncNormally(
        endpoint: String,
        credentials: SyncCredentials,
        remote: RemoteSnapshot?,
        localFingerprint: String,
        directory: File,
    ): EasyPodSyncResult {
        val baselineRemote = settings.value.lastRemoteEtag
        val baselineLocal = settings.value.lastLocalFingerprint
        return when (
            chooseSyncPlan(
                remoteEtag = remote?.etag,
                localFingerprint = localFingerprint,
                baselineRemoteEtag = baselineRemote,
                baselineLocalFingerprint = baselineLocal,
            )
        ) {
            SyncPlan.CREATE_REMOTE -> {
                val archive = File(directory, LOCAL_ARCHIVE_NAME)
                backupService.export(archive)
                val uploaded = client.upload(
                    endpoint,
                    credentials,
                    archive,
                    expectedEtag = null,
                )
                finishSuccess(
                    summary = "Created the remote EasyPod backup",
                    remote = uploaded,
                    localFingerprint = localFingerprint,
                )
            }

            SyncPlan.NEEDS_RESOLUTION -> throw SyncNeedsResolutionException(
                if (baselineRemote == null || baselineLocal == null) {
                    "A remote backup already exists. Choose Download and merge or " +
                        "Replace remote to establish the first sync baseline."
                } else {
                    "This device and the remote backup both changed. Choose Download " +
                        "and merge or Replace remote."
                },
            )

            SyncPlan.DOWNLOAD_AND_MERGE -> downloadMergeAndUpload(
                endpoint,
                credentials,
                directory,
            )

            SyncPlan.UPLOAD_LOCAL -> {
                val archive = File(directory, LOCAL_ARCHIVE_NAME)
                backupService.export(archive)
                val uploaded = client.upload(
                    endpoint,
                    credentials,
                    archive,
                    expectedEtag = requireNotNull(remote).etag,
                )
                finishSuccess(
                    summary = "Uploaded local EasyPod changes",
                    remote = uploaded,
                    localFingerprint = localFingerprint,
                )
            }

            SyncPlan.UP_TO_DATE -> finishSuccess(
                summary = "EasyPod is already up to date",
                remote = requireNotNull(remote),
                localFingerprint = localFingerprint,
            )
        }
    }

    private suspend fun downloadMergeAndUpload(
        endpoint: String,
        credentials: SyncCredentials,
        directory: File,
    ): EasyPodSyncResult {
        val remoteArchive = File(directory, REMOTE_ARCHIVE_NAME)
        val downloaded = client.download(endpoint, credentials, remoteArchive)
        val restored = backupService.restore(remoteArchive)
        feedRefreshManager.initialize()
        playbackConnection.restoreQueue(
            repository.getQueue(),
            repository.getPlaybackState(),
        )

        val mergedFingerprint = backupService.fingerprint()
        val mergedArchive = File(directory, LOCAL_ARCHIVE_NAME)
        backupService.export(mergedArchive)
        val uploaded = client.upload(
            endpoint,
            credentials,
            mergedArchive,
            expectedEtag = downloaded.etag,
        )
        return finishSuccess(
            summary = "Downloaded, merged, and uploaded the EasyPod library",
            remote = uploaded,
            localFingerprint = mergedFingerprint,
            restoredFeeds = restored.feeds,
            restoredEpisodes = restored.episodes,
        )
    }

    private fun finishSuccess(
        summary: String,
        remote: RemoteSnapshot,
        localFingerprint: String,
        restoredFeeds: Int = 0,
        restoredEpisodes: Int = 0,
    ): EasyPodSyncResult {
        settingsStore.updateResult(
            status = "Synced",
            message = summary,
            syncedAt = System.currentTimeMillis(),
            remoteEtag = remote.etag,
            localFingerprint = localFingerprint,
        )
        return EasyPodSyncResult(
            summary = summary,
            restoredFeeds = restoredFeeds,
            restoredEpisodes = restoredEpisodes,
        )
    }

    private fun reconcileSchedule(settings: EasyPodSyncSettings) {
        if (!settings.enabled || settings.endpoint.isBlank()) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (settings.wifiOnly) {
                    NetworkType.UNMETERED
                } else {
                    NetworkType.CONNECTED
                },
            )
            .setRequiresCharging(settings.chargingOnly)
            .build()
        val request = PeriodicWorkRequestBuilder<EasyPodSyncWorker>(
            settings.intervalHours.toLong(),
            TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private suspend fun <T> withTemporaryDirectory(
        block: suspend (File) -> T,
    ): T {
        val directory = File(
            appContext.cacheDir,
            "sync/${UUID.randomUUID()}",
        )
        check(directory.mkdirs()) { "Could not create the sync workspace" }
        return try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private companion object {
        const val WORK_NAME = "easypod-webdav-sync"
        const val LOCAL_ARCHIVE_NAME = "local.easypod.zip"
        const val REMOTE_ARCHIVE_NAME = "remote.easypod.zip"
    }
}
