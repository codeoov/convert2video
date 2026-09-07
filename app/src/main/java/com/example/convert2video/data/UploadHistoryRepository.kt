package com.example.convert2video.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.Flow

/**
 * Single entry point for YouTube upload history persisted in Room.
 * UI and Workers must not import [UploadRecordDao] directly.
 *
 * Exposes Worker/UI-facing history APIs only. Dao findRecent/deleteById are intentionally not wrapped.
 */
class UploadHistoryRepository(
    private val dao: UploadRecordDao,
) {
    fun observeAll(): Flow<List<UploadRecord>> = dao.observeAll()

    suspend fun findLatestForVideo(videoUri: Uri): UploadRecord? {
        require(videoUri.toString().isNotBlank()) { "videoUri must not be blank" }
        return dao.findLatestForVideo(videoUri.toString())
    }

    suspend fun recordUpload(
        videoUri: Uri,
        youtubeVideoId: String,
        watchUrl: String,
        uploadedAtMillis: Long = System.currentTimeMillis(),
    ): Long {
        require(videoUri.toString().isNotBlank()) { "videoUri must not be blank" }
        val trimmedYoutubeVideoId = youtubeVideoId.trim()
        val trimmedWatchUrl = watchUrl.trim()
        require(trimmedYoutubeVideoId.isNotBlank()) { "youtubeVideoId must not be blank" }
        require(trimmedWatchUrl.isNotBlank()) { "watchUrl must not be blank" }
        require(uploadedAtMillis > 0L) { "uploadedAtMillis must be > 0" }
        return dao.insert(
            UploadRecord(
                videoUri = videoUri.toString(),
                youtubeVideoId = trimmedYoutubeVideoId,
                watchUrl = trimmedWatchUrl,
                uploadedAt = uploadedAtMillis,
            ),
        )
    }

    suspend fun deleteByVideoUri(videoUri: Uri) {
        require(videoUri.toString().isNotBlank()) { "videoUri must not be blank" }
        dao.deleteByVideoUri(videoUri.toString())
    }

    /**
     * Returns the count of uploads recorded at or after [startMillis] (epoch ms).
     * Caller supplies the time window boundary — e.g., start of today for a daily counter.
     */
    suspend fun countUploadsSince(startMillis: Long): Int = dao.countUploadsSince(startMillis)

    /**
     * Reactive variant of [countUploadsSince]: Room re-emits whenever the table changes, so the
     * UI count updates in real-time without polling. Prefer this over [observeAll] + in-memory
     * filter to avoid loading all rows into memory just for a count.
     */
    fun observeCountSince(startMillis: Long): Flow<Int> = dao.observeCountSince(startMillis)

    companion object {
        fun create(context: Context): UploadHistoryRepository =
            UploadHistoryRepository(
                AppDatabase.getInstance(context).uploadRecordDao(),
            )
    }
}
