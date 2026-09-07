package com.example.convert2video.record

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import com.example.convert2video.R
import com.example.convert2video.data.AppDatabase
import com.example.convert2video.data.RecordingRecord
import com.example.convert2video.data.RecordingRepository
import com.example.convert2video.data.SettingsRepository
import com.example.convert2video.data.TrashRepository
import com.example.convert2video.data.TrashedItem
import com.example.convert2video.drive.enqueueDriveAutoUploadIfEnabled
import com.example.convert2video.store.StoreCapabilities
import com.example.convert2video.utils.AppLogger
import com.example.convert2video.video.enqueueAutoConvertIfEnabled
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

private const val RECORDING_SERVICE_POST_SAVE_TAG = "RecordingService"

internal fun shouldEnqueueDriveAutoUpload(capabilities: StoreCapabilities): Boolean =
    capabilities.supportsDrive

/** Production post-save Drive/conversion branch with callback seams for JVM tests. */
internal suspend fun enqueueRecordingPostSaveBranches(
    capabilities: StoreCapabilities,
    enqueueDriveAutoUpload: suspend () -> Unit,
    enqueueAutoConvert: suspend () -> Unit,
) {
    if (shouldEnqueueDriveAutoUpload(capabilities)) {
        try {
            enqueueDriveAutoUpload()
        } catch (ce: CancellationException) {
            AppLogger.w(
                RECORDING_SERVICE_POST_SAVE_TAG,
                "keep drive enqueue cancelled after index: ${ce.message}",
            )
        } catch (e: Exception) {
            AppLogger.e(
                RECORDING_SERVICE_POST_SAVE_TAG,
                "keep drive enqueue failed: ${e.javaClass.simpleName}",
                e,
            )
        }
    }
    try {
        enqueueAutoConvert()
    } catch (ce: CancellationException) {
        AppLogger.w(
            RECORDING_SERVICE_POST_SAVE_TAG,
            "keep convert enqueue cancelled after index: ${ce.message}",
        )
    } catch (e: Exception) {
        AppLogger.e(
            RECORDING_SERVICE_POST_SAVE_TAG,
            "keep convert enqueue failed: ${e.javaClass.simpleName}",
            e,
        )
    }
}

/**
 * 포그라운드 녹음 Service.
 * bind([LocalBinder]) + startCommand(ACTION_*) 이중 모드.
 * Room insert는 Keep 이후에만. STOP(>5s)은 [RecordingState.Review]만 노출한다.
 *
 * ## FGS 수명 구간
 * - [RecordingState.Recording] / [RecordingState.Paused] / [RecordingState.Stopping]
 *   → 마이크 포그라운드(startForeground) 유지. [isSessionActive] true.
 * - [RecordingState.Review]
 *   → **checkpoint 전략**(마이크 FGS 유지 아님): DETACH + ongoing Review 알림.
 *   occupancy([isSessionActive])는 Service가 살아있는 동안 true.
 *   Tile [isActiveRecordingSession]은 Review에서 false — occupancy와 접지 금지.
 *   onDestroy(Review pending)는 occupancy를 조용히 버리지 않고 Controller를
 *   recoverable Idle로 수렴한다. 파일은 삭제하지 않는다(file guard).
 *   Keep in-flight는 occupancy claim + dataSync [startForeground]로 IO를 보호한다.
 *   (매니페스트 `microphone|dataSync`; 신규 dangerous permission 없음.)
 * - [RecordingState.Saved] / [RecordingState.Failed] / Discard Idle
 *   → stopForeground(REMOVE) + stopSelf. [isSessionActive] false.
 *
 * Saved/Failed/Review에서 ACTION_STOP은 no-op — Idle로 강제하지 않는다.
 * Idle + pendingReview 없음 KEEP/DISCARD는 true no-op (stopSelf 금지).
 *
 * ## Call pause STOP 정책
 * C2 Quick Timer: 통화 pause면 countdown STOP cancel, resume 시 남은 실제 녹음 시간으로 재등록.
 * C3 Once/Weekly/Daily: AudioFocus pause/resume은 동일하되 RTC STOP은 그대로 둔다.
 */
class RecordingService : Service() {

    private val binder = LocalBinder()
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(
        serviceJob + Dispatchers.Main.immediate +
            recordingScopeExceptionHandler(TAG),
    )

    /**
     * androidTest — serviceScope CEH(log-only) 경로 발사. Main에서 호출.
     * onFailed=null 이므로 state를 Failed로 바꾸지 않아야 한다.
     */
    @VisibleForTesting
    internal fun launchUncaughtForTest(throwable: Throwable) {
        serviceScope.launch { throw throwable }
    }

    private var engine: RecordingEngine? = null
    private var engineObserveJob: Job? = null
    private var isFinishingStop: Boolean = false
    private var isFinishingReviewAction: Boolean = false
    /** STOP>5s 후 Keep/Discard용. Engine 해제 뒤에도 파일·포맷을 유지. */
    private var pendingReview: RecordingResult? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var audioManager: AudioManager? = null
    private var audioFocusMonitor: CallAudioFocusMonitor? = null
    /**
     * true only while paused by AudioFocus LOSS/TRANSIENT.
     * User [handlePauseResume] pause is always false; user resume clears it.
     * Do not abandon focus while this is true and still Paused — GAIN must arrive.
     */
    private var pausedByCallDetection: Boolean = false
    /**
     * C2: 통화 pause로 countdown STOP을 거둬 둔 뒤 resume에서 남은 시간으로 재등록.
     * C3 예약 RTC STOP은 이 플래그를 쓰지 않는다.
     */
    private var countdownStopHeldForCall: Boolean = false
    private var deferredCountdownDurationMinutes: Int? = null
    /** Failed FG 성공 후 bind가 state를 읽을 때까지 stopSelf를 미룬다. FG 미진입은 즉시 stop. */
    private var stopForegroundBeforeDelayedStop: Boolean = false
    private val delayedStopSelfRunnable = Runnable {
        if (stopForegroundBeforeDelayedStop) {
            stopForegroundCompat(removeNotification = true)
            stopForegroundBeforeDelayedStop = false
        }
        synchronized(SESSION_LOCK) { isSessionActive = false }
        stopSelf()
    }

