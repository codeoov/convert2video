package com.example.convert2video.data

import android.content.Context
import com.example.convert2video.record.C2vRecordingNames
import com.example.convert2video.record.RecordingBackupTrigger
import com.example.convert2video.utils.AppLogger
import com.example.convert2video.video.C2vOutputNames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

private const val TAG = "TrashRepository"
private const val DEST_CLAIM_ATTEMPTS = 32

/**
 * 휴지통 파일·Room 단일 진입점.
 * UI·Worker가 [TrashedItemDao]를 직접 임포트하는 것을 금지하고 이 Repository를 통해서만 접근한다.
 */
class TrashRepository(
    context: Context,
    private val dao: TrashedItemDao,
    private val recordingDao: RecordingDao,
    private val importedAudioDao: ImportedAudioDao,
) {
    private val appContext: Context = context.applicationContext

    fun observeAll(): Flow<List<TrashedItem>> = dao.observeAll()

    /** Room snapshot used by the recording-backup reconciliation flow. */
    internal suspend fun queryAllItems(): List<TrashedItem> = observeAll().first()

    /** Inserts a rehydrated recording-trash row with its original backup identity. */
    internal suspend fun insertRehydratedRecording(item: TrashedItem): TrashedItem {
        val id = dao.insert(item)
        return item.copy(id = id)
    }

    /**
     * [sourceFile]을 [trashDir]로 옮긴 뒤 row를 insert한다.
     * 전송 실패 시 row 없음(null).
     * insert 실패/CE: copy면 dest만 삭제(source 유지). move면 dest→source 복구 후 dest 정리.
     * 복구 실패 시 dest를 유지한다(고아 dest가 파일 소실보다 나음).
     *
     * @param deleteSource true(기본)=move; false=copy만 (F3 재사용).
     */
    suspend fun moveToTrash(
        sourceFile: File,
        itemType: String,
        displayName: String,
        wasIndexed: Boolean,
        originalFilePath: String? = null,
        durationMs: Long? = null,
        recordingFormat: String? = null,
        sourceAudioUri: String? = null,
        mimeType: String? = null,
        recordingBackupId: Long? = null,
        deleteSource: Boolean = true,
        deletedAt: Long = System.currentTimeMillis(),
    ): TrashedItem? {
        if (!TrashedItem.isKnownItemType(itemType)) {
            AppLogger.e(TAG, "moveToTrash unknown itemType")
            return null
        }
        val dir = trashDir(appContext)
        val dest = uniqueDestFile(dir, sourceFile)
        if (dest == null) {
            AppLogger.e(TAG, "moveToTrash dest claim failed")
            return null
        }
        if (!isUnderDirectory(dest, dir)) {
            AppLogger.e(TAG, "moveToTrash dest escaped trash dir")
            deleteDestIfUnderTrash(dest, dir)
            return null
        }
        val transferred = try {
            transferToTrash(sourceFile, dest, deleteSource)
        } catch (ce: CancellationException) {
            recoverAfterAbort(sourceFile, dest, deleteSource, dir)
            throw ce
        }
        if (!transferred) {
            recoverAfterAbort(sourceFile, dest, deleteSource, dir)
            return null
        }
        val row = TrashedItem(
            itemType = itemType,
            displayName = displayName,
            trashFilePath = dest.absolutePath,
            deletedAt = deletedAt,
            wasIndexed = wasIndexed,
            originalFilePath = originalFilePath,
            durationMs = durationMs,
            recordingFormat = recordingFormat,
            sourceAudioUri = sourceAudioUri,
            mimeType = mimeType,
            recordingBackupId = recordingBackupId,
        )
        return try {
            val id = dao.insert(row)
            row.copy(id = id)
        } catch (ce: CancellationException) {
            recoverAfterAbort(sourceFile, dest, deleteSource, dir)
            throw ce
        } catch (e: Exception) {
            AppLogger.e(TAG, "moveToTrash insert failed: ${e.javaClass.simpleName}")
            recoverAfterAbort(sourceFile, dest, deleteSource, dir)
            null
        }
    }

    /**
     * [item]을 원래 위치로 복원한다.
     *
     * RECORDING_AUDIO/IMPORTED_AUDIO는 [TrashedItem.wasIndexed] 값과 무관하게 항상 재인덱싱한다 —
     * Review 삭제([TrashedItem.wasIndexed]=false)로 한 번도 Room에 들어간 적 없는 항목도
     * 복원 시 여기서 처음 insert돼야 목록에 나타난다(목록은 Room만 읽고 파일시스템을 스캔하지 않음).
     * CONVERTED_VIDEO는 파일 이동만 한다 — [ConvertedVideoRepository]가 appStorageDir을
     * 파일시스템 스캔으로 조회하므로 별도 insert가 없어도 자동 인식된다.
     *
     * 파일 이동 성공 후 재인덱싱(insert) 실패 시 파일을 trash로 되돌리고 false를 반환한다.
     * trash row 삭제 실패는 파일·재인덱싱이 이미 끝난 뒤이므로 로그만 남기고 true를 반환한다
     * (고아 trash row는 [permanentlyDelete]/[purgeExpired]로 자가치유 가능 — 파일 소실보다 낫다).
     */
    suspend fun restore(item: TrashedItem): Boolean {
        val dest = resolveRestoreDest(item) ?: return false
        val trashFile = File(item.trashFilePath)
        if (!transferToTrash(trashFile, dest, deleteSource = true)) return false
        var restoredRecording: RecordingRecord? = null
        when (item.itemType) {
            TrashedItem.RECORDING_AUDIO -> {
                val record = RecordingRecord(
                    filePath = dest.absolutePath,
                    format = item.recordingFormat ?: "AAC",
                    durationMs = item.durationMs ?: 0L,
                    sizeBytes = dest.length(),
                    createdAt = System.currentTimeMillis(),
                    backupId = item.recordingBackupId ?: 0L,
                )
                try {
                    restoredRecording = recordingDao.insertRestored(record)
                } catch (ce: CancellationException) {
                    restoreDestToSource(dest, trashFile)
                    throw ce
                } catch (e: Exception) {
                    AppLogger.e(TAG, "restore recordingDao.insert failed: ${e.javaClass.simpleName}")
                    restoreDestToSource(dest, trashFile)
                    return false
                }
            }
            TrashedItem.IMPORTED_AUDIO -> {
                val record = ImportedAudioRecord(
                    filePath = dest.absolutePath,
                    originalDisplayName = item.displayName,
                    durationMs = item.durationMs ?: 0L,
                    sizeBytes = dest.length(),
                    createdAt = System.currentTimeMillis(),
                )
                try {
                    importedAudioDao.insert(record)
                } catch (ce: CancellationException) {
                    restoreDestToSource(dest, trashFile)
                    throw ce
                } catch (e: Exception) {
                    AppLogger.e(TAG, "restore importedAudioDao.insert failed: ${e.javaClass.simpleName}")
                    restoreDestToSource(dest, trashFile)
                    return false
                }
            }
            TrashedItem.CONVERTED_VIDEO -> {
                // DB insert 없음 — ConvertedVideoRepository 파일시스템 스캔이 자동 인식.
            }
        }
        try {
            dao.deleteById(item.id)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            AppLogger.e(TAG, "restore deleteById failed; stale row remains: ${e.javaClass.simpleName}")
        }
        restoredRecording?.let { record ->
            RecordingBackupTrigger.active(record, sourceFilePath = dest.absolutePath)
                ?.let { event -> RecordingBackupTrigger.enqueue(appContext, event) }
        }
        return true
    }

    private fun resolveRestoreDest(item: TrashedItem): File? = when (item.itemType) {
        TrashedItem.RECORDING_AUDIO -> resolveRecordingRestoreDest(item)
        TrashedItem.IMPORTED_AUDIO -> resolveImportedAudioRestoreDest(item)
        TrashedItem.CONVERTED_VIDEO -> resolveConvertedVideoRestoreDest(item)
        else -> {
            AppLogger.e(TAG, "resolveRestoreDest unknown itemType")
            null
        }
    }

    /**
     * 녹음 복원 목적지. [TrashedItem.originalFilePath]가 [C2vRecordingNames.appStorageDir] 안의
     * 미점유 경로면 그대로 재사용하고, 아니면 [C2vRecordingNames.firstAvailableDisplayName]
     * SSOT 헬퍼로 충돌 없는 이름을 새로 받는다(자체 충돌회피 로직 재구현 금지).
     */
    private fun resolveRecordingRestoreDest(item: TrashedItem): File? {
        val destDir = C2vRecordingNames.appStorageDir(appContext)
        resolveOriginalPathDest(item, destDir)?.let { return it }
        return try {
            val name = C2vRecordingNames.firstAvailableDisplayName(
                preferredDisplayName = item.displayName,
                isTaken = { name -> File(destDir, name).exists() },
            )
            File(destDir, name)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: IllegalStateException) {
            AppLogger.e(TAG, "resolveRecordingRestoreDest exhausted: ${e.javaClass.simpleName}")
            null
        }
    }

    /** Import 오디오 복원 목적지. [resolveRecordingRestoreDest]와 동일한 원칙, [ImportedAudioRepository] SSOT 재사용. */
    private fun resolveImportedAudioRestoreDest(item: TrashedItem): File? {
        val destDir = ImportedAudioRepository.appStorageDir(appContext)
        resolveOriginalPathDest(item, destDir)?.let { return it }
        return try {
            val name = ImportedAudioRepository.resolveUniqueDestFileName(
                destDir = destDir,
                rawDisplayName = item.displayName,
            )
            File(destDir, name)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: IllegalStateException) {
            AppLogger.e(TAG, "resolveImportedAudioRestoreDest exhausted: ${e.javaClass.simpleName}")
            null
        }
    }

    /** 변환 영상 복원 목적지. [resolveRecordingRestoreDest]와 동일한 원칙, [C2vOutputNames] SSOT 재사용. */
    private fun resolveConvertedVideoRestoreDest(item: TrashedItem): File? {
        val destDir = C2vOutputNames.appStorageDir(appContext)
        resolveOriginalPathDest(item, destDir)?.let { return it }
        return try {
            val name = C2vOutputNames.firstAvailableDisplayName(
                preferredDisplayName = item.displayName,
                isTaken = { name -> File(destDir, name).exists() },
            )
            File(destDir, name)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: IllegalStateException) {
            AppLogger.e(TAG, "resolveConvertedVideoRestoreDest exhausted: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * [item.originalFilePath]가 [destDir] 안의 아직 비어있는 경로를 가리키면 그대로 반환한다
     * (SSOT 충돌회피 헬퍼 호출 불필요 — 충돌이 없음이 이미 보장됨).
     * canonicalPath 계산 중 [IOException]이면 null(호출부가 SSOT 헬퍼 경로로 fall through).
     */
    private fun resolveOriginalPathDest(item: TrashedItem, destDir: File): File? {
        val original = item.originalFilePath
        if (original.isNullOrBlank()) return null
        val originalFile = File(original)
        return try {
            if (originalFile.parentFile?.canonicalPath == destDir.canonicalPath && !originalFile.exists()) {
                originalFile
            } else {
                null
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: IOException) {
            null
        }
    }

    /**
     * row를 먼저 삭제한 뒤, [trashDir] 자식 파일만 삭제한다.
     *
     * - [deleteById] 실패/CE면 파일을 건드리지 않는다 (row+file 유지).
     * - 경로가 [trashDir] 밖이거나 trashDir 자체면 파일/디렉터리는 건드리지 않는다.
     * - 파일이 없으면 row만 삭제된 상태로 종료.
     * - row 삭제 후 파일 delete 실패면 고아 파일(row 없음). 고아 row(파일만 삭제)는 만들지 않는다.
     */
    suspend fun permanentlyDelete(
        item: TrashedItem,
        enqueueRecordingBackupRemove: Boolean = true,
    ) {
        val dir = trashDir(appContext)
        val file = File(item.trashFilePath)
        dao.deleteById(item.id)
        withContext(Dispatchers.IO) {
            try {
                if (!isUnderDirectory(file, dir)) {
                    AppLogger.w(TAG, "permanentlyDelete skip file outside trash dir")
                    return@withContext
                }
                if (!file.exists()) return@withContext
                if (!file.delete()) {
                    AppLogger.e(TAG, "permanentlyDelete file delete failed: ${file.name}")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                AppLogger.e(TAG, "permanentlyDelete file delete failed: ${e.javaClass.simpleName}")
            }
        }
        if (enqueueRecordingBackupRemove && item.itemType == TrashedItem.RECORDING_AUDIO) {
            RecordingBackupTrigger.remove(item.recordingBackupId, item.recordingFormat)
                ?.let { event -> RecordingBackupTrigger.enqueue(appContext, event) }
        }
    }

    /**
     * [retentionDays]보다 오래된 항목을 영구 삭제한다. [TrashPurgeWorker]가 일 1회 호출.
     * [retentionDays]가 1 미만이면 no-op.
     * 항목 단위 실패는 로그 후 계속. [CancellationException]은 재throw.
     */
    suspend fun purgeExpired(
        retentionDays: Int = DEFAULT_RETENTION_DAYS,
        nowEpochMs: Long = System.currentTimeMillis(),
    ) {
        if (retentionDays < 1) {
            AppLogger.w(TAG, "purgeExpired no-op: retentionDays < 1")
            return
        }
        val cutoff = nowEpochMs - retentionDays * DAY_MS
        val expired = dao.listExpired(cutoff)
        for (item in expired) {
            try {
                permanentlyDelete(item)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                AppLogger.e(TAG, "purgeExpired failed for id=${item.id}: ${e.javaClass.simpleName}")
            }
        }
    }

    companion object {
        const val DEFAULT_RETENTION_DAYS = 15
        private const val DAY_MS = 24L * 60L * 60L * 1000L

        fun trashDir(context: Context): File =
            File(context.filesDir, "trash").apply { mkdirs() }

        fun create(context: Context): TrashRepository {
            val db = AppDatabase.getInstance(context)
            return TrashRepository(
                context.applicationContext,
                db.trashedItemDao(),
                db.recordingDao(),
                db.importedAudioDao(),
            )
        }
    }
}

private fun uniqueDestFile(trashDir: File, source: File): File? {
    val ext = source.extension
    val suffix = if (ext.isEmpty()) "" else ".$ext"
    repeat(DEST_CLAIM_ATTEMPTS) {
        val dest = File(trashDir, "trash_${UUID.randomUUID()}$suffix")
        try {
            if (dest.createNewFile()) return dest
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            AppLogger.e(TAG, "uniqueDestFile claim failed: ${e.javaClass.simpleName}")
        }
    }
    return null
}

/** true only for proper children of [directory]; the directory itself is not inside. */
private fun isUnderDirectory(file: File, directory: File): Boolean {
    return try {
        val fileCanon = file.canonicalFile
        val dirCanon = directory.canonicalFile
        fileCanon.path.startsWith(dirCanon.path + File.separator)
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        AppLogger.e(TAG, "trash confinement undetermined: ${e.javaClass.simpleName}")
        false
    }
}

private fun deleteDestIfUnderTrash(dest: File, trashDir: File) {
    if (!isUnderDirectory(dest, trashDir)) return
    try {
        if (dest.exists() && !dest.delete()) {
            AppLogger.e(TAG, "moveToTrash dest cleanup failed: ${dest.name}")
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        AppLogger.e(TAG, "moveToTrash dest cleanup failed: ${e.javaClass.simpleName}")
    }
}

/**
 * insert/transfer abort 복구.
 * copy 또는 source가 남아 있으면 dest만 삭제.
 * move이고 source가 없으면 dest→source 복구 후 dest 정리. 복구 실패 시 dest 유지.
 */
private fun recoverAfterAbort(
    source: File,
    dest: File,
    deleteSource: Boolean,
    trashDir: File,
) {
    val sourceAlive = try {
        source.isFile
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        AppLogger.e(TAG, "recoverAfterAbort source check failed: ${e.javaClass.simpleName}")
        false
    }
    if (!deleteSource || sourceAlive) {
        deleteDestIfUnderTrash(dest, trashDir)
        return
    }
    if (!restoreDestToSource(dest, source)) {
        AppLogger.e(TAG, "moveToTrash abort restore failed; keeping dest")
        return
    }
    if (dest.exists()) deleteDestIfUnderTrash(dest, trashDir)
}

private fun restoreDestToSource(dest: File, source: File): Boolean {
    return try {
        if (!dest.isFile) return false
        if (dest.renameTo(source)) return true
        val destLength = dest.length()
        dest.inputStream().use { input ->
            source.outputStream().use { output -> input.copyTo(output) }
        }
        source.isFile && source.length() == destLength
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        AppLogger.e(TAG, "moveToTrash restore to source failed: ${e.javaClass.simpleName}")
        false
    }
}

/**
 * 전달된 [source] 인스턴스의 [File.renameTo]/[File.delete]를 호출한다 (테스트 File 서브클래스 identity-bound).
 */
private suspend fun transferToTrash(
    source: File,
    dest: File,
    deleteSource: Boolean,
): Boolean = withContext(Dispatchers.IO) {
    try {
        if (!source.isFile) {
            AppLogger.e(TAG, "moveToTrash source is not a file")
            return@withContext false
        }
        val sourceLength = source.length()
        val moved = if (deleteSource) source.renameTo(dest) else false
        if (!moved) {
            source.inputStream().use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            if (!dest.isFile || dest.length() != sourceLength) {
                AppLogger.e(TAG, "moveToTrash copy size mismatch")
                return@withContext false
            }
            if (deleteSource) {
                if (source.exists() && !source.delete()) {
                    AppLogger.e(TAG, "moveToTrash source delete failed after copy")
                    return@withContext false
                }
            }
        }
        dest.isFile
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        AppLogger.e(TAG, "moveToTrash transfer failed: ${e.javaClass.simpleName}")
        false
    }
}
