package com.example.convert2video.record

import android.Manifest
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.convert2video.MainActivity
import com.example.convert2video.R
import com.example.convert2video.data.AppDatabase
import com.example.convert2video.data.RecordingSchedule
import com.example.convert2video.data.RecordingScheduleRepository
import com.example.convert2video.data.SettingsRepository
import com.example.convert2video.utils.AppLogger
import com.example.convert2video.utils.requireApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "ScheduledRecordingReceiver"

/**
 * 예약 녹음 AlarmManager 타깃 BroadcastReceiver (D-5).
 *
 * ## 계약
 * - 예약 ACTION / EXTRA / FQCN은 [RecordingScheduleAlarmScheduler] 상수만 사용한다.
 * - Quick Timer ACTION은 [RecordingCountdownAlarmScheduler] (extras 없음).
 *   COUNTDOWN 분기는 handleStart/handleStop을 타지 않는다.
 * - START confirm timeout([SCHEDULED_START_CONFIRM_TIMEOUT_MS])은 Controller
 *   BIND_TIMEOUT(3s)보다 길게 둔다. timeout 후에도 state 재확인으로 거짓 음성을 보정한다.
 * - reject 직후 Recording/Paused면 [shouldRecoverRejectedStartAsAccepted] → registerStop 경로로
 *   진행한다. **STOP 없는 녹음 금지**.
 * - ONCE registerStop 실패: stop 폴백 후 **setEnabled(false)** + 알림 (좀비 스케줄 방지;
 *   재무장(registerStart)하지 않음).
 * - STOP 전달 실패: 1회 재시도 후 전용 알람 채널 알림.
 * - C3: 예약 STOP(RTC)은 통화 pause로 cancel/재등록하지 않는다.
 *   C2 Quick Timer STOP만 실제 녹음 시간만큼 보정한다.
 *
 * D-4 Scheduler KDoc의 「Receiver 실클래스 없음」은 D-5에서 해소 (Scheduler 파일 미수정).
 */
class ScheduledRecordingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) {
            AppLogger.w(TAG, "onReceive: null intent")
            return
        }
        when (intent.action) {
            RecordingScheduleAlarmScheduler.ACTION_SCHEDULED_START -> handleStart(context, intent)
            RecordingScheduleAlarmScheduler.ACTION_SCHEDULED_STOP -> handleStop(context, intent)
            RecordingCountdownAlarmScheduler.ACTION_COUNTDOWN_START ->
                handleCountdownStart(context)
            RecordingCountdownAlarmScheduler.ACTION_COUNTDOWN_STOP ->
                handleCountdownStop(context)
            else -> AppLogger.w(TAG, "onReceive: unexpected action=${intent.action}")
        }
    }

    private fun handleStart(context: Context, intent: Intent) {
        val extras = readRequiredLongExtras(intent) ?: return
        val (scheduleId, occurrenceStartEpochMillis) = extras
        val app = context.requireApplication()
        val pendingResult = goAsync()
        val job = Job()
        CoroutineScope(job + Dispatchers.IO).launch {
            try {
                handleStartAsync(app, scheduleId, occurrenceStartEpochMillis)
            } catch (e: Exception) {
                AppLogger.e(TAG, "START failed id=$scheduleId", e)
            } finally {
                pendingResult.finish()
                job.cancel()
            }
        }
    }

    private suspend fun handleStartAsync(
        app: Application,
        scheduleId: Long,
        occurrenceStartEpochMillis: Long,
    ) {
        val repository = RecordingScheduleRepository(
            AppDatabase.getInstance(app).recordingScheduleDao(),
        )
        val schedule = repository.getById(scheduleId)
        if (schedule == null || !schedule.enabled) {
            AppLogger.w(
                TAG,
                "START skip: missing or disabled id=$scheduleId present=${schedule != null}",
            )
            return
        }

        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            AppLogger.w(TAG, "START denied: RECORD_AUDIO id=$scheduleId")
            notifyPermissionDenied(app)
            return
        }

        val format = SettingsRepository.recordingFormatHot.value
            ?: SettingsRepository(app).recordingFormat.first()

        when (val attempt = awaitScheduledStartAttempt(app, format, scheduleId)) {
            ScheduledStartAttempt.BusySkipped -> {
                // 다른 세션 녹음 중 — registerStart/stop 금지, 타 세션 stop도 금지.
                AppLogger.w(TAG, "START busy skipped id=$scheduleId")
                return
            }
            ScheduledStartAttempt.NotConfirmed -> {
                // start는 시도했으나 confirm 실패. 재확인 시 Recording이면 registerStop 경로.
                val state = withContext(Dispatchers.Main) {
                    RecordingController.getInstance(app).state.value
                }
                if (shouldRecoverRejectedStartAsAccepted(state)) {
                    AppLogger.w(
                        TAG,
                        "START late false-negative — registerStop path id=$scheduleId state=$state",
                    )
                    rescheduleAfterStart(
                        app = app,
                        repository = repository,
                        schedule = schedule,
                        occurrenceStartEpochMillis = occurrenceStartEpochMillis,
                    )
                } else {
                    AppLogger.w(
                        TAG,
                        "START not accepted — skip registerStart/registerStop id=$scheduleId",
                    )
                }
                return
            }
            ScheduledStartAttempt.Accepted -> {
                rescheduleAfterStart(
                    app = app,
                    repository = repository,
                    schedule = schedule,
                    occurrenceStartEpochMillis = occurrenceStartEpochMillis,
                )
            }
        }
    }

    /**
     * Main에서 busy 가드 → start → timeout 내 Recording/Paused 확인.
     * timeout/reject 후 state 재확인: Recording/Paused면 거짓 음성 → [Accepted]
     * (이후 registerStop 경로로 **STOP 없는 녹음 금지**).
     * busy는 [BusySkipped] — 타 세션 stop 금지.
     */
    internal suspend fun awaitScheduledStartAttempt(
        app: Application,
        format: RecordingFormat,
        scheduleId: Long,
    ): ScheduledStartAttempt = withContext(Dispatchers.Main) {
        val controller = RecordingController.getInstance(app)
        val before = controller.state.value
        if (!isControllerAcceptingScheduledStart(before)) {
            AppLogger.w(
                TAG,
                "START ignored: controller busy id=$scheduleId state=$before",
            )
            return@withContext ScheduledStartAttempt.BusySkipped
        }
        controller.start(format)
        val terminal = withTimeoutOrNull(SCHEDULED_START_CONFIRM_TIMEOUT_MS) {
            controller.state.first { state ->
                classifyPostStartState(state) != ScheduledStartObserveResult.Pending
            }
        }
        val observed = if (terminal == null) {
            ScheduledStartObserveResult.Pending
        } else {
            classifyPostStartState(terminal)
        }
        var accepted = isScheduledStartAcceptedAfterObserve(
            result = observed,
            timedOutWhilePending = terminal == null,
        )
        if (!accepted) {
            val after = controller.state.value
            if (shouldRecoverRejectedStartAsAccepted(after)) {
                AppLogger.w(
                    TAG,
                    "START reject false-negative recovered id=$scheduleId state=$after",
                )
                accepted = true
            } else {
                AppLogger.w(
                    TAG,
                    "START not confirmed id=$scheduleId observed=$observed state=$after",
                )
            }
        }
        if (accepted) {
            ScheduledStartAttempt.Accepted
        } else {
            ScheduledStartAttempt.NotConfirmed
        }
    }

    private suspend fun rescheduleAfterStart(
        app: Application,
        repository: RecordingScheduleRepository,
        schedule: RecordingSchedule,
        occurrenceStartEpochMillis: Long,
    ) {
        val mode = RecordingScheduleRepeatMode.fromStorageValue(schedule.repeatMode)
        when (mode) {
            RecordingScheduleRepeatMode.WEEKLY,
            RecordingScheduleRepeatMode.DAILY,
            -> {
                for (step in weeklyDailyRescheduleSteps()) {
                    when (step) {
                        ScheduledRescheduleStep.REGISTER_START -> {
                            val ok = RecordingScheduleAlarmScheduler.registerStart(app, schedule)
                            if (!ok) {
                                AppLogger.e(
                                    TAG,
                                    "registerStart failed id=${schedule.id} mode=$mode",
                                )
                                notifyAlarmRegisterFailed(app)
                            }
                        }
                        ScheduledRescheduleStep.REGISTER_STOP -> {
                            val ok = RecordingScheduleAlarmScheduler.registerStop(
                                app,
                                schedule,
                                occurrenceStartEpochMillis,
                            )
                            if (!ok) {
                                AppLogger.e(
                                    TAG,
                                    "registerStop failed id=${schedule.id} mode=$mode — stop fallback",
                                )
                                notifyAlarmRegisterFailed(app)
                                stopControllerWithDeliveryWait(app)
                            }
                        }
                    }
                }
            }
            RecordingScheduleRepeatMode.ONCE -> {
                val enabledSnapshot = schedule.copy(enabled = true)
                val stopOk = RecordingScheduleAlarmScheduler.registerStop(
                    app,
                    enabledSnapshot,
                    occurrenceStartEpochMillis,
                )
                if (shouldDisableOnceAfterStart(
                        startAccepted = true,
                        registerStopSucceeded = stopOk,
                    )
                ) {
                    repository.setEnabled(schedule.id, false)
                } else {
                    // Round 4: stop 폴백 + setEnabled(false) + 알림 (좀비/재무장 없음).
                    AppLogger.e(
                        TAG,
                        "ONCE registerStop failed — stop fallback + disable id=${schedule.id}",
                    )
                    notifyAlarmRegisterFailed(app)
                    stopControllerWithDeliveryWait(app)
                    if (shouldDisableOnceAfterStopFallback()) {
                        repository.setEnabled(schedule.id, false)
                    }
                }
            }
        }
    }

    private fun handleStop(context: Context, intent: Intent) {
        if (readRequiredLongExtras(intent) == null) return
        val app = context.requireApplication()
        val pendingResult = goAsync()
        val job = Job()
        CoroutineScope(job + Dispatchers.IO).launch {
            try {
                stopControllerWithDeliveryWait(app)
            } catch (e: Exception) {
                AppLogger.e(TAG, "STOP failed", e)
                notifyAlarmStopFailed(app)
            } finally {
                pendingResult.finish()
                job.cancel()
            }
        }
    }

    /**
     * Quick Timer START — [handleStart]와 분리. extras 없음.
     * Busy/fail → DataStore clear + cancel, STOP 미등록.
     * Accepted → 현재 elapsedRealtime + duration으로 registerStop, mark started.
     */
    private fun handleCountdownStart(context: Context) {
        val app = context.requireApplication()
        val pendingResult = goAsync()
        val job = Job()
        CoroutineScope(job + Dispatchers.IO).launch {
            try {
                handleCountdownStartAsync(app)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                AppLogger.e(TAG, "COUNTDOWN START failed", e)
                clearCountdownFailClosed(app)
            } finally {
                pendingResult.finish()
                job.cancel()
            }
        }
    }

    private suspend fun handleCountdownStartAsync(app: Application) {
        val settings = SettingsRepository(app)
        val pending = try {
            settings.pendingCountdown.first()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            AppLogger.e(TAG, "COUNTDOWN START pendingCountdown read failed", e)
            RecordingCountdownAlarmScheduler.cancel(app)
            return
        }
        if (pending == null || !isCountdownMinutesInRange(pending.durationMinutes)) {
            AppLogger.w(
                TAG,
                "COUNTDOWN START skip: missing or invalid pending " +
                    "present=${pending != null} duration=${pending?.durationMinutes}",
            )
            clearCountdownFailClosed(app, settings)
            return
        }

        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            AppLogger.w(TAG, "COUNTDOWN START denied: RECORD_AUDIO")
            notifyPermissionDenied(app)
            clearCountdownFailClosed(app, settings)
            return
        }

        val format = SettingsRepository.recordingFormatHot.value
            ?: SettingsRepository(app).recordingFormat.first()

        when (val attempt = awaitScheduledStartAttempt(app, format, scheduleId = -1L)) {
            ScheduledStartAttempt.BusySkipped,
            ScheduledStartAttempt.NotConfirmed,
            -> {
                if (shouldClearCountdownWithoutStop(attempt)) {
                    AppLogger.w(TAG, "COUNTDOWN START busy/fail — clear+cancel no STOP")
                    clearCountdownFailClosed(app, settings)
                }
            }
            ScheduledStartAttempt.Accepted -> {
                if (!shouldRegisterCountdownStop(attempt)) return
                val nowElapsed = SystemClock.elapsedRealtime()
                val stopOk = RecordingCountdownAlarmScheduler.registerStop(
                    app,
                    pending.durationMinutes,
                    nowElapsed,
                )
                if (!stopOk) {
                    AppLogger.e(TAG, "COUNTDOWN registerStop failed — stop fallback")
                    notifyAlarmRegisterFailed(app)
                    stopControllerWithDeliveryWait(app)
                    clearCountdownFailClosed(app, settings)
                    return
                }
                try {
                    settings.markPendingCountdownRecordingStarted(nowElapsed)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    AppLogger.e(TAG, "COUNTDOWN mark started failed", e)
                }
            }
        }
    }

    /**
     * Quick Timer STOP — [handleStop]와 분리. extras 없음.
     * stopController 후 DataStore clear + 알람 cancel.
     */
    private fun handleCountdownStop(context: Context) {
        val app = context.requireApplication()
        val pendingResult = goAsync()
        val job = Job()
        CoroutineScope(job + Dispatchers.IO).launch {
            try {
                stopControllerWithDeliveryWait(app)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                AppLogger.e(TAG, "COUNTDOWN STOP failed", e)
                notifyAlarmStopFailed(app)
            } finally {
                clearCountdownFailClosed(app)
                pendingResult.finish()
                job.cancel()
            }
        }
    }

    private suspend fun clearCountdownFailClosed(
        app: Application,
        settings: SettingsRepository = SettingsRepository(app),
    ) {
        try {
            settings.clearPendingCountdown()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            AppLogger.e(TAG, "COUNTDOWN clearPendingCountdown failed", e)
        }
        RecordingCountdownAlarmScheduler.cancel(app)
    }

    /**
     * Main에서 stop() → 전달 확인. 실패 시 1회 재시도, 그래도 실패면 알람 채널 알림.
     */
    private suspend fun stopControllerWithDeliveryWait(app: Application) {
        withContext(Dispatchers.Main) {
            val controller = RecordingController.getInstance(app)
            val before = controller.state.value
            val wasActive = before is RecordingState.Recording || before is RecordingState.Paused

            suspend fun attemptStop(): Boolean {
                controller.stop()
                withTimeoutOrNull(SCHEDULED_STOP_DELIVERY_TIMEOUT_MS) {
                    controller.state.first { state ->
                        isStopDeliveryConfirmed(state, wasActivelyRecording = wasActive) ||
                            state is RecordingState.Stopping ||
                            state is RecordingState.Saved ||
                            state is RecordingState.Failed ||
                            state is RecordingState.Review
                    }
                }
                return isStopDeliveryConfirmed(
                    stateAfterAttempt = controller.state.value,
                    wasActivelyRecording = wasActive,
                )
            }

            if (attemptStop()) return@withContext
            if (shouldRetryStopDelivery(
                    firstAttemptConfirmed = false,
                    wasActivelyRecording = wasActive,
                )
            ) {
                AppLogger.w(TAG, "STOP not confirmed — retry once")
                if (attemptStop()) return@withContext
                AppLogger.e(TAG, "STOP failed after retry — notify")
                notifyAlarmStopFailed(app)
            }
        }
    }

    private fun readRequiredLongExtras(intent: Intent): Pair<Long, Long>? {
        if (!hasRequiredScheduledRecordingExtras(
                hasScheduleId = intent.hasExtra(
                    RecordingScheduleAlarmScheduler.EXTRA_SCHEDULE_ID,
                ),
                hasOccurrenceStart = intent.hasExtra(
                    RecordingScheduleAlarmScheduler.EXTRA_OCCURRENCE_START_EPOCH_MILLIS,
                ),
            )
        ) {
            AppLogger.w(
                TAG,
                "missing required extras action=${intent.action} " +
                    "hasId=${intent.hasExtra(RecordingScheduleAlarmScheduler.EXTRA_SCHEDULE_ID)} " +
                    "hasOccurrence=${intent.hasExtra(
                        RecordingScheduleAlarmScheduler.EXTRA_OCCURRENCE_START_EPOCH_MILLIS,
                    )}",
            )
            return null
        }
        val extras = intent.extras
        if (extras == null) {
            AppLogger.w(TAG, "extras bundle null action=${intent.action}")
            return null
        }
        val scheduleId = extras.getLong(RecordingScheduleAlarmScheduler.EXTRA_SCHEDULE_ID)
        val occurrenceStart = extras.getLong(
            RecordingScheduleAlarmScheduler.EXTRA_OCCURRENCE_START_EPOCH_MILLIS,
        )
        return scheduleId to occurrenceStart
    }

    private fun notifyPermissionDenied(context: Context) {
        ensureChannel(
            context,
            PERMISSION_CHANNEL_ID,
            R.string.scheduled_recording_permission_channel,
        )
        val notification = NotificationCompat.Builder(context, PERMISSION_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.scheduled_recording_permission_title))
            .setContentText(context.getString(R.string.recording_permission_denied))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(appLaunchPendingIntent(context, PERMISSION_NOTIFICATION_ID))
            .setAutoCancel(true)
            .build()
        notifyIfAllowed(context, PERMISSION_NOTIFICATION_ID, notification)
    }

    private fun notifyAlarmRegisterFailed(context: Context) {
        notifyScheduledRecordingAlarmRegisterFailed(context)
    }

    private fun notifyAlarmStopFailed(context: Context) {
        ensureChannel(
            context,
            ALARM_CHANNEL_ID,
            R.string.scheduled_recording_alarm_channel,
        )
        val notification = NotificationCompat.Builder(context, ALARM_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.scheduled_recording_alarm_title))
            .setContentText(context.getString(R.string.scheduled_recording_alarm_stop_failed))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(appLaunchPendingIntent(context, ALARM_STOP_FAILED_NOTIFICATION_ID))
            .setAutoCancel(true)
            .build()
        notifyIfAllowed(context, ALARM_STOP_FAILED_NOTIFICATION_ID, notification)
    }

    private fun ensureChannel(context: Context, channelId: String, nameRes: Int) {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(
                channelId,
                NotificationManagerCompat.IMPORTANCE_DEFAULT,
            )
                .setName(context.getString(nameRes))
                .build(),
        )
    }

    private fun appLaunchPendingIntent(context: Context, requestCode: Int): PendingIntent {
        val launch = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notifyIfAllowed(
        context: Context,
        notificationId: Int,
        notification: android.app.Notification,
    ) {
        val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        if (canNotify) {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } else {
            AppLogger.w(TAG, "notify skipped id=$notificationId: POST_NOTIFICATIONS not granted")
        }
    }

    companion object {
        const val PERMISSION_CHANNEL_ID = "scheduled_recording_permission"
        const val ALARM_CHANNEL_ID = "scheduled_recording_alarm"
        const val PERMISSION_NOTIFICATION_ID = 4001
        const val ALARM_REGISTER_FAILED_NOTIFICATION_ID = 4002
        const val ALARM_STOP_FAILED_NOTIFICATION_ID = 4003
    }
}

