package com.example.convert2video.ui.screens.convert

import androidx.work.Data
import androidx.work.WorkInfo
import com.example.convert2video.ui.shared.ConversionUiState
import com.example.convert2video.video.ConversionWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class BatchConversionUiStateMappingTest {

    /** JVM 테스트용 — 프로덕션은 getString(R.string.conversion_failed_fallback) 주입. */
    private val failedFallback = "변환에 실패했습니다"

    private fun workInfo(
        id: UUID = UUID.randomUUID(),
        state: WorkInfo.State,
        progress: Data = Data.EMPTY,
        outputData: Data = Data.EMPTY,
    ): WorkInfo = WorkInfo(id, state, emptySet(), outputData, progress)

    // --- toBatchConversionUiState / resolveBatchItemMapping ---

    @Test
    fun emptyPendingMapsToEmptyBatchList() {
        // Given
        val pending = emptyList<ConvertViewModel.PendingBatchItem>()
        val infos = emptyList<WorkInfo>()
        // When
        val result = toBatchConversionUiState(pending, infos, failedFallback)
        // Then
        assertTrue(result.isEmpty())
    }

    @Test
    fun success_resolveBatchItemMapping_mapsSegmentIndexAndTotal() {
        // Given — Uri-free: segment meta + WorkInfo → batch row fields
        val progress = Data.Builder().putInt(ConversionWorker.KEY_PROGRESS, 40).build()
        val info = workInfo(state = WorkInfo.State.RUNNING, progress = progress)

        // When
        val result = resolveBatchItemMapping(
            info = info,
            segmentIndex = 2,
            segmentTotal = 5,
            failedFallbackMessage = failedFallback,
        )

        // Then
        assertEquals(2, result.segmentIndex)
        assertEquals(5, result.segmentTotal)
        assertEquals(ConversionUiState.InProgress(40), result.state)
    }

    @Test
    fun success_resolveBatchItemMapping_plainItemKeepsNullIndexTotal() {
        // Given
        val info = workInfo(state = WorkInfo.State.ENQUEUED)

        // When
        val result = resolveBatchItemMapping(
            info = info,
            segmentIndex = null,
            segmentTotal = null,
            failedFallbackMessage = failedFallback,
        )

        // Then
        assertNull(result.segmentIndex)
        assertNull(result.segmentTotal)
        assertTrue(result.state is ConversionUiState.InProgress)
    }

    @Test
    fun success_resolveBatchItemMapping_missingInfoDefaultsToIdle() {
        // Given / When
        val result = resolveBatchItemMapping(
            info = null,
            segmentIndex = 1,
            segmentTotal = 3,
            failedFallbackMessage = failedFallback,
        )

        // Then
        assertEquals(ConversionUiState.Idle, result.state)
        assertEquals(1, result.segmentIndex)
        assertEquals(3, result.segmentTotal)
    }

    // --- evaluateConversionEnqueueGuard (unfinished skip / fail-closed) ---

    @Test
    fun success_evaluateConversionEnqueueGuard_allowsWhenAllFinished() {
        // Given
        val infos = listOf(
            workInfo(state = WorkInfo.State.SUCCEEDED),
            workInfo(state = WorkInfo.State.FAILED),
        )

        // When
        val guard = evaluateConversionEnqueueGuard(Result.success(infos))

        // Then
        assertEquals(ConversionEnqueueGuard.Allow, guard)
    }

    @Test
    fun success_evaluateConversionEnqueueGuard_allowsEmptyList() {
        // Given / When / Then
        assertEquals(
            ConversionEnqueueGuard.Allow,
            evaluateConversionEnqueueGuard(Result.success(emptyList())),
        )
    }

    @Test
    fun failure_evaluateConversionEnqueueGuard_skipsWhenUnfinished() {
        // Given
        val infos = listOf(
            workInfo(state = WorkInfo.State.SUCCEEDED),
            workInfo(state = WorkInfo.State.RUNNING),
        )

        // When
        val guard = evaluateConversionEnqueueGuard(Result.success(infos))

        // Then
        assertEquals(ConversionEnqueueGuard.SkipUnfinished, guard)
    }

    @Test
    fun failure_evaluateConversionEnqueueGuard_skipsWhenEnqueuedOrBlocked() {
        // Given / When / Then
        assertEquals(
            ConversionEnqueueGuard.SkipUnfinished,
            evaluateConversionEnqueueGuard(
                Result.success(listOf(workInfo(state = WorkInfo.State.ENQUEUED))),
            ),
        )
        assertEquals(
            ConversionEnqueueGuard.SkipUnfinished,
            evaluateConversionEnqueueGuard(
                Result.success(listOf(workInfo(state = WorkInfo.State.BLOCKED))),
            ),
        )
    }

    @Test
    fun failure_evaluateConversionEnqueueGuard_rejectsQueryFailureFailClosed() {
        // Given
        val queryError = RuntimeException("wm query failed")

        // When
        val guard = evaluateConversionEnqueueGuard(Result.failure(queryError))

        // Then — fail-closed: must not Allow (would enqueue/overwrite pending)
        assertEquals(ConversionEnqueueGuard.RejectQueryFailed, guard)
    }

    // --- toAggregateConversionUiState ---

    @Test
    fun emptyInfosMapsToIdle() {
        // Given / When / Then
        assertEquals(ConversionUiState.Idle, emptyList<WorkInfo>().toAggregateConversionUiState(failedFallback))
    }

    @Test
    fun partialCompletionMapsToInProgress() {
        // Given: one succeeded, one still running
        val infos = listOf(
            workInfo(state = WorkInfo.State.SUCCEEDED),
            workInfo(state = WorkInfo.State.RUNNING),
        )
        // When
        val result = infos.toAggregateConversionUiState(failedFallback)
        // Then
        assertEquals(ConversionUiState.InProgress(0), result)
    }

    @Test
    fun allFailedMapsToFailed() {
        // Given: two FAILED workers, no CANCELLED
        val output = Data.Builder().putString(ConversionWorker.KEY_MESSAGE, "변환 오류").build()
        val infos = listOf(
            workInfo(state = WorkInfo.State.FAILED, outputData = output),
            workInfo(state = WorkInfo.State.FAILED, outputData = output),
        )
        // When
        val result = infos.toAggregateConversionUiState(failedFallback)
        // Then
        assertEquals(ConversionUiState.Failed("변환 오류"), result)
    }

    @Test
    fun failedTakesPriorityOverCancelled() {
        // Given: chain aborted — first FAILED, downstream CANCELLED
        // Failed must surface to the user even when CANCELLED siblings exist (evaluator-#3 fix)
        val output = Data.Builder().putString(ConversionWorker.KEY_MESSAGE, "에러").build()
        val infos = listOf(
            workInfo(state = WorkInfo.State.FAILED, outputData = output),
            workInfo(state = WorkInfo.State.CANCELLED),
        )
        // When
        val result = infos.toAggregateConversionUiState(failedFallback)
        // Then: Failed, not Cancelled
        assertEquals(ConversionUiState.Failed("에러"), result)
    }

    @Test
    fun purelycancelledMapsTosCancelled() {
        // Given: both workers cancelled (user cancelled entire chain)
        val infos = listOf(
            workInfo(state = WorkInfo.State.CANCELLED),
            workInfo(state = WorkInfo.State.CANCELLED),
        )
        // When
        val result = infos.toAggregateConversionUiState(failedFallback)
        // Then
        assertEquals(ConversionUiState.Cancelled, result)
    }

    @Test
    fun allSucceededWithoutVideoUriMapsToIdle() {
        // Given: in JVM unit tests Uri is stubbed → outputData has no KEY_VIDEO_URI
        // Real-runtime behaviour (with URI present) is tested in ConversionUiStateMappingInstrumentedTest
        val infos = listOf(
            workInfo(state = WorkInfo.State.SUCCEEDED),
            workInfo(state = WorkInfo.State.SUCCEEDED),
        )
        // When
        val result = infos.toAggregateConversionUiState(failedFallback)
        // Then: no URI available → Idle; at runtime the last worker's URI yields Success
        assertEquals(ConversionUiState.Idle, result)
    }

    @Test
    fun singleItemAggregateDelegatesToSingleMapping() {
        // Given: size==1 must behave identically to WorkInfo.toConversionUiState()
        val progress = Data.Builder().putInt(ConversionWorker.KEY_PROGRESS, 77).build()
        val info = workInfo(state = WorkInfo.State.RUNNING, progress = progress)
        // When
        val fromAggregate = listOf(info).toAggregateConversionUiState(failedFallback)
        val fromSingle = info.toConversionUiState(failedFallback)
        // Then
        assertEquals(fromSingle, fromAggregate)
    }

    @Test
    fun blockedWorkerCountsAsInProgress() {
        // Given
        val infos = listOf(
            workInfo(state = WorkInfo.State.SUCCEEDED),
            workInfo(state = WorkInfo.State.BLOCKED),
        )
        // When
        val result = infos.toAggregateConversionUiState(failedFallback)
        // Then
        assertEquals(ConversionUiState.InProgress(0), result)
    }

    @Test
    fun softFailedItemAmongSucceededMapsToFailed_batchContinuesPastOneBadItem() {
        // Regression test: a bad item in the middle of a batch/segment chain is encoded as
        // SUCCEEDED + KEY_ITEM_FAILED (not WorkInfo.State.FAILED), so WorkManager still runs the
        // remaining chained siblings instead of cascade-cancelling them. The aggregate must still
        // surface the failure to the user once every item is terminal.
        val failedOutput = Data.Builder()
            .putString(ConversionWorker.KEY_MESSAGE, "오디오 파일을 읽을 수 없습니다")
            .putBoolean(ConversionWorker.KEY_ITEM_FAILED, true)
            .build()
        val infos = listOf(
            workInfo(state = WorkInfo.State.SUCCEEDED, outputData = failedOutput),
            workInfo(state = WorkInfo.State.SUCCEEDED),
        )
        val result = infos.toAggregateConversionUiState(failedFallback)
        assertEquals(ConversionUiState.Failed("오디오 파일을 읽을 수 없습니다"), result)
    }

    @Test
    fun activeWorkersOverrideFailure() {
        // Given: one is RUNNING while another already FAILED (should not happen in normal chain,
        // but defensive: active state takes highest priority)
        val output = Data.Builder().putString(ConversionWorker.KEY_MESSAGE, "오류").build()
        val infos = listOf(
            workInfo(state = WorkInfo.State.RUNNING),
            workInfo(state = WorkInfo.State.FAILED, outputData = output),
        )
        // When
        val result = infos.toAggregateConversionUiState(failedFallback)
        // Then
        assertEquals(ConversionUiState.InProgress(0), result)
    }
}
