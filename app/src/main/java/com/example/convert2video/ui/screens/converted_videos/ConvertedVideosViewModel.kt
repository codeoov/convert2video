package com.example.convert2video.ui.screens.converted_videos

import android.app.Application
import android.net.Uri
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.convert2video.R
import com.example.convert2video.data.ConversionHistoryRepository
import com.example.convert2video.data.ConversionRecord
import com.example.convert2video.data.ConvertedVideo
import com.example.convert2video.data.ConvertedVideoRepository
import com.example.convert2video.data.UploadHistoryRepository
import com.example.convert2video.data.UploadRecord
import com.example.convert2video.utils.AppLogger
import com.example.convert2video.utils.appString
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

/** 변환 결과 목록 행: 미디어 항목 + 최신 업로드 이력(없으면 null) + 세그먼트·출처 메타. */
data class ConvertedVideoListItem(
    val video: ConvertedVideo,
    val uploadRecord: UploadRecord?,
    val segmentBatchId: String? = null,
    val segmentIndex: Int? = null,
    val segmentTotal: Int? = null,
    /** [ConversionRecord.audioUri] — 출처 필터용. conversion 이력 없으면 null. */
    val audioUri: String? = null,
)

/**
 * 결과 화면 혼합 목록: 세그먼트 그룹 헤더(+children) 또는 단독 행.
 * 평탄 List만으로는 불충분 — TabContent는 이 모델을 소비한다.
 */
sealed class ConvertedVideosListRow {
    data class Group(
        val segmentBatchId: String,
        val title: String,
        val segmentCount: Int,
        /** [segmentIndex] 오름차순. */
        val children: List<ConvertedVideoListItem>,
    ) : ConvertedVideosListRow()

    data class Standalone(
        val item: ConvertedVideoListItem,
    ) : ConvertedVideosListRow()
}

/** JVM 유닛 테스트용 미디어 참조 (android.net.Uri 없음). */
internal data class ConvertedVideoMediaRef(
    val uriString: String,
    val displayName: String,
    val dateAdded: Long,
)

/** 조인·그룹 매핑 중간 모델 (JVM 테스트 가능). */
internal data class ConvertedVideoJoinedRef(
    val uriString: String,
    val displayName: String,
    val dateAdded: Long,
    val uploadRecord: UploadRecord?,
    val segmentBatchId: String?,
    val segmentIndex: Int?,
    val segmentTotal: Int?,
    val audioUri: String?,
)

/**
 * 세그먼트 그룹 매핑 결과 (JVM 테스트용).
 * ViewModel이 [ConvertedVideosListRow]로 승격한다.
 */
internal sealed class ConvertedVideosSegmentRow {
    data class Group(
        val segmentBatchId: String,
        val title: String,
        val segmentCount: Int,
        val children: List<ConvertedVideoJoinedRef>,
    ) : ConvertedVideosSegmentRow()

    data class Standalone(
        val item: ConvertedVideoJoinedRef,
    ) : ConvertedVideosSegmentRow()
}

/**
 * 동일 videoUri 업로드 중 최신 1건.
 * 타이브레이크: uploadedAt DESC, 동점이면 id DESC (입력 순서·Dao 정렬 가정 금지).
 */
internal fun pickLatestUploadRecord(
    existing: UploadRecord?,
    candidate: UploadRecord,
): UploadRecord {
    if (existing == null) return candidate
    return when {
        candidate.uploadedAt > existing.uploadedAt -> candidate
        candidate.uploadedAt < existing.uploadedAt -> existing
        candidate.id >= existing.id -> candidate
        else -> existing
    }
}

/**
 * media-first 3-way 조인 + 그룹 매핑 (순수 함수).
 *
 * - 목록 후보 = [media]만 (Room-only orphan 행 없음, 빈 그룹 헤더 금지)
 * - upload: 동일 videoUri는 [pickLatestUploadRecord] 1건 (uploadedAt DESC, id DESC)
 * - 동일 non-blank segmentBatchId → Group, children은 segmentIndex ASC (null index는 맨 뒤)
 * - null/blank batchId·미매칭 → Standalone
 * - 혼합 목록 정렬: 그룹=자식 max dateAdded, 단독=자기 dateAdded, 내림차순
 */
