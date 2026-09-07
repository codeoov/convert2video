package com.example.convert2video.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.example.convert2video.utils.AppLogger
import com.example.convert2video.video.C2vOutputNames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Import 오디오 파일 인덱싱·삭제·URI 발급.
 * 파일은 [appStorageDir]에 복사 저장하고 Room에 기록한다.
 */
class ImportedAudioRepository(
    private val context: Context,
    private val dao: ImportedAudioDao,
    private val trashRepository: TrashRepository = TrashRepository.create(context),
    private val durationProvider: (File) -> Long = { file -> queryDurationMsStrict(file) },
) {
    val importedRecords: Flow<List<ImportedAudioRecord>> = dao.observeAll()

    /**
     * [sourceUri]의 바이트를 앱 저장소로 복사하고 Room에 인덱싱한다.
     * @throws IllegalArgumentException 파일이 없거나 크기가 0인 경우
     * @throws Exception I/O·메타데이터 조회 실패
     */
    suspend fun importFromUri(sourceUri: Uri): ImportedAudioRecord = withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(context, sourceUri)
        val destDir = appStorageDir(context)
        val destFile = claimUniqueDestFile(destDir, displayName)
        var insertedId: Long? = null
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalArgumentException("Import source stream unavailable")
            if (!destFile.isFile || destFile.length() <= 0L) {
                throw IllegalArgumentException("Import copy failed or empty: ${destFile.name}")
            }
            val durationMs = durationMsFor(destFile, strictMetadata = false)
            val createdAt = System.currentTimeMillis()
            val record = ImportedAudioRecord(
                filePath = destFile.absolutePath,
                originalDisplayName = displayName.ifBlank { destFile.name },
                durationMs = durationMs,
                sizeBytes = destFile.length(),
                createdAt = createdAt,
            )
            insertedId = dao.insert(record)
            record.copy(id = insertedId ?: 0L)
        } catch (ce: CancellationException) {
            cleanupAfterFailure(listOf(destFile), insertedId)
            throw ce
        } catch (error: Exception) {
            cleanupAfterFailure(listOf(destFile), insertedId)
            throw error
        }
    }

    /**
     * Imports an upload without exposing Ktor or any transport type to the data layer.
     * The callback writes the received bytes into the atomically claimed destination.
     */
    suspend fun importFromStream(
        originalDisplayName: String,
        copyTo: suspend (OutputStream) -> Unit,
    ): ImportedAudioRecord {
        val sanitizedDisplayName = sanitizeUploadFileName(originalDisplayName)
        require(sanitizedDisplayName.isNotBlank()) { "Uploaded originalFileName is blank" }
        val session = beginUpload()
        return try {
            session.copyTo(copyTo)
            session.complete(sanitizedDisplayName)
        } catch (ce: CancellationException) {
            session.abort()
            throw ce
        } catch (error: Exception) {
            session.abort()
            throw error
        }
    }

    /** A transport-neutral sink session used when multipart fields arrive in any order. */
    internal suspend fun beginUpload(): UploadSession = withContext(Dispatchers.IO) {
        UploadSession(claimUniqueDestFile(appStorageDir(context), ".upload_${System.nanoTime()}.tmp"))
    }

    internal inner class UploadSession internal constructor(
        private var destFile: File,
    ) {
        private var completed = false
        private val claimedFiles = mutableListOf(destFile)

        suspend fun copyTo(copy: suspend (OutputStream) -> Unit) {
            check(!completed) { "Upload session is already closed" }
            withContext(Dispatchers.IO) {
                destFile.outputStream().use { output -> copy(output) }
            }
        }

        suspend fun complete(sanitizedDisplayName: String): ImportedAudioRecord =
            withContext(Dispatchers.IO) {
                check(!completed) { "Upload session is already closed" }
                var insertedId: Long? = null
                try {
                    validateWaveFile(destFile)
                    val finalFile = claimUniqueDestFile(
                        appStorageDir(context),
                        forceWavExtension(sanitizedDisplayName),
                    )
                    try {
                        destFile.inputStream().use { input ->
                            finalFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        destFile = finalFile
                        claimedFiles += finalFile
                    } catch (error: Exception) {
                        finalFile.delete()
                        throw error
                    }
                    if (!claimedFiles.first().delete()) {
                        throw IOException("Uploaded temporary file cleanup failed")
                    }
                    val record = ImportedAudioRecord(
                        filePath = destFile.absolutePath,
                        originalDisplayName = sanitizedDisplayName,
                        durationMs = durationMsFor(destFile, strictMetadata = true),
                        sizeBytes = destFile.length(),
                        createdAt = System.currentTimeMillis(),
                    )
                    insertedId = dao.insert(record)
                    completed = true
                    record.copy(id = insertedId ?: 0L)
                } catch (ce: CancellationException) {
                    completed = true
                    cleanupAfterFailure(claimedFiles, insertedId)
                    throw ce
                } catch (error: Exception) {
                    completed = true
                    cleanupAfterFailure(claimedFiles, insertedId)
                    throw error
                }
            }

        suspend fun abort() {
            if (completed) return
            completed = true
            cleanupAfterFailure(claimedFiles, null)
        }
    }

    /**
     * 휴지통으로 이동 후 Room row 제거.
     * @return moveToTrash + dao 삭제 성공 시 true
     */
    suspend fun deleteImportedAudio(record: ImportedAudioRecord): Boolean = withContext(Dispatchers.IO) {
        val file = File(record.filePath)
        val trashed = try {
            trashRepository.moveToTrash(
                sourceFile = file,
                itemType = TrashedItem.IMPORTED_AUDIO,
                displayName = file.name,
                wasIndexed = true,
                originalFilePath = record.filePath,
                durationMs = record.durationMs,
                sourceAudioUri = uriFor(record).toString(),
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            AppLogger.e(TAG, "deleteImportedAudio moveToTrash failed: ${e.javaClass.simpleName}", e)
            null
        }
        if (trashed == null) {
            AppLogger.e(TAG, "deleteImportedAudio moveToTrash failed: ${file.name}")
            return@withContext false
        }
        try {
            dao.deleteById(record.id)
            true
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            AppLogger.e(TAG, "deleteImportedAudio dao delete failed: ${e.javaClass.simpleName}", e)
            try {
                trashRepository.permanentlyDelete(trashed)
            } catch (rollbackCe: CancellationException) {
                throw rollbackCe
            } catch (rollbackEx: Exception) {
                AppLogger.e(
                    TAG,
                    "deleteImportedAudio trash rollback failed: ${rollbackEx.javaClass.simpleName}",
                    rollbackEx,
                )
            }
            false
        }
    }

    fun uriFor(record: ImportedAudioRecord): Uri = uriFor(File(record.filePath))

    fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(
            context.applicationContext,
            C2vOutputNames.FILE_PROVIDER_AUTHORITY,
            file,
        )

    companion object {
        private const val TAG = "ImportedAudioRepository"
        private const val IMPORT_DIR_NAME = "C2VImported"
        private const val MAX_SUFFIX = 99

        private val TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.US)

        /**
         * Import 파일 저장 디렉터리.
         * Context.getExternalFilesDir(DIRECTORY_MUSIC)/C2VImported — 단일 소스.
         */
        fun appStorageDir(context: Context): File {
            val musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                ?: error("외부 저장소를 사용할 수 없습니다")
            val dir = File(musicDir, IMPORT_DIR_NAME)
            if (!dir.exists()) {
                val created = dir.mkdirs()
                if (!created && !dir.isDirectory) {
                    error("C2VImported 디렉터리를 만들 수 없습니다")
                }
            }
            return dir
        }

        private fun queryDisplayName(context: Context, sourceUri: Uri): String {
            context.contentResolver.query(
                sourceUri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        return cursor.getString(idx).orEmpty()
                    }
                }
            }
            return sourceUri.lastPathSegment.orEmpty()
        }

        private fun sanitizeFileName(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return ""
            return trimmed.replace(FORBIDDEN_DISPLAY_NAME_CHARS, "_")
        }

        internal fun resolveUniqueDestFileName(destDir: File, rawDisplayName: String): String {
            val sanitized = sanitizeFileName(rawDisplayName)
            val preferred = if (sanitized.isNotBlank()) {
                sanitized
            } else {
                val stamp = Instant.ofEpochMilli(System.currentTimeMillis())
                    .atZone(ZoneId.systemDefault())
                    .format(TIMESTAMP_FORMATTER)
                "import_$stamp.m4a"
            }
            val existing = destDir.listFiles()?.map { it.name }?.toSet().orEmpty()
            if (preferred !in existing && !File(destDir, preferred).exists()) {
                return preferred
            }
            val dot = preferred.lastIndexOf('.')
            val (stem, ext) = if (dot > 0) {
                preferred.substring(0, dot) to preferred.substring(dot)
            } else {
                preferred to ""
            }
            for (n in 2..MAX_SUFFIX) {
                val candidate = "${stem}_$n$ext"
                if (candidate !in existing && !File(destDir, candidate).exists()) {
                    return candidate
                }
            }
            throw IllegalStateException("Import 파일명 충돌 한도($MAX_SUFFIX) 초과")
        }

        internal fun sanitizeUploadFileName(rawDisplayName: String): String =
            sanitizeFileName(rawDisplayName).take(MAX_DISPLAY_NAME_STEM_LENGTH)

        private fun forceWavExtension(sanitizedDisplayName: String): String {
            val dot = sanitizedDisplayName.lastIndexOf('.')
            val stem = if (dot > 0) sanitizedDisplayName.substring(0, dot) else sanitizedDisplayName
            return "$stem.wav"
        }

        private fun claimUniqueDestFile(destDir: File, rawDisplayName: String): File {
            val sanitized = sanitizeFileName(rawDisplayName)
            val preferred = if (sanitized.isNotBlank()) sanitized else "upload.wav"
            val dot = preferred.lastIndexOf('.')
            val (stem, ext) = if (dot > 0) {
                preferred.substring(0, dot) to preferred.substring(dot)
            } else {
                preferred to ""
            }
            for (n in 1..MAX_SUFFIX) {
                val candidate = if (n == 1) preferred else "${stem}_$n$ext"
                val file = File(destDir, candidate)
                if (file.createNewFile()) return file
            }
            throw IllegalStateException("Import 파일명 충돌 한도($MAX_SUFFIX) 초과")
        }

        private fun validateWaveFile(file: File) {
            if (!file.isFile || file.length() <= 0L) {
                throw IllegalArgumentException("Uploaded WAV is empty")
            }
            val header = ByteArray(WAV_HEADER_LENGTH)
            FileInputStream(file).use { input ->
                var offset = 0
                while (offset < header.size) {
                    val read = input.read(header, offset, header.size - offset)
                    if (read < 0) throw IllegalArgumentException("Uploaded WAV header is incomplete")
                    offset += read
                }
            }
            if (!header.startsWithAscii("RIFF", 0) || !header.startsWithAscii("WAVE", 8)) {
                throw IllegalArgumentException("Uploaded file is not a RIFF/WAVE file")
            }
        }

        private fun ByteArray.startsWithAscii(value: String, offset: Int): Boolean =
            value.indices.all { index -> this[offset + index] == value[index].code.toByte() }

        private fun queryDurationMsStrict(file: File): Long {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(file.absolutePath)
                return retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: throw IllegalStateException("Uploaded audio duration metadata missing")
            }
        }

        private fun queryDurationMs(file: File): Long = try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "import duration query failed: ${file.name}", e)
            0L
        }

        private const val WAV_HEADER_LENGTH = 12
    }

    private suspend fun cleanupAfterFailure(files: List<File>, insertedId: Long?) {
        withContext(NonCancellable + Dispatchers.IO) {
            if (insertedId != null) {
                try {
                    dao.deleteById(insertedId)
                } catch (error: Exception) {
                    AppLogger.e(TAG, "Imported audio row cleanup failed", error)
                }
            }
            try {
                files.distinct().forEach { file ->
                    if (file.exists() && !file.delete()) {
                        AppLogger.w(TAG, "Imported audio file cleanup failed")
                    }
                }
            } catch (error: Exception) {
                AppLogger.e(TAG, "Imported audio file cleanup failed", error)
            }
        }
    }

    private fun durationMsFor(file: File, strictMetadata: Boolean): Long = try {
        durationProvider(file).coerceAtLeast(0L)
    } catch (ce: CancellationException) {
        throw ce
    } catch (error: Exception) {
        if (strictMetadata) throw ImportedAudioMetadataException(error)
        AppLogger.w(TAG, "import duration query failed", error)
        0L
    }
}

internal class ImportedAudioMetadataException(cause: Throwable) : IOException(cause)
