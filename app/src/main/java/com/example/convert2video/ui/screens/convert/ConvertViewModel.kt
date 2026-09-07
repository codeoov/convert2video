package com.example.convert2video.ui.screens.convert

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.convert2video.R
import com.example.convert2video.analytics.AnalyticsReporter
import com.example.convert2video.data.AppDatabase
import com.example.convert2video.data.BackgroundImage
import com.example.convert2video.data.BackgroundRepository
import com.example.convert2video.data.SettingsRepository
import com.example.convert2video.ui.shared.ConversionUiState
import com.example.convert2video.ui.shared.WorkInfoUiPhase
import com.example.convert2video.ui.shared.isRunning
import com.example.convert2video.ui.shared.toWorkInfoUiPhase
import com.example.convert2video.utils.AppLogger
import com.example.convert2video.utils.appString
import com.example.convert2video.video.CONVERSION_UNIQUE_WORK_NAME
import com.example.convert2video.video.ConversionWorker
import com.example.convert2video.video.VideoConverter
import com.example.convert2video.video.VideoSegment
import com.example.convert2video.video.VideoSegmentPlanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

class ConvertViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = BackgroundRepository(
        context = application,
        dao = AppDatabase.getInstance(application).backgroundDao(),
    )
    private val workManager = WorkManager.getInstance(application)
    private val settingsRepository = SettingsRepository(application)
    private val videoConverter = VideoConverter(application)

    /** Serializes plain + segmented enqueue so concurrent starts cannot race KEEP. */
    private val conversionEnqueueMutex = Mutex()

    /**
     * Cancellable lastUsed persist Job. Launched only after enqueue succeeds.
     * Cancelled on a newer successful start so DataStore last-writer is the latest
     * enqueued conversion (join before next enqueue, mutex inside persist).
     */
    private var persistLastUsedJob: Job? = null

    val selectedBackground: StateFlow<BackgroundImage?> = repository.backgrounds
        .map { list -> list.find { it.isSelected } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    private val _selectedAudioList = MutableStateFlow<List<SelectedAudio>>(emptyList())
    val selectedAudioList: StateFlow<List<SelectedAudio>> = _selectedAudioList.asStateFlow()

    private val _audioSourceTab = MutableStateFlow(AudioSourceTab.Record)
    val audioSourceTab: StateFlow<AudioSourceTab> = _audioSourceTab.asStateFlow()

    /** Batch request → item mapping; StateFlow ensures coroutine-safe reads in combine(). */
    private val _pendingBatchItems = MutableStateFlow<List<PendingBatchItem>>(emptyList())

    /** Whether the user explicitly dismissed the current result dialog. Reset on each startConversion(). */
    private val _conversionResultDismissed = MutableStateFlow(false)

    private val _isSegmentSplitEnabled = MutableStateFlow(false)
    val isSegmentSplitEnabled: StateFlow<Boolean> = _isSegmentSplitEnabled.asStateFlow()

    private val _segmentPlanMode = MutableStateFlow(SegmentPlanMode.Equal)
    val segmentPlanMode: StateFlow<SegmentPlanMode> = _segmentPlanMode.asStateFlow()

    private val _equalSegmentCount = MutableStateFlow(DEFAULT_EQUAL_SEGMENT_COUNT)
    val equalSegmentCount: StateFlow<Int> = _equalSegmentCount.asStateFlow()

    private val _customSegmentDrafts = MutableStateFlow(listOf(CustomSegmentDraft()))
    val customSegmentDrafts: StateFlow<List<CustomSegmentDraft>> = _customSegmentDrafts.asStateFlow()

    private val _segmentPlanError = MutableStateFlow<SegmentPlanError?>(null)
    val segmentPlanError: StateFlow<SegmentPlanError?> = _segmentPlanError.asStateFlow()

    private val _isPreparingConversion = MutableStateFlow(false)
    val isPreparingConversion: StateFlow<Boolean> = _isPreparingConversion.asStateFlow()

    private val _userMessage = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    /**
     * Single upstream subscription shared by both downstream StateFlows.
     * Avoids double getWorkInfosForUniqueWorkFlow subscription.
     */
    private val workInfosFlow: StateFlow<List<WorkInfo>> = workManager
        .getWorkInfosForUniqueWorkFlow(CONVERSION_WORK_NAME)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /** WorkRequest ids from the last successful enqueue. Empty = leftover unique Success must not log. */
    private val watchedConversionWorkIds = mutableSetOf<UUID>()

    /** VM-lifetime COMPLETED ids after a watched generation logs. */
    private val reportedCompletedWorkIds = mutableSetOf<UUID>()

    private val conversionFailedFallback: String =
        appString(R.string.conversion_failed_fallback)

    init {
        viewModelScope.launch {
            val infos = withContext(Dispatchers.IO) {
                runCatching { workManager.getWorkInfosForUniqueWork(CONVERSION_WORK_NAME).get() }
                    .getOrDefault(emptyList())
            }
            if (infos.isNotEmpty() && infos.all { it.state.isFinished }) {
                _conversionResultDismissed.value = true
            }
            workInfosFlow.collect { collected ->
                maybeLogConversionCompleted(collected)
            }
        }
    }

    /**
     * Single source of truth for conversion state. Dismissed flag suppresses terminal states
     * without touching other workers (pruneWork removed).
     */
    val conversionState: StateFlow<ConversionUiState> = combine(
        workInfosFlow,
        _conversionResultDismissed,
    ) { infos, dismissed ->
        if (dismissed) {
            ConversionUiState.Idle
        } else {
            infos.toAggregateConversionUiState(conversionFailedFallback)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ConversionUiState.Idle,
    )

    /** Per-item batch progress, derived from the same shared workInfosFlow. */
    val batchConversionItems: StateFlow<List<BatchItemState>> = combine(
        workInfosFlow,
        _pendingBatchItems,
    ) { infos, pending ->
        toBatchConversionUiState(pending, infos, conversionFailedFallback)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    fun setAudioList(items: List<SelectedAudio>) {
        _selectedAudioList.value = items
        if (_isSegmentSplitEnabled.value && items.size != 1) {
            _segmentPlanError.value = SegmentPlanError.SingleAudioRequired
        } else if (items.size <= 1) {
            // Clear multi-audio guard error when the list becomes valid again.
            if (_segmentPlanError.value is SegmentPlanError.SingleAudioRequired) {
                _segmentPlanError.value = null
            }
        }
    }

    fun selectAudioSourceTab(tab: AudioSourceTab) {
        _audioSourceTab.value = tab
    }

    /**
     * Phase 3 — 녹음 저장 seam 성공 후 Home 오디오 반영.
     * [AudioSourceTab.Record]로 맞추고 목록을 교체한다. **startConversion은 호출하지 않는다.**
     */
    fun applyRecordingSaved(uri: Uri, title: String, durationMs: Long = 0L) {
        selectAudioSourceTab(AudioSourceTab.Record)
        setAudioList(
            listOf(
                SelectedAudio(
                    uri = uri,
                    title = title,
                    artist = null,
                    durationMs = durationMs,
                ),
            ),
        )
    }

    /**
     * Phase 3 — AudioPick 완료 후 Home 오디오 반영 ([applyRecordingSaved] 대칭).
     * [AudioSourceTab.FilePick] + 목록 교체. **startConversion은 호출하지 않는다.**
     */
    fun applyFilePickSaved(items: List<SelectedAudio>) {
        selectAudioSourceTab(AudioSourceTab.FilePick)
        setAudioList(items)
    }

    /**
     * FileProvider URI 실패 Fallback 메시지.
     *
     * **정책**: 목록·[audioSourceTab] 모두 유지(탭↔목록 출처 일치).
     * Activity는 Record 잔류 + [RecordViewModel.clearTerminalState] + Toast로 UX를 닫는다.
     */
    fun reportRecordingUriFailed() {
        _userMessage.tryEmit(appString(R.string.home_recording_uri_failed))
    }

    fun setSegmentSplitEnabled(enabled: Boolean) {
        _isSegmentSplitEnabled.value = enabled
        if (!enabled) {
            _segmentPlanError.value = null
        } else if (_selectedAudioList.value.size != 1) {
            _segmentPlanError.value = SegmentPlanError.SingleAudioRequired
        }
    }

    fun setSegmentPlanMode(mode: SegmentPlanMode) {
        _segmentPlanMode.value = mode
        _segmentPlanError.value = null
        if (mode == SegmentPlanMode.Custom && _customSegmentDrafts.value.isEmpty()) {
            _customSegmentDrafts.value = listOf(CustomSegmentDraft())
        }
    }

    fun setEqualSegmentCount(count: Int) {
        _equalSegmentCount.value = count.coerceIn(MIN_EQUAL_SEGMENT_COUNT, VideoSegmentPlanner.MAX_SEGMENT_COUNT)
        _segmentPlanError.value = null
    }

    fun updateCustomSegmentDraft(index: Int, draft: CustomSegmentDraft) {
        val current = _customSegmentDrafts.value.toMutableList()
        if (index !in current.indices) return
        current[index] = draft
        _customSegmentDrafts.value = current
        _segmentPlanError.value = null
    }

    fun addCustomSegmentDraft() {
        val current = _customSegmentDrafts.value
        if (current.size >= VideoSegmentPlanner.MAX_SEGMENT_COUNT) return
        _customSegmentDrafts.value = current + CustomSegmentDraft()
        _segmentPlanError.value = null
    }

    fun removeCustomSegmentDraft(index: Int) {
        val current = _customSegmentDrafts.value
        if (current.size <= 1 || index !in current.indices) return
        _customSegmentDrafts.value = current.filterIndexed { i, _ -> i != index }
        _segmentPlanError.value = null
    }

    fun clearSegmentPlanError() {
        _segmentPlanError.value = null
    }

    /**
     * Enqueues N items as a sequential chain. No-op if no background or list is empty.
     * dismissed flag is reset only after all guards pass, immediately before enqueue —
     * so a guard failure never accidentally clears the current result state.
     *
     * lastUsed persist is **not** launched on button tap. [schedulePersistLastUsedIfEnqueued]
     * runs only after [enqueueConversionChain] returns true.
     */
    fun startConversion() {
        val backgroundPath = selectedBackground.value?.filePath
        val items = _selectedAudioList.value
        when (evaluateConversionStartGate(
            backgroundPath = backgroundPath,
            items = items,
            isPreparingConversion = _isPreparingConversion.value,
        )) {
            ConversionStartGate.MissingInputs,
            ConversionStartGate.Preparing,
            -> return
            ConversionStartGate.Allow -> Unit
        }
        val path = backgroundPath?.takeIf { it.isNotBlank() } ?: return

        if (_isSegmentSplitEnabled.value) {
            startSegmentedConversion(path, items)
        } else {
            startPlainConversion(path, items)
        }
    }

    /**
     * lastUsed persist Job/mutex — only after unique-work enqueue succeeds.
     * SkipUnfinished / query-failed / SingleAudioRequired never reach here with true.
     */
    private fun schedulePersistLastUsedIfEnqueued(
        enqueued: Boolean,
        path: String,
        segmentedSingleAudioRequired: Boolean = false,
    ) {
        if (!shouldPersistLastUsedAfterStart(
                guardsPassed = true,
                segmentedSingleAudioRequired = segmentedSingleAudioRequired,
                enqueueSucceeded = if (segmentedSingleAudioRequired) null else enqueued,
            )
        ) {
            return
        }
        persistLastUsedJob = launchPersistLastUsedBackgroundPath(
            scope = viewModelScope,
            mutex = conversionEnqueueMutex,
            previousJob = persistLastUsedJob,
            path = path,
            persist = { settingsRepository.setLastUsedBackgroundPath(it) },
        )
    }

    private fun startPlainConversion(backgroundPath: String, items: List<SelectedAudio>) {
        val requests = items.map { audio ->
            OneTimeWorkRequestBuilder<ConversionWorker>()
                .setInputData(
                    buildConversionInputData(
                        backgroundPath = backgroundPath,
                        audioUri = audio.uri.toString(),
                    ),
                )
                .build()
        }
        val pendingItems = requests.zip(items).map { (req, audio) ->
            PendingBatchItem(workId = req.id, audio = audio)
        }
        // Immediate UI gate; Mutex serializes with segmented path.
        _isPreparingConversion.value = true
        _segmentPlanError.value = null
        viewModelScope.launch {
            try {
                persistLastUsedJob?.join()
                val enqueued = conversionEnqueueMutex.withLock {
                    enqueueConversionChain(
                        requests = requests,
                        pendingItems = pendingItems,
                    )
                }
                schedulePersistLastUsedIfEnqueued(enqueued, backgroundPath)
            } finally {
                _isPreparingConversion.value = false
            }
        }
    }

    private fun startSegmentedConversion(backgroundPath: String, items: List<SelectedAudio>) {
        if (items.size != 1) {
            _segmentPlanError.value = SegmentPlanError.SingleAudioRequired
            schedulePersistLastUsedIfEnqueued(
                enqueued = false,
                path = backgroundPath,
                segmentedSingleAudioRequired = true,
            )
            return
        }
        val audio = items.first()
        // Snapshot plan inputs before IO so mid-flight UI edits cannot race the plan.
        val planMode = _segmentPlanMode.value
        val equalCount = _equalSegmentCount.value
        val customDrafts = _customSegmentDrafts.value.toList()
        val audioUriSnapshot = audio.uri

        _isPreparingConversion.value = true
        _segmentPlanError.value = null
        viewModelScope.launch {
            try {
                persistLastUsedJob?.join()
                val enqueued = conversionEnqueueMutex.withLock {
                    val durationUs = withContext(Dispatchers.IO) {
                        videoConverter.audioDurationUsOrNull(audioUriSnapshot)
                    }

                    // Mid-flight guard: split OFF or audio changed → never silent skip.
                    if (!_isSegmentSplitEnabled.value) {
                        emitMidFlightSkip()
                        return@withLock false
                    }
                    val currentAudio = _selectedAudioList.value.singleOrNull()
                    if (currentAudio == null || currentAudio.uri != audioUriSnapshot) {
                        emitMidFlightSkip()
                        return@withLock false
                    }

                    if (durationUs == null) {
                        _segmentPlanError.value = SegmentPlanError.DurationUnreadable
                        return@withLock false
                    }

                    when (planMode) {
                        SegmentPlanMode.Equal -> {
                            val planResult = VideoSegmentPlanner.computeEqualSegments(
                                totalDurationUs = durationUs,
                                count = equalCount,
                            )
                            if (planResult.isFailure) {
                                _segmentPlanError.value = SegmentPlanError.PlanInvalid
                                false
                            } else {
                                enqueueSegmentChain(backgroundPath, audio, planResult.getOrThrow())
                            }
                        }
                        SegmentPlanMode.Custom -> {
                            val parsed = parseCustomSegmentDrafts(customDrafts)
                            if (parsed.isFailure) {
                                _segmentPlanError.value = SegmentPlanError.CustomParse
                                false
                            } else {
                                val planResult = VideoSegmentPlanner.validateCustomSegments(
                                    durationUs,
                                    parsed.getOrThrow(),
                                )
                                if (planResult.isFailure) {
                                    _segmentPlanError.value = SegmentPlanError.PlanInvalid
                                    false
                                } else {
                                    enqueueSegmentChain(backgroundPath, audio, planResult.getOrThrow())
                                }
                            }
                        }
                    }
                }
                schedulePersistLastUsedIfEnqueued(enqueued, backgroundPath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "startSegmentedConversion failed", e)
                _segmentPlanError.value = SegmentPlanError.Unexpected
            } finally {
                _isPreparingConversion.value = false
            }
        }
    }

    private fun emitMidFlightSkip() {
        _userMessage.tryEmit(appString(R.string.conversion_skipped_mid_flight))
    }

    private suspend fun enqueueSegmentChain(
        backgroundPath: String,
        audio: SelectedAudio,
        segments: List<VideoSegment>,
    ): Boolean {
        _segmentPlanError.value = null
        val batchId = UUID.randomUUID().toString()
        val total = segments.size
        val requests = segments.mapIndexed { zeroBasedIndex, segment ->
            OneTimeWorkRequestBuilder<ConversionWorker>()
                .setInputData(
                    buildConversionInputData(
                        backgroundPath = backgroundPath,
                        audioUri = audio.uri.toString(),
                        segment = segment,
                        segmentBatchId = batchId,
                        segmentIndex = zeroBasedIndex + 1,
                        segmentTotal = total,
                    ),
                )
                .build()
        }
        return enqueueConversionChain(
            requests = requests,
            pendingItems = requests.mapIndexed { zeroBasedIndex, req ->
                PendingBatchItem(
                    workId = req.id,
                    audio = audio,
                    segmentIndex = zeroBasedIndex + 1,
                    segmentTotal = total,
                )
            },
        )
    }

    /**
     * Shared unique-work chain enqueue (Sprint 4 path). Both plain and segmented conversions
     * must go through this helper — do not re-implement beginUniqueWork/.then elsewhere.
     *
     * KEEP is preserved. Unfinished unique work or WM query failure → **fail-closed**:
     * does **not** mutate pending/dismissed and emits a user message (silent drop avoided).
     *
     * @return true if the chain was enqueued, false if skipped/rejected by guard.
     */
    private suspend fun enqueueConversionChain(
        requests: List<OneTimeWorkRequest>,
        pendingItems: List<PendingBatchItem>,
    ): Boolean {
        if (requests.isEmpty()) return false
        val infosResult = queryConversionWorkInfos()
        when (evaluateConversionEnqueueGuard(infosResult)) {
            ConversionEnqueueGuard.Allow -> Unit
            ConversionEnqueueGuard.SkipUnfinished -> {
                _userMessage.tryEmit(appString(R.string.conversion_skipped_active))
                return false
            }
            ConversionEnqueueGuard.RejectQueryFailed -> {
                AppLogger.e(
                    TAG,
                    "getWorkInfosForUniqueWork failed; refusing enqueue",
                    infosResult.exceptionOrNull(),
                )
                _userMessage.tryEmit(appString(R.string.conversion_enqueue_query_failed))
                return false
            }
        }
        _pendingBatchItems.value = pendingItems
        var chain = workManager.beginUniqueWork(
            CONVERSION_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            requests.first(),
        )
        requests.drop(1).forEach { req ->
            chain = chain.then(req)
        }
        // Reset dismissed only once all guards have passed and the chain is ready to enqueue.
        _conversionResultDismissed.value = false
        chain.enqueue()
        watchedConversionWorkIds.clear()
        watchedConversionWorkIds.addAll(requests.map { it.id })
        AnalyticsReporter.getInstance(getApplication())
            .logEvent(AnalyticsReporter.EVENT_CONVERSION_STARTED)
        return true
    }

    /**
     * COMPLETED is a collect side-effect, not a mapper side-effect.
     * Leftover unique Success (watched empty) does not log.
     */
    private fun maybeLogConversionCompleted(infos: List<WorkInfo>) {
        if (infos.isEmpty()) return
        val watched = watchedConversionWorkIds.toSet()
        if (watched.isEmpty()) return
        if (infos.toAggregateConversionUiState(conversionFailedFallback) !is ConversionUiState.Success) {
            return
        }
        val successIds = infos.mapNotNull { info ->
            if (info.toConversionUiState(conversionFailedFallback) is ConversionUiState.Success) {
                info.id
            } else {
                null
            }
        }.toSet()
        if (successIds.isEmpty()) return
        if (!successIds.containsAll(watched)) return
        watchedConversionWorkIds.clear()
        reportedCompletedWorkIds.addAll(successIds)
        AnalyticsReporter.getInstance(getApplication())
            .logEvent(AnalyticsReporter.EVENT_CONVERSION_COMPLETED)
    }

    private suspend fun queryConversionWorkInfos(): Result<List<WorkInfo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                workManager.getWorkInfosForUniqueWork(CONVERSION_WORK_NAME).get()
            }
        }

    /** Suppresses the result dialog; does NOT call pruneWork to avoid clearing unrelated workers. */
    fun dismissConversionResult() {
        _conversionResultDismissed.value = true
    }

    /** Cancels the entire in-progress chain; WorkManager surfaces this as [ConversionUiState.Cancelled]. */
    fun cancelConversion() {
        workManager.cancelUniqueWork(CONVERSION_WORK_NAME)
    }

    /** Home 오디오 소스 탭 — 빈 상태에서 SegmentedControl 선택·채움 시 "변경" 재진입 대상. */
    enum class AudioSourceTab {
        Record,
        FilePick,
    }

    data class SelectedAudio(
        val uri: Uri,
        val title: String,
        val artist: String?,
        val durationMs: Long = 0L,
    )

    data class PendingBatchItem(
        val workId: UUID,
        val audio: SelectedAudio,
        /** 1-based segment index when split ON; null for plain batch. */
        val segmentIndex: Int? = null,
        val segmentTotal: Int? = null,
    )

    data class BatchItemState(
        val audio: SelectedAudio,
        val state: ConversionUiState,
        val segmentIndex: Int? = null,
        val segmentTotal: Int? = null,
    )

    data class CustomSegmentDraft(
        val startSeconds: String = "0",
        val endSeconds: String = "60",
    )

    enum class SegmentPlanMode {
        Equal,
        Custom,
    }

    /** Typed segment-plan / guard errors — UI maps to strings; never compare getString(). */
    sealed class SegmentPlanError {
        data object SingleAudioRequired : SegmentPlanError()
        data object DurationUnreadable : SegmentPlanError()
        data object PlanInvalid : SegmentPlanError()
        data object CustomParse : SegmentPlanError()
        data object Unexpected : SegmentPlanError()
    }

    companion object {
        const val CONVERSION_WORK_NAME = CONVERSION_UNIQUE_WORK_NAME
        const val MIN_EQUAL_SEGMENT_COUNT = 2
        const val DEFAULT_EQUAL_SEGMENT_COUNT = 2
        private const val TAG = "ConvertViewModel"
    }
}

