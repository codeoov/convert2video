package com.example.convert2video.record

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.example.convert2video.data.RecordingSchedule
import com.example.convert2video.utils.AppLogger
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

private const val TAG = "RecordingScheduleAlarmScheduler"

/**
 * 예약 녹음 AlarmManager 등록/취소 + 다음 시작 시각 계산.
 *
 * PendingIntent 타깃은 D-5 [ScheduledRecordingReceiver] 클래스명·action **문자열 상수**만 사용한다
 * (이 스프린트에는 Receiver 실클래스·Manifest 등록 없음).
 *
 * ## PendingIntent identity (cancel 계약)
 * AlarmManager cancel / [PendingIntent.cancel] 매칭은 **requestCode + action + component**
 * (package + [RECEIVER_CLASS_NAME]) 이다. extras는 identity에 포함되지 않는다.
 * cancel 시에는 [PendingIntent.FLAG_NO_CREATE] or [PendingIntent.FLAG_IMMUTABLE] 로
 * 기존 PI만 조회하고, 없으면 no-op.
 *
 * ## requestCode
 * START = `scheduleId.toInt() shl 1` (짝수), STOP = `shl 1 or 1` (홀수).
 * 동일 scheduleId의 START/STOP은 물론, START(id)와 STOP(id')도 절대 충돌하지 않는다.
 * [MAX_SCHEDULE_ID_FOR_REQUEST_CODE] 초과 id는 register/cancel에서 catch → false/no-op
 * (공개 [requestCodeStart]/[requestCodeStop]는 단위 테스트용으로 throw 유지).
 *
 * ## setExact 실패 시 알람 보존
 * **성공한 setExact 이후에만** 짝 알람(START 등록 후 stale STOP 등)을 cancel한다.
 * setExact 전에 cancel하지 않는다 — 실패 시 기존 알람이 증발하지 않도록
 * 동일 requestCode+action PI의 replace 의미에 맡긴다.
 *
 * ## 권한 거부 정책
 * [ExactAlarmPermission.canScheduleExactAlarms] == false 이면 **새 등록을 하지 않고**
 * 기존 START/STOP을 [cancel]한다 (권한 철회 후 stale 알람이 계속 울리지 않게).
 *
 * ## Call pause (C3)
 * AudioFocus 통화 pause/resume은 [RecordingService]가 처리한다. 이 스케줄러의
 * [AlarmManager.RTC_WAKEUP] STOP은 통화 구간만큼 미루지 않는다 — 벽시계 종료 시각 유지.
 * Quick Timer STOP 보정은 [RecordingCountdownAlarmScheduler] (C2)만.
 *
 * ## D-5 Intent extras 계약 (Receiver 필수)
 * - [EXTRA_SCHEDULE_ID] / [EXTRA_OCCURRENCE_START_EPOCH_MILLIS]: Receiver는
 *   **[Intent.hasExtra]로 존재 여부를 확인**한다. `getLongExtra(..., 0)` 기본값 금지
 *   (schedule id=0·epoch=0과 혼동).
 * - START: occurrence = 이번 triggerAt (등록 시각).
 * - STOP: occurrence = 인자 occurrenceStart (START occurrence와 동일 키).
 */
object RecordingScheduleAlarmScheduler {

    /** D-5에서 실클래스를 붙일 Fully-qualified class name. */
    const val RECEIVER_CLASS_NAME =
        "com.example.convert2video.record.ScheduledRecordingReceiver"

    const val ACTION_SCHEDULED_START =
        "com.example.convert2video.action.SCHEDULED_RECORDING_START"

    const val ACTION_SCHEDULED_STOP =
        "com.example.convert2video.action.SCHEDULED_RECORDING_STOP"

    const val EXTRA_SCHEDULE_ID = "schedule_id"

    const val EXTRA_OCCURRENCE_START_EPOCH_MILLIS = "occurrence_start_epoch_millis"

    /** register 시 create/replace. */
    private const val PENDING_INTENT_FLAGS_UPDATE =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    /** cancel 시 기존 PI만 조회 (없으면 null). */
    private const val PENDING_INTENT_FLAGS_NO_CREATE =
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE

