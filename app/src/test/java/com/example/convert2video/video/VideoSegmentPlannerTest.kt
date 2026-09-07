package com.example.convert2video.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSegmentPlannerTest {

    private fun assertIllegalArgFailure(result: Result<*>, vararg messageKeywords: String) {
        assertTrue("expected failure", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(
            "expected IllegalArgumentException, was ${error?.javaClass?.name}",
            error is IllegalArgumentException,
        )
        val message = error!!.message.orEmpty()
        for (keyword in messageKeywords) {
            assertTrue("message \"$message\" should contain \"$keyword\"", message.contains(keyword))
        }
    }

    // ── VideoSegment.create / init ─────────────────────────────────────────

    @Test
    fun failure_create_startNegative() {
        // Given / When
        val result = VideoSegment.create(startUs = -1L, endUs = 60_000_000L)
        // Then
        assertIllegalArgFailure(result, "startUs")
    }

    @Test
    fun failure_create_endNotAfterStart() {
        // Given / When
        val result = VideoSegment.create(startUs = 10L, endUs = 10L)
        // Then
        assertIllegalArgFailure(result, "endUs")
    }

    @Test
    fun exception_init_startNegative() {
        // Given / When / Then
        assertThrows(IllegalArgumentException::class.java) {
            VideoSegment(startUs = -1L, endUs = 60_000_000L)
        }
    }

    @Test
    fun exception_init_endNotAfterStart() {
        // Given / When / Then
        assertThrows(IllegalArgumentException::class.java) {
            VideoSegment(startUs = 50L, endUs = 40L)
        }
    }

    @Test
    fun success_create_validRange() {
        // Given / When
        val result = VideoSegment.create(0L, 60_000_000L)
        // Then
        assertTrue(result.isSuccess)
        assertEquals(VideoSegment(0L, 60_000_000L), result.getOrThrow())
    }

    // ── computeEqualSegments ───────────────────────────────────────────────

    @Test
    fun success_equalSegments_sumEqualsTotalDuration() {
        // Given: 10 minutes, 3 equal parts
        val totalDurationUs = 600_000_000L
        val count = 3
        // When
        val result = VideoSegmentPlanner.computeEqualSegments(totalDurationUs, count)
        // Then
        assertTrue(result.isSuccess)
        val segments = result.getOrThrow()
        assertEquals(count, segments.size)
        assertEquals(totalDurationUs, segments.sumOf { it.durationUs })
        assertEquals(0L, segments.first().startUs)
        assertEquals(totalDurationUs, segments.last().endUs)
    }

    @Test
    fun success_equalSegments_lastAbsorbsRemainder() {
        // Given: real-scale duration not divisible by count (180s + 1µs) / 3
        val totalDurationUs = 180_000_001L
        val count = 3
        // When
        val result = VideoSegmentPlanner.computeEqualSegments(totalDurationUs, count)
        // Then: base=60_000_000, remainder=1 absorbed by last → 60s, 60s, 60s+1µs
        assertTrue(result.isSuccess)
        val segments = result.getOrThrow()
        assertEquals(listOf(60_000_000L, 60_000_000L, 60_000_001L), segments.map { it.durationUs })
        assertEquals(0L, segments[0].startUs)
        assertEquals(60_000_000L, segments[0].endUs)
        assertEquals(60_000_000L, segments[1].startUs)
        assertEquals(120_000_000L, segments[1].endUs)
        assertEquals(120_000_000L, segments[2].startUs)
        assertEquals(180_000_001L, segments[2].endUs)
        assertEquals(totalDurationUs, segments.sumOf { it.durationUs })
    }

    @Test
    fun success_equalSegments_exactMinDuration() {
        // Given: exactly 60s, count = 1 (boundary success)
        val totalDurationUs = VideoSegmentPlanner.MIN_SEGMENT_DURATION_US
        // When
        val result = VideoSegmentPlanner.computeEqualSegments(totalDurationUs, 1)
        // Then
        assertTrue(result.isSuccess)
        assertEquals(VideoSegment(0L, totalDurationUs), result.getOrThrow().single())
    }

    @Test
    fun success_equalSegments_singleSegmentCoversAll() {
        // Given: count = 1 above min
        val totalDurationUs = 90_000_000L
        // When
        val result = VideoSegmentPlanner.computeEqualSegments(totalDurationUs, 1)
        // Then
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().size)
        assertEquals(VideoSegment(0L, totalDurationUs), result.getOrThrow().single())
    }

    @Test
    fun failure_equalSegments_countAboveMax() {
        // Given: count > MAX_SEGMENT_COUNT
        val result = VideoSegmentPlanner.computeEqualSegments(
            600_000_000L,
            VideoSegmentPlanner.MAX_SEGMENT_COUNT + 1,
        )
        // Then
        assertIllegalArgFailure(result, "count", "1..${VideoSegmentPlanner.MAX_SEGMENT_COUNT}")
    }

    @Test
    fun failure_equalSegments_countBelowOne() {
        // Given: count < 1
        val result = VideoSegmentPlanner.computeEqualSegments(120_000_000L, 0)
        // Then
        assertIllegalArgFailure(result, "count")
    }

    @Test
    fun failure_equalSegments_nonPositiveDuration() {
        // Given: totalDurationUs = 0
        val result = VideoSegmentPlanner.computeEqualSegments(0L, 2)
        // Then
        assertIllegalArgFailure(result, "totalDurationUs")
    }

    @Test
    fun failure_equalSegments_belowMinDuration() {
        // Given: 119s / 2 → each base part < MIN_SEGMENT_DURATION_US (60s)
        val result = VideoSegmentPlanner.computeEqualSegments(119_000_000L, 2)
        // Then
        assertIllegalArgFailure(result, "MIN_SEGMENT_DURATION_US")
    }

    // ── validateCustomSegments ─────────────────────────────────────────────

    @Test
    fun success_customSegments_sortedNonOverlappingWithinBounds() {
        // Given: two abutting 60s segments inside 180s total
        val totalDurationUs = 180_000_000L
        val segments = listOf(
            VideoSegment(0L, 60_000_000L),
            VideoSegment(60_000_000L, 120_000_000L),
        )
        // When
        val result = VideoSegmentPlanner.validateCustomSegments(totalDurationUs, segments)
        // Then
        assertTrue(result.isSuccess)
        assertEquals(segments, result.getOrThrow())
    }

    @Test
    fun success_customSegments_allowsGaps() {
        // Given: gap between first end (60s) and second start (120s) — policy A
        val totalDurationUs = 180_000_000L
        val segments = listOf(
            VideoSegment(0L, 60_000_000L),
            VideoSegment(120_000_000L, 180_000_000L),
        )
        // When
        val result = VideoSegmentPlanner.validateCustomSegments(totalDurationUs, segments)
        // Then
        assertTrue(result.isSuccess)
        assertEquals(segments, result.getOrThrow())
    }

    @Test
    fun success_customSegments_allowsLeadingAndTrailingUnused() {
        // Given: leading unused (start>0) + trailing unused (end<total) — gap policy A
        val totalDurationUs = 300_000_000L
        val segments = listOf(
            VideoSegment(30_000_000L, 90_000_000L),
            VideoSegment(150_000_000L, 210_000_000L),
        )
        // When
        val result = VideoSegmentPlanner.validateCustomSegments(totalDurationUs, segments)
        // Then
        assertTrue(result.isSuccess)
        assertEquals(segments, result.getOrThrow())
        assertTrue(segments.first().startUs > 0L)
        assertTrue(segments.last().endUs < totalDurationUs)
    }

    @Test
    fun success_planMin_exportShorterBySafetyMargin() {
        // Given: planned MIN segment — SSOT: plan length ≠ export length
        val startUs = 0L
        val endUs = VideoSegmentPlanner.MIN_SEGMENT_DURATION_US
        // When
        val plannedUs = endUs - startUs
        val exportUs = effectiveClipDurationUs(startUs, endUs)
        // Then
        assertEquals(VideoSegmentPlanner.MIN_SEGMENT_DURATION_US, plannedUs)
        assertEquals(plannedUs - AUDIO_CLIP_SAFETY_MARGIN_US, exportUs)
    }

    @Test
    fun failure_customSegments_belowMinDuration() {
        // Given: segment shorter than MIN_SEGMENT_DURATION_US (60s)
        val totalDurationUs = 120_000_000L
        val segments = listOf(VideoSegment(0L, 59_999_999L))
        // When
        val result = VideoSegmentPlanner.validateCustomSegments(totalDurationUs, segments)
        // Then
        assertIllegalArgFailure(result, "MIN_SEGMENT_DURATION_US")
    }

    @Test
    fun failure_customSegments_overlapping() {
        // Given: second segment starts before first ends
        val totalDurationUs = 300_000_000L
        val segments = listOf(
            VideoSegment(0L, 120_000_000L),
            VideoSegment(100_000_000L, 220_000_000L),
        )
        // When
        val result = VideoSegmentPlanner.validateCustomSegments(totalDurationUs, segments)
        // Then
        assertIllegalArgFailure(result, "non-overlapping")
    }

    @Test
    fun failure_customSegments_unsorted() {
        // Given: later start appears before earlier start (also triggers overlap/order check)
        val totalDurationUs = 300_000_000L
        val segments = listOf(
            VideoSegment(120_000_000L, 240_000_000L),
            VideoSegment(0L, 60_000_000L),
        )
        // When
        val result = VideoSegmentPlanner.validateCustomSegments(totalDurationUs, segments)
        // Then
        assertIllegalArgFailure(result, "sorted", "non-overlapping")
    }

    @Test
    fun failure_customSegments_outOfBounds() {
        // Given: endUs past totalDurationUs
        val totalDurationUs = 120_000_000L
        val segments = listOf(VideoSegment(0L, 120_000_001L))
        // When
        val result = VideoSegmentPlanner.validateCustomSegments(totalDurationUs, segments)
        // Then
        assertIllegalArgFailure(result, "outside")
    }

    @Test
    fun failure_customSegments_exceedsMaxCount() {
        // Given: 21 segments of 60s each
        val count = VideoSegmentPlanner.MAX_SEGMENT_COUNT + 1
        val segmentDurationUs = VideoSegmentPlanner.MIN_SEGMENT_DURATION_US
        val totalDurationUs = segmentDurationUs * count
        val segments = List(count) { index ->
            val startUs = index * segmentDurationUs
            VideoSegment(startUs, startUs + segmentDurationUs)
        }
        // When
        val result = VideoSegmentPlanner.validateCustomSegments(totalDurationUs, segments)
        // Then
        assertIllegalArgFailure(result, "MAX_SEGMENT_COUNT")
    }

    @Test
    fun failure_customSegments_emptyList() {
        // Given: empty segments
        // When
        val result = VideoSegmentPlanner.validateCustomSegments(120_000_000L, emptyList())
        // Then
        assertIllegalArgFailure(result, "empty")
    }

    @Test
    fun failure_customSegments_nonPositiveTotal() {
        // Given: totalDurationUs < 0
        val result = VideoSegmentPlanner.validateCustomSegments(
            -1L,
            listOf(VideoSegment(0L, 60_000_000L)),
        )
        // Then
        assertIllegalArgFailure(result, "totalDurationUs")
    }
}
