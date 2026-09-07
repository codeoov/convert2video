package com.example.convert2video.ui.components.pickers

import com.example.convert2video.data.RecordingSchedule
import com.example.convert2video.record.RecordingScheduleRepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * JVM unit tests for schedule card/time format helpers (D-7 locale polish).
 */
class RecordingScheduleFormatTest {

    private val dayLabels = listOf("월", "화", "수", "목", "금", "토", "일")

    @Test
    fun success_formatMinuteOfDay_padsHourAndMinute() {
        // Given / When / Then
        assertEquals("07:00", formatMinuteOfDay(7 * 60))
        assertEquals("07:30", formatMinuteOfDay(7 * 60 + 30))
        assertEquals("00:05", formatMinuteOfDay(5))
        assertEquals("23:59", formatMinuteOfDay(23 * 60 + 59))
    }

    @Test
    fun success_formatMinuteOfDay_clampsOutOfRange() {
        // Given / When / Then — coerceIn(0..1439)
        assertEquals("00:00", formatMinuteOfDay(-1))
        assertEquals("00:00", formatMinuteOfDay(-100))
        assertEquals("23:59", formatMinuteOfDay(24 * 60))
        assertEquals("23:59", formatMinuteOfDay(10_000))
    }

    @Test
    fun success_formatScheduleTimeRange_sameDayAndOvernight() {
        // Given / When / Then
        assertEquals("07:00–07:30", formatScheduleTimeRange(7 * 60, 7 * 60 + 30))
        assertEquals("22:00–06:00", formatScheduleTimeRange(22 * 60, 6 * 60))
    }

    @Test
    fun success_formatScheduleCardSummary_overnightOnce() {
        // Given — overnight end < start (display only)
        val schedule = sampleSchedule(
            startMinuteOfDay = 22 * 60,
            endMinuteOfDay = 6 * 60,
            repeatMode = RecordingScheduleRepeatMode.ONCE.name,
            daysOfWeekMask = 0,
        )

        // When
        val summary = formatScheduleCardSummary(
            schedule = schedule,
            onceLabel = "1회",
            dailyLabel = "매일",
            weeklyPrefix = "매주",
            dayLabelsMonToSun = dayLabels,
        )

        // Then
        assertEquals("1회 22:00–06:00", summary)
    }

    @Test
    fun success_formatScheduleCardSummary_weeklyMultiDays() {
        // Given — bit0=월, bit2=수, bit4=금
        val schedule = sampleSchedule(
            startMinuteOfDay = 7 * 60,
            endMinuteOfDay = 7 * 60 + 30,
            repeatMode = RecordingScheduleRepeatMode.WEEKLY.name,
            daysOfWeekMask = (1 shl 0) or (1 shl 2) or (1 shl 4),
        )

        // When
        val summary = formatScheduleCardSummary(
            schedule = schedule,
            onceLabel = "1회",
            dailyLabel = "매일",
            weeklyPrefix = "매주",
            dayLabelsMonToSun = dayLabels,
        )

        // Then
        assertEquals("매주 월,수,금 07:00–07:30", summary)
    }

    @Test
    fun success_formatScheduleCardSummary_weeklySingleDay() {
        // Given — bit0=월 only
        val schedule = sampleSchedule(
            startMinuteOfDay = 9 * 60,
            endMinuteOfDay = 9 * 60 + 45,
            repeatMode = RecordingScheduleRepeatMode.WEEKLY.name,
            daysOfWeekMask = 1 shl 0,
        )

        // When
        val summary = formatScheduleCardSummary(
            schedule = schedule,
            onceLabel = "1회",
            dailyLabel = "매일",
            weeklyPrefix = "매주",
            dayLabelsMonToSun = dayLabels,
        )

        // Then
        assertEquals("매주 월 09:00–09:45", summary)
    }

    @Test
    fun success_formatScheduleCardSummary_weeklyEmptyMaskFallsBackToPrefix() {
        // Given — empty mask (display fallback; write path forbids this)
        val schedule = sampleSchedule(
            startMinuteOfDay = 9 * 60,
            endMinuteOfDay = 10 * 60,
            repeatMode = RecordingScheduleRepeatMode.WEEKLY.name,
            daysOfWeekMask = 0,
        )

        // When
        val summary = formatScheduleCardSummary(
            schedule = schedule,
            onceLabel = "1회",
            dailyLabel = "매일",
            weeklyPrefix = "매주",
            dayLabelsMonToSun = dayLabels,
        )

        // Then
        assertEquals("매주 09:00–10:00", summary)
        assertEquals(
            "매주",
            formatScheduleRepeatSummary(
                schedule = schedule,
                onceLabel = "1회",
                dailyLabel = "매일",
                weeklyPrefix = "매주",
                dayLabelsMonToSun = dayLabels,
            ),
        )
    }

    @Test
    fun success_formatScheduleCardSummary_onceAndDaily() {
        // Given
        val once = sampleSchedule(
            startMinuteOfDay = 8 * 60 + 15,
            endMinuteOfDay = 8 * 60 + 45,
            repeatMode = RecordingScheduleRepeatMode.ONCE.name,
            daysOfWeekMask = 0,
        )
        val daily = sampleSchedule(
            startMinuteOfDay = 6 * 60,
            endMinuteOfDay = 6 * 60 + 20,
            repeatMode = RecordingScheduleRepeatMode.DAILY.name,
            daysOfWeekMask = 0,
        )

        // When / Then
        assertEquals(
            "1회 08:15–08:45",
            formatScheduleCardSummary(
                schedule = once,
                onceLabel = "1회",
                dailyLabel = "매일",
                weeklyPrefix = "매주",
                dayLabelsMonToSun = dayLabels,
            ),
        )
        assertEquals(
            "매일 06:00–06:20",
            formatScheduleCardSummary(
                schedule = daily,
                onceLabel = "1회",
                dailyLabel = "매일",
                weeklyPrefix = "매주",
                dayLabelsMonToSun = dayLabels,
            ),
        )
    }

