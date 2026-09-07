package com.example.convert2video.record

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.example.convert2video.utils.AppLogger

private const val TAG = "RecordingCountdownAlarmScheduler"

internal const val COUNTDOWN_MINUTES_MIN = 1
internal const val COUNTDOWN_MINUTES_MAX = 180
internal val COUNTDOWN_MINUTES_RANGE = COUNTDOWN_MINUTES_MIN..COUNTDOWN_MINUTES_MAX

internal const val DEFAULT_COUNTDOWN_START_IN_MINUTES = 5
internal const val DEFAULT_COUNTDOWN_DURATION_MINUTES = 10

private const val MILLIS_PER_MINUTE = 60_000L

/**
 * Options Quick Timer — [AlarmManager.ELAPSED_REALTIME_WAKEUP] START/STOP.
 *
 * [RecordingAlarmBackend.setExact] / [AlarmManager.RTC_WAKEUP] 을 재사용하지 않는다.
 * PendingIntent 타깃은 [RecordingScheduleAlarmScheduler.RECEIVER_CLASS_NAME] (extras 없음).
 *
 * ## requestCode
 * START = [REQUEST_CODE_COUNTDOWN_START] (`-71001`), STOP = [REQUEST_CODE_COUNTDOWN_STOP] (`-71002`).
 *
 * ## 권한 거부
 * [canScheduleExactAlarms] false → stale START/STOP [cancel] 후 `false`.
 *
 * ## setElapsed 실패
 * 성공 전 cancel 금지 — 실패 시 기존 알람 보존 (replace-on-success).
 *
 * ## Call pause (C2)
 * 통화로 pause되면 STOP만 [cancelStop] 하고, resume 시 남은 실제 녹음 시간으로
 * [registerStopRemainingMs] 재등록한다. Once/Weekly/Daily RTC STOP은 여기 없음 (C3).
 */
object RecordingCountdownAlarmScheduler {

    const val ACTION_COUNTDOWN_START =
        "com.example.convert2video.action.COUNTDOWN_RECORDING_START"

    const val ACTION_COUNTDOWN_STOP =
        "com.example.convert2video.action.COUNTDOWN_RECORDING_STOP"

    const val REQUEST_CODE_COUNTDOWN_START = -71001
    const val REQUEST_CODE_COUNTDOWN_STOP = -71002

    private const val PENDING_INTENT_FLAGS_UPDATE =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private const val PENDING_INTENT_FLAGS_NO_CREATE =
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE

    fun registerStart(
        context: Context,
        startInMinutes: Int,
        nowElapsedMillis: Long = SystemClock.elapsedRealtime(),
    ): Boolean = registerStart(
        startInMinutes,
        AndroidRecordingCountdownAlarmBackend(context.applicationContext),
        nowElapsedMillis,
    )

    fun registerStop(
        context: Context,
        durationMinutes: Int,
        nowElapsedMillis: Long = SystemClock.elapsedRealtime(),
    ): Boolean = registerStop(
        durationMinutes,
        nowElapsedMillis,
        AndroidRecordingCountdownAlarmBackend(context.applicationContext),
    )

    fun registerStopRemainingMs(
        context: Context,
        remainingMs: Long,
        nowElapsedMillis: Long = SystemClock.elapsedRealtime(),
    ): Boolean = registerStopRemainingMs(
        remainingMs,
        nowElapsedMillis,
        AndroidRecordingCountdownAlarmBackend(context.applicationContext),
    )

    fun cancel(context: Context) {
        cancel(AndroidRecordingCountdownAlarmBackend(context.applicationContext))
    }

    fun cancelStop(context: Context) {
        cancelStop(AndroidRecordingCountdownAlarmBackend(context.applicationContext))
    }

    fun hasPendingStartPendingIntent(context: Context): Boolean =
        hasPendingStartPendingIntent(
            AndroidRecordingCountdownAlarmBackend(context.applicationContext),
        )

    fun hasPendingStopPendingIntent(context: Context): Boolean =
        hasPendingStopPendingIntent(
            AndroidRecordingCountdownAlarmBackend(context.applicationContext),
        )