/** D-5/D-6 공유: START 알람 register 실패 알림. Boot는 fail≥1일 때 1회 집계 호출. */
internal fun notifyScheduledRecordingAlarmRegisterFailed(context: Context) {
    ensureScheduledRecordingNotificationChannel(
        context,
        ScheduledRecordingReceiver.ALARM_CHANNEL_ID,
        R.string.scheduled_recording_alarm_channel,
    )
    val notification = NotificationCompat.Builder(
        context,
        ScheduledRecordingReceiver.ALARM_CHANNEL_ID,
    )
        .setContentTitle(context.getString(R.string.scheduled_recording_alarm_title))
        .setContentText(context.getString(R.string.scheduled_recording_alarm_register_failed))
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentIntent(
            scheduledRecordingAppLaunchPendingIntent(
                context,
                ScheduledRecordingReceiver.ALARM_REGISTER_FAILED_NOTIFICATION_ID,
            ),
        )
        .setAutoCancel(true)
        .build()
    notifyScheduledRecordingIfAllowed(
        context,
        ScheduledRecordingReceiver.ALARM_REGISTER_FAILED_NOTIFICATION_ID,
        notification,
    )
}

internal fun ensureScheduledRecordingNotificationChannel(
    context: Context,
    channelId: String,
    nameRes: Int,
) {
    NotificationManagerCompat.from(context).createNotificationChannel(
        NotificationChannelCompat.Builder(
            channelId,
            NotificationManagerCompat.IMPORTANCE_DEFAULT,
        )
            .setName(context.getString(nameRes))
            .build(),
    )
}

