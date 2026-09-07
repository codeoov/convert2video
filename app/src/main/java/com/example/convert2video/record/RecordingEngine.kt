package com.example.convert2video.record

import android.content.Context
import android.os.SystemClock
import com.example.convert2video.R
import com.example.convert2video.data.SettingsRepository
import com.example.convert2video.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Engine/Service Failed에 쓰는 안정 에러 코드 — 사용자 문자열은 [recordingErrorCodeToStringRes] + getString. */
object RecordingErrorCodes {
    const val START_FAILED = "START_FAILED"
    const val PAUSE_FAILED = "PAUSE_FAILED"
    const val RESUME_FAILED = "RESUME_FAILED"
    const val STOP_FAILED = "STOP_FAILED"
    const val CAPTURE_FAILED = "CAPTURE_FAILED"
    const val OUTPUT_MISSING = "OUTPUT_MISSING"
    const val INDEX_FAILED = "INDEX_FAILED"
    /** 레거시/미사용(Controller start는 emit 안 함). 상수·매핑·string은 호환용 유지. */
    const val BIND_FAILED = "BIND_FAILED"
    /**
     * start 거부(공유 코드). START_FAILED와 혼용 금지.
     *
     * 이 코드는 FGS deny뿐 아니라 Controller busy / startForegroundService
     * IllegalStateException / Idle+expectedSessionId 워치독에도 재사용된다.
     * 따라서 user string([R.string.recording_foreground_start_denied])은
     * FGS·백그라운드 전용 단정을 넣지 말 것(시점·앱 열어 재시도만).
     *
     * Emit 사이트(코드 수정 없이 고정 참조, @see ↔ 실제 emit 1:1):
     * @see RecordingController.start — busy([RecordingService.isRunning]),
     *   [androidx.core.content.ContextCompat.startForegroundService] IllegalStateException,
     *   stuck Idle+expectedSessionId 워치독
     * @see RecordingService — `startAsForeground` 실패 시 Failed(FOREGROUND_START_DENIED)
     *   (microphone SecurityException/IllegalStateException; FG 실패 시 즉시 stopSelf)
     */
    const val FOREGROUND_START_DENIED = "FOREGROUND_START_DENIED"
    /**
     * RECORD_AUDIO 미허용.
     * Controller 선검사가 예방; Service 도달 시 Failed 노출 후 FG 성공 여부에 따라
     * 지연 demote 또는 즉시 stopSelf (microphone FG 재시도로 타임아웃을 해결하지 않음).
     */
    const val PERMISSION_DENIED = "PERMISSION_DENIED"
    /**
     * 저장 허용 최소 녹음 길이(ms). [isTooShortForSave] / [RecordingService.handleStop] SSOT.
     * User-facing `recording_too_short`의 ‘5초’는 [MIN_SAVE_DURATION_MS]와 수동 동기 — 변경 시 strings.xml도.
     */
    const val MIN_SAVE_DURATION_MS = 5_000L
    /** 녹음이 너무 짧아 저장하지 않음 — l10n: recording_too_short. 임계값: [MIN_SAVE_DURATION_MS]. */
    const val TOO_SHORT = "TOO_SHORT"

    /** `durationMs <= [MIN_SAVE_DURATION_MS]` 이면 저장/인덱싱 불가. */
    fun isTooShortForSave(durationMs: Long): Boolean =
        durationMs <= MIN_SAVE_DURATION_MS
}

/**
 * Failed errorCode → 사용자 노출 string res (예외 메시지 아님). Service·ViewModel 공유 SSOT.
 * unknown 코드는 [AppLogger.w] 후 Fallback — W는 persist sink로 ErrorLog 저장 가능.
 */
