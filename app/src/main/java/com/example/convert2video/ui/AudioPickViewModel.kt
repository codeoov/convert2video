package com.example.convert2video.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.convert2video.data.AudioItem
import com.example.convert2video.data.AudioRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AudioPickViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AudioRepository(application)

    private val _audioItems = MutableStateFlow<List<AudioItem>>(emptyList())
    val audioItems: StateFlow<List<AudioItem>> = _audioItems.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun loadAudio() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                _audioItems.value = repository.queryAudioFiles()
            } catch (e: Exception) {
                // TODO: AppLogger.e("AudioPickViewModel", "오디오 쿼리 실패", e) — AppLogger 도입 후 교체
                _audioItems.value = emptyList()
                _errorMessage.value = "오디오 목록을 불러오지 못했습니다"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
