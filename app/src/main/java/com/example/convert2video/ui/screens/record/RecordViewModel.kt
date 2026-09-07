package com.example.convert2video.ui.screens.record

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.convert2video.data.SettingsRepository
import com.example.convert2video.record.RecordingController
import com.example.convert2video.record.RecordingErrorCodes
import com.example.convert2video.record.RecordingFormat
import com.example.convert2video.record.RecordingState
import com.example.convert2video.record.RecordingStorageEstimator
import com.example.convert2video.record.recordingErrorCodeToStringRes
import com.example.convert2video.utils.appString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 녹음 UI 상태 단일 소스 (Compose 없음 — Sprint 2-1).
 * bindService 없이 [RecordingController.getInstance]만 사용한다.
 *
 * ## Sprint 2-3/2-4 책임 표 (진입 동기화 / Toast / Phase 3 seam / clearTerminal)
 * | 책임 | 위치 |
 * |---|---|
     * | 권한 자동요청 1회 | [consumeAutoRequestPermission] (Activity VM 생존) |
 * | launcher `canRequestAgain` SSOT | [onPermissionResult] — ON_RESUME 덮어쓰기는 Screen ignore 윈도우 |
 * | Saved Toast one-shot | [consumeSavedToast] — **path** identity. 재진입·config change 재Toast 금지 |
 * | Saved → [onRecordingSaved] one-shot | [consumeSavedCallback] — **path** identity. Toast와 독립 키 저장소 |
 * | sticky 해제 + Toast/seam 재허용 | [clearTerminalState] |
 * | stop 직후 Saving 게이트 | [isSavingGate] — Stopping 도착 전 MainActivity OR |
 * | Record Again pending seam | [flushPendingSavedCallback] → 그다음 [clearTerminalState] |
 *
 * ## Off-screen Saved (Sprint 2-4 확정)
 * Activity-scoped VM이 Controller sticky Saved를 유지한다. 저장 직후 [RecordScreen] composition이
 * 없으면 seam([consumeSavedCallback])·Toast는 **재진입 composition까지 대기**했다가 **정확히 1회** 발사한다.
 * Phase 3의 재진입 감지·Active recording-aware wiring은 별도 스프린트.
 */
open class RecordViewModel(application: Application) : AndroidViewModel(application) {

    private val controller = RecordingController.getInstance(application)
    private val settingsRepository = SettingsRepository(application)

