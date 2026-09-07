package com.example.convert2video.record

import com.example.convert2video.data.RecordingSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * JVM unit tests for [nextTriggerEpochMillis] / [stopTriggerEpochMillis] / requestCode /
 * register·cancel via [FakeRecordingAlarmBackend].
 * Fixed zones — device default TZ에 의존하지 않음.
 */
class RecordingScheduleAlarmSchedulerTest {

    private val zone: ZoneId = ZoneOffset.UTC

    private fun epoch(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        zoneId: ZoneId = zone,
    ): Long = LocalDateTime.of(year, month, day, hour, minute)
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

    private fun schedule(
        start: Int,
        end: Int,
        mode: RecordingScheduleRepeatMode,
        mask: Int = 0,
        enabled: Boolean = true,
        id: Long = 1L,
    ) = RecordingSchedule(
        id = id,
        startMinuteOfDay = start,
        endMinuteOfDay = end,
        repeatMode = mode.name,
        daysOfWeekMask = mask,
        enabled = enabled,
        createdAt = 0L,
    )

    // --- ONCE ---

    @Test
    fun success_once_todayFutureStart() {
        // Given: Wed 2026-08-05 10:00, ONCE 14:00–14:30
        val now = epoch(2026, 8, 5, 10, 0)
        val s = schedule(14 * 60, 14 * 60 + 30, RecordingScheduleRepeatMode.ONCE)

        // When
        val trigger = nextTriggerEpochMillis(s, now, zone)

        // Then: next wall start only (D-5가 one-shot disable)
        assertEquals(epoch(2026, 8, 5, 14, 0), trigger)
    }

    @Test
    fun success_once_pastGoesToTomorrow() {
        // Given: Wed 2026-08-05 15:00, ONCE 14:00 already past → 다음날 wall start
        // (past→null 아님; one-shot consume은 D-5/VM)
        val now = epoch(2026, 8, 5, 15, 0)
        val s = schedule(14 * 60, 14 * 60 + 30, RecordingScheduleRepeatMode.ONCE)

        // When / Then
        assertEquals(epoch(2026, 8, 6, 14, 0), nextTriggerEpochMillis(s, now, zone))
    }

    @Test
    fun success_once_disabledReturnsNull() {
        val now = epoch(2026, 8, 5, 10, 0)
        val s = schedule(14 * 60, 14 * 60 + 30, RecordingScheduleRepeatMode.ONCE, enabled = false)
        assertNull(nextTriggerEpochMillis(s, now, zone))
    }

    // --- WEEKLY ---

    @Test
    fun success_weekly_sameDayFuture() {
        // Given: Wed 2026-08-05 (bit2=수), mask=월수금, start 16:00 still future
        val now = epoch(2026, 8, 5, 10, 0)
        val mask = (1 shl 0) or (1 shl 2) or (1 shl 4) // Mon/Wed/Fri
        val s = schedule(16 * 60, 16 * 60 + 20, RecordingScheduleRepeatMode.WEEKLY, mask)

        // When / Then
        assertEquals(epoch(2026, 8, 5, 16, 0), nextTriggerEpochMillis(s, now, zone))
    }

    @Test
    fun success_weekly_skipsNonMatchingDays() {
        // Given: Wed past start → next Fri (mask 월수금)
        val now = epoch(2026, 8, 5, 17, 0)
        val mask = (1 shl 0) or (1 shl 2) or (1 shl 4)
        val s = schedule(16 * 60, 16 * 60 + 20, RecordingScheduleRepeatMode.WEEKLY, mask)

        // When / Then — Fri 2026-08-07
        assertEquals(epoch(2026, 8, 7, 16, 0), nextTriggerEpochMillis(s, now, zone))
    }

    @Test
    fun success_weekly_sundayOnlyPast_nextSundayDayOffset7() {
        // Given: Sunday 2026-08-09 18:00, WEEKLY Sunday-only (bit6), start 10:00 already past
        // → next Sunday = dayOffset 7 (2026-08-16 10:00)
        val now = epoch(2026, 8, 9, 18, 0)
        val sundayBit = 1 shl 6
        val s = schedule(10 * 60, 10 * 60 + 30, RecordingScheduleRepeatMode.WEEKLY, sundayBit)

        // When / Then
        assertEquals(epoch(2026, 8, 16, 10, 0), nextTriggerEpochMillis(s, now, zone))
    }

    @Test
    fun success_weekly_maskZeroReturnsNull() {
        val now = epoch(2026, 8, 5, 10, 0)
        val s = schedule(16 * 60, 16 * 60 + 20, RecordingScheduleRepeatMode.WEEKLY, mask = 0)
        assertNull(nextTriggerEpochMillis(s, now, zone))
    }