    /**
     * 다음 START 알람 등록.
     *
     * @return `true` = [AlarmManager.setExactAndAllowWhileIdle] 성공.
     *         `false` = 미등록(disabled / nextTrigger null → 기존 취소, 권한 없음→cancel,
     *         setExact 실패→**기존 유지**, requestCode overflow, 오류 등).
     *         성공이 아닌데 true를 반환하지 않는다 (silent pretend-success 금지).
     */
    fun registerStart(context: Context, schedule: RecordingSchedule): Boolean =
        registerStart(
            schedule,
            AndroidRecordingAlarmBackend(context.applicationContext),
            System.currentTimeMillis(),
        )

    /**
     * 해당 occurrence의 STOP 알람 등록.
     *
     * [RecordingSchedule.enabled]가 false이면 기존 START/STOP을 취소하고 `false`를 반환한다
     * (disabled 스케줄에 STOP을 남기지 않음).
     *
     * @return `true` = setExact 성공. `false` = 미등록(disabled / invalid stop / 권한→cancel /
     *         setExact 실패→**기존 STOP 유지** / overflow).
     */
    fun registerStop(
        context: Context,
        schedule: RecordingSchedule,
        occurrenceStartEpochMillis: Long,
    ): Boolean = registerStop(
        schedule,
        occurrenceStartEpochMillis,
        AndroidRecordingAlarmBackend(context.applicationContext),
    )

    /**
     * START·STOP PendingIntent 모두 취소.
     * 매칭 계약: [requestCodeStart]/[requestCodeStop] + action + [RECEIVER_CLASS_NAME] component.
     * [FLAG_NO_CREATE]로 기존 PI만 조회 — 없으면 skip.
     * requestCode overflow → AppLogger + no-op (crash 금지).
     */
    fun cancel(context: Context, scheduleId: Long) {
        cancel(scheduleId, AndroidRecordingAlarmBackend(context.applicationContext))
    }

