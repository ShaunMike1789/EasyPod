package com.smgray.easypod.automation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.smgray.easypod.EasyPodApplication
import com.smgray.easypod.data.AutomationSettingsEntity
import com.smgray.easypod.widgets.EasyPodWidgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class FeedRefreshWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result = workerMutex.withLock {
        withContext(Dispatchers.IO) {
            val container =
                (applicationContext as EasyPodApplication).container
            val database = container.database
            val automationDao = database.automationDao()
            val settings = automationDao.getSettings()
                ?: AutomationSettingsEntity().also {
                    automationDao.putSettings(it)
                }
            val feeds = database.libraryDao().getRefreshableFeeds()
            var refreshed = 0
            var failed = 0
            var downloadsQueued = 0
            val errors = mutableListOf<String>()

            automationDao.updateRunStatus(
                lastRunAt = System.currentTimeMillis(),
                status = STATUS_RUNNING,
                message = "Refreshing ${feeds.size} feeds",
            )

            feeds.forEach { feed ->
                val url = feed.feedUrl ?: return@forEach
                try {
                    val result = container.feedIngestionService.addOrRefresh(
                        url,
                        refreshWidgets = false,
                    )
                    refreshed++
                    if (
                        settings.autoDownloadEnabled &&
                        feed.customDownload in AUTO_DOWNLOAD_ACTIONS
                    ) {
                        val limit = feed.maxDownloads
                            .takeIf { it > 0 }
                            ?: settings.defaultMaxDownloads
                        result.newEpisodeIds.take(limit).forEach { episodeId ->
                            container.episodeDownloadManager.request(
                                episodeId = episodeId,
                                wifiOnly = settings.wifiOnly,
                                chargingOnly = settings.chargingOnly,
                            )
                            downloadsQueued++
                        }
                    }
                } catch (error: Exception) {
                    failed++
                    errors += "${feed.title}: ${error.message ?: "refresh failed"}"
                }
            }

            val message = buildString {
                append("Updated $refreshed feeds")
                if (downloadsQueued > 0) {
                    append("; queued $downloadsQueued downloads")
                }
                if (failed > 0) {
                    append("; $failed failed")
                    errors.firstOrNull()?.let { append(" ($it)") }
                }
            }
            automationDao.updateRunStatus(
                lastRunAt = System.currentTimeMillis(),
                status = if (failed == 0) STATUS_COMPLETE else STATUS_PARTIAL,
                message = message.take(MAX_STATUS_MESSAGE),
            )
            EasyPodWidgets.updateAll(applicationContext)

            if (refreshed == 0 && failed > 0 && runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                Result.success()
            }
        }
    }

    companion object {
        const val STATUS_RUNNING = "Running"
        const val STATUS_COMPLETE = "Complete"
        const val STATUS_PARTIAL = "Completed with errors"

        private val workerMutex = Mutex()
        private val AUTO_DOWNLOAD_ACTIONS = setOf(1, 4)
        private const val MAX_RETRIES = 2
        private const val MAX_STATUS_MESSAGE = 500
    }
}
