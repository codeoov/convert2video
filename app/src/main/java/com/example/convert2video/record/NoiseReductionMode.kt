package com.example.convert2video.record

/**
 * 잡음 감소 모드. 단일 정의 SSOT — SettingsRepository는 import만 한다.
 *
 * - [DeviceDefault]: NoiseSuppressor 비부착 (기기 기본 동작 그대로).
 * - [On]: NoiseSuppressor 부착 + enabled = true.
 * - [Off]: NoiseSuppressor 부착 + enabled = false (강제 비활성).
 * - [storageValue]: DataStore 저장용 소문자 키.
 */
enum class NoiseReductionMode {
    DeviceDefault,
    On,
    Off,
    ;

    /** DataStore Preferences 저장값 (`device_default` | `on` | `off`). */
    val storageValue: String
        get() = when (this) {
            DeviceDefault -> "device_default"
            On -> "on"
            Off -> "off"
        }

    companion object {
        /**
         * DataStore 복원.
         * - `null` / 알 수 없는 값 → [DeviceDefault]
         * - `"on"` → [On]
         * - `"off"` → [Off]
         */
        fun fromStorageValue(value: String?): NoiseReductionMode = when (value) {
            "on" -> On
            "off" -> Off
            else -> DeviceDefault
        }
    }
}
