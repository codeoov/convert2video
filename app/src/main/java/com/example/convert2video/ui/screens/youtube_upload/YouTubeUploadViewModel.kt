package com.example.convert2video.ui.screens.youtube_upload

import android.accounts.Account
import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.convert2video.R
import com.example.convert2video.data.SettingsRepository
import com.example.convert2video.data.UploadHistoryRepository
import com.example.convert2video.ui.shared.WorkInfoUiPhase
import com.example.convert2video.ui.shared.toWorkInfoUiPhase
import com.example.convert2video.utils.AppLogger
import com.example.convert2video.utils.appString
import com.example.convert2video.youtube.AuthorizationOutcome
import com.example.convert2video.youtube.YouTubeApiClient
import com.example.convert2video.youtube.YouTubeApiResult
import com.example.convert2video.youtube.YouTubeAuthGateway
import com.example.convert2video.youtube.YouTubeUploadWorker
import com.example.convert2video.youtube.createYouTubeAuthGateway
import com.example.convert2video.youtube.youTubeFailureMessageToStringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "YouTubeUploadViewModel"

class YouTubeUploadViewModel(application: Application) : AndroidViewModel(application) {

    private val authManager: YouTubeAuthGateway = createYouTubeAuthGateway(application)
    private val apiClient = YouTubeApiClient()
    private val workManager = WorkManager.getInstance(application)
    private val settingsRepository = SettingsRepository(application)
    private val uploadHistoryRepository = UploadHistoryRepository.create(application)

    private val _isAuthorized = MutableStateFlow(authManager.isAuthorized)
    val isAuthorized: StateFlow<Boolean> = _isAuthorized.asStateFlow()

    private val _channelTitle = MutableStateFlow(authManager.cachedChannelTitle)
    val channelTitle: StateFlow<String?> = _channelTitle.asStateFlow()

    /**
     * 오늘(자정 기준) 업로드 횟수. Room COUNT(*) 쿼리 Flow 기반 — 전체 행 메모리 필터링 없음.
     * [_todayStartMillis]가 바뀌면 [flatMapLatest]로 새 구독으로 즉시 전환되어 자정 경과 후
     * 화면 재진입 시 [refreshTodayCount]를 호출하면 카운터 기준이 올바르게 갱신된다.
     */
    private val _todayStartMillis = MutableStateFlow(todayStartMillis())
    @OptIn(ExperimentalCoroutinesApi::class)
    val todayUploadCount: StateFlow<Int> = _todayStartMillis
        .flatMapLatest { startMillis -> uploadHistoryRepository.observeCountSince(startMillis) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0,
        )

    /** 단발성 동의 화면 요청: SharedFlow로 누락 없이 전달 (extraBufferCapacity=1). */
    private val _authorizationRequest = MutableSharedFlow<IntentSenderRequest>(extraBufferCapacity = 1)
    val authorizationRequest: SharedFlow<IntentSenderRequest> = _authorizationRequest.asSharedFlow()

    /** ConvertedVideos YouTube Login → Android 시스템 계정 선택기 요청 (Screen이 Intent 생성). Options와 동일 패턴, 공통 추출 금지. */
    private val _youtubeAccountPickerRequest = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val youtubeAccountPickerRequest: SharedFlow<Unit> = _youtubeAccountPickerRequest.asSharedFlow()

    /**
     * 계정 선택기에서 고른 Account. Consent UI를 거친 뒤에도 [rememberAccountName]에 쓰기 위해 보관.
     * picker 직후 즉시 Authorized여도 동일.
     */
    private var pendingYouTubeAccount: Account? = null

    private val _errorMessage = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val errorMessage: SharedFlow<String> = _errorMessage.asSharedFlow()

    /**
     * videoUri별 업로드 상태 StateFlow 캐시. [ConcurrentHashMap]으로 보호:
     * [signOut]의 clear()와 [uploadStateFor]의 computeIfAbsent() 간 구조적 경합 방지.
     */
    private val uploadStateCache = ConcurrentHashMap<Uri, StateFlow<YouTubeUploadUiState>>()

