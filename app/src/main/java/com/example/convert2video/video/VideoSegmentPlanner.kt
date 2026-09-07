package com.example.convert2video.video

/**
 * Half-open time range on an audio timeline, in microseconds: [startUs, endUs).
 * Duration is [endUs] - [startUs].
 *
 * Prefer [create] for untrusted input (returns [Result]). The primary constructor
 * still throws [IllegalArgumentException] when invariants fail.
 *
 * @throws IllegalArgumentException if [startUs] < 0 or [endUs] <= [startUs]
 */
data class VideoSegment(
    val startUs: Long,
    val endUs: Long,
) {
    init {
        require(startUs >= 0) { "startUs must be >= 0, was $startUs" }
        require(endUs > startUs) { "endUs must be > startUs, was [$startUs, $endUs)" }
    }

    val durationUs: Long get() = endUs - startUs

    companion object {
        /** Safe factory: same invariants as the primary constructor, without throwing. */
        fun create(startUs: Long, endUs: Long): Result<VideoSegment> {
            if (startUs < 0) {
                return Result.failure(
                    IllegalArgumentException("startUs must be >= 0, was $startUs"),
                )
            }
            if (endUs <= startUs) {
                return Result.failure(
                    IllegalArgumentException("endUs must be > startUs, was [$startUs, $endUs)"),
                )
            }
            return Result.success(VideoSegment(startUs, endUs))
        }
    }
}

/**
 * Pure planner for splitting an audio timeline into conversion segments.
 * No Worker / UI / Android framework dependencies.
 *
 * ## Gap policy (A)
 * [validateCustomSegments] allows **gaps** — unused audio that is not filled automatically:
 * - **Between** consecutive segments (later start after previous end)
 * - **Leading** unused (first `startUs` > 0)
 * - **Trailing** unused (last `endUs` < `totalDurationUs`)
 *
 * Overlap or reverse order is rejected. Segments must be sorted by start
 * (`startUs >= previousEndUs`).
 *
 * ## Plan length vs export length (SSOT)
 * [MIN_SEGMENT_DURATION_US] is the **planned** half-open range length.
 * [VideoConverter] export subtracts [AUDIO_CLIP_SAFETY_MARGIN_US] via
 * [effectiveClipDurationUs] / [computeClipWindow], so export media can be shorter
 * than the planned segment by up to that margin (e.g. 60s plan → ~59.7s export).
 */
object VideoSegmentPlanner {

    /** Minimum allowed **planned** segment length: 60 seconds (export may be shorter — see SSOT). */
    const val MIN_SEGMENT_DURATION_US = 60_000_000L

    /** Hard cap on how many segments a single plan may contain. */
    const val MAX_SEGMENT_COUNT = 20

    /**
     * Splits [totalDurationUs] into [count] equal parts.
     * The last segment absorbs any integer remainder so the sum of durations
     * equals [totalDurationUs] exactly.
     *
     * Fails (same [MIN_SEGMENT_DURATION_US] invariant as custom validation) when any
     * equal part would be shorter than the minimum — i.e. when
     * `totalDurationUs / count < MIN_SEGMENT_DURATION_US`.
     *
     * @return [Result.success] with [count] segments, or [Result.failure] with
     *   [IllegalArgumentException] when inputs are invalid or below min duration.
     */
    fun computeEqualSegments(totalDurationUs: Long, count: Int): Result<List<VideoSegment>> {
        if (totalDurationUs <= 0) {
            return Result.failure(
                IllegalArgumentException("totalDurationUs must be > 0, was $totalDurationUs"),
            )
        }
        if (count !in 1..MAX_SEGMENT_COUNT) {
            return Result.failure(
                IllegalArgumentException("count must be in 1..$MAX_SEGMENT_COUNT, was $count"),
            )
        }

        val baseDurationUs = totalDurationUs / count
        if (baseDurationUs < MIN_SEGMENT_DURATION_US) {
            return Result.failure(
                IllegalArgumentException(
                    "equal segment duration $baseDurationUs us is below MIN_SEGMENT_DURATION_US ($MIN_SEGMENT_DURATION_US)",
                ),
            )
        }

        val remainderUs = totalDurationUs % count
        var cursorUs = 0L
        val segments = List(count) { index ->
            val durationUs = if (index == count - 1) {
                baseDurationUs + remainderUs
            } else {
                baseDurationUs
            }
            val endUs = cursorUs + durationUs
            VideoSegment(startUs = cursorUs, endUs = endUs).also { cursorUs = endUs }
        }
        return Result.success(segments)
    }

    /**
     * Validates custom [segments] against ordering, non-overlap, bounds, min duration,
     * and [MAX_SEGMENT_COUNT].
     *
     * **Gaps (policy A) are allowed**: between segments, leading unused (`startUs` > 0),
     * and trailing unused (`endUs` < [totalDurationUs]). Segments must still be sorted by
     * start and must not overlap (`startUs >= previousEndUs`).
     *
     * Invariants already enforced by [VideoSegment] (`startUs >= 0`, `endUs > startUs`)
     * are not re-checked here.
     *
     * On success returns the same list (unchanged order assumed sorted).
     */
    fun validateCustomSegments(
        totalDurationUs: Long,
        segments: List<VideoSegment>,
    ): Result<List<VideoSegment>> {
        if (totalDurationUs <= 0) {
            return Result.failure(
                IllegalArgumentException("totalDurationUs must be > 0, was $totalDurationUs"),
            )
        }
        if (segments.isEmpty()) {
            return Result.failure(IllegalArgumentException("segments must not be empty"))
        }
        if (segments.size > MAX_SEGMENT_COUNT) {
            return Result.failure(
                IllegalArgumentException(
                    "segment count ${segments.size} exceeds MAX_SEGMENT_COUNT ($MAX_SEGMENT_COUNT)",
                ),
            )
        }

        var previousEndUs = Long.MIN_VALUE
        for ((index, segment) in segments.withIndex()) {
            if (segment.endUs > totalDurationUs) {
                return Result.failure(
                    IllegalArgumentException(
                        "segment[$index] [${segment.startUs}, ${segment.endUs}) is outside [0, $totalDurationUs]",
                    ),
                )
            }
            if (segment.durationUs < MIN_SEGMENT_DURATION_US) {
                return Result.failure(
                    IllegalArgumentException(
                        "segment[$index] duration ${segment.durationUs} us is below MIN_SEGMENT_DURATION_US ($MIN_SEGMENT_DURATION_US)",
                    ),
                )
            }
            if (segment.startUs < previousEndUs) {
                return Result.failure(
                    IllegalArgumentException(
                        "segments must be sorted and non-overlapping; segment[$index] starts at ${segment.startUs} before previous end $previousEndUs",
                    ),
                )
            }
            previousEndUs = segment.endUs
        }

        return Result.success(segments)
    }
}
