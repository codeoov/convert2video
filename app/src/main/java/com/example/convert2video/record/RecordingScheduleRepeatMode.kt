package com.example.convert2video.record

import com.example.convert2video.utils.AppLogger

private const val TAG = "RecordingScheduleRepeatMode"

/**
 * 예약 녹음 반복 모드.
 * Room [com.example.convert2video.data.RecordingSchedule.repeatMode]에는 [name] 문자열을 저장한다
 * (`ONCE` | `WEEKLY` | `DAILY`).
 *
 * - [storageValue] / [name]: Room 저장값과 동일
 * - 쓰기: [requireFromStorageValue] — 허용값만 통과, unknown은 [IllegalArgumentException]
 * - 읽기/복구: [fromStorageValue] — unknown은 [AppLogger.w] 후 [ONCE]
 */
enum class RecordingScheduleRepeatMode {
    ONCE,
    WEEKLY,
    DAILY,
    ;

    /** Room 저장값 — [name]과 동일 (`ONCE` | `WEEKLY` | `DAILY`). */
    val storageValue: String
        get() = name

    companion object {
        /**
         * 쓰기 경로 전용. `"ONCE"` | `"WEEKLY"` | `"DAILY"`만 허용.
         * @throws IllegalArgumentException unknown / blank / null이 아닌 비허용 값
         */
        fun requireFromStorageValue(value: String): RecordingScheduleRepeatMode = when (value) {
            "ONCE" -> ONCE
            "WEEKLY" -> WEEKLY
            "DAILY" -> DAILY
            else -> throw IllegalArgumentException(
                "Unknown repeatMode='$value'; allowed: ONCE|WEEKLY|DAILY",
            )
        }

        /**
         * Room/저장값 **읽기·복구**.
         * - `null` / `"ONCE"` → [ONCE]
         * - `"WEEKLY"` → [WEEKLY]
         * - `"DAILY"` → [DAILY]
         * - 그 외 → [AppLogger.w] 후 [ONCE]
         *
         * 쓰기 경로에서는 사용하지 말 것 — [requireFromStorageValue]를 쓴다.
         */
        fun fromStorageValue(value: String?): RecordingScheduleRepeatMode = when (value) {
            null, "ONCE" -> ONCE
            "WEEKLY" -> WEEKLY
            "DAILY" -> DAILY
            else -> {
                AppLogger.w(
                    TAG,
                    "Unknown repeatMode storage value='$value'; defaulting to ONCE",
                )
                ONCE
            }
        }
    }
}
