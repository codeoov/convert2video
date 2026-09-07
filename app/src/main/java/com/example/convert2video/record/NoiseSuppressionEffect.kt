package com.example.convert2video.record

import android.media.audiofx.NoiseSuppressor
import com.example.convert2video.utils.AppLogger

private const val TAG = "NoiseSuppressionEffect"

/**
 * [NoiseSuppressor] 생성·부착 헬퍼.
 *
 * - [NoiseReductionMode.DeviceDefault]: NoiseSuppressor 비부착 → null 반환.
 * - [NoiseReductionMode.On]: NoiseSuppressor 부착 + enabled = true.
 * - [NoiseReductionMode.Off]: NoiseSuppressor 부착 + enabled = false.
 * - [NoiseSuppressor.isAvailable] false이면 모드에 관계없이 null 반환.
 */
internal object NoiseSuppressionEffect {
    fun attach(sessionId: Int, mode: NoiseReductionMode): NoiseSuppressor? {
        if (mode == NoiseReductionMode.DeviceDefault || !NoiseSuppressor.isAvailable()) return null
        return runCatching {
            NoiseSuppressor.create(sessionId)?.also { it.enabled = (mode == NoiseReductionMode.On) }
        }.getOrElse { e ->
            AppLogger.w(TAG, "NoiseSuppressor.create failed: ${e.message}", e)
            null
        }
    }
}
