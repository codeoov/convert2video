package com.example.convert2video.drive

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.convert2video.data.RecordingRepository
import com.example.convert2video.data.SettingsRepository
import com.example.convert2video.record.RecordingFormat
import com.example.convert2video.store.StoreCapabilities
import com.example.convert2video.utils.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "DriveAutoUploadTrigger"
private const val DRIVE_AUTO_UPLOAD_WORK_NAME_PREFIX = "drive_auto_upload_"

/**
 * Enqueues [DriveAutoUploadWorker] after a recording is saved, when Drive auto-upload is enabled
 * and the user is authorized.
 *
 * Non-[CancellationException] failures are logged and swallowed so
 * [com.example.convert2video.record.RecordingService] never maps them to INDEX_FAILED.
 * [CancellationException] is logged and **rethrown** (cooperative cancel).
 */
suspend fun enqueueDriveAutoUploadIfEnabled(
    context: Context,
    file: File,
    format: RecordingFormat,
    settingsRepository: SettingsRepository,
    workManager: WorkManager,
    isAuthorized: () -> Boolean = { createDriveAuthGateway(context.applicationContext).isAuthorized },
    capabilities: StoreCapabilities = StoreCapabilities.current,
): Unit = enqueueDriveAutoUploadIfEnabled(
    context = context,
    file = file,
    format = format,
    settingsRepository = settingsRepository,
    isAuthorized = isAuthorized,
    fileUriFor = { ctx, f -> RecordingRepository.contentUriFor(ctx, f).toString() },
    capabilities = capabilities,
    enqueueUniqueWork = { name, policy, request ->
        workManager.enqueueUniqueWork(name, policy, request)
    },
)

/**
 * Testable entry — Fake WM is a capturing [enqueueUniqueWork]
 * (WorkManager ctor is module-internal; JVM tests must not call FileProvider).
 */
internal suspend fun enqueueDriveAutoUploadIfEnabled(
    context: Context,
    file: File,
    format: RecordingFormat,
    settingsRepository: SettingsRepository,
    isAuthorized: () -> Boolean,
    fileUriFor: (Context, File) -> String,
    enqueueUniqueWork: (
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ) -> Unit,
    capabilities: StoreCapabilities = StoreCapabilities.current,
): Unit {
    try {
        withContext(Dispatchers.IO) {
            if (!capabilities.supportsDrive) return@withContext
            if (!settingsRepository.driveAutoUploadEnabled.first()) return@withContext
            if (!isAuthorized()) return@withContext

            val wifiOnly = settingsRepository.wifiOnlyUpload.first()
            val networkType = networkTypeForDriveWifiOnly(wifiOnly)
            // String seam — avoids Android Uri.parse / FileProvider stubs on JVM unit tests.
            val fileUriString = fileUriFor(context, file)
            val request = OneTimeWorkRequestBuilder<DriveAutoUploadWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(networkType).build(),
                )
                .setInputData(
                    workDataOf(
                        DriveAutoUploadWorker.KEY_FILE_URI to fileUriString,
                        DriveAutoUploadWorker.KEY_DISPLAY_NAME to file.name,
                        DriveAutoUploadWorker.KEY_CONTENT_TYPE to driveContentTypeFor(format),
                    ),
                )
                .build()
            // Unique work name = FileProvider content URI string (per-file identity).
            // KEEP: 동일 content URI에 FAILED work가 남아 있으면 KEEP로 재enqueue 스킵될 수 있음 — 기획 수락.
            enqueueUniqueWork(
                driveAutoUploadWorkName(fileUriString),
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    } catch (ce: CancellationException) {
        AppLogger.w(TAG, "enqueue cancelled: ${ce.javaClass.simpleName}")
        throw ce
    } catch (e: Exception) {
        AppLogger.e(TAG, "enqueue failed: ${e.javaClass.simpleName}", e)
        return
    }
}

/** wifiOnlyUpload → WorkManager NetworkType (Drive auto-upload; not shared with YouTube). */
internal fun networkTypeForDriveWifiOnly(wifiOnly: Boolean): NetworkType =
    if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

/** Recording format → Drive upload Content-Type. */
internal fun driveContentTypeFor(format: RecordingFormat): String =
    when (format) {
        RecordingFormat.AAC -> "audio/mp4"
        RecordingFormat.WAV -> "audio/wav"
    }

/** Unique work name for a FileProvider content URI string. */
internal fun driveAutoUploadWorkName(fileUri: String): String =
    DRIVE_AUTO_UPLOAD_WORK_NAME_PREFIX + Uri.encode(fileUri)