    // --- DAILY ---

    @Test
    fun success_daily_todayFuture() {
        val now = epoch(2026, 8, 5, 8, 0)
        val s = schedule(9 * 60, 9 * 60 + 15, RecordingScheduleRepeatMode.DAILY)
        assertEquals(epoch(2026, 8, 5, 9, 0), nextTriggerEpochMillis(s, now, zone))
    }

    @Test
    fun success_daily_pastGoesToNextDay() {
        val now = epoch(2026, 8, 5, 10, 0)
        val s = schedule(9 * 60, 9 * 60 + 15, RecordingScheduleRepeatMode.DAILY)
        assertEquals(epoch(2026, 8, 6, 9, 0), nextTriggerEpochMillis(s, now, zone))
    }

    // --- validation nulls ---

    @Test
    fun success_startEqualsEnd_returnsNull() {
        val now = epoch(2026, 8, 5, 10, 0)
        val s = schedule(14 * 60, 14 * 60, RecordingScheduleRepeatMode.ONCE)
        assertNull(nextTriggerEpochMillis(s, now, zone))
        assertNull(stopTriggerEpochMillis(s, epoch(2026, 8, 5, 14, 0), zone))
    }

    @Test
    fun success_minuteOutOfRange_returnsNull() {
        val now = epoch(2026, 8, 5, 10, 0)
        val startTooHigh = schedule(1440, 60, RecordingScheduleRepeatMode.ONCE)
        val endTooHigh = schedule(60, 1440, RecordingScheduleRepeatMode.ONCE)
        val startNegative = schedule(-1, 60, RecordingScheduleRepeatMode.ONCE)
        assertNull(nextTriggerEpochMillis(startTooHigh, now, zone))
        assertNull(nextTriggerEpochMillis(endTooHigh, now, zone))
        assertNull(nextTriggerEpochMillis(startNegative, now, zone))
        assertNull(stopTriggerEpochMillis(startTooHigh, now, zone))
        assertNull(stopTriggerEpochMillis(endTooHigh, now, zone))
    }

    // --- overnight / DST wall-clock STOP ---

    @Test
    fun success_overnight_nextStartUsesStartMinute() {
        // Given: overnight 23:00–01:00, now 22:00 → start today 23:00
        val now = epoch(2026, 8, 5, 22, 0)
        val s = schedule(23 * 60, 60, RecordingScheduleRepeatMode.DAILY)
        assertEquals(epoch(2026, 8, 5, 23, 0), nextTriggerEpochMillis(s, now, zone))
    }

    @Test
    fun success_overnight_stopCrossesMidnight() {
        // Given: start Wed 23:00, end 01:00 → stop Thu 01:00 (wall-clock)
        val start = epoch(2026, 8, 5, 23, 0)
        val s = schedule(23 * 60, 60, RecordingScheduleRepeatMode.ONCE)
        assertEquals(epoch(2026, 8, 6, 1, 0), stopTriggerEpochMillis(s, start, zone))
    }

    @Test
    fun success_overnight_sameDayStopWhenEndAfterStart() {
        val start = epoch(2026, 8, 5, 10, 0)
        val s = schedule(10 * 60, 10 * 60 + 30, RecordingScheduleRepeatMode.ONCE)
        assertEquals(epoch(2026, 8, 5, 10, 30), stopTriggerEpochMillis(s, start, zone))
    }

    @Test
    fun success_stop_dstSpringForward_usesWallClockNotRawDuration() {
        // Given: America/New_York spring forward 2026-03-08 02:00→03:00
        // Schedule 01:00–04:00; raw 180min would land at 05:00 EDT, wall end is 04:00 EDT
        val ny = ZoneId.of("America/New_York")
        val start = epoch(2026, 3, 8, 1, 0, ny)
        val s = schedule(60, 4 * 60, RecordingScheduleRepeatMode.ONCE)
        val wallStop = ZonedDateTime.of(2026, 3, 8, 4, 0, 0, 0, ny)
            .toInstant()
            .toEpochMilli()
        val rawDurationStop = start + 180L * 60_000L

        // When
        val stopAt = stopTriggerEpochMillis(s, start, ny)

        // Then
        assertEquals(wallStop, stopAt)
        assertNotEquals(rawDurationStop, stopAt)
    }

