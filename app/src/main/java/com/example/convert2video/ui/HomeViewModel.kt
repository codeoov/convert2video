package com.example.convert2video.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.convert2video.data.AppDatabase
import com.example.convert2video.data.BackgroundImage
import com.example.convert2video.data.BackgroundRepository
import com.example.convert2video.video.ConversionWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = BackgroundRepository(
        context = application,
        dao = AppDatabase.getInstance(application).backgroundDao(),
    )
    private val workManager = WorkManager.getInstance(application)

    val selectedBackground: StateFlow<BackgroundImage?> = repository.backgrounds
        .map { list -> list.find { it.isSelected } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    private val _selectedAudio = MutableStateFlow<SelectedAudio?>(null)
    val selectedAudio: StateFlow<SelectedAudio?> = _selectedAudio.asStateFlow()

    /**
     * Single source of truth for conversion state: derived from WorkManager's own record of the
     * unique background job, so re-observing naturally shows whatever WorkManager currently knows
     * (SON-7 AC-6).
     */
    val conversionState: StateFlow<ConversionUiState> = workManager
        .getWorkInfosForUniqueWorkFlow(CONVERSION_WORK_NAME)
        .map { infos -> infos.firstOrNull()?.toConversionUiState() ?: ConversionUiState.Idle }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ConversionUiState.Idle,
        )

    fun setAudio(uri: Uri, title: String, artist: String?) {
        _selectedAudio.value = SelectedAudio(uri = uri, title = title, artist = artist)
    }

    /** Enqueues the export as background-service-backed work (SON-7); survives leaving the app. */
    fun startConversion() {
        val backgroundPath = selectedBackground.value?.filePath ?: return
        val audioUri = selectedAudio.value?.uri ?: return
        val request = OneTimeWorkRequestBuilder<ConversionWorker>()
            .setInputData(
                workDataOf(
                    ConversionWorker.KEY_BACKGROUND_PATH to backgroundPath,
                    ConversionWorker.KEY_AUDIO_URI to audioUri.toString(),
                ),
            )
            .build()
        workManager.enqueueUniqueWork(CONVERSION_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /** Clears a finished (success/failed/cancelled) conversion record so state returns to Idle. */
    fun dismissConversionResult() {
        workManager.pruneWork()
    }

    data class SelectedAudio(
        val uri: Uri,
        val title: String,
        val artist: String?,
    )

    companion object {
        const val CONVERSION_WORK_NAME = "audio_to_video_conversion"
    }
}

internal fun WorkInfo.toConversionUiState(): ConversionUiState = when (state) {
    WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED ->
        ConversionUiState.InProgress(progress.getInt(ConversionWorker.KEY_PROGRESS, 0))
    WorkInfo.State.SUCCEEDED -> {
        val videoUri = outputData.getString(ConversionWorker.KEY_VIDEO_URI)
        if (videoUri != null) ConversionUiState.Success(Uri.parse(videoUri)) else ConversionUiState.Idle
    }
    WorkInfo.State.FAILED ->
        ConversionUiState.Failed(
            outputData.getString(ConversionWorker.KEY_MESSAGE) ?: "변환에 실패했습니다",
        )
    WorkInfo.State.CANCELLED -> ConversionUiState.Cancelled
}
