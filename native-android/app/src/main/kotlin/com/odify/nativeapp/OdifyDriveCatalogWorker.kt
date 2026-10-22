package com.odify.nativeapp

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Automatic 15-minute best-effort folder polling, minimum allowed by WorkManager. */
class OdifyDriveCatalogWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val sync = OdifyDriveSync(applicationContext)
        if (sync.folderId.isEmpty()) return Result.success()
        return try {
            sync.refresh(OdifyLibrary(applicationContext))
            Result.success()
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

object OdifyDriveScheduler {
    fun enable(context: Context) {
        val request = PeriodicWorkRequestBuilder<OdifyDriveCatalogWorker>(
            15, TimeUnit.MINUTES
        ).setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        ).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "odify_drive_catalog_refresh", ExistingPeriodicWorkPolicy.UPDATE, request
        )
    }
}