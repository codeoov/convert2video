package com.example.convert2video.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * JVM unit tests for segment range / clip-window pure helpers extracted from [VideoConverter].
 */
class VideoConverterSegmentTest {

    // ── validateSegmentRange ───────────────────────────────────────────────

    @Test
    fun failure_segmentRange_startNegative() {
        // Given: start < 0
        // When
        val result = validateSegmentRange(
            segmentStartUs = -1L,
            segmentEndUs = 60_000_000L,
            audioDurationUs = 120_000_000L,
        )
        // Then
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as ConversionSegmentException
        assertEquals(ConversionSegmentError.SEGMENT_RANGE_INVALID, error.error)
        assertTrue(error.error.userMessage.contains("구간"))
    }

    @Test
    fun failure_segmentRange_endAfterDuration() {
        // Given: end > audioDurationUs
        // When
        val result = validateSegmentRange(
            segmentStartUs = 0L,
            segmentEndUs = 120_000_001L,
            audioDurationUs = 120_000_000L,
        )
        // Then
        assertTrue(result.isFailure)
        assertEquals(
            ConversionSegmentError.SEGMENT_RANGE_INVALID,
            (result.exceptionOrNull() as ConversionSegmentException).error,
        )
    }

    @Test
    fun failure_segmentRange_startNotBeforeEnd() {
        // Given: start >= end
        // When
        val result = validateSegmentRange(
            segmentStartUs = 60_000_000L,
            segmentEndUs = 60_000_000L,
            audioDurationUs = 120_000_000L,
        )
        // Then
        assertTrue(result.isFailure)
        assertEquals(
            ConversionSegmentError.SEGMENT_RANGE_INVALID,
            (result.exceptionOrNull() as ConversionSegmentException).error,
        )
    }

    @Test
    fun success_segmentRange_validBounds() {
        // Given: 0 <= start < end <= duration
        // When
        val result = validateSegmentRange(0L, 60_000_000L, 60_000_000L)
        // Then
        assertTrue(result.isSuccess)
    }

    // ── computeClipWindow ──────────────────────────────────────────────────

    @Test
    fun success_clipWindow_appliesSafetyMargin() {
        // Given: 60s planned segment
        val startUs = 0L
        val endUs = 60_000_000L
        // When
        val result = computeClipWindow(startUs, endUs)
        // Then: end trimmed by AUDIO_CLIP_SAFETY_MARGIN_US
        assertTrue(result.isSuccess)
        val window = result.getOrThrow()
        assertEquals(startUs, window.startUs)
        assertEquals(endUs - AUDIO_CLIP_SAFETY_MARGIN_US, window.endUs)
        assertEquals(endUs - AUDIO_CLIP_SAFETY_MARGIN_US, window.durationUs)
    }

    @Test
    fun failure_clipWindow_zeroAfterMargin() {
        // Given: range shorter than or equal to safety margin (direct-call defense)
        val startUs = 0L
        val endUs = AUDIO_CLIP_SAFETY_MARGIN_US
        // When
        val result = computeClipWindow(startUs, endUs)
        // Then
        assertTrue(result.isFailure)
        assertEquals(
            ConversionSegmentError.SEGMENT_TOO_SHORT_AFTER_MARGIN,
            (result.exceptionOrNull() as ConversionSegmentException).error,
        )
    }

    // ── effectiveClipDurationUs (MIN ↔ margin SSOT) ─────────────────────────

    @Test
    fun success_effectiveClip_planDurationExceedsExportByMargin() {
        // Given: planned length = Planner MIN (60s)
        val planStartUs = 0L
        val planEndUs = VideoSegmentPlanner.MIN_SEGMENT_DURATION_US
        val plannedDurationUs = planEndUs - planStartUs
        // When
        val exportDurationUs = effectiveClipDurationUs(planStartUs, planEndUs)
        // Then: plan length ≠ export length; export = plan - margin
        assertEquals(VideoSegmentPlanner.MIN_SEGMENT_DURATION_US, plannedDurationUs)
        assertEquals(plannedDurationUs - AUDIO_CLIP_SAFETY_MARGIN_US, exportDurationUs)
        assertTrue(
            "export ($exportDurationUs) must be shorter than plan ($plannedDurationUs)",
            exportDurationUs < plannedDurationUs,
        )
    }

    @Test
    fun success_segmentError_userMessagesAreKorean() {
        // Given / When / Then: no English raw UI strings
        for (error in ConversionSegmentError.entries) {
            val msg = error.userMessage
            if (msg.any { it in 'A'..'Z' || it in 'a'..'z' }) {
                fail("userMessage should be Korean fallback, was: $msg")
            }
        }
    }
}
