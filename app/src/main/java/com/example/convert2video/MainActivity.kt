package com.example.convert2video

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.convert2video.R
import com.example.convert2video.analytics.AnalyticsReporter
import com.example.convert2video.data.LanguageOption
import com.example.convert2video.data.RecordingRepository
import com.example.convert2video.data.RecordingBackupPromptPreferences
import com.example.convert2video.data.SettingsRepository
import com.example.convert2video.data.ThemeMode
import com.example.convert2video.desktopsync.DesktopSyncController
import com.example.convert2video.desktopsync.PairingRequest
import com.example.convert2video.record.ExactAlarmPermission
import com.example.convert2video.record.RecordingBackupFolder
import com.example.convert2video.record.RecordingController
import com.example.convert2video.record.RecordingState
import com.example.convert2video.record.isActiveRecordingSession
import com.example.convert2video.ui.screens.audio_pick.AudioPickScreen
import com.example.convert2video.ui.shared.AppDrawerContent
import com.example.convert2video.ui.shared.ConversionUiState
import com.example.convert2video.ui.screens.background_pick.BackgroundPickScreen
import com.example.convert2video.ui.screens.converted_videos.ConvertedVideosViewModel
import com.example.convert2video.ui.screens.youtube_upload.YouTubeUploadViewModel
import com.example.convert2video.ui.screens.error_log.ErrorLogScreen
import com.example.convert2video.ui.screens.convert.ConvertScreen
import com.example.convert2video.ui.screens.convert.ConvertViewModel
import com.example.convert2video.ui.screens.home.HomeScreen
import com.example.convert2video.ui.screens.home.HomeTab
import com.example.convert2video.ui.screens.language_pick.LanguagePickerScreen
import com.example.convert2video.ui.screens.language_pick.LanguagePromptUi
import com.example.convert2video.ui.screens.language_pick.resolveLanguagePromptGate
import com.example.convert2video.ui.screens.microphone_source.MicrophoneSourceScreen
import com.example.convert2video.ui.screens.options.OptionsScreen
import com.example.convert2video.store.StoreCapabilities
import com.example.convert2video.ui.screens.options.OptionsViewModel
import com.example.convert2video.ui.screens.record.RecordUiState
import com.example.convert2video.ui.screens.record.RecordViewModel
import com.example.convert2video.ui.screens.recordings_list.RecordingsListViewModel
import com.example.convert2video.ui.screens.trash.TrashScreen
import com.example.convert2video.ui.theme.Convert2videoTheme
import com.example.convert2video.utils.AppLogger
import com.example.convert2video.utils.requireApplication
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Context → Activity. LanguagePicker first-launch = recreate.
 * Options language = restartApp 게이트(null/finishing/destroyed → canRestart=false).
 */
