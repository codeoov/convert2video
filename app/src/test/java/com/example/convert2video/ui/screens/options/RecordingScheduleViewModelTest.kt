package com.example.convert2video.ui.screens.options

import com.example.convert2video.data.RecordingSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-5 ViewModel 알람↔DB 정합 정책 JVM 단위 테스트.
 * AndroidViewModel/Room 없이 롤백 결정 순수 함수만 검증.
 */
class RecordingScheduleViewModelTest {

    private fun schedule(
        id: Long = 1L,
        enabled: Boolean = true,
    ) = RecordingSchedule(
        id = id,
        startMinuteOfDay = 9 * 60,
        endMinuteOfDay = 10 * 60,
        repeatMode = "DAILY",
        daysOfWeekMask = 0,
        enabled = enabled,
        createdAt = 0L,
    )

    // --- insert register 실패 → orphan delete ---

    @Test
    fun failure_insertRegisterFailed_shouldDeleteOrphan() {
        // Given: Room insert 성공 후 registerStart 실패
        // Then: orphan row 삭제
        assertTrue(shouldDeleteOrphanAfterInsertRegisterFailure(registerOk = false))
    }

    @Test
    fun success_insertRegisterOk_shouldNotDeleteOrphan() {
        assertFalse(shouldDeleteOrphanAfterInsertRegisterFailure(registerOk = true))
    }

    // --- update register 실패 → previous 복구 / disable ---

    @Test
    fun success_updateRegisterFailure_restoresPreviousWhenPresent() {
        val previous = schedule(id = 7L, enabled = true)
        val action = decideUpdateAlarmFailureRecovery(previous)
        assertEquals(UpdateAlarmFailureRecovery.Restore(previous), action)
    }

    @Test
    fun failure_updateRegisterFailure_disableWhenPreviousNull() {
        assertEquals(
            UpdateAlarmFailureRecovery.DisableAndCancel,
            decideUpdateAlarmFailureRecovery(previous = null),
        )
    }

    // --- setEnabled(true) register 실패 → enabled 롤백 ---

    @Test
    fun failure_setEnabledRegisterFailed_shouldRollbackEnabled() {
        assertTrue(shouldRollbackEnabledOnRegisterFailure(registerOk = false))
    }

    @Test
    fun success_setEnabledRegisterOk_noRollback() {
        assertFalse(shouldRollbackEnabledOnRegisterFailure(registerOk = true))
    }
}
