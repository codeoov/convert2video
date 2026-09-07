package com.example.convert2video.data

import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.convert2video.utils.AppLogger
import com.example.convert2video.video.C2vOutputNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ConvertedVideo(
    val uri: Uri,
    val displayName: String,
    /** Unix epoch 초 단위. 신규(파일시스템)·레거시(MediaStore) 모두 초로 정규화한다. */
    val dateAdded: Long,
    /** 재생 길이(ms). 레거시=MediaStore DURATION, 신규=MediaMetadataRetriever. 실패 시 0L. */
    val durationMs: Long,
    /** 파일 크기(byte). 레거시=MediaStore SIZE, 신규=File.length(). 실패 시 0L. */
    val sizeBytes: Long = 0L,
    /**
     * 신규 앱 저장소 파일 — 삭제·이름변경에 사용.
     * 레거시 MediaStore 항목은 null (contentResolver 경유).
     */
    val file: File? = null,
)

/**
 * durationMs 정규화·프로세스 내 메모리 캐시·MMR 읽기 (Room 없음).
 * JVM 유닛 테스트에서 클램프/캐시 검증용으로 `internal`.
 */
internal object ConvertedVideoDuration {

    /** MMR 블로킹 읽기 상한(ms). 타임아웃 시 0L, 캐시하지 않음. */
    const val READ_TIMEOUT_MS: Long = 2_000L

    private val cache = ConcurrentHashMap<String, Long>()

    fun cacheKey(absolutePath: String, lastModified: Long): String =
        "$absolutePath@$lastModified"

    fun getCached(key: String): Long? = cache[key]

    fun putCached(key: String, durationMs: Long) {
        cache[key] = durationMs
    }

    /** 삭제·이름변경 성공 시 해당 파일의 모든 lastModified 키를 제거한다. */
    fun removeByAbsolutePath(absolutePath: String) {
        val prefix = "$absolutePath@"
        cache.keys.removeIf { it.startsWith(prefix) }
    }

    fun clearCache() {
        cache.clear()
    }

    /** null 또는 음수 → 0L. */
    fun normalizeDurationMs(raw: Long?): Long = (raw ?: 0L).coerceAtLeast(0L)

    /**
     * 실패(null)는 캐시하지 않음. 0L(진짜 0초) 포함 성공 raw만 캐시 가능.
     */
    fun shouldCacheRaw(raw: Long?): Boolean = raw != null

    /**
     * FileInputStream FD로 MediaMetadataRetriever 재생 길이(ms) 읽기.
     * @return 원시 duration(ms), 실패 시 null
     */
    fun readRawDurationMs(file: File): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            FileInputStream(file).use { stream ->
                retriever.setDataSource(stream.fd)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "영상 길이 읽기 실패: ${file.name}", e)
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                AppLogger.e(TAG, "MediaMetadataRetriever 해제 실패", e)
            }
        }
    }

    private const val TAG = "ConvertedVideoDuration"
}