internal fun mapConvertedVideosToSegmentRows(
    media: List<ConvertedVideoMediaRef>,
    conversions: List<ConversionRecord>,
    uploads: List<UploadRecord>,
): List<ConvertedVideosSegmentRow> {
    val latestByVideoUri = LinkedHashMap<String, UploadRecord>()
    for (record in uploads) {
        latestByVideoUri[record.videoUri] = pickLatestUploadRecord(
            existing = latestByVideoUri[record.videoUri],
            candidate = record,
        )
    }
    val conversionByVideoUri = LinkedHashMap<String, ConversionRecord>()
    for (record in conversions) {
        // 동일 URI에 여러 conversion이 있으면 최신(createdAt) 우선
        val existing = conversionByVideoUri[record.videoUri]
        if (existing == null || record.createdAt >= existing.createdAt) {
            conversionByVideoUri[record.videoUri] = record
        }
    }

    val joined = media.map { ref ->
        val conversion = conversionByVideoUri[ref.uriString]
        ConvertedVideoJoinedRef(
            uriString = ref.uriString,
            displayName = ref.displayName,
            dateAdded = ref.dateAdded,
            uploadRecord = latestByVideoUri[ref.uriString],
            segmentBatchId = conversion?.segmentBatchId,
            segmentIndex = conversion?.segmentIndex,
            segmentTotal = conversion?.segmentTotal,
            audioUri = conversion?.audioUri,
        )
    }

    val standalone = mutableListOf<ConvertedVideoJoinedRef>()
    val grouped = LinkedHashMap<String, MutableList<ConvertedVideoJoinedRef>>()
    for (item in joined) {
        val batchId = item.segmentBatchId
        if (batchId.isNullOrBlank()) {
            standalone += item
        } else {
            grouped.getOrPut(batchId) { mutableListOf() }.add(item)
        }
    }

    val rows = ArrayList<ConvertedVideosSegmentRow>(grouped.size + standalone.size)
    for ((batchId, members) in grouped) {
        if (members.isEmpty()) continue
        val children = members.sortedWith(
            compareBy<ConvertedVideoJoinedRef> { it.segmentIndex ?: Int.MAX_VALUE }
                .thenBy { it.uriString },
        )
        val title = children.first().displayName
        val segmentCount = resolveSegmentCount(children)
        rows += ConvertedVideosSegmentRow.Group(
            segmentBatchId = batchId,
            title = title,
            segmentCount = segmentCount,
            children = children,
        )
    }
    for (item in standalone) {
        rows += ConvertedVideosSegmentRow.Standalone(item)
    }

    return rows.sortedByDescending { row ->
        when (row) {
            is ConvertedVideosSegmentRow.Group ->
                row.children.maxOfOrNull { it.dateAdded } ?: 0L
            is ConvertedVideosSegmentRow.Standalone -> row.item.dateAdded
        }
    }
}

/**
 * 표시용 segmentCount = 매칭된 미디어 수([children].size)만.
 * segmentTotal이 더 커도 허수(부분 배치)를 쓰지 않는다.
 */
internal fun resolveSegmentCount(children: List<ConvertedVideoJoinedRef>): Int = children.size

/**
 * ViewModel 승격 시 segmentCount 단일 소스.
 * 매핑 단계 [mappedSegmentCount]와 승격 후 children 크기가 달라도
 * 항상 [childrenSize]를 쓴다(부분 필터·mapNotNull 드롭 후 허수 금지).
 */
internal fun resolvePromotedSegmentCount(
    @Suppress("UNUSED_PARAMETER") mappedSegmentCount: Int,
    childrenSize: Int,
): Int = childrenSize

/**
 * 목록 갱신 후 선택 동기화: 화면에 없는 URI 키는 제거.
 */
internal fun syncSelectionWithVisibleKeys(
    selectedUriKeys: Set<String>,
    visibleUriKeys: Set<String>,
): Set<String> = selectedUriKeys intersect visibleUriKeys

/**
 * 펼침 집합 prune: 현재 Group batchId와 intersect.
 * 사라진 배치(삭제·미디어 소실)의 expanded 잔존을 막는다.
 */
internal fun pruneExpandedSegmentBatchIds(
    expandedBatchIds: Set<String>,
    currentGroupBatchIds: Set<String>,
): Set<String> = expandedBatchIds intersect currentGroupBatchIds

