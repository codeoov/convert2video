package com.example.convert2video.ui.screens.record

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.convert2video.R
import com.example.convert2video.record.RecordingFormat
import com.example.convert2video.record.RecordingStorageEstimator
import com.example.convert2video.shouldBlockNavigationWhileRecordingSaving
import com.example.convert2video.ui.shared.MediaDurationStyle
import com.example.convert2video.ui.shared.formatApproxSizePerMinute
import com.example.convert2video.ui.shared.formatMediaDurationMs
import com.example.convert2video.ui.components.buttons.AccentCtaButton
import com.example.convert2video.ui.components.buttons.RoundedIconButton
import com.example.convert2video.ui.components.cards.C2vCard
import com.example.convert2video.ui.theme.C2vRadius
import com.example.convert2video.utils.AppLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** MediaRecorder maxAmplitude 상한(0..32767). WaveformBars normalize SSOT. */
private const val MAX_AMPLITUDE = 32_767

/** 파형 롤링 버퍼 길이. 100ms 틱 기준 약 8초. */
private const val WAVEFORM_SAMPLE_COUNT = 80

private const val WAVEFORM_TICK_MS = 100L

/** launcher ActivityResult 직후 ON_RESUME이 canRequestAgain을 덮지 못하게 하는 유예(ms). */
private const val RESUME_CAN_REQUEST_AGAIN_SUPPRESS_MS = 1_000L

private const val TAG = "RecordScreen"

private fun dispatchSavedCallbackSafely(
    file: File,
    durationMs: Long,
    durationCallback: ((File, Long) -> Unit)?,
    legacyCallback: (File) -> Unit,
    onCallbackFailure: () -> Unit,
) {
    runCatching {
        if (durationCallback != null) {
            durationCallback(file, durationMs)
        } else {
            legacyCallback(file)
        }
    }.onFailure { error ->
        AppLogger.e(
            TAG,
            "recording saved callback failed: ${error.javaClass.simpleName}",
            error,
        )
        runCatching { onCallbackFailure() }
            .onFailure { cleanupError ->
                AppLogger.e(
                    TAG,
                    "recording saved callback cleanup failed: ${cleanupError.javaClass.simpleName}",
                    cleanupError,
                )
            }
    }
}

/**
 * Stateful 녹음 화면 (Sprint 2-3 / 2-4 hardening).
 *
 * 권한 launcher·ON_RESUME 동기화·ViewModel 콜백 연결. 녹음 세션은 FGS가 유지하므로
 * onDispose/BackHandler에서 [RecordViewModel.pause]/[RecordViewModel.stop]을 호출하지 않는다.
 *
 * ## Phase 3 seam
 * [onRecordingSaved] 또는 [onRecordingSavedWithDuration]는 Saved 성공 시 **정확히 1회** 전달한다
 * (duration callback이 있으면 legacy callback보다 우선).
 * MainActivity 배선: 성공 시 FileProvider URI → [ConvertViewModel.applyRecordingSaved] → Home/Convert assembly screen ([AppDestination.Home] seam SSOT).
 * 실패 시 Record 잔류 + [RecordViewModel.discardStickySavedAfterUriFailure] + Toast.
 * Active recording-aware 재진입만 Out-of-scope.
 *
 * ## Saved seam 순서 (레이스 방지)
 * 1) [RecordViewModel.consumeSavedCallback]로 path one-shot 소비
 * 2) [onRecordingSaved] ([resolveRecordingSavedSeamAction] 정책)
 * 3) 성공 시 Home dispose / 실패 시 sticky만 제거(seam 키 유지)
 *
 * ## Off-screen Saved (Sprint 2-4 확정)
 * Activity-scoped [RecordViewModel]이 sticky Saved를 유지한다. 저장 직후 이 Screen composition이
 * 없으면 seam은 **재진입까지 대기**했다가 1회 발사한다([RecordViewModel.consumeSavedCallback]).
 *
 * ## Saving 네비게이션 차단 SSOT
 * UI TopBar·BackHandler와 MainActivity Drawer/destination 게이트는 모두
 * [shouldBlockNavigationWhileRecordingSaving] (uiState + [RecordViewModel.isSavingGate])를 쓴다.
 * 시스템 Back 소비는 [RecordContent]에만 둔다 (Stateful에서 중복 등록 금지).
 *
 * ## 화면 이탈 중 녹음 (FGS)
 * Back/드로어로 Home 등 다른 destination으로 가도 [RecordingService] 포그라운드가
 * Recording/Paused/Stopping을 유지한다. 시스템 알림이 진행 인디케이터이며,
 * Home에는 [RecordingController.state] 기반 간단 hint, 드로어 Record 행에는 활성 배지를 둔다.
 * Saving([RecordUiState.Saving] 또는 [RecordViewModel.isSavingGate]) 중 Drawer 열림·선택·destination
 * 변경은 MainActivity 게이트가 차단한다.
 * (알림·hint·배지가 UX 신호 — Screen dispose 시 세션을 끊지 않음)
 */
