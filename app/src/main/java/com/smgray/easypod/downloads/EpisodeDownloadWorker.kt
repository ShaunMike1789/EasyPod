package com.smgray.easypod.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.smgray.easypod.EasyPodApplication
import com.smgray.easypod.data.DownloadEntity
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.coroutines.coroutineContext

class EpisodeDownloadWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    private val database =
        (applicationContext as EasyPodApplication).container.database
    private val client = OkHttpClient()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val episodeId = inputData.getString(KEY_EPISODE_ID)
            ?: return@withContext Result.failure()
        val source = database.downloadDao().getDownloadSource(episodeId)
            ?: return@withContext Result.failure()
        val mediaUrl = source.mediaUrl
            ?: return@withContext fail(episodeId, "Episode media URL is missing")

        val directory = File(applicationContext.filesDir, DOWNLOAD_DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) {
            return@withContext fail(episodeId, "Unable to create the download folder")
        }
        val finalFile = File(
            directory,
            DownloadFileNames.forEpisode(episodeId, mediaUrl, source.mimeType),
        )
        val partialFile = File(directory, "${finalFile.name}.part")
        setForeground(notification(episodeId, source.title, 0, 0))

        try {
            database.downloadDao().putDownload(
                DownloadEntity(
                    episodeId = episodeId,
                    state = DownloadState.RUNNING,
                    localPath = null,
                    bytesDownloaded = 0,
                    totalBytes = 0,
                    requestedAt = System.currentTimeMillis(),
                    completedAt = null,
                    errorMessage = null,
                ),
            )

            val request = Request.Builder()
                .url(mediaUrl)
                .header("User-Agent", "EasyPod")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Download failed with HTTP ${response.code}")
                }
                val body = response.body
                val totalBytes = body.contentLength().coerceAtLeast(0L)
                require(totalBytes <= MAX_DOWNLOAD_BYTES) {
                    "Episode exceeds the supported download size"
                }

                partialFile.delete()
                FileOutputStream(partialFile).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var downloaded = 0L
                        var lastReported = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            downloaded += read
                            require(downloaded <= MAX_DOWNLOAD_BYTES) {
                                "Episode exceeds the supported download size"
                            }
                            output.write(buffer, 0, read)
                            if (downloaded - lastReported >= PROGRESS_STEP_BYTES) {
                                reportProgress(
                                    episodeId,
                                    source.title,
                                    downloaded,
                                    totalBytes,
                                )
                                lastReported = downloaded
                            }
                        }
                        output.fd.sync()
                        reportProgress(episodeId, source.title, downloaded, totalBytes)
                    }
                }

                finalFile.delete()
                if (!partialFile.renameTo(finalFile)) {
                    partialFile.copyTo(finalFile, overwrite = true)
                    partialFile.delete()
                }
                val completedBytes = finalFile.length()
                database.downloadDao().updateDownload(
                    episodeId = episodeId,
                    state = DownloadState.COMPLETE,
                    bytesDownloaded = completedBytes,
                    totalBytes = if (totalBytes > 0) totalBytes else completedBytes,
                    localPath = finalFile.absolutePath,
                    completedAt = System.currentTimeMillis(),
                    errorMessage = null,
                )
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            partialFile.delete()
            database.downloadDao().getDownload(episodeId)?.let { current ->
                database.downloadDao().updateDownload(
                    episodeId = episodeId,
                    state = DownloadState.CANCELLED,
                    bytesDownloaded = current.bytesDownloaded,
                    totalBytes = current.totalBytes,
                    localPath = null,
                    completedAt = null,
                    errorMessage = null,
                )
            }
            throw cancelled
        } catch (error: Exception) {
            partialFile.delete()
            fail(episodeId, error.message ?: "Download failed")
        }
    }

    private suspend fun reportProgress(
        episodeId: String,
        title: String,
        downloaded: Long,
        total: Long,
    ) {
        database.downloadDao().updateDownload(
            episodeId = episodeId,
            state = DownloadState.RUNNING,
            bytesDownloaded = downloaded,
            totalBytes = total,
            localPath = null,
            completedAt = null,
            errorMessage = null,
        )
        setForeground(notification(episodeId, title, downloaded, total))
    }

    private suspend fun fail(episodeId: String, message: String): Result {
        val current = database.downloadDao().getDownload(episodeId)
        database.downloadDao().updateDownload(
            episodeId = episodeId,
            state = DownloadState.FAILED,
            bytesDownloaded = current?.bytesDownloaded ?: 0,
            totalBytes = current?.totalBytes ?: 0,
            localPath = null,
            completedAt = null,
            errorMessage = message.take(MAX_ERROR_LENGTH),
        )
        return Result.failure()
    }

    private fun notification(
        episodeId: String,
        title: String,
        downloaded: Long,
        total: Long,
    ): ForegroundInfo {
        createNotificationChannel()
        val progress = if (total > 0) {
            ((downloaded * 100) / total).coerceIn(0, 100).toInt()
        } else {
            0
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading episode")
            .setContentText(title)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress, total <= 0)
            .build()
        val notificationId =
            NOTIFICATION_BASE_ID + episodeId.hashCode().ushr(1) % 10_000
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun createNotificationChannel() {
        val manager = applicationContext.getSystemService(
            Service.NOTIFICATION_SERVICE,
        ) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Episode downloads",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    companion object {
        const val KEY_EPISODE_ID = "episode_id"
        const val DOWNLOAD_DIRECTORY = "podcasts"

        private const val CHANNEL_ID = "episode_downloads"
        private const val NOTIFICATION_BASE_ID = 20_000
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_STEP_BYTES = 512L * 1024L
        private const val MAX_DOWNLOAD_BYTES = 4L * 1024L * 1024L * 1024L
        private const val MAX_ERROR_LENGTH = 300
    }
}