class ConvertedVideoRepository(
    private val context: Context,
    private val trashRepository: TrashRepository = TrashRepository.create(context),
) {

    private val pendingStaging = ConcurrentHashMap<String, File>()

    sealed class VideoDeleteOutcome {
        data object Deleted : VideoDeleteOutcome()
        data class NeedsConfirmation(val intentSender: IntentSender) : VideoDeleteOutcome()
        data object Failed : VideoDeleteOutcome()
    }

    /**
     * 앱 저장소(신규) + MediaStore 레거시 항목을 병합하여 dateAdded DESC 순으로 반환.
     *
     * 신규: C2vOutputNames.appStorageDir 의 .mp4 파일 목록 → FileProvider URI.
     * 레거시: Movies/C2V 및 Movies/convert2video MediaStore 쿼리 (파일 존재 여부 이중 확인 없음).
     *
     * dateAdded 정규화:
     * - 신규: File.lastModified() / 1000 (ms → 초)
     * - 레거시: MediaStore DATE_ADDED (이미 초 단위)
     */
    suspend fun getConvertedVideos(): List<ConvertedVideo> = withContext(Dispatchers.IO) {
        val newVideos = loadAppStorageVideos()
        val legacyVideos = loadLegacyMediaStoreVideos()

        // 신규 파일명 집합 — 레거시 중복 제거용
        val newNames = newVideos.mapTo(mutableSetOf()) { it.displayName }

        (newVideos + legacyVideos.filter { it.displayName !in newNames })
            .sortedByDescending { it.dateAdded }
    }

    /**
     * @return true if deleted successfully.
     */
    suspend fun deleteVideo(video: ConvertedVideo): Boolean =
        deleteVideoWithOutcome(video) is VideoDeleteOutcome.Deleted

    suspend fun deleteVideoWithOutcome(video: ConvertedVideo): VideoDeleteOutcome =
        withContext(Dispatchers.IO) {
            try {
                if (video.file != null) {
                    deleteAppOwnedVideo(video)
                } else {
                    deleteLegacyVideo(video)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "영상 삭제 실패: ${video.displayName}", e)
                VideoDeleteOutcome.Failed
            }
        }

    /**
     * 레거시 MediaStore 삭제 확인 결과 처리.
     */
    suspend fun confirmVideoDelete(video: ConvertedVideo, confirmed: Boolean): VideoDeleteOutcome =
        withContext(Dispatchers.IO) {
            if (!confirmed) {
                discardStaging(video.uri)
                return@withContext VideoDeleteOutcome.Failed
            }
            val staged = pendingStaging.remove(video.uri.toString())
            if (staged == null || !staged.isFile) {
                AppLogger.e(TAG, "confirmVideoDelete missing staging")
                return@withContext VideoDeleteOutcome.Failed
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return@withContext if (finalizeTrashFromStaging(video, staged)) {
                    VideoDeleteOutcome.Deleted
                } else {
                    VideoDeleteOutcome.Failed
                }
            }
            when (performMediaStoreVideoDelete(video.uri)) {
                is VideoDeleteOutcome.Deleted ->
                    if (finalizeTrashFromStaging(video, staged)) {
                        VideoDeleteOutcome.Deleted
                    } else {
                        VideoDeleteOutcome.Failed
                    }
                else -> {
                    discardStagingFile(staged)
                    VideoDeleteOutcome.Failed
                }
            }
        }

    fun cancelPendingVideoDelete(uri: Uri) {
        discardStaging(uri)
    }

    private suspend fun deleteAppOwnedVideo(video: ConvertedVideo): VideoDeleteOutcome {
        val file = video.file ?: return VideoDeleteOutcome.Failed
        val oldPath = file.absolutePath
        val trashed = trashRepository.moveToTrash(
            sourceFile = file,
            itemType = TrashedItem.CONVERTED_VIDEO,
            displayName = video.displayName,
            wasIndexed = true,
            originalFilePath = file.absolutePath,
            durationMs = video.durationMs,
            mimeType = "video/mp4",
        )
        if (trashed == null) {
            AppLogger.e(TAG, "deleteAppOwnedVideo moveToTrash failed: ${video.displayName}")
            return VideoDeleteOutcome.Failed
        }
        ConvertedVideoDuration.removeByAbsolutePath(oldPath)
        return VideoDeleteOutcome.Deleted
    }

    private suspend fun deleteLegacyVideo(video: ConvertedVideo): VideoDeleteOutcome {
        discardStaging(video.uri)
        val staged = stageUriToTempFile(video.uri, video.displayName)
            ?: return VideoDeleteOutcome.Failed
        pendingStaging[video.uri.toString()] = staged

        return when (val outcome = performMediaStoreVideoDelete(video.uri)) {
            is VideoDeleteOutcome.Deleted -> {
                pendingStaging.remove(video.uri.toString())
                if (finalizeTrashFromStaging(video, staged)) {
                    VideoDeleteOutcome.Deleted
                } else {
                    VideoDeleteOutcome.Failed
                }
            }
            is VideoDeleteOutcome.NeedsConfirmation -> outcome
            is VideoDeleteOutcome.Failed -> {
                discardStaging(video.uri)
                VideoDeleteOutcome.Failed
            }
        }
    }

    private fun stagingDir(): File =
        File(context.cacheDir, "video_delete_staging").apply { mkdirs() }

    private fun stageUriToTempFile(uri: Uri, displayName: String): File? {
        return try {
            val ext = if (displayName.endsWith(".mp4", ignoreCase = true)) ".mp4" else ".mp4"
            val dest = File(stagingDir(), "stage_${UUID.randomUUID()}$ext")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: run {
                AppLogger.e(TAG, "stageUriToTempFile open failed")
                return null
            }
            if (!dest.isFile || dest.length() <= 0L) {
                dest.delete()
                AppLogger.e(TAG, "stageUriToTempFile empty result")
                return null
            }
            dest
        } catch (e: Exception) {
            AppLogger.e(TAG, "stageUriToTempFile failed: ${e.javaClass.simpleName}", e)
            null
        }
    }

    private suspend fun finalizeTrashFromStaging(video: ConvertedVideo, staged: File): Boolean {
        val trashed = trashRepository.moveToTrash(
            sourceFile = staged,
            itemType = TrashedItem.CONVERTED_VIDEO,
            displayName = video.displayName,
            wasIndexed = true,
            durationMs = video.durationMs,
            mimeType = "video/mp4",
            deleteSource = true,
        )
        if (trashed == null) {
            AppLogger.e(TAG, "finalizeTrashFromStaging moveToTrash failed")
            discardStagingFile(staged)
            return false
        }
        return true
    }

    private fun performMediaStoreVideoDelete(uri: Uri): VideoDeleteOutcome {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pendingIntent = MediaStore.createDeleteRequest(
                context.contentResolver,
                listOf(uri),
            )
            return VideoDeleteOutcome.NeedsConfirmation(pendingIntent.intentSender)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return deleteLegacyVideoQ(uri)
        }
        return try {
            val rows = context.contentResolver.delete(uri, null, null)
            if (rows > 0) VideoDeleteOutcome.Deleted else VideoDeleteOutcome.Failed
        } catch (e: Exception) {
            AppLogger.e(TAG, "legacy video delete failed (API<29)", e)
            VideoDeleteOutcome.Failed
        }
    }

    @Suppress("NewApi")
    private fun deleteLegacyVideoQ(uri: Uri): VideoDeleteOutcome {
        return try {
            val rows = context.contentResolver.delete(uri, null, null)
            if (rows > 0) VideoDeleteOutcome.Deleted else VideoDeleteOutcome.Failed
        } catch (e: android.app.RecoverableSecurityException) {
            VideoDeleteOutcome.NeedsConfirmation(e.userAction.actionIntent.intentSender)
        } catch (e: Exception) {
            AppLogger.e(TAG, "legacy video delete failed (API29)", e)
            VideoDeleteOutcome.Failed
        }
    }

    private fun discardStaging(uri: Uri) {
        pendingStaging.remove(uri.toString())?.let { discardStagingFile(it) }
    }

    private fun discardStagingFile(file: File) {
        try {
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            AppLogger.e(TAG, "discardStagingFile failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * 영상 파일명 변경. ".mp4" 미포함 시 자동 부여.
     * 대상 이름의 파일이 이미 존재하면 false 반환(덮어쓰기 방지).
     * @return 성공 시 true
     */
    suspend fun renameVideo(video: ConvertedVideo, newName: String): Boolean =
        withContext(Dispatchers.IO) {
            val safeName =
                if (newName.endsWith(".mp4", ignoreCase = true)) newName else "$newName.mp4"
            try {
                if (video.file != null) {
                    val oldPath = video.file.absolutePath
                    val dest = File(video.file.parentFile, safeName)
                    if (dest.exists()) return@withContext false
                    val renamed = video.file.renameTo(dest)
                    if (renamed) {
                        ConvertedVideoDuration.removeByAbsolutePath(oldPath)
                    }
                    renamed
                } else {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, safeName)
                    }
                    context.contentResolver.update(video.uri, values, null, null) > 0
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "영상 이름변경 실패: ${video.displayName}", e)
                false
            }
        }

    // ── Private helpers ────────────────────────────────────────────────────

    private suspend fun loadAppStorageVideos(): List<ConvertedVideo> {
        val dir = try {
            C2vOutputNames.appStorageDir(context)
        } catch (e: Exception) {
            AppLogger.e(TAG, "앱 저장소 접근 실패", e)
            return emptyList()
        }
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".mp4", ignoreCase = true) }
            ?: return emptyList()
        return files.mapNotNull { file ->
            try {
                ConvertedVideo(
                    uri = FileProvider.getUriForFile(
                        context,
                        C2vOutputNames.FILE_PROVIDER_AUTHORITY,
                        file,
                    ),
                    displayName = file.name,
                    dateAdded = file.lastModified() / 1000L,
                    durationMs = readDurationMs(file),
                    sizeBytes = file.length(),
                    file = file,
                )
            } catch (e: Exception) {
                // 민감 경로 전체 로깅 금지 — 파일명만 기록
                AppLogger.e(TAG, "FileProvider URI 생성 실패: ${file.name}", e)
                null
            }
        }
    }

    /**
     * 프로세스 내 캐시 → (미스 시) runInterruptible + 짧은 예산으로 MMR 읽기 → 정규화.
     * Room 미사용. raw==null(실패)·타임아웃은 비캐시. raw==0L(진짜 0초)는 캐시 가능.
     */
    private suspend fun readDurationMs(file: File): Long {
        val key = ConvertedVideoDuration.cacheKey(file.absolutePath, file.lastModified())
        ConvertedVideoDuration.getCached(key)?.let { return it }

        // 래퍼로 완료(실패 null 포함)와 타임아웃(null)을 구분한다.
        val completed = withTimeoutOrNull(ConvertedVideoDuration.READ_TIMEOUT_MS) {
            DurationReadResult(
                runInterruptible(Dispatchers.IO) {
                    ConvertedVideoDuration.readRawDurationMs(file)
                },
            )
        }
        if (completed == null) {
            AppLogger.w(TAG, "영상 길이 읽기 타임아웃: ${file.name}")
            return 0L
        }
        val raw = completed.raw
        if (!ConvertedVideoDuration.shouldCacheRaw(raw)) {
            return ConvertedVideoDuration.normalizeDurationMs(raw)
        }
        val normalized = ConvertedVideoDuration.normalizeDurationMs(raw)
        ConvertedVideoDuration.putCached(key, normalized)
        return normalized
    }

    private data class DurationReadResult(val raw: Long?)

    private fun loadLegacyMediaStoreVideos(): List<ConvertedVideo> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
        )
        val (selection, selectionArgs) = buildLegacySelectionClause()
        val videos = mutableListOf<ConvertedVideo>()
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val durationIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            while (cursor.moveToNext()) {
                val displayName = cursor.getString(nameIdx) ?: continue
                val id = cursor.getLong(idIdx)
                val rawDuration =
                    if (cursor.isNull(durationIdx)) null else cursor.getLong(durationIdx)
                val rawSize = if (cursor.isNull(sizeIdx)) 0L else cursor.getLong(sizeIdx)
                videos += ConvertedVideo(
                    uri = Uri.withAppendedPath(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        id.toString(),
                    ),
                    displayName = displayName,
                    dateAdded = cursor.getLong(dateIdx), // 이미 초 단위
                    durationMs = ConvertedVideoDuration.normalizeDurationMs(rawDuration),
                    sizeBytes = rawSize.coerceAtLeast(0L),
                    file = null,
                )
            }
        }
        return videos
    }

    private fun buildLegacySelectionClause(): Pair<String, Array<String>> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val sel = "(${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? OR " +
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?)"
            val args = arrayOf(
                "${C2vOutputNames.C2V_FOLDER}%",
                "${C2vOutputNames.LEGACY_FOLDER}%",
            )
            sel to args
        } else {
            @Suppress("DEPRECATION")
            // TODO(2026-07-24): DATA 컬럼은 API 29부터 deprecated; minSdk >= 29 시 제거.
            val sel = "(${MediaStore.Video.Media.DATA} LIKE ? OR " +
                "${MediaStore.Video.Media.DATA} LIKE ?)"
            val args = arrayOf("%/C2V/%", "%/convert2video/%")
            sel to args
        }
    }

    private companion object {
        private const val TAG = "ConvertedVideoRepository"
    }
}
