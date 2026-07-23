package com.example.convert2video.ui

import androidx.work.Data
import androidx.work.WorkInfo
import com.example.convert2video.video.ConversionWorker
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class ConversionUiStateMappingTest {

    private fun workInfo(
        state: WorkInfo.State,
        progress: Data = Data.EMPTY,
        outputData: Data = Data.EMPTY,
    ): WorkInfo = WorkInfo(UUID.randomUUID(), state, emptySet(), outputData, progress)

    @Test
    fun runningMapsToInProgressWithPercent() {
        val progress = Data.Builder().putInt(ConversionWorker.KEY_PROGRESS, 42).build()
        val result = workInfo(WorkInfo.State.RUNNING, progress = progress).toConversionUiState()
        assertEquals(ConversionUiState.InProgress(42), result)
    }

    @Test
    fun enqueuedMapsToInProgressWithZeroPercentByDefault() {
        val result = workInfo(WorkInfo.State.ENQUEUED).toConversionUiState()
        assertEquals(ConversionUiState.InProgress(0), result)
    }

    // The SUCCEEDED + present-videoUri case calls android.net.Uri.parse, a real Android
    // framework class not implemented on the plain JVM unit-test runtime - covered instead by
    // ConversionUiStateMappingInstrumentedTest under androidTest.

    @Test
    fun succeededWithoutVideoUriMapsToIdle() {
        val result = workInfo(WorkInfo.State.SUCCEEDED, outputData = Data.EMPTY).toConversionUiState()
        assertEquals(ConversionUiState.Idle, result)
    }

    @Test
    fun failedMapsToFailedWithMessage() {
        val output = Data.Builder().putString(ConversionWorker.KEY_MESSAGE, "boom").build()
        val result = workInfo(WorkInfo.State.FAILED, outputData = output).toConversionUiState()
        assertEquals(ConversionUiState.Failed("boom"), result)
    }

    @Test
    fun cancelledMapsToCancelled() {
        val result = workInfo(WorkInfo.State.CANCELLED).toConversionUiState()
        assertEquals(ConversionUiState.Cancelled, result)
    }
}
