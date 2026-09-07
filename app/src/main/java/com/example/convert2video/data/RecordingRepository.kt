package com.example.convert2video.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.convert2video.record.RecordingBackupTrigger
import com.example.convert2video.record.RecordingFormat
import com.example.convert2video.record.recordingExtension
import com.example.convert2video.utils.AppLogger
import com.example.convert2video.video.C2vOutputNames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 녹음 파일 인덱싱·삭제·URI 발급.
 * 캡처는 [com.example.convert2video.record.RecordingEngine]/Service가 담당하고,
 * 이 Repository는 완성된 파일만 Room에 기록한다 (BackgroundRepository 패턴).
 */
class RecordingRepository(
    private val context: Context,
    private val dao: RecordingDao,
    private val trashRepository: TrashRepository = TrashRepository.create(context),
) {
    val recordings: Flow<List<RecordingRecord>> = dao.observeAll()

    /**
     * 캡처 백엔드가 이미 완성해 놓은 파일을 인덱싱만 한다.
     * @throws IllegalArgumentException 파일이 없거나 크기가 0인 경우
     */
    suspend fun recordFinishedRecording(
        file: File,
        format: RecordingFormat,
        durationMs: Long,
        createdAtMillis: Long = System.currentTimeMillis(),
    ): RecordingRecord {
        val sizeBytes = withContext(Dispatchers.IO) {
            if (!file.exists() || file.length() <= 0L) {
                throw IllegalArgumentException(
                    "Recording file missing or empty: ${file.name}",
                )
            }
            file.length()
        }
        val record = RecordingRecord(
            filePath = file.absolutePath,
            format = format.name,
            durationMs = durationMs,
            sizeBytes = sizeBytes,
            createdAt = createdAtMillis,
        )
        return dao.insertNewWithBackupId(record)
    }

    /**
     * 휴지통으로 이동 후 Room row 제거.
     * @return moveToTrash + dao 삭제 성공 시 true
     */
    suspend fun deleteRecording(record: RecordingRecord): Boolean = withContext(Dispatchers.IO) {
        val file = File(record.filePath)
        val trashed = try {
            trashRepository.moveToTrash(
                sourceFile = file,
                itemType = TrashedItem.RECORDING_AUDIO,
                displayName = file.name,
                wasIndexed = true,
                originalFilePath = record.filePath,
                durationMs = record.durationMs,
                recordingFormat = record.format,
                recordingBackupId = record.backupId.takeIf { it > 0L },
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            AppLogger.e(TAG, "deleteRecording moveToTrash failed: ${e.javaClass.simpleName}", e)
            null
        }
        if (trashed == null) {
            AppLogger.e(TAG, "deleteRecording moveToTrash failed: ${file.name}")
            return@withContext false
        }
        try {
            dao.deleteById(record.id)
            RecordingBackupTrigger.trashed(
                record = record,
                deletedAt = trashed.deletedAt,
                sourceFilePath = trashed.trashFilePath,
            )?.let { event ->
                RecordingBackupTrigger.enqueue(context, event)
            }
            true
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            AppLogger.e(TAG, "deleteRecording dao delete failed: ${e.javaClass.simpleName}", e)
            try {
                trashRepository.permanentlyDelete(
                    item = trashed,
                    enqueueRecordingBackupRemove = false,
                )
            } catch (rollbackCe: CancellationException) {
                throw rollbackCe
            } catch (rollbackEx: Exception) {
                AppLogger.e(
                    TAG,
                    "deleteRecording trash rollback failed: ${rollbackEx.javaClass.simpleName}",
                    rollbackEx,
                )
            }
            false
        }
    }

    /** Room 스냅샷 1회 — AudioPick loadRecordings 등. */
    suspend fun queryAllRecordings(): List<RecordingRecord> = recordings.first()

    /** Rehydrates one recording whose stable identity came from the backup manifest. */
    internal suspend fun insertRehydratedRecording(record: RecordingRecord): RecordingRecord =
        dao.insertRestored(record)

    /**
     * 녹음 파일 display name 변경. 확장자 미포함 시 format 기준 자동 부여.
     * 대상 이름이 이미 있으면 false (덮어쓰기 방지).
     * @return 성공 시 true — DB filePath도 갱신
     */
    suspend fun renameRecording(record: RecordingRecord, newDisplayName: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!isValidDisplayNameStem(newDisplayName)) return@withContext false
            val ext = recordingExtension(record.format)
            val safeName = if (newDisplayName.endsWith(".$ext", ignoreCase = true)) {
                newDisplayName
            } else {
                "$newDisplayName.$ext"
            }
            try {
                val oldFile = File(record.filePath)
                if (!oldFile.exists()) return@withContext false
                val dest = File(oldFile.parentFile, safeName)
                if (dest.exists()) return@withContext false
                val renamed = oldFile.renameTo(dest)
                if (!renamed) return@withContext false
                dao.updateFilePath(record.id, dest.absolutePath)
                true
            } catch (e: Exception) {
                AppLogger.e(
                    TAG,
                    "녹음 이름변경 실패: ${record.filePath}",
                    e,
                )
                false
            }
        }

    /** [RecordingRecord.filePath] → FileProvider content URI. */
    fun uriFor(record: RecordingRecord): Uri = uriFor(File(record.filePath))

    /** 녹음 파일 → FileProvider content URI (Activity seam도 동일 helper). */
    fun uriFor(file: File): Uri = contentUriFor(context, file)

    companion object {
        private const val TAG = "RecordingRepository"

        fun create(context: Context): RecordingRepository {
            val appContext = context.applicationContext
            val database = AppDatabase.getInstance(appContext)
            return RecordingRepository(
                context = appContext,
                dao = database.recordingDao(),
                trashRepository = TrashRepository.create(appContext),
            )
        }

        /**
         * 녹음 파일 FileProvider URI SSOT.
         * MainActivity onRecordingSaved 등 Repository 인스턴스 없이 쓸 때.
         */
        fun contentUriFor(context: Context, file: File): Uri =
            FileProvider.getUriForFile(
                context.applicationContext,
                C2vOutputNames.FILE_PROVIDER_AUTHORITY,
                file,
            )
    }
}
