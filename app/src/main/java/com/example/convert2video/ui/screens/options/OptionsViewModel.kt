package com.example.convert2video.ui.screens.options

import android.accounts.Account
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.result.IntentSenderRequest
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.example.convert2video.R
import com.example.convert2video.billing.BillingFailureKind
import com.example.convert2video.billing.BillingGatewayResult
import com.example.convert2video.billing.BillingOperationId
import com.example.convert2video.billing.BILLING_WORKFLOW_TIMEOUT_MILLIS
import com.example.convert2video.billing.EntitlementRepository
import com.example.convert2video.billing.PurchaseCompletionResult
import com.example.convert2video.billing.PurchaseLaunchSession
import com.example.convert2video.billing.PurchaseResult
import com.example.convert2video.billing.RestoreResult
import com.example.convert2video.data.LanguageOption
import com.example.convert2video.data.PerAppLocalesRead
import com.example.convert2video.data.SettingsRepository
import com.example.convert2video.data.ThemeMode
import com.example.convert2video.data.YoutubeDefaultVisibility
import com.example.convert2video.drive.DriveApiResult
import com.example.convert2video.drive.DriveAuthGateway
import com.example.convert2video.drive.DriveAuthorizationOutcome
import com.example.convert2video.drive.DriveStorageQuota
import com.example.convert2video.drive.GoogleDriveApiClient
import com.example.convert2video.drive.createDriveAuthGateway
import com.example.convert2video.drive.driveFailureMessageToStringRes
import com.example.convert2video.record.NoiseReductionMode
import com.example.convert2video.record.RecordingBackupFolder
import com.example.convert2video.record.RecordingBackupReconciler
import com.example.convert2video.record.RecordingFormat
import com.example.convert2video.store.StoreCapabilities
import com.example.convert2video.desktopsync.DesktopSyncController
import com.example.convert2video.desktopsync.ServerState
import com.example.convert2video.ui.shared.isPendingConstrained
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

private const val TAG = "OptionsViewModel"

private fun logError(message: String, error: Throwable) {
    AppLogger.e(TAG, "$message: ${error.javaClass.simpleName}")
}

private fun logWarning(message: String, error: Throwable) {
    AppLogger.w(TAG, "$message: ${error.javaClass.simpleName}")
}

internal sealed interface BillingPurchaseUiState {
    data object Idle : BillingPurchaseUiState
    data object Preparing : BillingPurchaseUiState
    data object AwaitingResolution : BillingPurchaseUiState
    data object Pending : BillingPurchaseUiState
    data object Purchased : BillingPurchaseUiState
    data object Cancelled : BillingPurchaseUiState
    data class Failed(val kind: BillingFailureKind) : BillingPurchaseUiState
}

internal fun billingPurchaseUiStateFor(result: PurchaseResult): BillingPurchaseUiState = when (result) {
    PurchaseResult.UserCancelled,
    PurchaseResult.Cancelled
    -> BillingPurchaseUiState.Cancelled
    PurchaseResult.AlreadyOwned -> BillingPurchaseUiState.Failed(BillingFailureKind.Unavailable)
    PurchaseResult.Duplicate -> BillingPurchaseUiState.Failed(BillingFailureKind.ProviderError)
    PurchaseResult.Malformed,
    PurchaseResult.ProductMismatch
    -> BillingPurchaseUiState.Failed(BillingFailureKind.InvalidResponse)
    is PurchaseResult.Failed -> BillingPurchaseUiState.Failed(result.kind)
    is PurchaseResult.Pending -> BillingPurchaseUiState.Pending
    // Provider PURCHASED is only an input to repository verification; it is not UI success.
    is PurchaseResult.Purchased -> BillingPurchaseUiState.Preparing
    is PurchaseResult.NeedsResolution ->
        BillingPurchaseUiState.Failed(BillingFailureKind.InvalidResponse)
}

internal fun billingPurchaseUiStateFor(
    result: PurchaseCompletionResult,
): BillingPurchaseUiState = when (result) {
    PurchaseCompletionResult.Purchased -> BillingPurchaseUiState.Purchased
    PurchaseCompletionResult.Pending -> BillingPurchaseUiState.Pending
    PurchaseCompletionResult.Cancelled -> BillingPurchaseUiState.Cancelled
    PurchaseCompletionResult.Unavailable -> BillingPurchaseUiState.Failed(BillingFailureKind.Unavailable)
    is PurchaseCompletionResult.Failed -> BillingPurchaseUiState.Failed(result.kind)
}

internal fun billingRestoreUiStateFor(result: RestoreResult): BillingPurchaseUiState = when (result) {
    RestoreResult.Restored -> BillingPurchaseUiState.Purchased
    RestoreResult.Pending -> BillingPurchaseUiState.Pending
    RestoreResult.Unavailable -> BillingPurchaseUiState.Failed(BillingFailureKind.Unavailable)
    is RestoreResult.Failed -> BillingPurchaseUiState.Failed(result.kind)
}

internal enum class BillingOperation {
    Purchase,
    Restore,
}

/** Common non-suspending admission seam for purchase and restore commands. */
internal class BillingOperationAdmission {
    private val mutex = Mutex()
    private var activeOperation: BillingOperation? = null

    @Synchronized
    fun tryAcquire(operation: BillingOperation): Boolean {
        if (!mutex.tryLock()) return false
        activeOperation = operation
        return true
    }

    @Synchronized
    fun release(operation: BillingOperation) {
        if (activeOperation != operation) return
        activeOperation = null
        mutex.unlock()
    }
}

internal sealed interface BillingPurchaseTerminal {
    data class ActivityResult(val resultCode: Int, val data: Intent?) : BillingPurchaseTerminal
    data object LaunchFailed : BillingPurchaseTerminal
    data object TimedOut : BillingPurchaseTerminal
}

internal class BillingActivityResultGate(
    private val operationId: BillingOperationId,
) {
    private val activityResultAccepted = AtomicBoolean(false)
    private val terminal = CompletableDeferred<BillingPurchaseTerminal>()
    private val awaitingActivityResult = AtomicBoolean(false)

    fun awaitActivityResult() = terminal

    fun matches(callbackOperationId: BillingOperationId?): Boolean =
        callbackOperationId != null && callbackOperationId == operationId

    fun beginAwaitingActivityResult() {
        awaitingActivityResult.set(true)
    }

    fun acceptActivityResult(
        callbackOperationId: BillingOperationId?,
        resultCode: Int,
        data: Intent?,
    ): Boolean {
        if (callbackOperationId != operationId) return false
        if (!awaitingActivityResult.get() ||
            !activityResultAccepted.compareAndSet(false, true)
        ) return false
        return terminal.complete(BillingPurchaseTerminal.ActivityResult(resultCode, data))
    }

    fun acceptLaunchFailure(callbackOperationId: BillingOperationId?): Boolean {
        if (callbackOperationId != operationId) return false
        if (!awaitingActivityResult.get() ||
            !activityResultAccepted.compareAndSet(false, true)
        ) return false
        return terminal.complete(BillingPurchaseTerminal.LaunchFailed)
    }

    fun acceptTimeout(callbackOperationId: BillingOperationId?): Boolean {
        if (callbackOperationId != operationId) return false
        if (!awaitingActivityResult.get() ||
            !activityResultAccepted.compareAndSet(false, true)
        ) return false
        return terminal.complete(BillingPurchaseTerminal.TimedOut)
    }

    fun isTerminalAccepted(): Boolean = activityResultAccepted.get()
}

/** Replayable Huawei resolution hand-off; the latest request survives a collector rotation. */
internal data class BillingResolutionRequest(
    val operationId: BillingOperationId,
    val request: IntentSenderRequest,
)

internal class BillingResolutionReplay {
    private val requests = MutableSharedFlow<BillingResolutionRequest>(
        replay = 1,
        extraBufferCapacity = 1,
    )
    private var pendingOperationId: BillingOperationId? = null
    private var launchedOperationId: BillingOperationId? = null

    val flow: SharedFlow<BillingResolutionRequest> = requests.asSharedFlow()

    @Synchronized
    fun publish(request: BillingResolutionRequest): Boolean {
        val emitted = requests.tryEmit(request)
        if (emitted) {
            pendingOperationId = request.operationId
            launchedOperationId = null
        }
        return emitted
    }

    /** Claims the provider launch once; replayed collectors may still recover the identity. */
    @Synchronized
    fun claimLaunch(operationId: BillingOperationId): Boolean {
        if (pendingOperationId != operationId) return false
        if (launchedOperationId == operationId) return false
        launchedOperationId = operationId
        return true
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Synchronized
    fun clear() {
        pendingOperationId = null
        launchedOperationId = null
        requests.resetReplayCache()
    }
}

/**
 * ActivityResultLauncher does not carry the request identity into its callback. This binding is
 * therefore a fail-closed single-flight quarantine: a second resolution request cannot be bound
 * until the first bound callback has been accepted or explicitly released after launch failure.
 */
internal class BillingResolutionCallbackBinding {
    internal class Captured internal constructor(
        internal val operationId: BillingOperationId,
        internal val generation: Long,
    )

    private var active: Captured? = null
    private var providerLaunchActive = false
    private var providerLaunchInProgress = false
    private var quarantinedForLateCallback = false
    private var nextGeneration = 0L

    @Synchronized
    fun capture(operationId: BillingOperationId): Captured? {
        if (active !== null) return null
        nextGeneration += 1L
        return Captured(operationId, nextGeneration).also {
            active = it
            providerLaunchActive = false
            providerLaunchInProgress = false
            quarantinedForLateCallback = false
        }
    }