    internal fun registerStart(
        startInMinutes: Int,
        backend: RecordingCountdownAlarmBackend,
        nowElapsedMillis: Long,
    ): Boolean {
        if (!isCountdownMinutesInRange(startInMinutes)) {
            AppLogger.w(TAG, "registerStart out of range minutes=$startInMinutes")
            return false
        }
        if (!backend.canScheduleExactAlarms()) {
            AppLogger.w(TAG, "registerStart denied: exact alarm permission — cancel stale")
            cancel(backend)
            return false
        }
        val triggerAt = countdownTriggerElapsedMillis(nowElapsedMillis, startInMinutes)
        val setOk = try {
            backend.setElapsedRealtimeWakeup(
                triggerAtElapsedMillis = triggerAt,
                requestCode = REQUEST_CODE_COUNTDOWN_START,
                action = ACTION_COUNTDOWN_START,
            )
            true
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "registerStart SecurityException", e)
            false
        } catch (e: RuntimeException) {
            AppLogger.e(TAG, "registerStart failed", e)
            false
        }
        if (!setOk) return false
        cancelStopOnly(backend)
        return true
    }

    internal fun registerStop(
        durationMinutes: Int,
        nowElapsedMillis: Long,
        backend: RecordingCountdownAlarmBackend,
    ): Boolean {
        if (!isCountdownMinutesInRange(durationMinutes)) {
            AppLogger.w(TAG, "registerStop out of range minutes=$durationMinutes")
            return false
        }
        if (!backend.canScheduleExactAlarms()) {
            AppLogger.w(TAG, "registerStop denied: exact alarm permission — cancel stale")
            cancel(backend)
            return false
        }
        val triggerAt = countdownTriggerElapsedMillis(nowElapsedMillis, durationMinutes)
        val setOk = try {
            backend.setElapsedRealtimeWakeup(
                triggerAtElapsedMillis = triggerAt,
                requestCode = REQUEST_CODE_COUNTDOWN_STOP,
                action = ACTION_COUNTDOWN_STOP,
            )
            true
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "registerStop SecurityException", e)
            false
        } catch (e: RuntimeException) {
            AppLogger.e(TAG, "registerStop failed", e)
            false
        }
        if (!setOk) return false
        cancelStartOnly(backend)
        return true
    }

    internal fun registerStopRemainingMs(
        remainingMs: Long,
        nowElapsedMillis: Long,
        backend: RecordingCountdownAlarmBackend,
    ): Boolean {
        if (!isCountdownRemainingMsInRange(remainingMs)) {
            AppLogger.w(TAG, "registerStopRemainingMs out of range remainingMs=$remainingMs")
            return false
        }
        if (!backend.canScheduleExactAlarms()) {
            AppLogger.w(TAG, "registerStopRemainingMs denied: exact alarm permission — cancel stale")
            cancel(backend)
            return false
        }
        val triggerAt = countdownRemainingTriggerElapsedMillis(nowElapsedMillis, remainingMs)
        val setOk = try {
            backend.setElapsedRealtimeWakeup(
                triggerAtElapsedMillis = triggerAt,
                requestCode = REQUEST_CODE_COUNTDOWN_STOP,
                action = ACTION_COUNTDOWN_STOP,
            )
            true
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "registerStopRemainingMs SecurityException", e)
            false
        } catch (e: RuntimeException) {
            AppLogger.e(TAG, "registerStopRemainingMs failed", e)
            false
        }
        if (!setOk) return false
        cancelStartOnly(backend)
        return true
    }

    internal fun cancel(backend: RecordingCountdownAlarmBackend) {
        backend.cancel(REQUEST_CODE_COUNTDOWN_START, ACTION_COUNTDOWN_START)
        backend.cancel(REQUEST_CODE_COUNTDOWN_STOP, ACTION_COUNTDOWN_STOP)
    }

    internal fun cancelStop(backend: RecordingCountdownAlarmBackend) {
        cancelStopOnly(backend)
    }

    internal fun hasPendingStartPendingIntent(backend: RecordingCountdownAlarmBackend): Boolean =
        backend.hasPendingIntent(REQUEST_CODE_COUNTDOWN_START, ACTION_COUNTDOWN_START)

    internal fun hasPendingStopPendingIntent(backend: RecordingCountdownAlarmBackend): Boolean =
        backend.hasPendingIntent(REQUEST_CODE_COUNTDOWN_STOP, ACTION_COUNTDOWN_STOP)

    private fun cancelStartOnly(backend: RecordingCountdownAlarmBackend) {
        backend.cancel(REQUEST_CODE_COUNTDOWN_START, ACTION_COUNTDOWN_START)
    }

    private fun cancelStopOnly(backend: RecordingCountdownAlarmBackend) {
        backend.cancel(REQUEST_CODE_COUNTDOWN_STOP, ACTION_COUNTDOWN_STOP)
    }

    internal fun pendingIntent(
        context: Context,
        action: String,
        requestCode: Int,
        flags: Int,
    ): PendingIntent? {
        val intent = Intent(action).setClassName(
            context.packageName,
            RecordingScheduleAlarmScheduler.RECEIVER_CLASS_NAME,
        )
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    internal const val PENDING_INTENT_FLAGS_UPDATE_INTERNAL = PENDING_INTENT_FLAGS_UPDATE
    internal const val PENDING_INTENT_FLAGS_NO_CREATE_INTERNAL = PENDING_INTENT_FLAGS_NO_CREATE
}

