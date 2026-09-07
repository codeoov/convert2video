package com.example.convert2video.ui.screens.audio_pick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSourceFilterMappingTest {

    // Given / When / Then

    @Test
    fun success_audioSourceFilterIndex_roundTrip() {
        AudioSourceFilter.entries.forEach { filter ->
            assertEquals(filter, audioSourceFilterFromIndex(audioSourceFilterToIndex(filter)))
        }
    }

    @Test
    fun success_audioSourceFilterFromIndex_defaultsToMyRecordingsOnInvalid() {
        assertEquals(AudioSourceFilter.MyRecordings, audioSourceFilterFromIndex(99))
    }

    @Test
    fun success_emptyMessage_differsByFilter() {
        val messages = AudioSourceFilter.entries.map { emptyMessageForAudioFilter(it) }.toSet()
        assertTrue(messages.size == AudioSourceFilter.entries.size)
    }

    @Test
    fun success_isLoadingForFilter_myRecordingsOnlyRecordingsLoader() {
        assertFalse(isLoadingForAudioFilter(AudioSourceFilter.MyRecordings, true, false))
        assertTrue(isLoadingForAudioFilter(AudioSourceFilter.MyRecordings, false, true))
        assertFalse(isLoadingForAudioFilter(AudioSourceFilter.Files, false, true))
    }

    @Test
    fun success_fullScreenError_myRecordingsEmptyAndRecordingsFailed() {
        val blocked = shouldShowFullScreenAudioPickError(
            filter = AudioSourceFilter.MyRecordings,
            displayedItems = emptyList(),
            mediaItems = emptyList(),
            recordingItems = emptyList(),
            isLoadingMedia = false,
            isLoadingRecordings = false,
            mediaError = "오디오 목록을 불러오지 못했습니다",
            recordingsError = "녹음 목록을 불러오지 못했습니다",
        )
        assertTrue(blocked)
        assertEquals(
            "녹음 목록을 불러오지 못했습니다",
            fullScreenAudioPickErrorMessage(
                AudioSourceFilter.MyRecordings,
                mediaError = "오디오 목록을 불러오지 못했습니다",
                recordingsError = "녹음 목록을 불러오지 못했습니다",
            ),
        )
    }

    @Test
    fun failure_fullScreenError_myRecordingsWithData_notBlockedByMediaError() {
        // displayedItems 비어 있지 않으면 즉시 false — AudioItem 내용 무관(JVM Uri 회피)
        @Suppress("UNCHECKED_CAST") // JVM에서 AudioItem(Uri) 생성 회피 — 내용 무관 비어있지 않은 리스트만 필요
        val blocked = shouldShowFullScreenAudioPickError(
            filter = AudioSourceFilter.MyRecordings,
            displayedItems = listOf(Any()) as List<com.example.convert2video.data.AudioItem>,
            mediaItems = emptyList(),
            recordingItems = emptyList(),
            isLoadingMedia = false,
            isLoadingRecordings = false,
            mediaError = "오디오 목록을 불러오지 못했습니다",
            recordingsError = null,
        )
        assertFalse(blocked)
    }

    @Test
    fun success_filterDisplayedItems_emptyInputs() {
        assertTrue(
            filterDisplayedItems(
                media = emptyList(),
                recordings = emptyList(),
                filter = AudioSourceFilter.All,
            ).isEmpty(),
        )
        assertTrue(
            filterDisplayedItems(
                media = emptyList(),
                recordings = emptyList(),
                filter = AudioSourceFilter.MyRecordings,
            ).isEmpty(),
        )
        assertTrue(
            filterDisplayedItems(
                media = emptyList(),
                recordings = emptyList(),
                filter = AudioSourceFilter.Files,
            ).isEmpty(),
        )
    }

    @Test
    fun success_retryTargets_byFilter() {
        assertFalse(shouldRetryMediaOnAudioPickRetry(AudioSourceFilter.MyRecordings))
        assertTrue(shouldRetryMediaOnAudioPickRetry(AudioSourceFilter.Files))
        assertTrue(shouldRetryRecordingsOnAudioPickRetry(AudioSourceFilter.MyRecordings))
        assertFalse(shouldRetryRecordingsOnAudioPickRetry(AudioSourceFilter.Files))
        assertTrue(shouldRetryMediaOnAudioPickRetry(AudioSourceFilter.All))
        assertTrue(shouldRetryRecordingsOnAudioPickRetry(AudioSourceFilter.All))
    }
}
