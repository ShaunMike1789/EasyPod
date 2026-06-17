package com.smgray.easypod.automation

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.smgray.easypod.data.AutomationSettingsEntity
import com.smgray.easypod.data.EasyPodDatabase
import java.util.concurrent.TimeUnit

class FeedRefreshManager(
    context: Context,
    private val database: EasyPodDatabase,
) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    suspend fun initialize() {
        val settings = database.automationDao().getSettings()
            ?: AutomationSettingsEntity().also {
                database.automationDao().putSettings(it)
            }
        reconcileSchedule(settings)
    }

    suspend fun save(settings: AutomationSettingsEntity) {
        val sanitized = settings.copy(
            refreshIntervalHours = settings.refreshIntervalHours.coerceIn(1, 168),
            defaultMaxDownloads = settings.defaultMaxDownloads.coerceIn(1, 20),
        )
        database.automationDao().putSettings(sanitized)
        reconcileSchedule(sanitized)
    }

    suspend fun refreshNow() {
        val settings = database.automationDao().getSettings()
            ?: AutomationSettingsEntity()
        val request = OneTimeWorkRequestBuilder<FeedRefreshWorker>()
            .setConstraints(settings.constraints())
            .addTag(TAG_FEED_REFRESH)
            .build()
        workManager.enqueueUniqueWork(
            MANUAL_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private fun reconcileSchedule(settings: AutomationSettingsEntity) {
        if (!settings.refreshEnabled) {
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<FeedRefreshWorker>(
            settings.refreshIntervalHours.toLong(),
            TimeUnit.HOURS,
        )
            .setConstraints(settings.constraints())
            .addTag(TAG_FEED_REFRESH)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun AutomationSettingsEntity.constraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(
                if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
            )
            .setRequiresCharging(chargingOnly)
            .build()

    companion object {
        const val TAG_FEED_REFRESH = "feed-refresh"
        const val PERIODIC_WORK_NAME = "periodic-feed-refresh"
        const val MANUAL_WORK_NAME = "manual-feed-refresh"
    }
}