    @Test
    fun success_overnight_stop_dstZone_wallClockNextDay() {
        // Given: overnight 23:00–01:00 starting night before spring forward in NY
        val ny = ZoneId.of("America/New_York")
        val start = epoch(2026, 3, 7, 23, 0, ny)
        val s = schedule(23 * 60, 60, RecordingScheduleRepeatMode.ONCE)
        val expected = epoch(2026, 3, 8, 1, 0, ny)
        assertEquals(expected, stopTriggerEpochMillis(s, start, ny))
    }

    @Test
    fun success_stop_dstFallBack_sameDay_usesWallClockNotRawDuration() {
        // Given: America/New_York fall back 2026-11-01 02:00→01:00
        // Same-day 00:30–03:00; raw 150min ≠ wall 03:00 after repeated hour
        val ny = ZoneId.of("America/New_York")
        val start = epoch(2026, 11, 1, 0, 30, ny)
        val s = schedule(30, 3 * 60, RecordingScheduleRepeatMode.ONCE)
        val wallStop = ZonedDateTime.of(2026, 11, 1, 3, 0, 0, 0, ny)
            .toInstant()
            .toEpochMilli()
        val rawDurationStop = start + 150L * 60_000L

        // When
        val stopAt = stopTriggerEpochMillis(s, start, ny)

        // Then
        assertEquals(wallStop, stopAt)
        assertNotEquals(rawDurationStop, stopAt)
    }

    @Test
    fun success_overnight_stop_dstFallBack_wallClockNextDay() {
        // Given: overnight 23:00–01:00 starting Oct 31 → Nov 1 01:00 wall (before 02:00 fall-back)
        val ny = ZoneId.of("America/New_York")
        val start = epoch(2026, 10, 31, 23, 0, ny)
        val s = schedule(23 * 60, 60, RecordingScheduleRepeatMode.ONCE)
        val expected = epoch(2026, 11, 1, 1, 0, ny)
        assertEquals(expected, stopTriggerEpochMillis(s, start, ny))
    }

    // --- past → next cycle ---

    @Test
    fun success_pastExactNowGoesToNextCycle() {
        // Given: now == start → treated as past, next day (DAILY)
        val now = epoch(2026, 8, 5, 9, 0)
        val s = schedule(9 * 60, 9 * 60 + 10, RecordingScheduleRepeatMode.DAILY)
        assertEquals(epoch(2026, 8, 6, 9, 0), nextTriggerEpochMillis(s, now, zone))
    }

    @Test
    fun success_pastInOvernightWindowGoesToNextStart() {
        // Given: overnight 23:00–01:00, now Thu 00:30 (after midnight, start already past)
        val now = epoch(2026, 8, 6, 0, 30)
        val s = schedule(23 * 60, 60, RecordingScheduleRepeatMode.DAILY)
        assertEquals(epoch(2026, 8, 6, 23, 0), nextTriggerEpochMillis(s, now, zone))
    }

    // --- requestCode collision regression ---

    @Test
    fun success_requestCode_startNeverCollidesWithStop() {
        // Given / When / Then: START even, STOP odd — cross-id collision 불가
        val ids = listOf(0L, 1L, 42L, 100_000L, 200_000L, MAX_SCHEDULE_ID_FOR_REQUEST_CODE)
        for (id in ids) {
            val start = RecordingScheduleAlarmScheduler.requestCodeStart(id)
            val stop = RecordingScheduleAlarmScheduler.requestCodeStop(id)
            assertEquals(id.toInt() shl 1, start)
            assertEquals((id.toInt() shl 1) or 1, stop)
            assertTrue(start != stop)
            assertEquals(0, start and 1)
            assertEquals(1, stop and 1)
        }
        // Cross-id: START(a) != STOP(b) for sampled pairs (parity guarantees all)
        for (a in ids) {
            for (b in ids) {
                assertNotEquals(
                    RecordingScheduleAlarmScheduler.requestCodeStart(a),
                    RecordingScheduleAlarmScheduler.requestCodeStop(b),
                )
            }
        }
    }

