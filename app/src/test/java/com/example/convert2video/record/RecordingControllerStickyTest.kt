package com.example.convert2video.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Controller sticky merge 순수 함수 단위 테스트 (Robolectric 없음).
 */
class RecordingControllerStickyTest {

    private val saved = RecordingState.Saved(
        outputFile = File("a.m4a"),
        elapsedMs = 1_000L,
    )

    @Test
    fun success_idle_doesNotWipeSavedSticky() {
        // Given / When
        val next = mergeRecordingControllerState(
            previous = saved,
            serviceState = RecordingState.Idle,
            controllerSessionId = 1L,
            serviceSessionId = 0L,
        )
        // Then
        assertNull(next)
    }

    @Test
    fun success_staleSaved_fromOldSession_doesNotReviveStickyAfterRestartIdle() {
        // Given — start() cleared sticky to Idle and issued session 2
        // When — lingering Service still exposes session-1 Saved
        val next = mergeRecordingControllerState(
            previous = RecordingState.Idle,
            serviceState = saved,
            controllerSessionId = 2L,
            serviceSessionId = 1L,
        )
        // Then
        assertNull(next)
    }

    @Test
    fun success_matchingSession_recordingAccepted() {
        // Given / When
        val recording = RecordingState.Recording(elapsedMs = 100L, amplitude = 10)
        val next = mergeRecordingControllerState(
            previous = RecordingState.Idle,
            serviceState = recording,
            controllerSessionId = 3L,
            serviceSessionId = 3L,
        )
        // Then
        assertEquals(recording, next)
    }

    @Test
    fun success_matchingSession_savedAcceptedFromStoppingPath() {
        // Given / When
        val next = mergeRecordingControllerState(
            previous = RecordingState.Stopping,
            serviceState = saved,
            controllerSessionId = 4L,
            serviceSessionId = 4L,
        )
        // Then
        assertEquals(saved, next)
    }

    @Test
    fun success_failedPermission_matchingSessionAccepted() {
        // Given / When
        val failed = RecordingState.Failed(RecordingErrorCodes.PERMISSION_DENIED)
        val next = mergeRecordingControllerState(
            previous = RecordingState.Idle,
            serviceState = failed,
            controllerSessionId = 5L,
            serviceSessionId = 5L,
        )
        // Then
        assertEquals(failed, next)
    }

    @Test
    fun success_stopped_neverExposedOnController() {
        // Given
        val result = RecordingResult(
            file = File("x.m4a"),
            format = RecordingFormat.AAC,
            durationMs = 10L,
        )
        // When
        val next = mergeRecordingControllerState(
            previous = RecordingState.Recording(0L, 0),
            serviceState = RecordingState.Stopped(result),
            controllerSessionId = 6L,
            serviceSessionId = 6L,
        )
        // Then
        assertNull(next)
    }

    @Test
    fun success_zeroControllerSession_rejectsActiveStates() {
        // Given / When
        val next = mergeRecordingControllerState(
            previous = RecordingState.Idle,
            serviceState = RecordingState.Recording(0L, 0),
            controllerSessionId = 0L,
            serviceSessionId = 0L,
        )
        // Then
        assertNull(next)
    }

    @Test
    fun success_idle_appliesWhenNotTerminal() {
        // Given / When
        val next = mergeRecordingControllerState(
            previous = RecordingState.Idle,
            serviceState = RecordingState.Idle,
            controllerSessionId = 1L,
            serviceSessionId = 0L,
        )
        // Then
        assertEquals(RecordingState.Idle, next)
        assertTrue(next is RecordingState.Idle)
    }

    @Test
    fun success_foregroundStartDenied_sticky_notWipedByServiceSavedOrRecording() {
        // Given — FGS deny sticky, controllerSessionId 리셋(0)
        val denied = RecordingState.Failed(RecordingErrorCodes.FOREGROUND_START_DENIED)
        // When — Service가 Saved/Recording을 내도 merge null → deny sticky 유지
        assertNull(
            mergeRecordingControllerState(
                previous = denied,
                serviceState = saved,
                controllerSessionId = 0L,
                serviceSessionId = 9L,
            ),
        )
        assertNull(
            mergeRecordingControllerState(
                previous = denied,
                serviceState = RecordingState.Recording(elapsedMs = 0L, amplitude = 0),
                controllerSessionId = 0L,
                serviceSessionId = 9L,
            ),
        )
    }

    @Test
    fun success_shouldClearStuckExpectedSession_onlyWhenIdleMatches() {
        // Given / When / Then — Idle+동일 id+Service 비활성 → 고착 클리어
        assertTrue(
            shouldClearStuckExpectedSession(
                expectedSessionId = 7L,
                startSessionId = 7L,
                current = RecordingState.Idle,
                isServiceRunning = false,
            ),
        )
        // Recording 중이면 클리어 금지
        assertTrue(
            !shouldClearStuckExpectedSession(
                expectedSessionId = 7L,
                startSessionId = 7L,
                current = RecordingState.Recording(elapsedMs = 0L, amplitude = 0),
                isServiceRunning = false,
            ),
        )
        // Failed 미러 완료 시 Idle이 아니므로 클리어 금지
        assertTrue(
            !shouldClearStuckExpectedSession(
                expectedSessionId = 7L,
                startSessionId = 7L,
                current = RecordingState.Failed(RecordingErrorCodes.FOREGROUND_START_DENIED),
                isServiceRunning = false,
            ),
        )
        // 세션 id 불일치
        assertTrue(
            !shouldClearStuckExpectedSession(
                expectedSessionId = 8L,
                startSessionId = 7L,
                current = RecordingState.Idle,
                isServiceRunning = false,
            ),
        )
    }