internal fun recordingErrorCodeToStringRes(errorCode: String): Int =
    when (errorCode) {
        RecordingErrorCodes.START_FAILED -> R.string.recording_start_failed
        RecordingErrorCodes.PAUSE_FAILED -> R.string.recording_pause_failed
        RecordingErrorCodes.RESUME_FAILED -> R.string.recording_resume_failed
        RecordingErrorCodes.STOP_FAILED -> R.string.recording_stop_failed
        RecordingErrorCodes.CAPTURE_FAILED -> R.string.recording_capture_failed
        RecordingErrorCodes.OUTPUT_MISSING -> R.string.recording_output_missing
        RecordingErrorCodes.INDEX_FAILED -> R.string.recording_index_failed
        RecordingErrorCodes.BIND_FAILED -> R.string.recording_bind_failed
        RecordingErrorCodes.FOREGROUND_START_DENIED -> R.string.recording_foreground_start_denied
        RecordingErrorCodes.PERMISSION_DENIED -> R.string.recording_permission_denied
        RecordingErrorCodes.TOO_SHORT -> R.string.recording_too_short
        else -> {
            // record 패턴: PascalCase 타입/파일명 TAG (RecordingFormat/RecordingEngine 등)
            AppLogger.w(
                RECORDING_ERROR_CODE_TAG,
                "Unknown recording errorCode='$errorCode'; falling back to recording_notification_failed",
            )
            R.string.recording_notification_failed
        }
    }

/** [recordingErrorCodeToStringRes] unknown 분기 AppLogger tag — record PascalCase 패턴. */
private const val RECORDING_ERROR_CODE_TAG = "RecordingErrorCodes"

/**
 * TOO_SHORT / INDEX_FAILED 산출물 삭제. basename만 로그(전체 경로 금지).
 * 실패하고 파일이 남으면 1회 재시도. @return 최종 delete 성공 여부.
 */
internal fun deleteRecordingOutputOrLog(
    file: File,
    reason: String,
    tag: String = "RecordingDelete",
): Boolean {
    fun attempt(): Boolean =
        runCatching { file.delete() }.getOrElse { e ->
            AppLogger.e(tag, "delete threw ($reason): ${file.name}", e)
            false
        }
    var deleted = attempt()
    if (!deleted && file.exists()) {
        deleted = attempt()
    }
    if (!deleted) {
        AppLogger.w(tag, "delete failed ($reason): ${file.name}")
    }
    return deleted
}

sealed interface RecordingState {
    data object Idle : RecordingState

    /** 캡처 중. [elapsedMs]·[amplitude]는 Engine ~100ms 틱으로 갱신. */
    data class Recording(val elapsedMs: Long, val amplitude: Int) : RecordingState

    /** 일시정지. [elapsedMs]는 pause 시점 누적값. */
    data class Paused(val elapsedMs: Long) : RecordingState

    /**
     * Service 전용: STOP 직후 Repo 인덱싱 중.
     * Engine은 이 상태를 emit하지 않는다. binder가 Recording/Paused에 남지 않게 한다.
     */
    data object Stopping : RecordingState

    data class Stopped(val result: RecordingResult) : RecordingState

    /**
     * Service/Controller 전용: Room insert 성공 후 UI가 파일·경과를 관찰.
     * Engine은 emit하지 않는다.
     * Controller에서는 sticky — [RecordingController.clearTerminalState] 또는 다음 start로만 해제.
     */
    data class Saved(val outputFile: File, val elapsedMs: Long) : RecordingState

    /**
     * Service/Controller 전용: STOP 후 Keep 전 검토.
     * Engine은 emit하지 않는다 (Saved와 동일).
     * Room insert·Drive/auto-convert enqueue는 Keep 이후에만.
     * Controller에서는 sticky — [RecordingController.keep]/[RecordingController.discard]로만 해제.
     * [RecordingController.clearTerminalState]는 Review를 지우지 않는다.
     */
    data class Review(val outputFile: File, val elapsedMs: Long) : RecordingState

    /** [errorCode]는 [RecordingErrorCodes] 값. 예외 메시지는 AppLogger만. */
    data class Failed(val errorCode: String) : RecordingState
}

data class RecordingResult(
    val file: File,
    val format: RecordingFormat,
    val durationMs: Long,
)

/** 실제 오디오 캡처 호출을 감추어 JVM에서 상태 머신을 검증할 수 있게 한다. */
internal interface AudioCaptureBackend {
    /**
     * @param onCaptureFailed 캡처 스레드 등 비동기 실패 시 즉시 호출(Engine이 Failed로 전이).
     */
    fun start(outputFile: File, onCaptureFailed: (Exception) -> Unit = {})
    fun pause()
    fun resume()
    /** 파일 최종화(WAV 헤더 패치 포함) + 리소스 해제 */
    fun stop()
    /** 유효한 파일 보장 없는 비상 정리, 멱등 */
    fun release()
    /** UI 미터용 피크 힌트. 대략 0..32767, 불가 시 0. */
    fun amplitude(): Int
}