internal fun scheduledRecordingAppLaunchPendingIntent(
    context: Context,
    requestCode: Int,
): PendingIntent {
    val launch = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    return PendingIntent.getActivity(
        context,
        requestCode,
        launch,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

internal fun notifyScheduledRecordingIfAllowed(
    context: Context,
    notificationId: Int,
    notification: android.app.Notification,
) {
    val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    if (canNotify) {
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    } else {
        AppLogger.w(
            "ScheduledRecordingReceiver",
            "notify skipped id=$notificationId: POST_NOTIFICATIONS not granted",
        )
    }
}

/**
 * start 확인 대기. Controller BIND_TIMEOUT_MS(3_000)보다 길어야 bind→FGS 레이스를 덮는다.
 * Controller 상수는 수정하지 않는다.
 */
internal const val SCHEDULED_START_CONFIRM_TIMEOUT_MS = 5_500L

/** Controller BIND_TIMEOUT 하한 — 회귀 테스트용 (Receiver timeout이 이보다 커야 함). */
internal const val CONTROLLER_BIND_TIMEOUT_FLOOR_MS = 3_000L

/** stop() 전달/상태 전이 대기. */
internal const val SCHEDULED_STOP_DELIVERY_TIMEOUT_MS = 2_500L

internal fun hasRequiredScheduledRecordingExtras(
    hasScheduleId: Boolean,
    hasOccurrenceStart: Boolean,
): Boolean = hasScheduleId && hasOccurrenceStart

internal fun isControllerAcceptingScheduledStart(state: RecordingState): Boolean =
    when (state) {
        is RecordingState.Recording,
        is RecordingState.Paused,
        is RecordingState.Stopping,
        is RecordingState.Review,
        -> false
        else -> true
    }

internal fun shouldProceedWithReschedule(startAccepted: Boolean): Boolean = startAccepted

/** start 시도 결과 — busy는 타 세션 보호용으로 분리. */
internal enum class ScheduledStartAttempt {
    /** 이미 Recording/Paused/Stopping — start 미호출. */
    BusySkipped,
    /** start 호출했으나 confirm 실패(timeout/Failed). */
    NotConfirmed,
    /** confirm 또는 거짓 음성 복구 성공. */
    Accepted,
}

/**
 * reject 직후 state 재확인 — Recording/Paused면 거짓 음성.
 * true면 accepted로 복구해 registerStop 경로로 진행한다.
 */
internal fun shouldRecoverRejectedStartAsAccepted(stateAfterReject: RecordingState): Boolean =
    stateAfterReject is RecordingState.Recording ||
        stateAfterReject is RecordingState.Paused

internal enum class ScheduledStartObserveResult {
    Confirmed,
    Failed,
    Pending,
}

internal fun classifyPostStartState(state: RecordingState): ScheduledStartObserveResult =
    when (state) {
        is RecordingState.Recording,
        is RecordingState.Paused,
        -> ScheduledStartObserveResult.Confirmed
        is RecordingState.Failed -> ScheduledStartObserveResult.Failed
        else -> ScheduledStartObserveResult.Pending
    }

internal fun isScheduledStartAcceptedAfterObserve(
    result: ScheduledStartObserveResult,
    timedOutWhilePending: Boolean,
): Boolean = when {
    result == ScheduledStartObserveResult.Confirmed -> true
    result == ScheduledStartObserveResult.Failed -> false
    timedOutWhilePending -> false
    else -> false
}

/** registerStop 성공 시 ONCE disable. */
internal fun shouldDisableOnceAfterStart(
    startAccepted: Boolean,
    registerStopSucceeded: Boolean,
): Boolean = startAccepted && registerStopSucceeded

/**
 * ONCE registerStop 실패 정책(Round 4): stop 폴백 후 setEnabled(false) + 알림.
 * registerStart 재무장하지 않음 (스케줄 off로 좀비 방지).
 */
internal fun shouldDisableOnceAfterStopFallback(): Boolean = true

internal fun isStopDeliveryConfirmed(
    stateAfterAttempt: RecordingState,
    wasActivelyRecording: Boolean,
): Boolean = when (stateAfterAttempt) {
    is RecordingState.Stopping,
    is RecordingState.Saved,
    is RecordingState.Failed,
    is RecordingState.Review,
    -> true
    is RecordingState.Recording,
    is RecordingState.Paused,
    -> false
    is RecordingState.Idle -> !wasActivelyRecording
    else -> !wasActivelyRecording
}

internal fun shouldRetryStopDelivery(
    firstAttemptConfirmed: Boolean,
    wasActivelyRecording: Boolean,
): Boolean = wasActivelyRecording && !firstAttemptConfirmed

internal enum class ScheduledRescheduleStep {
    REGISTER_START,
    REGISTER_STOP,
}

internal fun weeklyDailyRescheduleSteps(): List<ScheduledRescheduleStep> =
    listOf(
        ScheduledRescheduleStep.REGISTER_START,
        ScheduledRescheduleStep.REGISTER_STOP,
    )

/** Busy/NotConfirmed → DataStore clear + cancel, STOP 미등록. */
internal fun shouldClearCountdownWithoutStop(attempt: ScheduledStartAttempt): Boolean =
    attempt == ScheduledStartAttempt.BusySkipped ||
        attempt == ScheduledStartAttempt.NotConfirmed

/** Accepted 만 registerStop + mark started. */
internal fun shouldRegisterCountdownStop(attempt: ScheduledStartAttempt): Boolean =
    attempt == ScheduledStartAttempt.Accepted
