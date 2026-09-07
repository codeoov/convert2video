package com.example.convert2video.record

import com.example.convert2video.utils.AppLogger

private const val TAG = "RecordingFormat"

/**
 * 녹음 출력 포맷. Sprint1 단일 정의 — Engine/Repo/Service는 import만 한다.
 *
 * - [fileExtension] / [name]: Engine·Service·Room (`EXTRA_FORMAT` = [name])
 * - [storageValue]: DataStore 저장용 소문자 키 (`aac`/`wav`) — [name]과 별개
 */
enum class RecordingFormat(val fileExtension: String) {
    AAC("m4a"),
    WAV("wav"),
    ;

    /** DataStore Preferences 저장값 (`aac` | `wav`). */
    val storageValue: String
        get() = when (this) {
            AAC -> "aac"
            WAV -> "wav"
        }

    companion object {
        /**
         * DataStore 복원.
         * - `null` / `"aac"` → [AAC]
         * - `"wav"` → [WAV]
         * - 그 외(대문자 `AAC`·garbage 등) → [AppLogger.w] 후 [AAC]
         */
        fun fromStorageValue(value: String?): RecordingFormat = when (value) {
            null, "aac" -> AAC
            "wav" -> WAV
            else -> {
                AppLogger.w(
                    TAG,
                    "Unknown recording_format storage value='$value'; defaulting to AAC",
                )
                AAC
            }
        }
    }
}