    @Test
    fun success_formatScheduleCardSummary_unknownRepeatDefaultsToOnceSilently() {
        // Given — display silent parse (no AppLogger); unknown → ONCE label
        val schedule = sampleSchedule(
            startMinuteOfDay = 7 * 60,
            endMinuteOfDay = 7 * 60 + 30,
            repeatMode = "GARBAGE",
            daysOfWeekMask = 0,
        )

        // When
        assertEquals(RecordingScheduleRepeatMode.ONCE, parseRepeatModeForDisplay("GARBAGE"))
        assertEquals(RecordingScheduleRepeatMode.ONCE, parseRepeatModeForDisplay(""))
        val summary = formatScheduleCardSummary(
            schedule = schedule,
            onceLabel = "1회",
            dailyLabel = "매일",
            weeklyPrefix = "매주",
            dayLabelsMonToSun = dayLabels,
        )

        // Then
        assertEquals("1회 07:00–07:30", summary)
    }

    @Test
    fun failure_formatScheduleRepeatSummary_requiresSevenDayLabels() {
        // Given
        val schedule = sampleSchedule(
            startMinuteOfDay = 7 * 60,
            endMinuteOfDay = 7 * 60 + 30,
            repeatMode = RecordingScheduleRepeatMode.WEEKLY.name,
            daysOfWeekMask = 1 shl 0,
        )

        // When / Then
        assertThrows(IllegalArgumentException::class.java) {
            formatScheduleRepeatSummary(
                schedule = schedule,
                onceLabel = "1회",
                dailyLabel = "매일",
                weeklyPrefix = "매주",
                dayLabelsMonToSun = listOf("월", "화"),
            )
        }
    }

    @Test
    fun success_formatScheduleCardSummary_clampsOutOfRangeMinutes() {
        // Given — start/end out of 0..1439; card string must reflect coerceIn clamp
        val schedule = sampleSchedule(
            startMinuteOfDay = -100,
            endMinuteOfDay = 10_000,
            repeatMode = RecordingScheduleRepeatMode.ONCE.name,
            daysOfWeekMask = 0,
        )

        // When
        val summary = formatScheduleCardSummary(
            schedule = schedule,
            onceLabel = "1회",
            dailyLabel = "매일",
            weeklyPrefix = "매주",
            dayLabelsMonToSun = dayLabels,
        )

        // Then
        assertEquals("1회 00:00–23:59", summary)
    }

    @Test
    fun success_formatScheduleCardSummary_weeklyAllSevenDays() {
        // Given — all Mon…Sun bits
        val allDaysMask = (0..6).fold(0) { acc, bit -> acc or (1 shl bit) }
        val schedule = sampleSchedule(
            startMinuteOfDay = 8 * 60,
            endMinuteOfDay = 9 * 60,
            repeatMode = RecordingScheduleRepeatMode.WEEKLY.name,
            daysOfWeekMask = allDaysMask,
        )

        // When
        val summary = formatScheduleCardSummary(
            schedule = schedule,
            onceLabel = "1회",
            dailyLabel = "매일",
            weeklyPrefix = "매주",
            dayLabelsMonToSun = dayLabels,
        )

        // Then
        assertEquals("매주 월,화,수,목,금,토,일 08:00–09:00", summary)
    }

    @Test
    fun success_formatScheduleCardSummary_overnightWeekly() {
        // Given — overnight + WEEKLY multi-day
        val schedule = sampleSchedule(
            startMinuteOfDay = 22 * 60,
            endMinuteOfDay = 6 * 60,
            repeatMode = RecordingScheduleRepeatMode.WEEKLY.name,
            daysOfWeekMask = (1 shl 0) or (1 shl 6),
        )

        // When
        val summary = formatScheduleCardSummary(
            schedule = schedule,
            onceLabel = "1회",
            dailyLabel = "매일",
            weeklyPrefix = "매주",
            dayLabelsMonToSun = dayLabels,
        )

        // Then
        assertEquals("매주 월,일 22:00–06:00", summary)
    }

    @Test
    fun failure_formatScheduleCardSummary_requiresSevenDayLabels() {
        // Given — size ≠ 7 must fail via formatScheduleRepeatSummary require
        val schedule = sampleSchedule(
            startMinuteOfDay = 7 * 60,
            endMinuteOfDay = 7 * 60 + 30,
            repeatMode = RecordingScheduleRepeatMode.WEEKLY.name,
            daysOfWeekMask = 1 shl 0,
        )

        // When / Then
        assertThrows(IllegalArgumentException::class.java) {
            formatScheduleCardSummary(
                schedule = schedule,
                onceLabel = "1회",
                dailyLabel = "매일",
                weeklyPrefix = "매주",
                dayLabelsMonToSun = listOf("월", "화", "수"),
            )
        }
    }

    private fun sampleSchedule(
        startMinuteOfDay: Int,
        endMinuteOfDay: Int,
        repeatMode: String,
        daysOfWeekMask: Int,
    ): RecordingSchedule = RecordingSchedule(
        id = 1L,
        startMinuteOfDay = startMinuteOfDay,
        endMinuteOfDay = endMinuteOfDay,
        repeatMode = repeatMode,
        daysOfWeekMask = daysOfWeekMask,
        enabled = true,
        createdAt = 0L,
    )
}