    /**
     * Controller [RecordingController]가 발급한 세션 id.
     * ACTION_START의 [EXTRA_SESSION_ID]로 설정. 새 인스턴스/미시작은 0.
     */
    @Volatile
    var sessionId: Long = 0L
        private set

    private val repository: RecordingRepository by lazy {
        RecordingRepository(
            applicationContext,
            AppDatabase.getInstance(applicationContext).recordingDao(),
        )
    }

    private val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(applicationContext)
    }

    private val workManager: WorkManager by lazy {
        WorkManager.getInstance(applicationContext)
    }

    /** androidTest — [discardReviewToTrash] TrashRepository 대체. null이면 매번 create. */
    @VisibleForTesting
    internal var trashRepositoryForTest: TrashRepository? = null

    private fun resolveTrashRepository(): TrashRepository =
        trashRepositoryForTest ?: TrashRepository.create(applicationContext)

    inner class LocalBinder : Binder() {
        val service: RecordingService get() = this@RecordingService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_PAUSE_RESUME -> handlePauseResume()
            ACTION_STOP -> handleStop()
            ACTION_KEEP -> handleKeep(intent)
            ACTION_DISCARD -> handleDiscard(intent)
            else -> AppLogger.w(TAG, "unknown action=${intent?.action}")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // G4: Review pending이면 occupancy를 침묵 드롭하지 않는다.
        // checkpoint = 파일 유지 + Controller recoverable Idle. Keep IO는 dataSync FGS.
        val reviewPending = _state.value is RecordingState.Review || pendingReview != null
        mainHandler.removeCallbacks(delayedStopSelfRunnable)
        stopForegroundBeforeDelayedStop = false
        abandonAudioFocusAndClearCallPauseFlag()
        serviceJob.cancel()
        engineObserveJob = null
        runCatching { engine?.release() }
        engine = null
        synchronized(SESSION_LOCK) {
            isSessionActive = false
        }
        isFinishingStop = false
        isFinishingReviewAction = false
        pendingReview = null
        super.onDestroy()
        if (reviewPending) {
            RecordingController.notifyServiceDestroyedWhileReview()
        }
    }

    private fun handleStart(intent: Intent) {
        if (_state.value is RecordingState.Review) {
            AppLogger.w(TAG, "START ignored — review pending")
            return
        }
        val incomingSessionId = intent.getLongExtra(EXTRA_SESSION_ID, 0L)
        // SESSION_LOCK: busy면 ignore; 아니면 원자 claim — deny bind 창·이중 engine 방지.
        synchronized(SESSION_LOCK) {
            if (shouldIgnoreStartWhileSessionBusy(isSessionActive)) {
                AppLogger.w(TAG, "session already active or deny bind window — ignore START")
                return
            }
            isSessionActive = true
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Failed emit만 새 sessionId — sticky merge 매칭용.
            // Controller RECORD_AUDIO 선검사가 예방; Service 도달 시 FG 재시도로 타임아웃을 "해결"하지 않는다.
            // FG 성공 시에만 bind 창 지연 demote, FG 실패면 즉시 stopSelf.
            sessionId = incomingSessionId
            AppLogger.w(TAG, "RECORD_AUDIO not granted — Failed without engine")
            finishDeniedWithoutEngine(RecordingErrorCodes.PERMISSION_DENIED, attemptForeground = true)
            return
        }
        val formatName = intent.getStringExtra(EXTRA_FORMAT) ?: RecordingFormat.AAC.name
        val format = runCatching { RecordingFormat.valueOf(formatName) }.getOrElse {
            AppLogger.e(TAG, "invalid format=$formatName")
            sessionId = incomingSessionId
            finishDeniedWithoutEngine(RecordingErrorCodes.START_FAILED, attemptForeground = true)
            return
        }
        sessionId = incomingSessionId
        ensureChannel()
        val initialRecording = RecordingState.Recording(elapsedMs = 0L, amplitude = 0)
        if (!startAsForeground(notificationFor(initialRecording))) {
            AppLogger.e(TAG, "startAsForeground denied — Failed without engine, immediate stopSelf")
            _state.value = RecordingState.Failed(RecordingErrorCodes.FOREGROUND_START_DENIED)
            publishNotification(_state.value)
            synchronized(SESSION_LOCK) { isSessionActive = false }
            stopSelfImmediately(stopForegroundFirst = false)
            return
        }
        val created = RecordingEngine.create(applicationContext)
        engine = created
        observeEngine(created)
        val ok = created.start(format)
        if (!ok) {
            val failed = created.state.value
            val code = (failed as? RecordingState.Failed)?.errorCode
                ?: RecordingErrorCodes.START_FAILED
            _state.value = RecordingState.Failed(code)
            publishNotification(_state.value)
            cleanupSessionWithoutInsert()
            return
        }
        val recording = created.state.value as? RecordingState.Recording ?: initialRecording
        _state.value = recording
        publishNotification(recording)
        requestAudioFocusAfterEngineStart()
    }

    /**
     * 엔진 없이 단말 Failed. 호출 전 [isSessionActive]는 이미 claim된 상태.
     *
     * - [attemptForeground]가 **성공**한 경우만: Failed FG 알림 유지 → bind 창 후 demote+stopSelf.
     *   (시스템이 startForeground를 이미 수락했으므로 타임아웃이 뜨지 않음.)
     *   deny bind 창 동안 busy 유지 → 재START ignore.
     * - FG **미진입**: tray notify + **즉시** stopSelf. 지연 금지, busy 해제.
     *   PERMISSION_DENIED에서 microphone FG 재시도가 타임아웃을 해결한다고 주장하지 않는다 —
     *   Controller 선검사가 예방, Service는 Failed 노출 후 즉시/지연 종료만 한다.
     */
    private fun finishDeniedWithoutEngine(errorCode: String, attemptForeground: Boolean) {
        val failed = RecordingState.Failed(errorCode)
        _state.value = failed
        ensureChannel()
        var enteredForeground = false
        if (attemptForeground) {
            enteredForeground = startAsForeground(notificationFor(failed))
        }
        if (shouldScheduleDelayedStopSelf(enteredForeground)) {
            // FG 성공 — busy 유지(재START ignore), bind 창 후 demote.
            scheduleDelayedStopSelf(stopForegroundFirst = true)
        } else {
            publishNotification(failed)
            synchronized(SESSION_LOCK) { isSessionActive = false }
            stopSelfImmediately(stopForegroundFirst = false)
        }
    }

    private fun scheduleDelayedStopSelf(stopForegroundFirst: Boolean) {
        mainHandler.removeCallbacks(delayedStopSelfRunnable)
        stopForegroundBeforeDelayedStop = stopForegroundFirst
        mainHandler.postDelayed(delayedStopSelfRunnable, RECORDING_FAILED_BIND_WINDOW_MS)
    }

    /** FG 미진입 등 — 지연 콜백 취소 후 즉시 종료. */
    private fun stopSelfImmediately(stopForegroundFirst: Boolean) {
        mainHandler.removeCallbacks(delayedStopSelfRunnable)
        stopForegroundBeforeDelayedStop = false
        if (stopForegroundFirst) {
            stopForegroundCompat(removeNotification = true)
        }
        stopSelf()
    }

    private fun handlePauseResume() {
        if (_state.value is RecordingState.Stopping) return
        if (engine == null) return
        when (_state.value) {
            is RecordingState.Recording -> pauseEngineFromService(pausedByCall = false)
            is RecordingState.Paused -> resumeEngineFromService()
            else -> AppLogger.d(TAG, "pause/resume ignored in state=${recordingStateLabel(_state.value)}")
        }
    }

    /**
     * 엔진 start 성공 후에만 AudioFocus를 잡는다. request 실패는 fail-open(녹음 계속).
     * pause 대기 중에는 [abandonAudioFocusAndClearCallPauseFlag] 금지 — GAIN을 받아야 한다.
     */
    private fun requestAudioFocusAfterEngineStart() {
        abandonAudioFocusAndClearCallPauseFlag()
        val manager = getSystemService(AudioManager::class.java)
        if (manager == null) {
            AppLogger.w(TAG, "AudioManager missing — call detection skip")
            audioManager = null
            return
        }
        audioManager = manager
        val monitor = CallAudioFocusMonitor(
            AndroidRecordingAudioFocusBackend(
                audioManager = manager,
                listenerHandler = mainHandler,
            ),
        )
        audioFocusMonitor = monitor
        val granted = try {
            monitor.request { focusChange -> onCallAudioFocusChange(focusChange) }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            AppLogger.e(TAG, "audio focus request failed: ${e.javaClass.simpleName}", e)
            false
        }
        if (!granted) {
            AppLogger.w(TAG, "audio focus not granted — recording continues fail-open")
        }
    }

    private fun onCallAudioFocusChange(focusChange: Int) {
        AppLogger.d(TAG, "audio focus change=${audioFocusChangeLabel(focusChange)}")
        val mode = audioManager?.mode
        val isCallModeActive = mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION
        when (
            resolveCallAudioFocusAction(
                focusChange = focusChange,
                state = _state.value,
                pausedByCallDetection = pausedByCallDetection,
                isCallModeActive = isCallModeActive,
            )
        ) {
            CallAudioFocusAction.Pause -> pauseEngineFromService(pausedByCall = true)
            CallAudioFocusAction.Resume -> resumeEngineFromService()
            CallAudioFocusAction.None -> Unit
        }
    }

    private fun pauseEngineFromService(pausedByCall: Boolean) {
        val eng = engine ?: return
        if (_state.value !is RecordingState.Recording) return
        val ok = eng.pause()
        if (!ok) {
            syncFailedFromEngine(eng)
            return
        }
        pausedByCallDetection = pausedByCall
        val paused = eng.state.value as? RecordingState.Paused ?: return
        _state.value = paused
        publishNotification(paused)
        if (shouldDeferCountdownStopOnCallPause(
                pausedByCall = pausedByCall,
                hasPendingCountdownStop =
                    RecordingCountdownAlarmScheduler.hasPendingStopPendingIntent(this),
            )
        ) {
            deferCountdownStopForCallPause()
        }
    }

    private fun resumeEngineFromService() {
        val eng = engine ?: return
        if (_state.value !is RecordingState.Paused) return
        val ok = eng.resume()
        if (!ok) {
            syncFailedFromEngine(eng)
            return
        }
        pausedByCallDetection = false
        val recording = eng.state.value as? RecordingState.Recording ?: return
        _state.value = recording
        publishNotification(recording)
        rescheduleDeferredCountdownStopIfHeld()
    }

    private fun deferCountdownStopForCallPause() {
        RecordingCountdownAlarmScheduler.cancelStop(this)
        countdownStopHeldForCall = true
        serviceScope.launch {
            try {
                val duration = settingsRepository.pendingCountdown.first()?.durationMinutes
                if (duration != null && isCountdownMinutesInRange(duration)) {
                    deferredCountdownDurationMinutes = duration
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                AppLogger.e(TAG, "countdown duration cache failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun rescheduleDeferredCountdownStopIfHeld() {
        if (!countdownStopHeldForCall) return
        countdownStopHeldForCall = false
        val cachedDuration = deferredCountdownDurationMinutes
        deferredCountdownDurationMinutes = null
        val elapsedMs = recordingElapsedMsOrZero(_state.value)
        serviceScope.launch {
            val duration = cachedDuration ?: try {
                settingsRepository.pendingCountdown.first()?.durationMinutes
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                AppLogger.e(TAG, "countdown duration read failed: ${e.javaClass.simpleName}")
                null
            }
            if (duration == null || !isCountdownMinutesInRange(duration)) {
                AppLogger.e(TAG, "countdown remaining duration missing after call — stop")
                handleStop()
                return@launch
            }
            val remainingMs = remainingCountdownStopMs(duration, elapsedMs)
            if (shouldStopCountdownImmediately(remainingMs)) {
                handleStop()
                return@launch
            }
            val ok = RecordingCountdownAlarmScheduler.registerStopRemainingMs(
                this@RecordingService,
                remainingMs,
                SystemClock.elapsedRealtime(),
            )
            if (!ok) {
                AppLogger.e(TAG, "countdown remaining STOP register failed — stop")
                handleStop()
            }
        }
    }

    /** 세션 종료 경로만. 통화 pause 대기 중 GAIN을 기다리려면 호출하지 않는다. */
    private fun abandonAudioFocusAndClearCallPauseFlag() {
        pausedByCallDetection = false
        countdownStopHeldForCall = false
        deferredCountdownDurationMinutes = null
        audioManager = null
        val monitor = audioFocusMonitor ?: return
        audioFocusMonitor = null
        try {
            monitor.abandon()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            AppLogger.e(TAG, "audio focus abandon failed: ${e.javaClass.simpleName}", e)
        }
    }

    private fun handleStop() {
        if (isFinishingStop) return
        // Terminal: STOP must not force Idle (Controller sticky contract).
        when (_state.value) {
            is RecordingState.Saved,
            is RecordingState.Failed,
            is RecordingState.Review,
            is RecordingState.Stopping,
            -> return
            else -> Unit
        }
        val eng = engine
        if (eng == null) {
            // Never started or already cleaned — do not demote Saved/Failed.
            if (_state.value !is RecordingState.Idle) return
            cleanupSessionWithoutInsert()
            return
        }
        // Immediately leave Recording/Paused so binder never lingers there after STOP.
        isFinishingStop = true
        _state.value = RecordingState.Stopping
        publishNotification(RecordingState.Stopping)

        val stoppedOk = eng.stop()
        if (!stoppedOk) {
            val code = (eng.state.value as? RecordingState.Failed)?.errorCode
                ?: RecordingErrorCodes.STOP_FAILED
            _state.value = RecordingState.Failed(code)
            publishNotification(_state.value)
            isFinishingStop = false
            cleanupSessionWithoutInsert()
            return
        }
        val engineState = eng.state.value
        if (engineState !is RecordingState.Stopped) {
            _state.value = RecordingState.Failed(RecordingErrorCodes.STOP_FAILED)
            publishNotification(_state.value)
            isFinishingStop = false
            cleanupSessionWithoutInsert()
            return
        }
        val result = engineState.result
        serviceScope.launch {
            try {
                // 인덱싱/Room은 Service 책임이라 Engine.stop이 아닌 handleStop에서 판정.
                if (RecordingErrorCodes.isTooShortForSave(result.durationMs)) {
                    AppLogger.w(TAG, "recording too short: durationMs=${result.durationMs}")
                    deleteRecordingOutputOrLog(result.file, reason = RecordingErrorCodes.TOO_SHORT)
                    _state.value = RecordingState.Failed(RecordingErrorCodes.TOO_SHORT)
                    publishNotification(_state.value)
                    finishSession(eng, keepTerminalState = true)
                    return@launch
                }
                pendingReview = result
                val review = RecordingState.Review(
                    outputFile = result.file,
                    elapsedMs = result.durationMs,
                )
                _state.value = review
                finishCaptureKeepReview(eng)
                publishNotification(review)
            } catch (ce: CancellationException) {
                AppLogger.w(TAG, "stop cancelled before review: ${ce.message}")
                throw ce
            } catch (e: Exception) {
                AppLogger.e(TAG, "stop finalize failed: ${e.message}", e)
                deleteRecordingOutputOrLog(result.file, reason = RecordingErrorCodes.STOP_FAILED)
                pendingReview = null
                _state.value = RecordingState.Failed(RecordingErrorCodes.STOP_FAILED)
                publishNotification(_state.value)
                finishSession(eng, keepTerminalState = true)
            } finally {
                isFinishingStop = false
            }
        }
    }

    private fun handleKeep(intent: Intent) {
        if (isFinishingReviewAction) return
        when (_state.value) {
            is RecordingState.Saved,
            is RecordingState.Failed,
            is RecordingState.Recording,
            is RecordingState.Paused,
            is RecordingState.Stopping,
            -> return
            else -> Unit
        }
        val result = resolveReviewActionResult(intent) ?: run {
            AppLogger.w(TAG, "KEEP ignored — no pending review")
            return
        }
        // Keep in-flight: occupancy + dataSync FGS so IO survives DETACH Review.
        synchronized(SESSION_LOCK) { isSessionActive = true }
        isFinishingReviewAction = true
        ensureChannel()
        startKeepForeground(notificationFor(_state.value))
        serviceScope.launch {
            try {
                persistIndexedRecording(result)
            } finally {
                isFinishingReviewAction = false
            }
        }
    }

    private fun handleDiscard(intent: Intent) {
        if (isFinishingReviewAction) return
        when (_state.value) {
            is RecordingState.Saved,
            is RecordingState.Failed,
            is RecordingState.Recording,
            is RecordingState.Paused,
            is RecordingState.Stopping,
            -> return
            else -> Unit
        }
        val result = resolveReviewActionResult(intent) ?: run {
            // G2: Idle + no pending = true no-op (Keep ignore와 동일). stopSelf 금지.
            AppLogger.w(TAG, "DISCARD ignored — no pending review")
            return
        }
        isFinishingReviewAction = true
        serviceScope.launch {
            try {
                discardReviewToTrash(result)
            } finally {
                isFinishingReviewAction = false
            }
        }
    }

    /**
     * Keep/Discard 대상. pendingReview 우선.
     * Idle + extras만으로는 insert/trash 금지 (G1). extras 폴백은 Review 상태 + sessionId + storageDir.
     */
    private fun resolveReviewActionResult(intent: Intent): RecordingResult? {
        val pending = pendingReview
        if (pending != null) return pending
        val extras = reviewResultFromIntent(intent)
        val storageDir = runCatching { C2vRecordingNames.appStorageDir(this) }.getOrNull()
        val extrasFileConfined = extras != null &&
            storageDir != null &&
            isReviewOutputConfined(extras.file, storageDir)
        if (!shouldExecuteKeep(
                stateIsReview = _state.value is RecordingState.Review,
                hasPendingReview = false,
                extrasPresent = extras != null,
                extrasSessionMatches = sessionIdMatchesKeepExtras(
                    sessionId,
                    intent.getLongExtra(EXTRA_SESSION_ID, 0L),
                ),
                extrasFileConfined = extrasFileConfined,
            )
        ) {
            return null
        }
        return extras
    }

    private fun reviewResultFromIntent(intent: Intent): RecordingResult? {
        val path = intent.getStringExtra(EXTRA_REVIEW_FILE_PATH) ?: return null
        if (path.isBlank()) return null
        val elapsedMs = intent.getLongExtra(EXTRA_REVIEW_ELAPSED_MS, -1L)
        if (elapsedMs < 0L) return null
        val file = File(path)
        val format = intent.getStringExtra(EXTRA_FORMAT)?.let { name ->
            runCatching { RecordingFormat.valueOf(name) }.getOrNull()
        } ?: recordingFormatFromFile(file)
        return RecordingResult(file = file, format = format, durationMs = elapsedMs)
    }

    private suspend fun persistIndexedRecording(result: RecordingResult) {
        // Cancel BEFORE persist — cancelled Keep must not insert. After insert, always Saved.
        coroutineContext.ensureActive()
        var insertSucceeded = false
        var persistedRecord: RecordingRecord? = null
        try {
            withContext(Dispatchers.IO) {
                persistedRecord = repository.recordFinishedRecording(
                    file = result.file,
                    format = result.format,
                    durationMs = result.durationMs,
                )
                insertSucceeded = true
            }
        } catch (ce: CancellationException) {
            if (!insertSucceeded) {
                AppLogger.w(TAG, "keep cancelled before index: ${ce.message}")
                throw ce
            }
            AppLogger.w(TAG, "keep cancelled after index: ${ce.message}")
        } catch (e: Exception) {
            if (shouldDeleteOutputAfterKeepFailure(insertSucceeded)) {
                AppLogger.e(TAG, "recordFinishedRecording failed: ${e.message}", e)
                deleteRecordingOutputOrLog(result.file, reason = RecordingErrorCodes.INDEX_FAILED)
                pendingReview = null
                _state.value = RecordingState.Failed(RecordingErrorCodes.INDEX_FAILED)
                publishNotification(_state.value)
                finishTerminalAndStopSelf()
                return
            }
            AppLogger.e(TAG, "keep post-index failed: ${e.javaClass.simpleName}", e)
        }
        enqueueKeepSideEffects(result, persistedRecord)
        if (!shouldPublishSavedAfterKeepInsert(insertSucceeded)) {
            return
        }
        pendingReview = null
        val saved = RecordingState.Saved(
            outputFile = result.file,
            elapsedMs = result.durationMs,
        )
        _state.value = saved
        publishNotification(saved)
        finishTerminalAndStopSelf()
    }

    /**
     * Drive/AutoConvert enqueue after a successful insert. Failures are log-only (convert-only).
     * INDEX_FAILED + file delete must not run here.
     */
    private suspend fun enqueueKeepSideEffects(
        result: RecordingResult,
        persistedRecord: RecordingRecord?,
    ) {
        persistedRecord?.let { record ->
            try {
                RecordingBackupTrigger.active(record)?.let { event ->
                    RecordingBackupTrigger.enqueue(applicationContext, event)
                }
            } catch (ce: CancellationException) {
                AppLogger.w(TAG, "keep backup enqueue cancelled after index: ${ce.message}")
            } catch (e: Exception) {
                AppLogger.e(TAG, "keep backup enqueue failed: ${e.javaClass.simpleName}")
            }
        }
        enqueueRecordingPostSaveBranches(
            capabilities = StoreCapabilities.current,
            enqueueDriveAutoUpload = {
                enqueueDriveAutoUploadIfEnabled(
                    applicationContext,
                    result.file,
                    result.format,
                    settingsRepository,
                    workManager,
                )
            },
            enqueueAutoConvert = {
                enqueueAutoConvertIfEnabled(
                    applicationContext,
                    result.file,
                    settingsRepository,
                    workManager,
                )
            },
        )
    }

    private suspend fun discardReviewToTrash(result: RecordingResult) {
        val trashed = try {
            withContext(Dispatchers.IO) {
                resolveTrashRepository().moveToTrash(
                    sourceFile = result.file,
                    itemType = TrashedItem.RECORDING_AUDIO,
                    displayName = result.file.name,
                    wasIndexed = false,
                    originalFilePath = null,
                    durationMs = result.durationMs,
                    recordingFormat = result.format.name,
                )
            }
        } catch (ce: CancellationException) {
            AppLogger.w(TAG, "discard cancelled: ${ce.message}")
            deleteRecordingOutputOrLog(result.file, reason = "DISCARD")
            goIdleAfterReviewCleanup()
            throw ce
        } catch (e: Exception) {
            AppLogger.e(TAG, "discard moveToTrash failed: ${e.javaClass.simpleName}", e)
            null
        }
        if (trashed == null) {
            AppLogger.w(TAG, "discard failed — cleaning output")
            deleteRecordingOutputOrLog(result.file, reason = "DISCARD")
        }
        goIdleAfterReviewCleanup()
    }

    private fun goIdleAfterReviewCleanup() {
        pendingReview = null
        _state.value = RecordingState.Idle
        finishTerminalAndStopSelf()
    }

    /**
     * Engine 해제 + 마이크 FGS DETACH. Review occupancy([isSessionActive])는 유지한다.
     * POST_NOTIFICATIONS가 있으면 DETACH 후 Review 알림으로 교체.
     * 거부면 Stopping Pause/Stop 알림을 남기지 않도록 REMOVE (G3).
     * stopSelf는 Saved/Failed/Idle 단말([finishTerminalAndStopSelf])만.
     */
    private fun finishCaptureKeepReview(eng: RecordingEngine) {
        abandonAudioFocusAndClearCallPauseFlag()
        engineObserveJob?.cancel()
        engineObserveJob = null
        runCatching { eng.release() }
        engine = null
        val canNotify = canPostNotifications()
        stopForegroundCompat(removeNotification = shouldRemoveNotificationAfterReviewDetach(canNotify))
    }

    private fun finishTerminalAndStopSelf() {
        abandonAudioFocusAndClearCallPauseFlag()
        engineObserveJob?.cancel()
        engineObserveJob = null
        synchronized(SESSION_LOCK) { isSessionActive = false }
        runCatching { engine?.release() }
        engine = null
        stopForegroundCompat(removeNotification = true)
        stopSelf()
    }

    /**
     * Engine Recording/Paused 틱·비동기 Failed를 Service state에 반영.
     * 알림은 타입 전환 시에만 갱신(틱마다 notify 금지).
     */
    private fun observeEngine(eng: RecordingEngine) {
        engineObserveJob?.cancel()
        engineObserveJob = serviceScope.launch {
            eng.state.collect { engineState ->
                if (isFinishingStop) return@collect
                when (engineState) {
                    is RecordingState.Failed -> {
                        val current = _state.value
                        if (current is RecordingState.Recording || current is RecordingState.Paused) {
                            _state.value = RecordingState.Failed(engineState.errorCode)
                            publishNotification(_state.value)
                            cleanupSessionWithoutInsert()
                        }
                    }
                    is RecordingState.Recording, is RecordingState.Paused -> {
                        val current = _state.value
                        if (current is RecordingState.Recording || current is RecordingState.Paused) {
                            _state.value = engineState
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun syncFailedFromEngine(eng: RecordingEngine) {
        val failed = eng.state.value
        if (failed is RecordingState.Failed) {
            _state.value = failed
            publishNotification(_state.value)
            cleanupSessionWithoutInsert()
        }
    }

    private fun finishSession(eng: RecordingEngine, keepTerminalState: Boolean) {
        abandonAudioFocusAndClearCallPauseFlag()
        engineObserveJob?.cancel()
        engineObserveJob = null
        synchronized(SESSION_LOCK) { isSessionActive = false }
        runCatching { eng.release() }
        engine = null
        if (!keepTerminalState &&
            _state.value !is RecordingState.Idle &&
            _state.value !is RecordingState.Saved &&
            _state.value !is RecordingState.Failed &&
            _state.value !is RecordingState.Review
        ) {
            _state.value = RecordingState.Idle
        }
        stopForegroundCompat(removeNotification = true)
        stopSelf()
    }

    private fun cleanupSessionWithoutInsert() {
        abandonAudioFocusAndClearCallPauseFlag()
        engineObserveJob?.cancel()
        engineObserveJob = null
        synchronized(SESSION_LOCK) { isSessionActive = false }
        runCatching { engine?.release() }
        engine = null
        if (_state.value !is RecordingState.Failed) {
            _state.value = RecordingState.Idle
        }
        stopForegroundCompat(removeNotification = true)
        stopSelf()
    }

    /**
     * startForeground 성공 여부. SecurityException / IllegalStateException 시 false.
     * API 34+ 는 [ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE] 유지.
     * 실패 시 대체 타입 재시도 없음 — 캡처 구간은 microphone.
     */
    private fun startAsForeground(notification: Notification): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "startForeground SecurityException: ${e.message}", e)
            false
        } catch (e: IllegalStateException) {
            AppLogger.e(TAG, "startForeground IllegalStateException: ${e.message}", e)
            false
        }
    }

    /**
     * Keep IO용 dataSync FGS. 매니페스트 `microphone|dataSync` — 신규 dangerous permission 없음.
     * 실패해도 occupancy는 이미 claim됨. 경로 로그 금지.
     */
    private fun startKeepForeground(notification: Notification): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "keep startForeground SecurityException: ${e.message}", e)
            false
        } catch (e: IllegalStateException) {
            AppLogger.e(TAG, "keep startForeground IllegalStateException: ${e.message}", e)
            false
        }
    }

    /**
     * [removeNotification] true → REMOVE (Saved/Failed/Idle 단말).
     * false → DETACH: 마이크 FGS만 내리고 Review Keep/Discard 알림을 남긴다.
     */
    private fun stopForegroundCompat(removeNotification: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(
                if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH,
            )
        } else {
            @Suppress("DEPRECATION")
            stopForeground(removeNotification)
        }
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun publishNotification(state: RecordingState) {
        ensureChannel()
        if (canPostNotifications()) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notificationFor(state))
        }
    }

    private fun notificationFor(state: RecordingState): Notification {
        val contentText = when (state) {
            is RecordingState.Failed -> getString(recordingErrorCodeToStringRes(state.errorCode))
            else -> getString(notificationTextResIdFor(state))
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(notificationOngoingFor(state))
            .setOnlyAlertOnce(true)
        if (state is RecordingState.Review) {
            val result = pendingReview ?: RecordingResult(
                file = state.outputFile,
                format = recordingFormatFromFile(state.outputFile),
                durationMs = state.elapsedMs,
            )
            builder
                .setContentIntent(scheduledRecordingAppLaunchPendingIntent(this, REQ_OPEN_APP))
                .addAction(
                    0,
                    getString(R.string.record_review_keep),
                    pendingReviewAction(ACTION_KEEP, REQ_KEEP, result),
                )
                .addAction(
                    0,
                    getString(R.string.record_review_discard),
                    pendingReviewAction(ACTION_DISCARD, REQ_DISCARD, result),
                )
        } else if (notificationHasPauseStopActions(state)) {
            builder
                .addAction(
                    0,
                    getString(R.string.recording_action_pause_resume),
                    pendingServiceAction(ACTION_PAUSE_RESUME, REQ_PAUSE_RESUME),
                )
                .addAction(
                    0,
                    getString(R.string.recording_action_stop),
                    pendingServiceAction(ACTION_STOP, REQ_STOP),
                )
        }
        return builder.build()
    }

    private fun pendingServiceAction(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, RecordingService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun pendingReviewAction(
        action: String,
        requestCode: Int,
        result: RecordingResult,
    ): PendingIntent {
        val intent = Intent(this, RecordingService::class.java).apply {
            this.action = action
            putExtra(EXTRA_REVIEW_FILE_PATH, result.file.absolutePath)
            putExtra(EXTRA_REVIEW_ELAPSED_MS, result.durationMs)
            putExtra(EXTRA_FORMAT, result.format.name)
            putExtra(EXTRA_SESSION_ID, sessionId)
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel() {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.recording_notification_channel))
                .build(),
        )
    }

    companion object {
        const val ACTION_START = "com.example.convert2video.record.action.START"
        const val ACTION_PAUSE_RESUME = "com.example.convert2video.record.action.PAUSE_RESUME"
        const val ACTION_STOP = "com.example.convert2video.record.action.STOP"
        const val ACTION_KEEP = "com.example.convert2video.record.action.KEEP"
        const val ACTION_DISCARD = "com.example.convert2video.record.action.DISCARD"
        const val EXTRA_FORMAT = "format"
        /** Controller 발급 세션 id (Long). stale Saved sticky 차단용. */
        const val EXTRA_SESSION_ID = "session_id"
        /** Keep/Discard extras — Review 파일 절대경로 (로그 금지). */
        const val EXTRA_REVIEW_FILE_PATH = "review_file_path"
        const val EXTRA_REVIEW_ELAPSED_MS = "review_elapsed_ms"

        const val CHANNEL_ID = "recording_progress"
        private const val NOTIFICATION_ID = 2001
        private const val REQ_PAUSE_RESUME = 2101
        private const val REQ_STOP = 2102
        private const val REQ_KEEP = 2103
        private const val REQ_DISCARD = 2104
        private const val REQ_OPEN_APP = 2105
        private const val TAG = "RecordingService"

        private val SESSION_LOCK = Any()

        @Volatile
        private var isSessionActive: Boolean = false

        fun isRunning(): Boolean = isSessionActive
    }
}

/**
 * Failed FG 성공 직후 Controller bind가 [RecordingService.state]를 읽을 수 있게
 * stopSelf를 미루는 창(ms). FG 미진입에는 적용하지 않는다(즉시 stopSelf).
 */
internal const val RECORDING_FAILED_BIND_WINDOW_MS = 500L

/**
 * FG 진입 성공 시에만 bind 창 지연 demote/stop. FG 미진입이면 즉시 stopSelf.
 */
internal fun shouldScheduleDelayedStopSelf(enteredForeground: Boolean): Boolean =
    enteredForeground

/**
 * SESSION_LOCK occupancy — 녹음 중·deny bind 창·**Review(파일 보유)** 재START ignore.
 * Tile [isActiveRecordingSession]과 접지하지 않는다 (Review는 occupancy true, active session false).
 */
internal fun shouldIgnoreStartWhileSessionBusy(isSessionActive: Boolean): Boolean =
    isSessionActive

/**
 * Keep insert 실패일 때만 산출 삭제+INDEX_FAILED. insert 성공 후 enqueue 실패는 삭제 금지.
 */
internal fun shouldDeleteOutputAfterKeepFailure(insertSucceeded: Boolean): Boolean =
    !insertSucceeded

/**
 * insert 성공 후에는 cancel이어도 Saved로 수렴. insert 전이면 Review 유지.
 */
internal fun shouldPublishSavedAfterKeepInsert(insertSucceeded: Boolean): Boolean =
    insertSucceeded

/**
 * Keep/Discard 실행 게이트 (G1).
 * pendingReview가 있으면 충분. Idle + extras만으로는 false.
 * extras 폴백은 Review + session 일치 + storageDir confinement.
 */
internal fun shouldExecuteKeep(
    stateIsReview: Boolean,
    hasPendingReview: Boolean,
    extrasPresent: Boolean,
    extrasSessionMatches: Boolean,
    extrasFileConfined: Boolean,
): Boolean {
    if (hasPendingReview) return true
    if (!stateIsReview) return false
    if (!extrasPresent) return false
    return extrasSessionMatches && extrasFileConfined
}

/** Idle + pending 없음 = true no-op (Keep ignore와 동일, stopSelf 금지). */
internal fun shouldNoOpReviewActionWithoutPending(
    stateIsReview: Boolean,
    hasPendingReview: Boolean,
): Boolean = !stateIsReview && !hasPendingReview

internal fun sessionIdMatchesKeepExtras(serviceSessionId: Long, extrasSessionId: Long): Boolean =
    extrasSessionId != 0L && extrasSessionId == serviceSessionId

/**
 * extras 경로 confinement. true = [storageDir] 자식.
 * undetermined(IO/Security)는 fail-closed false. 경로 문자열은 로그하지 않는다.
 */
internal fun isReviewOutputConfined(file: File, storageDir: File): Boolean {
    return try {
        val fileCanon = file.canonicalFile
        val dirCanon = storageDir.canonicalFile
        fileCanon.path.startsWith(dirCanon.path + File.separator)
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        AppLogger.w("RecordingService", "review confinement undetermined: ${e.javaClass.simpleName}")
        false
    }
}

/** Review·캡처 중 ongoing. Saved/Failed/Idle은 false. */
internal fun notificationOngoingFor(state: RecordingState): Boolean =
    state is RecordingState.Recording ||
        state is RecordingState.Paused ||
        state is RecordingState.Stopping ||
        state is RecordingState.Review

/** Pause/Stop 액션은 캡처 중에만. Review·Saved·Failed 단말 금지. */
internal fun notificationHasPauseStopActions(state: RecordingState): Boolean =
    state is RecordingState.Recording ||
        state is RecordingState.Paused ||
        state is RecordingState.Stopping

/** POST_NOTIFICATIONS 거부면 Stopping 알림 REMOVE. 허용이면 DETACH 후 Review notify. */
internal fun shouldRemoveNotificationAfterReviewDetach(canNotify: Boolean): Boolean =
    !canNotify

/** Keep/Discard in-flight가 Review에 고착이면 워치독 clear. Service occupancy 중(Keep IO)은 금지. */
internal fun shouldClearStuckReviewInFlight(
    stillInFlight: Boolean,
    current: RecordingState,
    isServiceRunning: Boolean,
): Boolean = stillInFlight && current is RecordingState.Review && !isServiceRunning

/**
 * in-flight expectedSessionId 고착 해제 여부 — Controller 워치독용 순수 판정.
 * Idle + 동일 id 이더라도 Service가 아직 활성이면 clear/Failed 금지(false-positive 방지).
 */
internal fun shouldClearStuckExpectedSession(
    expectedSessionId: Long,
    startSessionId: Long,
    current: RecordingState,
    isServiceRunning: Boolean = false,
): Boolean = expectedSessionId == startSessionId &&
    current is RecordingState.Idle &&
    !isServiceRunning

internal fun recordingElapsedMsOrZero(state: RecordingState): Long = when (state) {
    is RecordingState.Recording -> state.elapsedMs
    is RecordingState.Paused -> state.elapsedMs
    else -> 0L
}

/** 알림 본문 string resource id — JVM에서 리소스 id 매핑을 검증한다. */
internal fun notificationTextResIdFor(state: RecordingState): Int =
    when (state) {
        is RecordingState.Idle -> R.string.recording_notification_idle
        is RecordingState.Recording -> R.string.recording_notification_recording
        is RecordingState.Paused -> R.string.recording_notification_paused
        is RecordingState.Stopping -> R.string.recording_notification_stopping
        is RecordingState.Stopped -> R.string.recording_notification_stopped
        is RecordingState.Saved -> R.string.recording_notification_saved
        is RecordingState.Review -> R.string.recording_notification_review
        is RecordingState.Failed -> R.string.recording_notification_failed
    }
