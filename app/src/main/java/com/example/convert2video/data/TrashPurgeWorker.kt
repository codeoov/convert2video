package com.example.convert2video.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.convert2video.utils.AppLogger
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Daily background purge of trashed items older than [TrashRepository.DEFAULT_RETENTION_DAYS].
 * Enqueued once from [com.example.convert2video.Convert2videoApplication.onCreate] as unique
 * periodic work ([TRASH_PURGE_UNIQUE_WORK_NAME], [ExistingPeriodicWorkPolicy.KEEP]).
 *
 * Per-item failures are handled inside [TrashRepository.purgeExpired]. [CancellationException]
 * always rethrows — worker cancellation must stay real cancellation, not [Result.failure].
 */
class TrashPurgeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val trashRepository = TrashRepository.create(applicationContext)

    override suspend fun doWork(): Result {
        return try {
            trashRepository.purgeExpired(retentionDays = TrashRepository.DEFAULT_RETENTION_DAYS)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "purge failed: ${e.javaClass.simpleName}")
            Result.failure()
        }
    }

    companion object {
        internal const val TAG = "TrashPurgeWorker"

        /** Unique periodic work name SSOT — do not duplicate as a string literal elsewhere. */
        const val TRASH_PURGE_UNIQUE_WORK_NAME = "trash_purge"

        /** Schedules the daily purge worker; safe to call on every cold start (KEEP). */
        fun schedulePeriodicWork(context: Context) {
            schedulePeriodicWork { name, policy, request ->
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniquePeriodicWork(name, policy, request)
            }
        }

        /**
         * Testable entry — Fake WM is a capturing [enqueueUniquePeriodicWork]
         * (WorkManager ctor is module-internal; JVM tests must not call real enqueue).
         */
        internal fun schedulePeriodicWork(
            enqueueUniquePeriodicWork: (
                uniqueWorkName: String,
                existingPeriodicWorkPolicy: ExistingPeriodicWorkPolicy,
                request: PeriodicWorkRequest,
            ) -> Unit,
        ) {
            try {
                val request = PeriodicWorkRequestBuilder<TrashPurgeWorker>(1, TimeUnit.DAYS)
                    .build()
                enqueueUniquePeriodicWork(
                    TRASH_PURGE_UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "schedulePeriodicWork failed: ${e.javaClass.simpleName}")
            }
        }
    }
}
