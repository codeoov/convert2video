package com.example.convert2video.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [RecordingScheduleRepeatMode] write vs read seams.
 */
class RecordingScheduleRepeatModeTest {

    @Test
    fun success_fromStorageValue_nullDefaultsToOnce() {
        // Given / When / Then — soft read/recovery
        assertEquals(RecordingScheduleRepeatMode.ONCE, RecordingScheduleRepeatMode.fromStorageValue(null))
    }

    @Test
    fun success_fromStorageValue_knownModes() {
        // Given / When / Then
        assertEquals(RecordingScheduleRepeatMode.ONCE, RecordingScheduleRepeatMode.fromStorageValue("ONCE"))
        assertEquals(RecordingScheduleRepeatMode.WEEKLY, RecordingScheduleRepeatMode.fromStorageValue("WEEKLY"))
        assertEquals(RecordingScheduleRepeatMode.DAILY, RecordingScheduleRepeatMode.fromStorageValue("DAILY"))
    }

    @Test
    fun success_fromStorageValue_garbageDefaultsToOnce() {
        // Given / When / Then — soft recovery only (write must reject)
        assertEquals(
            RecordingScheduleRepeatMode.ONCE,
            RecordingScheduleRepeatMode.fromStorageValue("garbage"),
        )
    }

    @Test
    fun success_requireFromStorageValue_knownModes() {
        // Given / When / Then
        assertEquals(
            RecordingScheduleRepeatMode.ONCE,
            RecordingScheduleRepeatMode.requireFromStorageValue("ONCE"),
        )
        assertEquals(
            RecordingScheduleRepeatMode.WEEKLY,
            RecordingScheduleRepeatMode.requireFromStorageValue("WEEKLY"),
        )
        assertEquals(
            RecordingScheduleRepeatMode.DAILY,
            RecordingScheduleRepeatMode.requireFromStorageValue("DAILY"),
        )
    }

    @Test
    fun failure_requireFromStorageValue_garbageRejected() {
        // Given / When / Then — write path must not soft-default
        val error = assertThrows(IllegalArgumentException::class.java) {
            RecordingScheduleRepeatMode.requireFromStorageValue("garbage")
        }
        assertTrue(error.message.orEmpty().contains("Unknown repeatMode"))
    }

    @Test
    fun failure_requireFromStorageValue_lowercaseRejected() {
        // Given: storage is enum.name (uppercase)
        // When / Then
        val error = assertThrows(IllegalArgumentException::class.java) {
            RecordingScheduleRepeatMode.requireFromStorageValue("once")
        }
        assertTrue(error.message.orEmpty().contains("Unknown repeatMode"))
    }

    @Test
    fun success_storageValue_roundTrip() {
        // Given / When / Then
        RecordingScheduleRepeatMode.entries.forEach { mode ->
            assertEquals(mode, RecordingScheduleRepeatMode.requireFromStorageValue(mode.storageValue))
            assertEquals(mode, RecordingScheduleRepeatMode.fromStorageValue(mode.storageValue))
        }
    }
}