@Composable
fun RecordScreen(
    onNavigateBack: () -> Unit,
    onOpenDrawer: () -> Unit,
    /**
     * 제품 기본 false — Home Record 탭 임베드(outer Home TopBar만).
     * standalone/레거시 TopBar 검증 fixture만 true.
     */
    showTopBar: Boolean = false,
    modifier: Modifier = Modifier,
    /**
     * Phase 3 seam — Saved.outputFile 성공 전달. 기본 no-op.
     * 성공: contentUriFor → applyRecordingSaved → Home.
     * 실패: Record 잔류 + clearTerminal + Toast (목록/탭 유지).
     * 호출 전 [RecordViewModel.consumeSavedCallback]이 path를 소비한다.
     * Toast one-shot([RecordViewModel.consumeSavedToast])과 독립(path identity, 키 저장소 분리).
     */
    onRecordingSaved: (File) -> Unit = {},
    onRecordingSavedWithDuration: ((File, Long) -> Unit)? = null,
    viewModel: RecordViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isSavingGate by viewModel.isSavingGate.collectAsStateWithLifecycle()
    val isRecordingFormatReady by viewModel.isRecordingFormatReady.collectAsStateWithLifecycle()
    val estimatedRemainingSeconds by viewModel.estimatedRemainingSeconds.collectAsStateWithLifecycle()
    val recordingFormat by viewModel.recordingFormat.collectAsStateWithLifecycle()
    val isReviewActionInFlight by viewModel.isReviewActionInFlight.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context.findActivity()
    val lifecycleOwner = LocalLifecycleOwner.current
    // LaunchedEffect에 콜백을 캡처하지 않음 — 최신 seam은 rememberUpdatedState + snapshotFlow
    val latestOnRecordingSaved by rememberUpdatedState(onRecordingSaved)
    val latestOnRecordingSavedWithDuration by rememberUpdatedState(onRecordingSavedWithDuration)

    // 권한 다이얼로그 in-flight — ON_RESUME에서 rationale 오판(canRequestAgain=false) 방지
    // AtomicBoolean: Lifecycle 콜백에서 Compose Snapshot 밖에서도 최신값 보장
    val permissionRequestInFlight = remember { AtomicBoolean(false) }
    // launcher 결과 직후 ON_RESUME이 canRequestAgain을 덮어쓰는 OEM 레이스 차단 (SSOT=launcher)
    val suppressResumeCanRequestAgainUntilElapsed = remember { AtomicLong(0L) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionRequestInFlight.set(false)
        val canRequestAgain = if (granted) {
            true
        } else {
            // findActivity null → 보수적으로 false (설정 열기 CTA)
            activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
                ?: false
        }
        AppLogger.d(TAG, "permission result granted=$granted canRequestAgain=$canRequestAgain")
        // launcher가 canRequestAgain SSOT — 이어지는 ON_RESUME 덮어쓰기 무시 윈도우
        suppressResumeCanRequestAgainUntilElapsed.set(
            SystemClock.elapsedRealtime() + RESUME_CAN_REQUEST_AGAIN_SUPPRESS_MS,
        )
        viewModel.onPermissionResult(granted = granted, canRequestAgain = canRequestAgain)
    }

    val launchPermissionRequest: () -> Unit = {
        permissionRequestInFlight.set(true)
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // 진입 시: 승인 / ViewModel 1회 자동 요청 / 거부+rationale
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        when {
            granted -> {
                viewModel.onPermissionResult(granted = true, canRequestAgain = true)
            }
            viewModel.consumeAutoRequestPermission() -> {
                AppLogger.d(TAG, "auto-request RECORD_AUDIO once")
                launchPermissionRequest()
            }
            else -> {
                val canRequestAgain = activity
                    ?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
                    ?: false
                viewModel.onPermissionResult(granted = false, canRequestAgain = canRequestAgain)
            }
        }
    }

    // Saved Toast + Phase 3 seam — snapshotFlow로 sticky Saved 관찰.
    // Toast/seam identity는 둘 다 absolute path (키 저장소 독립). Toast 표시 문자열은 name만.
    // Off-screen Saved: composition 없으면 collect 대기 → 재진입 시 1회.
    LaunchedEffect(Unit) {
        snapshotFlow { uiState as? RecordUiState.Saved }
            .collect { saved ->
                val file = saved?.outputFile ?: return@collect
                val path = file.absolutePath
                if (viewModel.consumeSavedToast(path)) {
                    Toast.makeText(context, file.name, Toast.LENGTH_SHORT).show()
                }
                // 콜백 스냅샷을 path 소비보다 먼저 잡아 duration/legacy 중 하나만 발사한다.
                val durationCallback = latestOnRecordingSavedWithDuration
                val legacyCallback = latestOnRecordingSaved
                if (viewModel.consumeSavedCallback(path)) {
                    dispatchSavedCallbackSafely(
                        file = file,
                        durationMs = saved.elapsedMs,
                        durationCallback = durationCallback,
                        legacyCallback = legacyCallback,
                        onCallbackFailure = viewModel::discardStickySavedAfterUriFailure,
                    )
                }
            }
    }

    // Saving TopBar/BackHandler SSOT는 RecordContent — Stateful에서 중복 등록하지 않는다.

    // 설정 복귀 등 — ON_RESUME에서 시스템 권한 재동기화 (pause/stop 호출 금지)
    DisposableEffect(lifecycleOwner, activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val suppressCanRequestAgain = permissionRequestInFlight.get() ||
                    SystemClock.elapsedRealtime() < suppressResumeCanRequestAgainUntilElapsed.get()
                if (suppressCanRequestAgain) {
                    // in-flight 또는 launcher 직후 — granted만 동기화 (canRequestAgain SSOT 유지)
                    AppLogger.d(TAG, "ON_RESUME suppress canRequestAgain: refresh granted only")
                    viewModel.refreshPermission(canRequestAgain = null)
                } else {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    val canRequestAgain = if (granted) {
                        true
                    } else {
                        activity?.shouldShowRequestPermissionRationale(
                            Manifest.permission.RECORD_AUDIO,
                        ) ?: false
                    }
                    AppLogger.d(TAG, "ON_RESUME refreshPermission granted=$granted")
                    viewModel.refreshPermission(canRequestAgain = canRequestAgain)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // 화면을 떠나도 녹음 FGS는 계속 — pause()/stop() 호출 금지
        }
    }

    RecordContent(
        uiState = uiState,
        onStart = viewModel::start,
        onPause = viewModel::pause,
        onResume = viewModel::resume,
        onStop = viewModel::stop,
        onKeep = viewModel::keepRecording,
        onDiscard = viewModel::discardRecording,
        onRequestPermission = launchPermissionRequest,
        onOpenSettings = {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
            runCatching { context.startActivity(intent) }
                .onFailure { e -> AppLogger.e(TAG, "open app settings failed", e) }
        },
        onRecordAgain = {
            // clear 전에 pending seam flush — LaunchedEffect보다 먼저 Record Again 시 유실 방지
            try {
                val durationCallback = latestOnRecordingSavedWithDuration
                if (durationCallback != null) {
                    viewModel.flushPendingSavedCallbackWithDuration()?.let { saved ->
                        dispatchSavedCallbackSafely(
                            file = saved.outputFile,
                            durationMs = saved.elapsedMs,
                            durationCallback = durationCallback,
                            legacyCallback = latestOnRecordingSaved,
                            onCallbackFailure = viewModel::discardStickySavedAfterUriFailure,
                        )
                    }
                } else {
                    viewModel.flushPendingSavedCallback()?.let { legacyFile ->
                        dispatchSavedCallbackSafely(
                            file = legacyFile,
                            durationMs = 0L,
                            durationCallback = null,
                            legacyCallback = latestOnRecordingSaved,
                            onCallbackFailure = viewModel::discardStickySavedAfterUriFailure,
                        )
                    }
                }
            } finally {
                // Callback code belongs to the integration boundary; even a throwing legacy
                // consumer must not leave sticky Saved state or the one-shot gate behind.
                viewModel.clearTerminalState()
            }
        },
        onDismissError = {
            viewModel.clearTerminalState()
        },
        onNavigateBack = onNavigateBack,
        onOpenDrawer = onOpenDrawer,
        isSavingGate = isSavingGate,
        isStartEnabled = isRecordingFormatReady,
        showTopBar = showTopBar,
        storageRemainingSeconds = if (uiState is RecordUiState.Idle) {
            estimatedRemainingSeconds
        } else {
            null
        },
        recordingFormat = if (uiState is RecordUiState.Idle) {
            recordingFormat
        } else {
            null
        },
        reviewActionsEnabled = !isReviewActionInFlight,
        modifier = modifier,
    )
}

