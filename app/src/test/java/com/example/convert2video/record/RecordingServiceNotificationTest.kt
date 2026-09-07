package com.example.convert2video.record

import com.example.convert2video.R
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext

class RecordingServiceNotificationTest {

    @Test
    fun success_notificationTextResIdFor_idle() {
        assertEquals(
            R.string.recording_notification_idle,
            notificationTextResIdFor(RecordingState.Idle),
        )
    }

    @Test
    fun success_notificationTextResIdFor_recording() {
        assertEquals(
            R.string.recording_notification_recording,
            notificationTextResIdFor(RecordingState.Recording(elapsedMs = 0L, amplitude = 0)),
        )
    }

    @Test
    fun success_notificationTextResIdFor_paused() {
        assertEquals(
            R.string.recording_notification_paused,
            notificationTextResIdFor(RecordingState.Paused(elapsedMs = 0L)),
        )
    }

    @Test
    fun success_notificationTextResIdFor_stopping() {
        assertEquals(
            R.string.recording_notification_stopping,
            notificationTextResIdFor(RecordingState.Stopping),
        )
    }

    @Test
    fun success_notificationTextResIdFor_stopped() {
        // Given
        val result = RecordingResult(
            file = File("dummy.m4a"),
            format = RecordingFormat.AAC,
            durationMs = 1_000L,
        )
        // When / Then
        assertEquals(
            R.string.recording_notification_stopped,
            notificationTextResIdFor(RecordingState.Stopped(result)),
        )
    }

    @Test
    fun success_notificationTextResIdFor_failed() {
        assertEquals(
            R.string.recording_notification_failed,
            notificationTextResIdFor(RecordingState.Failed(RecordingErrorCodes.CAPTURE_FAILED)),
        )
    }

    @Test
    fun success_notificationTextResIdFor_saved() {
        assertEquals(
            R.string.recording_notification_saved,
            notificationTextResIdFor(
                RecordingState.Saved(outputFile = File("dummy.m4a"), elapsedMs = 1_000L),
            ),
        )
        assertTrue(
            notificationTextResIdFor(
                RecordingState.Saved(outputFile = File("dummy.m4a"), elapsedMs = 1_000L),
            ) != R.string.recording_notification_stopped,
        )
    }

    @Test
    fun success_notificationTextResIdFor_review() {
        assertEquals(
            R.string.recording_notification_review,
            notificationTextResIdFor(
                RecordingState.Review(outputFile = File("dummy.m4a"), elapsedMs = 6_000L),
            ),
        )
    }

    @Test
    fun success_recordingErrorCodeToStringRes_mapsKnownCodes() {
        assertEquals(
            R.string.recording_start_failed,
            recordingErrorCodeToStringRes(RecordingErrorCodes.START_FAILED),
        )
        assertEquals(
            R.string.recording_index_failed,
            recordingErrorCodeToStringRes(RecordingErrorCodes.INDEX_FAILED),
        )
        assertEquals(
            R.string.recording_bind_failed,
            recordingErrorCodeToStringRes(RecordingErrorCodes.BIND_FAILED),
        )
        assertEquals(
            R.string.recording_foreground_start_denied,
            recordingErrorCodeToStringRes(RecordingErrorCodes.FOREGROUND_START_DENIED),
        )
        assertEquals(
            R.string.recording_permission_denied,
            recordingErrorCodeToStringRes(RecordingErrorCodes.PERMISSION_DENIED),
        )
        assertEquals(
            R.string.recording_notification_failed,
            recordingErrorCodeToStringRes("UNKNOWN"),
        )
    }

    @Test
    fun success_foregroundStartDenied_mapsToSpecificStringNotGenericFailed() {
        // Given / When — Failed 알림 contentText는 recordingErrorCodeToStringRes SSOT
        val resId = recordingErrorCodeToStringRes(RecordingErrorCodes.FOREGROUND_START_DENIED)
        // Then — recording_foreground_start_denied (generic notification_failed 아님)
        assertEquals(R.string.recording_foreground_start_denied, resId)
        assertTrue(resId != R.string.recording_notification_failed)
    }

    @Test
    fun success_failedBindWindow_isPositiveForControllerObserve() {
        // Given / When / Then — FG 성공 시에만 쓰는 delayed stopSelf 창
        assertTrue(RECORDING_FAILED_BIND_WINDOW_MS > 0L)
        assertTrue(RECORDING_FAILED_BIND_WINDOW_MS <= 2_000L)
    }

    @Test
    fun success_shouldScheduleDelayedStopSelf_onlyWhenEnteredForeground() {
        // Given / When / Then — FG 성공 → 지연 demote; FG 실패 → 즉시 stop (지연 금지)
        assertTrue(shouldScheduleDelayedStopSelf(enteredForeground = true))
        assertTrue(!shouldScheduleDelayedStopSelf(enteredForeground = false))
    }