    /**
     * Uri 목록 키 → 배치 상태 Map Flow 캐시.
     * Compose for-루프 collect 대신 [uploadStatesFor] 단일 구독용.
     */
    private val uploadStatesForCache =
        ConcurrentHashMap<String, StateFlow<Map<Uri, YouTubeUploadUiState>>>()

    private val emptyUploadStatesFlow: StateFlow<Map<Uri, YouTubeUploadUiState>> =
        MutableStateFlow(emptyMap())

    /** 사용자가 결과 다이얼로그를 닫았는지 Uri별로 추적 — [dismissUploadResult]로 설정, [startUpload] 시 리셋. */
    private val _uploadResultDismissed = MutableStateFlow<Map<Uri, Boolean>>(emptyMap())

    /**
     * Uri별 업로드 세션 세대 — [suppressFinishedWorkIfNeeded]와 [startUpload] 간 레이스 방지.
     * [ConcurrentHashMap]으로 보호: [signOut]의 clear()와 세대 증가 간 경합 방지.
     */
    private val uploadSessionGeneration = ConcurrentHashMap<Uri, Int>()

    /** enqueue 진행 중 Uri — 동시 suspend 호출 중복 방지. */
    private val startingUploadUris: MutableSet<Uri> = ConcurrentHashMap.newKeySet()

    /**
     * [YouTubeApiClient]/YouTube auth gateway raw 메시지(Korean 내부 리터럴)를
     * [youTubeFailureMessageToStringRes]로 로케일에 맞는 문자열로 매핑한다.
     * [com.example.convert2video.ui.screens.options.OptionsViewModel]의
     * `youTubeUserFacingMessage`와 동일 패턴 — 공통 추출 금지.
     */
    private fun youTubeUserFacingMessage(raw: String?): String {
        val (resId, code) = youTubeFailureMessageToStringRes(raw)
        return if (code != null) appString(resId, code) else appString(resId)
    }

    /**
     * SharedPreferences 기준 인증 상태를 StateFlow에 재동기화.
     * Options 화면과 로그인/아웃이 갈라져 있을 때 목록 화면 재진입 시 호출한다.
     */
    fun refreshAuthState() {
        _isAuthorized.value = authManager.isAuthorized
        _channelTitle.value = authManager.cachedChannelTitle
    }

    private fun commitPendingYouTubeAccountName() {
        pendingYouTubeAccount?.name?.takeIf { it.isNotBlank() }?.let { name ->
            authManager.rememberAccountName(name)
        }
        pendingYouTubeAccount = null
    }

    /** ConvertedVideos YouTube Login 버튼 — 계정 선택기만 요청 (auth in-flight 없음). Options [requestYouTubeAccountPick]와 동일 패턴. */
    fun requestYouTubeAccountPick() {
        _youtubeAccountPickerRequest.tryEmit(Unit)
    }

