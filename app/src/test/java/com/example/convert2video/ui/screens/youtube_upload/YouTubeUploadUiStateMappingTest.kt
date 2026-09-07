package com.example.convert2video.ui.screens.youtube_upload

import android.net.Uri
import androidx.work.Data
import androidx.work.WorkInfo
import com.example.convert2video.youtube.YouTubeUploadWorker
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class YouTubeUploadUiStateMappingTest {

    private fun workInfo(
        state: WorkInfo.State,
        progress: Data = Data.EMPTY,
        outputData: Data = Data.EMPTY,
    ): WorkInfo = WorkInfo(UUID.randomUUID(), state, emptySet(), outputData, progress)

    @Test
    fun runningMapsToInProgressWithPercent() {
        val progress = Data.Builder().putInt(YouTubeUploadWorker.KEY_PROGRESS, 42).build()
        val result = workInfo(WorkInfo.State.RUNNING, progress = progress).toYouTubeUploadUiState()
        assertEquals(YouTubeUploadUiState.InProgress(42), result)
    }

    @Test
    fun enqueuedMapsToInProgressWithZeroPercentByDefault() {
        val result = workInfo(WorkInfo.State.ENQUEUED).toYouTubeUploadUiState()
        assertEquals(YouTubeUploadUiState.InProgress(0), result)
    }

    @Test
    fun blockedMapsToInProgressWithZeroPercentByDefault() {
        val result = workInfo(WorkInfo.State.BLOCKED).toYouTubeUploadUiState()
        assertEquals(YouTubeUploadUiState.InProgress(0), result)
    }

    @Test
    fun succeededWithVideoIdAndWatchUrlMapsToSuccess() {
        val output = Data.Builder()
            .putString(YouTubeUploadWorker.KEY_VIDEO_ID, "abc123")
            .putString(YouTubeUploadWorker.KEY_WATCH_URL, "https://youtu.be/abc123")
            .build()
        val result = workInfo(WorkInfo.State.SUCCEEDED, outputData = output).toYouTubeUploadUiState()
        assertEquals(YouTubeUploadUiState.Success("abc123", "https://youtu.be/abc123"), result)
    }

    @Test
    fun succeededWithoutVideoIdMapsToIdle() {
        val result = workInfo(WorkInfo.State.SUCCEEDED, outputData = Data.EMPTY).toYouTubeUploadUiState()
        assertEquals(YouTubeUploadUiState.Idle, result)
    }

    @Test
    fun failedMapsToFailedWithMessage() {
        val output = Data.Builder().putString(YouTubeUploadWorker.KEY_MESSAGE, "boom").build()
        val result = workInfo(WorkInfo.State.FAILED, outputData = output).toYouTubeUploadUiState()
        assertEquals(YouTubeUploadUiState.Failed("boom"), result)
    }

    @Test
    fun failedWithoutMessageUsesInjectedFallback() {
        val failedFallback = "업로드에 실패했습니다"
        val result = workInfo(WorkInfo.State.FAILED, outputData = Data.EMPTY)
            .toYouTubeUploadUiState(failedFallback)
        assertEquals(YouTubeUploadUiState.Failed(failedFallback), result)
    }

    @Test
    fun cancelledMapsToCancelled() {
        val result = workInfo(WorkInfo.State.CANCELLED).toYouTubeUploadUiState()
        assertEquals(YouTubeUploadUiState.Cancelled, result)
    }

    @Test
    fun uploadWorkName_usesPrefixAndEncodedUri() {
        val uriString = "content://media/external/video/1"
        assertEquals(
            "youtube_upload_${Uri.encode(uriString)}",
            YouTubeUploadViewModel.uploadWorkNameForUriString(uriString),
        )
    }
}