    @Test
    fun success_shouldIgnoreStartWhileSessionBusy_blocksSecondStart() {
        // Given / When / Then — deny bind 창·녹음 중 재START ignore
        assertTrue(shouldIgnoreStartWhileSessionBusy(true))
        assertTrue(!shouldIgnoreStartWhileSessionBusy(false))
    }

    @Test
    fun success_handleRecordingScopeException_failedCallback_usesForegroundStartDeniedOnly() {
        // Given — A_seam SSOT #1: Failed 콜백 코드
        var failedCode: String? = null
        // When — 비취소 Throwable + Failed 콜백
        handleRecordingScopeException(
            tag = "RecordingServiceNotificationTest",
            throwable = RuntimeException("uncaught"),
        ) { code -> failedCode = code }
        // Then — FOREGROUND_START_DENIED만
        assertEquals(RecordingErrorCodes.FOREGROUND_START_DENIED, failedCode)
    }

    @Test
    fun success_handleRecordingScopeException_cancellation_rethrowsWithoutFailedCallback() {
        // Given — A_seam SSOT #2: Cancellation 정책 (direct + 1-level wrapped cause)
        // Policy: isRecordingScopeCancellation = CE || cause is CE
        assertTrue(isRecordingScopeCancellation(CancellationException("direct")))
        assertTrue(
            isRecordingScopeCancellation(RuntimeException("wrap", CancellationException("inner"))),
        )
        assertFalse(isRecordingScopeCancellation(RuntimeException("plain")))

        var failedOnDirect = false
        try {
            handleRecordingScopeException(
                tag = "RecordingServiceNotificationTest",
                throwable = CancellationException("cancelled"),
            ) { failedOnDirect = true }
            fail("expected CancellationException to be rethrown")
        } catch (ce: CancellationException) {
            assertEquals("cancelled", ce.message)
            assertFalse(failedOnDirect)
        }

        var failedOnWrapped = false
        try {
            handleRecordingScopeException(
                tag = "RecordingServiceNotificationTest",
                throwable = RuntimeException("wrap", CancellationException("inner")),
            ) { failedOnWrapped = true }
            fail("expected wrapped CancellationException cause to be rethrown")
        } catch (ce: CancellationException) {
            assertEquals("inner", ce.message)
            assertFalse(failedOnWrapped)
        }

        // CEH 경로: wrapped CE → Failed 콜백 금지 + log message는 class/message only
        val wrapped = RuntimeException("wrap", CancellationException("inner"))
        val logMsg = recordingScopeCancellationLogMessage(wrapped)
        assertTrue(logMsg.contains("RuntimeException"))
        assertTrue(logMsg.contains("CancellationException"))
        assertFalse(logMsg.contains("/"))
        var failedFromCeh = false
        val handler = recordingScopeExceptionHandler("RecordingServiceNotificationTest") { failedFromCeh = true }
        handler.handleException(EmptyCoroutineContext, wrapped)
        assertFalse(failedFromCeh)
    }

    @Test
    fun success_shouldDeleteOutputAfterKeepFailure_onlyWhenInsertFailed() {
        // Given / When / Then — insert 성공 후 enqueue 실패는 파일 삭제 금지
        assertTrue(shouldDeleteOutputAfterKeepFailure(insertSucceeded = false))
        assertFalse(shouldDeleteOutputAfterKeepFailure(insertSucceeded = true))
        assertTrue(shouldPublishSavedAfterKeepInsert(insertSucceeded = true))
        assertFalse(shouldPublishSavedAfterKeepInsert(insertSucceeded = false))
    }

    @Test
    fun success_reviewOccupancy_isNotActiveRecordingSession() {
        // Given — Tile active session ≠ Service file occupancy
        val review = RecordingState.Review(outputFile = File("dummy.m4a"), elapsedMs = 6_000L)
        // When / Then
        assertFalse(isActiveRecordingSession(review))
        assertTrue(shouldIgnoreStartWhileSessionBusy(isSessionActive = true))
    }

    @Test
    fun success_shouldExecuteKeep_requiresReviewOrPending_notIdleExtras() {
        // Given / When / Then — G1 state machine (Idle+extras alone = no insert)
        assertTrue(
            shouldExecuteKeep(
                stateIsReview = true,
                hasPendingReview = true,
                extrasPresent = false,
                extrasSessionMatches = false,
                extrasFileConfined = false,
            ),
        )
        assertTrue(
            shouldExecuteKeep(
                stateIsReview = false,
                hasPendingReview = true,
                extrasPresent = true,
                extrasSessionMatches = true,
                extrasFileConfined = true,
            ),
        )
        assertFalse(
            shouldExecuteKeep(
                stateIsReview = false,
                hasPendingReview = false,
                extrasPresent = true,
                extrasSessionMatches = true,
                extrasFileConfined = true,
            ),
        )
        assertTrue(
            shouldExecuteKeep(
                stateIsReview = true,
                hasPendingReview = false,
                extrasPresent = true,
                extrasSessionMatches = true,
                extrasFileConfined = true,
            ),
        )
        assertFalse(
            shouldExecuteKeep(
                stateIsReview = true,
                hasPendingReview = false,
                extrasPresent = true,
                extrasSessionMatches = false,
                extrasFileConfined = true,
            ),
        )
        assertFalse(
            shouldExecuteKeep(
                stateIsReview = true,
                hasPendingReview = false,
                extrasPresent = true,
                extrasSessionMatches = true,
                extrasFileConfined = false,
            ),
        )
    }