/**
 * Stateless 녹음 UI (Sprint 2-2).
 * ViewModel·권한 launcher는 [RecordScreen] stateful wrapper가 담당한다.
 *
 * @param isSavingGate [RecordViewModel.isSavingGate] — stop() 직후 Stopping 도착 전 레이스 가드.
 *   TopBar/BackHandler 차단은 [shouldBlockNavigationWhileRecordingSaving] SSOT.
 * @param isStartEnabled DataStore 녹음 포맷 hot cache 준비 여부 — Idle Start만 비활성(포맷 stale AAC 방지).
 * @param storageRemainingSeconds VM 추정 잔여 녹음 가능 시간(초). [RecordUiState.Idle]일 때만
 *   포맷·표시; Recording/Paused 등 그 외 상태에서는 무시(null 취급).
 * @param recordingFormat 현재 녹음 포맷 hot cache. [RecordUiState.Idle]일 때만 포맷·용량 힌트 줄로
 *   표시; 그 외 상태에서는 무시(null 취급) — [storageRemainingSeconds]와 동일 조건.
 * @param reviewActionsEnabled Review Keep/Discard 버튼. Keep/Discard in-flight면 false.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordContent(
    uiState: RecordUiState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onKeep: () -> Unit = {},
    onDiscard: () -> Unit = {},
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onRecordAgain: () -> Unit,
    onDismissError: () -> Unit,
    onNavigateBack: () -> Unit,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
    isSavingGate: Boolean = false,
    /** 기본 false — 호출부가 포맷 hot cache 준비 여부를 명시 전달해야 한다. */
    isStartEnabled: Boolean = false,
    /**
     * 제품 기본 false — Home 임베드. standalone TopBar 경로(테스트 fixture)만 true.
     */
    showTopBar: Boolean = false,
    /** VM 추정 잔여 녹음 초. Idle일 때만 포맷·표시 — 상세는 KDoc [storageRemainingSeconds]. */
    storageRemainingSeconds: Long? = null,
    /** 현재 녹음 포맷. Idle일 때만 포맷·표시 — 상세는 KDoc [recordingFormat]. */
    recordingFormat: RecordingFormat? = null,
    /** Review Keep/Discard. in-flight면 false. */
    reviewActionsEnabled: Boolean = true,
) {
    val cdNavigateBack = stringResource(R.string.cd_navigate_back)
    val cdOpenDrawer = stringResource(R.string.cd_open_drawer)
    val cdLevelMeter = stringResource(R.string.cd_record_level_meter)
    val titleText = stringResource(R.string.record_screen_title)
    val cdRecordStart = stringResource(R.string.cd_record_start)
    val cdRecordPause = stringResource(R.string.cd_record_pause)
    val cdRecordResume = stringResource(R.string.cd_record_resume)
    val cdRecordStop = stringResource(R.string.cd_record_stop)
    val cdRecordSaving = stringResource(R.string.cd_record_saving)
    val recordAgainLabel = stringResource(R.string.record_again)
    val reviewKeepLabel = stringResource(R.string.record_review_keep)
    val reviewDiscardLabel = stringResource(R.string.record_review_discard)
    val cdReviewKeep = stringResource(R.string.cd_record_review_keep)
    val cdReviewDiscard = stringResource(R.string.cd_record_review_discard)
    val reviewMessage = stringResource(R.string.record_review_message)
    val permissionDeniedMessage = stringResource(R.string.recording_permission_denied)
    val permissionRequestLabel = stringResource(R.string.record_permission_action_request)
    val permissionSettingsLabel = stringResource(R.string.record_permission_action_settings)
    val retryLabel = stringResource(R.string.record_retry)
    val savedMessage = stringResource(R.string.record_saved_message)
    val savedFileNameForCd = (uiState as? RecordUiState.Saved)?.outputFile?.name
    val savedFileCd = savedFileNameForCd?.let { name ->
        stringResource(R.string.record_saved_file_cd, name)
    }
    val storageRemainingText = if (uiState is RecordUiState.Idle) {
        val seconds = storageRemainingSeconds
        val formatted = remember(seconds) {
            seconds?.let { formatMediaDurationMs(it * 1_000L, MediaDurationStyle.Timer) }
        }
        formatted?.let { stringResource(R.string.record_storage_remaining, it) }
    } else {
        null
    }
    val aacFormatLabel = stringResource(R.string.options_recording_format_aac)
    val wavFormatLabel = stringResource(R.string.options_recording_format_wav)
    val formatSizeHintText = if (uiState is RecordUiState.Idle) {
        recordingFormat?.let { format ->
            val formatLabel = when (format) {
                RecordingFormat.AAC -> aacFormatLabel
                RecordingFormat.WAV -> wavFormatLabel
            }
            val sizeText = remember(format) {
                formatApproxSizePerMinute(RecordingStorageEstimator.approxBytesPerMinute(format))
            }
            stringResource(R.string.record_format_size_hint, formatLabel, sizeText)
        }
    } else {
        null
    }

    // MainActivity Drawer/destination 게이트와 동일 SSOT
    val navigationBlocked = shouldBlockNavigationWhileRecordingSaving(
        recordUiState = uiState,
        isSavingGate = isSavingGate,
    )

    // Saving BackHandler SSOT — 여기(RecordContent)에만 등록.
    // Stateful RecordScreen·MainActivity 전역 Back보다 중첩 handler가 우선 소비한다.
    // TopBar enabled=false · MainActivity Drawer/destination 게이트와 쌍.
    BackHandler(enabled = navigationBlocked) {
        // no-op — Saving 중 destination 이탈 금지 (pause/stop 호출 금지)
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (showTopBar) {
                TopAppBar(
                    title = { Text(titleText, style = MaterialTheme.typography.titleLarge) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                    navigationIcon = {
                        RoundedIconButton(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = cdNavigateBack,
                            onClick = onNavigateBack,
                            enabled = !navigationBlocked,
                            modifier = Modifier
                                .padding(start = 20.dp)
                                .testTag("navigate_back_button"),
                        )
                    },
                    actions = {
                        // 목록 CTA 제거 — Listen은 Home 탭 전환 (죽은 destination 없음)
                        RoundedIconButton(
                            icon = Icons.Filled.Menu,
                            contentDescription = cdOpenDrawer,
                            onClick = onOpenDrawer,
                            enabled = !navigationBlocked,
                            modifier = Modifier
                                .padding(end = 20.dp)
                                .testTag("open_drawer_button"),
                        )
                    },
                )
            }
        },
    ) { innerPadding ->
        when (val state = uiState) {
            is RecordUiState.Idle -> {
                RecordSessionBody(
                    elapsedMs = 0L,
                    amplitude = 0,
                    isPaused = false,
                    showStop = false,
                    showLevelMeter = false,
                    recordContentDescription = cdRecordStart,
                    stopContentDescription = cdRecordStop,
                    levelMeterContentDescription = cdLevelMeter,
                    onRecordClick = onStart,
                    onStopClick = onStop,
                    recordButtonEnabled = isStartEnabled,
                    storageRemainingText = storageRemainingText,
                    formatSizeHintText = formatSizeHintText,
                    modifier = Modifier.padding(innerPadding),
                )
            }

            is RecordUiState.PermissionDenied -> {
                Column(
                    modifier = Modifier
                        .padding(innerPadding)
                        .fillMaxSize()
                        .padding(horizontal = 20.dp)
                        .testTag("record_permission_denied_state"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = permissionDeniedMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    val actionLabel =
                        if (state.canRequestAgain) permissionRequestLabel else permissionSettingsLabel
                    val onAction =
                        if (state.canRequestAgain) onRequestPermission else onOpenSettings
                    Button(
                        onClick = onAction,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .testTag("record_permission_action_button")
                            .semantics {
                                contentDescription = actionLabel
                                role = Role.Button
                            },
                    ) {
                        Text(actionLabel)
                    }
                }
            }

            is RecordUiState.Recording, is RecordUiState.Paused -> {
                val isPaused = state is RecordUiState.Paused
                val elapsedMs = when (state) {
                    is RecordUiState.Recording -> state.elapsedMs
                    is RecordUiState.Paused -> state.elapsedMs
                    else -> 0L
                }
                val amplitude = when (state) {
                    is RecordUiState.Recording -> state.amplitude
                    else -> 0
                }
                RecordSessionBody(
                    elapsedMs = elapsedMs,
                    amplitude = amplitude,
                    isPaused = isPaused,
                    showStop = true,
                    showLevelMeter = true,
                    recordContentDescription = if (isPaused) cdRecordResume else cdRecordPause,
                    stopContentDescription = cdRecordStop,
                    levelMeterContentDescription = cdLevelMeter,
                    onRecordClick = if (isPaused) onResume else onPause,
                    onStopClick = onStop,
                    modifier = Modifier.padding(innerPadding),
                )
            }

            RecordUiState.Saving -> {
                Box(
                    modifier = Modifier
                        .padding(innerPadding)
                        .fillMaxSize()
                        .testTag("record_saving_state"),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics {
                            contentDescription = cdRecordSaving
                        },
                    )
                }
            }

            is RecordUiState.Review -> {
                Column(
                    modifier = Modifier
                        .padding(innerPadding)
                        .fillMaxSize()
                        .padding(horizontal = 20.dp)
                        .testTag("record_review_state"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    C2vCard(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(20.dp),
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = reviewMessage,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = state.outputFile.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("record_review_file_name"),
                            )
                            ElapsedTimeText(elapsedMs = state.elapsedMs)
                            AccentCtaButton(
                                text = reviewKeepLabel,
                                onClick = onKeep,
                                enabled = reviewActionsEnabled,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("record_review_keep_button")
                                    .semantics {
                                        contentDescription = cdReviewKeep
                                    },
                            )
                            Button(
                                onClick = onDiscard,
                                enabled = reviewActionsEnabled,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("record_review_discard_button")
                                    .semantics {
                                        contentDescription = cdReviewDiscard
                                    },
                            ) {
                                Text(reviewDiscardLabel)
                            }
                        }
                    }
                }
            }

            is RecordUiState.Saved -> {
                // Sprint 2-3: outputFile.name만 표시 (절대 경로 금지). Toast는 RecordScreen.
                Column(
                    modifier = Modifier
                        .padding(innerPadding)
                        .fillMaxSize()
                        .padding(horizontal = 20.dp)
                        .testTag("record_saved_state"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    C2vCard(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(20.dp),
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = savedMessage,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = state.outputFile.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .testTag("record_saved_file_name")
                                    .semantics {
                                        if (savedFileCd != null) {
                                            contentDescription = savedFileCd
                                        }
                                    },
                            )
                            ElapsedTimeText(elapsedMs = state.elapsedMs)
                            AccentCtaButton(
                                text = recordAgainLabel,
                                onClick = onRecordAgain,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("record_record_again_button"),
                            )
                        }
                    }
                }
            }

            is RecordUiState.Error -> {
                Column(
                    modifier = Modifier
                        .padding(innerPadding)
                        .fillMaxSize()
                        .padding(horizontal = 20.dp)
                        .testTag("record_error_state"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(
                        onClick = onDismissError,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .testTag("record_retry_button"),
                    ) {
                        Text(retryLabel)
                    }
                }
            }
        }
    }
}