/**
 * 그룹 「전체 선택」정책:
 * - 자식 URI가 모두 선택돼 있으면 자식만 해제(다른 선택 유지)
 * - 아니면 자식을 기존 선택에 union
 * - 빈 childKeys면 current 그대로
 */
internal fun applyGroupSelectAll(
    current: Set<String>,
    childKeys: Set<String>,
): Set<String> {
    if (childKeys.isEmpty()) return current
    return if (childKeys.all { it in current }) {
        current - childKeys
    } else {
        current + childKeys
    }
}

/**
 * 그룹「전체 선택」+ expand 정책 (고정):
 * - 자식을 새로 선택(union)하면 해당 [batchId]를 expanded에 추가해 선택 결과가 보이게 한다.
 * - 전부 선택 해제(toggle-off)해도 expand는 유지(자동으로 접지 않음).
 * - childKeys가 비면 selection·expanded 모두 그대로.
 *
 * @return Pair(nextSelection, nextExpandedBatchIds)
 */
internal fun applyGroupSelectAllWithExpandPolicy(
    currentSelection: Set<String>,
    childKeys: Set<String>,
    batchId: String,
    expandedBatchIds: Set<String>,
): Pair<Set<String>, Set<String>> {
    if (childKeys.isEmpty()) return currentSelection to expandedBatchIds
    val willSelectMore = !childKeys.all { it in currentSelection }
    val nextSelection = applyGroupSelectAll(currentSelection, childKeys)
    val nextExpanded = if (willSelectMore) {
        expandedBatchIds + batchId
    } else {
        expandedBatchIds
    }
    return nextSelection to nextExpanded
}

/**
 * 다이얼로그 표시 개수 = 요청 키 ∩ 현재 목록(실제 존재 영상만).
 */
internal fun resolveDialogSelectionCount(
    requestedUriKeys: Set<String>,
    visibleUriKeys: Set<String>,
): Int = (requestedUriKeys intersect visibleUriKeys).size

fun List<ConvertedVideosListRow>.flatVideoItems(): List<ConvertedVideoListItem> =
    flatMap { row ->
        when (row) {
            is ConvertedVideosListRow.Group -> row.children
            is ConvertedVideosListRow.Standalone -> listOf(row.item)
        }
    }