    @Test
    fun failure_requestCode_rejectsOverflowId() {
        // Given
        val tooLarge = MAX_SCHEDULE_ID_FOR_REQUEST_CODE + 1

        // When / Then — START
        try {
            RecordingScheduleAlarmScheduler.requestCodeStart(tooLarge)
            org.junit.Assert.fail("expected IllegalArgumentException for requestCodeStart")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        // STOP overflow도 동일
        try {
            RecordingScheduleAlarmScheduler.requestCodeStop(tooLarge)
            org.junit.Assert.fail("expected IllegalArgumentException for requestCodeStop")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun success_registerStart_overflowId_returnsFalseNoCrash() {
        // Given: id beyond MAX — register must not throw
        val now = epoch(2026, 8, 5, 10, 0)
        val s = schedule(
            14 * 60,
            14 * 60 + 30,
            RecordingScheduleRepeatMode.ONCE,
            id = MAX_SCHEDULE_ID_FOR_REQUEST_CODE + 1,
        )
        val fake = FakeRecordingAlarmBackend()

        // When
        val ok = RecordingScheduleAlarmScheduler.registerStart(s, fake, now, zone)

        // Then
        assertFalse(ok)
        assertTrue(fake.setExactCalls.isEmpty())
        assertTrue(fake.cancelCalls.isEmpty())
    }

    @Test
    fun success_cancel_overflowId_isNoOp() {
        val fake = FakeRecordingAlarmBackend()
        RecordingScheduleAlarmScheduler.cancel(MAX_SCHEDULE_ID_FOR_REQUEST_CODE + 1, fake)
        assertTrue(fake.cancelCalls.isEmpty())
    }

    // --- enabled=false cancel seam (registerStop path) ---

    @Test
    fun success_stopTriggerForRegister_disabledReturnsNull() {
        // Given: disabled schedule — registerStop must cancel, not schedule
        val start = epoch(2026, 8, 5, 10, 0)
        val s = schedule(10 * 60, 10 * 60 + 30, RecordingScheduleRepeatMode.ONCE, enabled = false)

        // When / Then
        assertNull(stopTriggerForRegister(s, start, zone))
        assertNull(nextTriggerEpochMillis(s, start, zone))
    }

    @Test
    fun success_stopTriggerForRegister_enabledReturnsWallStop() {
        val start = epoch(2026, 8, 5, 10, 0)
        val s = schedule(10 * 60, 10 * 60 + 30, RecordingScheduleRepeatMode.ONCE)
        assertEquals(epoch(2026, 8, 5, 10, 30), stopTriggerForRegister(s, start, zone))
    }

    // --- register/cancel Fake backend (Grill-me 1·2·4) ---

    @Test
    fun success_registerStart_permissionDenied_cancelsStale() {
        // Given: valid future trigger but exact-alarm denied
        val now = epoch(2026, 8, 5, 10, 0)
        val s = schedule(14 * 60, 14 * 60 + 30, RecordingScheduleRepeatMode.ONCE, id = 7L)
        val fake = FakeRecordingAlarmBackend(canSchedule = false)

        // When
        val ok = RecordingScheduleAlarmScheduler.registerStart(s, fake, now, zone)

        // Then: false + cancel START+STOP, no setExact
        assertFalse(ok)
        assertTrue(fake.setExactCalls.isEmpty())
        assertEquals(2, fake.cancelCalls.size)
        assertEquals(
            RecordingScheduleAlarmScheduler.ACTION_SCHEDULED_START,
            fake.cancelCalls[0].action,
        )
        assertEquals(
            RecordingScheduleAlarmScheduler.ACTION_SCHEDULED_STOP,
            fake.cancelCalls[1].action,
        )
        assertEquals(7L, fake.cancelCalls[0].scheduleId)
    }

    @Test
    fun success_registerStop_permissionDenied_cancelsStale() {
        val start = epoch(2026, 8, 5, 14, 0)
        val s = schedule(14 * 60, 14 * 60 + 30, RecordingScheduleRepeatMode.ONCE, id = 3L)
        val fake = FakeRecordingAlarmBackend(canSchedule = false)

        val ok = RecordingScheduleAlarmScheduler.registerStop(s, start, fake, zone)

        assertFalse(ok)
        assertTrue(fake.setExactCalls.isEmpty())
        assertEquals(2, fake.cancelCalls.size)
    }

    @Test
    fun success_registerStart_setExactFailure_doesNotCancelExisting() {
        // Given: setExact throws after permission ok — must NOT cancel (no evaporate)
        val now = epoch(2026, 8, 5, 10, 0)
        val s = schedule(14 * 60, 14 * 60 + 30, RecordingScheduleRepeatMode.ONCE, id = 5L)
        val fake = FakeRecordingAlarmBackend(
            canSchedule = true,
            setExactThrows = RuntimeException("setExact failed"),
        )

        // When
        val ok = RecordingScheduleAlarmScheduler.registerStart(s, fake, now, zone)

        // Then
        assertFalse(ok)
        assertTrue(fake.cancelCalls.isEmpty())
        assertTrue(fake.setExactCalls.isEmpty()) // threw before record
    }

    @Test
    fun success_registerStop_setExactFailure_doesNotCancelExisting() {
        val start = epoch(2026, 8, 5, 14, 0)
        val s = schedule(14 * 60, 14 * 60 + 30, RecordingScheduleRepeatMode.ONCE, id = 5L)
        val fake = FakeRecordingAlarmBackend(
            canSchedule = true,
            setExactThrows = SecurityException("denied mid-set"),
        )

        val ok = RecordingScheduleAlarmScheduler.registerStop(s, start, fake, zone)

        assertFalse(ok)
        assertTrue(fake.cancelCalls.isEmpty())
    }

    @Test
    fun success_registerStart_ok_thenCancelsStaleStopOnly() {
        // Given: setExact succeeds → cancel STOP only (not pre-cancel)
        val now = epoch(2026, 8, 5, 10, 0)
        val s = schedule(14 * 60, 14 * 60 + 30, RecordingScheduleRepeatMode.ONCE, id = 9L)
        val fake = FakeRecordingAlarmBackend()

        // When
        val ok = RecordingScheduleAlarmScheduler.registerStart(s, fake, now, zone)

        // Then
        assertTrue(ok)
        assertEquals(1, fake.setExactCalls.size)
        assertEquals(
            RecordingScheduleAlarmScheduler.ACTION_SCHEDULED_START,
            fake.setExactCalls[0].action,
        )
        assertEquals(epoch(2026, 8, 5, 14, 0), fake.setExactCalls[0].triggerAtMillis)
        // occurrence = triggerAt for START (D-5 contract)
        assertEquals(epoch(2026, 8, 5, 14, 0), fake.setExactCalls[0].occurrenceStartEpochMillis)
        assertEquals(1, fake.cancelCalls.size)
        assertEquals(
            RecordingScheduleAlarmScheduler.ACTION_SCHEDULED_STOP,
            fake.cancelCalls[0].action,
        )
    }

    @Test
    fun success_registerStop_ok_returnsTrueNoCancel() {
        val start = epoch(2026, 8, 5, 14, 0)
        val s = schedule(14 * 60, 14 * 60 + 30, RecordingScheduleRepeatMode.ONCE, id = 2L)
        val fake = FakeRecordingAlarmBackend()

        val ok = RecordingScheduleAlarmScheduler.registerStop(s, start, fake, zone)

        assertTrue(ok)
        assertEquals(1, fake.setExactCalls.size)
        assertEquals(
            RecordingScheduleAlarmScheduler.ACTION_SCHEDULED_STOP,
            fake.setExactCalls[0].action,
        )
        // STOP occurrence = occurrenceStart (not triggerAt)
        assertEquals(start, fake.setExactCalls[0].occurrenceStartEpochMillis)
        assertEquals(epoch(2026, 8, 5, 14, 30), fake.setExactCalls[0].triggerAtMillis)
        assertTrue(fake.cancelCalls.isEmpty())
    }

    @Test
    fun success_registerStart_disabled_cancelsAndReturnsFalse() {
        val now = epoch(2026, 8, 5, 10, 0)
        val s = schedule(
            14 * 60,
            14 * 60 + 30,
            RecordingScheduleRepeatMode.ONCE,
            enabled = false,
            id = 4L,
        )
        val fake = FakeRecordingAlarmBackend()

        val ok = RecordingScheduleAlarmScheduler.registerStart(s, fake, now, zone)

        assertFalse(ok)
        assertTrue(fake.setExactCalls.isEmpty())
        assertEquals(2, fake.cancelCalls.size)
    }
}

/**
 * JVM Fake for [RecordingAlarmBackend] — register/cancel 경로 단위 테스트용.
 */
internal class FakeRecordingAlarmBackend(
    var canSchedule: Boolean = true,
    var setExactThrows: RuntimeException? = null,
) : RecordingAlarmBackend {

    data class SetExactCall(
        val triggerAtMillis: Long,
        val requestCode: Int,
        val action: String,
        val scheduleId: Long,
        val occurrenceStartEpochMillis: Long?,
    )

    data class CancelCall(
        val requestCode: Int,
        val action: String,
        val scheduleId: Long,
    )

    val setExactCalls = mutableListOf<SetExactCall>()
    val cancelCalls = mutableListOf<CancelCall>()

    override fun canScheduleExactAlarms(): Boolean = canSchedule

    override fun setExact(
        triggerAtMillis: Long,
        requestCode: Int,
        action: String,
        scheduleId: Long,
        occurrenceStartEpochMillis: Long?,
    ) {
        setExactThrows?.let { throw it }
        setExactCalls += SetExactCall(
            triggerAtMillis,
            requestCode,
            action,
            scheduleId,
            occurrenceStartEpochMillis,
        )
    }

    override fun cancel(requestCode: Int, action: String, scheduleId: Long) {
        cancelCalls += CancelCall(requestCode, action, scheduleId)
    }
}