/**
 * Idle/Recording/Paused 공통 녹음 세션 본문.
 *
 * @param storageRemainingText Idle 전용 잔여 저장 공간 안내. non-null이면 [ElapsedTimeText] 아래에
 *   `testTag("record_storage_remaining_text")`로 표시.
 * @param formatSizeHintText Idle 전용 포맷·용량 안내. non-null이면 [storageRemainingText] 바로
 *   아래에 `testTag("record_format_size_hint_text")`로 표시.
 */
@Composable
private fun RecordSessionBody(
    elapsedMs: Long,
    amplitude: Int,
    isPaused: Boolean,
    showStop: Boolean,
    showLevelMeter: Boolean,
    recordContentDescription: String,
    stopContentDescription: String,
    levelMeterContentDescription: String,
    onRecordClick: () -> Unit,
    onStopClick: () -> Unit,
    modifier: Modifier = Modifier,
    recordButtonEnabled: Boolean = true,
    storageRemainingText: String? = null,
    formatSizeHintText: String? = null,
) {
    val waveformSamples = remember { mutableStateListOf<Int>() }
    val latestAmplitude by rememberUpdatedState(amplitude)
    val latestPaused by rememberUpdatedState(isPaused)
    LaunchedEffect(showLevelMeter) {
        if (!showLevelMeter) {
            waveformSamples.clear()
            return@LaunchedEffect
        }
        while (isActive) {
            if (!latestPaused) {
                waveformSamples.add(latestAmplitude)
                while (waveformSamples.size > WAVEFORM_SAMPLE_COUNT) {
                    waveformSamples.removeAt(0)
                }
            }
            delay(WAVEFORM_TICK_MS)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ElapsedTimeText(elapsedMs = elapsedMs)
        if (storageRemainingText != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = storageRemainingText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("record_storage_remaining_text"),
            )
        }
        if (formatSizeHintText != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = formatSizeHintText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("record_format_size_hint_text"),
            )
        }
        if (showLevelMeter) {
            Spacer(modifier = Modifier.height(24.dp))
            WaveformBars(
                samples = waveformSamples,
                dimmed = isPaused,
                contentDescription = levelMeterContentDescription,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("record_level_meter"),
            )
        }
        Spacer(modifier = Modifier.height(36.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            CircularRecordButton(
                mode = when {
                    isPaused -> CircularRecordMode.Paused
                    showStop -> CircularRecordMode.Recording
                    else -> CircularRecordMode.Idle
                },
                contentDescription = recordContentDescription,
                onClick = onRecordClick,
                enabled = recordButtonEnabled,
                modifier = Modifier.testTag("record_button"),
            )
            if (showStop) {
                StopSquareButton(
                    contentDescription = stopContentDescription,
                    onClick = onStopClick,
                    modifier = Modifier.testTag("record_stop_button"),
                )
            }
        }
    }
}

