package com.example.convert2video.ui.screens.record

import com.example.convert2video.record.RecordingErrorCodes
import com.example.convert2video.record.RecordingFormat
import com.example.convert2video.record.RecordingResult
import com.example.convert2video.record.RecordingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [toRecordUiState] 순수 매핑 단위 테스트 (Sprint 2-1).
 *
 * [errorMessages] 값은 `res/values/strings.xml`의 recording_* 문자열과 1:1 동일해야 한다.
 */
class RecordUiStateMappingTest {

    /** strings.xml recording_* 와 1:1 (변경 시 리소스도 함께 맞출 것). */
    private val errorMessages = mapOf(
        RecordingErrorCodes.START_FAILED to "녹음을 시작할 수 없습니다",
        RecordingErrorCodes.PAUSE_FAILED to "일시정지에 실패했습니다",
        RecordingErrorCodes.RESUME_FAILED to "녹음 재개에 실패했습니다",
        RecordingErrorCodes.STOP_FAILED to "녹음 정지에 실패했습니다",
        RecordingErrorCodes.CAPTURE_FAILED to "녹음 중 오류가 발생했습니다",
        RecordingErrorCodes.OUTPUT_MISSING to "녹음 파일을 찾을 수 없습니다",
        RecordingErrorCodes.INDEX_FAILED to "녹음 저장에 실패했습니다",
        RecordingErrorCodes.BIND_FAILED to "녹음 서비스에 연결할 수 없습니다",
        RecordingErrorCodes.PERMISSION_DENIED to "마이크 권한이 필요합니다",
        RecordingErrorCodes.TOO_SHORT to "5초 이하 녹음은 저장되지 않았습니다",
    )

    /** strings.xml `recording_notification_failed` 와 1:1. */
    private val unknownFallback = "녹음 실패"

    private fun map(
        state: RecordingState,
        hasPermission: Boolean = true,
        canRequestAgain: Boolean = true,
    ): RecordUiState = toRecordUiState(
        recordingState = state,
        hasPermission = hasPermission,
        canRequestAgain = canRequestAgain,
        errorMessageFor = { code -> errorMessages[code] ?: unknownFallback },
    )

    private fun stoppedState(): RecordingState.Stopped = RecordingState.Stopped(
        RecordingResult(
            file = File("/tmp/stopped.m4a"),
            format = RecordingFormat.AAC,
            durationMs = 1000L,
        ),
    )

    @Test
    fun success_noPermission_mapsToPermissionDenied() {
        // Given
        val state = RecordingState.Idle
        // When
        val result = map(state, hasPermission = false, canRequestAgain = true)
        // Then
        assertEquals(RecordUiState.PermissionDenied(canRequestAgain = true), result)
    }

    @Test
    fun success_noPermissionCannotRequestAgain_mapsToPermissionDenied() {
        // Given
        val state = RecordingState.Idle
        // When
        val result = map(state, hasPermission = false, canRequestAgain = false)
        // Then
        assertEquals(RecordUiState.PermissionDenied(canRequestAgain = false), result)
    }

    @Test
    fun success_permissionPlusIdle_mapsToIdle() {
        // Given
        val state = RecordingState.Idle
        // When
        val result = map(state, hasPermission = true)
        // Then
        assertEquals(RecordUiState.Idle, result)
    }

    @Test
    fun success_recording_passThroughElapsedAndAmplitude() {
        // Given
        val state = RecordingState.Recording(elapsedMs = 12_345L, amplitude = 77)
        // When
        val result = map(state)
        // Then
        assertEquals(RecordUiState.Recording(elapsedMs = 12_345L, amplitude = 77), result)
    }

    @Test
    fun success_paused_passThroughElapsed() {
        // Given
        val state = RecordingState.Paused(elapsedMs = 9_000L)
        // When
        val result = map(state)
        // Then
        assertEquals(RecordUiState.Paused(elapsedMs = 9_000L), result)
    }

    @Test
    fun success_stopping_mapsToSaving() {
        // Given
        val state = RecordingState.Stopping
        // When
        val result = map(state)
        // Then
        assertEquals(RecordUiState.Saving, result)
    }

    @Test
    fun success_saved_mapsToSavedWithFileAndElapsed() {
        // Given
        val file = File("/tmp/c2v-test.m4a")
        val state = RecordingState.Saved(outputFile = file, elapsedMs = 4_200L)
        // When
        val result = map(state)
        // Then
        assertEquals(RecordUiState.Saved(outputFile = file, elapsedMs = 4_200L), result)
    }