    /**
     * Process-wide [SettingsRepository.recordingFormatHot] 준비 여부.
     * Options set과 동일 캐시 — WAV 저장 직후 Start도 WAV.
     */
    val isRecordingFormatReady: StateFlow<Boolean> = SettingsRepository.recordingFormatHot
        .map { it != null }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = SettingsRepository.recordingFormatHot.value != null,
        )

    /** 현재 녹음 포맷 hot cache — Idle 화면의 포맷·용량 힌트 표시용(B-6). */
    val recordingFormat: StateFlow<RecordingFormat?> = SettingsRepository.recordingFormatHot

    /**
     * 잔여 녹음 가능 **초** (raw [Long], UI 포맷·l10n은 B-6).
     *
     * Eligibility SSOT는 **Controller** [RecordingState.Idle] + RECORD_AUDIO granted 뿐이다.
     * sticky [RecordingState.Failed] (PERMISSION_DENIED) + hasPermission은 UI상 [RecordUiState.Idle]이어도
     * Controller가 Idle이 아니므로 estimate는 **null** — [clearTerminalState] 또는 [start] 후 Idle 전환 시 재개.
     *
     * - hot format null → null (StatFs/IO 스킵).
     * - availableBytes 0 → 0L.
     * - 비-Idle·PermissionDenied·녹음 중 → **null** (stale 방지).
     *
     * 재계산: eligible Idle 진입 1회 + eligible Idle 유지 중 hot format 변경.
     * IO 완료 후 eligibility 재확인; in-flight Job은 새 트리거·비-eligible 시 cancel.
     */
    private val _estimatedRemainingSeconds = MutableStateFlow<Long?>(null)
    val estimatedRemainingSeconds: StateFlow<Long?> = _estimatedRemainingSeconds.asStateFlow()

    /**
     * Review Keep/Discard in-flight — 버튼 enabled는 이 값의 역.
     * Controller [RecordingController.isReviewActionInFlight] SSOT.
     */
    val isReviewActionInFlight: StateFlow<Boolean> = controller.isReviewActionInFlight

    /** 단일 in-flight StatFs recompute — 레이스로 비-Idle null 덮어쓰기 방지. */
    private var estimateRecomputeJob: Job? = null

    private val permissionState = MutableStateFlow(
        PermissionSnapshot(
            granted = ContextCompat.checkSelfPermission(
                application,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED,
            canRequestAgain = true,
        ),
    )

    /**
     * 자동 권한 요청 1회 플래그 (Activity [viewModelStore]에 생존 — 화면 이탈 후에도 유지).
     * [rememberSaveable]만으로는 destination 전환 시 부족하다.
     */
    private val hasAutoRequestedPermission = MutableStateFlow(false)

    /**
     * [stop] 직후~단말(Idle/Saved/Failed/Review) 전 local Saving 게이트.
     * Controller [RecordingState.Stopping] 도착 레이스를 막아 MainActivity가 OR로 네비게이션을 차단한다.
     * Review에서는 false.
     */
    private val _isSavingGate = MutableStateFlow(false)
    val isSavingGate: StateFlow<Boolean> = _isSavingGate.asStateFlow()

    /** androidTest — stop() 레이스 [isSavingGate] without reflection. */
    @VisibleForTesting
    internal fun setSavingGateForTest(enabled: Boolean) {
        _isSavingGate.value = enabled
    }

    /**
     * 이미 Toast를 띄운 Saved 파일 path. null이면 아직 미표시.
     * [clearTerminalState] 또는 다른 path의 새 Saved에서만 재허용.
     * identity는 [consumeSavedCallback]과 동일하게 **absolute path** (키 저장소는 독립).
     */
    private var toastedSavedFilePath: String? = null

    /**
     * 이미 [onRecordingSaved]를 발사한 Saved 파일 path. null이면 아직 미발사.
     * Toast one-shot([toastedSavedFilePath])과 **독립** — 한쪽이 소비돼도 다른 쪽은 별개.
     * [clearTerminalState] 또는 다른 path의 새 Saved에서만 재허용.
     */
    private var consumedSavedCallbackPath: String? = null

    /** Guards callback/toast one-shot identities across Compose and lifecycle callbacks. */
    private val savedCallbackIdentityLock = Any()

    val uiState: StateFlow<RecordUiState> = combine(
        controller.state,
        permissionState,
    ) { recordingState, permission ->
        toRecordUiState(
            recordingState = recordingState,
            hasPermission = permission.granted,
            canRequestAgain = permission.canRequestAgain,
            errorMessageFor = ::resolveErrorMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = toRecordUiState(
            recordingState = controller.state.value,
            hasPermission = permissionState.value.granted,
            canRequestAgain = permissionState.value.canRequestAgain,
            errorMessageFor = ::resolveErrorMessage,
        ),
    )

    init {
        // DataStore → process-wide hot cache 시드
        viewModelScope.launch {
            settingsRepository.recordingFormat.collect { }
        }
        // Idle/Saved/Failed 도달 시 stop 직후 게이트 해제 (Stopping 중에는 유지)
        viewModelScope.launch {
            controller.state.collect { state ->
                when (state) {
                    RecordingState.Idle,
                    is RecordingState.Saved,
                    is RecordingState.Failed,
                    is RecordingState.Review,
                    -> _isSavingGate.value = false
                    else -> Unit
                }
            }
        }
        viewModelScope.launch {
            var wasEligible = false
            var lastFormatWhileEligible: RecordingFormat? = null
            combine(
                controller.state,
                SettingsRepository.recordingFormatHot,
                permissionState,
            ) { recordingState, format, permission ->
                Triple(recordingState, format, permission.granted)
            }.collect { (recordingState, format, hasPermission) ->
                val isEligible = shouldEstimateRemainingSeconds(recordingState, hasPermission)
                if (!isEligible) {
                    cancelEstimateRecompute()
                    _estimatedRemainingSeconds.value = null
                    wasEligible = false
                    lastFormatWhileEligible = null
                    return@collect
                }
                val justEnteredEligible = !wasEligible
                val formatChangedWhileEligible = wasEligible && format != lastFormatWhileEligible
                wasEligible = true
                lastFormatWhileEligible = format
                if (!shouldRecomputeEstimate(justEnteredEligible, formatChangedWhileEligible)) {
                    return@collect
                }
                if (format == null) {
                    cancelEstimateRecompute()
                    _estimatedRemainingSeconds.value = null
                    return@collect
                }
                scheduleEstimateRecompute(format)
            }
        }
    }

    /**
     * 녹음 시작. 진입 시 [refreshPermission]으로 [permissionState]를 시스템 권한과 동기화한 뒤,
     * granted가 아니면 no-op, granted면 process-wide hot cache로 Controller에 전달한다.
     *
     * 무인자 — [RecordContent.onStart] `() -> Unit` / `viewModel::start` 메서드 레퍼런스와 호환.
     * hot cache 미준비(null)면 no-op — UI는 [isRecordingFormatReady]로 Start 비활성.
     */
    open fun start() {
        refreshPermission()
        if (!permissionState.value.granted) return
        val format = SettingsRepository.recordingFormatHot.value ?: return
        controller.start(format)
    }

    open fun pause() {
        controller.pause()
    }

    open fun resume() {
        controller.resume()
    }

    /**
     * 정지 요청. Controller Stopping 반영 **전에** [isSavingGate]를 세워
     * MainActivity 네비게이션 차단 레이스를 막는다.
     */
    open fun stop() {
        _isSavingGate.value = true
        controller.stop()
    }

    /** Review Keep — insert 후 Saved. Review가 아니면 Controller no-op. */
    open fun keepRecording() {
        controller.keep()
    }

    /** Review Discard — 휴지통 후 Idle. Review가 아니면 Controller no-op. */
    open fun discardRecording() {
        controller.discard()
    }

    fun onPermissionResult(granted: Boolean, canRequestAgain: Boolean) {
        permissionState.value = PermissionSnapshot(
            granted = granted,
            canRequestAgain = canRequestAgain,
        )
    }

    /**
     * 진입 시 자동 권한 요청을 1회만 수행하기 위한 consume.
     *
     * @return `true`면 이번에 launcher를 띄워야 함(플래그 소비). `false`면 이미 요청함.
     */
    fun consumeAutoRequestPermission(): Boolean {
        if (hasAutoRequestedPermission.value) return false
        hasAutoRequestedPermission.value = true
        return true
    }

    /**
     * 시스템 [Manifest.permission.RECORD_AUDIO]를 다시 읽어 [permissionState]를 동기화한다.
     * Activity `onResume` 등 런타임 권한 철회/복구 감지용.
     *
     * @param canRequestAgain `null`(기본)이면 기존 값을 유지하고, non-null이면 교체한다.
     *   권한 다이얼로그 in-flight 중에는 `null`을 넘겨 rationale 오판을 피한다.
     *   shouldShowRequestPermissionRationale 결과는 [onPermissionResult]에서 넘기는 것을 권장.
     */
    fun refreshPermission(canRequestAgain: Boolean? = null) {
        val granted = ContextCompat.checkSelfPermission(
            getApplication(),
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        val previous = permissionState.value
        permissionState.value = PermissionSnapshot(
            granted = granted,
            canRequestAgain = canRequestAgain ?: previous.canRequestAgain,
        )
    }

    /**
     * Saved Toast one-shot. 같은 파일 **path**로는 한 번만 true.
     * 재진입·구성변경에서 sticky Saved가 남아 있어도 재Toast하지 않는다.
     * identity는 [consumeSavedCallback]과 동일(path) — 키 저장소는 독립.
     *
     * @param filePath [java.io.File.getAbsolutePath] (또는 동등한 안정 identity).
     * @return true면 이번에 Toast를 띄워야 함.
     */
    fun consumeSavedToast(filePath: String): Boolean {
        synchronized(savedCallbackIdentityLock) {
            if (toastedSavedFilePath == filePath) return false
            toastedSavedFilePath = filePath
            return true
        }
    }

    /**
     * Phase 3 seam([onRecordingSaved]) one-shot. 같은 파일 path로는 한 번만 true.
     * Toast([consumeSavedToast])와 키·시점이 독립이다.
     * 재진입·구성변경에서 sticky Saved가 남아 있어도 콜백을 재발사하지 않는다.
     *
     * Off-screen: composition이 없으면 재진입까지 대기 후 1회 발사(Activity-scoped VM sticky).
     *
     * @param filePath [java.io.File.getAbsolutePath] (또는 동등한 안정 identity).
     * @return true면 이번에 [onRecordingSaved]를 호출해야 함.
     */
    fun consumeSavedCallback(filePath: String): Boolean {
        synchronized(savedCallbackIdentityLock) {
            if (consumedSavedCallbackPath == filePath) return false
            consumedSavedCallbackPath = filePath
            return true
        }
    }

    /**
     * Record Again 직전 — pending [onRecordingSaved] seam을 1회 flush.
     * LaunchedEffect보다 먼저 clear하면 seam이 유실되므로, [clearTerminalState] **앞에** 호출한다.
     *
     * @return flush된 [File] (이번에 seam을 소비함). 이미 소비됐거나 Saved가 아니면 null.
     */
    fun flushPendingSavedCallback(): File? {
        // uiState는 WhileSubscribed라 구독 없을 때 stale일 수 있음 — Controller sticky Saved SSOT
        val saved = controller.state.value as? RecordingState.Saved ?: return null
        val path = saved.outputFile.absolutePath
        if (!consumeSavedCallback(path)) return null
        return saved.outputFile
    }

    /**
     * Record Again 직전 duration-aware Saved seam을 1회 flush한다.
     * Legacy [flushPendingSavedCallback]과 동일한 path 소비 키를 공유하므로 둘 중 하나만 성공한다.
     */
    fun flushPendingSavedCallbackWithDuration(): RecordUiState.Saved? {
        val saved = controller.state.value as? RecordingState.Saved ?: return null
        val path = saved.outputFile.absolutePath
        if (!consumeSavedCallback(path)) return null
        return RecordUiState.Saved(
            outputFile = saved.outputFile,
            elapsedMs = saved.elapsedMs,
        )
    }

    /**
     * Saved/Failed sticky 해제 — [RecordScreen]의 dismiss / 다시 녹음에서 호출.
     * Toast·[onRecordingSaved] one-shot 키도 리셋해 다음 Saved에서 다시 발사할 수 있게 한다.
     */
    open fun clearTerminalState() {
        synchronized(savedCallbackIdentityLock) {
            toastedSavedFilePath = null
            consumedSavedCallbackPath = null
        }
        _isSavingGate.value = false
        controller.clearTerminalState()
    }

    /**
     * FileProvider seam 실패 전용 — sticky Saved만 제거(좀비 Saved UI 방지).
     * [consumedSavedCallbackPath]는 **유지**해 동일 path로 [onRecordingSaved]가 재발사되지 않게 한다.
     */
    fun discardStickySavedAfterUriFailure() {
        _isSavingGate.value = false
        controller.clearTerminalState()
    }

    /** androidTest — eligible Idle 판정 without reflection. */
    @VisibleForTesting
    internal fun shouldEstimateRemainingSeconds(
        recordingState: RecordingState,
        hasPermission: Boolean,
    ): Boolean = recordingState is RecordingState.Idle && hasPermission

    /** androidTest — recompute 트리거 판정 without reflection. */
    @VisibleForTesting
    internal fun shouldRecomputeEstimate(
        justEnteredEligible: Boolean,
        formatChangedWhileEligible: Boolean,
    ): Boolean = justEnteredEligible || formatChangedWhileEligible

    /** androidTest — StatFs 추정 경로 (IO dispatcher) without reflection. */
    @VisibleForTesting
    internal suspend fun computeEstimatedRemainingSecondsForTest(format: RecordingFormat?): Long? {
        if (format == null) return null
        return computeEstimatedRemainingSeconds(format)
    }

    private fun cancelEstimateRecompute() {
        estimateRecomputeJob?.cancel()
        estimateRecomputeJob = null
    }

    private fun scheduleEstimateRecompute(format: RecordingFormat) {
        cancelEstimateRecompute()
        estimateRecomputeJob = viewModelScope.launch {
            val result = computeEstimatedRemainingSeconds(format)
            if (!shouldEstimateRemainingSeconds(
                    controller.state.value,
                    permissionState.value.granted,
                )
            ) {
                return@launch
            }
            _estimatedRemainingSeconds.value = result
        }
    }

    private suspend fun computeEstimatedRemainingSeconds(format: RecordingFormat): Long {
        return withContext(Dispatchers.IO) {
            val availableBytes = RecordingStorageEstimator.queryAvailableBytes(getApplication())
            RecordingStorageEstimator.estimateRemainingSeconds(availableBytes, format)
        }
    }

    private fun resolveErrorMessage(errorCode: String): String =
        appString(recordingErrorCodeToStringRes(errorCode))

    private data class PermissionSnapshot(
        val granted: Boolean,
        val canRequestAgain: Boolean,
    )
}

/**
 * [RecordingState] + 권한 → [RecordUiState] 순수 매핑 (top-level, JVM 단위 테스트 대상).
 *
 * - 활성 세션(`Recording`/`Paused`/`Stopping`/`Stopped`)은 권한 철회여도 오버레이하지 않는다.
 * - sticky 터미널(`Saved` / `Review` / `Failed` 비권한)도 권한 철회여도 유지한다 (PermissionDenied 오버레이 금지).
 * - `Failed(PERMISSION_DENIED)` + !hasPermission → [RecordUiState.PermissionDenied] (`Error` 아님).
 * - `Failed(PERMISSION_DENIED)` + hasPermission → [RecordUiState.Idle]
 *   (권한 복구 후 stale Failed 폐기). Controller sticky는 [RecordViewModel.clearTerminalState]
 *   또는 다음 [RecordViewModel.start]로 해제하는 것을 권장.
 * - amplitude / elapsedMs 는 가공 없이 전달한다.
 *
 * @param errorMessageFor Failed errorCode → 사용자용 문자열 (리소스/테스트 더블).
 */
internal fun toRecordUiState(
    recordingState: RecordingState,
    hasPermission: Boolean,
    canRequestAgain: Boolean,
    errorMessageFor: (errorCode: String) -> String,
): RecordUiState {
    val isActiveSession = when (recordingState) {
        is RecordingState.Recording,
        is RecordingState.Paused,
        is RecordingState.Stopping,
        is RecordingState.Stopped,
        -> true
        else -> false
    }

    // Saved / Failed(비권한) sticky — 권한없음이어도 PermissionDenied로 덮지 않음.
    val isStickyTerminal = when (recordingState) {
        is RecordingState.Saved,
        is RecordingState.Review,
        -> true
        is RecordingState.Failed ->
            recordingState.errorCode != RecordingErrorCodes.PERMISSION_DENIED
        else -> false
    }

    if (!hasPermission && !isActiveSession && !isStickyTerminal) {
        return RecordUiState.PermissionDenied(canRequestAgain = canRequestAgain)
    }

    return when (recordingState) {
        is RecordingState.Idle -> RecordUiState.Idle
        is RecordingState.Recording -> RecordUiState.Recording(
            elapsedMs = recordingState.elapsedMs,
            amplitude = recordingState.amplitude,
        )
        is RecordingState.Paused -> RecordUiState.Paused(
            elapsedMs = recordingState.elapsedMs,
        )
        is RecordingState.Stopping -> RecordUiState.Saving
        // Engine 전용 — Controller state에는 비노출. 인덱싱 직전으로 Saving 취급.
        is RecordingState.Stopped -> RecordUiState.Saving
        is RecordingState.Saved -> RecordUiState.Saved(
            outputFile = recordingState.outputFile,
            elapsedMs = recordingState.elapsedMs,
        )
        is RecordingState.Review -> RecordUiState.Review(
            outputFile = recordingState.outputFile,
            elapsedMs = recordingState.elapsedMs,
        )
        is RecordingState.Failed -> {
            if (recordingState.errorCode == RecordingErrorCodes.PERMISSION_DENIED) {
                // hasPermission: stale PERMISSION_DENIED → Idle 폐기 (!hasPermission은 위에서 처리)
                RecordUiState.Idle
            } else {
                RecordUiState.Error(
                    message = errorMessageFor(recordingState.errorCode),
                )
            }
        }
    }
}