internal fun Context.findActivityOrNull(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

enum class AppDestination { Home, BackgroundPick, AudioPick, Options, ErrorLog, Convert, Trash, MicrophoneSource }

/**
 * [rememberSaveable] 복원용 — enum 스키마 변경 후 알 수 없는 saved name
 * (예: 구버전 Record / RecordingsList / ConvertedVideos) → [AppDestination.Home].
 */
internal fun restoreAppDestination(savedName: String): AppDestination =
    AppDestination.entries.find { it.name == savedName } ?: AppDestination.Home

/**
 * 구버전 top-level `ConvertedVideos` destination rememberSaveable name → Home 탭 보정.
 * [restoreAppDestination]은 [AppDestination.Home]만 반환; 탭은 [AppDestinationSaver.restore] 경유 1회 적용.
 */
internal fun restoreLegacyHomeTabFromSavedDestination(savedDestinationName: String): HomeTab? =
    if (savedDestinationName == "ConvertedVideos") HomeTab.ConvertedVideos else null

private object NavigationRestoreHolder {
    var pendingLegacyHomeTab: HomeTab? = null
}

internal val AppDestinationSaver: Saver<AppDestination, String> = Saver(
    save = { it.name },
    restore = { savedName ->
        NavigationRestoreHolder.pendingLegacyHomeTab =
            restoreLegacyHomeTabFromSavedDestination(savedName)
        restoreAppDestination(savedName)
    },
)

internal const val HOME_TAB_HISTORY_MAX = 8

private const val RECORDING_BACKUP_PROMPT_READ_MAX_ATTEMPTS = 2
private const val RECORDING_BACKUP_PROMPT_RETRY_DELAY_MILLIS = 100L

/**
 * 연속 중복 제거 + [HOME_TAB_HISTORY_MAX] 상한(앞에서 drop).
 * push·Saver.restore 동일 정규화 SSOT.
 */
internal fun normalizeHomeTabHistory(stack: List<HomeTab>): List<HomeTab> {
    if (stack.isEmpty()) return stack
    val collapsed = ArrayList<HomeTab>(minOf(stack.size, HOME_TAB_HISTORY_MAX))
    for (tab in stack) {
        if (collapsed.lastOrNull() != tab) {
            collapsed.add(tab)
        }
    }
    return if (collapsed.size <= HOME_TAB_HISTORY_MAX) {
        collapsed
    } else {
        collapsed.takeLast(HOME_TAB_HISTORY_MAX)
    }
}

/**
 * Home 3탭 방문 히스토리 [rememberSaveable] 복원. 알 수 없는 name은 건너뛴다.
 * restore 후 push와 동일 정규화(연속 중복 제거 + cap).
 */
internal val HomeTabHistorySaver: Saver<List<HomeTab>, ArrayList<String>> = Saver(
    save = { tabs -> ArrayList(tabs.map { it.name }) },
    restore = { names ->
        val tabs = names.mapNotNull { name -> HomeTab.entries.find { it.name == name } }
        normalizeHomeTabHistory(tabs)
    },
)

internal data class HomeTabHistoryPop(
    val stack: List<HomeTab>,
    val previous: HomeTab,
)

/** 연속 중복 skip + [HOME_TAB_HISTORY_MAX] 상한(앞에서 drop). */
internal fun pushHomeTabHistory(
    stack: List<HomeTab>,
    current: HomeTab,
): List<HomeTab> {
    if (stack.lastOrNull() == current) return normalizeHomeTabHistory(stack)
    return normalizeHomeTabHistory(stack + current)
}

/** empty → null. non-empty → dropLast + previous. */
internal fun popHomeTabHistory(stack: List<HomeTab>): HomeTabHistoryPop? {
    val previous = stack.lastOrNull() ?: return null
    return HomeTabHistoryPop(stack = stack.dropLast(1), previous = previous)
}

/**
 * Saving 중 Drawer 제스처·destination·전역 Back 차단 여부 (SSOT).
 *
 * - [RecordUiState.Saving] (Controller Stopping/Stopped 매핑)
 * - 또는 [RecordViewModel.isSavingGate] (stop() 직후 Stopping 도착 전 레이스 가드)
 *
 * MainActivity 네비게이션 게이트 — UI TopBar enabled=false·RecordContent BackHandler와 쌍.
 */
internal fun shouldBlockNavigationWhileRecordingSaving(
    recordUiState: RecordUiState,
    isSavingGate: Boolean,
): Boolean = isSavingGate || recordUiState is RecordUiState.Saving

/**
 * App Shortcut extra 파서 SSOT.
 * shortcuts.xml `<extra android:value="true" />` 는 String이고, 테스트/코드는 Boolean도 넣을 수 있다.
 * String `"true"`(ignoreCase)를 먼저 보고, Boolean `true`는 폴백. missing / `"false"` → false.
 */
internal fun Intent.isQuickRecordRequested(): Boolean {
    if (getStringExtra(MainActivity.EXTRA_QUICK_RECORD).equals("true", ignoreCase = true)) return true
    return getBooleanExtra(MainActivity.EXTRA_QUICK_RECORD, false)
}

/** Consume 시 extra 제거 SSOT. missing이면 no-op. */
internal fun Intent.stripQuickRecordExtra() {
    if (hasExtra(MainActivity.EXTRA_QUICK_RECORD)) {
        removeExtra(MainActivity.EXTRA_QUICK_RECORD)
    }
}

/**
 * 변환 중·결과 다이얼로그 표시 중이면 Quick Record start 금지.
 * [ConversionUiState.InProgress] / [ConversionUiState.Success] / [ConversionUiState.Failed]
 * (dismiss 전). Success·Failed 순간 자동 start 방지 — dismiss 후 Idle이면 재시도.
 */
internal fun isConversionBlockingQuickRecord(state: ConversionUiState): Boolean =
    state is ConversionUiState.InProgress ||
        state is ConversionUiState.Success ||
        state is ConversionUiState.Failed

/**
 * Quick Record consume 가드 SSOT.
 * true면 destination 변경·[RecordViewModel.start] 금지. pending/extra는 유지하고 가드 해제 시 재시도.
 *
 * - [isRecordingSaving]: Saving 게이트
 * - [isActiveSession]: [isActiveRecordingSession]
 * - [isSavedSticky]: [RecordUiState.Saved]
 * - [isConversionBlocking]: [isConversionBlockingQuickRecord] (InProgress / Success / Failed dismiss 전)
 * - [isReviewPending]: [RecordUiState.Review] (Keep/Discard 전 start 금지)
 */
internal fun shouldDeferQuickRecord(
    isRecordingSaving: Boolean,
    isActiveSession: Boolean,
    isSavedSticky: Boolean,
    isConversionBlocking: Boolean,
    isReviewPending: Boolean,
): Boolean = isRecordingSaving ||
    isActiveSession ||
    isSavedSticky ||
    isConversionBlocking ||
    isReviewPending

/**
 * `start()` 이후 Quick Record consume 수락 SSOT.
 * [RecordingState.Failed]만 거부. Idle/Recording 등 그 외는 수락.
 * [isActiveRecordingSession]은 defer 가드 전용 — 여기 쓰지 않음.
 */
internal fun shouldConsumeAfterQuickStart(after: RecordingState): Boolean =
    after !is RecordingState.Failed

/**
 * ModalNavigationDrawer [gesturesEnabled] 계산.
 * Saving 중에는 destination·드로어 활성 여부와 무관하게 제스처를 끈다.
 * Listen·변환 결과 선택 모드도 제스처를 끈다.
 * 엣지 스와이프로 여는 것만 차단 — [isDrawerActive]가 true이면 닫기 제스처는 유지.
 */
internal fun isDrawerGesturesEnabled(
    isDrawerActive: Boolean,
    isRecordingSaving: Boolean,
    isListenSelectionActive: Boolean = false,
    isConvertedVideosSelectionActive: Boolean = false,
): Boolean = !isRecordingSaving &&
    !isListenSelectionActive &&
    !isConvertedVideosSelectionActive &&
    isDrawerActive

/**
 * Drawer [AppDrawerContent.selected] 매핑 — 드로어에 표시되는 destination만 반환.
 */
internal fun resolveDrawerSelected(currentDestination: AppDestination): AppDestination? =
    when (currentDestination) {
        AppDestination.Home -> AppDestination.Home
        AppDestination.Options -> AppDestination.Options
        AppDestination.Trash -> AppDestination.Trash
        AppDestination.ErrorLog -> AppDestination.ErrorLog
        AppDestination.MicrophoneSource -> AppDestination.MicrophoneSource
        else -> null
    }

/**
 * [onRecordingSaved] URI fold 정책 (순수 SSOT — VM/Activity 회귀 테스트용).
 *
 * - 성공: 오디오 적용 + Home 이동. terminal은 다음 Record Again/새 녹음에서 clear.
 * - 실패: **Record 잔류** + **discardStickySaved**(좀비 Saved 방지, seam 키 유지) + 목록/탭 미변경 + Toast.
 */
internal data class RecordingSavedSeamAction(
    val applyAudioToHome: Boolean,
    val navigateHome: Boolean,
    /** true면 [RecordViewModel.discardStickySavedAfterUriFailure] 호출 */
    val clearRecordTerminal: Boolean,
    val showUriFailedFeedback: Boolean,
)

internal fun resolveRecordingSavedSeamAction(uriSucceeded: Boolean): RecordingSavedSeamAction =
    if (uriSucceeded) {
        RecordingSavedSeamAction(
            applyAudioToHome = true,
            navigateHome = true,
            clearRecordTerminal = false,
            showUriFailedFeedback = false,
        )
    } else {
        RecordingSavedSeamAction(
            applyAudioToHome = false,
            navigateHome = false,
            clearRecordTerminal = true,
            showUriFailedFeedback = true,
        )
    }

/**
 * Prompt candidate types are explicit: only [RecordingBackupPromptCandidate.SuccessfulKeepCallback]
 * can show the prompt, and it is supplied by the existing successful URI branch of
 * [onRecordingSaved]. A stored URI is connected only when its persisted read/write SAF grant is
 * valid, matching Options. The Options CTA navigates to [AppDestination.Options], where the
 * existing backup card/picker is available; this prompt never launches a picker.
 */
internal sealed interface RecordingBackupPromptCandidate {
    data object SuccessfulKeepCallback : RecordingBackupPromptCandidate
    data object UriUnavailable : RecordingBackupPromptCandidate
    data object KeepFailed : RecordingBackupPromptCandidate
    data object Discarded : RecordingBackupPromptCandidate
}

internal fun shouldShowRecordingBackupPrompt(
    candidate: RecordingBackupPromptCandidate,
    isBackupFolderConnected: Boolean,
    isPromptHandled: Boolean,
): Boolean = candidate is RecordingBackupPromptCandidate.SuccessfulKeepCallback &&
    !isBackupFolderConnected &&
    !isPromptHandled

internal enum class RecordingBackupPromptPersistence {
    Failed,
    Succeeded,
}

internal data class RecordingBackupPromptActionResult(
    val closePrompt: Boolean,
    val navigateToOptions: Boolean,
)

/**
 * The dialog may close or navigate only after the handled flag persistence succeeds.
 * Options means the existing Options screen and its already-built backup card/picker;
 * this result never represents an immediate picker launch.
 */
internal fun resolveRecordingBackupPromptActionResult(
    openOptions: Boolean,
    persistence: RecordingBackupPromptPersistence,
): RecordingBackupPromptActionResult =
    if (persistence == RecordingBackupPromptPersistence.Succeeded) {
        RecordingBackupPromptActionResult(
            closePrompt = true,
            navigateToOptions = openOptions,
        )
    } else {
        RecordingBackupPromptActionResult(
            closePrompt = false,
            navigateToOptions = false,
        )
    }

/** Dismiss keeps the current destination; Options opens the existing backup entry point. */
internal fun resolveRecordingBackupPromptDestination(openOptions: Boolean): AppDestination? =
    if (openOptions) AppDestination.Options else null

/**
 * Home/Convert assembly screen — 동일 [ConvertScreen]·공유 [ConvertViewModel].
 * [AppDestination.Home]은 기본 랜딩·seam 복귀 SSOT; [AppDestination.Convert]는 Listen/AudioPick 등 조립 진입.
 */
@Composable
private fun ConvertAssemblyScreen(
    convertViewModel: ConvertViewModel,
    isRecordingActive: Boolean,
    onNavigateBack: () -> Unit,
    onNavigateToBackgroundPick: () -> Unit,
    onNavigateToAudioPick: () -> Unit,
    onNavigateToRecord: () -> Unit,
    onNavigateToConvertedVideos: () -> Unit,
    onOpenDrawer: () -> Unit,
) {
    ConvertScreen(
        viewModel = convertViewModel,
        isRecordingActive = isRecordingActive,
        onNavigateBack = onNavigateBack,
        onNavigateToBackgroundPick = onNavigateToBackgroundPick,
        onNavigateToAudioPick = onNavigateToAudioPick,
        onNavigateToRecord = onNavigateToRecord,
        onNavigateToConvertedVideos = onNavigateToConvertedVideos,
        onOpenDrawer = onOpenDrawer,
    )
}

/**
 * App shell after language prompt is done. ViewModels / RecordingController live here only
 * so the first-launch picker never creates them.
 */
@Composable
private fun MainAppContent(
    activity: MainActivity,
    app: Application,
    settingsRepository: SettingsRepository,
    quickRecordFlow: StateFlow<Boolean>,
) {
    val convertViewModel: ConvertViewModel = viewModel()
    val optionsViewModel: OptionsViewModel = viewModel()
    // Activity viewModelStore — Record 화면 이탈 후에도 자동권한·Saved sticky·seam 키 생존
    val recordViewModel: RecordViewModel = viewModel()
    val recordingsListViewModel: RecordingsListViewModel = viewModel()
    val convertedVideosViewModel: ConvertedVideosViewModel = viewModel(
        factory = ConvertedVideosViewModel.Factory,
    )
    val youTubeUploadViewModel: YouTubeUploadViewModel? =
        if (StoreCapabilities.current.supportsYouTube) viewModel() else null
    val desktopSyncController = remember(app) { DesktopSyncController.getInstance(app) }
    var currentDestination by rememberSaveable(stateSaver = AppDestinationSaver) {
        mutableStateOf(AppDestination.Home)
    }
    var currentHomeTab by rememberSaveable { mutableStateOf(HomeTab.Record) }
    var homeTabHistory by rememberSaveable(stateSaver = HomeTabHistorySaver) {
        mutableStateOf(emptyList<HomeTab>())
    }
    var legacyHomeTabApplied by rememberSaveable { mutableStateOf(false) }
    SideEffect {
        if (!legacyHomeTabApplied) {
            NavigationRestoreHolder.pendingLegacyHomeTab?.let { tab ->
                currentHomeTab = tab
                legacyHomeTabApplied = true
                NavigationRestoreHolder.pendingLegacyHomeTab = null
            }
        } else {
            // Config change re-restore must not re-apply stale pending tab.
            NavigationRestoreHolder.pendingLegacyHomeTab = null
        }
    }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    // in-flight 가드 — Intent 성공/원샷 종료 후에만 true 유지, 실패 시 set(false)
    val exactAlarmPromptInFlight = remember { AtomicBoolean(false) }
    DisposableEffect(lifecycleOwner, settingsRepository) {
        fun tryPromptExactAlarmOnce() {
            if (!exactAlarmPromptInFlight.compareAndSet(false, true)) return
            activity.lifecycleScope.launch {
                try {
                    val prompted = runCatching {
                        settingsRepository.exactAlarmPrompted.first()
                    }.onFailure { e ->
                        AppLogger.e(MainActivity.TAG_MAIN, "Failed to read exactAlarmPrompted", e)
                    }.getOrDefault(false)
                    if (prompted) return@launch

                    if (ExactAlarmPermission.canScheduleExactAlarms(app)) {
                        runCatching {
                            settingsRepository.setExactAlarmPrompted(true)
                        }.onFailure { e ->
                            AppLogger.e(MainActivity.TAG_MAIN, "Failed to write exactAlarmPrompted", e)
                            exactAlarmPromptInFlight.set(false)
                        }
                        return@launch
                    }

                    val intent = ExactAlarmPermission.buildRequestIntent(app)
                    if (intent == null) {
                        runCatching {
                            settingsRepository.setExactAlarmPrompted(true)
                        }.onFailure { e ->
                            AppLogger.e(MainActivity.TAG_MAIN, "Failed to write exactAlarmPrompted", e)
                            exactAlarmPromptInFlight.set(false)
                        }
                        return@launch
                    }

                    val promptedAgain = runCatching {
                        settingsRepository.exactAlarmPrompted.first()
                    }.onFailure { e ->
                        AppLogger.e(MainActivity.TAG_MAIN, "Failed to re-read exactAlarmPrompted", e)
                    }.getOrDefault(false)
                    if (promptedAgain) return@launch

                    if (!ExactAlarmPermission.isRequestIntentResolvable(app, intent)) {
                        AppLogger.e(MainActivity.TAG_MAIN, "exact alarm request intent unresolved")
                        exactAlarmPromptInFlight.set(false)
                        return@launch
                    }

                    runCatching {
                        activity.startActivity(intent)
                    }.onSuccess {
                        runCatching {
                            settingsRepository.setExactAlarmPrompted(true)
                        }.onFailure { e ->
                            AppLogger.e(MainActivity.TAG_MAIN, "Failed to write exactAlarmPrompted", e)
                        }
                    }.onFailure { e ->
                        AppLogger.e(MainActivity.TAG_MAIN, "exact alarm request intent failed", e)
                        exactAlarmPromptInFlight.set(false)
                    }
                } catch (e: Exception) {
                    AppLogger.e(MainActivity.TAG_MAIN, "exact alarm prompt failed", e)
                    exactAlarmPromptInFlight.set(false)
                }
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tryPromptExactAlarmOnce()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            tryPromptExactAlarmOnce()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    val recordingState by remember(app) {
        RecordingController.getInstance(app).state
    }.collectAsStateWithLifecycle()
    val recordUiState by recordViewModel.uiState.collectAsStateWithLifecycle()
    val isSavingGate by recordViewModel.isSavingGate.collectAsStateWithLifecycle()
    val isRecordingActive = when (recordingState) {
        is RecordingState.Recording,
        is RecordingState.Paused,
        is RecordingState.Stopping,
        -> true
        else -> false
    }
    val isRecordingSaving = shouldBlockNavigationWhileRecordingSaving(
        recordUiState = recordUiState,
        isSavingGate = isSavingGate,
    )
    val conversionState by convertViewModel.conversionState.collectAsStateWithLifecycle()
    val isRecordingFormatReady by recordViewModel.isRecordingFormatReady.collectAsStateWithLifecycle()
    val quickRecordPending by quickRecordFlow.collectAsStateWithLifecycle()
    val pairingRequest by desktopSyncController.pairingRequest.collectAsStateWithLifecycle()
    val isActiveSession = isActiveRecordingSession(recordingState)
    val isSavedSticky = recordUiState is RecordUiState.Saved
    val isReviewPending = recordUiState is RecordUiState.Review
    val isConversionBlocking = isConversionBlockingQuickRecord(conversionState)
    var isRecordingBackupPromptVisible by rememberSaveable { mutableStateOf(false) }
    var isRecordingBackupPromptActionInFlight by remember { mutableStateOf(false) }
    var recordingBackupPromptCandidatePending by rememberSaveable { mutableStateOf(false) }
    val recordingBackupPromptReadInFlight = remember { AtomicBoolean(false) }
    val recordingBackupPromptActionInFlight = remember { AtomicBoolean(false) }
    var listenSelectionActive by remember { mutableStateOf(false) }
    var convertedVideosSelectionActive by remember { mutableStateOf(false) }
    val isHomeSelectionActive = listenSelectionActive || convertedVideosSelectionActive
    fun clearHomeSelectionIfNeeded() {
        listenSelectionActive = false
        convertedVideosSelectionActive = false
    }
    fun setHomeTabRoot(tab: HomeTab) {
        currentHomeTab = tab
        homeTabHistory = emptyList()
        clearHomeSelectionIfNeeded()
    }
    // LanguagePromptUi.App + MainAppContent 안정 마운트 이후에만 실행(이 Composable은 App 분기에서만 구성).
    // extra는 consume 전까지 Intent에 유지 → 언어 recreate가 트리거를 잃지 않음.
    // savedInstanceState == null 로 재점화 차단하지 않음(consume이 extra를 제거하는 것이 SSOT).
    LaunchedEffect(
        quickRecordPending,
        isRecordingSaving,
        isActiveSession,
        isSavedSticky,
        isReviewPending,
        isConversionBlocking,
        isRecordingFormatReady,
    ) {
        if (!quickRecordPending) return@LaunchedEffect
        if (shouldDeferQuickRecord(
                isRecordingSaving = isRecordingSaving,
                isActiveSession = isActiveSession,
                isSavedSticky = isSavedSticky,
                isConversionBlocking = isConversionBlocking,
                isReviewPending = isReviewPending,
            )
        ) {
            return@LaunchedEffect
        }
        val hasRecordAudio = ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (hasRecordAudio && !isRecordingFormatReady) {
            val loaded = runCatching { settingsRepository.recordingFormat.first() }
                .onFailure { e ->
                    AppLogger.w(MainActivity.TAG_MAIN, "quick record format load failed", e)
                }
                .getOrNull()
            if (loaded == null) return@LaunchedEffect
        }
        val latestUi = recordViewModel.uiState.value
        val latestSavingGate = recordViewModel.isSavingGate.value
        val latestRecording = RecordingController.getInstance(app).state.value
        val latestConversion = convertViewModel.conversionState.value
        if (shouldDeferQuickRecord(
                isRecordingSaving = shouldBlockNavigationWhileRecordingSaving(
                    recordUiState = latestUi,
                    isSavingGate = latestSavingGate,
                ),
                isActiveSession = isActiveRecordingSession(latestRecording),
                isSavedSticky = latestUi is RecordUiState.Saved,
                isConversionBlocking = isConversionBlockingQuickRecord(latestConversion),
                isReviewPending = latestUi is RecordUiState.Review,
            )
        ) {
            return@LaunchedEffect
        }
        if (!hasRecordAudio) {
            setHomeTabRoot(HomeTab.Record)
            currentDestination = AppDestination.Home
            activity.consumePendingQuickRecord()
            return@LaunchedEffect
        }
        recordViewModel.start()
        val afterStart = RecordingController.getInstance(app).state.value
        if (!shouldConsumeAfterQuickStart(afterStart)) {
            // Failed → destination 유지, pending·extra 유지
            return@LaunchedEffect
        }
        setHomeTabRoot(HomeTab.Record)
        currentDestination = AppDestination.Home
        activity.consumePendingQuickRecord()
    }
    val openDrawer: () -> Unit = {
        if (!isRecordingSaving &&
            !isHomeSelectionActive &&
            drawerState.targetValue == DrawerValue.Closed &&
            !drawerState.isAnimationRunning
        ) {
            scope.launch { drawerState.open() }
        }
    }
    val navigateToRecord: () -> Unit = {
        convertViewModel.selectAudioSourceTab(ConvertViewModel.AudioSourceTab.Record)
        setHomeTabRoot(HomeTab.Record)
        currentDestination = AppDestination.Home
    }
    val navigateToLanding: () -> Unit = {
        currentDestination = AppDestination.Home
    }
    fun maybeShowRecordingBackupPromptAfterSuccessfulKeep() {
        if (!recordingBackupPromptCandidatePending) return
        if (!recordingBackupPromptReadInFlight.compareAndSet(false, true)) return
        activity.lifecycleScope.launch {
            var shouldKeepReadGuard = false
            try {
                var promptPreferences: RecordingBackupPromptPreferences? = null
                var readAttempt = 0
                while (
                    promptPreferences == null &&
                    readAttempt < RECORDING_BACKUP_PROMPT_READ_MAX_ATTEMPTS
                ) {
                    readAttempt += 1
                    promptPreferences = settingsRepository.readRecordingBackupPromptPreferencesOrNull()
                    if (
                        promptPreferences == null &&
                        readAttempt < RECORDING_BACKUP_PROMPT_READ_MAX_ATTEMPTS
                    ) {
                        delay(RECORDING_BACKUP_PROMPT_RETRY_DELAY_MILLIS)
                    }
                }
                val preferences = promptPreferences ?: return@launch
                if (preferences.isHandled) {
                    recordingBackupPromptCandidatePending = false
                    return@launch
                }
                val isBackupFolderConnected = withContext(Dispatchers.IO) {
                    RecordingBackupFolder.isStoredUriAccessible(
                        context = app,
                        storedUri = preferences.backupFolderUri,
                    )
                }
                if (shouldShowRecordingBackupPrompt(
                        candidate = RecordingBackupPromptCandidate.SuccessfulKeepCallback,
                        isBackupFolderConnected = isBackupFolderConnected,
                        isPromptHandled = preferences.isHandled,
                    )
                ) {
                    recordingBackupPromptCandidatePending = false
                    isRecordingBackupPromptVisible = true
                    shouldKeepReadGuard = true
                } else {
                    recordingBackupPromptCandidatePending = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(
                    MainActivity.TAG_MAIN,
                    "Failed to evaluate recording backup prompt",
                    e,
                )
            } finally {
                if (!shouldKeepReadGuard) {
                    recordingBackupPromptReadInFlight.set(false)
                }
            }
        }
    }
    fun handleRecordingBackupPromptAction(openOptions: Boolean) {
        if (!recordingBackupPromptActionInFlight.compareAndSet(false, true)) return
        isRecordingBackupPromptActionInFlight = true
        activity.lifecycleScope.launch {
            try {
                settingsRepository.setRecordingBackupPromptHandled(true)
                val actionResult = resolveRecordingBackupPromptActionResult(
                    openOptions = openOptions,
                    persistence = RecordingBackupPromptPersistence.Succeeded,
                )
                if (actionResult.closePrompt) {
                    isRecordingBackupPromptVisible = false
                }
                if (actionResult.navigateToOptions) {
                    resolveRecordingBackupPromptDestination(openOptions)?.let { destination ->
                        currentDestination = destination
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(
                    MainActivity.TAG_MAIN,
                    "Failed to persist recording backup prompt choice",
                    e,
                )
                Toast.makeText(
                    activity,
                    activity.getString(R.string.recording_backup_prompt_save_failed),
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                recordingBackupPromptActionInFlight.set(false)
                isRecordingBackupPromptActionInFlight = false
            }
        }
    }
    DisposableEffect(lifecycleOwner, settingsRepository) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Retry only a pending one-shot successful-Keep candidate; never Saved recomposition.
                maybeShowRecordingBackupPromptAfterSuccessfulKeep()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            maybeShowRecordingBackupPromptAfterSuccessfulKeep()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    val handleRecordingSaved: (java.io.File, Long) -> Unit = { file, durationMs ->
        val uriResult = runCatching {
            RecordingRepository.contentUriFor(activity, file)
        }
        val action = resolveRecordingSavedSeamAction(
            uriSucceeded = uriResult.isSuccess,
        )
        uriResult.fold(
            onSuccess = { uri ->
                if (action.applyAudioToHome) {
                    convertViewModel.applyRecordingSaved(
                        uri = uri,
                        title = file.name,
                        durationMs = durationMs,
                    )
                }
                if (action.navigateHome) {
                    navigateToLanding()
                }
                recordingBackupPromptCandidatePending = true
                maybeShowRecordingBackupPromptAfterSuccessfulKeep()
            },
            onFailure = { error ->
                AppLogger.e(
                    MainActivity.TAG_MAIN,
                    "FileProvider URI failed for saved recording",
                    error,
                )
                if (action.clearRecordTerminal) {
                    recordViewModel.discardStickySavedAfterUriFailure()
                }
                if (action.showUriFailedFeedback) {
                    convertViewModel.reportRecordingUriFailed()
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.home_recording_uri_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
        )
    }
    val onRecordingSaved: (java.io.File) -> Unit = { file ->
        handleRecordingSaved(file, 0L)
    }
    val onRecordingSavedWithDuration: (java.io.File, Long) -> Unit = handleRecordingSaved
    if (isRecordingBackupPromptVisible) {
        AlertDialog(
            modifier = Modifier.testTag("recording_backup_prompt_dialog"),
            onDismissRequest = {
                handleRecordingBackupPromptAction(openOptions = false)
            },
            title = {
                Text(text = stringResource(R.string.recording_backup_prompt_title))
            },
            text = {
                Text(text = stringResource(R.string.recording_backup_prompt_body))
            },
            confirmButton = {
                TextButton(
                    onClick = { handleRecordingBackupPromptAction(openOptions = true) },
                    enabled = !isRecordingBackupPromptActionInFlight,
                    modifier = Modifier.testTag("recording_backup_prompt_options_button"),
                ) {
                    Text(text = stringResource(R.string.recording_backup_prompt_options))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { handleRecordingBackupPromptAction(openOptions = false) },
                    enabled = !isRecordingBackupPromptActionInFlight,
                    modifier = Modifier.testTag("recording_backup_prompt_dismiss_button"),
                ) {
                    Text(text = stringResource(R.string.recording_backup_prompt_dismiss))
                }
            },
        )
    }
    val isDrawerActive = drawerState.isOpen || drawerState.targetValue == DrawerValue.Open
    val drawerSelected = resolveDrawerSelected(currentDestination)

    BackHandler(enabled = isDrawerActive) {
        scope.launch { drawerState.close() }
    }
    BackHandler(
        enabled = !isDrawerActive &&
            currentDestination != AppDestination.Home &&
            !isRecordingSaving,
    ) {
        navigateToLanding()
    }
    BackHandler(
        enabled = currentDestination == AppDestination.Home &&
            !isDrawerActive &&
            !isRecordingSaving &&
            homeTabHistory.isNotEmpty() &&
            !isHomeSelectionActive,
    ) {
        val popped = popHomeTabHistory(homeTabHistory) ?: return@BackHandler
        homeTabHistory = popped.stack
        clearHomeSelectionIfNeeded()
        currentHomeTab = popped.previous
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = isDrawerGesturesEnabled(
            isDrawerActive = isDrawerActive,
            isRecordingSaving = isRecordingSaving,
            isListenSelectionActive = listenSelectionActive,
            isConvertedVideosSelectionActive = convertedVideosSelectionActive,
        ),
        drawerContent = {
            AppDrawerContent(
                selected = drawerSelected,
                isRecordingActive = isRecordingActive,
                onSelect = { dest ->
                    if (isRecordingSaving || isHomeSelectionActive) {
                        scope.launch { drawerState.close() }
                        return@AppDrawerContent
                    }
                    currentDestination = dest
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        when (currentDestination) {
            AppDestination.Home -> HomeScreen(
                selectedTab = currentHomeTab,
                onSelectTab = { tab ->
                    if (tab != currentHomeTab) {
                        homeTabHistory = pushHomeTabHistory(homeTabHistory, currentHomeTab)
                        clearHomeSelectionIfNeeded()
                        currentHomeTab = tab
                    }
                },
                onOpenDrawer = openDrawer,
                recordViewModel = recordViewModel,
                recordingsListViewModel = recordingsListViewModel,
                convertedVideosViewModel = convertedVideosViewModel,
                onRecordingSaved = onRecordingSaved,
                onRecordingSavedWithDuration = onRecordingSavedWithDuration,
                onConvertAudioItems = { items ->
                    listenSelectionActive = false
                    convertViewModel.applyFilePickSaved(
                        items.map { item ->
                            ConvertViewModel.SelectedAudio(
                                uri = item.uri,
                                title = item.title,
                                artist = item.artist,
                                durationMs = item.durationMs,
                            )
                        },
                    )
                    currentDestination = AppDestination.Convert
                },
                onListenSelectionActiveChange = { listenSelectionActive = it },
                youTubeViewModel = youTubeUploadViewModel,
                onConvertedVideosSelectionActiveChange = {
                    convertedVideosSelectionActive = it
                },
            )
            AppDestination.Convert -> ConvertAssemblyScreen(
                convertViewModel = convertViewModel,
                isRecordingActive = isRecordingActive,
                onNavigateBack = navigateToLanding,
                onNavigateToBackgroundPick = {
                    currentDestination = AppDestination.BackgroundPick
                },
                onNavigateToAudioPick = {
                    currentDestination = AppDestination.AudioPick
                },
                onNavigateToRecord = navigateToRecord,
                onNavigateToConvertedVideos = {
                    currentDestination = AppDestination.Home
                    setHomeTabRoot(HomeTab.ConvertedVideos)
                },
                onOpenDrawer = openDrawer,
            )
            AppDestination.BackgroundPick -> BackgroundPickScreen(
                onNavigateBack = navigateToLanding,
                onOpenDrawer = openDrawer,
            )
            AppDestination.AudioPick -> AudioPickScreen(
                onAudioBatchPicked = { items ->
                    convertViewModel.applyFilePickSaved(
                        items.map { item ->
                            ConvertViewModel.SelectedAudio(
                                uri = item.uri,
                                title = item.title,
                                artist = item.artist,
                                durationMs = item.durationMs,
                            )
                        },
                    )
                    currentDestination = AppDestination.Convert
                },
                onNavigateBack = navigateToLanding,
                onOpenDrawer = openDrawer,
            )
            AppDestination.Options -> OptionsScreen(
                onOpenDrawer = openDrawer,
                viewModel = optionsViewModel,
            )
            AppDestination.ErrorLog -> ErrorLogScreen(
                onNavigateBack = navigateToLanding,
                onOpenDrawer = openDrawer,
            )
            AppDestination.Trash -> TrashScreen(
                onNavigateBack = navigateToLanding,
                onOpenDrawer = openDrawer,
            )
            AppDestination.MicrophoneSource -> MicrophoneSourceScreen(
                onNavigateBack = navigateToLanding,
                onOpenDrawer = openDrawer,
            )
        }
    }
    // 페어링 승인 다이얼로그는 어떤 화면에서도 표시되어야 하므로 AppContent 최상위에 배치한다.
    // (intentional convention exception; CLAUDE.md 갱신은 Phase 9)
    pairingRequest?.let { request ->
        DesktopPairingApprovalDialog(
            request = request,
            onApprove = { desktopSyncController.approvePairingRequest(request.requestId) },
            onReject = { desktopSyncController.rejectPairingRequest(request.requestId) },
        )
    }
}

@Composable
private fun DesktopPairingApprovalDialog(
    request: PairingRequest,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    val title = stringResource(R.string.desktop_pairing_request_title)
    val message = stringResource(
        R.string.desktop_pairing_request_message,
        request.deviceName,
        request.remoteHost,
    )
    val approveLabel = stringResource(R.string.desktop_pairing_request_approve)
    val rejectLabel = stringResource(R.string.desktop_pairing_request_reject)
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onApprove,
                modifier = Modifier.testTag("desktop_pairing_approve_button"),
            ) {
                Text(approveLabel)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onReject,
                modifier = Modifier.testTag("desktop_pairing_reject_button"),
            ) {
                Text(rejectLabel)
            }
        },
    )
}

class MainActivity : ComponentActivity() {
    /**
     * AppCompatDelegate bridge for per-app locales while keeping [ComponentActivity]
     * (not [androidx.appcompat.app.AppCompatActivity]).
     *
     * Only [attachBaseContext2] is forwarded. This app's theme (`Theme.Convert2video`) does
     * **not** descend from `Theme.AppCompat` (100% Compose/Material3, by design — do not change
     * theme parent or migrate to AppCompatActivity here). Forwarding [AppCompatDelegate.onCreate]/
     * [AppCompatDelegate.onPostCreate]/[AppCompatDelegate.onConfigurationChanged]/
     * [AppCompatDelegate.onDestroy] was tried and reverted: `onPostCreate` unconditionally calls
     * `ensureSubDecor()`, which throws `IllegalStateException: You need to use a Theme.AppCompat
     * theme` on a non-AppCompat theme — confirmed via a real-device crash (`AppCompatDelegateImpl
     * .createSubDecor`). This app never calls `appCompatDelegate.setContentView`/other window-decor
     * APIs, so those lifecycle forwards serve no purpose here; [attachBaseContext2] alone is what
     * makes [AppCompatDelegate.setApplicationLocales] resolve correctly for this Activity's
     * resources (the Android 14 per-app-locale bug this API was added to fix).
     *
     * - No static attachBaseContext helper — instance from [AppCompatDelegate.create].
     * - [attachBaseContext2] wraps the base Context for locale-aware resources.
     * - Strong field holds the Delegate instance for the Activity lifetime (AppCompat's internal
     *   activity references remain WeakRef; this field does not replace that).
     * - [create] second arg is `null` [androidx.appcompat.app.AppCompatCallback]: OK for
     *   100% Compose — no ActionMode / AppCompat window callbacks needed.
     * - Do **not** call [AppCompatDelegate.installViewFactory].
     *
     * Options Screen owns `restartApp` after [SettingsRepository.applyLanguage];
     * first-launch LanguagePicker owns [Activity.recreate].
     */
    private val appCompatDelegate: AppCompatDelegate by lazy {
        // null AppCompatCallback: Compose-only Activity — no ActionMode / window callbacks.
        AppCompatDelegate.create(this, null)
    }

    // onNewIntent → Compose 브리지. true = quick record 트리거 대기중. Compose는 직접 쓰지 않음.
    private val _pendingQuickRecord = MutableStateFlow(false)

    internal fun markQuickRecordPending() {
        _pendingQuickRecord.value = true
    }

    /**
     * CAS(true→false) 후 extra 제거. true = 이 호출자가 consume.
     * recreate 재점화 방지는 extra 제거가 SSOT ([savedInstanceState] null 체크 금지).
     */
    internal fun consumePendingQuickRecord(): Boolean {
        if (!_pendingQuickRecord.compareAndSet(expect = true, update = false)) return false
        val current = intent
        current.stripQuickRecordExtra()
        setIntent(current)
        return true
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(appCompatDelegate.attachBaseContext2(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AnalyticsReporter.getInstance(this).logEvent(AnalyticsReporter.EVENT_APP_OPEN)
        if (intent?.isQuickRecordRequested() == true) {
            markQuickRecordPending()
        }
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val app = remember(context) { context.requireApplication() }
            // Exact alarm / language: MainActivity → SettingsRepository 직접 (OptionsViewModel 경유 금지)
            val settingsRepository = remember(app) { SettingsRepository(app) }
            val languagePromptShown by settingsRepository.languagePromptShown
                .collectAsStateWithLifecycle(initialValue = null)
            // Flash gate: set true ONLY after successful setLanguagePromptShown (awaiting recreate)
            // — never at tap start (that unmounted Picker and cancelled apply).
            var languageSelectionCommitted by remember { mutableStateOf(false) }
            var isLanguageApplying by remember { mutableStateOf(false) }
            // Apply job lives outside LanguagePickerScreen so it survives Picker→Progress switch
            val languagePromptScope = rememberCoroutineScope()
            val languagePromptContext = LocalContext.current
            val languageApplyFailedMessage = stringResource(
                R.string.language_picker_apply_failed,
            )
            // Theme without OptionsViewModel (picker / loading branch must not create Options VM)
            val themeMode by settingsRepository.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.System)
            val systemInDarkTheme = isSystemInDarkTheme()
            val isDarkTheme = when (themeMode) {
                ThemeMode.System -> systemInDarkTheme
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            val languagePromptGate = resolveLanguagePromptGate(
                promptShown = languagePromptShown,
                committed = languageSelectionCommitted,
            )

            // Picker or committed-awaiting-recreate only — not null Loading (avoid forever trap)
            BackHandler(
                enabled = languagePromptGate == LanguagePromptUi.Picker ||
                    languagePromptGate == LanguagePromptUi.ApplyingProgress,
            ) { /* no-op — force language choice / wait recreate */ }

            fun onLanguageSelectedFromPicker(option: LanguageOption) {
                if (isLanguageApplying) return
                isLanguageApplying = true
                languagePromptScope.launch {
                    try {
                        // (a) apply locale → (b) committed 먼저 → (c) prompt shown → (d) recreate
                        // write 전 committed로 true+!committed → App 플래시(consume)를 막는다.
                        SettingsRepository.applyLanguage(option)
                        languageSelectionCommitted = true
                        settingsRepository.setLanguagePromptShown(true)
                        val host = languagePromptContext.findActivityOrNull()
                        if (host == null) {
                            AppLogger.e(
                                TAG_MAIN,
                                "language pick apply: Activity null after prompt write",
                            )
                            try {
                                settingsRepository.setLanguagePromptShown(false)
                            } catch (rollback: Exception) {
                                AppLogger.e(
                                    TAG_MAIN,
                                    "language pick rollback promptShown failed",
                                    rollback,
                                )
                            }
                            isLanguageApplying = false
                            languageSelectionCommitted = false
                            Toast.makeText(
                                languagePromptContext,
                                languageApplyFailedMessage,
                                Toast.LENGTH_LONG,
                            ).show()
                            return@launch
                        }
                        host.recreate()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e(TAG_MAIN, "language pick apply failed", e)
                        isLanguageApplying = false
                        languageSelectionCommitted = false
                        Toast.makeText(
                            languagePromptContext,
                            languageApplyFailedMessage,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }

            Convert2videoTheme(darkTheme = isDarkTheme) {
                when (languagePromptGate) {
                    LanguagePromptUi.Loading,
                    LanguagePromptUi.ApplyingProgress,
                    -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    LanguagePromptUi.Picker -> {
                        LanguagePickerScreen(
                            isApplying = isLanguageApplying,
                            onLanguageSelected = ::onLanguageSelectedFromPicker,
                        )
                    }
                    LanguagePromptUi.App -> {
                        MainAppContent(
                            activity = this@MainActivity,
                            app = app,
                            settingsRepository = settingsRepository,
                            quickRecordFlow = _pendingQuickRecord,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.isQuickRecordRequested()) {
            markQuickRecordPending()
        }
    }

    companion object {
        internal const val TAG_MAIN = "MainActivity"
        const val EXTRA_QUICK_RECORD = "com.example.convert2video.EXTRA_QUICK_RECORD"
    }
}
