package com.example.convert2video.record

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.convert2video.data.RecordingRecord
import com.example.convert2video.utils.AppLogger
import kotlinx.coroutines.CancellationException

private const val TAG = "RecordingBackupTrigger"
private const val RECORDING_BACKUP_WORK_NAME_PREFIX = "recording_backup_"

/** The only transitions a recording backup worker accepts. */
internal enum class RecordingBackupAction {
    ACTIVE,
    TRASHED,
    REMOVE,
}

/** Metadata is needed only when an event has to create a previously absent manifest entry. */
internal data class RecordingBackupMetadata(
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val createdAt: Long,
    val deletedAt: Long? = null,
)

/** Immutable write-through event payload. The source path is intentionally worker input only. */
internal data class RecordingBackupEvent(
    val action: RecordingBackupAction,
    val backupId: Long,
    val format: RecordingFormat,
    val metadata: RecordingBackupMetadata? = null,
    val sourceFilePath: String? = null,
)

/** Per-identity unique work name SSOT. */
internal fun recordingBackupWorkName(backupId: Long): String =
    RECORDING_BACKUP_WORK_NAME_PREFIX + backupId

/**
 * Enqueues write-through backup events. [ExistingWorkPolicy.APPEND_OR_REPLACE] keeps events for
 * one stable recording identity ordered without coupling them to Drive or conversion work.
 */
internal object RecordingBackupTrigger {
    internal const val KEY_ACTION = "recording_backup_action"
    internal const val KEY_BACKUP_ID = "recording_backup_id"
    internal const val KEY_FORMAT = "recording_backup_format"
    internal const val KEY_DISPLAY_NAME = "recording_backup_display_name"
    internal const val KEY_DURATION_MS = "recording_backup_duration_ms"
    internal const val KEY_SIZE_BYTES = "recording_backup_size_bytes"
    internal const val KEY_CREATED_AT = "recording_backup_created_at"
    internal const val KEY_DELETED_AT = "recording_backup_deleted_at"
    internal const val KEY_SOURCE_FILE_PATH = "recording_backup_source_file_path"

    fun enqueue(context: Context, event: RecordingBackupEvent) {
        enqueue(event) { workName, policy, request ->
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(workName, policy, request)
        }
    }

    /** WorkManager seam for JVM tests; callers get no failure state from best-effort backup. */
    internal fun enqueue(
        event: RecordingBackupEvent,
        enqueueUniqueWork: (
            uniqueWorkName: String,
            existingWorkPolicy: ExistingWorkPolicy,
            request: OneTimeWorkRequest,
        ) -> Unit,
    ) {
        try {
            validateEvent(event)
            val request = OneTimeWorkRequestBuilder<RecordingBackupWorker>()
                .setInputData(inputDataFor(event))
                .build()
            enqueueUniqueWork(
                recordingBackupWorkName(event.backupId),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            AppLogger.e(TAG, "backup enqueue failed: ${exception.javaClass.simpleName}")
        }
    }

    internal fun active(
        record: RecordingRecord,
        sourceFilePath: String = record.filePath,
    ): RecordingBackupEvent? {
        val format = record.recordingFormatOrNull() ?: return null
        if (record.backupId <= 0L) return null
        return RecordingBackupEvent(
            action = RecordingBackupAction.ACTIVE,
            backupId = record.backupId,
            format = format,
            metadata = record.toBackupMetadata(),
            sourceFilePath = sourceFilePath,
        )
    }

    internal fun trashed(
        record: RecordingRecord,
        deletedAt: Long,
        sourceFilePath: String,
    ): RecordingBackupEvent? {
        val format = record.recordingFormatOrNull() ?: return null
        if (record.backupId <= 0L) return null
        return RecordingBackupEvent(
            action = RecordingBackupAction.TRASHED,
            backupId = record.backupId,
            format = format,
            metadata = record.toBackupMetadata(deletedAt = deletedAt),
            sourceFilePath = sourceFilePath,
        )
    }

    internal fun remove(
        backupId: Long?,
        formatName: String?,
    ): RecordingBackupEvent? {
        val format = formatName?.let { value ->
            runCatching { RecordingFormat.valueOf(value) }.getOrNull()
        } ?: return null
        if (backupId == null || backupId <= 0L) return null
        return RecordingBackupEvent(
            action = RecordingBackupAction.REMOVE,
            backupId = backupId,
            format = format,
        )
    }

    internal fun inputDataFor(event: RecordingBackupEvent): Data = Data.Builder()
        .putString(KEY_ACTION, event.action.name)
        .putLong(KEY_BACKUP_ID, event.backupId)
        .putString(KEY_FORMAT, event.format.name)
        .apply {
            event.metadata?.let { metadata ->
                putString(KEY_DISPLAY_NAME, metadata.displayName)
                putLong(KEY_DURATION_MS, metadata.durationMs)
                putLong(KEY_SIZE_BYTES, metadata.sizeBytes)
                putLong(KEY_CREATED_AT, metadata.createdAt)
                metadata.deletedAt?.let { deletedAt -> putLong(KEY_DELETED_AT, deletedAt) }
            }
            event.sourceFilePath?.let { path -> putString(KEY_SOURCE_FILE_PATH, path) }
        }
        .build()

    private fun validateEvent(event: RecordingBackupEvent) {
        require(event.backupId > 0L) { "Recording backup ID must be positive" }
        when (event.action) {
            RecordingBackupAction.REMOVE -> Unit
            RecordingBackupAction.ACTIVE,
            RecordingBackupAction.TRASHED,
            -> {
                val metadata = requireNotNull(event.metadata) { "Recording backup metadata is required" }
                require(metadata.displayName.isNotBlank()) { "Recording backup display name is required" }
                require(metadata.durationMs >= 0L && metadata.sizeBytes >= 0L && metadata.createdAt >= 0L)
                if (event.action == RecordingBackupAction.TRASHED) {
                    require(metadata.deletedAt != null && metadata.deletedAt >= 0L)
                } else {
                    require(metadata.deletedAt == null)
                }
            }
        }
    }
}

private fun RecordingRecord.recordingFormatOrNull(): RecordingFormat? =
    runCatching { RecordingFormat.valueOf(format) }.getOrNull()

private fun RecordingRecord.toBackupMetadata(deletedAt: Long? = null): RecordingBackupMetadata =
    RecordingBackupMetadata(
        displayName = java.io.File(filePath).name,
        durationMs = durationMs,
        sizeBytes = sizeBytes,
        createdAt = createdAt,
        deletedAt = deletedAt,
    )