/**
 * 녹음 상태 머신.
 * Room 연동은 하지 않는다 — Service가 [RecordingResult]를 Repository에 넘긴다.
 */
class RecordingEngine internal constructor(
    private val backendFactory: (RecordingFormat) -> AudioCaptureBackend,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    /** 저장 디렉터리 공급자. 기본 생성([create])은 [C2vRecordingNames.appStorageDir]을 닫는다. */
    private val resolveStorageDir: () -> File,
    private val tickIntervalMs: Long = DEFAULT_TICK_INTERVAL_MS,
    private val engineScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private var backend: AudioCaptureBackend? = null
    private var activeFormat: RecordingFormat? = null
    private var outputFile: File? = null
    private var accumulatedMs: Long = 0L
    private var segmentStartMs: Long? = null
    private var tickJob: Job? = null

    fun start(format: RecordingFormat): Boolean {
        val created: AudioCaptureBackend
        val claimedFile: File
        synchronized(this) {
            if (_state.value !is RecordingState.Idle) return false
            try {
                val destDir = resolveStorageDir()
                val existing = destDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
                val preferred = C2vRecordingNames.buildDisplayName(
                    existingNames = existing,
                    format = format,
                )
                val claimed = C2vRecordingNames.claimUniqueDestFile(destDir, preferred)
                created = backendFactory(format)
                backend = created
                activeFormat = format
                outputFile = claimed.destFile
                accumulatedMs = 0L
                claimedFile = claimed.destFile
            } catch (e: Exception) {
                AppLogger.e(TAG, "start prepare failed: ${e.message}", e)
                safeReleaseBackend()
                outputFile?.delete()
                clearSessionFields()
                _state.value = RecordingState.Failed(RecordingErrorCodes.START_FAILED)
                return false
            }
        }
        // backend.start는 락 밖에서 — 동기 onCaptureFailed 재진입 데드락 방지.
        return try {
            created.start(claimedFile, onCaptureFailed = ::handleAsyncCaptureFailure)
            synchronized(this) {
                if (_state.value is RecordingState.Failed) return false
                segmentStartMs = elapsedRealtimeMs()
                emitRecordingLocked()
                startTickerLocked()
                true
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "start failed: ${e.message}", e)
            synchronized(this) {
                transitionToFailed(RecordingErrorCodes.START_FAILED)
            }
            false
        }
    }

    @Synchronized
    fun pause(): Boolean {
        if (_state.value !is RecordingState.Recording) return false
        return try {
            backend?.pause()
            val start = segmentStartMs
            if (start != null) {
                accumulatedMs += (elapsedRealtimeMs() - start).coerceAtLeast(0L)
                segmentStartMs = null
            }
            stopTickerLocked()
            _state.value = RecordingState.Paused(elapsedMs = accumulatedMs)
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "pause failed: ${e.message}", e)
            transitionToFailed(RecordingErrorCodes.PAUSE_FAILED)
            false
        }
    }

    @Synchronized
    fun resume(): Boolean {
        if (_state.value !is RecordingState.Paused) return false
        return try {
            backend?.resume()
            segmentStartMs = elapsedRealtimeMs()
            emitRecordingLocked()
            startTickerLocked()
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "resume failed: ${e.message}", e)
            transitionToFailed(RecordingErrorCodes.RESUME_FAILED)
            false
        }
    }

    @Synchronized
    fun stop(): Boolean {
        val current = _state.value
        if (current !is RecordingState.Recording && current !is RecordingState.Paused) return false
        return try {
            stopTickerLocked()
            val start = segmentStartMs
            if (current is RecordingState.Recording && start != null) {
                accumulatedMs += (elapsedRealtimeMs() - start).coerceAtLeast(0L)
            }
            segmentStartMs = null
            backend?.stop()
            val file = outputFile
            val format = activeFormat
            if (file == null || format == null) {
                AppLogger.e(TAG, "stop: output missing")
                transitionToFailed(RecordingErrorCodes.OUTPUT_MISSING)
                return false
            }
            val result = RecordingResult(
                file = file,
                format = format,
                durationMs = accumulatedMs,
            )
            backend = null
            outputFile = null
            activeFormat = null
            accumulatedMs = 0L
            _state.value = RecordingState.Stopped(result)
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "stop failed: ${e.message}", e)
            transitionToFailed(RecordingErrorCodes.STOP_FAILED)
            false
        }
    }

    @Synchronized
    fun release() {
        stopTickerLocked()
        safeReleaseBackend()
        clearSessionFields()
        if (_state.value !is RecordingState.Idle) {
            _state.value = RecordingState.Idle
        }
    }

    /** 캡처 스레드 비동기 실패 — 즉시 Failed (Service가 Recording에 갇히지 않도록). */
    private fun handleAsyncCaptureFailure(e: Exception) {
        synchronized(this) {
            val current = _state.value
            val startInProgress = current is RecordingState.Idle && backend != null
            if (current !is RecordingState.Recording &&
                current !is RecordingState.Paused &&
                !startInProgress
            ) {
                return
            }
            AppLogger.e(TAG, "async capture failed: ${e.message}", e)
            transitionToFailed(RecordingErrorCodes.CAPTURE_FAILED)
        }
    }

    private fun transitionToFailed(errorCode: String) {
        stopTickerLocked()
        safeReleaseBackend()
        outputFile?.delete()
        clearSessionFields()
        _state.value = RecordingState.Failed(errorCode)
    }

    private fun clearSessionFields() {
        outputFile = null
        activeFormat = null
        accumulatedMs = 0L
        segmentStartMs = null
    }

    private fun safeReleaseBackend() {
        try {
            backend?.release()
        } catch (e: Exception) {
            AppLogger.w(TAG, "backend release: ${e.message}", e)
        }
        backend = null
    }

    private fun currentElapsedMsLocked(): Long {
        val start = segmentStartMs
        return if (start != null) {
            accumulatedMs + (elapsedRealtimeMs() - start).coerceAtLeast(0L)
        } else {
            accumulatedMs
        }
    }

    private fun readAmplitudeLocked(): Int =
        try {
            backend?.amplitude() ?: 0
        } catch (e: Exception) {
            AppLogger.w(TAG, "amplitude read: ${e.message}", e)
            0
        }

    private fun emitRecordingLocked() {
        _state.value = RecordingState.Recording(
            elapsedMs = currentElapsedMsLocked(),
            amplitude = readAmplitudeLocked(),
        )
    }

    private fun startTickerLocked() {
        stopTickerLocked()
        tickJob = engineScope.launch {
            while (isActive) {
                delay(tickIntervalMs)
                synchronized(this@RecordingEngine) {
                    if (_state.value is RecordingState.Recording) {
                        emitRecordingLocked()
                    }
                }
            }
        }
    }

    private fun stopTickerLocked() {
        tickJob?.cancel()
        tickJob = null
    }

    companion object {
        private const val TAG = "RecordingEngine"
        const val DEFAULT_TICK_INTERVAL_MS = 100L

        /** AAC/WAV 실백엔드를 연결한 기본 팩토리. */
        fun create(context: Context): RecordingEngine {
            val appContext = context.applicationContext
            val noiseMode = SettingsRepository.noiseReductionModeHot.value
                ?: NoiseReductionMode.DeviceDefault.also {
                    AppLogger.d(TAG, "noiseReductionModeHot not seeded yet; falling back to DeviceDefault")
                }
            val micSource = SettingsRepository.microphoneSourceHot.value
                ?: MicrophoneSource.Default.also {
                    AppLogger.d(TAG, "microphoneSourceHot not seeded yet; falling back to Default")
                }
            return RecordingEngine(
                backendFactory = { format ->
                    when (format) {
                        RecordingFormat.AAC -> MediaRecorderAacBackend(appContext, micSource)
                        RecordingFormat.WAV -> AudioRecordWavBackend(noiseMode, micSource, appContext)
                    }
                },
                resolveStorageDir = { C2vRecordingNames.appStorageDir(appContext) },
            )
        }
    }
}