private enum class CircularRecordMode {
    Idle,
    Recording,
    Paused,
}

/** 120dp CircleShape 녹음 토글 버튼. [AccentGlyphBadge]와 별개. */
@Composable
private fun CircularRecordButton(
    mode: CircularRecordMode,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val containerColor = when (mode) {
        CircularRecordMode.Idle -> MaterialTheme.colorScheme.primary
        CircularRecordMode.Recording -> MaterialTheme.colorScheme.error
        CircularRecordMode.Paused -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (mode) {
        CircularRecordMode.Idle -> MaterialTheme.colorScheme.onPrimary
        CircularRecordMode.Recording -> MaterialTheme.colorScheme.onError
        CircularRecordMode.Paused -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .size(120.dp)
            .clip(CircleShape)
            .background(containerColor)
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
                this.role = Role.Button
            }
            .clickable(enabled = enabled, onClick = onClick, role = Role.Button),
        contentAlignment = Alignment.Center,
    ) {
        when (mode) {
            CircularRecordMode.Idle -> MicGlyph(tint = contentColor)
            CircularRecordMode.Recording -> PauseGlyph(tint = contentColor)
            CircularRecordMode.Paused -> Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(48.dp),
            )
        }
    }
}

@Composable
private fun StopSquareButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(56.dp)
            .clip(RoundedCornerShape(C2vRadius.chip))
            .background(MaterialTheme.colorScheme.onSurface)
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
                this.role = Role.Button
            }
            .clickable(enabled = enabled, onClick = onClick, role = Role.Button),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surface),
        )
    }
}

