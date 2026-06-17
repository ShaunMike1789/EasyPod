package com.smgray.easypod.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.smgray.easypod.EasyPodApplication
import java.io.IOException

class EasyPodSyncWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val manager = (applicationContext as EasyPodApplication).container.syncManager
        return try {
            manager.sync()
            Result.success()
        } catch (_: SyncNeedsResolutionException) {
            Result.success()
        } catch (_: SyncConflictException) {
            Result.retry()
        } catch (_: IOException) {
            Result.retry()
        } catch (_: Exception) {
            Result.failure()
        }
    }
}