    @Test
    fun success_review_mapsToReviewNotSaved() {
        // Given
        val file = File("/tmp/c2v-review.m4a")
        val state = RecordingState.Review(outputFile = file, elapsedMs = 6_000L)
        // When
        val result = map(state)
        // Then
        assertEquals(RecordUiState.Review(outputFile = file, elapsedMs = 6_000L), result)
        assertTrue(result !is RecordUiState.Saved)
        assertTrue(result !is RecordUiState.Saving)
    }

    @Test
    fun success_review_keepsReviewWhenPermissionRevoked() {
        // Given — sticky Review는 권한 철회여도 PermissionDenied로 덮지 않음
        val file = File("/tmp/review.m4a")
        val state = RecordingState.Review(outputFile = file, elapsedMs = 6_000L)
        // When
        val result = map(state, hasPermission = false, canRequestAgain = true)
        // Then
        assertEquals(RecordUiState.Review(outputFile = file, elapsedMs = 6_000L), result)
    }

    @Test
    fun success_stopped_mapsToSaving() {
        // Given — Engine-only variant; treat as in-flight finalize for exhaustiveness.
        val state = stoppedState()
        // When
        val result = map(state)
        // Then
        assertEquals(RecordUiState.Saving, result)
    }

    @Test
    fun success_activeSessionRecording_keepsRecordingWhenPermissionRevoked() {
        // Given — 활성 세션은 권한 철회여도 PermissionDenied로 덮지 않음
        val state = RecordingState.Recording(elapsedMs = 1L, amplitude = 1)
        // When
        val result = map(state, hasPermission = false, canRequestAgain = true)
        // Then
        assertEquals(RecordUiState.Recording(elapsedMs = 1L, amplitude = 1), result)
    }

    @Test
    fun success_activeSessionPaused_keepsPausedWhenPermissionRevoked() {
        // Given
        val state = RecordingState.Paused(elapsedMs = 500L)
        // When
        val result = map(state, hasPermission = false, canRequestAgain = false)
        // Then
        assertEquals(RecordUiState.Paused(elapsedMs = 500L), result)
    }

    @Test
    fun success_activeSessionStopping_keepsSavingWhenPermissionRevoked() {
        // Given
        val state = RecordingState.Stopping
        // When
        val result = map(state, hasPermission = false)
        // Then
        assertEquals(RecordUiState.Saving, result)
    }

    @Test
    fun success_activeSessionStopped_keepsSavingWhenPermissionRevoked() {
        // Given
        val state = stoppedState()
        // When
        val result = map(state, hasPermission = false)
        // Then
        assertEquals(RecordUiState.Saving, result)
    }

    @Test
    fun success_saved_keepsSavedWhenPermissionRevoked() {
        // Given — sticky Saved는 권한 철회여도 PermissionDenied로 덮지 않음
        val file = File("/tmp/sticky.m4a")
        val state = RecordingState.Saved(outputFile = file, elapsedMs = 3_000L)
        // When
        val result = map(state, hasPermission = false, canRequestAgain = true)
        // Then
        assertEquals(RecordUiState.Saved(outputFile = file, elapsedMs = 3_000L), result)
    }

    @Test
    fun success_failedNonPermission_keepsErrorWhenPermissionRevoked() {
        // Given — sticky Failed(비권한)는 권한 오버레이 금지
        val state = RecordingState.Failed(RecordingErrorCodes.INDEX_FAILED)
        // When
        val result = map(state, hasPermission = false, canRequestAgain = true)
        // Then
        assertEquals(RecordUiState.Error(message = "녹음 저장에 실패했습니다"), result)
    }

    @Test
    fun success_failedTooShort_keepsErrorWhenPermissionRevoked() {
        // Given — sticky Failed(TOO_SHORT)는 권한 오버레이 금지
        val state = RecordingState.Failed(RecordingErrorCodes.TOO_SHORT)
        // When
        val result = map(state, hasPermission = false, canRequestAgain = true)
        // Then
        assertEquals(
            RecordUiState.Error(message = errorMessages.getValue(RecordingErrorCodes.TOO_SHORT)),
            result,
        )
    }

    @Test
    fun success_failedPermissionDenied_withoutPermission_mapsToPermissionDenied() {
        // Given
        val state = RecordingState.Failed(RecordingErrorCodes.PERMISSION_DENIED)
        // When
        val result = map(state, hasPermission = false, canRequestAgain = true)
        // Then
        assertEquals(RecordUiState.PermissionDenied(canRequestAgain = true), result)
    }

