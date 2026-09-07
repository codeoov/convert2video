package com.example.convert2video.video

import androidx.work.ListenableWorker.Result
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Worker-side range guards (duration + margin), history soft-fail policy, and the
 * unexpected-exception soft-fail wrapper ([runCatchingUnexpectedAsSoftFail]).
 * Full Worker doWork needs instrumentation; these pure helpers are the early-fail SSOT.
 */
class ConversionWorkerRangeAndHistoryTest {

    @Test
    fun success_validateResolvedRange_nullRangePasses() {
        // Given / When / Then
        assertNull(validateResolvedRangeForConvert(range = null, audioDurationUs = 60_000_000L))
    }

    @Test
    fun success_validateResolvedRange_validWindowPasses() {
        // Given — 60s planned within 120s audio
        val range = SegmentRange(startUs = 0L, endUs = 60_000_000L)
        // When / Then
        assertNull(validateResolvedRangeForConvert(range, audioDurationUs = 120_000_000L))
    }

    @Test
    fun failure_validateResolvedRange_endAfterDuration() {
        // Given — resolve would accept start<end, but end exceeds audio duration
        val range = SegmentRange(startUs = 0L, endUs = 90_000_000L)
        // When
        val error = validateResolvedRangeForConvert(range, audioDurationUs = 60_000_000L)
        // Then — early-fail before convert
        assertEquals(ConversionSegmentError.SEGMENT_RANGE_INVALID, error)
    }

    @Test
    fun failure_validateResolvedRange_shorterThanSafetyMargin() {
        // Given — start<end passes resolve, but margin collapses export duration to 0
        val range = SegmentRange(startUs = 0L, endUs = 100_000L)
        assertTrue(range.endUs - range.startUs < AUDIO_CLIP_SAFETY_MARGIN_US)
        // When
        val error = validateResolvedRangeForConvert(range, audioDurationUs = 60_000_000L)
        // Then
        assertEquals(ConversionSegmentError.SEGMENT_TOO_SHORT_AFTER_MARGIN, error)
    }

    @Test
    fun failure_validateResolvedRange_exactMarginLengthFails() {
        // Given — planned length == margin → effective export 0
        val range = SegmentRange(startUs = 0L, endUs = AUDIO_CLIP_SAFETY_MARGIN_US)
        // When / Then
        assertEquals(
            ConversionSegmentError.SEGMENT_TOO_SHORT_AFTER_MARGIN,
            validateResolvedRangeForConvert(range, audioDurationUs = 60_000_000L),
        )
    }

    @Test
    fun exception_historySoftFail_iaeRethrows() {
        // Given / When / Then — IAE must not soft-fail
        assertThrows(IllegalArgumentException::class.java) {
            historySoftFailOrRethrow(IllegalArgumentException("bad trio"))
        }
    }

    @Test
    fun success_historySoftFail_otherReturnsFalse() {
        // Given / When
        val flag = historySoftFailOrRethrow(RuntimeException("db down"))
        // Then — soft-fail → KEY_HISTORY_RECORDED=false
        assertFalse(flag)
    }

    @Test
    fun success_successOutputData_includesHistoryRecorded() {
        // Given / When
        val data = successOutputData(videoUri = "content://video/1", historyRecorded = false)
        // Then — WorkInfo/UI can observe soft-failed history
        assertTrue(data.keyValueMap.containsKey(ConversionWorker.KEY_HISTORY_RECORDED))
        assertFalse(data.getBoolean(ConversionWorker.KEY_HISTORY_RECORDED, true))
        assertEquals("content://video/1", data.getString(ConversionWorker.KEY_VIDEO_URI))
    }

    @Test
    fun success_successOutputData_historyRecordedTrue() {
        // Given / When
        val data = successOutputData(videoUri = "content://video/2", historyRecorded = true)
        // Then
        assertTrue(data.getBoolean(ConversionWorker.KEY_HISTORY_RECORDED, false))
    }

    @Test
    fun success_workDataOf_historyKeyConstant() {
        // Given / When — constant wiring smoke
        val data = workDataOf(ConversionWorker.KEY_HISTORY_RECORDED to true)
        // Then
        assertNotNull(ConversionWorker.KEY_HISTORY_RECORDED)
        assertEquals("historyRecorded", ConversionWorker.KEY_HISTORY_RECORDED)
        assertTrue(data.getBoolean(ConversionWorker.KEY_HISTORY_RECORDED, false))
    }

    @Test
    fun success_runCatchingUnexpectedAsSoftFail_passesThroughBlockResult() = runTest {
        // Given — block completes normally
        val blockResult = Result.success(workDataOf(ConversionWorker.KEY_VIDEO_URI to "content://ok"))
        // When
        val result = runCatchingUnexpectedAsSoftFail(
            onUnexpectedFailure = { fail("must not soft-fail when block succeeds") as Nothing },
        ) { blockResult }
        // Then — untouched, same instance
        assertSame(blockResult, result)
    }

    @Test
    fun exception_runCatchingUnexpectedAsSoftFail_softFailsOnUnexpectedException() = runTest {
        // Given — a fake converter step that blows up deep inside the risky body (regression for
        // the WorkContinuation cascade-cancel bug: an escaped exception must not reach doWork()).
        val softFailResult = Result.success(
            workDataOf(ConversionWorker.KEY_MESSAGE to "oops", ConversionWorker.KEY_ITEM_FAILED to true),
        )
        // When
        val result = runCatchingUnexpectedAsSoftFail(
            onUnexpectedFailure = { softFailResult },
        ) {
            throw IllegalStateException("fake converter blew up")
        }
        // Then — softened to the same shape as known failures, not propagated
        assertSame(softFailResult, result)
    }

    @Test
    fun exception_runCatchingUnexpectedAsSoftFail_rethrowsCancellationWithoutSoftFail() = runTest {
        // Given
        var onUnexpectedFailureCalled = false
        // When — real worker cancellation must stay a real cancellation, not a soft-fail
        val thrown = try {
            runCatchingUnexpectedAsSoftFail(
                onUnexpectedFailure = { onUnexpectedFailureCalled = true; Result.failure() },
            ) {
                throw CancellationException("worker cancelled")
            }
            null
        } catch (e: CancellationException) {
            e
        }
        // Then
        assertNotNull(thrown)
        assertFalse(onUnexpectedFailureCalled)
    }
}
