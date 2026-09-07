package com.example.convert2video.ui.screens.microphone_source

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.convert2video.data.SettingsRepository
import com.example.convert2video.record.MicrophoneSource
import com.example.convert2video.record.MicrophoneSourceRouting
import com.example.convert2video.utils.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MicrophoneSourceViewModel(
    private val application: Application,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val microphoneSource: StateFlow<MicrophoneSource?> = SettingsRepository.microphoneSourceHot

    private val _isBluetoothConnected = MutableStateFlow(false)
    val isBluetoothConnected: StateFlow<Boolean> = _isBluetoothConnected.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.microphoneSource.collect { }
        }
        refreshBluetoothConnected()
    }

    fun refreshBluetoothConnected() {
        _isBluetoothConnected.value = MicrophoneSourceRouting.isBluetoothMicConnected(application)
    }

    fun setMicrophoneSource(source: MicrophoneSource) {
        viewModelScope.launch {
            try {
                settingsRepository.setMicrophoneSource(source)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to set microphoneSource", e)
            }
        }
    }

    companion object {
        private const val TAG = "MicrophoneSourceViewModel"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app: Application = checkNotNull(this[APPLICATION_KEY])
                MicrophoneSourceViewModel(
                    application = app,
                    settingsRepository = SettingsRepository(app),
                )
            }
        }
    }
}
