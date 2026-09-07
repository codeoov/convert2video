package com.example.convert2video.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [RecordingCountdownAlarmScheduler] via [FakeRecordingCountdownAlarmBackend].
 * ELAPSED_REALTIME 트리거 산술만 검증 — RTC_WAKEUP / [RecordingAlarmBackend.setExact] 미사용.
 */
class RecordingCountdownAlarmSchedulerTest {

    @Test
    fun success_actionConstants_matchContractLiterals() {
        assertEquals(
            "com.example.convert2video.action.COUNTDOWN_RECORDING_START",
            RecordingCountdownAlarmScheduler.ACTION_COUNTDOWN_START,
        )
        assertEquals(
            "com.example.convert2video.action.COUNTDOWN_RECORDING_STOP",
            RecordingCountdownAlarmScheduler.ACTION_COUNTDOWN_STOP,
        )
        assertEquals(-71001, RecordingCountdownAlarmScheduler.REQUEST_CODE_COUNTDOWN_START)
        assertEquals(-71002, RecordingCountdownAlarmScheduler.REQUEST_CODE_COUNTDOWN_STOP)
    }

    @Test
    fun success_countdownActions_differFromScheduledActions() {
        assertNotEquals(
            RecordingScheduleAlarmScheduler.ACTION_SCHEDULED_START,
            RecordingCountdownAlarmScheduler.ACTION_COUNTDOWN_START,
        )
        assertNotEquals(
            RecordingScheduleAlarmScheduler.ACTION_SCHEDULED_STOP,
            RecordingCountdownAlarmScheduler.ACTION_COUNTDOWN_STOP,
        )
    }

    @Test
    fun success_triggerElapsed_addsMinutesTimes60000() {
        assertEquals(301_000L, countdownTriggerElapsedMillis(nowElapsedMillis = 1_000L, minutes = 5))
        assertEquals(600_000L, countdownTriggerElapsedMillis(nowElapsedMillis = 0L, minutes = 10))
    }

    @Test
    fun success_range_1And180Inclusive() {
        assertTrue(isCountdownMinutesInRange(1))
        assertTrue(isCountdownMinutesInRange(180))
        assertFalse(isCountdownMinutesInRange(0))
        assertFalse(isCountdownMinutesInRange(181))
    }

    @Test
    fun failure_registerStart_outOfRange_doesNotSetOrCancel() {
        val fake = FakeRecordingCountdownAlarmBackend()
        assertFalse(
            RecordingCountdownAlarmScheduler.registerStart(
                startInMinutes = 0,
                backend = fake,
                nowElapsedMillis = 10_000L,
            ),
        )
        assertFalse(
            RecordingCountdownAlarmScheduler.registerStart(
                startInMinutes = 181,
                backend = fake,
                nowElapsedMillis = 10_000L,
            ),
        )
        assertTrue(fake.setElapsedCalls.isEmpty())
        assertTrue(fake.cancelCalls.isEmpty())
    }

    @Test
    fun failure_registerStart_permissionDenied_cancelsStaleReturnsFalse() {
        val fake = FakeRecordingCountdownAlarmBackend(canSchedule = false)
        val ok = RecordingCountdownAlarmScheduler.registerStart(
            startInMinutes = 5,
            backend = fake,
            nowElapsedMillis = 10_000L,
        )
        assertFalse(ok)
        assertTrue(fake.setElapsedCalls.isEmpty())
        assertEquals(2, fake.cancelCalls.size)
        assertEquals(
            RecordingCountdownAlarmScheduler.REQUEST_CODE_COUNTDOWN_START,
            fake.cancelCalls[0].requestCode,
        )
        assertEquals(
            RecordingCountdownAlarmScheduler.REQUEST_CODE_COUNTDOWN_STOP,
            fake.cancelCalls[1].requestCode,
        )
    }