    /**
     * Testable overload — [RecordingAlarmBackend] seam (Context 불필요).
     * @param nowEpochMillis nextTrigger 기준 시각 (프로덕션은 [System.currentTimeMillis])
     * @param zoneId nextTrigger/stop 벽시계 zone (프로덕션 systemDefault; 테스트는 고정 zone)
     */
    internal fun registerStart(
        schedule: RecordingSchedule,
        backend: RecordingAlarmBackend,
        nowEpochMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        val triggerAt = nextTriggerEpochMillis(schedule, nowEpochMillis, zoneId)
        if (!schedule.enabled || triggerAt == null) {
            AppLogger.w(
                TAG,
                "registerStart cancel-only: enabled=${schedule.enabled} " +
                    "trigger=$triggerAt id=${schedule.id}",
            )
            cancel(schedule.id, backend)
            return false
        }
        if (!backend.canScheduleExactAlarms()) {
            AppLogger.w(
                TAG,
                "registerStart denied: exact alarm permission — cancel stale id=${schedule.id}",
            )
            cancel(schedule.id, backend)
            return false
        }
        val startCode = requestCodeStartOrNull(schedule.id) ?: return false
        // setExact 성공 전에는 START/STOP을 cancel하지 않음 (실패 시 기존 알람 보존).
        // 동일 requestCode+action 은 AlarmManager replace 의미.
        val setOk = try {
            backend.setExact(
                triggerAtMillis = triggerAt,
                requestCode = startCode,
                action = ACTION_SCHEDULED_START,
                scheduleId = schedule.id,
                occurrenceStartEpochMillis = triggerAt,
            )
            true
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "registerStart SecurityException id=${schedule.id}", e)
            false
        } catch (e: RuntimeException) {
            AppLogger.e(TAG, "registerStart failed id=${schedule.id}", e)
            false
        }
        if (!setOk) return false
        // 성공 후에만 stale STOP 제거
        cancelStopOnly(schedule.id, backend)
        return true
    }

    /** Testable overload — [RecordingAlarmBackend] seam. */
    internal fun registerStop(
        schedule: RecordingSchedule,
        occurrenceStartEpochMillis: Long,
        backend: RecordingAlarmBackend,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        if (!schedule.enabled) {
            AppLogger.w(TAG, "registerStop cancel-only: disabled id=${schedule.id}")
            cancel(schedule.id, backend)
            return false
        }
        val triggerAt = stopTriggerEpochMillis(schedule, occurrenceStartEpochMillis, zoneId)
        if (triggerAt == null || triggerAt <= occurrenceStartEpochMillis) {
            AppLogger.w(
                TAG,
                "registerStop cancel-stop: invalid stop id=${schedule.id} " +
                    "start=$occurrenceStartEpochMillis stop=$triggerAt",
            )
            cancelStopOnly(schedule.id, backend)
            return false
        }
        if (!backend.canScheduleExactAlarms()) {
            AppLogger.w(
                TAG,
                "registerStop denied: exact alarm permission — cancel stale id=${schedule.id}",
            )
            cancel(schedule.id, backend)
            return false
        }
        val stopCode = requestCodeStopOrNull(schedule.id) ?: return false
        // setExact 성공 전 cancel 금지 — 실패 시 기존 STOP 유지 (replace-on-success).
        return try {
            backend.setExact(
                triggerAtMillis = triggerAt,
                requestCode = stopCode,
                action = ACTION_SCHEDULED_STOP,
                scheduleId = schedule.id,
                occurrenceStartEpochMillis = occurrenceStartEpochMillis,
            )
            true
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "registerStop SecurityException id=${schedule.id}", e)
            false
        } catch (e: RuntimeException) {
            AppLogger.e(TAG, "registerStop failed id=${schedule.id}", e)
            false
        }
    }

    /** Testable overload — [RecordingAlarmBackend] seam. */
    internal fun cancel(scheduleId: Long, backend: RecordingAlarmBackend) {
        val startCode = requestCodeStartOrNull(scheduleId) ?: return
        val stopCode = requestCodeStopOrNull(scheduleId) ?: return
        backend.cancel(startCode, ACTION_SCHEDULED_START, scheduleId)
        backend.cancel(stopCode, ACTION_SCHEDULED_STOP, scheduleId)
    }

    /** STOP만 취소 (invalid stop 시 START는 유지). */
    private fun cancelStopOnly(scheduleId: Long, backend: RecordingAlarmBackend) {
        val stopCode = requestCodeStopOrNull(scheduleId) ?: return
        backend.cancel(stopCode, ACTION_SCHEDULED_STOP, scheduleId)
    }

    /**
     * START requestCode — 짝수 `id shl 1`.
     * STOP과 비트 대역이 겹치지 않음 (회귀: [requestCodeStart] ≠ [requestCodeStop] for any ids).
     * @throws IllegalArgumentException [MAX_SCHEDULE_ID_FOR_REQUEST_CODE] 초과 시
     */
    fun requestCodeStart(scheduleId: Long): Int {
        require(scheduleId in 0L..MAX_SCHEDULE_ID_FOR_REQUEST_CODE) {
            "scheduleId out of requestCode range: $scheduleId"
        }
        return scheduleId.toInt() shl 1
    }

    /**
     * STOP requestCode — 홀수 `id shl 1 or 1`.
     * @throws IllegalArgumentException [MAX_SCHEDULE_ID_FOR_REQUEST_CODE] 초과 시
     */
    fun requestCodeStop(scheduleId: Long): Int {
        require(scheduleId in 0L..MAX_SCHEDULE_ID_FOR_REQUEST_CODE) {
            "scheduleId out of requestCode range: $scheduleId"
        }
        return (scheduleId.toInt() shl 1) or 1
    }

    private fun requestCodeStartOrNull(scheduleId: Long): Int? = try {
        requestCodeStart(scheduleId)
    } catch (e: IllegalArgumentException) {
        AppLogger.e(TAG, "requestCodeStart overflow id=$scheduleId", e)
        null
    }

    private fun requestCodeStopOrNull(scheduleId: Long): Int? = try {
        requestCodeStop(scheduleId)
    } catch (e: IllegalArgumentException) {
        AppLogger.e(TAG, "requestCodeStop overflow id=$scheduleId", e)
        null
    }

    /**
     * Production PendingIntent 빌더 — [AndroidRecordingAlarmBackend] 전용.
     * @return UPDATE_CURRENT면 non-null PI; NO_CREATE면 기존 없을 때 null.
     */
    internal fun pendingIntent(
        context: Context,
        action: String,
        requestCode: Int,
        scheduleId: Long,
        occurrenceStartEpochMillis: Long?,
        flags: Int,
    ): PendingIntent? {
        val intent = Intent(action).setClassName(context.packageName, RECEIVER_CLASS_NAME).apply {
            putExtra(EXTRA_SCHEDULE_ID, scheduleId)
            if (occurrenceStartEpochMillis != null) {
                putExtra(EXTRA_OCCURRENCE_START_EPOCH_MILLIS, occurrenceStartEpochMillis)
            }
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    internal const val PENDING_INTENT_FLAGS_UPDATE_INTERNAL = PENDING_INTENT_FLAGS_UPDATE
    internal const val PENDING_INTENT_FLAGS_NO_CREATE_INTERNAL = PENDING_INTENT_FLAGS_NO_CREATE
}

/**
 * AlarmManager/PendingIntent 테스트 seam.
 * 프로덕션: [AndroidRecordingAlarmBackend]. 단위 테스트: [FakeRecordingAlarmBackend].
 */
internal interface RecordingAlarmBackend {
    fun canScheduleExactAlarms(): Boolean

    /**
     * Exact alarm 등록(동일 requestCode+action이면 replace).
     * @throws SecurityException / RuntimeException on failure — caller가 기존 알람 보존.
     */
    fun setExact(
        triggerAtMillis: Long,
        requestCode: Int,
        action: String,
        scheduleId: Long,
        occurrenceStartEpochMillis: Long?,
    )

    fun cancel(requestCode: Int, action: String, scheduleId: Long)
}

/** Production backend — AlarmManager + PendingIntent. */
internal class AndroidRecordingAlarmBackend(
    private val context: Context,
) : RecordingAlarmBackend {

    override fun canScheduleExactAlarms(): Boolean =
        ExactAlarmPermission.canScheduleExactAlarms(context)

    override fun setExact(
        triggerAtMillis: Long,
        requestCode: Int,
        action: String,
        scheduleId: Long,
        occurrenceStartEpochMillis: Long?,
    ) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
            ?: throw IllegalStateException("AlarmManager null")
        // Scheduler null→false: AlarmManager 없으면 등록 불가 (UI ExactAlarm null→true와 다름).
        val pending = RecordingScheduleAlarmScheduler.pendingIntent(
            context,
            action = action,
            requestCode = requestCode,
            scheduleId = scheduleId,
            occurrenceStartEpochMillis = occurrenceStartEpochMillis,
            flags = RecordingScheduleAlarmScheduler.PENDING_INTENT_FLAGS_UPDATE_INTERNAL,
        ) ?: throw IllegalStateException("PendingIntent null")
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAtMillis,
            pending,
        )
    }

    override fun cancel(requestCode: Int, action: String, scheduleId: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        if (alarmManager == null) {
            AppLogger.w(TAG, "cancel: AlarmManager null id=$scheduleId action=$action")
            return
        }
        val existing = RecordingScheduleAlarmScheduler.pendingIntent(
            context,
            action = action,
            requestCode = requestCode,
            scheduleId = scheduleId,
            occurrenceStartEpochMillis = null,
            flags = RecordingScheduleAlarmScheduler.PENDING_INTENT_FLAGS_NO_CREATE_INTERNAL,
        )
        if (existing == null) return
        try {
            alarmManager.cancel(existing)
            existing.cancel()
        } catch (e: RuntimeException) {
            AppLogger.e(TAG, "cancel failed id=$scheduleId action=$action", e)
        }
    }
}

