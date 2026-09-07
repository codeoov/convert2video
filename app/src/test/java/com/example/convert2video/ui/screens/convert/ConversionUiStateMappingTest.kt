package com.example.convert2video.ui.screens.convert

import androidx.work.Data
import androidx.work.WorkInfo
import com.example.convert2video.ui.shared.ConversionUiState
import com.example.convert2video.video.ConversionWorker
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class ConversionUiStateMappingTest {

    /** JVM 테스트용 — 프로덕션은 getString(R.string.conversion_failed_fallback) 주입. */
    private val failedFallback = "변환에 실패했습니다"

    private fun workInfo(
        state: WorkInfo.State,
        progress: Data = Data.EMPTY,
        outputData: Data = Data.EMPTY,
    ): WorkInfo = WorkInfo(UUID.randomUUID(), state, emptySet(), outputData, progress)

    @Test
    fun runningMapsToInProgressWithPercent() {
        val progress = Data.Builder().putInt(ConversionWorker.KEY_PROGRESS, 42).build()
        val result = workInfo(WorkInfo.State.RUNNING, progress = progress)
            .toConversionUiState(failedFallback)
        assertEquals(ConversionUiState.InProgress(42), result)
    }

    @Test
    fun enqueuedMapsToInProgressWithZeroPercentByDefault() {
        val result = workInfo(WorkInfo.State.ENQUEUED).toConversionUiState(failedFallback)
        assertEquals(ConversionUiState.InProgress(0), result)
    }

    // The SUCCEEDED + present-videoUri case calls android.net.Uri.parse, a real Android
    // framework class not implemented on the plain JVM unit-test runtime - covered instead by
    // ConversionUiStateMappingInstrumentedTest under androidTest.

    @Test
    fun succeededWithoutVideoUriMapsToIdle() {
        val result = workInfo(WorkInfo.State.SUCCEEDED, outputData = Data.EMPTY)
            .toConversionUiState(failedFallback)
        assertEquals(ConversionUiState.Idle, result)
    }

    @Test
    fun failedMapsToFailedWithMessage() {
        val output = Data.Builder().putString(ConversionWorker.KEY_MESSAGE, "boom").build()
        val result = workInfo(WorkInfo.State.FAILED, outputData = output)
            .toConversionUiState(failedFallback)
        assertEquals(ConversionUiState.Failed("boom"), result)
    }

    @Test
    fun failedWithoutMessageUsesInjectedFallback() {
        val result = workInfo(WorkInfo.State.FAILED, outputData = Data.EMPTY)
            .toConversionUiState(failedFallback)
        assertEquals(ConversionUiState.Failed(failedFallback), result)
    }

    @Test
    fun succeededWithItemFailedFlagMapsToFailedWithMessage() {
        // ConversionWorker encodes a per-item failure as Result.success + KEY_ITEM_FAILED so
        // chained batch/segment siblings still run instead of being cascade-CANCELLED.
        val output = Data.Builder()
            .putString(ConversionWorker.KEY_MESSAGE, "오디오 파일을 읽을 수 없습니다")
            .putBoolean(ConversionWorker.KEY_ITEM_FAILED, true)
            .build()
        val result = workInfo(WorkInfo.State.SUCCEEDED, outputData = output)
            .toConversionUiState(failedFallback)
        assertEquals(ConversionUiState.Failed("오디오 파일을 읽을 수 없습니다"), result)
    }

    @Test
    fun cancelledMapsToCancelled() {
        val result = workInfo(WorkInfo.State.CANCELLED).toConversionUiState(failedFallback)
        assertEquals(ConversionUiState.Cancelled, result)
    }
}