internal sealed interface ConversionStartGate {
    data object Allow : ConversionStartGate
    data object MissingInputs : ConversionStartGate
    data object Preparing : ConversionStartGate
}

/** Production gate used before any WorkRequest construction, preparation, or enqueue. */
internal fun evaluateConversionStartGate(
    backgroundPath: String?,
    items: List<ConvertViewModel.SelectedAudio>,
    isPreparingConversion: Boolean,
): ConversionStartGate {
    if (!passesStartConversionPersistGuards(backgroundPath, items.size, isPreparingConversion)) {
        return if (isPreparingConversion) {
            ConversionStartGate.Preparing
        } else {
            ConversionStartGate.MissingInputs
        }
    }
    return ConversionStartGate.Allow
}

/**
 * Exact side-effect seam for the start gate: the callback represents WorkRequest creation,
 * preparation, and enqueue, and is reachable only after [ConversionStartGate.Allow].
 */
internal fun runConversionStartGate(
    backgroundPath: String?,
    items: List<ConvertViewModel.SelectedAudio>,
    isPreparingConversion: Boolean,
    onAllowed: () -> Unit,
): ConversionStartGate {
    val gate = evaluateConversionStartGate(backgroundPath, items, isPreparingConversion)
    if (gate is ConversionStartGate.Allow) onAllowed()
    return gate
}

