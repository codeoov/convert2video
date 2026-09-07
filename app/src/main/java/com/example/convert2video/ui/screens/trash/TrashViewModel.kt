package com.example.convert2video.ui.screens.trash

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.convert2video.R
import com.example.convert2video.data.ConversionHistoryRepository
import com.example.convert2video.data.TrashRepository
import com.example.convert2video.data.TrashedItem
import com.example.convert2video.utils.AppLogger
import com.example.convert2video.utils.appString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TrashViewModel(
    application: Application,
    private val trashRepository: TrashRepository = TrashRepository.create(application),
    conversionHistoryRepository: ConversionHistoryRepository =
        ConversionHistoryRepository.create(application),
) : AndroidViewModel(application) {

    private val _userMessage = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    private val _actionInFlightIds = MutableStateFlow<Set<Long>>(emptySet())
    val actionInFlightIds: StateFlow<Set<Long>> = _actionInFlightIds.asStateFlow()

    internal val rows: StateFlow<List<TrashRowUi>> = combine(
        trashRepository.observeAll(),
        conversionHistoryRepository.observeConvertedAudioUris(),
    ) { items, convertedUris ->
        items.map { item -> toTrashRowUi(item, convertedUris) }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    fun restore(item: TrashedItem) {
        viewModelScope.launch {
            if (!tryBeginAction(item.id)) return@launch
            try {
                val restored = trashRepository.restore(item)
                if (!restored) {
                    _userMessage.emit(appString(R.string.trash_restore_failed))
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                AppLogger.e(TAG, "restore failed: ${e.javaClass.simpleName}", e)
                _userMessage.emit(appString(R.string.trash_restore_failed))
            } finally {
                endAction(item.id)
            }
        }
    }

    fun permanentlyDelete(item: TrashedItem) {
        viewModelScope.launch {
            if (!tryBeginAction(item.id)) return@launch
            try {
                trashRepository.permanentlyDelete(item)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                AppLogger.e(TAG, "permanentlyDelete failed: ${e.javaClass.simpleName}", e)
                _userMessage.emit(appString(R.string.trash_delete_failed))
            } finally {
                endAction(item.id)
            }
        }
    }

    private fun tryBeginAction(id: Long): Boolean {
        val current = _actionInFlightIds.value
        if (id in current) return false
        _actionInFlightIds.value = current + id
        return true
    }

    private fun endAction(id: Long) {
        _actionInFlightIds.value = _actionInFlightIds.value - id
    }

    companion object {
        private const val TAG = "TrashViewModel"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app: Application = checkNotNull(this[APPLICATION_KEY])
                TrashViewModel(
                    application = app,
                    trashRepository = TrashRepository.create(app),
                    conversionHistoryRepository = ConversionHistoryRepository.create(app),
                )
            }
        }
    }
}