    @Test
    fun success_registerStart_setsElapsedWakeupThenCancelsStaleStop() {
        val fake = FakeRecordingCountdownAlarmBackend()
        val now = 50_000L
        val ok = RecordingCountdownAlarmScheduler.registerStart(
            startInMinutes = 5,
            backend = fake,
            nowElapsedMillis = now,
        )
        assertTrue(ok)
        assertEquals(1, fake.setElapsedCalls.size)
        val call = fake.setElapsedCalls.single()
        assertEquals(now + 5 * 60_000L, call.triggerAtElapsedMillis)
        assertEquals(
            RecordingCountdownAlarmScheduler.REQUEST_CODE_COUNTDOWN_START,
            call.requestCode,
        )
        assertEquals(
            RecordingCountdownAlarmScheduler.ACTION_COUNTDOWN_START,
            call.action,
        )
        assertEquals(1, fake.cancelCalls.size)
        assertEquals(
            RecordingCountdownAlarmScheduler.REQUEST_CODE_COUNTDOWN_STOP,
            fake.cancelCalls.single().requestCode,
        )
    }

    @Test
    fun failure_registerStart_setElapsedThrows_preservesExisting() {
        val fake = FakeRecordingCountdownAlarmBackend(
            setElapsedThrows = IllegalStateException("AlarmManager null"),
        )
        val ok = RecordingCountdownAlarmScheduler.registerStart(
            startInMinutes = 5,
            backend = fake,
            nowElapsedMillis = 1L,
        )
        assertFalse(ok)
        assertTrue(fake.cancelCalls.isEmpty())
    }

    @Test
    fun success_registerStop_setsElapsedThenCancelsStart() {
        val fake = FakeRecordingCountdownAlarmBackend()
        val now = 90_000L
        val ok = RecordingCountdownAlarmScheduler.registerStop(
            durationMinutes = 10,
            nowElapsedMillis = now,
            backend = fake,
        )
        assertTrue(ok)
        val call = fake.setElapsedCalls.single()
        assertEquals(now + 10 * 60_000L, call.triggerAtElapsedMillis)
        assertEquals(
            RecordingCountdownAlarmScheduler.REQUEST_CODE_COUNTDOWN_STOP,
            call.requestCode,
        )
        assertEquals(
            RecordingCountdownAlarmScheduler.ACTION_COUNTDOWN_STOP,
            call.action,
        )
        assertEquals(
            RecordingCountdownAlarmScheduler.REQUEST_CODE_COUNTDOWN_START,
            fake.cancelCalls.single().requestCode,
        )
    }

    @Test
    fun failure_registerStop_permissionDenied_cancelsStale() {
        val fake = FakeRecordingCountdownAlarmBackend(canSchedule = false)
        assertFalse(
            RecordingCountdownAlarmScheduler.registerStop(
                durationMinutes = 10,
                nowElapsedMillis = 1L,
                backend = fake,
            ),
        )
        assertTrue(fake.setElapsedCalls.isEmpty())
        assertEquals(2, fake.cancelCalls.size)
    }

    @Test
    fun success_hasPendingStartAndStop_delegateToBackend() {
        val fake = FakeRecordingCountdownAlarmBackend()
        assertFalse(RecordingCountdownAlarmScheduler.hasPendingStartPendingIntent(fake))
        fake.pendingStart = true
        fake.pendingStop = true
        assertTrue(RecordingCountdownAlarmScheduler.hasPendingStartPendingIntent(fake))
        assertTrue(RecordingCountdownAlarmScheduler.hasPendingStopPendingIntent(fake))
    }

    @Test
    fun success_cancel_cancelsBothRequestCodes() {
        val fake = FakeRecordingCountdownAlarmBackend()
        RecordingCountdownAlarmScheduler.cancel(fake)
        assertEquals(2, fake.cancelCalls.size)
        assertEquals(
            RecordingCountdownAlarmScheduler.ACTION_COUNTDOWN_START,
            fake.cancelCalls[0].action,
        )
        assertEquals(
            RecordingCountdownAlarmScheduler.ACTION_COUNTDOWN_STOP,
            fake.cancelCalls[1].action,
        )
    }

