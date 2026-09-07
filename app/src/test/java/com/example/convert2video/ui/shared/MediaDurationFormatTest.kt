package com.example.convert2video.ui.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaDurationFormatTest {

    // Given / When / Then

    @Test
    fun success_timerFormatsMmSs() {
        // Given
        val elapsedMs = 65_000L

        // When
        val text = formatMediaDurationMs(elapsedMs, MediaDurationStyle.Timer)

        // Then
        assertEquals("01:05", text)
    }

    @Test
    fun success_timerFormatsHMmSs() {
        // Given
        val elapsedMs = 3_661_000L

        // When
        val text = formatMediaDurationMs(elapsedMs, MediaDurationStyle.Timer)

        // Then
        assertEquals("1:01:01", text)
    }

    @Test
    fun success_timerJustUnderOneHourIsMmSs() {
        // Given — 3599s → still under 1h boundary
        val elapsedMs = 3_599_000L

        // When
        val text = formatMediaDurationMs(elapsedMs, MediaDurationStyle.Timer)

        // Then
        assertEquals("59:59", text)
    }

    @Test
    fun success_timerAtOneHourIsHMmSs() {
        // Given — exactly 3600s → hour form
        val elapsedMs = 3_600_000L

        // When
        val text = formatMediaDurationMs(elapsedMs, MediaDurationStyle.Timer)

        // Then
        assertEquals("1:00:00", text)
    }

    @Test
    fun success_timerZeroIsZeroPadded() {
        // Given / When
        val text = formatMediaDurationMs(0L, MediaDurationStyle.Timer)

        // Then
        assertEquals("00:00", text)
    }

    @Test
    fun success_listRowKeepsMinutesOver59() {
        // Given — AudioPick historical: no hour split
        val durationMs = 3_661_000L

        // When
        val text = formatMediaDurationMs(durationMs, MediaDurationStyle.ListRow)

        // Then
        assertEquals("61:01", text)
    }

    @Test
    fun success_listRowVsTimerDifferAtOneHour() {
        // Given — same ms; ListRow keeps total minutes, Timer splits hours
        val durationMs = 3_600_000L

        // When
        val listRow = formatMediaDurationMs(durationMs, MediaDurationStyle.ListRow)
        val timer = formatMediaDurationMs(durationMs, MediaDurationStyle.Timer)

        // Then
        assertEquals("60:00", listRow)
        assertEquals("1:00:00", timer)
    }

    @Test
    fun success_negativeClampsToZero() {
        // Given / When / Then
        assertEquals("00:00", formatMediaDurationMs(-5L, MediaDurationStyle.Timer))
        assertEquals("0:00", formatMediaDurationMs(-5L, MediaDurationStyle.ListRow))
    }

    @Test
    fun success_approxSizePerMinuteRoundsToNearestMb() {
        // Given / When / Then
        assertEquals("1MB", formatApproxSizePerMinute(960_000L))
        assertEquals("5MB", formatApproxSizePerMinute(5_292_000L))
        assertEquals("2MB", formatApproxSizePerMinute(1_500_000L))
    }

    @Test
    fun success_approxSizePerMinuteFloorsBelowHalfMb() {
        // Given / When / Then
        assertEquals("1MB", formatApproxSizePerMinute(1_499_999L))
    }

    @Test
    fun success_approxSizePerMinuteFloorsToMinimumOneMb() {
        // Given — sub-500KB rounds down to 0 before the 1MB floor applies
        assertEquals("1MB", formatApproxSizePerMinute(1L))
        assertEquals("1MB", formatApproxSizePerMinute(0L))
    }
}