/**
 * Fail-closed gate for unique-work chain enqueue.
 * Query failure must not be treated as "no unfinished work".
 */
internal sealed class ConversionEnqueueGuard {
    data object Allow : ConversionEnqueueGuard()
    data object SkipUnfinished : ConversionEnqueueGuard()
    data object RejectQueryFailed : ConversionEnqueueGuard()
}

/**
 * Evaluates whether a new conversion chain may be enqueued.
 * - [Result] failure → [ConversionEnqueueGuard.RejectQueryFailed] (fail-closed)
 * - any unfinished WorkInfo → [ConversionEnqueueGuard.SkipUnfinished]
 * - otherwise → [ConversionEnqueueGuard.Allow]
 */
internal fun evaluateConversionEnqueueGuard(
    infosResult: Result<List<WorkInfo>>,
): ConversionEnqueueGuard {
    val infos = infosResult.getOrElse { return ConversionEnqueueGuard.RejectQueryFailed }
    return if (infos.any { !it.state.isFinished }) {
        ConversionEnqueueGuard.SkipUnfinished
    } else {
        ConversionEnqueueGuard.Allow
    }
}

/**
 * Builds Worker input Data.
 * - Segment OFF: [KEY_SEGMENT_*] keys are **omitted** (none) — never put 0/blank defaults.
 * - Segment ON: all five segment keys (Range + Batch) must be present together.
 * - Partial batch keys (1/3 or 2/3) are rejected even when range is absent.
 */
