package com.example.convert2video.record

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.convert2video.data.RecordingRecord
import com.example.convert2video.data.RecordingRepository
import com.example.convert2video.data.TrashedItem
import com.example.convert2video.data.TrashRepository
import com.example.convert2video.utils.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Reconciles one user-selected backup tree into the app-local recording stores.
 *
 * The local files and Room rows remain the runtime source of truth. The SAF tree is read only
 * during an explicit folder reconnect, so normal playback and trash operations do not become
 * dual-storage operations.
 */
internal class RecordingBackupReconciler(
    context: Context,
    private val recordingRepository: RecordingRepository = RecordingRepository.create(context),
    private val trashRepository: TrashRepository = TrashRepository.create(context),
) {
    private val appContext = context.applicationContext

    internal data class Summary(
        val restoredActiveCount: Int = 0,
        val restoredTrashCount: Int = 0,
        val expiredTrashCount: Int = 0,
        val skippedCount: Int = 0,
    ) {
        val restoredCount: Int get() = restoredActiveCount + restoredTrashCount
    }

    suspend fun reconcile(
        treeUri: Uri,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Summary = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(appContext, treeUri)
            ?: throw IOException("Recording backup root unavailable")
        val adapter = DocumentFileRecordingBackupSafAdapter(appContext.contentResolver, root)
        val localRecordingIds = recordingRepository.queryAllRecordings()
            .mapNotNull { record -> record.backupId.takeIf { it > 0L } }
            .toMutableSet()
        val localTrashIds = trashRepository.queryAllItems()
            .mapNotNull { item -> item.recordingBackupId?.takeIf { it > 0L } }
            .toMutableSet()
        val counters = Counters()

        RecordingBackupManifest.mutateInSaf(adapter) { _, current ->
            if (current == null) return@mutateInSaf null

            val keptEntries = buildList {
                current.entries.forEach { entry ->
                    currentCoroutineContext().ensureActive()
                    if (entry.kind == RecordingBackupManifest.Entry.Kind.TRASHED &&
                        isRecordingBackupTrashEntryExpired(entry.deletedAt, nowEpochMs)
                    ) {
                        if (deleteExpiredEntry(adapter, entry)) {
                            counters.expiredTrashCount += 1
                        } else {
                            counters.skippedCount += 1
                            add(entry)
                        }
                        return@forEach
                    }

                    val alreadyLocal = when (entry.kind) {
                        RecordingBackupManifest.Entry.Kind.ACTIVE ->
                            entry.id in localRecordingIds || entry.id in localTrashIds
                        RecordingBackupManifest.Entry.Kind.TRASHED ->
                            entry.id in localTrashIds || entry.id in localRecordingIds
                    }
                    if (alreadyLocal) {
                        add(entry)
                        return@forEach
                    }

                    when (entry.kind) {
                        RecordingBackupManifest.Entry.Kind.ACTIVE -> {
                            if (restoreActiveEntry(adapter, entry)) {
                                localRecordingIds += entry.id
                                counters.restoredActiveCount += 1
                            } else {
                                counters.skippedCount += 1
                            }
                        }

                        RecordingBackupManifest.Entry.Kind.TRASHED -> {
                            if (restoreTrashedEntry(adapter, entry)) {
                                localTrashIds += entry.id
                                counters.restoredTrashCount += 1
                            } else {
                                counters.skippedCount += 1
                            }
                        }
                    }
                    add(entry)
                }
            }
            if (keptEntries.size == current.entries.size) current
            else current.copy(entries = keptEntries)
        }
        counters.toSummary()
    }

    private suspend fun restoreActiveEntry(
        adapter: RecordingBackupSafAdapter,
        entry: RecordingBackupManifest.Entry,
    ): Boolean {
        val destinationDirectory = try {
            C2vRecordingNames.appStorageDir(appContext)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logFailure("recording storage unavailable", exception)
            return false
        }
        val preferredName = preferredDisplayName(entry)
        val destination = try {
            C2vRecordingNames.claimUniqueDestFile(destinationDirectory, preferredName)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logFailure("recording destination unavailable", exception)
            return false
        }
        if (!copyIdentityFile(adapter, entry, destination.destFile)) {
            deleteLocalFile(destination.destFile)
            return false
        }

        val record = RecordingRecord(
            filePath = destination.destFile.absolutePath,
            format = entry.format.name,
            durationMs = entry.durationMs,
            sizeBytes = destination.destFile.length(),
            createdAt = entry.createdAt,
            backupId = entry.id,
        )
        return try {
            recordingRepository.insertRehydratedRecording(record)
            true
        } catch (exception: CancellationException) {
            deleteLocalFile(destination.destFile)
            throw exception
        } catch (exception: Exception) {
            logFailure("recording row restore failed", exception)
            deleteLocalFile(destination.destFile)
            false
        }
    }

    private suspend fun restoreTrashedEntry(
        adapter: RecordingBackupSafAdapter,
        entry: RecordingBackupManifest.Entry,
    ): Boolean {
        val destinationDirectory = TrashRepository.trashDir(appContext)
        val destination = try {
            File.createTempFile("c2v_rehydrated_", ".${entry.format.fileExtension}", destinationDirectory)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logFailure("trash storage unavailable", exception)
            return false
        }
        if (!copyIdentityFile(adapter, entry, destination)) {
            deleteLocalFile(destination)
            return false
        }

        val item = TrashedItem(
            itemType = TrashedItem.RECORDING_AUDIO,
            displayName = entry.displayName,
            trashFilePath = destination.absolutePath,
            deletedAt = requireNotNull(entry.deletedAt),
            wasIndexed = true,
            durationMs = entry.durationMs,
            recordingFormat = entry.format.name,
            recordingBackupId = entry.id,
        )
        return try {
            trashRepository.insertRehydratedRecording(item)
            true
        } catch (exception: CancellationException) {
            deleteLocalFile(destination)
            throw exception
        } catch (exception: Exception) {
            logFailure("trash row restore failed", exception)
            deleteLocalFile(destination)
            false
        }
    }

    private suspend fun copyIdentityFile(
        adapter: RecordingBackupSafAdapter,
        entry: RecordingBackupManifest.Entry,
        destination: File,
    ): Boolean {
        val identityName = recordingBackupFileName(entry.id, entry.format)
        val source = adapter.findFile(identityName)
        if (source == null || !source.exists || !source.isFile || source.isDirectory) {
            AppLogger.w(TAG, "recording backup identity file unavailable")
            return false
        }
        return try {
            currentCoroutineContext().ensureActive()
            adapter.openInputStream(source)?.use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            } ?: throw IOException("Recording backup input stream unavailable")
            currentCoroutineContext().ensureActive()
            if (destination.length() != entry.sizeBytes) {
                throw IOException("Recording backup size mismatch")
            }
            true
        } catch (exception: CancellationException) {
            deleteLocalFile(destination)
            throw exception
        } catch (exception: Exception) {
            logFailure("recording backup file restore failed", exception)
            deleteLocalFile(destination)
            false
        }
    }

    private fun deleteExpiredEntry(
        adapter: RecordingBackupSafAdapter,
        entry: RecordingBackupManifest.Entry,
    ): Boolean {
        val identity = adapter.findFile(recordingBackupFileName(entry.id, entry.format)) ?: return true
        if (!identity.exists || !identity.isFile || identity.isDirectory) {
            AppLogger.w(TAG, "expired recording backup identity file is invalid")
            return false
        }
        return try {
            if (adapter.delete(identity)) true
            else {
                AppLogger.w(TAG, "expired recording backup identity file delete failed")
                false
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logFailure("expired recording backup cleanup failed", exception)
            false
        }
    }

    private fun preferredDisplayName(entry: RecordingBackupManifest.Entry): String {
        val expectedExtension = ".${entry.format.fileExtension}"
        val candidate = entry.displayName
        val isSafeCandidate = candidate.isNotBlank() &&
            candidate.endsWith(expectedExtension, ignoreCase = true) &&
            !candidate.contains('/') && !candidate.contains('\\') &&
            candidate != "." && candidate != ".."
        return if (isSafeCandidate) candidate else {
            C2vRecordingNames.buildDisplayName(
                format = entry.format,
                nowMillis = entry.createdAt,
            )
        }
    }

    private fun deleteLocalFile(file: File) {
        try {
            if (file.exists() && !file.delete()) {
                AppLogger.e(TAG, "rehydrated file cleanup failed")
            }
        } catch (exception: Exception) {
            AppLogger.e(TAG, "rehydrated file cleanup failed: ${exception.javaClass.simpleName}")
        }
    }

    private fun logFailure(message: String, exception: Exception) {
        AppLogger.e(TAG, "$message: ${exception.javaClass.simpleName}")
    }

    private class Counters {
        var restoredActiveCount = 0
        var restoredTrashCount = 0
        var expiredTrashCount = 0
        var skippedCount = 0

        fun toSummary(): Summary = Summary(
            restoredActiveCount = restoredActiveCount,
            restoredTrashCount = restoredTrashCount,
            expiredTrashCount = expiredTrashCount,
            skippedCount = skippedCount,
        )
    }

    private companion object {
        private const val TAG = "RecordingBackupReconciler"
    }
}

/** Pure retention rule shared by reconciliation tests and the reconnect implementation. */
internal fun isRecordingBackupTrashEntryExpired(
    deletedAt: Long?,
    nowEpochMs: Long,
): Boolean {
    if (deletedAt == null || nowEpochMs < deletedAt) return false
    return nowEpochMs - deletedAt >= TrashRepository.DEFAULT_RETENTION_DAYS * 24L * 60L * 60L * 1000L
}