    @Test
    fun success_shouldNoOpReviewActionWithoutPending_idleMatchesKeepIgnore() {
        // Given / When / Then — G2: Idle+no pending = no-op, not stopSelf
        assertTrue(
            shouldNoOpReviewActionWithoutPending(stateIsReview = false, hasPendingReview = false),
        )
        assertFalse(
            shouldNoOpReviewActionWithoutPending(stateIsReview = true, hasPendingReview = false),
        )
        assertFalse(
            shouldNoOpReviewActionWithoutPending(stateIsReview = false, hasPendingReview = true),
        )
    }

    @Test
    fun success_sessionIdMatchesKeepExtras_rejectsZeroOrMismatch() {
        assertTrue(sessionIdMatchesKeepExtras(serviceSessionId = 9L, extrasSessionId = 9L))
        assertFalse(sessionIdMatchesKeepExtras(serviceSessionId = 9L, extrasSessionId = 0L))
        assertFalse(sessionIdMatchesKeepExtras(serviceSessionId = 9L, extrasSessionId = 8L))
        assertFalse(sessionIdMatchesKeepExtras(serviceSessionId = 0L, extrasSessionId = 0L))
    }

    @Test
    fun success_isReviewOutputConfined_storageDirChildOnly() {
        val dir = File.createTempFile("review-confine", "dir").apply {
            delete()
            mkdirs()
        }
        try {
            val inside = File(dir, "clip.m4a")
            val outside = File.createTempFile("review-outside", ".m4a")
            try {
                assertTrue(isReviewOutputConfined(inside, dir))
                assertFalse(isReviewOutputConfined(outside, dir))
                assertFalse(isReviewOutputConfined(dir, dir))
            } finally {
                outside.delete()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun success_notificationOngoingAndPauseStop_reviewVsTerminal() {
        val review = RecordingState.Review(outputFile = File("dummy.m4a"), elapsedMs = 6_000L)
        val saved = RecordingState.Saved(outputFile = File("dummy.m4a"), elapsedMs = 6_000L)
        val recording = RecordingState.Recording(elapsedMs = 0L, amplitude = 0)
        val failed = RecordingState.Failed(RecordingErrorCodes.CAPTURE_FAILED)
        assertTrue(notificationOngoingFor(review))
        assertTrue(notificationOngoingFor(recording))
        assertFalse(notificationOngoingFor(saved))
        assertFalse(notificationOngoingFor(failed))
        assertFalse(notificationHasPauseStopActions(review))
        assertFalse(notificationHasPauseStopActions(saved))
        assertFalse(notificationHasPauseStopActions(failed))
        assertTrue(notificationHasPauseStopActions(recording))
        assertTrue(notificationHasPauseStopActions(RecordingState.Stopping))
    }

    @Test
    fun success_shouldRemoveNotificationAfterReviewDetach_whenCannotNotify() {
        assertTrue(shouldRemoveNotificationAfterReviewDetach(canNotify = false))
        assertFalse(shouldRemoveNotificationAfterReviewDetach(canNotify = true))
    }

    @Test
    fun success_shouldClearStuckReviewInFlight_onlyWhenServiceNotRunning() {
        val review = RecordingState.Review(outputFile = File("dummy.m4a"), elapsedMs = 6_000L)
        assertTrue(
            shouldClearStuckReviewInFlight(
                stillInFlight = true,
                current = review,
                isServiceRunning = false,
            ),
        )
        assertFalse(
            shouldClearStuckReviewInFlight(
                stillInFlight = true,
                current = review,
                isServiceRunning = true,
            ),
        )
        assertFalse(
            shouldClearStuckReviewInFlight(
                stillInFlight = false,
                current = review,
                isServiceRunning = false,
            ),
        )
        assertFalse(
            shouldClearStuckReviewInFlight(
                stillInFlight = true,
                current = RecordingState.Idle,
                isServiceRunning = false,
            ),
        )
    }

    @Test
    fun success_recordingElapsedMsOrZero_recordingAndPaused() {
        assertEquals(
            12_000L,
            recordingElapsedMsOrZero(RecordingState.Recording(elapsedMs = 12_000L, amplitude = 1)),
        )
        assertEquals(
            8_000L,
            recordingElapsedMsOrZero(RecordingState.Paused(elapsedMs = 8_000L)),
        )
    }

    @Test
    fun success_recordingElapsedMsOrZero_idleIsZero() {
        assertEquals(0L, recordingElapsedMsOrZero(RecordingState.Idle))
    }
}
