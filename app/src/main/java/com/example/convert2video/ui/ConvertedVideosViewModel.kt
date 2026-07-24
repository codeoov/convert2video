package com.example.convert2video.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.convert2video.data.ConvertedVideo
import com.example.convert2video.data.ConvertedVideoRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ConvertedVideosViewModel(
    private val repository: ConvertedVideoRepository,
) : ViewModel() {

    private val _videos = MutableStateFlow<List<ConvertedVideo>>(emptyList())
    val videos: StateFlow<List<ConvertedVideo>> = _videos.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** 단발성 오류 이벤트: SharedFlow로 누락 없이 전달 (extraBufferCapacity=10). */
    private val _errorMessage = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val errorMessage: SharedFlow<String> = _errorMessage.asSharedFlow()

    init {
        loadVideos()
    }

    fun loadVideos() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _videos.value = repository.getConvertedVideos()
            } catch (e: Exception) {
                _videos.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deleteVideo(uri: Uri) {
        viewModelScope.launch {
            try {
                val success = repository.deleteVideo(uri)
                if (!success) _errorMessage.tryEmit("영상을 삭제할 수 없습니다")
            } catch (e: Exception) {
                _errorMessage.tryEmit("영상을 삭제할 수 없습니다")
            } finally {
                loadVideos()
            }
        }
    }

    fun renameVideo(uri: Uri, newName: String) {
        viewModelScope.launch {
            try {
                val success = repository.renameVideo(uri, newName)
                if (!success) _errorMessage.tryEmit("이름을 변경할 수 없습니다")
            } catch (e: Exception) {
                _errorMessage.tryEmit("이름을 변경할 수 없습니다")
            } finally {
                loadVideos()
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app: Application = checkNotNull(this[APPLICATION_KEY])
                ConvertedVideosViewModel(ConvertedVideoRepository(app))
            }
        }
    }
}