/**
 * Countdown 전용 AlarmManager seam — [RecordingAlarmBackend]와 분리.
 * [setElapsedRealtimeWakeup]만 ELAPSED_REALTIME_WAKEUP 를 쓴다 (RTC_WAKEUP 금지).
 */
internal interface RecordingCountdownAlarmBackend {
    fun canScheduleExactAlarms(): Boolean

    /**
     * Exact elapsed-realtime alarm (동일 requestCode+action이면 replace).
     * @throws SecurityException / RuntimeException on failure — caller가 기존 알람 보존.
     */
    fun setElapsedRealtimeWakeup(
        triggerAtElapsedMillis: Long,
        requestCode: Int,
        action: String,
    )

    fun cancel(requestCode: Int, action: String)

    fun hasPendingIntent(requestCode: Int, action: String): Boolean
}

internal class AndroidRecordingCountdownAlarmBackend(
    private val context: Context,
) : RecordingCountdownAlarmBackend {

    override fun canScheduleExactAlarms(): Boolean =
        ExactAlarmPermission.canScheduleExactAlarms(context)

    override fun setElapsedRealtimeWakeup(
        triggerAtElapsedMillis: Long,
        requestCode: Int,
        action: String,
    ) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
            ?: throw IllegalStateException("AlarmManager null")
        val pending = RecordingCountdownAlarmScheduler.pendingIntent(
            context,
            action = action,
            requestCode = requestCode,
            flags = RecordingCountdownAlarmScheduler.PENDING_INTENT_FLAGS_UPDATE_INTERNAL,
        ) ?: throw IllegalStateException("PendingIntent null")
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerAtElapsedMillis,
            pending,
        )
    }

    override fun cancel(requestCode: Int, action: String) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        if (alarmManager == null) {
            AppLogger.w(TAG, "cancel: AlarmManager null action=$action")
            return
        }
        val existing = RecordingCountdownAlarmScheduler.pendingIntent(
            context,
            action = action,
            requestCode = requestCode,
            flags = RecordingCountdownAlarmScheduler.PENDING_INTENT_FLAGS_NO_CREATE_INTERNAL,
        )
        if (existing == null) return
        try {
            alarmManager.cancel(existing)
            existing.cancel()
        } catch (e: RuntimeException) {
            AppLogger.e(TAG, "cancel failed action=$action", e)
        }
    }

    override fun hasPendingIntent(requestCode: Int, action: String): Boolean {
        return RecordingCountdownAlarmScheduler.pendingIntent(
            context,
            action = action,
            requestCode = requestCode,
            flags = RecordingCountdownAlarmScheduler.PENDING_INTENT_FLAGS_NO_CREATE_INTERNAL,
        ) != null
    }
}

internal fun isCountdownMinutesInRange(minutes: Int): Boolean =
    minutes in COUNTDOWN_MINUTES_RANGE

