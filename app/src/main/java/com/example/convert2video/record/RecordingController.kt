package com.example.convert2video.record

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import com.example.convert2video.utils.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * ViewModel용 녹음 진입점 싱글톤 (Hilt 없음).
 *
 * bindService는 Controller 내부에서만 수행한다 — UI/ViewModel은 이 클래스만 호출한다.
 *
 * ## 공식 상태 머신 (Controller 노출분)
 * `Idle → Recording ⇄ Paused → Stopping → Review → Saved | Idle(Discard) | Failed`
 *
 * - [RecordingState.Stopped]는 Engine 전용 — Controller [state]에는 비노출.
 * - 단말 sticky: [RecordingState.Saved] / [RecordingState.Failed]는
 *   [clearTerminalState] 또는 다음 [start]로만 해제한다.
 * - [RecordingState.Review] sticky는 Saved/Failed와 동등 — Service Idle(BIND_AUTO_CREATE)로
 *   덮지 않는다. 해제는 [keep]/[discard]만 ([clearTerminalState] 제외, start 재시작 금지).
 * - Service가 stopSelf 후 BIND_AUTO_CREATE로 Idle을 다시내도 sticky를 덮어쓰지 않는다.
 * - stale Saved/Failed/Review는 [expectedSessionId] 불일치로 무시한다.
 *
 * ## FGS 수명 (Service와 동일 계약)
 * Recording / Paused / Stopping = 마이크 포그라운드 유지.
 * Review = checkpoint(마이크 FGS DETACH + ongoing Keep/Discard 알림). occupancy는 Service 생존 동안 true.
 * Service onDestroy(Review pending) → Controller recoverable Idle + 파일 유지 (occupancy 침묵 드롭 금지).
 * Keep in-flight = occupancy + dataSync FGS. Tile [isActiveRecordingSession]은 Review에서 false.
 * Saved / Failed / Discard Idle = Service가 stopForeground(REMOVE) + stopSelf (Controller는 Saved/Failed sticky).
 *
 * ## CoroutineExceptionHandler Failed 발행
 * [scope] 미처리 예외 → [onScopeUncaughtFailed]: 코드는
 * [RecordingErrorCodes.FOREGROUND_START_DENIED]만 · **Idle에서만** Failed emit.
 * Saved·Failed sticky는 [clearTerminalState] 또는 다음 [start]로만 해제 —
 * Handler가 기존 Failed를 FOREGROUND_START_DENIED로 덮지 않는다.
 * Recording·Paused·Stopping 활성 중 uncaught는 Failed로 덮지 않고
 * [resubscribeServiceMirror]로 collect 미러를 재구독한다.
 */