    @Test
    fun success_defaults_areFiveAndTen() {
        assertEquals(5, DEFAULT_COUNTDOWN_START_IN_MINUTES)
        assertEquals(10, DEFAULT_COUNTDOWN_DURATION_MINUTES)
    }

    @Test
    fun success_shouldPersist_onlyWhenRegisterOk() {
        assertTrue(shouldPersistPendingCountdownAfterRegister(registerOk = true))
        assertFalse(shouldPersistPendingCountdownAfterRegister(registerOk = false))
    }

    @Test
    fun success_remainingCountdownStopMs_subtractsElapsed() {
        assertEquals(7 * 60_000L, remainingCountdownStopMs(durationMinutes = 10, elapsedRecordingMs = 3 * 60_000L))
        assertEquals(45_000L, remainingCountdownStopMs(durationMinutes = 1, elapsedRecordingMs = 15_000L))
    }

    @Test
    fun success_remainingCountdownStopMs_clampsAtZero() {
        assertEquals(0L, remainingCountdownStopMs(durationMinutes = 10, elapsedRecordingMs = 10 * 60_000L))
        assertEquals(0L, remainingCountdownStopMs(durationMinutes = 10, elapsedRecordingMs = 11 * 60_000L))
    }

    @Test
    fun success_shouldStopCountdownImmediately_whenRemainingZeroOrNegative() {
        assertTrue(shouldStopCountdownImmediately(0L))
        assertTrue(shouldStopCountdownImmediately(-1L))
        assertFalse(shouldStopCountdownImmediately(1L))
    }

    @Test
    fun success_shouldDeferCountdownStopOnCallPause_onlyCallAndPending() {
        assertTrue(
            shouldDeferCountdownStopOnCallPause(
                pausedByCall = true,
                hasPendingCountdownStop = true,
            ),
        )
    }

    @Test
    fun failure_shouldDeferCountdownStopOnCallPause_userPauseKeepsStop() {
        assertFalse(
            shouldDeferCountdownStopOnCallPause(
                pausedByCall = false,
                hasPendingCountdownStop = true,
            ),
        )
    }

    @Test
    fun failure_shouldDeferCountdownStopOnCallPause_noPendingCountdownStop() {
        assertFalse(
            shouldDeferCountdownStopOnCallPause(
                pausedByCall = true,
                hasPendingCountdownStop = false,
            ),
        )
    }

    @Test
    fun success_shouldDeferScheduledStopOnCallPause_alwaysFalse() {
        assertFalse(shouldDeferScheduledStopOnCallPause())
    }

    @Test
    fun success_registerStopRemainingMs_setsElapsedThenCancelsStart() {
        val fake = FakeRecordingCountdownAlarmBackend()
        val now = 90_000L
        val remainingMs = 45_000L
        val ok = RecordingCountdownAlarmScheduler.registerStopRemainingMs(
            remainingMs = remainingMs,
            nowElapsedMillis = now,
            backend = fake,
        )
        assertTrue(ok)
        val call = fake.setElapsedCalls.single()
        assertEquals(now + remainingMs, call.triggerAtElapsedMillis)
        assertEquals(
            RecordingCountdownAlarmScheduler.REQUEST_CODE_COUNTDOWN_STOP,
            call.requestCode,
        )
        assertEquals(
            RecordingCountdownAlarmScheduler.ACTION_COUNTDOWN_STOP,
            call.action,
        )
        assertEquals(
            RecordingCountdownAlarmScheduler.REQUEST_CODE_COUNTDOWN_START,
            fake.cancelCalls.single().requestCode,
        )
    }

    @Test
    fun failure_registerStopRemainingMs_outOfRange_doesNotSetOrCancel() {
        val fake = FakeRecordingCountdownAlarmBackend()
        assertFalse(
            RecordingCountdownAlarmScheduler.registerStopRemainingMs(
                remainingMs = 0L,
                nowElapsedMillis = 1L,
                backend = fake,
            ),
        )
        assertFalse(
            RecordingCountdownAlarmScheduler.registerStopRemainingMs(
                remainingMs = 181L * 60_000L,
                nowElapsedMillis = 1L,
                backend = fake,
            ),
        )
        assertTrue(fake.setElapsedCalls.isEmpty())
        assertTrue(fake.cancelCalls.isEmpty())
    }

