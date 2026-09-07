package com.example.convert2video.ui.screens.converted_videos

import com.example.convert2video.data.isRecordingSourcedAudioUri
import java.util.Locale

internal fun ConvertedVideosListRow.isRecordingSourced(): Boolean = when (this) {
    is ConvertedVideosListRow.Standalone ->
        isRecordingSourcedAudioUri(item.audioUri)
    is ConvertedVideosListRow.Group ->
        isRecordingSourcedAudioUri(children.firstOrNull()?.audioUri)
}

/**
 * [ConvertedVideoSourceFilter]에 따라 최상위 [ConvertedVideosListRow]만 필터.
 * Group 내부 세그먼트 순서는 유지한다.
 */
internal fun filterConvertedVideosListRowsBySource(
    rows: List<ConvertedVideosListRow>,
    filter: ConvertedVideoSourceFilter,
): List<ConvertedVideosListRow> = when (filter) {
    ConvertedVideoSourceFilter.All -> rows.filterNot { it.isRecordingSourced() }
    ConvertedVideoSourceFilter.MyRecordings -> rows.filter { it.isRecordingSourced() }
}

private fun rowSortTimeKey(row: ConvertedVideosListRow): Long = when (row) {
    is ConvertedVideosListRow.Group ->
        row.children.maxOfOrNull { it.video.dateAdded } ?: 0L
    is ConvertedVideosListRow.Standalone -> row.item.video.dateAdded
}

private fun rowSortNameKey(row: ConvertedVideosListRow): String = when (row) {
    is ConvertedVideosListRow.Group -> row.title.lowercase(Locale.ROOT)
    is ConvertedVideosListRow.Standalone ->
        row.item.video.displayName.lowercase(Locale.ROOT)
}

private fun rowSortDurationKey(row: ConvertedVideosListRow): Long = when (row) {
    is ConvertedVideosListRow.Group -> row.children.sumOf { it.video.durationMs }
    is ConvertedVideosListRow.Standalone -> row.item.video.durationMs
}

private fun rowStableKey(row: ConvertedVideosListRow): String = when (row) {
    is ConvertedVideosListRow.Group -> row.segmentBatchId
    is ConvertedVideosListRow.Standalone -> row.item.video.uri.toString()
}

/**
 * 최상위 [ConvertedVideosListRow] 순서만 정렬. Group children segmentIndex 순서는 유지.
 */
internal fun sortConvertedVideosListRows(
    rows: List<ConvertedVideosListRow>,
    order: ConvertedVideosSortOrder,
): List<ConvertedVideosListRow> = when (order) {
    ConvertedVideosSortOrder.Time ->
        rows.sortedWith(
            compareByDescending<ConvertedVideosListRow> { rowSortTimeKey(it) }
                .thenBy { rowStableKey(it) },
        )
    ConvertedVideosSortOrder.Name ->
        rows.sortedWith(
            compareBy<ConvertedVideosListRow> { rowSortNameKey(it) }
                .thenByDescending { rowSortTimeKey(it) }
                .thenBy { rowStableKey(it) },
        )
    ConvertedVideosSortOrder.Duration ->
        rows.sortedWith(
            compareByDescending<ConvertedVideosListRow> { rowSortDurationKey(it) }
                .thenByDescending { rowSortTimeKey(it) }
                .thenBy { rowStableKey(it) },
        )
}
