package com.example.convert2video.record

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.example.convert2video.utils.AppLogger

private const val TAG = "MicrophoneSourceRouting"

/**
 * 블루투스 SCO 마이크 탐색·오디오 라우팅 헬퍼.
 *
 * - 기기 이름([AudioDeviceInfo.getProductName])은 절대 읽지 않는다.
 * - API 31+: [AudioManager.setCommunicationDevice] / [AudioManager.clearCommunicationDevice]
 * - API 26–30: deprecated SCO API ([AudioManager.startBluetoothSco] 등)
 */
internal object MicrophoneSourceRouting {
    fun findConnectedBluetoothMic(context: Context): AudioDeviceInfo? =
        context.getSystemService(AudioManager::class.java)
            ?.getDevices(AudioManager.GET_DEVICES_INPUTS)
            ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }

    fun isBluetoothMicConnected(context: Context): Boolean =
        findConnectedBluetoothMic(context) != null

    fun beginRouting(context: Context): Boolean {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return false
        val device = findConnectedBluetoothMic(context) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.setCommunicationDevice(device)
        } else {
            // TODO(2026-08-18): remove when minSdk >= 31 — use setCommunicationDevice only
            @Suppress("DEPRECATION")
            runCatching {
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
                true
            }.getOrElse { e ->
                AppLogger.w(TAG, "beginRouting SCO failed: ${e.message}", e)
                false
            }
        }
    }

    fun endRouting(context: Context) {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            // TODO(2026-08-18): remove when minSdk >= 31 — use clearCommunicationDevice only
            @Suppress("DEPRECATION")
            runCatching {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            }.onFailure { e ->
                AppLogger.w(TAG, "endRouting SCO failed: ${e.message}", e)
            }
        }
    }
}
