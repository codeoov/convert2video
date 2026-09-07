package com.example.convert2video.ui.screens.audio_pick

import androidx.annotation.StringRes
import com.example.convert2video.R
import com.example.convert2video.data.AudioItem

/** SegmentedControl 필터 → 표시 목록 (createdAt/dateAdded 내림차순). */
fun filterDisplayedItems(
    media: List<AudioItem>,
    recordings: List<AudioItem>,
    filter: AudioSourceFilter,
): List<AudioItem> = when (filter) {
    AudioSourceFilter.All ->
        (media + recordings).sortedByDescending { it.dateAdded }
    AudioSourceFilter.MyRecordings ->
        recordings.sortedByDescending { it.dateAdded }
    AudioSourceFilter.Files ->
        media.sortedByDescending { it.dateAdded }
}

/** [AudioPickViewModel] 필터 인덱스 SSOT — SegmentedControl 순서와 동기. */
fun audioSourceFilterFromIndex(index: Int): AudioSourceFilter = when (index) {
    0 -> AudioSourceFilter.All
    1 -> AudioSourceFilter.MyRecordings
    2 -> AudioSourceFilter.Files
    else -> AudioSourceFilter.MyRecordings
}

fun audioSourceFilterToIndex(filter: AudioSourceFilter): Int = when (filter) {
    AudioSourceFilter.All -> 0
    AudioSourceFilter.MyRecordings -> 1
    AudioSourceFilter.Files -> 2
}

/** 필터별 빈 목록 메시지 리소스 ID — UI에서 [stringResource]/[getString]으로 해석. */
@StringRes
fun emptyMessageForAudioFilter(filter: AudioSourceFilter): Int = when (filter) {
    AudioSourceFilter.All -> R.string.audio_pick_empty_all
    AudioSourceFilter.MyRecordings -> R.string.audio_pick_empty_my_recordings
    AudioSourceFilter.Files -> R.string.audio_pick_empty_files
}

/** 현재 필터 기준 로딩 여부. */
fun isLoadingForAudioFilter(
    filter: AudioSourceFilter,
    isLoadingMedia: Boolean,
    isLoadingRecordings: Boolean,
): Boolean = when (filter) {
    AudioSourceFilter.MyRecordings -> isLoadingRecordings
    AudioSourceFilter.Files -> isLoadingMedia
    AudioSourceFilter.All -> isLoadingMedia || isLoadingRecordings
}

/**
 * 전체 화면 오류 블록 — 목록이 비어 있고 해당 소스 로딩이 끝났을 때만.
 * MyRecordings에 녹음 데이터가 있으면 MediaStore 실패로 가리지 않음.
 */
fun shouldShowFullScreenAudioPickError(
    filter: AudioSourceFilter,
    displayedItems: List<AudioItem>,
    mediaItems: List<AudioItem>,
    recordingItems: List<AudioItem>,
    isLoadingMedia: Boolean,
    isLoadingRecordings: Boolean,
    mediaError: String?,
    recordingsError: String?,
): Boolean {
    if (displayedItems.isNotEmpty()) return false
    if (isLoadingForAudioFilter(filter, isLoadingMedia, isLoadingRecordings)) return false
    return when (filter) {
        AudioSourceFilter.MyRecordings -> recordingsError != null
        AudioSourceFilter.Files -> mediaError != null
        AudioSourceFilter.All -> when {
            mediaError != null && recordingsError != null -> true
            mediaError != null && recordingItems.isEmpty() -> true
            recordingsError != null && mediaItems.isEmpty() -> true
            else -> false
        }
    }
}

/** 전체 화면 오류 메시지 — [shouldShowFullScreenAudioPickError] true일 때만 사용. */
fun fullScreenAudioPickErrorMessage(
    filter: AudioSourceFilter,
    mediaError: String?,
    recordingsError: String?,
): String? = when (filter) {
    AudioSourceFilter.MyRecordings -> recordingsError
    AudioSourceFilter.Files -> mediaError
    AudioSourceFilter.All -> recordingsError ?: mediaError
}

/**
 * 목록은 보이지만 다른 소스가 실패한 경우 인라인 배너 (All 필터).
 */
fun inlineAudioPickError(
    filter: AudioSourceFilter,
    displayedItems: List<AudioItem>,
    mediaItems: List<AudioItem>,
    recordingItems: List<AudioItem>,
    mediaError: String?,
    recordingsError: String?,
): String? {
    if (displayedItems.isEmpty()) return null
    if (filter != AudioSourceFilter.All) return null
    return when {
        mediaError != null && recordingItems.isNotEmpty() -> mediaError
        recordingsError != null && mediaItems.isNotEmpty() -> recordingsError
        else -> null
    }
}

/** retry 시 MediaStore 재로드 대상. */
fun shouldRetryMediaOnAudioPickRetry(filter: AudioSourceFilter): Boolean =
    filter == AudioSourceFilter.Files || filter == AudioSourceFilter.All

/** retry 시 녹음 Room 재로드 대상. */
fun shouldRetryRecordingsOnAudioPickRetry(filter: AudioSourceFilter): Boolean =
    filter == AudioSourceFilter.MyRecordings || filter == AudioSourceFilter.All