    @Test
    fun success_failedPermissionDenied_withPermission_mapsToIdle() {
        // Given — 권한 복구 후 stale Failed(PERMISSION_DENIED) → Idle 폐기
        val state = RecordingState.Failed(RecordingErrorCodes.PERMISSION_DENIED)
        // When
        val result = map(state, hasPermission = true, canRequestAgain = true)
        // Then
        assertEquals(RecordUiState.Idle, result)
    }

    @Test
    fun success_failedPermissionDenied_respectsCanRequestAgainFalse() {
        // Given
        val state = RecordingState.Failed(RecordingErrorCodes.PERMISSION_DENIED)
        // When
        val result = map(state, hasPermission = false, canRequestAgain = false)
        // Then
        assertEquals(RecordUiState.PermissionDenied(canRequestAgain = false), result)
    }

    @Test
    fun failure_failed_mapsToErrorWithUserMessage() {
        // Given
        val state = RecordingState.Failed(RecordingErrorCodes.INDEX_FAILED)
        // When
        val result = map(state)
        // Then
        assertTrue(result is RecordUiState.Error)
        assertEquals("녹음 저장에 실패했습니다", (result as RecordUiState.Error).message)
    }

    @Test
    fun failure_startFailed_mapsToErrorWithUserMessage() {
        // Given
        val state = RecordingState.Failed(RecordingErrorCodes.START_FAILED)
        // When
        val result = map(state)
        // Then
        assertEquals(RecordUiState.Error(message = "녹음을 시작할 수 없습니다"), result)
    }

    @Test
    fun failure_pauseFailed_mapsToErrorWithUserMessage() {
        // Given
        val state = RecordingState.Failed(RecordingErrorCodes.PAUSE_FAILED)
        // When
        val result = map(state)
        // Then
        assertEquals(RecordUiState.Error(message = "일시정지에 실패했습니다"), result)
    }

    @Test
    fun failure_resumeFailed_mapsToErrorWithUserMessage() {
        // Given
        val state = RecordingState.Failed(RecordingErrorCodes.RESUME_FAILED)
        // When
        val result = map(state)
        // Then
        assertEquals(RecordUiState.Error(message = "녹음 재개에 실패했습니다"), result)
    }

    @Test
    fun failure_stopFailed_mapsToErrorWithUserMessage() {
        // Given
        val state = RecordingState.Failed(RecordingErrorCodes.STOP_FAILED)
        // When
        val result = map(state)
        // Then
        assertEquals(RecordUiState.Error(message = "녹음 정지에 실패했습니다"), result)
    }

    @Test
    fun failure_captureFailed_mapsToErrorWithUserMessage() {
        // Given
        val state = RecordingState.Failed(RecordingErrorCodes.CAPTURE_FAILED)
        // When
        val result = map(state)
        // Then
        assertEquals(RecordUiState.Error(message = "녹음 중 오류가 발생했습니다"), result)
    }

    @Test
    fun failure_outputMissing_mapsToErrorWithUserMessage() {
        // Given
        val state = RecordingState.Failed(RecordingErrorCodes.OUTPUT_MISSING)
        // When
        val result = map(state)
        // Then
        assertEquals(RecordUiState.Error(message = "녹음 파일을 찾을 수 없습니다"), result)
    }

    @Test
    fun failure_bindFailed_mapsToErrorWithUserMessage() {
        // Given
        val state = RecordingState.Failed(RecordingErrorCodes.BIND_FAILED)
        // When
        val result = map(state)
        // Then
        assertEquals(RecordUiState.Error(message = "녹음 서비스에 연결할 수 없습니다"), result)
    }

    @Test
    fun failure_tooShort_mapsToErrorWithUserMessage() {
        // Given
        val state = RecordingState.Failed(RecordingErrorCodes.TOO_SHORT)
        // When
        val result = map(state)
        // Then
        assertEquals(
            RecordUiState.Error(message = errorMessages.getValue(RecordingErrorCodes.TOO_SHORT)),
            result,
        )
    }

    @Test
    fun failure_unknownErrorCode_mapsToFallbackMessage() {
        // Given
        val state = RecordingState.Failed("NOT_A_REAL_CODE")
        // When
        val result = map(state)
        // Then
        assertEquals(RecordUiState.Error(message = unknownFallback), result)
    }

    @Test
    fun failure_unknownErrorCode_keepsErrorWhenPermissionRevoked() {
        // Given — sticky Failed(unknown)도 권한 오버레이 금지
        val state = RecordingState.Failed("NOT_A_REAL_CODE")
        // When
        val result = map(state, hasPermission = false)
        // Then
        assertEquals(RecordUiState.Error(message = unknownFallback), result)
    }
}