class RecordingController private constructor(
    private val app: Application,
) {
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    /**
     * Review Keep/Discard in-flight. Discard/Start no-op while true.
     * Review 버튼 enabled는 이 플래그의 역.
     */
    private val _isReviewActionInFlight = MutableStateFlow(false)
    val isReviewActionInFlight: StateFlow<Boolean> = _isReviewActionInFlight.asStateFlow()

    @Volatile
    private var isKeepInFlight: Boolean = false

    @Volatile
    private var isDiscardInFlight: Boolean = false

    /** androidTest — Saving UI / navigation block without reflection. */
    @VisibleForTesting
    internal fun setStateForTest(state: RecordingState) {
        _state.value = state
    }

    /**
     * androidTest — [scope] CEH 경로를 타게 미처리 예외를 발사한다.
     * Main dispatcher에서 호출할 것.
     */
    @VisibleForTesting
    internal fun launchUncaughtForTest(throwable: Throwable) {
        scope.launch { throw throwable }
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            recordingScopeExceptionHandler(TAG, onFailed = ::onScopeUncaughtFailed),
    )
    private var collectJob: Job? = null
    private var bound: Boolean = false
    private var service: RecordingService? = null
    private var bindReady: CompletableDeferred<Boolean>? = null

    /** Controller.start가 발급한 세션. Service EXTRA_SESSION_ID와 일치할 때만 진행/단말 반영. */
    @Volatile
    private var expectedSessionId: Long = 0L

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? RecordingService.LocalBinder
            if (local == null) {
                AppLogger.w(TAG, "unexpected binder type")
                bindReady?.complete(false)
                return
            }
            service = local.service
            bound = true
            bindReady?.complete(true)
            startServiceMirrorCollect(local.service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            collectJob?.cancel()
            collectJob = null
            bound = false
            // Retain last terminal state (Saved/Failed) for UI observation.
        }
    }

    /**
     * Service 미러 → Controller sticky 규칙.
     * Idle/Stopping이 단말 Saved/Failed를 지우지 않는다.
     * 세션 불일치 stale Saved는 sticky를 되살리지 않는다.
     */
    private fun applyServiceState(serviceState: RecordingState, serviceSessionId: Long) {
        val previous = _state.value
        if (shouldApplyServiceIdleForDiscardConfirm(
                previous = previous,
                serviceState = serviceState,
                discardInFlight = isDiscardInFlight,
            )
        ) {
            clearReviewActionInFlight()
            expectedSessionId = 0L
            _state.value = RecordingState.Idle
            unbindQuietly()
            return
        }
        val next = mergeRecordingControllerState(
            previous = previous,
            serviceState = serviceState,
            controllerSessionId = expectedSessionId,
            serviceSessionId = serviceSessionId,
        ) ?: return
        _state.value = next
        if (next is RecordingState.Saved ||
            next is RecordingState.Failed ||
            next is RecordingState.Idle
        ) {
            clearReviewActionInFlight()
        }
        val shouldUnbind = next is RecordingState.Saved ||
            next is RecordingState.Failed
        if (shouldUnbind) {
            unbindQuietly()
        }
    }

    private fun clearReviewActionInFlight() {
        isKeepInFlight = false
        isDiscardInFlight = false
        _isReviewActionInFlight.value = false
    }

    /**
     * 녹음 시작. 기본 포맷 [RecordingFormat.AAC].
     * 이전 Saved/Failed sticky는 새 세션으로 덮인다.
     * Service busy, RECORD_AUDIO 미허용, 또는
     * [ContextCompat.startForegroundService] [IllegalStateException] 시
     * Failed만 낸다 (START_FAILED 재사용 금지).
     * FGS는 동기 스택에서 올리고, bind는 이후 fire-and-forget — bind 실패로 start를 되돌리지 않는다.
     */
    fun start(format: RecordingFormat = RecordingFormat.AAC) {
        if (shouldIgnoreDiscardOrStartWhileKeepInFlight(isKeepInFlight) || isDiscardInFlight) {
            AppLogger.w(TAG, "start ignored — review action in-flight")
            return
        }
        when (_state.value) {
            is RecordingState.Recording,
            is RecordingState.Paused,
            is RecordingState.Stopping,
            is RecordingState.Review,
            -> {
                AppLogger.w(TAG, "start ignored — session already in progress")
                return
            }
            is RecordingState.Saved, is RecordingState.Failed -> {
                // 단말 sticky 해제 후 새 세션 허용 — in-flight 가드가 이전 id에 막히지 않게 리셋.
                expectedSessionId = 0L
                _state.value = RecordingState.Idle
            }
            else -> Unit
        }
        // FGS 직후 in-flight: Idle이어도 세션 발급됨 — 연타만 차단. UI를 Recording으로 가짜 emit하지 않음.
        if (expectedSessionId != 0L) {
            AppLogger.w(TAG, "start ignored — in-flight session expectedSessionId=$expectedSessionId")
            return
        }
        if (RecordingService.isRunning()) {
            AppLogger.w(TAG, "start denied — previous session still finishing")
            // busy는 session 발급 전 deny — sticky merge가 이전 Saved로 deny Failed를 덮지 않게 고아/이전 id 정리.
            expectedSessionId = 0L
            _state.value = RecordingState.Failed(RecordingErrorCodes.FOREGROUND_START_DENIED)
            return
        }
        // API 34+ microphone startForeground는 RECORD_AUDIO 필수. 없으면 FGS를 올리지 않아
        // ForegroundServiceDidNotStartInTimeException·매니페스트 대체타입 부재 문제를 피한다.
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            expectedSessionId = 0L
            _state.value = RecordingState.Failed(RecordingErrorCodes.PERMISSION_DENIED)
            return
        }
        val sessionId = NEXT_SESSION_ID.incrementAndGet()
        expectedSessionId = sessionId
        val intent = Intent(app, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_FORMAT, format.name)
            putExtra(RecordingService.EXTRA_SESSION_ID, sessionId)
        }
        try {
            ContextCompat.startForegroundService(app, intent)
        } catch (e: IllegalStateException) {
            AppLogger.w(TAG, "startForegroundService denied: ${e.message}", e)
            // incrementAndGet 후 deny — sticky merge 오염 방지로 expectedSessionId 반드시 리셋.
            expectedSessionId = 0L
            _state.value = RecordingState.Failed(RecordingErrorCodes.FOREGROUND_START_DENIED)
            return
        }
        // Grill-2/3: Service가 Failed 후 bind 전에 destroy되면 Idle+expectedSessionId 고착 가능 —
        // 창이 지나도 Idle이고 Service가 비활성이면 클리어 + FOREGROUND_START_DENIED.
        // Controller Idle + Service 활성이면 Failed/clear 금지 (워치독 false-positive 방지).
        scope.launch {
            delay(IN_FLIGHT_STUCK_CLEAR_MS)
            if (shouldClearStuckExpectedSession(
                    expectedSessionId = expectedSessionId,
                    startSessionId = sessionId,
                    current = _state.value,
                    isServiceRunning = RecordingService.isRunning(),
                )
            ) {
                AppLogger.w(TAG, "clear stuck expectedSessionId=$sessionId — Idle after FGS start")
                expectedSessionId = 0L
                _state.value = RecordingState.Failed(RecordingErrorCodes.FOREGROUND_START_DENIED)
            }
        }
        // FGS 기동 후 bind — collect 연결. 실패해도 start를 Failed/BIND_FAILED로 되돌리지 않는다.
        scope.launch {
            if (awaitBound()) return@launch
            AppLogger.w(TAG, "start: bind failed after FGS start — retrying once")
            // timeout/false 후 잔여 deferred·연결 정리 뒤 재 bindService (Failed/롤백 금지).
            unbindQuietly()
            bindReady = null
            delay(BIND_RETRY_DELAY_MS)
            if (!awaitBound()) {
                AppLogger.w(TAG, "start: bind failed after FGS start — collect may be delayed")
            }
        }
    }

    /** Recording 중일 때만 pause 요청. bind 실패 시 no-op. */
    fun pause() {
        if (_state.value !is RecordingState.Recording) return
        scope.launch {
            if (!awaitBound()) {
                AppLogger.w(TAG, "pause: bind failed — no-op")
                return@launch
            }
            app.startService(
                Intent(app, RecordingService::class.java)
                    .setAction(RecordingService.ACTION_PAUSE_RESUME),
            )
        }
    }

    /** Paused 일 때만 resume 요청. bind 실패 시 no-op. */
    fun resume() {
        if (_state.value !is RecordingState.Paused) return
        scope.launch {
            if (!awaitBound()) {
                AppLogger.w(TAG, "resume: bind failed — no-op")
                return@launch
            }
            app.startService(
                Intent(app, RecordingService::class.java)
                    .setAction(RecordingService.ACTION_PAUSE_RESUME),
            )
        }
    }

    /**
     * stop은 fire-and-forget이되, bind 완료를 기다린 뒤 STOP을 보낸다.
     * finalize + Room insert 후 [state]에 [RecordingState.Saved]가 sticky로 유지된다.
     *
     * Saved/Failed(단말)에서는 no-op — Idle로 강제하지 않는다.
     */
    fun stop() {
        when (_state.value) {
            is RecordingState.Saved,
            is RecordingState.Failed,
            is RecordingState.Review,
            is RecordingState.Idle,
            is RecordingState.Stopping,
            -> return
            else -> Unit
        }
        scope.launch {
            if (!awaitBound()) {
                AppLogger.w(TAG, "stop: bind failed — STOP not sent")
                return@launch
            }
            app.startService(
                Intent(app, RecordingService::class.java)
                    .setAction(RecordingService.ACTION_STOP),
            )
        }
    }

    /**
     * Review Keep — Room insert + enqueue + Saved.
     * Review가 아니면 no-op. Keep in-flight 중 재Keep/Discard/Start no-op.
     * bind 실패 + Service 비활성이면 in-flight를 남기지 않는다 (워치독 없이 즉시 clear).
     * Service no-op면 IN_FLIGHT_STUCK_CLEAR_MS 워치독이 버튼을 해제한다.
     */
    fun keep() {
        if (shouldIgnoreDiscardOrStartWhileKeepInFlight(isKeepInFlight) || isDiscardInFlight) {
            AppLogger.w(TAG, "keep ignored — review action in-flight")
            return
        }
        val review = _state.value as? RecordingState.Review ?: return
        val format = recordingFormatFromFile(review.outputFile)
        val sessionId = expectedSessionId
        isKeepInFlight = true
        _isReviewActionInFlight.value = true
        scope.launch {
            if (!awaitBound()) {
                AppLogger.w(TAG, "keep: bind failed")
                if (!RecordingService.isRunning()) {
                    clearReviewActionInFlight()
                    return@launch
                }
                AppLogger.w(TAG, "keep: bind failed — sending KEEP anyway")
            }
            app.startService(
                Intent(app, RecordingService::class.java).apply {
                    action = RecordingService.ACTION_KEEP
                    putExtra(RecordingService.EXTRA_REVIEW_FILE_PATH, review.outputFile.absolutePath)
                    putExtra(RecordingService.EXTRA_REVIEW_ELAPSED_MS, review.elapsedMs)
                    putExtra(RecordingService.EXTRA_FORMAT, format.name)
                    putExtra(RecordingService.EXTRA_SESSION_ID, sessionId)
                },
            )
            delay(IN_FLIGHT_STUCK_CLEAR_MS)
            if (shouldClearStuckReviewInFlight(
                    stillInFlight = isKeepInFlight,
                    current = _state.value,
                    isServiceRunning = RecordingService.isRunning(),
                )
            ) {
                AppLogger.w(TAG, "clear stuck keep in-flight")
                clearReviewActionInFlight()
            }
        }
    }

    /**
     * Review Discard — 휴지통 이동 후 Idle.
     * Keep in-flight면 no-op. Controller Idle은 Service Idle/fail confirm 후에만
     * ([shouldApplyServiceIdleForDiscardConfirm]). Review가 아니면 no-op.
     */
    fun discard() {
        if (shouldIgnoreDiscardOrStartWhileKeepInFlight(isKeepInFlight) || isDiscardInFlight) {
            AppLogger.w(TAG, "discard ignored — review action in-flight")
            return
        }
        val review = _state.value as? RecordingState.Review ?: return
        val format = recordingFormatFromFile(review.outputFile)
        val path = review.outputFile.absolutePath
        val elapsedMs = review.elapsedMs
        val sessionId = expectedSessionId
        isDiscardInFlight = true
        _isReviewActionInFlight.value = true
        scope.launch {
            if (!awaitBound()) {
                AppLogger.w(TAG, "discard: bind failed")
                if (!RecordingService.isRunning()) {
                    clearReviewActionInFlight()
                    return@launch
                }
                AppLogger.w(TAG, "discard: bind failed — sending DISCARD anyway")
            }
            app.startService(
                Intent(app, RecordingService::class.java).apply {
                    action = RecordingService.ACTION_DISCARD
                    putExtra(RecordingService.EXTRA_REVIEW_FILE_PATH, path)
                    putExtra(RecordingService.EXTRA_REVIEW_ELAPSED_MS, elapsedMs)
                    putExtra(RecordingService.EXTRA_FORMAT, format.name)
                    putExtra(RecordingService.EXTRA_SESSION_ID, sessionId)
                },
            )
            delay(IN_FLIGHT_STUCK_CLEAR_MS)
            if (shouldClearStuckReviewInFlight(
                    stillInFlight = isDiscardInFlight,
                    current = _state.value,
                    isServiceRunning = RecordingService.isRunning(),
                )
            ) {
                AppLogger.w(TAG, "clear stuck discard in-flight")
                clearReviewActionInFlight()
            }
        }
    }

    /**
     * Service onDestroy(Review pending): occupancy 침묵 드롭 대신 recoverable Idle.
     * 파일은 삭제하지 않는다. 싱글톤이 없으면 no-op (테스트 Service-only).
     */
    private fun onServiceDestroyedWhileReviewPending() {
        when (val current = _state.value) {
            is RecordingState.Review -> {
                AppLogger.w(
                    TAG,
                    "service destroyed during ${recordingStateLabel(current)} — recoverable Idle",
                )
                clearReviewActionInFlight()
                expectedSessionId = 0L
                _state.value = RecordingState.Idle
                unbindQuietly()
            }
            else -> {
                if (isKeepInFlight || isDiscardInFlight) {
                    clearReviewActionInFlight()
                }
            }
        }
    }

    /**
     * Saved/Failed 단말 sticky를 Idle로 되돌린다 (Phase 2 dismiss용).
     * Review는 포함하지 않는다 — Keep/Discard만 해제.
     * 진행 중(Recording/Paused/Stopping)에는 no-op.
     * expectedSessionId도 0으로 리셋해 다음 start·merge가 고아 세션에 묶이지 않게 한다.
     */
    fun clearTerminalState() {
        when (_state.value) {
            is RecordingState.Saved, is RecordingState.Failed -> {
                expectedSessionId = 0L
                _state.value = RecordingState.Idle
            }
            else -> Unit
        }
    }

    /**
     * bind 완료까지 대기. stop→Saved 레이스에서 collect 누락을 막는다.
     * @return binder 연결 성공 여부
     */
    private suspend fun awaitBound(timeoutMs: Long = BIND_TIMEOUT_MS): Boolean {
        service?.let { if (bound) return true }
        val deferred = synchronized(connection) {
            service?.let { if (bound) return true }
            val existing = bindReady
            if (existing != null && !existing.isCompleted) {
                existing
            } else {
                CompletableDeferred<Boolean>().also { next ->
                    bindReady = next
                    if (!bound) {
                        val ok = app.bindService(
                            Intent(app, RecordingService::class.java),
                            connection,
                            Context.BIND_AUTO_CREATE,
                        )
                        if (!ok) {
                            bound = false
                            next.complete(false)
                            AppLogger.w(TAG, "bindService failed")
                        }
                    } else if (service != null) {
                        next.complete(true)
                    }
                }
            }
        }
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        if (result == null) {
            AppLogger.w(TAG, "awaitBound timed out after ${timeoutMs}ms")
            return false
        }
        return result
    }

    private fun unbindQuietly() {
        if (!bound) return
        runCatching { app.unbindService(connection) }
            .onFailure { e -> AppLogger.w(TAG, "unbindService: ${e.message}", e) }
        bound = false
        service = null
        collectJob?.cancel()
        collectJob = null
        bindReady = null
    }

    /**
     * Service state collect 미러 — bind 직후 및 활성 세션 uncaught 후 재구독.
     * StateFlow 현재값을 즉시 반영 — STOP~Saved 레이스에서 누락 방지.
     */
    private fun startServiceMirrorCollect(svc: RecordingService) {
        collectJob?.cancel()
        collectJob = scope.launch {
            applyServiceState(svc.state.value, svc.sessionId)
            svc.state.collect { serviceState ->
                applyServiceState(serviceState, svc.sessionId)
            }
        }
    }

    /** 활성 세션에서 collectJob이 죽어도 미러를 복구한다. unbound면 no-op. */
    private fun resubscribeServiceMirror() {
        val svc = service
        if (!bound || svc == null) {
            AppLogger.w(TAG, "mirror reconnect skipped — not bound")
            return
        }
        AppLogger.w(TAG, "mirror reconnect — resubscribe Service state collect")
        startServiceMirrorCollect(svc)
    }

    /**
     * Handler Failed 발행 지점 — **Idle만** Failed(FOREGROUND_START_DENIED).
     * Saved·Failed sticky는 덮지 않는다([clearTerminalState]/다음 [start]만 해제).
     * 활성(Recording/Paused/Stopping)은 Failed 금지 + 미러 재구독.
     * 로그는 [recordingStateLabel]만 사용(Saved.outputFile 경로 금지).
     */
    private fun onScopeUncaughtFailed(code: String) {
        when (val current = _state.value) {
            is RecordingState.Saved,
            is RecordingState.Failed,
            is RecordingState.Review,
            -> {
                AppLogger.w(
                    TAG,
                    "scope Failed ignored — sticky ${recordingStateLabel(current)}",
                )
            }
            is RecordingState.Recording,
            is RecordingState.Paused,
            is RecordingState.Stopping,
            -> {
                AppLogger.w(
                    TAG,
                    "scope uncaught during active ${recordingStateLabel(current)} — reconnect mirror",
                )
                resubscribeServiceMirror()
            }
            else -> {
                expectedSessionId = 0L
                _state.value = RecordingState.Failed(code)
            }
        }
    }

    companion object {
        private const val TAG = "RecordingController"
        private const val BIND_TIMEOUT_MS = 3_000L
        /** start 후 awaitBound 실패 시 1회 재시도 전 대기. */
        private const val BIND_RETRY_DELAY_MS = 200L
        /**
         * FGS start 후 Idle+expectedSessionId 고착 판정 창.
         * Service [RECORDING_FAILED_BIND_WINDOW_MS] + bind 재시도보다 길게.
         */
        private const val IN_FLIGHT_STUCK_CLEAR_MS =
            RECORDING_FAILED_BIND_WINDOW_MS + BIND_TIMEOUT_MS + BIND_RETRY_DELAY_MS
        private val NEXT_SESSION_ID = AtomicLong(0L)

        @Volatile
        private var instance: RecordingController? = null

        fun getInstance(application: Application): RecordingController {
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                instance ?: RecordingController(application).also { instance = it }
            }
        }

        /** androidTest 등에서 싱글톤 리셋. */
        internal fun clearInstanceForTest() {
            synchronized(this) {
                instance?.unbindQuietly()
                instance = null
            }
        }

        /**
         * Service onDestroy(Review pending) → Controller recoverable Idle.
         * 인스턴스가 없으면 no-op (생성하지 않음).
         */
        internal fun notifyServiceDestroyedWhileReview() {
            instance?.onServiceDestroyedWhileReviewPending()
        }
    }
}