@Composable
private fun WaveformBars(
    samples: List<Int>,
    dimmed: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val barColor = MaterialTheme.colorScheme.error
    Canvas(
        modifier = modifier
            .height(96.dp)
            .alpha(if (dimmed) 0.4f else 1f)
            .semantics { this.contentDescription = contentDescription },
    ) {
        val count = WAVEFORM_SAMPLE_COUNT
        if (count <= 0) return@Canvas
        val gapPx = 2.dp.toPx()
        val barWidth = ((size.width - gapPx * (count - 1)) / count).coerceAtLeast(1f)
        val minHeight = 3.dp.toPx()
        val maxHeight = size.height
        samples.forEachIndexed { index, raw ->
            if (index >= count) return@forEachIndexed
            val fraction = normalizeAmplitude(raw)
            val barHeight = minHeight + (maxHeight - minHeight) * fraction
            val x = index * (barWidth + gapPx)
            val y = (maxHeight - barHeight) / 2f
            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

@Composable
private fun ElapsedTimeText(
    elapsedMs: Long,
    modifier: Modifier = Modifier,
) {
    Text(
        text = formatMediaDurationMs(elapsedMs, MediaDurationStyle.Timer),
        style = MaterialTheme.typography.displaySmall,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.testTag("record_timer_text"),
    )
}

@Composable
private fun MicGlyph(tint: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .width(18.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(tint),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .width(28.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(tint),
        )
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(8.dp)
                .background(tint),
        )
    }
}

@Composable
private fun PauseGlyph(tint: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .width(8.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(tint),
        )
        Box(
            modifier = Modifier
                .width(8.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(tint),
        )
    }
}

/** MediaRecorder maxAmplitude 기준(0..[MAX_AMPLITUDE]) → 0f..1f. */
private fun normalizeAmplitude(raw: Int): Float =
    (raw / MAX_AMPLITUDE.toFloat()).coerceIn(0f, 1f)

/** Context → Activity (shouldShowRequestPermissionRationale용). */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