    @Test
    fun failure_registerStopRemainingMs_permissionDenied_cancelsStale() {
        val fake = FakeRecordingCountdownAlarmBackend(canSchedule = false)
        assertFalse(
            RecordingCountdownAlarmScheduler.registerStopRemainingMs(
                remainingMs = 45_000L,
                nowElapsedMillis = 1L,
                backend = fake,
            ),
        )
        assertTrue(fake.setElapsedCalls.isEmpty())
        assertEquals(2, fake.cancelCalls.size)
    }

    @Test
    fun success_cancelStop_cancelsStopOnly() {
        val fake = FakeRecordingCountdownAlarmBackend()
        RecordingCountdownAlarmScheduler.cancelStop(fake)
        assertEquals(1, fake.cancelCalls.size)
        assertEquals(
            RecordingCountdownAlarmScheduler.REQUEST_CODE_COUNTDOWN_STOP,
            fake.cancelCalls.single().requestCode,
        )
        assertEquals(
            RecordingCountdownAlarmScheduler.ACTION_COUNTDOWN_STOP,
            fake.cancelCalls.single().action,
        )
    }

    @Test
    fun success_remainingStartMinutes_ceilsPartialMinute() {
        // Given — any leftover millis must not display 0
        val oneMsRemainingStart = 1L
        val exactOneMinuteStart = 60_000L
        val oneMinutePlusOneMsStart = 60_001L
        // When
        val fromOneMs = countdownRemainingStartMinutes(
            nowElapsedMillis = 0L,
            startElapsedMillis = oneMsRemainingStart,
        )
        val fromExactMinute = countdownRemainingStartMinutes(
            nowElapsedMillis = 0L,
            startElapsedMillis = exactOneMinuteStart,
        )
        val fromMinutePlusOne = countdownRemainingStartMinutes(
            nowElapsedMillis = 0L,
            startElapsedMillis = oneMinutePlusOneMsStart,
        )
        val fromFiveMinutes = countdownRemainingStartMinutes(
            nowElapsedMillis = 0L,
            startElapsedMillis = 5 * 60_000L,
        )
        // Then
        assertEquals(1, fromOneMs)
        assertEquals(1, fromExactMinute)
        assertEquals(2, fromMinutePlusOne)
        assertEquals(5, fromFiveMinutes)
    }

    @Test
    fun failure_remainingStartMinutes_pastOrEqualStartIsZeroNotNegative() {
        // Given
        val nowPastStart = 120_000L
        val startElapsed = 60_000L
        // When
        val past = countdownRemainingStartMinutes(
            nowElapsedMillis = nowPastStart,
            startElapsedMillis = startElapsed,
        )
        val equal = countdownRemainingStartMinutes(
            nowElapsedMillis = startElapsed,
            startElapsedMillis = startElapsed,
        )
        // Then
        assertEquals(0, past)
        assertEquals(0, equal)
    }

    @Test
    fun success_elapsedRecordingMinutes_floorsAndClampsToDuration() {
        // Given
        val started = 10_000L
        val durationMinutes = 10
        // When
        val underOneMinute = countdownElapsedRecordingMinutes(
            nowElapsedMillis = started + 59_999L,
            recordingStartedElapsedMillis = started,
            durationMinutes = durationMinutes,
        )
        val exactlyOneMinute = countdownElapsedRecordingMinutes(
            nowElapsedMillis = started + 60_000L,
            recordingStartedElapsedMillis = started,
            durationMinutes = durationMinutes,
        )
        val ninetySeconds = countdownElapsedRecordingMinutes(
            nowElapsedMillis = started + 90_000L,
            recordingStartedElapsedMillis = started,
            durationMinutes = durationMinutes,
        )
        val atStart = countdownElapsedRecordingMinutes(
            nowElapsedMillis = started,
            recordingStartedElapsedMillis = started,
            durationMinutes = durationMinutes,
        )
        // Then
        assertEquals(0, underOneMinute)
        assertEquals(1, exactlyOneMinute)
        assertEquals(1, ninetySeconds)
        assertEquals(0, atStart)
    }

