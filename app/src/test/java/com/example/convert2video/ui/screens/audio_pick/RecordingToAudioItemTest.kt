package com.example.convert2video.ui.screens.audio_pick

import com.example.convert2video.data.RecordingRecord
import com.example.convert2video.data.bindRecordingToAudioItem
import com.example.convert2video.data.toAudioItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingToAudioItemTest {

    private val sampleRecord = RecordingRecord(
        id = 7L,
        filePath = "/music/C2V/test.m4a",
        format = "AAC",
        durationMs = 500L,
        sizeBytes = 10L,
        createdAt = 50L,
    )

    // Given / When / Then

    @Test
    fun success_bindRecordingToAudioItem_invokesUriForWithSameRecord() {
        var captured: RecordingRecord? = null
        try {
            bindRecordingToAudioItem(sampleRecord) { record ->
                captured = record
                throw UriStubStopException()
            }
        } catch (_: UriStubStopException) {
            // mapRecordingToAudioItem 전 uriFor 호출 검증용
        }
        assertEquals(sampleRecord, captured)
    }

    @Test
    fun success_toAudioItem_extension_delegatesToBind() {
        var invoked = false
        try {
            sampleRecord.toAudioItem { record ->
                invoked = true
                assertEquals(7L, record.id)
                throw UriStubStopException()
            }
        } catch (_: UriStubStopException) {
        }
        assertTrue(invoked)
    }

    private class UriStubStopException : RuntimeException("uri stub")
}