/** requestCode = id≪1 이 Int 범위 안이도록 (shl 1 overflow 방지). */
internal const val MAX_SCHEDULE_ID_FOR_REQUEST_CODE = (Int.MAX_VALUE / 2).toLong()

/**
 * 다음 START 트리거 epoch ms (벽시계 시작 시각만).
 *
 * - TZ: [zoneId] (기본 [ZoneId.systemDefault])
 * - 요일: bit0=월 .. bit6=일 ([RecordingSchedule.daysOfWeekMask] / `todayWeekdayBitMask`와 동일)
 * - **ONCE**: 다음 벽시계 start만 반환 (오늘 지났으면 다음날). past→null 하지 않음.
 *   one-shot 소비 / [RecordingSchedule.enabled]=false 는 **D-5 Receiver·VM** 책임.
 * - DAILY: 다음 startMinuteOfDay (오늘이 지났으면 다음날)
 * - WEEKLY: mask에 맞는 다음 요일의 start (해당 요일만·이미 지났으면 dayOffset=7로 다음 주)
 * - overnight(`end < start`)여도 START는 startMinuteOfDay 기준
 * - [nowEpochMillis] 시각이 이미 start 이후(또는 동일)면 다음 주기
 * - disabled / 동일 분 / minute∉0..1439 / WEEKLY mask0 → null
 */