internal fun countdownTriggerElapsedMillis(nowElapsedMillis: Long, minutes: Int): Long =
    nowElapsedMillis + minutes.toLong() * MILLIS_PER_MINUTE

/**
 * Remaining minutes until START alarm. Never negative.
 * Any leftover millis still counts as 1 minute (ceil). Past/equal start → 0
 * (UI must use [shouldShowCountdownWaitingSoon], never "Starts in 0 min").
 */
internal fun countdownRemainingStartMinutes(
    nowElapsedMillis: Long,
    startElapsedMillis: Long,
): Int {
    val remainingMs = startElapsedMillis - nowElapsedMillis
    if (remainingMs <= 0L) return 0
    val ceilMinutes = remainingMs / MILLIS_PER_MINUTE +
        if (remainingMs % MILLIS_PER_MINUTE == 0L) 0L else 1L
    return ceilMinutes.toInt().coerceAtLeast(1)
}

/** Waiting copy: remaining 0 (Doze/지연) → soon 문자열. "Starts in 0 min" 금지. */
internal fun shouldShowCountdownWaitingSoon(remainingStartMinutes: Int): Boolean =
    remainingStartMinutes <= 0

/**
 * Elapsed recording minutes since START Accepted. Floor to minutes, clamp 0..[durationMinutes].
 * [durationMinutes] <= 0 → 0 (coerceIn throw 금지). Wall-clock elapsedRealtime —
 * pause-excluded capture time은 C2 이후.
 */
internal fun countdownElapsedRecordingMinutes(
    nowElapsedMillis: Long,
    recordingStartedElapsedMillis: Long,
    durationMinutes: Int,
): Int {
    if (durationMinutes <= 0) return 0
    val elapsedMs = nowElapsedMillis - recordingStartedElapsedMillis
    if (elapsedMs <= 0L) return 0
    val elapsedMinutes = (elapsedMs / MILLIS_PER_MINUTE).toInt().coerceAtLeast(0)
    return elapsedMinutes.coerceIn(0, durationMinutes)
}

internal fun countdownRemainingTriggerElapsedMillis(
    nowElapsedMillis: Long,
    remainingMs: Long,
): Long = nowElapsedMillis + remainingMs

internal fun remainingCountdownStopMs(durationMinutes: Int, elapsedRecordingMs: Long): Long {
    val targetMs = durationMinutes.toLong() * MILLIS_PER_MINUTE
    return (targetMs - elapsedRecordingMs).coerceAtLeast(0L)
}

internal fun isCountdownRemainingMsInRange(remainingMs: Long): Boolean =
    remainingMs in 1L..(COUNTDOWN_MINUTES_MAX.toLong() * MILLIS_PER_MINUTE)

internal fun shouldStopCountdownImmediately(remainingMs: Long): Boolean = remainingMs <= 0L

/**
 * C2: Quick Timer STOP(ELAPSED_REALTIME_WAKEUP)만 통화 pause 동안 미룬다.
 * 사용자 pause 또는 countdown STOP이 없으면 false.
 */
internal fun shouldDeferCountdownStopOnCallPause(
    pausedByCall: Boolean,
    hasPendingCountdownStop: Boolean,
): Boolean = pausedByCall && hasPendingCountdownStop

/**
 * C3: Once/Weekly/Daily STOP은 벽시계 RTC_WAKEUP.
 * 통화 pause/resume이 이 알람을 cancel하거나 재등록하면 안 된다.
 */
internal fun shouldDeferScheduledStopOnCallPause(): Boolean = false

internal fun shouldPersistPendingCountdownAfterRegister(registerOk: Boolean): Boolean =
    registerOk

internal fun isCountdownStartEnabled(
    needsExactAlarmPermission: Boolean,
    isLanguageApplying: Boolean,
    hasPendingCountdown: Boolean,
    isInFlight: Boolean,
): Boolean = !needsExactAlarmPermission &&
    !isLanguageApplying &&
    !hasPendingCountdown &&
    !isInFlight

internal fun clampCountdownMinutes(value: Int): Int =
    value.coerceIn(COUNTDOWN_MINUTES_MIN, COUNTDOWN_MINUTES_MAX)
