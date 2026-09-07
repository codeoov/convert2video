package com.example.convert2video.record

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.example.convert2video.utils.AppLogger

private const val TAG = "ExactAlarmPermission"

/**
 * Exact alarm (SCHEDULE_EXACT_ALARM) 권한 헬퍼 — API 31+ 만 시스템 게이트.
 * AlarmManager 스케줄링·Receiver 등록은 이 객체의 책임이 아니다 (D-3 OUT).
 *
 * ## AlarmManager null SSOT
 * - **UI** (MainActivity 유도·Options 배너): [canScheduleExactAlarms]가 AlarmManager null이면
 *   **true** — 배너 오탐·유도 루프 방지.
 * - **Scheduler** ([RecordingScheduleAlarmScheduler] / [AndroidRecordingAlarmBackend]):
 *   AlarmManager null이면 등록 불가(**false** 경로) — setExact가 실패하고 true를 반환하지 않음.
 *   권한 false(철회) 시에는 stale START/STOP을 cancel한다 (UI null→true와 별개 정책).
 */
object ExactAlarmPermission {

    /**
     * SDK &lt; S → 항상 true.
     * S+ → [AlarmManager.canScheduleExactAlarms].
     * AlarmManager null → 로그 후 보수적 **true** (UI 배너 오탐·유도 루프 방지).
     * Scheduler는 null을 별도 false로 취급한다 — 위 KDoc SSOT 참고.
     */
    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        if (alarmManager == null) {
            AppLogger.w(TAG, "AlarmManager null; treating as canScheduleExactAlarms=true")
            return true
        }
        return alarmManager.canScheduleExactAlarms()
    }

    /**
     * 시스템 exact-alarm 설정 화면 Intent.
     * SDK &lt; S → null (no-op). S+ → ACTION_REQUEST_SCHEDULE_EXACT_ALARM + package URI.
     */
    fun buildRequestIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    /** [buildRequestIntent] 결과가 시스템에 resolve 되는지 사전 검사. */
    fun isRequestIntentResolvable(context: Context, intent: Intent): Boolean {
        val packageManager = context.packageManager
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.resolveActivity(
                intent,
                PackageManager.ResolveInfoFlags.of(
                    PackageManager.MATCH_DEFAULT_ONLY.toLong(),
                ),
            )
        } else {
            // TODO(2026-08-05): API 32 이하는 ResolveInfoFlags 미제공 — int flags 오버로드 유지
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        return resolved != null
    }
}