    /**
     * Atomically validates a captured generation and hands it to Android. The binding only
     * becomes provider-active after [handoff] returns, so a failed launcher call cannot claim a
     * provider Activity. The in-progress flag still lets a synchronously delivered callback
     * consume the request after the handoff has actually begun.
     */
    @Synchronized
    fun handoffToProviderIfCurrent(captured: Captured, handoff: () -> Unit): Boolean {
        if (active !== captured || quarantinedForLateCallback) return false
        providerLaunchInProgress = true
        try {
            handoff()
        } catch (error: Throwable) {
            if (active === captured) providerLaunchInProgress = false
            throw error
        }
        if (active === captured) {
            providerLaunchInProgress = false
            providerLaunchActive = true
        }
        return true
    }

    /** Identity-bearing acceptance rejects both a foreign token and a stale binding token. */
    @Synchronized
    fun accept(
        captured: Captured,
        callbackOperationId: BillingOperationId?,
        onAccepted: (BillingOperationId) -> Unit,
    ): Boolean {
        val current = active
        if (current !== captured || callbackOperationId != captured.operationId) return false
        active = null
        providerLaunchActive = false
        providerLaunchInProgress = false
        quarantinedForLateCallback = false
        onAccepted(captured.operationId)
        return true
    }

    /** Launcher callback seam: only the one quarantined request may consume the callback. */
    @Synchronized
    fun acceptCurrent(onAccepted: (BillingOperationId) -> Unit): Boolean {
        val current = active ?: return false
        if (!providerLaunchActive && !providerLaunchInProgress && !quarantinedForLateCallback) {
            return false
        }
        return accept(current, current.operationId, onAccepted)
    }

    @Synchronized
    fun release(captured: Captured): Boolean {
        if (active !== captured ||
            providerLaunchActive ||
            providerLaunchInProgress ||
            quarantinedForLateCallback
        ) return false
        active = null
        providerLaunchActive = false
        providerLaunchInProgress = false
        return true
    }

    /**
     * A timed-out/aborted operation may still produce an identity-less Android callback. Keep its
     * binding as a quarantine, but permanently reject its stale collector before launcher handoff.
     */
    @Synchronized
    fun invalidate(operationId: BillingOperationId): Boolean {
        val current = active ?: return false
        if (current.operationId != operationId) return false
        quarantinedForLateCallback = true
        providerLaunchActive = false
        providerLaunchInProgress = false
        return true
    }

    /**
     * Resets a binding left behind by Activity recreation only when no provider launch is active.
     * A timed-out/aborted launch remains bound until its identity-less late callback is consumed.
     */
    @Synchronized
    fun clear(): Boolean {
        if (providerLaunchActive || providerLaunchInProgress || quarantinedForLateCallback) return false
        active = null
        return true
    }

    @Synchronized
    internal fun isProviderLaunchActive(): Boolean = providerLaunchActive

    /** A bound callback blocks new purchases even before a provider Activity has started. */
    @Synchronized
    internal fun hasActiveBinding(): Boolean = active !== null

    @Synchronized
    internal fun isBound(operationId: BillingOperationId): Boolean =
        active?.operationId == operationId
}

/**
 * Recording-backup folder UI state. The selected URI remains private to the ViewModel.
 */
sealed interface RecordingBackupFolderUiState {
    /** No backup tree URI is stored. */
    data object Unavailable : RecordingBackupFolderUiState

    /** The stored tree URI has persisted read and write access. */
    data object Connected : RecordingBackupFolderUiState

    /** A tree URI is stored, but its persisted read/write grant is no longer valid. */
    data object BrokenGrant : RecordingBackupFolderUiState
}

private data class DesktopSyncSeams(
    val startServer: () -> Unit,
    val serverState: () -> StateFlow<ServerState>,
)

/**
 * 옵션 화면 상태 — Wi-Fi 전용 업로드 설정 + YouTube/Drive 계정 로그인/로그아웃.
 * 인증 플로우는 [YouTubeUploadViewModel]과 동일 패턴을 복제(공통 추출 금지).
 * Drive는 YouTube와 이름·흐름을 완전 분리한다.
 */
class OptionsViewModel(application: Application) : AndroidViewModel(application) {

    private val capabilities = StoreCapabilities.current
    private val settingsRepository = SettingsRepository(application)
    private val authManager: YouTubeAuthGateway by lazy { createYouTubeAuthGateway(application) }
    private val apiClient by lazy { YouTubeApiClient() }
    private val driveAuthManager: DriveAuthGateway by lazy { createDriveAuthGateway(application) }
    private val driveApiClient by lazy { GoogleDriveApiClient() }
    // Keep construction independent from WorkManager test/application initialization. The only
    // caller needs it when refreshing upload work, not while rendering billing state.
    private val workManager by lazy { WorkManager.getInstance(application) }
    private val entitlementRepository = EntitlementRepository.getInstance(application)
    private val _billingPurchaseState = MutableStateFlow<BillingPurchaseUiState>(
        BillingPurchaseUiState.Idle,
    )
    internal val billingPurchaseState: StateFlow<BillingPurchaseUiState> =
        _billingPurchaseState.asStateFlow()
    val proStatus: StateFlow<Boolean?> = entitlementRepository.isProHot
    /** Compatibility alias retained for the existing Options wiring. */
    val billingIsPro: StateFlow<Boolean?> = proStatus
    private val billingResolutionReplay = BillingResolutionReplay()
    // ViewModel-owned so a configuration change cannot silently bind a late A callback to B.
    private val billingCallbackBinding = BillingResolutionCallbackBinding()
    internal val billingResolutionRequests: SharedFlow<BillingResolutionRequest> =
        billingResolutionReplay.flow
    private var billingPurchaseJob: Job? = null
    private var billingRestoreJob: Job? = null
    private val billingAdmission = BillingOperationAdmission()
    private val billingOperationLock = Any()
    private var billingPurchaseOperation: BillingActivityResultGate? = null
    private var billingPurchaseSession: PurchaseLaunchSession? = null
    private val cleared = AtomicBoolean(false)
    private val _isPurchaseInFlight = MutableStateFlow(false)
    val isPurchaseInFlight: StateFlow<Boolean> = _isPurchaseInFlight.asStateFlow()
    private val _isRestoreInFlight = MutableStateFlow(false)
    val isRestoreInFlight: StateFlow<Boolean> = _isRestoreInFlight.asStateFlow()
    private val desktopSyncController = DesktopSyncController.getInstance(application)
    private val desktopSyncSeamLock = Any()
    private var desktopSyncStartGuardActive = false
    private var desktopSyncObservedState: ServerState? = null
    private var desktopSyncStateObservationJob: Job? = null
    private val desktopSyncStoppedFallback = MutableStateFlow<ServerState>(ServerState.Stopped)

    /** One atomic androidTest seam snapshot; production defaults remain the process singletons. */
    private var desktopSyncSeams = DesktopSyncSeams(
        startServer = desktopSyncController::startServer,
        serverState = { desktopSyncController.serverState },
    )
    /** Wi-Fi / YouTube / Drive 자동업로드 토글 직렬화 — DataStore write 경합 방지. */
    private val settingsMutex = Mutex()
    private val recordingBackupReconciliationMutex = Mutex()

    init {
        restartDesktopSyncStateObservation()
    }

    /**
     * null = DataStore 첫 값 대기(Switch 비활성). Eagerly로 초기 false 깜빡임 방지.
     */
    val wifiOnlyUpload: StateFlow<Boolean?> = settingsRepository.wifiOnlyUpload
        .map<Boolean, Boolean?> { it }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    /**
     * null = DataStore 첫 값 대기(Switch 비활성). Eagerly로 초기 false 깜빡임 방지.
     * YouTube 녹음 후 자동 변환·업로드 — Wi-Fi/Drive 키와 분리.
     */
    val youtubeAutoUploadEnabled: StateFlow<Boolean?>? by lazy {
        if (capabilities.supportsYouTube) {
            settingsRepository.youtubeAutoUploadEnabled
                .map<Boolean, Boolean?> { it }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.Eagerly,
                    initialValue = null,
                )
        } else null
    }