internal fun buildConversionInputData(
    backgroundPath: String,
    audioUri: String,
    segment: VideoSegment? = null,
    segmentBatchId: String? = null,
    segmentIndex: Int? = null,
    segmentTotal: Int? = null,
): Data {
    val builder = Data.Builder()
        .putString(ConversionWorker.KEY_BACKGROUND_PATH, backgroundPath)
        .putString(ConversionWorker.KEY_AUDIO_URI, audioUri)

    val batchKeyCount = listOf(
        segmentBatchId != null,
        segmentIndex != null,
        segmentTotal != null,
    ).count { it }
    require(batchKeyCount == 0 || batchKeyCount == 3) {
        "segment batch keys must be all 3 or none (got $batchKeyCount/3); " +
            "keys=${ConversionWorker.KEY_SEGMENT_BATCH_ID}," +
            "${ConversionWorker.KEY_SEGMENT_INDEX},${ConversionWorker.KEY_SEGMENT_TOTAL}"
    }

    val hasRange = segment != null
    val hasBatch = batchKeyCount == 3
    require(hasRange == hasBatch) {
        "segment range and batch keys must be all-or-none (got range=$hasRange batch=$hasBatch); " +
            "rangeKeys=${ConversionWorker.KEY_SEGMENT_START_US},${ConversionWorker.KEY_SEGMENT_END_US}"
    }
    if (segment != null && segmentBatchId != null && segmentIndex != null && segmentTotal != null) {
        builder
            .putLong(ConversionWorker.KEY_SEGMENT_START_US, segment.startUs)
            .putLong(ConversionWorker.KEY_SEGMENT_END_US, segment.endUs)
            .putString(ConversionWorker.KEY_SEGMENT_BATCH_ID, segmentBatchId)
            .putInt(ConversionWorker.KEY_SEGMENT_INDEX, segmentIndex)
            .putInt(ConversionWorker.KEY_SEGMENT_TOTAL, segmentTotal)
    }
    return builder.build()
}

