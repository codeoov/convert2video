package com.example.convert2video.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Single entry point for conversion history persisted in Room.
 * UI and Workers must not import [ConversionRecordDao] directly.
 *
 * Exposes Worker/UI-facing history APIs only. Dao findRecent/deleteById are intentionally not wrapped.
 */
class ConversionHistoryRepository(
    private val dao: ConversionRecordDao,
) {
    fun observeAll(): Flow<List<ConversionRecord>> = dao.observeAll()

    /** 변환 이력에서 오디오 URI 문자열 집합을 실시간으로 관찰한다 (이미 변환됨 배지용). */
    fun observeConvertedAudioUris(): Flow<Set<String>> =
        observeAll()
            .map { records -> records.map { it.audioUri }.toSet() }
            .distinctUntilChanged()

    suspend fun findByAudioUri(audioUri: Uri): ConversionRecord? {
        require(audioUri.toString().isNotBlank()) { "audioUri must not be blank" }
        return dao.findByAudioUri(audioUri.toString())
    }

    suspend fun recordConversion(
        audioUri: Uri,
        videoUri: Uri,
        createdAtMillis: Long = System.currentTimeMillis(),
        segmentBatchId: String? = null,
        segmentIndex: Int? = null,
        segmentTotal: Int? = null,
    ): Long {
        require(audioUri.toString().isNotBlank()) { "audioUri must not be blank" }
        require(videoUri.toString().isNotBlank()) { "videoUri must not be blank" }
        require(createdAtMillis > 0L) { "createdAtMillis must be > 0" }
        requireSegmentTrioConsistent(segmentBatchId, segmentIndex, segmentTotal)
        return dao.insert(
            ConversionRecord(
                audioUri = audioUri.toString(),
                videoUri = videoUri.toString(),
                createdAt = createdAtMillis,
                segmentBatchId = segmentBatchId,
                segmentIndex = segmentIndex,
                segmentTotal = segmentTotal,
            ),
        )
    }

    suspend fun deleteByVideoUri(videoUri: Uri) {
        require(videoUri.toString().isNotBlank()) { "videoUri must not be blank" }
        dao.deleteByVideoUri(videoUri.toString())
    }

    companion object {
        fun create(context: Context): ConversionHistoryRepository =
            ConversionHistoryRepository(
                AppDatabase.getInstance(context).conversionRecordDao(),
            )
    }
}

/**
 * Index/total pair SSOT for NofM naming (`C2vOutputNames.buildDisplayName`) and the
 * index/total half of [requireSegmentTrioConsistent].
 * Both null (non-segment) or both non-null with [segmentTotal] >= 1 and 1-based
 * [segmentIndex] in 1..total.
 */
internal fun requireSegmentIndexTotalConsistent(
    segmentIndex: Int?,
    segmentTotal: Int?,
) {
    require((segmentIndex == null) == (segmentTotal == null)) {
        "segmentIndex and segmentTotal must both be null or both non-null"
    }
    if (segmentIndex != null && segmentTotal != null) {
        require(segmentTotal >= 1) { "segmentTotal must be >= 1" }
        require(segmentIndex in 1..segmentTotal) {
            "segmentIndex must be in 1..segmentTotal (1-based)"
        }
    }
}

/**
 * Segment batch trio SSOT: all null (non-segment) or all non-null with
 * blank-free [segmentBatchId] plus [requireSegmentIndexTotalConsistent].
 * Used by Repository, Worker [resolveSegmentKeys], and (index/total half) display names.
 */
internal fun requireSegmentTrioConsistent(
    segmentBatchId: String?,
    segmentIndex: Int?,
    segmentTotal: Int?,
) {
    val hasBatchId = segmentBatchId != null
    val hasIndex = segmentIndex != null
    val hasTotal = segmentTotal != null
    require(hasBatchId == hasIndex && hasIndex == hasTotal) {
        "segmentBatchId, segmentIndex, and segmentTotal must all be null or all non-null"
    }
    if (segmentBatchId != null && segmentIndex != null && segmentTotal != null) {
        require(segmentBatchId.isNotBlank()) { "segmentBatchId must not be blank" }
        requireSegmentIndexTotalConsistent(segmentIndex, segmentTotal)
    }
}
