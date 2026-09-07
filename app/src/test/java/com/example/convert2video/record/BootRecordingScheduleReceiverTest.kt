package com.example.convert2video.record

import com.example.convert2video.data.RecordingSchedule
import com.example.convert2video.data.RecordingScheduleDao
import com.example.convert2video.data.RecordingScheduleRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-6 BootRecordingScheduleReceiver JVM 단위 테스트 (Robolectric 없음).
 * seam 범위: [registerStart] λ만 — registerStop/RecordingController는 Contract로 보장.
 */
class BootRecordingScheduleReceiverTest {

    private fun schedule(id: Long) = RecordingSchedule(
        id = id,
        startMinuteOfDay = 9 * 60,
        endMinuteOfDay = 10 * 60,
        repeatMode = "DAILY",
        daysOfWeekMask = 0,
        enabled = true,
        createdAt = 0L,
    )

    private class FakeRecordingScheduleDao(
        private val enabledSchedules: List<RecordingSchedule>,
    ) : RecordingScheduleDao {
        override fun observeAll(): Flow<List<RecordingSchedule>> = flowOf(emptyList())
        override suspend fun getById(id: Long): RecordingSchedule? = null
        override suspend fun insert(schedule: RecordingSchedule): Long = 0L
        override suspend fun update(schedule: RecordingSchedule) = Unit
        override suspend fun deleteById(id: Long) = Unit
        override suspend fun setEnabled(id: Long, enabled: Boolean) = Unit
        override suspend fun getAllEnabled(): List<RecordingSchedule> = enabledSchedules
    }

    // --- unexpected action 거부 ---

    @Test
    fun failure_unexpectedAction_notBootCompleted() {
        assertFalse(isBootCompletedIntentAction("android.intent.action.MY_PACKAGE_REPLACED"))
        assertFalse(isBootCompletedIntentAction(null))
    }

    @Test
    fun success_bootCompletedAction_accepted() {
        assertTrue(isBootCompletedIntentAction("android.intent.action.BOOT_COMPLETED"))
    }

    // --- enabled N건 registerStart 호출 ---

    @Test
    fun success_enabledSchedules_registerStartCalledForEach() = runTest {
        // Given: enabled 3건
        val schedules = listOf(schedule(1L), schedule(2L), schedule(3L))
        val repository = RecordingScheduleRepository(FakeRecordingScheduleDao(schedules))
        val invokedIds = mutableListOf<Long>()

        // When
        val result = reregisterEnabledStartAlarms(repository) { s ->
            invokedIds.add(s.id)
            true
        }

        // Then: registerStart seam만 N회
        assertEquals(3, result.total)
        assertEquals(3, result.ok)
        assertEquals(0, result.fail)
        assertEquals(listOf(1L, 2L, 3L), invokedIds)
    }

    // --- 개별 실패 격리 ---

    @Test
    fun success_individualFailure_isolatedOthersContinue() = runTest {
        // Given: id=2만 registerStart false
        val schedules = listOf(schedule(1L), schedule(2L), schedule(3L))
        val repository = RecordingScheduleRepository(FakeRecordingScheduleDao(schedules))
        val invokedIds = mutableListOf<Long>()

        // When
        val result = reregisterEnabledStartAlarms(repository) { s ->
            invokedIds.add(s.id)
            s.id != 2L
        }

        // Then: 2 실패해도 1·3 계속
        assertEquals(3, result.total)
        assertEquals(2, result.ok)
        assertEquals(1, result.fail)
        assertEquals(listOf(1L, 2L, 3L), invokedIds)
    }

    @Test
    fun success_registerStartException_isolatedOthersContinue() = runTest {
        val schedules = listOf(schedule(10L), schedule(20L))
        val repository = RecordingScheduleRepository(FakeRecordingScheduleDao(schedules))

        val result = reregisterEnabledStartAlarms(repository) { s ->
            if (s.id == 10L) error("alarm backend down")
            true
        }

        assertEquals(2, result.total)
        assertEquals(1, result.ok)
        assertEquals(1, result.fail)
    }

    @Test
    fun success_emptyEnabled_noRegisterStartCalls() = runTest {
        val repository = RecordingScheduleRepository(FakeRecordingScheduleDao(emptyList()))
        var callCount = 0

        val result = reregisterEnabledStartAlarms(repository) {
            callCount++
            true
        }

        assertEquals(0, result.total)
        assertEquals(0, callCount)
    }

    // --- fail>0 → onRegisterFailed 1회; fail==0 → 미호출 ---

    @Test
    fun success_allOk_onRegisterFailedNotCalled() = runTest {
        val repository = RecordingScheduleRepository(
            FakeRecordingScheduleDao(listOf(schedule(1L), schedule(2L))),
        )
        var notifyCount = 0

        bootReregisterWithNotification(
            repository = repository,
            registerStart = { true },
            onRegisterFailed = { notifyCount++ },
        )

        assertEquals(0, notifyCount)
    }

    @Test
    fun success_anyFail_onRegisterFailedCalledOnce() = runTest {
        val repository = RecordingScheduleRepository(
            FakeRecordingScheduleDao(listOf(schedule(1L), schedule(2L), schedule(3L))),
        )
        var notifyCount = 0

        val result = bootReregisterWithNotification(
            repository = repository,
            registerStart = { s -> s.id != 2L },
            onRegisterFailed = { notifyCount++ },
        )

        assertEquals(1, result.fail)
        assertEquals(1, notifyCount)
    }

    @Test
    fun success_notificationThrows_doesNotPropagate() = runTest {
        val repository = RecordingScheduleRepository(
            FakeRecordingScheduleDao(listOf(schedule(1L))),
        )

        val result = bootReregisterWithNotification(
            repository = repository,
            registerStart = { false },
            onRegisterFailed = { error("notification backend down") },
        )

        assertEquals(1, result.fail)
    }
}