/** Parses UI second-strings into [VideoSegment]s (µs). Does not validate against total duration. */
internal fun parseCustomSegmentDrafts(
    drafts: List<ConvertViewModel.CustomSegmentDraft>,
): Result<List<VideoSegment>> {
    if (drafts.isEmpty()) {
        return Result.failure(IllegalArgumentException("segments must not be empty"))
    }
    val segments = ArrayList<VideoSegment>(drafts.size)
    for ((index, draft) in drafts.withIndex()) {
        val startSec = draft.startSeconds.trim().toDoubleOrNull()
            ?: return Result.failure(IllegalArgumentException("segment[$index] start invalid"))
        val endSec = draft.endSeconds.trim().toDoubleOrNull()
            ?: return Result.failure(IllegalArgumentException("segment[$index] end invalid"))
        val startUs = (startSec * 1_000_000.0).toLong()
        val endUs = (endSec * 1_000_000.0).toLong()
        val created = VideoSegment.create(startUs, endUs)
        if (created.isFailure) {
            return Result.failure(
                created.exceptionOrNull()
                    ?: IllegalArgumentException("segment[$index] create failed"),
            )
        }
        segments.add(created.getOrThrow())
    }
    return Result.success(segments)
}

/**
 * Maps a single WorkInfo to ConversionUiState.
 * [failedFallbackMessage]는 호출부에서 `appString(R.string.conversion_failed_fallback)` 전달(하드코딩 기본값 금지).
 */