    /**
     * null = DataStore 첫 값 대기(SegmentedControl 비활성). Eagerly로 초기 깜빡임 방지.
     */
    val youtubeDefaultVisibility: StateFlow<YoutubeDefaultVisibility?>? by lazy {
        if (capabilities.supportsYouTube) {
            settingsRepository.youtubeDefaultVisibility
                .map<YoutubeDefaultVisibility, YoutubeDefaultVisibility?> { it }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.Eagerly,
                    initialValue = null,
                )
        } else null
    }

    /**
     * null = DataStore 첫 값 대기(Switch 비활성). Eagerly로 초기 false 깜빡임 방지.
     * Drive 자동 업로드 — Wi-Fi 전용 키와 분리.
     */
    val driveAutoUploadEnabled: StateFlow<Boolean?>? by lazy {
        if (capabilities.supportsDrive) {
            settingsRepository.driveAutoUploadEnabled
                .map<Boolean, Boolean?> { it }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.Eagerly,
                    initialValue = null,
                )
        } else null
    }

    /**
     * 사용자가 지정한 Drive 업로드 폴더 이름. null = DataStore 미시드(기본값 미적용).
     * UI에서 null이면 [R.string.app_name]을 기본값으로 표시한다.
     */
    val driveFolderNameOverride: StateFlow<String?>? by lazy {
        if (capabilities.supportsDrive) {
            settingsRepository.driveFolderName
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.Eagerly,
                    initialValue = null,
                )
        } else null
    }

    /**
     * Incremented when Options becomes visible again, because a persisted SAF grant can be
     * revoked outside this process without changing DataStore.
     */
    private val recordingBackupFolderRefresh = MutableStateFlow(0)

    /**
     * Derived from the private DataStore URI and persisted grant snapshot; neither exposes a
     * provider URI nor a filesystem path to the UI.
     */
    val recordingBackupFolderState: StateFlow<RecordingBackupFolderUiState> = combine(
        settingsRepository.recordingBackupFolderUri,
        recordingBackupFolderRefresh,
    ) { storedUri, _ -> storedUri }
        .map { storedUri ->
            withContext(Dispatchers.IO) {
                when {
                    storedUri.isNullOrBlank() -> RecordingBackupFolderUiState.Unavailable
                    RecordingBackupFolder.isStoredUriAccessible(getApplication(), storedUri) ->
                        RecordingBackupFolderUiState.Connected
                    else -> RecordingBackupFolderUiState.BrokenGrant
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = RecordingBackupFolderUiState.Unavailable,
        )

    private val _isRecordingBackupReconciliationInFlight = MutableStateFlow(false)
    val isRecordingBackupReconciliationInFlight: StateFlow<Boolean> =
        _isRecordingBackupReconciliationInFlight.asStateFlow()

    /** Drives [com.example.convert2video.ui.theme.Convert2videoTheme]'s darkTheme param from MainActivity. */
    val themeMode: StateFlow<ThemeMode> = settingsRepository.themeMode
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = ThemeMode.System,
        )

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            try {
                settingsRepository.setThemeMode(mode)
            } catch (e: Exception) {
                logError("Failed to set themeMode", e)
                _userMessage.tryEmit(appString(R.string.options_settings_save_failed))
            }
        }
    }

    /** Prepare may suspend; launch is a synchronous hand-off for an actor-admitted session. */
    fun purchasePro(activity: Activity) {
        if (!capabilities.supportsBilling || billingCallbackBinding.hasActiveBinding()) return
        if (!billingAdmission.tryAcquire(BillingOperation.Purchase)) return
        if (billingCallbackBinding.hasActiveBinding()) {
            billingAdmission.release(BillingOperation.Purchase)
            return
        }
        synchronized(billingOperationLock) {
            billingPurchaseOperation = null
            billingPurchaseSession = null
            _isPurchaseInFlight.value = true
        }
        billingPurchaseJob = viewModelScope.launch {
            var session: PurchaseLaunchSession? = null
            var operation: BillingActivityResultGate? = null
            try {
                val result = withTimeoutOrNull(BILLING_WORKFLOW_TIMEOUT_MILLIS) {
                    _billingPurchaseState.value = BillingPurchaseUiState.Preparing
                    when (val prepared = entitlementRepository.preparePurchaseIntent()) {
                        is BillingGatewayResult.Failure -> {
                            return@withTimeoutOrNull PurchaseCompletionResult.Failed(prepared.kind)
                        }
                        is BillingGatewayResult.NeedsResolution -> {
                            return@withTimeoutOrNull PurchaseCompletionResult.Failed(
                                BillingFailureKind.InvalidResponse,
                            )
                        }
                        is BillingGatewayResult.Success -> Unit
                    }
                    val admittedSession = entitlementRepository.beginPurchaseSession()
                    session = admittedSession
                    if (admittedSession === null) {
                        return@withTimeoutOrNull PurchaseCompletionResult.Unavailable
                    }
                    operation = BillingActivityResultGate(admittedSession.operationId)
                    synchronized(billingOperationLock) {
                        billingPurchaseSession = session
                        billingPurchaseOperation = operation
                    }

                    val launchResult = entitlementRepository.launchPreparedPurchase(
                        admittedSession,
                        activity,
                    )
                    when (launchResult) {                         
                        is BillingGatewayResult.Success -> {
                            if (admittedSession.store == StoreCapabilities.GOOGLE_PLAY_STORE_ID) {
                                awaitAndApplyPurchaseCompletion(admittedSession)
                            } else {
                                _billingPurchaseState.value =
                                    BillingPurchaseUiState.AwaitingResolution
                                operation?.beginAwaitingActivityResult()
                                clearBillingResolutionRequest()
                                awaitAndApplyPurchaseTerminal(operation!!, admittedSession)
                            }
                        }
                        is BillingGatewayResult.NeedsResolution -> {
                            if (admittedSession.store != StoreCapabilities.HUAWEI_STORE_ID) {
                                return@withTimeoutOrNull PurchaseCompletionResult.Failed(
                                    BillingFailureKind.InvalidResponse,
                                )
                            }
                            _billingPurchaseState.value =
                                BillingPurchaseUiState.AwaitingResolution
                            operation!!.beginAwaitingActivityResult()
                            if (!billingResolutionReplay.publish(
                                    BillingResolutionRequest(
                                        admittedSession.operationId,
                                        IntentSenderRequest.Builder(
                                            launchResult.pendingIntent.intentSender,
                                        ).build(),
                                    ),
                                )
                            ) {
                                operation.acceptLaunchFailure(admittedSession.operationId)
                            }
                            awaitAndApplyPurchaseTerminal(operation!!, admittedSession)
                        }
                        is BillingGatewayResult.Failure ->
                            PurchaseCompletionResult.Failed(launchResult.kind)
                        }
                    }
                if (result === null) {
                    operation?.acceptTimeout(session?.operationId)
                    abortPurchaseForWorkflow(
                        session,
                        PurchaseCompletionResult.Failed(BillingFailureKind.Network),
                    )
                    _billingPurchaseState.value =
                        BillingPurchaseUiState.Failed(BillingFailureKind.Network)
                } else {
                    _billingPurchaseState.value = billingPurchaseUiStateFor(result)
                }
            } catch (error: CancellationException) {
                operation?.acceptTimeout(session?.operationId)
                abortPurchaseForWorkflow(session, PurchaseCompletionResult.Cancelled)
                throw error
            } catch (error: Exception) {
                AppLogger.e(TAG, "Billing purchase flow failed: ${error.javaClass.simpleName}")
                abortPurchaseForWorkflow(
                    session,
                    PurchaseCompletionResult.Failed(BillingFailureKind.ProviderError),
                )
                _billingPurchaseState.value =
                    BillingPurchaseUiState.Failed(BillingFailureKind.ProviderError)
            } finally {
                abortPurchaseForWorkflow(session)
                synchronized(billingOperationLock) {
                    if (billingPurchaseOperation === operation) {
                        billingPurchaseOperation = null
                        billingPurchaseSession = null
                        _isPurchaseInFlight.value = false
                    }
                }
                clearBillingResolutionRequest()
                billingAdmission.release(BillingOperation.Purchase)
            }
        }
    }

    private suspend fun abortPurchaseForWorkflow(
        session: PurchaseLaunchSession?,
        result: PurchaseCompletionResult = PurchaseCompletionResult.Failed(
            BillingFailureKind.LaunchFailed,
        ),
    ) {
        if (session === null) return
        // Preserve a Huawei callback quarantine after timeout/abort. A late identity-less result
        // may only clear its own binding; it can never be rebound to a later purchase.
        billingCallbackBinding.invalidate(session.operationId)
        withContext(kotlinx.coroutines.NonCancellable) {
            entitlementRepository.abortPurchase(session, result)
        }
    }

    private suspend fun awaitAndApplyPurchaseCompletion(
        session: PurchaseLaunchSession,
    ): PurchaseCompletionResult = entitlementRepository.awaitPurchaseCompletion(session)

    private suspend fun awaitAndApplyPurchaseTerminal(
        operation: BillingActivityResultGate,
        session: PurchaseLaunchSession,
    ): PurchaseCompletionResult {
        return when (val terminal = operation.awaitActivityResult().await()) {
            is BillingPurchaseTerminal.ActivityResult -> {
                entitlementRepository.handlePurchaseActivityResult(
                    session,
                    session.operationId,
                    terminal.resultCode,
                    terminal.data,
                )
            }
            BillingPurchaseTerminal.LaunchFailed ->
                PurchaseCompletionResult.Failed(BillingFailureKind.LaunchFailed)
            BillingPurchaseTerminal.TimedOut ->
                PurchaseCompletionResult.Failed(BillingFailureKind.Network)
        }
    }

    private fun clearBillingResolutionRequest() {
        billingResolutionReplay.clear()
    }

    internal fun claimBillingResolutionLaunch(operationId: BillingOperationId): Boolean =
        billingResolutionReplay.claimLaunch(operationId)

    fun restorePurchase() {
        if (!capabilities.supportsBilling ||
            !billingAdmission.tryAcquire(BillingOperation.Restore)
        ) return
        _isRestoreInFlight.value = true
        billingRestoreJob = viewModelScope.launch {
            try {
                _billingPurchaseState.value = BillingPurchaseUiState.Preparing
                val result = entitlementRepository.restorePurchase()
                _billingPurchaseState.value = billingRestoreUiStateFor(result)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLogger.e(
                    TAG,
                    "Billing restore flow failed: ${error.javaClass.simpleName}",
                )
                _billingPurchaseState.value = BillingPurchaseUiState.Failed(
                    BillingFailureKind.ProviderError,
                )
            } finally {
                _isRestoreInFlight.value = false
                billingAdmission.release(BillingOperation.Restore)
            }
        }
    }

    internal fun onPurchaseActivityResult(
        operationId: BillingOperationId?,
        resultCode: Int,
        data: Intent?,
    ) {
        if (!capabilities.supportsBilling) return
        val operation = synchronized(billingOperationLock) { billingPurchaseOperation }
        operation?.acceptActivityResult(operationId, resultCode, data)
    }

    internal fun captureBillingCallback(
        operationId: BillingOperationId,
    ): BillingResolutionCallbackBinding.Captured? =
        billingCallbackBinding.capture(operationId)

    internal fun handoffBillingCallbackIfCurrent(
        captured: BillingResolutionCallbackBinding.Captured,
        handoff: () -> Unit,
    ): Boolean = billingCallbackBinding.handoffToProviderIfCurrent(captured, handoff)

    internal fun acceptBillingCallback(resultCode: Int, data: Intent?): Boolean =
        billingCallbackBinding.acceptCurrent { operationId ->
            onPurchaseActivityResult(operationId, resultCode, data)
        }

    internal fun releaseBillingCallback(
        captured: BillingResolutionCallbackBinding.Captured,
    ): Boolean = billingCallbackBinding.release(captured)

    /** Configuration-change seam; a live provider launch deliberately refuses this reset. */
    internal fun resetBillingCallbackForLifecycleRecreation(): Boolean =
        billingCallbackBinding.clear()

    /** Compatibility delegate retained until OptionsScreen adopts the new command name. */
    fun onBillingActivityResult(resultCode: Int, intent: Intent?) {
        // Legacy callers cannot prove operation identity; fail closed by design.
    }

    internal fun onBillingLaunchFailed(operationId: BillingOperationId?) {
        if (!capabilities.supportsBilling) return
        val operation = synchronized(billingOperationLock) { billingPurchaseOperation }
        if (operation == null || !operation.matches(operationId)) return
        if (operation.acceptLaunchFailure(operationId)) return
        if (operation.isTerminalAccepted()) return
        billingPurchaseJob?.cancel()
        _billingPurchaseState.value = BillingPurchaseUiState.Failed(BillingFailureKind.LaunchFailed)
    }

    @VisibleForTesting
    internal fun billingAdmissionForTest(): BillingOperationAdmission = billingAdmission

    /**
     * Options 언어 — per-app locale 콜드 시드.
     * DataStore Flow/stateIn 없음. LocaleManager / Resources 직접 읽기 금지.
     * [PerAppLocalesRead.Ready]일 때만 commit. NotReady는 policy A English가 아니며,
     * 메인 루퍼 다음 턴에 Application으로 LocaleManager를 1회 재조회한다 (`yield` 없음).
     */
    private val _languageOption = MutableStateFlow(LanguageOption.English)
    val languageOption: StateFlow<LanguageOption> = _languageOption.asStateFlow()

    /** True only after a [PerAppLocalesRead.Ready] commit (not the constructor placeholder). */
    private var languageSeedReady = false

    private val languageSeedMainHandler = Handler(Looper.getMainLooper())
    private val retryLanguageSeedOnce = Runnable {
        if (languageSeedReady || _isLanguageApplying.value) return@Runnable
        when (val second = SettingsRepository.currentLanguageOption(getApplication())) {
            is PerAppLocalesRead.Ready -> {
                _languageOption.value = second.option
                languageSeedReady = true
            }
            PerAppLocalesRead.NotReady -> {
                AppLogger.w(
                    TAG,
                    "Per-app locales not ready after retry; not committing English",
                )
            }
        }
    }

    init {
        when (val first = SettingsRepository.currentLanguageOption(application)) {
            is PerAppLocalesRead.Ready -> {
                _languageOption.value = first.option
                languageSeedReady = true
            }
            PerAppLocalesRead.NotReady -> {
                languageSeedMainHandler.post(retryLanguageSeedOnce)
            }
        }
    }

    /**
     * Language apply/restart in flight — SegmentedControl disable + double-tap guard.
     * Success path leaves true until restartApp destroys the VM host;
     * failure paths reset to false.
     */
    private val _isLanguageApplying = MutableStateFlow(false)
    val isLanguageApplying: StateFlow<Boolean> = _isLanguageApplying.asStateFlow()

    /**
     * Applies [option] via [SettingsRepository.applyLanguage] only when restart can proceed.
     *
     * Contract (same→false / other→true):
     * - same as current → `false` (no-op, no message)
     * - in-flight → `false` (ignore double-tap)
     * - [canRestart] false (Screen: Activity null / finishing / destroyed) → `false`,
     *   no apply, [language_picker_apply_failed] via [userMessage]
     * - apply throws → rollback StateFlow (+ best-effort locale restore), `false`, user message
     * - changed + [canRestart] true + apply ok → commit StateFlow, `true`
     *   (Screen **must** call restartApp immediately; on restart failure call
     *   [rollbackAfterRestartFailure])
     */
    fun setLanguage(option: LanguageOption, canRestart: Boolean): Boolean {
        if (languageSeedReady && option == _languageOption.value) return false
        if (!languageSeedReady && option == LanguageOption.English) return false
        if (_isLanguageApplying.value) return false
        if (!canRestart) {
            AppLogger.w(
                TAG,
                "setLanguage skipped: Activity null/finishing/destroyed (cannot restart)",
            )
            _userMessage.tryEmit(appString(R.string.language_picker_apply_failed))
            return false
        }
        val previous = _languageOption.value
        return try {
            _isLanguageApplying.value = true
            applyLanguageOrThrow(option)
            _languageOption.value = option
            languageSeedReady = true
            true
        } catch (e: Exception) {
            logWarning("Failed to set language", e)
            _languageOption.value = previous
            runCatching { applyLanguageOrThrow(previous) }
                .onFailure { rollbackEx ->
                    logWarning("Failed to rollback language after apply error", rollbackEx)
                }
            _isLanguageApplying.value = false
            _userMessage.tryEmit(appString(R.string.language_picker_apply_failed))
            false
        }
    }

    /**
     * Screen seam: restartApp startActivity failed after a successful [setLanguage]
     * (locale already applied). Restores [previous], clears applying so SegmentedControl
     * re-enables, emits [language_picker_apply_failed].
     * Screen is the sole [AppLogger.e] + throwable recorder for the restart failure.
     */
    fun rollbackAfterRestartFailure(previous: LanguageOption) {
        AppLogger.w(TAG, "restartApp failed after setLanguage; rolling back")
        _languageOption.value = previous
        runCatching { applyLanguageOrThrow(previous) }
            .onFailure { rollbackEx ->
                logWarning("Failed to rollback language after restart failure", rollbackEx)
            }
        _isLanguageApplying.value = false
        _userMessage.tryEmit(appString(R.string.language_picker_apply_failed))
    }

    private var applyLanguageHookForTest: ((LanguageOption) -> Unit)? = null

    private fun applyLanguageOrThrow(option: LanguageOption) {
        applyLanguageHookForTest?.invoke(option) ?: SettingsRepository.applyLanguage(option)
    }

    /**
     * Options 녹음 포맷 — process-wide [SettingsRepository.recordingFormatHot] 공유.
     * null = DataStore 미시드(SegmentedControl 비활성). Eager AAC placeholder 금지.
     */
    val recordingFormat: StateFlow<RecordingFormat?> = SettingsRepository.recordingFormatHot

    /**
     * Options 잡음 감소 모드 — process-wide [SettingsRepository.noiseReductionModeHot] 공유.
     * null = DataStore 미시드(SegmentedControl 비활성).
     */
    val noiseReductionMode: StateFlow<NoiseReductionMode?> = SettingsRepository.noiseReductionModeHot

    init {
        // DataStore → hot cache 시드 (Options/Record가 동일 companion을 본다)
        viewModelScope.launch {
            settingsRepository.recordingFormat.collect { }
        }
        viewModelScope.launch {
            settingsRepository.noiseReductionMode.collect { }
        }
    }

    fun setRecordingFormat(format: RecordingFormat) {
        viewModelScope.launch {
            try {
                // Repository가 hot cache optimistic 갱신 + 실패 시 롤백
                settingsRepository.setRecordingFormat(format)
            } catch (e: Exception) {
                logError("Failed to set recordingFormat", e)
                _userMessage.tryEmit(appString(R.string.options_settings_save_failed))
            }
        }
    }

    fun setNoiseReductionMode(mode: NoiseReductionMode) {
        viewModelScope.launch {
            try {
                settingsRepository.setNoiseReductionMode(mode)
            } catch (e: Exception) {
                logError("Failed to set noiseReductionMode", e)
                _userMessage.tryEmit(appString(R.string.options_settings_save_failed))
            }
        }
    }

    private val _isAuthorized by lazy {
        check(capabilities.supportsYouTube)
        MutableStateFlow(authManager.isAuthorized)
    }
    val isAuthorized: StateFlow<Boolean> by lazy { _isAuthorized.asStateFlow() }

    private val _channelTitle by lazy {
        check(capabilities.supportsYouTube)
        MutableStateFlow<String?>(authManager.cachedChannelTitle)
    }
    val channelTitle: StateFlow<String?> by lazy { _channelTitle.asStateFlow() }

    private val _youtubeAccountEmail by lazy {
        check(capabilities.supportsYouTube)
        MutableStateFlow<String?>(authManager.cachedAccountName)
    }
    val youtubeAccountEmail: StateFlow<String?> by lazy { _youtubeAccountEmail.asStateFlow() }

    private val _isChannelTitleLoading by lazy {
        check(capabilities.supportsYouTube)
        MutableStateFlow(false)
    }
    val isChannelTitleLoading: StateFlow<Boolean> by lazy { _isChannelTitleLoading.asStateFlow() }

    private val _channelTitleFetchFailed by lazy {
        check(capabilities.supportsYouTube)
        MutableStateFlow(false)
    }
    val channelTitleFetchFailed: StateFlow<Boolean> by lazy { _channelTitleFetchFailed.asStateFlow() }

    private var channelTitleFetchGeneration: Int = 0

    /** 단발성 동의 화면 요청: SharedFlow로 누락 없이 전달 (extraBufferCapacity=1). */
    private val _authorizationRequest by lazy {
        check(capabilities.supportsYouTube)
        MutableSharedFlow<IntentSenderRequest>(extraBufferCapacity = 1)
    }
    val authorizationRequest: SharedFlow<IntentSenderRequest> by lazy {
        _authorizationRequest.asSharedFlow()
    }

    /** Options YouTube Login → Android 시스템 계정 선택기 요청 (Screen이 Intent 생성). */
    private val _youtubeAccountPickerRequest by lazy {
        check(capabilities.supportsYouTube)
        MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    }
    val youtubeAccountPickerRequest: SharedFlow<Unit> by lazy {
        _youtubeAccountPickerRequest.asSharedFlow()
    }

    /**
     * 계정 선택기에서 고른 Account. Consent UI를 거친 뒤에도 [rememberAccountName]에 쓰기 위해 보관.
     * picker 직후 즉시 Authorized여도 동일.
     */
    private var pendingYouTubeAccount: Account? = null

    /** 에러·Wi-Fi constraint 힌트를 단일 스트림으로 직렬화 (Screen 단일 collector). */
    private val _userMessage = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    init {
        if (capabilities.supportsYouTube &&
            authManager.isAuthorized &&
            authManager.cachedChannelTitle == null
        ) {
            viewModelScope.launch { tryAuthorize(silent = true) }
        }
    }

    private fun commitPendingYouTubeAccountName() {
        pendingYouTubeAccount?.name?.takeIf { it.isNotBlank() }?.let { name ->
            authManager.rememberAccountName(name)
        }
        pendingYouTubeAccount = null
        _youtubeAccountEmail.value = authManager.cachedAccountName
    }

    /** Options YouTube Login 버튼 — 계정 선택기만 요청 (auth in-flight 없음). */
    fun requestYouTubeAccountPick() {
        if (!capabilities.supportsYouTube) return
        _youtubeAccountPickerRequest?.tryEmit(Unit)
    }

    /**
     * 계정 선택기 결과. null/취소 → Snackbar + pending clear.
     * non-null → pending 보관 후 [requestAuthorization] (AM-1).
     */
    fun onYouTubeAccountPicked(account: Account?) {
        if (!capabilities.supportsYouTube) return
        if (account == null || account.name.isBlank()) {
            pendingYouTubeAccount = null
            _userMessage.tryEmit(appString(R.string.youtube_account_pick_cancelled))
            return
        }
        pendingYouTubeAccount = account
        viewModelScope.launch {
            try {
                when (val outcome = authManager.requestAuthorization(account)) {
                    is AuthorizationOutcome.Authorized -> {
                        // YT-P3b: picker 직후 즉시 Authorized
                        commitPendingYouTubeAccountName()
                        onAuthorized(outcome.accessToken)
                    }
                    is AuthorizationOutcome.NeedsConsent -> {
                        // YT-P4: pending 유지 → consent 결과에서 YT-P3
                        val request =
                            IntentSenderRequest.Builder(outcome.pendingIntent.intentSender).build()
                        _authorizationRequest?.tryEmit(request)
                    }
                    is AuthorizationOutcome.Failed -> {
                        pendingYouTubeAccount = null
                        _userMessage.tryEmit(youTubeUserFacingMessage(outcome.message))
                    }
                }
            } catch (e: Exception) {
                logError("onYouTubeAccountPicked failed", e)
                pendingYouTubeAccount = null
                _userMessage.tryEmit(appString(R.string.youtube_auth_incomplete))
            }
        }
    }

    /**
     * YouTube 계층 raw 메시지(Korean 내부 리터럴)를 [youTubeFailureMessageToStringRes]로
     * 로케일에 맞는 문자열로 매핑한다. 매핑 실패/blank는 [R.string.youtube_auth_incomplete]
     * Fallback — 운영 빌드 스택 노출 방지. Drive [driveUserFacingMessage]와 동일 패턴, 공통 추출 금지.
     */
    private fun youTubeUserFacingMessage(raw: String?): String {
        val (resId, code) = youTubeFailureMessageToStringRes(raw)
        return if (code != null) appString(resId, code) else appString(resId)
    }

    /** SharedPreferences 기준 인증 상태를 StateFlow에 재동기화. */
    fun refreshAuthState() {
        if (!capabilities.supportsYouTube) return
        _isAuthorized.value = authManager.isAuthorized
        _channelTitle.value = authManager.cachedChannelTitle
        _youtubeAccountEmail.value = authManager.cachedAccountName
    }

    /** Rechecks the persisted read/write grant without exposing the stored URI. */
    fun refreshRecordingBackupFolderState() {
        recordingBackupFolderRefresh.value += 1
    }

    /**
     * Handles the ACTION_OPEN_DOCUMENT_TREE result off the main thread. The new URI reaches
     * DataStore only after [RecordingBackupFolder] has successfully persisted its grant.
     */
    fun onRecordingBackupFolderSelected(selectedTreeUri: Uri?) {
        viewModelScope.launch {
            val selectionResult = try {
                withContext(Dispatchers.IO) {
                    RecordingBackupFolder.handleSelection(
                        context = getApplication(),
                        selectedTreeUri = selectedTreeUri,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(
                    TAG,
                    "Recording backup selection failed: ${e.javaClass.simpleName}",
                )
                _userMessage.tryEmit(appString(R.string.options_recording_backup_selection_failed))
                refreshRecordingBackupFolderState()
                return@launch
            }

            when (selectionResult) {
                is RecordingBackupFolder.SelectionResult.Persisted -> {
                    try {
                        settingsMutex.withLock {
                            settingsRepository.setRecordingBackupFolderUri(selectionResult.uri)
                        }
                        refreshRecordingBackupFolderState()
                        reconcileRecordingBackupFolder(selectionResult.uri)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e(
                            TAG,
                            "Recording backup folder could not be saved: ${e.javaClass.simpleName}",
                        )
                        _userMessage.tryEmit(
                            appString(R.string.options_recording_backup_selection_failed),
                        )
                        refreshRecordingBackupFolderState()
                    }
                }

                is RecordingBackupFolder.SelectionResult.KeepExisting -> {
                    val messageRes = when (selectionResult.reason) {
                        RecordingBackupFolder.KeepExistingReason.Cancelled ->
                            R.string.options_recording_backup_selection_cancelled
                        RecordingBackupFolder.KeepExistingReason.InvalidTreeUri,
                        RecordingBackupFolder.KeepExistingReason.PersistPermissionFailed ->
                            R.string.options_recording_backup_selection_failed
                    }
                    _userMessage.tryEmit(appString(messageRes))
                    refreshRecordingBackupFolderState()
                }
            }
        }
    }

    /** Rehydrates the selected folder's manifest into local Room/files after reconnect. */
    private fun reconcileRecordingBackupFolder(storedUri: String) {
        viewModelScope.launch {
            recordingBackupReconciliationMutex.withLock {
                _isRecordingBackupReconciliationInFlight.value = true
                try {
                    val summary = withContext(Dispatchers.IO) {
                        RecordingBackupReconciler(getApplication()).reconcile(Uri.parse(storedUri))
                    }
                    _userMessage.tryEmit(
                        appString(
                            R.string.options_recording_backup_reconcile_complete,
                            summary.restoredActiveCount,
                            summary.restoredTrashCount,
                            summary.expiredTrashCount,
                        ),
                    )
                    if (summary.skippedCount > 0) {
                        AppLogger.w(
                            TAG,
                            "Recording backup reconciliation skipped ${summary.skippedCount} item(s)",
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e(
                        TAG,
                        "Recording backup reconciliation failed: ${e.javaClass.simpleName}",
                    )
                    _userMessage.tryEmit(
                        appString(R.string.options_recording_backup_reconcile_failed),
                    )
                } finally {
                    _isRecordingBackupReconciliationInFlight.value = false
                }
            }
        }
    }

    fun setWifiOnlyUpload(enabled: Boolean) {
        viewModelScope.launch {
            settingsMutex.withLock {
                try {
                    settingsRepository.setWifiOnlyUpload(enabled)
                    if (hasPendingConstrainedYouTubeUpload()) {
                        _userMessage.tryEmit(appString(R.string.options_pending_upload_constraint_hint))
                    }
                } catch (e: Exception) {
                    logError("Failed to set wifiOnlyUpload", e)
                    _userMessage.tryEmit(appString(R.string.options_settings_save_failed))
                }
            }
        }
    }

    /**
     * Drive 업로드 폴더 이름 저장.
     * trim 후 blank이면 no-op(다이얼로그가 사전 검증하지만 방어적으로).
     * [settingsMutex]로 다른 설정 저장과 동일하게 직렬화.
     * 저장 성공 후 즉시 [driveAuthManager.clearAppFolderId]를 호출해
     * 다음 업로드 시 새 이름으로 find-or-create가 재수행되도록 한다.
     */
    fun setDriveFolderName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            settingsMutex.withLock {
                try {
                    settingsRepository.setDriveFolderName(trimmed)
                    driveAuthManager.clearAppFolderId()
                } catch (e: Exception) {
                    logError("Failed to set driveFolderName", e)
                    _userMessage.tryEmit(appString(R.string.options_settings_save_failed))
                }
            }
        }
    }

    /**
     * YouTube 자동 변환·업로드 마스터 토글. OFF면 [RecordingAutoConvertTrigger] 전체 스킵.
     * [settingsMutex]로 Wi-Fi/Drive 토글과 동일 직렬화.
     */
    fun setYoutubeAutoUploadEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsMutex.withLock {
                try {
                    settingsRepository.setYoutubeAutoUploadEnabled(enabled)
                } catch (e: Exception) {
                    logError("Failed to set youtubeAutoUploadEnabled", e)
                    _userMessage.tryEmit(appString(R.string.options_settings_save_failed))
                }
            }
        }
    }

    fun setYoutubeDefaultVisibility(visibility: YoutubeDefaultVisibility) {
        viewModelScope.launch {
            settingsMutex.withLock {
                try {
                    settingsRepository.setYoutubeDefaultVisibility(visibility)
                } catch (e: Exception) {
                    logError("Failed to set youtubeDefaultVisibility", e)
                    _userMessage.tryEmit(appString(R.string.options_settings_save_failed))
                }
            }
        }
    }

    /**
     * Drive 자동 업로드 토글. DataStore 영속만 — pending-upload 힌트 없음.
     * 로그인 여부와 무관하게 조작 가능(Screen에서 enabled = != null).
     * [settingsMutex]로 Wi-Fi 토글과 동일 직렬화.
     */
    fun setDriveAutoUploadEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsMutex.withLock {
                try {
                    settingsRepository.setDriveAutoUploadEnabled(enabled)
                } catch (e: Exception) {
                    logError("Failed to set driveAutoUploadEnabled", e)
                    _userMessage.tryEmit(appString(R.string.options_settings_save_failed))
                }
            }
        }
    }

    /**
     * ENQUEUED/BLOCKED YouTube 업로드 unique work가 있으면 설정 변경 힌트가 필요.
     * WorkManager가 Worker 클래스명을 자동 태그로 붙이므로 KEY/Work 이름 변경 없이 조회.
     */
    private suspend fun hasPendingConstrainedYouTubeUpload(): Boolean = withContext(Dispatchers.IO) {
        val infos = runCatching {
            workManager.getWorkInfosByTag(YouTubeUploadWorker::class.java.name).get()
        }.getOrDefault(emptyList())
        infos.any { info -> info.state.isPendingConstrained() }
    }

    /**
     * 이미 동의한 사용자는 UI 없이 바로 토큰을 받는다(채널명 새로고침용으로도 쓰임). 최초 동의가 필요하면
     * [authorizationRequest]로 동의 화면 인텐트를 흘려보낸다.
     *
     * Options Login 버튼은 [requestYouTubeAccountPick]을 쓴다.
     * [silent]=false는 레거시/재시도 경로용이며 계정 선택기를 띄우지 않는다 (AM-2/AM-3).
     */
    fun tryAuthorize(silent: Boolean = false) {
        if (!capabilities.supportsYouTube) return
        // G-2: in-flight guard — silent 경로에서만 isChannelTitleLoading을 게이트로 사용.
        // 동시 silent 호출(UnconfinedTestDispatcher 등) 방어 + 세션 전환 중복 방지.
        if (silent) {
            if (_isChannelTitleLoading.value) return
            _isChannelTitleLoading.value = true  // 로딩 슬롯 선점 (requestAuthorization 전)
        }
        viewModelScope.launch {
            try {
                when (val outcome = authManager.requestAuthorization()) {
                    is AuthorizationOutcome.Authorized -> {
                        onAuthorized(outcome.accessToken)
                    }
                    is AuthorizationOutcome.NeedsConsent -> {
                        if (silent) {
                            _channelTitleFetchFailed.value = true
                            _isChannelTitleLoading.value = false  // onAuthorized 미진입이므로 해제
                            return@launch
                        }
                        val request = IntentSenderRequest.Builder(outcome.pendingIntent.intentSender).build()
                        _authorizationRequest?.tryEmit(request)
                    }
                    is AuthorizationOutcome.Failed -> {
                        if (silent) {
                            _channelTitleFetchFailed.value = true
                            _isChannelTitleLoading.value = false
                            return@launch
                        }
                        _userMessage.tryEmit(youTubeUserFacingMessage(outcome.message))
                    }
                }
            } catch (e: Exception) {
                logError("tryAuthorize failed", e)
                if (silent) {
                    _channelTitleFetchFailed.value = true
                    _isChannelTitleLoading.value = false
                }
            }
        }
    }

    /** [rememberLauncherForActivityResult]의 StartIntentSenderForResult 콜백에서 전달받은 결과 처리. */
    fun onAuthorizationActivityResult(intent: Intent?) {
        if (!capabilities.supportsYouTube) return
        if (intent == null) {
            pendingYouTubeAccount = null
            _userMessage.tryEmit(appString(R.string.youtube_login_cancelled))
            return
        }
        when (val outcome = authManager.resultFromIntent(intent)) {
            is AuthorizationOutcome.Authorized -> {
                // YT-P3: consent 결과 Authorized → pending 계정명 저장
                commitPendingYouTubeAccountName()
                onAuthorized(outcome.accessToken)
            }
            is AuthorizationOutcome.NeedsConsent -> {
                pendingYouTubeAccount = null
                _userMessage.tryEmit(appString(R.string.youtube_auth_incomplete))
            }
            is AuthorizationOutcome.Failed -> {
                pendingYouTubeAccount = null
                _userMessage.tryEmit(youTubeUserFacingMessage(outcome.message))
            }
        }
    }

    private fun onAuthorized(accessToken: String) {
        refreshAuthState()
        _isAuthorized.value = true
        _isChannelTitleLoading.value = true
        _channelTitleFetchFailed.value = false
        viewModelScope.launch { fetchChannelTitleWithRetry(accessToken) }
    }

    private suspend fun fetchChannelTitleWithRetry(accessToken: String) {
        val generationAtStart = channelTitleFetchGeneration
        try {
            val delayMs = longArrayOf(2_000L, 5_000L)
            for (attempt in 0 until 3) {
                when (val result = apiClient.fetchChannelTitle(accessToken)) {
                    is YouTubeApiResult.Success -> {
                        if (generationAtStart == channelTitleFetchGeneration) {
                            authManager.rememberChannelTitle(result.value)
                            refreshAuthState()
                        }
                        return
                    }
                    is YouTubeApiResult.Failure -> {
                        if (attempt < 2) delay(delayMs[attempt])
                    }
                }
            }
            if (generationAtStart == channelTitleFetchGeneration) _channelTitleFetchFailed.value = true
        } finally {
            if (generationAtStart == channelTitleFetchGeneration) _isChannelTitleLoading.value = false
        }
    }

    fun signOut() {
        if (!capabilities.supportsYouTube) return
        channelTitleFetchGeneration += 1
        _isChannelTitleLoading.value = false
        _channelTitleFetchFailed.value = false
        pendingYouTubeAccount = null
        authManager.signOut()
        refreshAuthState()
    }

    /**
     * 채널명 재조회. 토큰이 있으면 [fetchChannelTitleWithRetry]를 호출한다.
     * NeedsConsent면 사용자가 「다시 시도」를 눌렀으므로 동의 화면을 띄운다
     * (readonly 스코프 추가 등 재동의 필요 시 Try again이 고착되지 않게).
     */
    fun retryFetchChannelTitle() {
        if (!capabilities.supportsYouTube) return
        if (_isChannelTitleLoading.value) return
        _isChannelTitleLoading.value = true
        _channelTitleFetchFailed.value = false
        viewModelScope.launch {
            try {
                when (val outcome = authManager.requestAuthorization()) {
                    is AuthorizationOutcome.Authorized -> {
                        fetchChannelTitleWithRetry(outcome.accessToken)
                    }
                    is AuthorizationOutcome.NeedsConsent -> {
                        _isChannelTitleLoading.value = false
                        val request =
                            IntentSenderRequest.Builder(outcome.pendingIntent.intentSender).build()
                        _authorizationRequest?.tryEmit(request)
                    }
                    is AuthorizationOutcome.Failed -> {
                        _channelTitleFetchFailed.value = true
                        _isChannelTitleLoading.value = false
                    }
                }
            } catch (e: Exception) {
                logError("retryFetchChannelTitle failed", e)
                _channelTitleFetchFailed.value = true
                _isChannelTitleLoading.value = false
            }
        }
    }

    // ── Google Drive (YouTube와 이름·흐름 완전 분리) ─────────────────────────

    private val _isDriveAuthorized by lazy {
        check(capabilities.supportsDrive)
        MutableStateFlow(driveAuthManager.isAuthorized)
    }
    val isDriveAuthorized: StateFlow<Boolean> by lazy { _isDriveAuthorized.asStateFlow() }

    private val _driveAccountEmail by lazy {
        check(capabilities.supportsDrive)
        MutableStateFlow<String?>(driveAuthManager.cachedAccountEmail)
    }
    val driveAccountEmail: StateFlow<String?> by lazy { _driveAccountEmail.asStateFlow() }

    private val _driveStorageQuota by lazy {
        check(capabilities.supportsDrive)
        MutableStateFlow<DriveStorageQuota?>(null)
    }
    val driveStorageQuota: StateFlow<DriveStorageQuota?> by lazy {
        _driveStorageQuota.asStateFlow()
    }

    /** Options 재진입 레이스 — 새 [refreshDriveStorageQuota]가 이전 Job을 cancel. */
    private var driveStorageQuotaJob: Job? = null

    /**
     * Drive 로그인/동의/이메일 조회가 진행 중이면 true.
     * 로그인 버튼·[tryDriveAuthorize] 가드용 — 이중 탭·중첩 동의 방지.
     *
     * **상태 머신 A**: email 확정 전엔 authorized UI 금지 — 진행은 이 플래그로만 표시.
     * ([OptionsDriveAccountContent]는 `driveAccountEmail != null`일 때만 로그인됨 UI.)
     */
    private val _isDriveAuthInFlight by lazy {
        check(capabilities.supportsDrive)
        MutableStateFlow(false)
    }
    val isDriveAuthInFlight: StateFlow<Boolean> by lazy { _isDriveAuthInFlight.asStateFlow() }

    /**
     * signOut / launch-fail / cancel 과 [completeDriveAuthorization] Success 적용의 TOCTOU 차단용 세대.
     * [signOutDrive]·[onDriveAuthorizationLaunchFailed]·cancel 경로에서 증가.
     */
    private var driveAuthGeneration: Int = 0

    /** Drive 단발성 동의 화면 요청 — YouTube [authorizationRequest]와 별도. */
    private val _driveAuthorizationRequest by lazy {
        check(capabilities.supportsDrive)
        MutableSharedFlow<IntentSenderRequest>(extraBufferCapacity = 1)
    }
    val driveAuthorizationRequest: SharedFlow<IntentSenderRequest> by lazy {
        _driveAuthorizationRequest.asSharedFlow()
    }

    /** Options Drive Login → Android 시스템 계정 선택기 요청 (Screen이 Intent 생성). */
    private val _driveAccountPickerRequest by lazy {
        check(capabilities.supportsDrive)
        MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    }
    val driveAccountPickerRequest: SharedFlow<Unit> by lazy {
        _driveAccountPickerRequest.asSharedFlow()
    }

    /**
     * Drive 계층 raw 메시지(Korean 내부 리터럴)를 [driveFailureMessageToStringRes]로 로케일에
     * 맞는 문자열로 매핑한다. 매핑 실패/blank는 [R.string.drive_auth_incomplete] Fallback.
     */
    private fun driveUserFacingMessage(raw: String?): String {
        val (resId, code) = driveFailureMessageToStringRes(raw)
        return if (code != null) appString(resId, code) else appString(resId)
    }

    /** SharedPreferences 기준 Drive 인증 상태를 StateFlow에 재동기화. about GET 없음. */
    fun refreshDriveAuthState() {
        if (!capabilities.supportsDrive) return
        _isDriveAuthorized.value = driveAuthManager.isAuthorized
        _driveAccountEmail.value = driveAuthManager.cachedAccountEmail
    }

    /**
     * Options 진입·재진입 quota 갱신. silent Drive auth gateway authorization
     * (account=null)는 **이미 로그인된 세션**만. prefs 미로그인(`!isAuthorized` 또는 email null)이면
     * requestAuthorization / about GET 스킵, quota null 유지.
     * NeedsConsent/Failed → quota null, 동의 UI·로그아웃·Snackbar 없음.
     * 대입 전 [driveAuthGeneration] + [isActive] 가드 — signOut 후 stale Success 금지.
     */
    fun refreshDriveStorageQuota() {
        if (!capabilities.supportsDrive) return
        driveStorageQuotaJob?.cancel()
        if (!driveAuthManager.isAuthorized || driveAuthManager.cachedAccountEmail == null) {
            _driveStorageQuota.value = null
            driveStorageQuotaJob = null
            return
        }
        driveStorageQuotaJob = viewModelScope.launch {
            val generationAtStart = driveAuthGeneration
            try {
                when (val outcome = requestDriveAuthorization()) {
                    is DriveAuthorizationOutcome.Authorized -> {
                        enqueueOrApplyFetchedQuota(
                            generationAtStart = generationAtStart,
                            accessToken = outcome.accessToken,
                            alreadyOnQuotaJob = true,
                        )
                    }
                    is DriveAuthorizationOutcome.NeedsConsent,
                    is DriveAuthorizationOutcome.Failed -> {
                        publishDriveStorageQuota(generationAtStart, null)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                logError("refreshDriveStorageQuota failed", e)
                publishDriveStorageQuota(generationAtStart, null)
            }
        }
    }

    private suspend fun requestDriveAuthorization(account: Account? = null): DriveAuthorizationOutcome =
        requestDriveAuthorizationHookForTest?.invoke(account)
            ?: driveAuthManager.requestAuthorization(account)

    private suspend fun fetchDriveStorageQuota(accessToken: String): DriveApiResult<DriveStorageQuota> =
        fetchStorageQuotaHookForTest?.invoke(accessToken)
            ?: driveApiClient.fetchStorageQuota(accessToken)

    private suspend fun fetchDriveAccountEmail(accessToken: String): DriveApiResult<String> =
        fetchAccountEmailHookForTest?.invoke(accessToken)
            ?: driveApiClient.fetchAccountEmail(accessToken)

    /**
     * Login-path quota fetch shares [driveStorageQuotaJob] with [refreshDriveStorageQuota].
     * [alreadyOnQuotaJob]=true when the caller *is* that job (refresh); false launches a new job.
     */
    private suspend fun enqueueOrApplyFetchedQuota(
        generationAtStart: Int,
        accessToken: String,
        alreadyOnQuotaJob: Boolean,
    ) {
        if (alreadyOnQuotaJob) {
            applyFetchedDriveStorageQuota(generationAtStart, accessToken)
            return
        }
        driveStorageQuotaJob?.cancel()
        driveStorageQuotaJob = viewModelScope.launch {
            try {
                applyFetchedDriveStorageQuota(generationAtStart, accessToken)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                logError("fetchStorageQuota after login failed", e)
                publishDriveStorageQuota(generationAtStart, null)
            }
        }
    }

    private suspend fun applyFetchedDriveStorageQuota(
        generationAtStart: Int,
        accessToken: String,
    ) {
        if (!coroutineContext.isActive || generationAtStart != driveAuthGeneration) return
        when (val result = fetchDriveStorageQuota(accessToken)) {
            is DriveApiResult.Success ->
                publishDriveStorageQuota(generationAtStart, result.value)
            is DriveApiResult.Failure ->
                publishDriveStorageQuota(generationAtStart, null)
        }
    }

    private fun publishDriveStorageQuota(generationAtStart: Int, quota: DriveStorageQuota?) {
        val job = driveStorageQuotaJob
        if (generationAtStart != driveAuthGeneration) return
        if (job != null && !job.isActive) return
        _driveStorageQuota.value = quota
    }

    /**
     * Options Drive Login — 계정 선택기만 요청.
     * picker 표시 중 [isDriveAuthInFlight]는 false 유지 (F4).
     */
    fun requestDriveAccountPick() {
        if (!capabilities.supportsDrive) return
        if (_isDriveAuthInFlight.value) return
        _driveAccountPickerRequest?.tryEmit(Unit)
    }

    /**
     * Drive 계정 선택기 결과.
     * null/취소 → Snackbar, in-flight false 유지.
     * non-null → [authorizeDriveWithAccount] (이때부터 in-flight true).
     */
    fun onDriveAccountPicked(account: Account?) {
        if (!capabilities.supportsDrive) return
        if (account == null || account.name.isBlank()) {
            _userMessage.tryEmit(appString(R.string.drive_account_pick_cancelled))
            return
        }
        authorizeDriveWithAccount(account)
    }

    /**
     * Options 로그인 진입점 — 계정 선택기를 연다 ([requestDriveAccountPick]).
     * 실제 authorize는 [onDriveAccountPicked] 이후.
     */
    fun tryDriveAuthorize() {
        if (!capabilities.supportsDrive) return
        requestDriveAccountPick()
    }

    /**
     * [account]로 Drive authorize를 시작한다. 호출 시점에 [isDriveAuthInFlight]=true.
     * NeedsConsent emit 성공 시에는 in-flight를 **유지**(동의 Activity 결과까지).
     * Authorized → [completeDriveAuthorization] finally에서 해제.
     */
    private fun authorizeDriveWithAccount(account: Account) {
        if (_isDriveAuthInFlight.value) return
        viewModelScope.launch {
            if (_isDriveAuthInFlight.value) return@launch
            _isDriveAuthInFlight.value = true
            try {
                when (val outcome = requestDriveAuthorization(account)) {
                    is DriveAuthorizationOutcome.Authorized -> {
                        completeDriveAuthorization(outcome.accessToken)
                    }
                    is DriveAuthorizationOutcome.NeedsConsent -> {
                        val request =
                            IntentSenderRequest.Builder(outcome.pendingIntent.intentSender).build()
                        if (_driveAuthorizationRequest?.tryEmit(request) != true) {
                            AppLogger.w(TAG, "driveAuthorizationRequest drop: buffer full")
                            _userMessage.tryEmit(appString(R.string.drive_auth_incomplete))
                            _isDriveAuthInFlight.value = false
                        }
                        // emit 성공 시 동의 Activity 결과까지 in-flight 유지
                    }
                    is DriveAuthorizationOutcome.Failed -> {
                        _userMessage.tryEmit(driveUserFacingMessage(outcome.message))
                        _isDriveAuthInFlight.value = false
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                logError("authorizeDriveWithAccount failed", e)
                _userMessage.tryEmit(appString(R.string.drive_auth_incomplete))
                _isDriveAuthInFlight.value = false
            }
        }
    }

    /**
     * Drive StartIntentSenderForResult 콜백.
     * RESULT_CANCELED 및 non-OK → [R.string.drive_login_cancelled].
     */
    fun onDriveAuthorizationActivityResult(resultCode: Int, intent: Intent?) {
        if (!capabilities.supportsDrive) return
        if (resultCode != Activity.RESULT_OK) {
            driveAuthGeneration += 1
            _userMessage.tryEmit(appString(R.string.drive_login_cancelled))
            _isDriveAuthInFlight.value = false
            return
        }
        if (intent == null) {
            driveAuthGeneration += 1
            _userMessage.tryEmit(appString(R.string.drive_login_cancelled))
            _isDriveAuthInFlight.value = false
            return
        }
        when (val outcome = driveAuthManager.resultFromIntent(intent)) {
            is DriveAuthorizationOutcome.Authorized ->
                viewModelScope.launch { completeDriveAuthorization(outcome.accessToken) }
            is DriveAuthorizationOutcome.NeedsConsent -> {
                driveAuthGeneration += 1
                _userMessage.tryEmit(appString(R.string.drive_auth_incomplete))
                _isDriveAuthInFlight.value = false
            }
            is DriveAuthorizationOutcome.Failed -> {
                driveAuthGeneration += 1
                _userMessage.tryEmit(driveUserFacingMessage(outcome.message))
                _isDriveAuthInFlight.value = false
            }
        }
    }

    /**
     * Screen에서 [driveAuthorizationLauncher.launch] 가 예외로 실패한 경우의 seam.
     * in-flight 해제 + 사용자 Fallback (drive 패키지 수정 없이 Options 측).
     */
    fun onDriveAuthorizationLaunchFailed() {
        if (!capabilities.supportsDrive) return
        driveAuthGeneration += 1
        _isDriveAuthInFlight.value = false
        AppLogger.w(TAG, "driveAuthorizationLauncher.launch failed")
        _userMessage.tryEmit(appString(R.string.drive_auth_incomplete))
    }

    /**
     * 토큰 확보 후 계정 이메일 조회.
     * **상태 머신 A**: email Success 전까지 `_isDriveAuthorized` UI 강제 true 금지 —
     * 진행은 [isDriveAuthInFlight]만. Failure 시 [signOutDrive]로 prefs 롤백.
     * Success 적용 전 [driveAuthGeneration] 가드로 signOut TOCTOU 차단.
     * 이메일 Success 후 동일 [accessToken]으로 [GoogleDriveApiClient.fetchStorageQuota]
     * (silent [requestAuthorization] 재호출 금지). 조회 실패 → quota null, 로그인 유지.
     */
    private suspend fun completeDriveAuthorization(accessToken: String) {
        val generationAtStart = driveAuthGeneration
        try {
            when (val result = fetchDriveAccountEmail(accessToken)) {
                is DriveApiResult.Success -> {
                    if (generationAtStart != driveAuthGeneration) {
                        AppLogger.w(TAG, "completeDriveAuthorization Success dropped: generation mismatch")
                        return
                    }
                    driveAuthManager.rememberAccountEmail(result.value)
                    refreshDriveAuthState()
                    enqueueOrApplyFetchedQuota(
                        generationAtStart = generationAtStart,
                        accessToken = accessToken,
                        alreadyOnQuotaJob = false,
                    )
                }
                is DriveApiResult.Failure -> {
                    if (generationAtStart != driveAuthGeneration) return
                    signOutDrive()
                    _userMessage.tryEmit(driveUserFacingMessage(result.message))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logError("completeDriveAuthorization failed", e)
            if (generationAtStart == driveAuthGeneration) {
                signOutDrive()
                _userMessage.tryEmit(appString(R.string.drive_auth_incomplete))
            }
        } finally {
            if (generationAtStart == driveAuthGeneration) {
                _isDriveAuthInFlight.value = false
            }
        }
    }

    /**
     * Drive 로그아웃. in-flight 중에도 호출 가능 — 세대 증가로 진행 중 Success 적용을 무효화하고
     * [_isDriveAuthInFlight]를 즉시 false로 내린다.
     */
    fun signOutDrive() {
        if (!capabilities.supportsDrive) return
        driveAuthGeneration += 1
        _isDriveAuthInFlight.value = false
        driveStorageQuotaJob?.cancel()
        driveStorageQuotaJob = null
        driveAuthManager.signOut()
        _driveStorageQuota.value = null
        refreshDriveAuthState()
    }

    /** androidTest: in-flight 시드 (프로덕션 호출 금지). */
    internal fun markDriveAuthInFlightForTest() {
        _isDriveAuthInFlight.value = true
    }

    private var requestDriveAuthorizationHookForTest:
        (suspend (Account?) -> DriveAuthorizationOutcome)? = null
    private var fetchStorageQuotaHookForTest:
        (suspend (String) -> DriveApiResult<DriveStorageQuota>)? = null
    private var fetchAccountEmailHookForTest:
        (suspend (String) -> DriveApiResult<String>)? = null

    /** androidTest: Drive quota/auth 더블 (프로덕션 호출 금지). AuthManager 무변경. */
    @VisibleForTesting
    internal fun installDriveQuotaSeamsForTest(
        requestAuthorization: (suspend (Account?) -> DriveAuthorizationOutcome)? = null,
        fetchStorageQuota: (suspend (String) -> DriveApiResult<DriveStorageQuota>)? = null,
        fetchAccountEmail: (suspend (String) -> DriveApiResult<String>)? = null,
    ) {
        requestDriveAuthorizationHookForTest = requestAuthorization
        fetchStorageQuotaHookForTest = fetchStorageQuota
        fetchAccountEmailHookForTest = fetchAccountEmail
    }

    /** androidTest: applyLanguage 대체 (프로덕션 호출 금지). */
    @VisibleForTesting
    internal fun applyLanguageForTest(block: (LanguageOption) -> Unit) {
        applyLanguageHookForTest = block
    }

    val desktopSyncServerState: StateFlow<ServerState>
        get() = synchronized(desktopSyncSeamLock) {
            try {
                desktopSyncSeams.serverState()
            } catch (e: Exception) {
                desktopSyncStartGuardActive = false
                logError("Failed to read desktop sync server state", e)
                desktopSyncStoppedFallback
            }
        }

    val desktopSyncIsPaired: StateFlow<Boolean> = desktopSyncController.isPaired
    val desktopSyncPairedDeviceName: StateFlow<String?> = desktopSyncController.pairedDeviceName

    fun startDesktopSync() {
        val startServer: () -> Unit
        synchronized(desktopSyncSeamLock) {
            val currentState = try {
                desktopSyncSeams.serverState().value
            } catch (e: Exception) {
                desktopSyncStartGuardActive = false
                logError("Failed to read desktop sync server state", e)
                return
            }
            if (currentState is ServerState.Running || currentState is ServerState.Starting) {
                desktopSyncStartGuardActive = true
                return
            }

            if (desktopSyncStartGuardActive) {
                val terminalAfterStarting = currentState is ServerState.Stopped ||
                    currentState is ServerState.Failed
                if (!(terminalAfterStarting && desktopSyncObservedState != currentState)) return
                desktopSyncStartGuardActive = false
            }

            if (currentState is ServerState.Failed) {
                desktopSyncStartGuardActive = false
            }
            desktopSyncStartGuardActive = true
            startServer = desktopSyncSeams.startServer
        }

        try {
            startServer()
        } catch (e: CancellationException) {
            synchronized(desktopSyncSeamLock) {
                desktopSyncStartGuardActive = false
            }
            throw e
        } catch (e: Exception) {
            synchronized(desktopSyncSeamLock) {
                desktopSyncStartGuardActive = false
            }
            logError("Failed to start desktop sync", e)
        }
    }

    fun stopDesktopSync() {
        synchronized(desktopSyncSeamLock) {
            desktopSyncStartGuardActive = false
        }
        desktopSyncController.stopServer()
    }

    @VisibleForTesting
    internal fun installDesktopSyncSeamsForTest(
        startServer: (() -> Unit)? = null,
        serverState: (() -> StateFlow<ServerState>)? = null,
    ) {
        synchronized(desktopSyncSeamLock) {
            desktopSyncSeams = DesktopSyncSeams(
                startServer = startServer ?: desktopSyncController::startServer,
                serverState = serverState ?: { desktopSyncController.serverState },
            )
            desktopSyncStartGuardActive = false
            desktopSyncObservedState = null
        }
        restartDesktopSyncStateObservation()
    }

    fun unpairDesktop() = desktopSyncController.unpair()

    @VisibleForTesting
    internal fun clearForTest() {
        onCleared()
    }

    override fun onCleared() {
        if (!cleared.compareAndSet(false, true)) return
        val purchaseSession = synchronized(billingOperationLock) {
            billingPurchaseOperation?.acceptTimeout(billingPurchaseSession?.operationId)
            billingPurchaseSession
        }
        if (purchaseSession !== null) {
            billingCallbackBinding.invalidate(purchaseSession.operationId)
            entitlementRepository.abortPurchaseImmediately(
                purchaseSession,
                PurchaseCompletionResult.Cancelled,
            )
        }
        billingPurchaseJob?.cancel()
        billingRestoreJob?.cancel()
        synchronized(billingOperationLock) {
            billingPurchaseOperation = null
            billingPurchaseSession = null
            _isPurchaseInFlight.value = false
        }
        _isRestoreInFlight.value = false
        billingResolutionReplay.clear()
        billingCallbackBinding.clear()
        billingAdmission.release(BillingOperation.Purchase)
        billingAdmission.release(BillingOperation.Restore)
        driveStorageQuotaJob?.cancel()
        driveStorageQuotaJob = null
        _isRecordingBackupReconciliationInFlight.value = false
        languageSeedMainHandler.removeCallbacks(retryLanguageSeedOnce)
        synchronized(desktopSyncSeamLock) {
            desktopSyncStartGuardActive = false
            desktopSyncStateObservationJob?.cancel()
            desktopSyncStateObservationJob = null
        }
        super.onCleared()
    }

    private fun restartDesktopSyncStateObservation() {
        val stateFlow = synchronized(desktopSyncSeamLock) {
            desktopSyncStateObservationJob?.cancel()
            desktopSyncStateObservationJob = null
            try {
                desktopSyncSeams.serverState().also { flow ->
                    val current = flow.value
                    desktopSyncObservedState = current
                    desktopSyncStartGuardActive = current is ServerState.Starting ||
                        current is ServerState.Running
                }
            } catch (e: Exception) {
                desktopSyncObservedState = null
                desktopSyncStartGuardActive = false
                logError("Failed to observe desktop sync server state", e)
                null
            }
        } ?: return

        desktopSyncStateObservationJob = viewModelScope.launch {
            try {
                stateFlow.collect { state ->
                    synchronized(desktopSyncSeamLock) {
                        val previous = desktopSyncObservedState
                        desktopSyncObservedState = state
                        if (previous != null && previous != state &&
                            (state is ServerState.Stopped || state is ServerState.Failed)
                        ) {
                            desktopSyncStartGuardActive = false
                        }
                        if (state is ServerState.Starting || state is ServerState.Running) {
                            desktopSyncStartGuardActive = true
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                synchronized(desktopSyncSeamLock) {
                    desktopSyncStartGuardActive = false
                }
                logError("Failed to observe desktop sync server state", e)
            }
        }
    }
}
