package com.example.convert2video.ui.screens.error_log

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.convert2video.data.ErrorLogEntry
import com.example.convert2video.data.ErrorLogRepository
import com.example.convert2video.utils.AppLogger
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ErrorLogViewModel(
    private val repository: ErrorLogRepository,
) : ViewModel() {

    val entries: StateFlow<List<ErrorLogEntry>> = repository.observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun deleteAll() {
        viewModelScope.launch {
            try {
                repository.deleteAll()
            } catch (e: Exception) {
                // AppLogger.e 대신 eLocal: e()는 persistSink를 호출하므로 sink→insert 자기 참조 체인 발생.
                // eLocal은 raw Log만 출력하고 sink를 타지 않는다(AppLogger.kt 내부 전용).
                AppLogger.eLocal(TAG, "에러 로그 전체 삭제 실패", e)
            }
        }
    }

    fun deleteById(id: Long) {
        viewModelScope.launch {
            try {
                repository.deleteById(id)
            } catch (e: Exception) {
                // AppLogger.e 대신 eLocal: e()는 persistSink를 호출하므로 sink→insert 자기 참조 체인 발생.
                // eLocal은 raw Log만 출력하고 sink를 타지 않는다(AppLogger.kt 내부 전용).
                AppLogger.eLocal(TAG, "에러 로그 항목 삭제 실패: id=$id", e)
            }
        }
    }

    companion object {
        private const val TAG = "ErrorLogViewModel"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app: Application = checkNotNull(this[APPLICATION_KEY])
                ErrorLogViewModel(
                    repository = ErrorLogRepository.create(app),
                )
            }
        }
    }
}