/**
 * Cancellation 판정(1-level unwrap):
 * `throwable is CancellationException || throwable.cause is CancellationException`.
 * CEH는 log 후 early-return(no-op), 순수 seam은 CE(또는 cause CE)를 rethrow.
 */
internal fun isRecordingScopeCancellation(throwable: Throwable): Boolean =
    throwable is CancellationException || throwable.cause is CancellationException

/** 로그용 sealed 라벨 — Saved.outputFile 등 경로 문자열을 절대 포함하지 않는다. */
internal fun recordingStateLabel(state: RecordingState): String =
    state::class.simpleName ?: "RecordingState"

/**
 * CEH cancellation early-return용 로그 메시지 — class/message only (경로 금지).
 */
internal fun recordingScopeCancellationLogMessage(throwable: Throwable): String {
    val head = "${throwable.javaClass.simpleName}: ${throwable.message}"
    val cause = throwable.cause
    return if (throwable !is CancellationException && cause is CancellationException) {
        "$head causeCe=${cause.javaClass.simpleName}: ${cause.message}"
    } else {
        head
    }
}

/**
 * record/ 스코프용 [CoroutineExceptionHandler] — 취소는 log+no-op, 그 외 [handleRecordingScopeException].
 * Tile/Service는 [onFailed] null(log-only). Controller만 Failed 콜백 전달.
 *
 * CEH는 [CancellationException]을 rethrow하지 않는다(핸들러 throw 금지 관례) —
 * 취소 전파 검증은 순수 [handleRecordingScopeException] 유닛이 담당.
 */