    /**
     * 계정 선택기 결과. null/취소 → 에러 메시지 + pending clear.
     * non-null → pending 보관 후 [requestAuthorization] (AM-1).
     */
    fun onYouTubeAccountPicked(account: Account?) {
        if (account == null || account.name.isBlank()) {
            pendingYouTubeAccount = null
            _errorMessage.tryEmit(appString(R.string.youtube_account_pick_cancelled))
            return
        }
        pendingYouTubeAccount = account
        viewModelScope.launch {
            try {
                when (val outcome = authManager.requestAuthorization(account)) {
                    is AuthorizationOutcome.Authorized -> {
                        commitPendingYouTubeAccountName()
                        onAuthorized(outcome.accessToken)
                    }
                    is AuthorizationOutcome.NeedsConsent -> {
                        val request = IntentSenderRequest.Builder(outcome.pendingIntent.intentSender).build()
                        _authorizationRequest.tryEmit(request)
                    }
                    is AuthorizationOutcome.Failed -> {
                        pendingYouTubeAccount = null
                        _errorMessage.tryEmit(youTubeUserFacingMessage(outcome.message))
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "onYouTubeAccountPicked failed", e)
                pendingYouTubeAccount = null
                _errorMessage.tryEmit(appString(R.string.youtube_auth_incomplete))
            }
        }
    }

    /** [rememberLauncherForActivityResult]의 StartIntentSenderForResult 콜백에서 전달받은 결과 처리. */
    fun onAuthorizationActivityResult(intent: Intent?) {
        if (intent == null) {
            pendingYouTubeAccount = null
            _errorMessage.tryEmit(appString(R.string.youtube_login_cancelled))
            return
        }
        when (val outcome = authManager.resultFromIntent(intent)) {
            is AuthorizationOutcome.Authorized -> {
                commitPendingYouTubeAccountName()
                onAuthorized(outcome.accessToken)
            }
            is AuthorizationOutcome.NeedsConsent -> {
                pendingYouTubeAccount = null
                _errorMessage.tryEmit(appString(R.string.youtube_auth_incomplete))
            }
            is AuthorizationOutcome.Failed -> {
                pendingYouTubeAccount = null
                _errorMessage.tryEmit(youTubeUserFacingMessage(outcome.message))
            }
        }
    }

    private fun onAuthorized(accessToken: String) {
        refreshAuthState()
        _isAuthorized.value = true
        viewModelScope.launch {
            when (val result = apiClient.fetchChannelTitle(accessToken)) {
                is YouTubeApiResult.Success -> {
                    authManager.rememberChannelTitle(result.value)
                    refreshAuthState()
                }
                is YouTubeApiResult.Failure -> _errorMessage.tryEmit(youTubeUserFacingMessage(result.message))
            }
        }
    }

    /** 이미 로그인된 상태에서 채널명만 조용히 새로고침 — 동의 화면 없이 토큰 재발급 후 API 호출. */
    fun refreshChannelTitleIfAuthorized() {
        refreshAuthState()
        if (!authManager.isAuthorized) return
        viewModelScope.launch {
            when (val outcome = authManager.requestAuthorization()) {
                is AuthorizationOutcome.Authorized -> {
                    when (val result = apiClient.fetchChannelTitle(outcome.accessToken)) {
                        is YouTubeApiResult.Success -> {
                            authManager.rememberChannelTitle(result.value)
                            refreshAuthState()
                        }
                        is YouTubeApiResult.Failure -> _errorMessage.tryEmit(youTubeUserFacingMessage(result.message))
                    }
                }
                is AuthorizationOutcome.NeedsConsent, is AuthorizationOutcome.Failed -> Unit
            }
        }
    }

    fun signOut() {
        pendingYouTubeAccount = null
        authManager.signOut()
        refreshAuthState()
        _uploadResultDismissed.value = emptyMap()
        uploadStateCache.clear()
        uploadStatesForCache.clear()
        uploadSessionGeneration.clear()
    }

    /**
     * 화면 재진입 시 호출 — 자정이 지난 뒤 새 업로드가 없어도 카운터 기준이 오늘로 재설정된다.
     * [_todayStartMillis] 갱신 → [flatMapLatest]가 새 [observeCountSince] 구독으로 즉시 전환.
     */
    fun refreshTodayCount() {
        _todayStartMillis.value = todayStartMillis()
    }

    /** 오늘 자정(로컬 시간)의 epoch 밀리초. [todayUploadCount] 집계 기준. */
    private fun todayStartMillis(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * 일괄 업로드 — active work가 있는 항목은 KEEP 정책상 skip.
     * [BatchUploadResult]는 실제 enqueue 성공/스킵 수만 반영한다.
     */
    suspend fun startBatchUpload(
        items: List<YouTubeBatchUploadItem>,
        description: String,
        privacyStatus: String,
    ): BatchUploadResult {
        var startedCount = 0
        var skippedCount = 0
        items.forEach { item ->
            if (enqueueUploadGuarded(item.videoUri, item.title, description, privacyStatus)) {
                startedCount++
            } else {
                skippedCount++
            }
        }
        if (skippedCount > 0) {
            _errorMessage.tryEmit(appString(R.string.youtube_batch_upload_skipped, skippedCount))
        }
        return BatchUploadResult(startedCount = startedCount, skippedCount = skippedCount)
    }

    /**
     * 영상 Uri별로 고유 작업으로 등록 — active work가 있으면 KEEP으로 skip하고 false 반환.
     *
     * Network Constraints는 **enqueue 직전** DataStore `wifiOnlyUpload` 스냅샷만 반영한다.
     * 이미 ENQUEUED/RUNNING인 작업은 Options에서 Wi-Fi 설정을 바꿔도 자동 재적용되지 않는다.
     * 새 설정을 쓰려면 대기 중 업로드를 취소한 뒤 다시 시작해야 한다.
     *
     * @return 실제 enqueue 성공 true, skip/중복 false (스킵 시 [errorMessage] emit)
     */
    suspend fun startUpload(
        videoUri: Uri,
        title: String,
        description: String,
        privacyStatus: String,
    ): Boolean {
        val started = enqueueUploadGuarded(videoUri, title, description, privacyStatus)
        if (!started) {
            _errorMessage.tryEmit(appString(R.string.youtube_upload_skipped_active))
        }
        return started
    }

    private suspend fun enqueueUploadGuarded(
        videoUri: Uri,
        title: String,
        description: String,
        privacyStatus: String,
    ): Boolean {
        if (!startingUploadUris.add(videoUri)) {
            return false
        }
        return try {
            enqueueUpload(videoUri, title, description, privacyStatus)
        } finally {
            startingUploadUris.remove(videoUri)
        }
    }

    private suspend fun enqueueUpload(
        videoUri: Uri,
        title: String,
        description: String,
        privacyStatus: String,
    ): Boolean {
        if (hasActiveUploadWork(videoUri)) {
            return false
        }
        // Cold-start 레이스 방지: enqueue 직전 DataStore 최신값.
        val wifiOnly = settingsRepository.wifiOnlyUpload.first()
        val networkType = networkTypeForWifiOnly(wifiOnly)
        val request = OneTimeWorkRequestBuilder<YouTubeUploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
            .setInputData(
                workDataOf(
                    YouTubeUploadWorker.KEY_VIDEO_URI to videoUri.toString(),
                    YouTubeUploadWorker.KEY_TITLE to title,
                    YouTubeUploadWorker.KEY_DESCRIPTION to description,
                    YouTubeUploadWorker.KEY_PRIVACY_STATUS to privacyStatus,
                ),
            )
            .build()
        uploadSessionGeneration[videoUri] = (uploadSessionGeneration[videoUri] ?: 0) + 1
        _uploadResultDismissed.update { it - videoUri }
        workManager.enqueueUniqueWork(uploadWorkName(videoUri), ExistingWorkPolicy.KEEP, request)
        return true
    }

    /**
     * [ConvertViewModel.conversionState]와 동일한 WorkInfo 관찰 패턴 — WorkManager가 아는 현재 상태를 그대로 반영.
     * [ConcurrentHashMap.computeIfAbsent]로 동일 Uri에 대한 Flow 인스턴스 단일 생성을 보장한다
     * ([getOrPut]은 원자적이지 않아 signOut clear()와 경합 가능).
     */
    fun uploadStateFor(videoUri: Uri): StateFlow<YouTubeUploadUiState> =
        uploadStateCache.computeIfAbsent(videoUri) { uri ->
            suppressFinishedWorkIfNeeded(uri)
            combine(
                workManager.getWorkInfosForUniqueWorkFlow(uploadWorkName(uri)),
                _uploadResultDismissed,
            ) { infos, dismissedMap ->
                if (dismissedMap[uri] == true) {
                    YouTubeUploadUiState.Idle
                } else {
                    infos.firstOrNull()?.toYouTubeUploadUiState(
                        failedFallback = appString(R.string.youtube_upload_failed_fallback),
                    ) ?: YouTubeUploadUiState.Idle
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = YouTubeUploadUiState.Idle,
            )
        }

    /**
     * Uri 목록 전체에 대한 업로드 상태를 **단일** [StateFlow]<[Map]>으로 구독한다.
     * Compose에서는 [collectAsStateWithLifecycle] 1회만 — for 안에서 collect 금지.
     * 개별 [uploadStateFor] 캐시를 재사용하므로 업로드 정책은 동일하다.
     */
    fun uploadStatesFor(uris: List<Uri>): StateFlow<Map<Uri, YouTubeUploadUiState>> {
        if (uris.isEmpty()) return emptyUploadStatesFlow
        val cacheKey = uris.joinToString(separator = "\u0000") { it.toString() }
        return uploadStatesForCache.computeIfAbsent(cacheKey) {
            val flows = uris.map { uploadStateFor(it) }
            combine(flows) { values: Array<YouTubeUploadUiState> ->
                uris.mapIndexed { index, uri -> uri to values[index] }.toMap()
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = uris.associateWith { YouTubeUploadUiState.Idle },
            )
        }
    }

    fun cancelUpload(videoUri: Uri) {
        workManager.cancelUniqueWork(uploadWorkName(videoUri))
    }

    /** 완료/실패 결과 다이얼로그를 닫음 — pruneWork 없이 dismissed 플래그로 Success/Failed를 Idle로 억제. */
    fun dismissUploadResult(videoUri: Uri) {
        _uploadResultDismissed.update { it + (videoUri to true) }
    }

    private fun suppressFinishedWorkIfNeeded(videoUri: Uri) {
        val generationAtCheck = uploadSessionGeneration[videoUri] ?: 0
        viewModelScope.launch {
            val infos = withContext(Dispatchers.IO) {
                runCatching { workManager.getWorkInfosForUniqueWork(uploadWorkName(videoUri)).get() }
                    .getOrDefault(emptyList())
            }
            if (infos.isNotEmpty() && infos.all { it.state.isFinished }) {
                if ((uploadSessionGeneration[videoUri] ?: 0) == generationAtCheck) {
                    _uploadResultDismissed.update { it + (videoUri to true) }
                }
            }
        }
    }

    private suspend fun hasActiveUploadWork(videoUri: Uri): Boolean = withContext(Dispatchers.IO) {
        val infos = runCatching {
            workManager.getWorkInfosForUniqueWork(uploadWorkName(videoUri)).get()
        }.getOrDefault(emptyList())
        infos.any { !it.state.isFinished }
    }

    companion object {
        private const val UPLOAD_WORK_NAME_PREFIX = "youtube_upload_"

        fun uploadWorkName(videoUri: Uri): String =
            uploadWorkNameForUriString(videoUri.toString())

        /** JVM unit test용 — [Uri.parse] stub 환경에서도 naming contract 검증 가능. */
        internal fun uploadWorkNameForUriString(uriString: String): String =
            UPLOAD_WORK_NAME_PREFIX + Uri.encode(uriString)
    }
}

/** wifiOnlyUpload 설정 → WorkManager NetworkType 매핑 (순수 함수). */
internal fun networkTypeForWifiOnly(wifiOnly: Boolean): NetworkType =
    if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

data class YouTubeBatchUploadItem(
    val videoUri: Uri,
    val title: String,
)

data class BatchUploadResult(
    val startedCount: Int,
    val skippedCount: Int,
)

internal fun WorkInfo.toYouTubeUploadUiState(
    failedFallback: String = "",
): YouTubeUploadUiState = when (toWorkInfoUiPhase()) {
    WorkInfoUiPhase.Active ->
        YouTubeUploadUiState.InProgress(progress.getInt(YouTubeUploadWorker.KEY_PROGRESS, 0))
    WorkInfoUiPhase.Succeeded -> {
        val videoId = outputData.getString(YouTubeUploadWorker.KEY_VIDEO_ID)
        val watchUrl = outputData.getString(YouTubeUploadWorker.KEY_WATCH_URL)
        if (videoId != null && watchUrl != null) {
            YouTubeUploadUiState.Success(videoId, watchUrl)
        } else {
            YouTubeUploadUiState.Idle
        }
    }
    WorkInfoUiPhase.Failed ->
        YouTubeUploadUiState.Failed(
            outputData.getString(YouTubeUploadWorker.KEY_MESSAGE) ?: failedFallback,
        )
    WorkInfoUiPhase.Cancelled -> YouTubeUploadUiState.Cancelled
}
