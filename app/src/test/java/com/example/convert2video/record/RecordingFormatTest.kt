package com.example.convert2video.record

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingFormatTest {

    @Test
    fun success_fromStorageValue_nullDefaultsToAac() {
        // Given / When / Then
        assertEquals(RecordingFormat.AAC, RecordingFormat.fromStorageValue(null))
    }

    @Test
    fun success_fromStorageValue_aacMapsToAac() {
        // Given / When / Then
        assertEquals(RecordingFormat.AAC, RecordingFormat.fromStorageValue("aac"))
    }

    @Test
    fun success_fromStorageValue_wavMapsToWav() {
        // Given / When / Then
        assertEquals(RecordingFormat.WAV, RecordingFormat.fromStorageValue("wav"))
    }

    @Test
    fun success_fromStorageValue_uppercaseAacDefaultsToAac() {
        // Given: enum name 형태는 storageValue가 아님
        // When / Then
        assertEquals(RecordingFormat.AAC, RecordingFormat.fromStorageValue("AAC"))
    }

    @Test
    fun success_fromStorageValue_garbageDefaultsToAac() {
        // Given / When / Then
        assertEquals(RecordingFormat.AAC, RecordingFormat.fromStorageValue("garbage"))
    }

    @Test
    fun success_storageValue_roundTrip() {
        // Given / When / Then
        RecordingFormat.entries.forEach { format ->
            assertEquals(format, RecordingFormat.fromStorageValue(format.storageValue))
        }
    }
}
