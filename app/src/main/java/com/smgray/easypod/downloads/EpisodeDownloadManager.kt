package com.smgray.easypod.downloads

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.smgray.easypod.data.DownloadEntity
import com.smgray.easypod.data.EasyPodDatabase
import java.io.File

class EpisodeDownloadManager(
    context: Context,
    private val database: EasyPodDatabase,
) {
    private val applicationContext = context.applicationContext
    private val workManager = WorkManager.getInstance(applicationContext)

    suspend fun request(
        episodeId: String,
        wifiOnly: Boolean = false,
        chargingOnly: Boolean = false,
    ) {
        val source = database.downloadDao().getDownloadSource(episodeId)
            ?: throw IllegalArgumentException("Episode no longer exists")
        require(!source.mediaUrl.isNullOrBlank()) {
            "This episode has no downloadable media"
        }

        val existing = database.downloadDao().getDownload(episodeId)
        if (
            existing?.state == DownloadState.COMPLETE &&
            existing.localPath?.let(::File)?.exists() == true
        ) {
            return
        }

        database.downloadDao().putDownload(
            DownloadEntity(
                episodeId = episodeId,
                state = DownloadState.QUEUED,
                localPath = null,
                bytesDownloaded = 0,
                totalBytes = 0,
                requestedAt = System.currentTimeMillis(),
                completedAt = null,
                errorMessage = null,
            ),
        )

        val request = OneTimeWorkRequestBuilder<EpisodeDownloadWorker>()
            .setInputData(
                Data.Builder()
                    .putString(EpisodeDownloadWorker.KEY_EPISODE_ID, episodeId)
                    .build(),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(
                        if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
                    )
                    .setRequiresCharging(chargingOnly)
                    .build(),
            )
            .addTag(TAG_DOWNLOAD)
            .addTag(episodeTag(episodeId))
            .build()
        workManager.enqueueUniqueWork(
            uniqueWorkName(episodeId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    suspend fun cancel(episodeId: String) {
        workManager.cancelUniqueWork(uniqueWorkName(episodeId))
        database.downloadDao().getDownload(episodeId)?.let { current ->
            database.downloadDao().updateDownload(
                episodeId = episodeId,
                state = DownloadState.CANCELLED,
                bytesDownloaded = current.bytesDownloaded,
                totalBytes = current.totalBytes,
                localPath = current.localPath,
                completedAt = null,
                errorMessage = null,
            )
        }
        partialFile(episodeId)?.delete()
    }

    suspend fun delete(episodeId: String) {
        workManager.cancelUniqueWork(uniqueWorkName(episodeId))
        database.downloadDao().getDownload(episodeId)?.localPath
            ?.let(::File)
            ?.takeIf { it.isFile && it.isInside(downloadDirectory()) }
            ?.delete()
        partialFile(episodeId)?.delete()
        database.downloadDao().deleteDownload(episodeId)
    }

    private fun partialFile(episodeId: String): File? =
        downloadDirectory().listFiles()
            ?.firstOrNull { it.name.startsWith("$episodeId.") && it.name.endsWith(".part") }

    private fun downloadDirectory(): File =
        File(applicationContext.filesDir, EpisodeDownloadWorker.DOWNLOAD_DIRECTORY)

    private fun File.isInside(parent: File): Boolean =
        canonicalFile.parentFile == parent.canonicalFile

    companion object {
        const val TAG_DOWNLOAD = "episode-download"

        fun uniqueWorkName(episodeId: String) = "episode-download-$episodeId"
        fun episodeTag(episodeId: String) = "episode-$episodeId"
    }
}
