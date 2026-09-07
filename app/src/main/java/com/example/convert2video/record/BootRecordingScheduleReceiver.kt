package com.example.convert2video.record

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.convert2video.data.AppDatabase
import com.example.convert2video.data.RecordingSchedule
import com.example.convert2video.data.RecordingScheduleRepository
import com.example.convert2video.utils.AppLogger
import com.example.convert2video.utils.requireApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val TAG = "BootRecordingScheduleReceiver"

/**
 * D-6: 재부팅 후 enabled 예약 스케줄의 START 알람만 재등록한다.
 *
 * ## mid-window STOP gap (의도적 non-action)
 * 부팅 시각이 예약 구간 [start, end) 안이어도 [RecordingController.start]를 호출하지 않는다.
 * [RecordingScheduleAlarmScheduler.registerStop]도 부팅 시 호출하지 않는다 — STOP 알람은 D-5
 * [ScheduledRecordingReceiver]가 START confirm 성공 후 registerStop으로 등록한다. 부팅만으로는
 * occurrence context(occurrenceStartEpochMillis)가 없어 STOP 재등록·즉시 stop이 불가하다.
 * 구간 중 재부팅 시 해당 occurrence 녹음은 건너뛰며, 다음 occurrence START 알람만 재등록한다.
 *
 * ## FBE / DB 잠금 gap
 * [Intent.ACTION_BOOT_COMPLETED] 시점에 CE(Credential Encrypted) storage가 잠긴 상태(FBE)일 수
 * 있다. Room open·[RecordingScheduleRepository.getAllEnabled] 실패는 예상 가능하며 AppLogger.w로
 * 기록한다. **자동 bulk 재등록 경로는 없다** — BOOT 시 DB가 열리지 않으면 이번 부팅의 재등록은
 * 건너뛴다. 사용자가 스케줄 토글·수정(CRUD) 시에만 [RecordingScheduleAlarmScheduler.registerStart]
 * 재시도가 일어난다. USER_UNLOCKED receiver 추가는 범위 확대이므로 본 gap은 KDoc으로만 명시한다.
 *
 * ## Coroutine scope (D-5 동일 패턴)
 * [ScheduledRecordingReceiver]와 동일: goAsync + Job + Dispatchers.IO. Application scope
 * ([Convert2videoApplication] appScope)는 persist sink 전용이며 Receiver 수명과 무관하므로
 * 재사용하지 않는다. pendingResult.finish()와 함께 job.cancel()로 정리한다.
 *
 * registerStart 개별 실패: AppLogger.w + 루프 계속. 1건 이상 실패 시 D-5와 동일 알림 1회.
 */
class BootRecordingScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) {
            AppLogger.w(TAG, "onReceive: null intent")
            return
        }
        if (!isBootCompletedIntentAction(intent.action)) {
            AppLogger.w(TAG, "onReceive: unexpected action=${intent.action}")
            return
        }
        handleBootCompleted(context)
    }

    private fun handleBootCompleted(context: Context) {
        val app = context.requireApplication()
        val pendingResult = goAsync()
        val job = Job()
        CoroutineScope(job + Dispatchers.IO).launch {
            try {
                performBootReregister(app)
            } finally {
                pendingResult.finish()
                job.cancel()
            }
        }
    }

    private suspend fun performBootReregister(
        app: Application,
        onRegisterFailed: () -> Unit = { notifyScheduledRecordingAlarmRegisterFailed(app) },
    ) {
        try {
            val repository = RecordingScheduleRepository(
                AppDatabase.getInstance(app).recordingScheduleDao(),
            )
            bootReregisterWithNotification(
                repository = repository,
                registerStart = { schedule ->
                    RecordingScheduleAlarmScheduler.registerStart(app, schedule)
                },
                onRegisterFailed = onRegisterFailed,
            )
        } catch (e: Exception) {
            AppLogger.w(
                TAG,
                "BOOT re-register aborted: DB unavailable (FBE/locked CE storage); " +
                    "no automatic bulk re-register — user schedule CRUD retries registerStart",
                e,
            )
        }
    }
}

/** BOOT_COMPLETED intent action 여부 — JVM 단위 테스트용. */
internal fun isBootCompletedIntentAction(action: String?): Boolean =
    action == Intent.ACTION_BOOT_COMPLETED

/** D-6 부팅 재등록 결과 (순수 data). */
internal data class BootReregisterResult(
    val total: Int,
    val ok: Int,
    val fail: Int,
)

/**
 * enabled 스케줄 START 재등록 + 실패 집계 알림. DB open/getAllEnabled는 caller 책임.
 * 알림([onRegisterFailed]) 실패는 DB/FBE와 분리해 AppLogger.e로 기록한다.
 */
internal suspend fun bootReregisterWithNotification(
    repository: RecordingScheduleRepository,
    registerStart: suspend (RecordingSchedule) -> Boolean,
    onRegisterFailed: () -> Unit,
): BootReregisterResult {
    val result = reregisterEnabledStartAlarms(repository, registerStart)
    AppLogger.i(
        TAG,
        "BOOT re-register done total=${result.total} ok=${result.ok} fail=${result.fail}",
    )
    if (result.fail > 0) {
        try {
            onRegisterFailed()
        } catch (e: Exception) {
            AppLogger.e(TAG, "BOOT alarm register-failed notification failed", e)
        }
    }
    return result
}

/**
 * enabled 스케줄마다 [registerStart] 호출. 개별 실패는 격리하고 다음 건 계속.
 * seam 범위: [registerStart] 주입만 — registerStop/RecordingController는 Contract로 보장.
 */
internal suspend fun reregisterEnabledStartAlarms(
    repository: RecordingScheduleRepository,
    registerStart: suspend (RecordingSchedule) -> Boolean,
): BootReregisterResult {
    val schedules = repository.getAllEnabled()
    var ok = 0
    var fail = 0
    for (schedule in schedules) {
        try {
            if (registerStart(schedule)) {
                ok++
            } else {
                fail++
                AppLogger.w(TAG, "registerStart returned false id=${schedule.id}")
            }
        } catch (e: Exception) {
            fail++
            AppLogger.e(TAG, "registerStart threw id=${schedule.id}", e)
        }
    }
    return BootReregisterResult(total = schedules.size, ok = ok, fail = fail)
}
