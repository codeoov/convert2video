package com.example.convert2video.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for write/read schedule normalization (Dao-free).
 */
class RecordingScheduleNormalizeTest {

    private fun base(
        start: Int = 480,
        end: Int = 540,
        mode: String = "ONCE",
        mask: Int = 0,
        id: Long = 1L,
    ) = RecordingSchedule(
        id = id,
        startMinuteOfDay = start,
        endMinuteOfDay = end,
        repeatMode = mode,
        daysOfWeekMask = mask,
        enabled = true,
        createdAt = 1_000L,
    )

    @Test
    fun failure_normalizeForWrite_garbageRepeatModeRejected() {
        // Given
        val schedule = base(mode = "garbage")
        // When / Then
        val error = assertThrows(IllegalArgumentException::class.java) {
            normalizeRecordingScheduleForWrite(schedule)
        }
        assertTrue(error.message.orEmpty().contains("Unknown repeatMode"))
    }

    @Test
    fun failure_normalizeForWrite_weeklyMaskZeroRejected() {
        // Given
        val schedule = base(mode = "WEEKLY", mask = 0)
        // When / Then
        val error = assertThrows(IllegalArgumentException::class.java) {
            normalizeRecordingScheduleForWrite(schedule)
        }
        assertTrue(error.message.orEmpty().contains("WEEKLY"))
    }

    @Test
    fun success_normalizeForWrite_onceMaskForcedToZero() {
        // Given — ONCE ignores caller mask
        val schedule = base(mode = "ONCE", mask = 0b0000011)
        // When
        val result = normalizeRecordingScheduleForWrite(schedule)
        // Then
        assertEquals("ONCE", result.repeatMode)
        assertEquals(0, result.daysOfWeekMask)
    }

    @Test
    fun success_normalizeForWrite_dailyMaskForcedToZero() {
        // Given
        val schedule = base(mode = "DAILY", mask = 0b1111111)
        // When
        val result = normalizeRecordingScheduleForWrite(schedule)
        // Then
        assertEquals("DAILY", result.repeatMode)
        assertEquals(0, result.daysOfWeekMask)
    }

    @Test
    fun success_normalizeForWrite_overnightAllowed() {
        // Given — end < start (midnight wrap)
        val schedule = base(start = 22 * 60, end = 60, mode = "DAILY", mask = 99)
        // When
        val result = normalizeRecordingScheduleForWrite(schedule)
        // Then
        assertEquals(22 * 60, result.startMinuteOfDay)
        assertEquals(60, result.endMinuteOfDay)
        assertEquals(0, result.daysOfWeekMask)
    }

    @Test
    fun failure_normalizeForWrite_sameMinuteRejected() {
        // Given
        val schedule = base(start = 480, end = 480, mode = "ONCE")
        // When / Then
        val error = assertThrows(IllegalArgumentException::class.java) {
            normalizeRecordingScheduleForWrite(schedule)
        }
        assertTrue(error.message.orEmpty().contains("same-minute"))
    }

    @Test
    fun success_normalizeForWrite_weeklyWithBitsKept() {
        // Given — Monday + Tuesday
        val schedule = base(mode = "WEEKLY", mask = 0b0000011)
        // When
        val result = normalizeRecordingScheduleForWrite(schedule)
        // Then
        assertEquals("WEEKLY", result.repeatMode)
        assertEquals(0b0000011, result.daysOfWeekMask)
    }

    @Test
    fun success_tryNormalizeForRead_garbageSoftDefaultsToOnce() {
        // Given — soft recovery on read
        val schedule = base(mode = "garbage", mask = 5)
        // When
        val result = tryNormalizeRecordingScheduleForRead(schedule)
        // Then
        assertNotNull(result)
        assertEquals("ONCE", result!!.repeatMode)
        assertEquals(0, result.daysOfWeekMask)
    }

    @Test
    fun success_tryNormalizeForRead_skipsWeeklyMaskZero() {
        // Given
        val schedule = base(mode = "WEEKLY", mask = 0)
        // When / Then
        assertNull(tryNormalizeRecordingScheduleForRead(schedule))
    }

    @Test
    fun success_tryNormalizeForRead_skipsSameMinute() {
        // Given
        val schedule = base(start = 100, end = 100, mode = "ONCE")
        // When / Then
        assertNull(tryNormalizeRecordingScheduleForRead(schedule))
    }

    @Test
    fun success_tryNormalizeForRead_overnightKept() {
        // Given
        val schedule = base(start = 1300, end = 60, mode = "DAILY", mask = 7)
        // When
        val result = tryNormalizeRecordingScheduleForRead(schedule)
        // Then
        assertNotNull(result)
        assertEquals(1300, result!!.startMinuteOfDay)
        assertEquals(60, result.endMinuteOfDay)
        assertEquals(0, result.daysOfWeekMask)
    }
}
