package com.example.convert2video.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Sprint A: fileName·folderLabel 필드 및 recordingIdFromAudioItemId 역함수 검증.
 * Uri 의존 없는 JVM 순수 테스트 — Uri.EMPTY/Robolectric 불필요.
 */
class RecordingAudioMappingSprintATest {

    private val sampleRecord = RecordingRecord(
        id = 5L,
        filePath = "/music/C2V/(C2V)2026-08-03_10-00-00.m4a",
        format = "AAC",
        durationMs = 12_345L,
        sizeBytes = 100L,
        createdAt = 99L,
    )

    // Given / When / Then

    @Test
    fun success_recordingIdFromAudioItemId_isInverseOfRecordingAudioItemId() {
        // Given
        val recordingId = 5L
        // When
        val audioItemId = recordingAudioItemId(recordingId)
        val restored = recordingIdFromAudioItemId(audioItemId)
        // Then
        assertEquals(recordingId, requireNotNull(restored))
    }

    @Test
    fun success_recordingIdFromAudioItemId_knownValues() {
        // Given / When / Then
        assertEquals(5L, requireNotNull(recordingIdFromAudioItemId(-6L)))
        assertEquals(5L, requireNotNull(recordingIdFromAudioItemId(recordingAudioItemId(5L))))
        assertEquals(1L, requireNotNull(recordingIdFromAudioItemId(-2L)))
        assertEquals(0L, requireNotNull(recordingIdFromAudioItemId(-1L)))
    }

    @Test
    fun success_recordingIdFromAudioItemId_positiveMediaStoreId_returnsNull() {
        // Given: positive id = MediaStore audio item (not a recording)
        // When / Then
        assertNull(recordingIdFromAudioItemId(123L))
        assertNull(recordingIdFromAudioItemId(0L))
        assertNull(recordingIdFromAudioItemId(1L))
    }

    @Test
    fun success_recordingIdFromAudioItemId_importedRange_returnsNull() {
        // Given — imported id occupies more-negative range
        val importedItemId = importedAudioItemId(1L)
        // When / Then
        assertNull(recordingIdFromAudioItemId(importedItemId))
    }

    @Test
    fun success_recordingAudioItemTitle_matchesExpectedFileName() {
        // Given / When
        val title = recordingAudioItemTitle(sampleRecord.filePath)
        // Then
        assertEquals("(C2V)2026-08-03_10-00-00.m4a", title)
    }

    @Test
    fun success_recordingFolderLabel_isMusicC2V() {
        // Given / When / Then
        assertEquals("Music/C2V", RECORDING_AUDIO_FOLDER_LABEL)
    }
}