    @Test
    fun failure_elapsedRecordingMinutes_doesNotGoBelowZeroOrAboveDuration() {
        // Given
        val started = 50_000L
        val durationMinutes = 8
        // When
        val beforeStart = countdownElapsedRecordingMinutes(
            nowElapsedMillis = started - 1_000L,
            recordingStartedElapsedMillis = started,
            durationMinutes = durationMinutes,
        )
        val beyondDuration = countdownElapsedRecordingMinutes(
            nowElapsedMillis = started + 15 * 60_000L,
            recordingStartedElapsedMillis = started,
            durationMinutes = durationMinutes,
        )
        // Then
        assertEquals(0, beforeStart)
        assertEquals(8, beyondDuration)
    }

    @Test
    fun failure_elapsedRecordingMinutes_invalidDurationReturnsZeroWithoutThrow() {
        // Given — DataStore duration may be non-positive; coerceIn would throw
        val started = 10_000L
        // When
        val zeroDuration = countdownElapsedRecordingMinutes(
            nowElapsedMillis = started + 60_000L,
            recordingStartedElapsedMillis = started,
            durationMinutes = 0,
        )
        val negativeDuration = countdownElapsedRecordingMinutes(
            nowElapsedMillis = started + 60_000L,
            recordingStartedElapsedMillis = started,
            durationMinutes = -5,
        )
        // Then
        assertEquals(0, zeroDuration)
        assertEquals(0, negativeDuration)
    }

    @Test
    fun success_waitingSoon_whenRemainingMinutesNotPositive() {
        // Given
        // When / Then
        assertTrue(shouldShowCountdownWaitingSoon(0))
        assertTrue(shouldShowCountdownWaitingSoon(-1))
        assertFalse(shouldShowCountdownWaitingSoon(1))
    }
}

internal class FakeRecordingCountdownAlarmBackend(
    var canSchedule: Boolean = true,
    var setElapsedThrows: RuntimeException? = null,
    var pendingStart: Boolean = false,
    var pendingStop: Boolean = false,
) : RecordingCountdownAlarmBackend {

    data class SetElapsedCall(
        val triggerAtElapsedMillis: Long,
        val requestCode: Int,
        val action: String,
    )

    data class CancelCall(
        val requestCode: Int,
        val action: String,
    )

    val setElapsedCalls = mutableListOf<SetElapsedCall>()
    val cancelCalls = mutableListOf<CancelCall>()

    override fun canScheduleExactAlarms(): Boolean = canSchedule

    override fun setElapsedRealtimeWakeup(
        triggerAtElapsedMillis: Long,
        requestCode: Int,
        action: String,
    ) {
        setElapsedThrows?.let { throw it }
        setElapsedCalls += SetElapsedCall(triggerAtElapsedMillis, requestCode, action)
    }

    override fun cancel(requestCode: Int, action: String) {
        cancelCalls += CancelCall(requestCode, action)
        if (requestCode == RecordingCountdownAlarmScheduler.REQUEST_CODE_COUNTDOWN_START) {
            pendingStart = false
        }
        if (requestCode == RecordingCountdownAlarmScheduler.REQUEST_CODE_COUNTDOWN_STOP) {
            pendingStop = false
        }
    }

    override fun hasPendingIntent(requestCode: Int, action: String): Boolean =
        when (requestCode) {
            RecordingCountdownAlarmScheduler.REQUEST_CODE_COUNTDOWN_START -> pendingStart
            RecordingCountdownAlarmScheduler.REQUEST_CODE_COUNTDOWN_STOP -> pendingStop
            else -> false
        }
}
