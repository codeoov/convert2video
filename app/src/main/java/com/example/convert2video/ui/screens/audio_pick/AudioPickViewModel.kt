package com.example.convert2video.ui.screens.audio_pick

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.convert2video.R
import com.example.convert2video.data.AppDatabase
import com.example.convert2video.data.AudioItem
import com.example.convert2video.data.AudioRepository
import com.example.convert2video.data.ConversionHistoryRepository
import com.example.convert2video.data.RecordingRepository
import com.example.convert2video.data.toAudioItem
import com.example.convert2video.utils.AppLogger
import com.example.convert2video.utils.appString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AudioPickViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AudioRepository(application)
    private val recordingRepository = RecordingRepository(
        application,
        AppDatabase.getInstance(application).recordingDao(),
    )
    private val conversionHistoryRepository = ConversionHistoryRepository.create(application)

    /** Audio URIs (as strings) that already have a conversion record, for the "이미 변환됨" badge. */
    val convertedAudioUris: StateFlow<Set<String>> = conversionHistoryRepository
        .observeConvertedAudioUris()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptySet(),
        )

    private val _mediaItems = MutableStateFlow<List<AudioItem>>(emptyList())
    private val _recordingItems = MutableStateFlow<List<AudioItem>>(emptyList())

    private val _audioFilter = MutableStateFlow(AudioSourceFilter.MyRecordings)
    val audioFilter: StateFlow<AudioSourceFilter> = _audioFilter.asStateFlow()

    val displayedAudioItems: StateFlow<List<AudioItem>> = combine(
        _mediaItems,
        _recordingItems,
        _audioFilter,
    ) { media, recordings, filter ->
        filterDisplayedItems(media, recordings, filter)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private val _isLoadingMedia = MutableStateFlow(false)
    private val _isLoadingRecordings = MutableStateFlow(false)

    val isLoadingMedia: StateFlow<Boolean> = _isLoadingMedia.asStateFlow()
    val isLoadingRecordings: StateFlow<Boolean> = _isLoadingRecordings.asStateFlow()

    val isLoading: StateFlow<Boolean> = combine(
        _isLoadingMedia,
        _isLoadingRecordings,
    ) { media, recordings -> media || recordings }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    val mediaItems: StateFlow<List<AudioItem>> = _mediaItems.asStateFlow()
    val recordingItems: StateFlow<List<AudioItem>> = _recordingItems.asStateFlow()

    private val _mediaError = MutableStateFlow<String?>(null)
    val mediaError: StateFlow<String?> = _mediaError.asStateFlow()

    private val _recordingsError = MutableStateFlow<String?>(null)
    val recordingsError: StateFlow<String?> = _recordingsError.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    private val _isSelectionMode = MutableStateFlow(false)
    val isSelectionMode: StateFlow<Boolean> = _isSelectionMode.asStateFlow()

    fun setAudioFilter(filter: AudioSourceFilter) {
        if (_audioFilter.value != filter) {
            _audioFilter.value = filter
            clearSelection()
        }
    }

    fun loadAudio() {
        viewModelScope.launch {
            _isLoadingMedia.value = true
            _mediaError.value = null
            try {
                val items = repository.queryAudioFiles()
                _mediaItems.value = items
                syncSelectionWithDisplayed()
            } catch (e: Exception) {
                AppLogger.e(TAG, "오디오 쿼리 실패", e)
                _mediaItems.value = emptyList()
                _mediaError.value = appString(R.string.audio_pick_load_media_failed)
                clearSelection()
            } finally {
                _isLoadingMedia.value = false
            }
        }
    }

    fun loadRecordings() {
        viewModelScope.launch {
            _isLoadingRecordings.value = true
            _recordingsError.value = null
            try {
                val records = recordingRepository.queryAllRecordings()
                _recordingItems.value = records.map { it.toAudioItem(recordingRepository) }
                syncSelectionWithDisplayed()
            } catch (e: Exception) {
                AppLogger.e(TAG, "녹음 목록 쿼리 실패", e)
                _recordingItems.value = emptyList()
                _recordingsError.value = appString(R.string.audio_pick_load_recordings_failed)
            } finally {
                _isLoadingRecordings.value = false
            }
        }
    }

    fun enterSelectionMode(initialId: Long) {
        _isSelectionMode.value = true
        _selectedIds.value = setOf(initialId)
    }

    fun toggleSelection(id: Long) {
        _selectedIds.value = if (id in _selectedIds.value) {
            _selectedIds.value - id
        } else {
            _selectedIds.value + id
        }
        if (_selectedIds.value.isEmpty()) {
            _isSelectionMode.value = false
        }
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
        _isSelectionMode.value = false
    }

    private fun syncSelectionWithDisplayed() {
        val visibleIds = filterDisplayedItems(
            _mediaItems.value,
            _recordingItems.value,
            _audioFilter.value,
        ).map { it.id }.toSet()
        _selectedIds.value = _selectedIds.value intersect visibleIds
        if (_selectedIds.value.isEmpty()) {
            _isSelectionMode.value = false
        }
    }

    companion object {
        private const val TAG = "AudioPickViewModel"
    }
}
