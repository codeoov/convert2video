package com.example.convert2video.record

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.example.convert2video.data.SettingsRepository
import com.example.convert2video.utils.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.IOException

/**
 * Applies one serialized write-through recording backup event to the selected SAF tree.
 * Backup is intentionally best-effort: local recording state remains authoritative in Phase 4.
 */
class RecordingBackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val settingsRepository = SettingsRepository(applicationContext)

    override suspend fun doWork(): Result {
        val event = eventFromInput(inputData)
        if (event == null) {
            AppLogger.e(TAG, "recording backup event is invalid")
            return Result.success()
        }
        return try {
            val storedUri = settingsRepository.recordingBackupFolderUri.first()
            if (storedUri.isNullOrBlank()) {
                AppLogger.w(TAG, "recording backup folder is unavailable")
                return Result.success()
            }
            if (!RecordingBackupFolder.isStoredUriAccessible(applicationContext, storedUri)) {
                AppLogger.w(TAG, "recording backup folder is unavailable")
                return Result.success()
            }
            val root = DocumentFile.fromTreeUri(applicationContext, Uri.parse(storedUri))
                ?: throw IOException("Recording backup root unavailable")
            processEvent(
                event = event,
                adapter = DocumentFileRecordingBackupSafAdapter(applicationContext.contentResolver, root),
                sourceFile = event.sourceFilePath?.takeIf(String::isNotBlank)?.let(::File),
            )
            Result.success()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            AppLogger.e(TAG, "recording backup event failed: ${exception.javaClass.simpleName}")
            Result.success()
        }
    }

    companion object {
        private const val TAG = "RecordingBackupWorker"

        /** Parses only the SSOT keys in [RecordingBackupTrigger]. */
        internal fun eventFromInput(inputData: Data): RecordingBackupEvent? {
            val action = inputData.getString(RecordingBackupTrigger.KEY_ACTION)
                ?.let { value -> runCatching { RecordingBackupAction.valueOf(value) }.getOrNull() }
                ?: return null
            val backupId = inputData.getLong(RecordingBackupTrigger.KEY_BACKUP_ID, 0L)
            if (backupId <= 0L) return null
            val format = inputData.getString(RecordingBackupTrigger.KEY_FORMAT)
                ?.let { value -> runCatching { RecordingFormat.valueOf(value) }.getOrNull() }
                ?: return null
            if (action == RecordingBackupAction.REMOVE) {
                return RecordingBackupEvent(action = action, backupId = backupId, format = format)
            }

            val displayName = inputData.getString(RecordingBackupTrigger.KEY_DISPLAY_NAME)
                ?.takeIf(String::isNotBlank)
                ?: return null
            val durationMs = inputData.getLong(RecordingBackupTrigger.KEY_DURATION_MS, -1L)
            val sizeBytes = inputData.getLong(RecordingBackupTrigger.KEY_SIZE_BYTES, -1L)
            val createdAt = inputData.getLong(RecordingBackupTrigger.KEY_CREATED_AT, -1L)
            if (durationMs < 0L || sizeBytes < 0L || createdAt < 0L) return null
            val deletedAt = when (action) {
                RecordingBackupAction.ACTIVE -> null
                RecordingBackupAction.TRASHED -> inputData
                    .getLong(RecordingBackupTrigger.KEY_DELETED_AT, -1L)
                    .takeIf { value -> value >= 0L }
                    ?: return null
                RecordingBackupAction.REMOVE -> return null
            }
            return RecordingBackupEvent(
                action = action,
                backupId = backupId,
                format = format,
                metadata = RecordingBackupMetadata(
                    displayName = displayName,
                    durationMs = durationMs,
                    sizeBytes = sizeBytes,
                    createdAt = createdAt,
                    deletedAt = deletedAt,
                ),
                sourceFilePath = inputData.getString(RecordingBackupTrigger.KEY_SOURCE_FILE_PATH)
                    ?.takeIf(String::isNotBlank),
            )
        }

        /**
         * SAF seam for JVM tests. The manifest lock covers the identity-file operation and its
         * read-transform-replace sequence, preventing cross-record lost updates.
         */
        internal suspend fun processEvent(
            event: RecordingBackupEvent,
            adapter: RecordingBackupSafAdapter,
            sourceFile: File?,
        ) {
            val identityFileName = recordingBackupFileName(event.backupId, event.format)
            RecordingBackupManifest.mutateInSaf(adapter) { safAdapter, current ->
                when (event.action) {
                    RecordingBackupAction.ACTIVE,
                    RecordingBackupAction.TRASHED,
                    -> applyActiveOrTrashedEvent(
                        event = event,
                        identityFileName = identityFileName,
                        adapter = safAdapter,
                        current = current,
                        sourceFile = sourceFile,
                    )
                    RecordingBackupAction.REMOVE -> {
                        removeIdentityFile(safAdapter, identityFileName)
                        current?.let { manifest ->
                            manifest.copy(entries = manifest.entries.filterNot { it.id == event.backupId })
                        }
                    }
                }
            }
        }

        private suspend fun applyActiveOrTrashedEvent(
            event: RecordingBackupEvent,
            identityFileName: String,
            adapter: RecordingBackupSafAdapter,
            current: RecordingBackupManifest?,
            sourceFile: File?,
        ): RecordingBackupManifest? {
            val existing = current?.entries?.firstOrNull { entry -> entry.id == event.backupId }
            if (existing == null) {
                // A delayed Keep can use a later trash/restore source, but no source means no row.
                if (!copyIdentityFileIfNeeded(adapter, identityFileName, event.format, sourceFile)) {
                    return current
                }
                val metadata = requireNotNull(event.metadata)
                val created = RecordingBackupManifest.Entry(
                    id = event.backupId,
                    kind = event.toManifestKind(),
                    displayName = metadata.displayName,
                    format = event.format,
                    durationMs = metadata.durationMs,
                    sizeBytes = metadata.sizeBytes,
                    createdAt = metadata.createdAt,
                    deletedAt = metadata.deletedAt,
                )
                return (current ?: RecordingBackupManifest(emptyList()))
                    .copy(entries = listOf(created))
            }

            // Existing entries always retain their original metadata. If the payload vanished,
            // a later local source repairs it before the kind transition is committed.
            copyIdentityFileIfNeeded(adapter, identityFileName, event.format, sourceFile)
            val updated = existing.copy(
                kind = event.toManifestKind(),
                deletedAt = event.metadata?.deletedAt,
            )
            val manifest = requireNotNull(current)
            return manifest.copy(
                entries = manifest.entries.map { entry ->
                    if (entry.id == event.backupId) updated else entry
                },
            )
        }

        private suspend fun copyIdentityFileIfNeeded(
            adapter: RecordingBackupSafAdapter,
            identityFileName: String,
            format: RecordingFormat,
            sourceFile: File?,
        ): Boolean {
            val existing = adapter.findFile(identityFileName)
            if (existing != null) {
                if (!existing.exists || !existing.isFile || existing.isDirectory) {
                    throw IOException("Recording backup identity file is invalid")
                }
                return true
            }
            if (sourceFile == null || !sourceFile.isFile) return false

            val created = adapter.createFile(mimeTypeFor(format), identityFileName)
                ?: throw IOException("Recording backup identity file could not be created")
            try {
                if (!created.exists || !created.isFile || created.isDirectory) {
                    throw IOException("Recording backup identity file is invalid")
                }
                currentCoroutineContext().ensureActive()
                sourceFile.inputStream().use { input ->
                    adapter.openOutputStream(created)?.use { output ->
                        input.copyTo(output)
                        output.flush()
                    } ?: throw IOException("Recording backup output stream unavailable")
                }
                currentCoroutineContext().ensureActive()
                return true
            } catch (exception: CancellationException) {
                deletePartialIdentityFile(adapter, created, exception)
                throw exception
            } catch (exception: Exception) {
                deletePartialIdentityFile(adapter, created, exception)
                throw exception
            }
        }

        private fun removeIdentityFile(
            adapter: RecordingBackupSafAdapter,
            identityFileName: String,
        ) {
            val identity = adapter.findFile(identityFileName) ?: return
            if (!identity.exists || !identity.isFile || identity.isDirectory) {
                throw IOException("Recording backup identity file is invalid")
            }
            if (!adapter.delete(identity)) {
                throw IOException("Recording backup identity file deletion failed")
            }
        }

        private fun deletePartialIdentityFile(
            adapter: RecordingBackupSafAdapter,
            created: RecordingBackupSafFile,
            original: Throwable,
        ) {
            try {
                if (created.exists && !adapter.delete(created)) {
                    original.addSuppressed(IOException("Recording backup partial file cleanup failed"))
                }
            } catch (cleanup: CancellationException) {
                if (cleanup !== original) original.addSuppressed(cleanup)
            } catch (cleanup: Exception) {
                original.addSuppressed(cleanup)
            }
        }

        private fun RecordingBackupEvent.toManifestKind(): RecordingBackupManifest.Entry.Kind =
            when (action) {
                RecordingBackupAction.ACTIVE -> RecordingBackupManifest.Entry.Kind.ACTIVE
                RecordingBackupAction.TRASHED -> RecordingBackupManifest.Entry.Kind.TRASHED
                RecordingBackupAction.REMOVE -> error("REMOVE has no manifest kind")
            }

        private fun mimeTypeFor(format: RecordingFormat): String = when (format) {
            RecordingFormat.AAC -> "audio/mp4"
            RecordingFormat.WAV -> "audio/wav"
        }
    }
}
