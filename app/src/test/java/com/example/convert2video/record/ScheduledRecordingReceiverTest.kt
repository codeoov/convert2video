package com.example.convert2video.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * D-5 Receiver 핵심 순수 계약 JVM 단위 테스트 (Robolectric 없음).
 */
class ScheduledRecordingReceiverTest {

    // --- confirm timeout > BIND 3s ---

    @Test
    fun success_startConfirmTimeout_greaterThanBindTimeout() {
        assertTrue(
            "START confirm must exceed Controller BIND_TIMEOUT floor",
            SCHEDULED_START_CONFIRM_TIMEOUT_MS > CONTROLLER_BIND_TIMEOUT_FLOOR_MS,
        )
        assertTrue(SCHEDULED_START_CONFIRM_TIMEOUT_MS > 3_000L)
        assertTrue(SCHEDULED_START_CONFIRM_TIMEOUT_MS >= 5_500L)
    }

    // --- hasExtra ---

    @Test
    fun success_hasRequiredExtras_bothPresent() {
        assertTrue(
            hasRequiredScheduledRecordingExtras(
                hasScheduleId = true,
                hasOccurrenceStart = true,
            ),
        )
    }

    @Test
    fun failure_hasRequiredExtras_missingScheduleId() {
        assertFalse(
            hasRequiredScheduledRecordingExtras(
                hasScheduleId = false,
                hasOccurrenceStart = true,
            ),
        )
    }

    // --- busy → registerStart 금지 ---

    @Test
    fun failure_busy_shouldNotProceedWithReschedule() {
        assertFalse(
            isControllerAcceptingScheduledStart(
                RecordingState.Recording(elapsedMs = 0L, amplitude = 0),
            ),
        )
        assertFalse(shouldProceedWithReschedule(startAccepted = false))
    }

    // --- reject 후 Recording → 거짓 음성 복구 (registerStop 경로) ---

    @Test
    fun success_rejectThenRecording_recoverAsAcceptedForRegisterStop() {
        // Given: observe reject 직후 state가 Recording (start는 시도한 경우)
        val after = RecordingState.Recording(elapsedMs = 100L, amplitude = 1)
        assertTrue(shouldRecoverRejectedStartAsAccepted(after))
        // Then: Accepted 복구 → registerStop 경로 (STOP 없는 녹음 금지)
        assertTrue(shouldProceedWithReschedule(startAccepted = true))
    }

    @Test
    fun failure_busySkipped_doesNotRecoverOtherSession() {
        // busy 중 보이는 Recording은 "우리가 start한 결과"가 아님 → BusySkipped 경로
        // recover 플래그는 state만 보므로 true일 수 있으나, 호출측은 BusySkipped면 무시한다.
        assertTrue(
            shouldRecoverRejectedStartAsAccepted(
                RecordingState.Recording(0L, 0),
            ),
        )
        assertFalse(shouldProceedWithReschedule(startAccepted = false))
        // enum 분기 존재 확인 (BusySkipped ≠ NotConfirmed)
        assertTrue(
            ScheduledStartAttempt.BusySkipped != ScheduledStartAttempt.NotConfirmed,
        )
    }

    @Test
    fun failure_rejectThenIdle_noRecover() {
        assertFalse(shouldRecoverRejectedStartAsAccepted(RecordingState.Idle))
        assertFalse(
            shouldRecoverRejectedStartAsAccepted(
                RecordingState.Failed(RecordingErrorCodes.BIND_FAILED),
            ),
        )
    }

    @Test
    fun success_rejectThenPaused_recoverAsAccepted() {
        assertTrue(shouldRecoverRejectedStartAsAccepted(RecordingState.Paused(50L)))
    }

    // --- start observe ---

    @Test
    fun success_classifyPostStart_recordingConfirmed() {
        assertEquals(
            ScheduledStartObserveResult.Confirmed,
            classifyPostStartState(RecordingState.Recording(0L, 0)),
        )
    }

    @Test
    fun failure_startObserve_timeoutPending_notAccepted() {
        assertFalse(
            isScheduledStartAcceptedAfterObserve(
                result = ScheduledStartObserveResult.Pending,
                timedOutWhilePending = true,
            ),
        )
    }

    // --- ONCE: registerStop 성공 → disable; 실패(폴백) → 역시 disable ---

    @Test
    fun success_onceDisable_whenRegisterStopOk() {
        assertTrue(
            shouldDisableOnceAfterStart(
                startAccepted = true,
                registerStopSucceeded = true,
            ),
        )
    }

