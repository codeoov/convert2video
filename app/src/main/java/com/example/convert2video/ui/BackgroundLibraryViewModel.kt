package com.example.convert2video.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.convert2video.data.AppDatabase
import com.example.convert2video.data.BackgroundImage
import com.example.convert2video.data.BackgroundRepository
import com.example.convert2video.video.ConversionEvent
import com.example.convert2video.video.ConversionResult
import com.example.convert2video.video.MediaStoreSaver
import com.example.convert2video.video.VideoConverter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class BackgroundLibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = BackgroundRepository(
        context = application,
        dao = AppDatabase.getInstance(application).backgroundDao(),
    )
    private val videoConverter = VideoConverter(application)
    private var conversionJob: Job? = null

    val backgrounds: StateFlow<List<BackgroundImage>> = repository.backgrounds.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private val _conversionState = MutableStateFlow<ConversionUiState>(ConversionUiState.Idle)
    val conversionState: StateFlow<ConversionUiState> = _conversionState

    fun addBackground(uri: Uri) {
        viewModelScope.launch { repository.addBackground(uri) }
    }

    fun selectBackground(id: Long) {
        viewModelScope.launch { repository.selectBackground(id) }
    }

    fun deleteBackground(background: BackgroundImage) {
        viewModelScope.launch { repository.deleteBackground(background) }
    }

    fun startConversion(backgroundFilePath: String, audioUri: Uri) {
        if (_conversionState.value is ConversionUiState.InProgress) return
        val application = getApplication<Application>()
        conversionJob = viewModelScope.launch {
            _conversionState.value = ConversionUiState.InProgress(0)

            val durationUs = videoConverter.audioDurationUsOrNull(audioUri)
            if (durationUs == null) {
                _conversionState.value =
                    ConversionUiState.Failed("오디오 파일을 읽을 수 없습니다. 다른 파일을 선택해 주세요.")
                return@launch
            }

            val outputFile = File(application.cacheDir, "convert_${System.currentTimeMillis()}.mp4")
            videoConverter.convert(File(backgroundFilePath), audioUri, durationUs, outputFile)
                .collect { event -> handleConversionEvent(event) }
        }
    }

    private fun handleConversionEvent(event: ConversionEvent) {
        when (event) {
            is ConversionEvent.Progress -> {
                _conversionState.value = ConversionUiState.InProgress(event.percent)
            }
            is ConversionEvent.Done -> {
                when (val result = event.result) {
                    is ConversionResult.Success -> {
                        val application = getApplication<Application>()
                        _conversionState.value = try {
                            val savedUri = MediaStoreSaver.saveVideoToMovies(
                                context = application,
                                sourceFile = result.outputFile,
                                displayName = "convert2video_${System.currentTimeMillis()}.mp4",
                            )
                            result.outputFile.delete()
                            ConversionUiState.Success(savedUri)
                        } catch (e: Exception) {
                            ConversionUiState.Failed("변환은 완료됐지만 갤러리 저장에 실패했습니다")
                        }
                    }
                    is ConversionResult.Failure -> {
                        _conversionState.value = ConversionUiState.Failed(result.message)
                    }
                }
            }
        }
    }

    /** Called when the app leaves the foreground mid-conversion (AC-7): stops the export. */
    fun cancelConversion() {
        if (_conversionState.value is ConversionUiState.InProgress) {
            conversionJob?.cancel()
            _conversionState.value = ConversionUiState.Cancelled
        }
    }

    fun dismissConversionResult() {
        _conversionState.value = ConversionUiState.Idle
    }
}