internal fun WorkInfo.toConversionUiState(failedFallbackMessage: String): ConversionUiState = when (toWorkInfoUiPhase()) {
    WorkInfoUiPhase.Active ->
        ConversionUiState.InProgress(progress.getInt(ConversionWorker.KEY_PROGRESS, 0))
    WorkInfoUiPhase.Succeeded -> {
        val videoUri = outputData.getString(ConversionWorker.KEY_VIDEO_URI)
        when {
            videoUri != null -> ConversionUiState.Success(Uri.parse(videoUri))
            // Per-item conversion failures are encoded as Result.success (see
            // ConversionWorker.KEY_ITEM_FAILED) so chained batch/segment siblings still run
            // instead of being cascade-CANCELLED by a WorkManager Result.failure().
            outputData.getBoolean(ConversionWorker.KEY_ITEM_FAILED, false) ->
                ConversionUiState.Failed(
                    outputData.getString(ConversionWorker.KEY_MESSAGE) ?: failedFallbackMessage,
                )
            else -> ConversionUiState.Idle
        }
    }
    WorkInfoUiPhase.Failed ->
        ConversionUiState.Failed(
            outputData.getString(ConversionWorker.KEY_MESSAGE) ?: failedFallbackMessage,
        )
    WorkInfoUiPhase.Cancelled -> ConversionUiState.Cancelled
}