    @Test
    fun success_shouldClearStuckExpectedSession_blocksWhenServiceStillRunning() {
        // Given — Controller Idle이어도 Service 활성(Recording 등)이면 워치독 clear 금지
        // When / Then
        assertTrue(
            !shouldClearStuckExpectedSession(
                expectedSessionId = 11L,
                startSessionId = 11L,
                current = RecordingState.Idle,
                isServiceRunning = true,
            ),
        )
        // Service 비활성 + Idle + 동일 id → 허용
        assertTrue(
            shouldClearStuckExpectedSession(
                expectedSessionId = 11L,
                startSessionId = 11L,
                current = RecordingState.Idle,
                isServiceRunning = false,
            ),
        )
    }

    @Test
    fun success_shouldIgnoreStartWhileSessionBusy_denyBindWindowPolicy() {
        // Given / When / Then — busy(true)면 재START ignore (이중 engine·Failed 덮어쓰기 금지)
        assertTrue(shouldIgnoreStartWhileSessionBusy(isSessionActive = true))
        assertTrue(!shouldIgnoreStartWhileSessionBusy(isSessionActive = false))
    }

    @Test
    fun success_idle_doesNotWipeReviewSticky() {
        // Given
        val review = RecordingState.Review(outputFile = File("a.m4a"), elapsedMs = 6_000L)
        // When
        val next = mergeRecordingControllerState(
            previous = review,
            serviceState = RecordingState.Idle,
            controllerSessionId = 1L,
            serviceSessionId = 0L,
        )
        // Then
        assertNull(next)
    }

    @Test
    fun success_matchingSession_reviewAcceptedFromStoppingPath() {
        // Given
        val review = RecordingState.Review(outputFile = File("a.m4a"), elapsedMs = 6_000L)
        // When
        val next = mergeRecordingControllerState(
            previous = RecordingState.Stopping,
            serviceState = review,
            controllerSessionId = 4L,
            serviceSessionId = 4L,
        )
        // Then
        assertEquals(review, next)
    }

    @Test
    fun success_staleReview_fromOldSession_doesNotReviveSticky() {
        // Given — Idle + new session; lingering Review from old session
        val review = RecordingState.Review(outputFile = File("a.m4a"), elapsedMs = 6_000L)
        // When
        val next = mergeRecordingControllerState(
            previous = RecordingState.Idle,
            serviceState = review,
            controllerSessionId = 2L,
            serviceSessionId = 1L,
        )
        // Then
        assertNull(next)
    }

    @Test
    fun success_reviewSticky_stoppingDoesNotWipe() {
        // Given
        val review = RecordingState.Review(outputFile = File("a.m4a"), elapsedMs = 6_000L)
        // When
        val next = mergeRecordingControllerState(
            previous = review,
            serviceState = RecordingState.Stopping,
            controllerSessionId = 8L,
            serviceSessionId = 8L,
        )
        // Then
        assertNull(next)
    }

    @Test
    fun success_shouldIgnoreDiscardOrStartWhileKeepInFlight() {
        // Given / When / Then
        assertTrue(shouldIgnoreDiscardOrStartWhileKeepInFlight(keepInFlight = true))
        assertTrue(!shouldIgnoreDiscardOrStartWhileKeepInFlight(keepInFlight = false))
    }

    @Test
    fun success_shouldApplyServiceIdleForDiscardConfirm_onlyWhenDiscardInFlight() {
        // Given
        val review = RecordingState.Review(outputFile = File("a.m4a"), elapsedMs = 6_000L)
        // When / Then — BIND_AUTO_CREATE Idle must not wipe Review
        assertTrue(
            !shouldApplyServiceIdleForDiscardConfirm(
                previous = review,
                serviceState = RecordingState.Idle,
                discardInFlight = false,
            ),
        )
        // Discard confirm → Idle over Review
        assertTrue(
            shouldApplyServiceIdleForDiscardConfirm(
                previous = review,
                serviceState = RecordingState.Idle,
                discardInFlight = true,
            ),
        )
        // Saved is not discard confirm
        assertTrue(
            !shouldApplyServiceIdleForDiscardConfirm(
                previous = review,
                serviceState = RecordingState.Saved(outputFile = File("a.m4a"), elapsedMs = 6_000L),
                discardInFlight = true,
            ),
        )
    }

    @Test
    fun success_recordingStateLabel_review_doesNotIncludePath() {
        // Given
        val review = RecordingState.Review(
            outputFile = File("/secret/path/clip.m4a"),
            elapsedMs = 6_000L,
        )
        // When
        val label = recordingStateLabel(review)
        // Then — pause/resume ignored log must not stringify outputFile
        assertEquals("Review", label)
        assertTrue(!label.contains("/"))
        assertTrue(!label.contains("secret"))
        assertTrue(!label.contains("clip.m4a"))
    }

    @Test
    fun success_shouldClearStuckReviewInFlight_blocksWhileServiceRunning() {
        val review = RecordingState.Review(outputFile = File("a.m4a"), elapsedMs = 6_000L)
        assertTrue(
            !shouldClearStuckReviewInFlight(
                stillInFlight = true,
                current = review,
                isServiceRunning = true,
            ),
        )
        assertTrue(
            shouldClearStuckReviewInFlight(
                stillInFlight = true,
                current = review,
                isServiceRunning = false,
            ),
        )
    }

    @Test
    fun success_shouldExecuteKeep_idleExtrasAlone_isFalse() {
        assertTrue(
            !shouldExecuteKeep(
                stateIsReview = false,
                hasPendingReview = false,
                extrasPresent = true,
                extrasSessionMatches = true,
                extrasFileConfined = true,
            ),
        )
    }
}
