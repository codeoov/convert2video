package com.example.convert2video.record

/**
 * 녹음 마이크 입력 소스. 단일 정의 SSOT — SettingsRepository는 import만 한다.
 *
 * - [Default]: 내장/시스템 기본 마이크.
 * - [Bluetooth]: 연결된 블루투스 SCO 마이크(없으면 Default로 조용히 폴백).
 * - [storageValue]: DataStore 저장용 소문자 키.
 */
enum class MicrophoneSource {
    Default,
    Bluetooth,
    ;

    /** DataStore Preferences 저장값 (`default` | `bluetooth`). */
    val storageValue: String
        get() = when (this) {
            Default -> "default"
            Bluetooth -> "bluetooth"
        }

    companion object {
        /** DataStore 복원. `null` / 알 수 없는 값 → [Default]. */
        fun fromStorageValue(value: String?): MicrophoneSource = when (value) {
            "bluetooth" -> Bluetooth
            else -> Default
        }
    }
}
