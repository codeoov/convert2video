package com.example.convert2video.ui.screens.recordings_list

import android.app.Application
import android.net.Uri
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.convert2video.R
import com.example.convert2video.data.AppDatabase
import com.example.convert2video.data.AudioItem
import com.example.convert2video.data.AudioRepository
import com.example.convert2video.data.ConversionHistoryRepository
import com.example.convert2video.data.ImportedAudioRecord
import com.example.convert2video.data.ImportedAudioRepository
import com.example.convert2video.data.RecordingRecord
import com.example.convert2video.data.RecordingRepository
import com.example.convert2video.data.toAudioItem
import com.example.convert2video.data.importedIdFromAudioItemId
import com.example.convert2video.record.recordingExtension
import com.example.convert2video.ui.screens.audio_pick.AudioSourceFilter
import com.example.convert2video.utils.AppLogger
import com.example.convert2video.utils.appString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingsListViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = RecordingRepository(
        application,
        AppDatabase.getInstance(application).recordingDao(),
    )
    private val importedAudioRepository = ImportedAudioRepository(
        application,
        AppDatabase.getInstance(application).importedAudioDao(),
    )
    private val audioRepository = AudioRepository(application)
    private val conversionHistoryRepository = ConversionHistoryRepository.create(application)

    /** Test seam for the MediaStore query; production uses [audioRepository]. */
    private var queryMediaAudio: suspend () -> List<AudioItem> = {
        audioRepository.queryAudioFiles()
    }

    // ── 녹음 (Room Flow) ─────────────────────────────────────────────────────

    val recordings: StateFlow<List<RecordingRecord>> = repository.recordings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /**
     * Room 녹음 Flow → AudioItem 매핑. [recordings] StateFlow에서 파생하므로
     * repository.recordings를 직접 재구독하지 않는다 (이중 Dao 구독 방지).
     * Flow (not StateFlow) — [audioItems] combine 내부에서 구독됨.
     *
     * Sprint E/F UI가 소비 예정.
     */
    private val recordingAudioItemsOverride = MutableStateFlow<List<AudioItem>?>(null)

    private val recordingAudioItemsFromRoom: Flow<List<AudioItem>> = recordings
        .map { records -> records.map { it.toAudioItem(repository) } }

    val recordingAudioItems: Flow<List<AudioItem>> = recordingAudioItemsOverride
        .flatMapLatest { override -> override?.let(::flowOf) ?: recordingAudioItemsFromRoom }

    // ── Import (Room Flow) ───────────────────────────────────────────────────

    val importedAudioRecords: StateFlow<List<ImportedAudioRecord>> =
        importedAudioRepository.importedRecords
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )

    private val importedAudioItemsOverride = MutableStateFlow<List<AudioItem>?>(null)

    private val importedAudioItemsFromRoom: Flow<List<AudioItem>> = importedAudioRecords
        .map { records -> records.map { it.toAudioItem(importedAudioRepository) } }

    val importedAudioItems: Flow<List<AudioItem>> = importedAudioItemsOverride
        .flatMapLatest { override -> override?.let(::flowOf) ?: importedAudioItemsFromRoom }

    // ── MediaStore 오디오 ─────────────────────────────────────────────────────

    private val _mediaItems = MutableStateFlow<List<AudioItem>>(emptyList())

    private val mediaLoadLock = Any()
    /**
     * Serializes current-generation error check with the suspending SharedFlow emit.
     * Lock order is always this mutex before [mediaLoadLock]; state-only paths
     * release [mediaLoadLock] before entering this mutex.
     */
    private val mediaErrorDispatchMutex = Mutex()
    private val mediaLoadState = MediaLoadState()
    private var mediaLoadErrorDispatchHookForTests: (suspend () -> Unit)? = null

    private class MediaLoadState {
        var job: Job? = null
        var generation: Long = 0L
        var activeGeneration: Long? = null
        var hasLoaded: Boolean = false
    }

    /**
     * MediaStore 오디오 목록을 로드한다.
     * 공개 호출은 항상 강제 새로고침이다. 이미 진행 중인 로드는 취소하지만,
     * 취소에 협조하지 않는 쿼리도 세대 검증을 통과한 최신 결과만 반영한다.
     * 실패 시 기존 [errorMessage] 이벤트 경로와 빈 목록 처리를 유지한다.
     *
     * Sprint E/F UI가 소비 예정.
     */
    fun loadMediaAudio() {
        synchronized(mediaLoadLock) {
            // Reserve the new generation before any queued coroutine can run.
            startMediaAudioLoadLocked()
        }
    }

    private fun ensureMediaAudioLoaded() {
        synchronized(mediaLoadLock) {
            if (mediaLoadState.hasLoaded || mediaLoadState.activeGeneration != null) return
            startMediaAudioLoadLocked()
        }
    }

    private fun startMediaAudioLoadLocked() {
        check(Thread.holdsLock(mediaLoadLock))
        val previousJob = mediaLoadState.job
        val requestGeneration = ++mediaLoadState.generation
        mediaLoadState.activeGeneration = requestGeneration
        mediaLoadState.job = null
        previousJob?.cancel()

        val job = viewModelScope.launch {
            try {
                val canStartQuery = mediaErrorDispatchMutex.withLock {
                    synchronized(mediaLoadLock) {
                        mediaLoadState.activeGeneration == requestGeneration
                    }
                }
                if (!canStartQuery) return@launch

                val items = queryMediaAudio()
                if (!commitMediaLoadSuccess(requestGeneration, items)) return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!commitMediaLoadFailure(requestGeneration)) return@launch
                AppLogger.e(TAG, "오디오 쿼리 실패", e)
                dispatchMediaLoadErrorIfCurrent(requestGeneration)
            } finally {
                synchronized(mediaLoadLock) {
                    if (mediaLoadState.activeGeneration == requestGeneration) {
                        mediaLoadState.activeGeneration = null
                        mediaLoadState.job = null
                    }
                }
            }
        }
        if (mediaLoadState.activeGeneration == requestGeneration) {
            mediaLoadState.job = job
        }
    }

    private fun commitMediaLoadSuccess(
        requestGeneration: Long,
        items: List<AudioItem>,
    ): Boolean =
        synchronized(mediaLoadLock) {
            if (mediaLoadState.activeGeneration != requestGeneration) return@synchronized false
            _mediaItems.value = items
            mediaLoadState.hasLoaded = true
            true
        }

    private fun commitMediaLoadFailure(requestGeneration: Long): Boolean =
        synchronized(mediaLoadLock) {
            if (mediaLoadState.activeGeneration != requestGeneration) return@synchronized false
            _mediaItems.value = emptyList()
            mediaLoadState.hasLoaded = false
            true
        }

    private suspend fun dispatchMediaLoadErrorIfCurrent(requestGeneration: Long) {
        mediaErrorDispatchMutex.withLock {
            val isCurrentBeforeHook = synchronized(mediaLoadLock) {
                mediaLoadState.activeGeneration == requestGeneration
            }
            if (!isCurrentBeforeHook) return@withLock

            val dispatchHook = synchronized(mediaLoadLock) {
                mediaLoadErrorDispatchHookForTests
            }
            dispatchHook?.invoke()

            val isCurrentBeforeEmit = synchronized(mediaLoadLock) {
                mediaLoadState.activeGeneration == requestGeneration
            }
            if (!isCurrentBeforeEmit) return@withLock
            _errorMessage.emit(appString(R.string.audio_pick_load_media_failed))
        }
    }

    /** Test-only seam; production callers keep the real [AudioRepository] query. */
    internal fun setMediaAudioQueryForTests(query: suspend () -> List<AudioItem>) {
        queryMediaAudio = query
    }

    internal fun setAudioPipelineFixturesForTests(
        recordings: List<AudioItem>,
        imported: List<AudioItem>,
        convertedUris: Set<String>,
    ) {
        recordingAudioItemsOverride.value = recordings
        importedAudioItemsOverride.value = imported
        convertedAudioUrisOverride.value = convertedUris
    }

    internal fun setMediaLoadErrorDispatchHookForTests(hook: (suspend () -> Unit)?) {
        synchronized(mediaLoadLock) {
            mediaLoadErrorDispatchHookForTests = hook
        }
    }

    override fun onCleared() {
        synchronized(mediaLoadLock) {
            mediaLoadState.generation += 1L
            mediaLoadState.activeGeneration = null
            mediaLoadState.job?.cancel()
            mediaLoadState.job = null
            mediaLoadState.hasLoaded = false
            mediaLoadErrorDispatchHookForTests = null
        }
        super.onCleared()
    }

    // ── 필터 / 정렬 ────────────────────────────────────────────────────────────

    /**
     * Listen 탭 1차 브라우징 — 기본 MyRecordings (좌측 탭).
     */
    private val _audioFilter = MutableStateFlow(AudioSourceFilter.MyRecordings)

    /**
     * 현재 오디오 소스 필터. Sprint E/F UI가 소비·변경 예정.
     */
    val audioFilter: StateFlow<AudioSourceFilter> = _audioFilter.asStateFlow()

    /**
     * Listen 화면은 Files 필터를 표시하지 않는다.
     * [AudioSourceFilter.Files]가 주입되면 [AudioSourceFilter.MyRecordings]로 정규화한다.
     */
    fun setAudioFilter(filter: AudioSourceFilter) {
        val normalizedFilter = if (filter == AudioSourceFilter.Files) {
            AudioSourceFilter.MyRecordings
        } else {
            filter
        }
        _audioFilter.value = normalizedFilter
        if (normalizedFilter == AudioSourceFilter.All) {
            ensureMediaAudioLoaded()
        }
    }

    private val _sortOrder = MutableStateFlow(RecordingsListSortOrder.Time)

    /**
     * 현재 정렬 순서. Sprint E/F UI가 소비·변경 예정.
     */
    val sortOrder: StateFlow<RecordingsListSortOrder> = _sortOrder.asStateFlow()

    fun setSortOrder(order: RecordingsListSortOrder) {
        _sortOrder.value = order
    }

    /**
     * Listen 화면 변환 여부 필터 상태.
     * 기본값 [RecordingsListConversionFilter.All] — [audioItems] combine에서 [filterByConversion]에 투입된다.
     */
    private val _conversionFilter =
        MutableStateFlow(RecordingsListConversionFilter.All)

    /**
     * 현재 변환 여부 필터. [RecordingsListContent]가 소비·변경한다.
     * [audioItems] combine 내 [filterByConversion] 연산의 SSOT.
     *
     * @see setConversionFilter
     */
    val conversionFilter: StateFlow<RecordingsListConversionFilter> =
        _conversionFilter.asStateFlow()

    /**
     * 변환 여부 필터를 [filter]로 변경한다.
     * [audioItems]가 즉시 재계산된다.
     *
     * @see conversionFilter
     */
    fun setConversionFilter(filter: RecordingsListConversionFilter) {
        _conversionFilter.value = filter
    }

    // ── 변환 이력 URI 집합 ────────────────────────────────────────────────────

    /**
     * 이미 변환된 오디오 URI 문자열 집합 (이미 변환됨 배지용 + 변환 필터 SSOT).
     * Sprint E/F UI가 소비 예정.
     */
    private val convertedAudioUrisOverride = MutableStateFlow<Set<String>?>(null)

    private val convertedAudioUrisFromRoom: StateFlow<Set<String>> = conversionHistoryRepository
        .observeConvertedAudioUris()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptySet(),
        )

    val convertedAudioUris: StateFlow<Set<String>> = convertedAudioUrisOverride
        .flatMapLatest { override -> override?.let(::flowOf) ?: convertedAudioUrisFromRoom }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptySet(),
        )

    // ── 통합 목록: filter → conversionFilter → sort ───────────────────────────

    /**
     * [filterListenAudioItems] → [filterByConversion] → [sortDisplayedItems] 순으로 적용한 표시 목록.
     * [convertedAudioUris] StateFlow를 재사용하며 [observeConvertedAudioUris] 재구독 금지.
     *
     * outer combine 파라미터 순서: innerFiltered / [_conversionFilter] / [convertedAudioUris] / [_sortOrder].
     */
    val audioItems: StateFlow<List<AudioItem>> = combine(
        combine(recordingAudioItems, _mediaItems, importedAudioItems, _audioFilter) {
            recordings, media, imported, filter ->
            filterListenAudioItems(media + imported, recordings, filter)
        },
        _conversionFilter,
        convertedAudioUris,
        _sortOrder,
    ) { sourceFiltered, cFilter, uris, sort ->
        sortDisplayedItems(filterByConversion(sourceFiltered, cFilter, uris), sort)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    // ── 오류 이벤트 ─────────────────────────────────────────────────────────

    /**
     * 단발 오류 이벤트. extraBufferCapacity=1 — Screen Stateful의
     * LaunchedEffect(Unit) 수집기가 composition 수명 동안 구독하며,
     * 구독 직전 1건 emit을 버퍼로 보존한다.
     */
    private val _errorMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errorMessage: SharedFlow<String> = _errorMessage.asSharedFlow()

    // ── 녹음 삭제 / 이름 변경 (기존 — 변경 없음) ──────────────────────────────

    fun deleteRecording(record: RecordingRecord) {
        viewModelScope.launch {
            try {
                if (!repository.deleteRecording(record)) {
                    _errorMessage.emit(appString(R.string.recordings_list_delete_failed))
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "녹음 삭제 실패", e)
                _errorMessage.emit(appString(R.string.recordings_list_delete_failed))
            }
        }
    }

    fun renameRecording(record: RecordingRecord, newDisplayName: String) {
        viewModelScope.launch {
            val success = repository.renameRecording(record, newDisplayName)
            if (!success) {
                _errorMessage.emit(appString(R.string.recordings_list_rename_failed))
            }
        }
    }

    fun uriFor(record: RecordingRecord): Uri = repository.uriFor(record)

    fun displayNameFor(record: RecordingRecord): String =
        File(record.filePath).name

    fun displayNameStemFor(record: RecordingRecord): String {
        val name = displayNameFor(record)
        val ext = recordingExtension(record.format)
        return if (name.endsWith(".$ext", ignoreCase = true)) {
            name.dropLast(ext.length + 1)
        } else {
            name.substringBeforeLast('.', name)
        }
    }

    /** Room 녹음 lookup 실패 시 Snackbar용 — Screen [errorMessage] collect 경로 SSOT. */
    fun reportRecordingLookupFailed(isRename: Boolean) {
        viewModelScope.launch {
            _errorMessage.emit(
                appString(
                    if (isRename) {
                        R.string.recordings_list_rename_failed
                    } else {
                        R.string.recordings_list_delete_failed
                    },
                ),
            )
        }
    }

    // ── MediaStore 오디오 삭제 ────────────────────────────────────────────────

    /**
     * MediaStore 오디오 파일 삭제 요청 결과.
     * [AudioRepository.MediaDeleteOutcome.NeedsConfirmation] 시 시스템 확인 UI 실행용 이벤트.
     * extraBufferCapacity=1 — Screen 수집기가 composition 수명 동안 구독.
     *
     * Sprint E/F UI가 launcher로 소비 예정.
     */
    private val _mediaDeleteConfirmation =
        MutableSharedFlow<IntentSenderRequest>(extraBufferCapacity = 1)
    val mediaDeleteConfirmation: SharedFlow<IntentSenderRequest> =
        _mediaDeleteConfirmation.asSharedFlow()

    /**
     * 시스템 확인 다이얼로그 대기 중인 URI.
     * - NeedsConfirmation 시 설정 → Screen이 별도 추적 없이 [confirmMediaDelete] 호출 가능.
     * - [confirmMediaDelete] 처리 후 null로 초기화.
     *
     * Sprint E/F UI가 소비 예정.
     */
    private val _pendingDeleteUri = MutableStateFlow<Uri?>(null)
    val pendingDeleteUri: StateFlow<Uri?> = _pendingDeleteUri.asStateFlow()

    /** IntentSender race 등 [confirmMediaDelete]에 uri 없을 때 pending만 해제. */
    fun clearPendingMediaDelete() {
        _pendingDeleteUri.value?.let { audioRepository.cancelPendingDelete(it) }
        _pendingDeleteUri.value = null
    }

    /**
     * MediaStore 오디오 [item]을 삭제 요청한다.
     *
     * - [AudioRepository.MediaDeleteOutcome.Deleted]: 즉시 삭제 성공 → [loadMediaAudio] 갱신.
     * - [AudioRepository.MediaDeleteOutcome.NeedsConfirmation]: 시스템 다이얼로그 필요
     *   → [_pendingDeleteUri] 저장 + [mediaDeleteConfirmation] emit.
     *   Screen이 launcher로 실행 후 [confirmMediaDelete] 호출.
     * - [AudioRepository.MediaDeleteOutcome.Failed]: AppLogger.e + [errorMessage] emit.
     *
     * Sprint E/F UI가 소비 예정.
     */
    fun deleteMediaAudioItem(item: AudioItem) {
        viewModelScope.launch {
            if (_pendingDeleteUri.value != null) {
                _errorMessage.emit(appString(R.string.recordings_list_media_delete_failed))
                return@launch
            }
            try {
                when (val outcome = audioRepository.deleteAudioItem(item)) {
                    is AudioRepository.MediaDeleteOutcome.Deleted -> loadMediaAudio()
                    is AudioRepository.MediaDeleteOutcome.NeedsConfirmation -> {
                        _pendingDeleteUri.value = item.uri
                        val request =
                            IntentSenderRequest.Builder(outcome.intentSender).build()
                        _mediaDeleteConfirmation.emit(request)
                    }
                    is AudioRepository.MediaDeleteOutcome.Failed -> {
                        AppLogger.e(TAG, "미디어 오디오 삭제 실패 (시스템 거절): ${item.fileName}")
                        _errorMessage.emit(appString(R.string.recordings_list_media_delete_failed))
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "미디어 오디오 삭제 실패", e)
                _errorMessage.emit(appString(R.string.recordings_list_media_delete_failed))
            }
        }
    }

    /**
     * 시스템 삭제 확인 다이얼로그 결과 처리.
     *
     * API 레벨별 동작 차이를 VM이 은닉한다:
     * - API 30+ (R): 시스템이 [uri] 파일을 이미 삭제함 → [_mediaItems]에서 즉시 제거.
     * - API 29 (Q): 사용자 허가만 받았고 파일은 아직 있음 → [audioRepository.deleteAudioFile]
     *   재호출. 성공 시 [_mediaItems] 제거; 실패 시 [errorMessage] emit.
     * - API 26-28: [AudioRepository.MediaDeleteOutcome.NeedsConfirmation]이 반환되지 않으므로
     *   이 경로에 도달하지 않음.
     *
     * [confirmed] false → 취소; [_pendingDeleteUri]만 초기화.
     *
     * Sprint E/F UI가 ActivityResultLauncher 결과 콜백에서 호출 예정.
     */
    fun confirmMediaDelete(uri: Uri, confirmed: Boolean) {
        val item = _mediaItems.value.find { it.uri == uri }
        _pendingDeleteUri.value = null
        if (item == null) {
            audioRepository.cancelPendingDelete(uri)
            return
        }
        if (!confirmed) {
            audioRepository.cancelPendingDelete(uri)
            return
        }

        viewModelScope.launch {
            try {
                when (audioRepository.confirmMediaDelete(item, confirmed = true)) {
                    is AudioRepository.MediaDeleteOutcome.Deleted ->
                        removeMediaItemAndRefresh(uri)
                    else -> {
                        AppLogger.e(
                            TAG,
                            "미디어 오디오 확인 삭제 실패: ${uri.lastPathSegment.orEmpty()}",
                        )
                        _errorMessage.emit(appString(R.string.recordings_list_media_delete_failed))
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "미디어 오디오 확인 삭제 예외", e)
                _errorMessage.emit(appString(R.string.recordings_list_media_delete_failed))
            }
        }
    }

    // ── URI import (앱 저장소 복사 → All 목록 영속) ───────────────────────────

    /**
     * 시스템 파일 피커 [uri]를 앱 저장소로 복사·인덱싱한다.
     * 성공 시 Room Flow가 목록을 갱신한다. Convert 네비게이션은 트리거하지 않는다.
     */
    fun importAudioFromUri(uri: Uri) {
        viewModelScope.launch {
            try {
                importedAudioRepository.importFromUri(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "importAudioFromUri failed", e)
                _errorMessage.emit(appString(R.string.recordings_list_import_failed))
            }
        }
    }

    /**
     * Import 항목 삭제 — FileProvider URI이므로 MediaStore 삭제 경로를 타지 않는다.
     */
    fun deleteImportedAudioItem(item: AudioItem) {
        val importedId = importedIdFromAudioItemId(item.id) ?: return
        viewModelScope.launch {
            val record = importedAudioRecords.value.find { it.id == importedId }
            if (record == null) {
                AppLogger.w(TAG, "imported audio lookup failed")
                _errorMessage.emit(appString(R.string.recordings_list_delete_failed))
                return@launch
            }
            try {
                if (!importedAudioRepository.deleteImportedAudio(record)) {
                    _errorMessage.emit(appString(R.string.recordings_list_delete_failed))
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "imported audio delete failed", e)
                _errorMessage.emit(appString(R.string.recordings_list_delete_failed))
            }
        }
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────────────


    private fun removeMediaItemAndRefresh(uri: Uri) {
        _mediaItems.value = _mediaItems.value.filterNot { it.uri == uri }
        loadMediaAudio()
    }

    companion object {
        private const val TAG = "RecordingsListViewModel"
    }

}
