package com.example.convert2video.ui.screens.options

import com.example.convert2video.record.DEFAULT_COUNTDOWN_DURATION_MINUTES
import com.example.convert2video.record.DEFAULT_COUNTDOWN_START_IN_MINUTES
import com.example.convert2video.record.clampCountdownMinutes
import com.example.convert2video.record.isCountdownStartEnabled
import com.example.convert2video.record.shouldPersistPendingCountdownAfterRegister
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quick Timer VM 정책 JVM 단위 테스트 (AndroidViewModel 없이 순수 함수).
 */
class RecordingCountdownViewModelTest {

    @Test
    fun success_defaults_areFiveStartInAndTenDuration() {
        assertEquals(5, DEFAULT_COUNTDOWN_START_IN_MINUTES)
        assertEquals(10, DEFAULT_COUNTDOWN_DURATION_MINUTES)
    }

    @Test
    fun success_clamp_staysWithinOneTo180() {
        assertEquals(1, clampCountdownMinutes(0))
        assertEquals(1, clampCountdownMinutes(-3))
        assertEquals(180, clampCountdownMinutes(181))
        assertEquals(12, clampCountdownMinutes(12))
    }

    @Test
    fun success_persist_onlyAfterRegisterTrue() {
        assertTrue(shouldPersistPendingCountdownAfterRegister(registerOk = true))
        assertFalse(shouldPersistPendingCountdownAfterRegister(registerOk = false))
    }

    @Test
    fun failure_startDisabled_whenNeedsExactAlarmPermission() {
        assertFalse(
            isCountdownStartEnabled(
                needsExactAlarmPermission = true,
                isLanguageApplying = false,
                hasPendingCountdown = false,
                isInFlight = false,
            ),
        )
    }

    @Test
    fun success_startEnabled_whenPermissionOkAndIdle() {
        assertTrue(
            isCountdownStartEnabled(
                needsExactAlarmPermission = false,
                isLanguageApplying = false,
                hasPendingCountdown = false,
                isInFlight = false,
            ),
        )
    }

    @Test
    fun failure_startDisabled_whenPendingOrInFlightOrLanguageApplying() {
        assertFalse(
            isCountdownStartEnabled(
                needsExactAlarmPermission = false,
                isLanguageApplying = true,
                hasPendingCountdown = false,
                isInFlight = false,
            ),
        )
        assertFalse(
            isCountdownStartEnabled(
                needsExactAlarmPermission = false,
                isLanguageApplying = false,
                hasPendingCountdown = true,
                isInFlight = false,
            ),
        )
        assertFalse(
            isCountdownStartEnabled(
                needsExactAlarmPermission = false,
                isLanguageApplying = false,
                hasPendingCountdown = false,
                isInFlight = true,
            ),
        )
    }

    @Test
    fun success_stepperMinMax_atRangeEdges() {
        assertTrue(isCountdownStepperAtMin(1))
        assertFalse(isCountdownStepperAtMin(2))
        assertTrue(isCountdownStepperAtMax(180))
        assertFalse(isCountdownStepperAtMax(179))
    }
}