internal fun recordingScopeExceptionHandler(
    tag: String,
    onFailed: ((errorCode: String) -> Unit)? = null,
): CoroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
    if (isRecordingScopeCancellation(throwable)) {
        AppLogger.w(tag, "recording scope cancellation ignored: ${recordingScopeCancellationLogMessage(throwable)}")
        return@CoroutineExceptionHandler
    }
    handleRecordingScopeException(tag = tag, throwable = throwable, onFailed = onFailed)
}

/**
 * A_seam — 미처리 코루틴 예외 정책 (패키지 internal, 신규 파일 없음).
 *
 * - 비취소 [Throwable]: [AppLogger] + [onFailed]가 있으면
 *   [RecordingErrorCodes.FOREGROUND_START_DENIED]만 전달.
 * - Cancellation([isRecordingScopeCancellation]): **rethrow** · [onFailed] 금지.
 *   (CE 자체면 그대로, cause만 CE면 cause를 throw — 1-level unwrap.)
 */
internal fun handleRecordingScopeException(
    tag: String,
    throwable: Throwable,
    onFailed: ((errorCode: String) -> Unit)? = null,
) {
    if (isRecordingScopeCancellation(throwable)) {
        when (throwable) {
            is CancellationException -> throw throwable
            else -> throw (throwable.cause as CancellationException)
        }
    }
    AppLogger.e(tag, "uncaught in recording scope: ${throwable.message}", throwable)
    onFailed?.invoke(RecordingErrorCodes.FOREGROUND_START_DENIED)
}