internal fun nextTriggerEpochMillis(
    schedule: RecordingSchedule,
    nowEpochMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): Long? {
    if (!schedule.enabled) return null
    if (schedule.startMinuteOfDay !in MINUTE_OF_DAY_RANGE ||
        schedule.endMinuteOfDay !in MINUTE_OF_DAY_RANGE
    ) {
        return null
    }
    if (schedule.startMinuteOfDay == schedule.endMinuteOfDay) return null

    val mode = RecordingScheduleRepeatMode.fromStorageValue(schedule.repeatMode)
    if (mode == RecordingScheduleRepeatMode.WEEKLY && schedule.daysOfWeekMask == 0) {
        return null
    }

    val nowZoned = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId)
    // 최대 8일(오늘 포함) 탐색 — WEEKLY 전 요일 커버 (일요일-only past → dayOffset=7)
    for (dayOffset in 0..7) {
        val date = nowZoned.toLocalDate().plusDays(dayOffset.toLong())
        if (!matchesRepeatDay(mode, schedule.daysOfWeekMask, date.dayOfWeek.value)) {
            continue
        }
        val trigger = ZonedDateTime.of(
            date.year,
            date.monthValue,
            date.dayOfMonth,
            schedule.startMinuteOfDay / 60,
            schedule.startMinuteOfDay % 60,
            0,
            0,
            zoneId,
        ).toInstant().toEpochMilli()
        // past 또는 동일 시각 → 다음 주기
        if (trigger > nowEpochMillis) {
            return trigger
        }
    }
    return null
}

/**
 * occurrence START 기준 STOP epoch ms — **벽시계 endMinuteOfDay** ([ZoneId]/[ZonedDateTime]).
 * overnight(`end < start`)이면 시작일+1일의 endMinuteOfDay.
 * raw `durationMinutes * 60_000` 금지 (DST에서 벽시계와 어긋남).
 *
 * [zoneId]는 [nextTriggerEpochMillis]와 동일하게 정렬한다 (기본 systemDefault).
 */
internal fun stopTriggerEpochMillis(
    schedule: RecordingSchedule,
    occurrenceStartEpochMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): Long? {
    if (schedule.startMinuteOfDay !in MINUTE_OF_DAY_RANGE ||
        schedule.endMinuteOfDay !in MINUTE_OF_DAY_RANGE
    ) {
        return null
    }
    if (schedule.startMinuteOfDay == schedule.endMinuteOfDay) return null

    val startZoned = Instant.ofEpochMilli(occurrenceStartEpochMillis).atZone(zoneId)
    val endDate = if (schedule.endMinuteOfDay < schedule.startMinuteOfDay) {
        startZoned.toLocalDate().plusDays(1)
    } else {
        startZoned.toLocalDate()
    }
    return ZonedDateTime.of(
        endDate.year,
        endDate.monthValue,
        endDate.dayOfMonth,
        schedule.endMinuteOfDay / 60,
        schedule.endMinuteOfDay % 60,
        0,
        0,
        zoneId,
    ).toInstant().toEpochMilli()
}

/**
 * registerStop이 알람을 올릴지 순수 판정 (enabled=false → null → caller가 cancel).
 * JVM 단위 테스트용 seam.
 */
internal fun stopTriggerForRegister(
    schedule: RecordingSchedule,
    occurrenceStartEpochMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): Long? {
    if (!schedule.enabled) return null
    val stopAt = stopTriggerEpochMillis(schedule, occurrenceStartEpochMillis, zoneId) ?: return null
    if (stopAt <= occurrenceStartEpochMillis) return null
    return stopAt
}

/**
 * Monday-start bit: [java.time.DayOfWeek.getValue] MON=1..SUN=7 → bit = value - 1.
 */
private fun matchesRepeatDay(
    mode: RecordingScheduleRepeatMode,
    daysOfWeekMask: Int,
    dayOfWeekValue: Int,
): Boolean = when (mode) {
    RecordingScheduleRepeatMode.ONCE,
    RecordingScheduleRepeatMode.DAILY,
    -> true
    RecordingScheduleRepeatMode.WEEKLY -> {
        val bit = 1 shl (dayOfWeekValue - 1)
        daysOfWeekMask and bit != 0
    }
}

private val MINUTE_OF_DAY_RANGE = 0..1439