/**
 * Aggregates a list of WorkInfos from a chained unique work into a single ConversionUiState.
 *
 * Priority (highest → lowest):
 *   1. Active (RUNNING/ENQUEUED/BLOCKED) → InProgress
 *   2. Any FAILED → Failed
 *   3. Any CANCELLED → Cancelled
 *   4. All SUCCEEDED → Success (last video URI if available, else Idle)
 *
 * For size==1 delegates to toConversionUiState() to preserve single-audio UX exactly.
 */
internal fun List<WorkInfo>.toAggregateConversionUiState(
    failedFallbackMessage: String,
): ConversionUiState {
    if (isEmpty()) return ConversionUiState.Idle
    if (size == 1) return first().toConversionUiState(failedFallbackMessage)
    val active = any { it.toWorkInfoUiPhase() == WorkInfoUiPhase.Active }
    if (active) {
        val percent = firstOrNull { it.isRunning() }
            ?.progress?.getInt(ConversionWorker.KEY_PROGRESS, 0) ?: 0
        return ConversionUiState.InProgress(percent)
    }
    // Terminal states only from here. Per-item failures may be either a genuine WorkManager
    // WorkInfo.State.FAILED (unexpected crash) or a soft-failure encoded as SUCCEEDED +
    // KEY_ITEM_FAILED (see ConversionWorker) — toConversionUiState() decodes both uniformly.
    val terminalStates = map { it.toConversionUiState(failedFallbackMessage) }
    terminalStates.filterIsInstance<ConversionUiState.Failed>().firstOrNull()?.let { return it }
    if (terminalStates.any { it == ConversionUiState.Cancelled }) return ConversionUiState.Cancelled
    // All SUCCEEDED: show success dialog; fall back to Idle if no URI (e.g., test stubs).
    return terminalStates.filterIsInstance<ConversionUiState.Success>().lastOrNull()
        ?: ConversionUiState.Idle
}