class ConvertedVideosViewModel(
    private val application: Application,
    private val repository: ConvertedVideoRepository,
    private val conversionHistoryRepository: ConversionHistoryRepository,
    private val uploadHistoryRepository: UploadHistoryRepository,
) : ViewModel() {

    private val _mediaVideos = MutableStateFlow<List<ConvertedVideo>>(emptyList())

    private val _sourceFilter = MutableStateFlow(ConvertedVideoSourceFilter.All)
    val sourceFilter: StateFlow<ConvertedVideoSourceFilter> = _sourceFilter.asStateFlow()

    private val _convertedSortOrder = MutableStateFlow(ConvertedVideosSortOrder.Time)
    val convertedSortOrder: StateFlow<ConvertedVideosSortOrder> = _convertedSortOrder.asStateFlow()

    fun setSourceFilter(filter: ConvertedVideoSourceFilter) {
        _sourceFilter.value = filter
    }

    fun setSortOrder(order: ConvertedVideosSortOrder) {
        _convertedSortOrder.value = order
    }

    /**
     * MediaStore/파일 + conversion 이력(segment*) + upload 이력을 합친 혼합 목록.
     * 동일 videoUri에 여러 업로드가 있으면 uploadedAt maxBy 1건만 매칭.
     * 출처·정렬 필터는 최상위 [ConvertedVideosListRow]에만 적용.
     */
    val listRows: StateFlow<List<ConvertedVideosListRow>> = combine(
        _mediaVideos,
        conversionHistoryRepository.observeAll(),
        uploadHistoryRepository.observeAll(),
        _sourceFilter,
        _convertedSortOrder,
    ) { media, conversions, uploads, sourceFilter, sortOrder ->
        val mediaByUri = media.associateBy { it.uri.toString() }
        val segmentRows = mapConvertedVideosToSegmentRows(
            media = media.map {
                ConvertedVideoMediaRef(
                    uriString = it.uri.toString(),
                    displayName = it.displayName,
                    dateAdded = it.dateAdded,
                )
            },
            conversions = conversions,
            uploads = uploads,
        )
        val promoted = segmentRows.mapNotNull { row ->
            when (row) {
                is ConvertedVideosSegmentRow.Group -> {
                    val children = row.children.mapNotNull { ref ->
                        val video = mediaByUri[ref.uriString] ?: return@mapNotNull null
                        ConvertedVideoListItem(
                            video = video,
                            uploadRecord = ref.uploadRecord,
                            segmentBatchId = ref.segmentBatchId,
                            segmentIndex = ref.segmentIndex,
                            segmentTotal = ref.segmentTotal,
                            audioUri = ref.audioUri,
                        )
                    }
                    if (children.isEmpty()) return@mapNotNull null
                    // 승격 시 segmentCount = children.size 재계산 (매핑값 불일치 시에도 허수 금지)
                    ConvertedVideosListRow.Group(
                        segmentBatchId = row.segmentBatchId,
                        title = row.title,
                        segmentCount = resolvePromotedSegmentCount(
                            mappedSegmentCount = row.segmentCount,
                            childrenSize = children.size,
                        ),
                        children = children,
                    )
                }
                is ConvertedVideosSegmentRow.Standalone -> {
                    val video = mediaByUri[row.item.uriString] ?: return@mapNotNull null
                    ConvertedVideosListRow.Standalone(
                        ConvertedVideoListItem(
                            video = video,
                            uploadRecord = row.item.uploadRecord,
                            segmentBatchId = row.item.segmentBatchId,
                            segmentIndex = row.item.segmentIndex,
                            segmentTotal = row.item.segmentTotal,
                            audioUri = row.item.audioUri,
                        ),
                    )
                }
            }
        }
        val filtered = filterConvertedVideosListRowsBySource(promoted, sourceFilter)
        sortConvertedVideosListRows(filtered, sortOrder)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** 단발성 오류 이벤트: SharedFlow로 누락 없이 전달 (extraBufferCapacity=10). */
    private val _errorMessage = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val errorMessage: SharedFlow<String> = _errorMessage.asSharedFlow()

    private val _videoDeleteConfirmation =
        MutableSharedFlow<IntentSenderRequest>(extraBufferCapacity = 1)
    val videoDeleteConfirmation: SharedFlow<IntentSenderRequest> =
        _videoDeleteConfirmation.asSharedFlow()

    private val _pendingDeleteVideo = MutableStateFlow<ConvertedVideo?>(null)
    val pendingDeleteVideo: StateFlow<ConvertedVideo?> = _pendingDeleteVideo.asStateFlow()

    fun clearPendingVideoDelete() {
        _pendingDeleteVideo.value?.let { repository.cancelPendingVideoDelete(it.uri) }
        _pendingDeleteVideo.value = null
    }

    // 초기 로드는 ConvertedVideosTabContent의 LaunchedEffect(Unit)에서 단일 처리
    // (init 중복 호출 방지)

    fun loadVideos() {
        viewModelScope.launch {
            refreshVideos()
        }
    }

    fun deleteVideo(video: ConvertedVideo) {
        viewModelScope.launch {
            try {
                when (val outcome = repository.deleteVideoWithOutcome(video)) {
                    is ConvertedVideoRepository.VideoDeleteOutcome.Deleted -> {
                        deleteHistoryForVideo(video.uri)
                    }
                    is ConvertedVideoRepository.VideoDeleteOutcome.NeedsConfirmation -> {
                        _pendingDeleteVideo.value = video
                        _videoDeleteConfirmation.emit(
                            IntentSenderRequest.Builder(outcome.intentSender).build(),
                        )
                        return@launch
                    }
                    is ConvertedVideoRepository.VideoDeleteOutcome.Failed -> {
                        _errorMessage.tryEmit(application.appString(R.string.converted_videos_delete_failed))
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "영상 삭제 실패", e)
                _errorMessage.tryEmit(application.appString(R.string.converted_videos_delete_failed))
            } finally {
                if (_pendingDeleteVideo.value == null) {
                    refreshVideos(showLoading = false)
                }
            }
        }
    }

    fun confirmVideoDelete(confirmed: Boolean) {
        val video = _pendingDeleteVideo.value
        _pendingDeleteVideo.value = null
        if (video == null) return
        if (!confirmed) {
            repository.cancelPendingVideoDelete(video.uri)
            return
        }
        viewModelScope.launch {
            try {
                when (repository.confirmVideoDelete(video, confirmed = true)) {
                    is ConvertedVideoRepository.VideoDeleteOutcome.Deleted -> {
                        deleteHistoryForVideo(video.uri)
                    }
                    else -> {
                        _errorMessage.tryEmit(application.appString(R.string.converted_videos_delete_failed))
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "영상 확인 삭제 실패", e)
                _errorMessage.tryEmit(application.appString(R.string.converted_videos_delete_failed))
            } finally {
                refreshVideos(showLoading = false)
            }
        }
    }

    fun deleteVideos(uris: Collection<Uri>) {
        viewModelScope.launch {
            val uriKeys = uris.map { it.toString() }.toSet()
            val toDelete = _mediaVideos.value.filter { it.uri.toString() in uriKeys }
            if (toDelete.isEmpty()) {
                _errorMessage.tryEmit(application.appString(R.string.converted_videos_delete_not_found))
                return@launch
            }

            var hasFailure = false
            var awaitingConfirmation = false
            try {
                for (video in toDelete) {
                    try {
                        when (val outcome = repository.deleteVideoWithOutcome(video)) {
                            is ConvertedVideoRepository.VideoDeleteOutcome.Deleted -> {
                                deleteHistoryForVideo(video.uri)
                            }
                            is ConvertedVideoRepository.VideoDeleteOutcome.NeedsConfirmation -> {
                                _pendingDeleteVideo.value = video
                                _videoDeleteConfirmation.emit(
                                    IntentSenderRequest.Builder(outcome.intentSender).build(),
                                )
                                awaitingConfirmation = true
                                break
                            }
                            is ConvertedVideoRepository.VideoDeleteOutcome.Failed -> {
                                hasFailure = true
                            }
                        }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "일괄 삭제 중 항목 실패", e)
                        hasFailure = true
                    }
                }
                if (hasFailure) {
                    _errorMessage.tryEmit(application.appString(R.string.converted_videos_delete_failed))
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "일괄 삭제 실패", e)
                _errorMessage.tryEmit(application.appString(R.string.converted_videos_delete_failed))
            } finally {
                if (!awaitingConfirmation) {
                    refreshVideos(showLoading = false)
                }
            }
        }
    }

    fun renameVideo(video: ConvertedVideo, newName: String) {
        viewModelScope.launch {
            try {
                val success = repository.renameVideo(video, newName)
                if (!success) {
                    _errorMessage.tryEmit(application.appString(R.string.converted_videos_rename_failed))
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "영상 이름 변경 실패", e)
                _errorMessage.tryEmit(application.appString(R.string.converted_videos_rename_failed))
            } finally {
                refreshVideos(showLoading = false)
            }
        }
    }

    /**
     * 미디어 삭제 성공 후 호출. 이력 삭제 실패는 로그만 남기고 미디어 성공을 유지한다.
     */
    private suspend fun deleteHistoryForVideo(videoUri: Uri) {
        try {
            conversionHistoryRepository.deleteByVideoUri(videoUri)
        } catch (e: Exception) {
            AppLogger.e(TAG, "변환 이력 삭제 실패", e)
        }
        try {
            uploadHistoryRepository.deleteByVideoUri(videoUri)
        } catch (e: Exception) {
            AppLogger.e(TAG, "업로드 이력 삭제 실패", e)
        }
    }

    private suspend fun refreshVideos(showLoading: Boolean = true) {
        if (showLoading) _isLoading.value = true
        try {
            _mediaVideos.value = repository.getConvertedVideos()
        } catch (e: Exception) {
            AppLogger.e(TAG, "영상 목록 새로고침 실패", e)
            _mediaVideos.value = emptyList()
            _errorMessage.tryEmit(application.appString(R.string.converted_videos_load_failed))
        } finally {
            if (showLoading) _isLoading.value = false
        }
    }

    companion object {
        private const val TAG = "ConvertedVideosViewModel"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app: Application = checkNotNull(this[APPLICATION_KEY])
                ConvertedVideosViewModel(
                    application = app,
                    repository = ConvertedVideoRepository(app),
                    conversionHistoryRepository = ConversionHistoryRepository.create(app),
                    uploadHistoryRepository = UploadHistoryRepository.create(app),
                )
            }
        }
    }
}
