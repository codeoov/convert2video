package com.example.convert2video.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingAudioMappingTest {

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
    fun success_recordingAudioItemId_negativeOffset() {
        assertEquals(-6L, recordingAudioItemId(5L))
        assertEquals(-2L, recordingAudioItemId(1L))
    }

    @Test
    fun success_recordingAudioItemTitle_usesFileName() {
        assertEquals(
            "(C2V)2026-08-03_10-00-00.m4a",
            recordingAudioItemTitle(sampleRecord.filePath),
        )
    }

    @Test
    fun success_mapRecordingToAudioItem_mapsScalarFields() {
        // Uri 통합은 RecordingRepositoryTest·androidTest — JVM은 스칼ar 필드만
        val fields = RecordingAudioItemFields.from(sampleRecord)
        assertEquals(-6L, fields.id)
        assertEquals("(C2V)2026-08-03_10-00-00.m4a", fields.title)
        assertEquals(null, fields.artist)
        assertEquals(12_345L, fields.durationMs)
        assertEquals(99L, fields.dateAdded)
    }
}

/** [mapRecordingToAudioItem] JVM 테스트용 — Uri 없이 스칼ar 필드만 추출. */
internal data class RecordingAudioItemFields(
    val id: Long,
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val dateAdded: Long,
) {
    companion object {
        fun from(record: RecordingRecord): RecordingAudioItemFields =
            RecordingAudioItemFields(
                id = recordingAudioItemId(record.id),
                title = recordingAudioItemTitle(record.filePath),
                artist = null,
                durationMs = record.durationMs,
                dateAdded = record.createdAt,
            )
    }
}