/**
 * Controller sticky merge — JVM 단위 테스트 가능한 순수 함수.
 *
 * @return 반영할 다음 상태. null이면 previous 유지.
 */
internal fun mergeRecordingControllerState(
    previous: RecordingState,
    serviceState: RecordingState,
    controllerSessionId: Long,
    serviceSessionId: Long,
): RecordingState? {
    when (serviceState) {
        is RecordingState.Stopped -> return null
        is RecordingState.Idle -> {
            if (isControllerStickyTerminal(previous)) {
                return null
            }
            return RecordingState.Idle
        }
        is RecordingState.Stopping,
        is RecordingState.Recording,
        is RecordingState.Paused,
        is RecordingState.Saved,
        is RecordingState.Failed,
        is RecordingState.Review,
        -> {
            // 세션 불일치(stale Saved/Review 포함)는 sticky/진행 상태를 오염시키지 않는다.
            if (controllerSessionId == 0L || serviceSessionId != controllerSessionId) {
                return null
            }
            if (isControllerStickyTerminal(previous) &&
                serviceState is RecordingState.Stopping
            ) {
                return null
            }
            return serviceState
        }
    }
}

/** Saved/Failed/Review — Service Idle로 wipe 금지. Discard confirm은 [shouldApplyServiceIdleForDiscardConfirm]. */
internal fun isControllerStickyTerminal(state: RecordingState): Boolean =
    state is RecordingState.Saved ||
        state is RecordingState.Failed ||
        state is RecordingState.Review

/** Keep in-flight 동안 Discard/Start no-op. */
internal fun shouldIgnoreDiscardOrStartWhileKeepInFlight(keepInFlight: Boolean): Boolean =
    keepInFlight

/**
 * Discard 확인: Review sticky를 Service Idle로 내릴 유일한 경로.
 * BIND_AUTO_CREATE Idle은 [discardInFlight] false라 Review를 지우지 않는다.
 */
internal fun shouldApplyServiceIdleForDiscardConfirm(
    previous: RecordingState,
    serviceState: RecordingState,
    discardInFlight: Boolean,
): Boolean = discardInFlight &&
    previous is RecordingState.Review &&
    serviceState is RecordingState.Idle