/**
 * Maps each [ConvertViewModel.PendingBatchItem] to its current WorkInfo state by UUID lookup.
 * Items not yet seen in WorkManager infos default to Idle.
 */
internal fun toBatchConversionUiState(
    pending: List<ConvertViewModel.PendingBatchItem>,
    infos: List<WorkInfo>,
    failedFallbackMessage: String,
): List<ConvertViewModel.BatchItemState> {
    if (pending.isEmpty()) return emptyList()
    val infoMap = infos.associateBy { it.id }
    return pending.map { item ->
        val resolved = resolveBatchItemMapping(
            info = infoMap[item.workId],
            segmentIndex = item.segmentIndex,
            segmentTotal = item.segmentTotal,
            failedFallbackMessage = failedFallbackMessage,
        )
        ConvertViewModel.BatchItemState(
            audio = item.audio,
            state = resolved.state,
            segmentIndex = resolved.segmentIndex,
            segmentTotal = resolved.segmentTotal,
        )
    }
}

/**
 * Pure mapping of WorkInfo + segment meta → batch row fields (Uri-free for JVM unit tests).
 */
internal data class BatchItemMapping(
    val state: ConversionUiState,
    val segmentIndex: Int?,
    val segmentTotal: Int?,
)

internal fun resolveBatchItemMapping(
    info: WorkInfo?,
    segmentIndex: Int?,
    segmentTotal: Int?,
    failedFallbackMessage: String,
): BatchItemMapping = BatchItemMapping(
    state = info?.toConversionUiState(failedFallbackMessage) ?: ConversionUiState.Idle,
    segmentIndex = segmentIndex,
    segmentTotal = segmentTotal,
)

/**
 * 3-guard SSOT for [ConvertViewModel.startConversion]: non-blank background path,
 * at least one audio item, not already preparing. Failures must not persist or enqueue.
 */
internal fun passesStartConversionPersistGuards(
    backgroundPath: String?,
    audioItemCount: Int,
    isPreparingConversion: Boolean,
): Boolean = !backgroundPath.isNullOrBlank() && audioItemCount > 0 && !isPreparingConversion

/**
 * lastUsed persist happens only after unique-work enqueue succeeds.
 * Button tap ([enqueueSucceeded] null), SingleAudioRequired, SkipUnfinished/query-failed
 * ([enqueueSucceeded] false) → false. Guards failed → false.
 */
internal fun shouldPersistLastUsedAfterStart(
    guardsPassed: Boolean,
    segmentedSingleAudioRequired: Boolean,
    enqueueSucceeded: Boolean?,
): Boolean {
    if (!guardsPassed) return false
    if (segmentedSingleAudioRequired) return false
    return enqueueSucceeded == true
}

/**
 * Persists lastUsedBackgroundPath after enqueue success.
 * Serialized on [mutex]; [previousJob] is cancelled so the latest successful enqueue is last-writer.
 * Contract: [CoroutineScope.launch] (caller uses viewModelScope).
 */
internal fun launchPersistLastUsedBackgroundPath(
    scope: CoroutineScope,
    mutex: Mutex,
    previousJob: Job?,
    path: String,
    persist: suspend (String) -> Unit,
): Job {
    previousJob?.cancel()
    return scope.launch {
        mutex.withLock {
            persistLastUsedBackgroundPathCatching(path, persist)
        }
    }
}

/**
 * DataStore persist with CE rethrow; other failures log simpleName only (no throwable / path).
 */
internal suspend fun persistLastUsedBackgroundPathCatching(
    path: String,
    persist: suspend (String) -> Unit,
) {
    try {
        persist(path)
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        AppLogger.e(
            "ConvertViewModel",
            "persist lastUsedBackgroundPath failed: ${e.javaClass.simpleName}",
        )
    }
}