    @Test
    fun failure_onceRegisterStopFailed_pathUsesStopFallbackThenDisable() {
        // registerStop 실패 시 shouldDisableOnceAfterStart는 false (성공 경로 아님)
        assertFalse(
            shouldDisableOnceAfterStart(
                startAccepted = true,
                registerStopSucceeded = false,
            ),
        )
        // Round 4 확정: stop 폴백 후 setEnabled(false) — 재무장 없음
        assertTrue(shouldDisableOnceAfterStopFallback())
    }

    @Test
    fun success_countdownBusyOrNotConfirmed_clearsWithoutStop() {
        assertTrue(shouldClearCountdownWithoutStop(ScheduledStartAttempt.BusySkipped))
        assertTrue(shouldClearCountdownWithoutStop(ScheduledStartAttempt.NotConfirmed))
        assertFalse(shouldClearCountdownWithoutStop(ScheduledStartAttempt.Accepted))
        assertFalse(shouldRegisterCountdownStop(ScheduledStartAttempt.BusySkipped))
        assertFalse(shouldRegisterCountdownStop(ScheduledStartAttempt.NotConfirmed))
        assertTrue(shouldRegisterCountdownStop(ScheduledStartAttempt.Accepted))
    }

    @Test
    fun success_countdownActions_areDistinctFromScheduledAndHaveNoExtrasContract() {
        assertEquals(
            "com.example.convert2video.action.COUNTDOWN_RECORDING_START",
            RecordingCountdownAlarmScheduler.ACTION_COUNTDOWN_START,
        )
        assertEquals(
            "com.example.convert2video.action.COUNTDOWN_RECORDING_STOP",
            RecordingCountdownAlarmScheduler.ACTION_COUNTDOWN_STOP,
        )
        assertTrue(
            RecordingCountdownAlarmScheduler.ACTION_COUNTDOWN_START
                != RecordingScheduleAlarmScheduler.ACTION_SCHEDULED_START,
        )
        // countdown PI has no schedule extras — scheduled extras helper stays independent
        assertFalse(
            hasRequiredScheduledRecordingExtras(
                hasScheduleId = false,
                hasOccurrenceStart = false,
            ),
        )
    }

    @Test
    fun failure_onceDisable_startNotAccepted_neitherPath() {
        assertFalse(
            shouldDisableOnceAfterStart(
                startAccepted = false,
                registerStopSucceeded = true,
            ),
        )
    }

    // --- STOP delivery / retry ---

    @Test
    fun failure_stopDelivery_stillRecording_notConfirmed() {
        assertFalse(
            isStopDeliveryConfirmed(
                stateAfterAttempt = RecordingState.Recording(1L, 0),
                wasActivelyRecording = true,
            ),
        )
        assertTrue(
            shouldRetryStopDelivery(
                firstAttemptConfirmed = false,
                wasActivelyRecording = true,
            ),
        )
    }

    @Test
    fun success_stopDelivery_stoppingConfirmed() {
        assertTrue(
            isStopDeliveryConfirmed(
                stateAfterAttempt = RecordingState.Stopping,
                wasActivelyRecording = true,
            ),
        )
        assertFalse(
            shouldRetryStopDelivery(
                firstAttemptConfirmed = true,
                wasActivelyRecording = true,
            ),
        )
    }

    // --- WEEKLY 순서 ---

    @Test
    fun success_weeklyDailyRescheduleOrder_startThenStop() {
        assertEquals(
            listOf(
                ScheduledRescheduleStep.REGISTER_START,
                ScheduledRescheduleStep.REGISTER_STOP,
            ),
            weeklyDailyRescheduleSteps(),
        )
    }

    @Test
    fun success_controllerAcceptsIdleFamily() {
        assertTrue(isControllerAcceptingScheduledStart(RecordingState.Idle))
        assertTrue(
            isControllerAcceptingScheduledStart(RecordingState.Saved(File("x.m4a"), 1L)),
        )
        assertFalse(
            isControllerAcceptingScheduledStart(
                RecordingState.Review(outputFile = File("x.m4a"), elapsedMs = 6_000L),
            ),
        )
    }

    @Test
    fun success_stopDelivery_reviewConfirmed() {
        assertTrue(
            isStopDeliveryConfirmed(
                stateAfterAttempt = RecordingState.Review(
                    outputFile = File("x.m4a"),
                    elapsedMs = 6_000L,
                ),
                wasActivelyRecording = true,
            ),
        )
    }
}
