package com.example.convert2video.record

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.example.convert2video.utils.AppLogger
import java.io.File

/**
 * AAC(.m4a) 캡처 백엔드 — [MediaRecorder] 래퍼.
 * minSdk 26이므로 pause/resume 버전 분기 없음.
 * API 31+는 [MediaRecorder] Context 생성자, 미만은 deprecated no-arg.
 *
 * [MediaRecorder]는 `audioSessionId`를 노출하지 않아 [android.media.audiofx.NoiseSuppressor]를
 * 붙일 수 없음 — 잡음 감소는 WAV([AudioRecordWavBackend])에서만 지원.
 */
internal class MediaRecorderAacBackend(
    context: Context,
    private val microphoneSource: MicrophoneSource = MicrophoneSource.Default,
) : AudioCaptureBackend {

    private val appContext = context.applicationContext
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var isRoutedToBluetooth: Boolean = false

    /**
     * [MediaRecorder.getMaxAmplitude]는 호출 시마다 피크를 리셋한다.
     * Engine 틱(~100ms) 사이 레이스/0 스파이크를 줄이려고 마지막 유효값을 volatile 캐시한다.
     */
    @Volatile
    private var lastAmplitude: Int = 0

    override fun start(outputFile: File, onCaptureFailed: (Exception) -> Unit) {
        this.outputFile = outputFile
        lastAmplitude = 0
        routeToBluetoothIfNeeded()
        val mediaRecorder = createMediaRecorder()
        try {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRecorder.setAudioEncodingBitRate(AAC_BITRATE_BPS)
            mediaRecorder.setAudioSamplingRate(AAC_SAMPLE_RATE_HZ)
            mediaRecorder.setAudioChannels(AAC_CHANNEL_COUNT)
            mediaRecorder.setOutputFile(outputFile.absolutePath)
            mediaRecorder.prepare()
            mediaRecorder.start()
            recorder = mediaRecorder
        } catch (e: Exception) {
            runCatching { mediaRecorder.release() }
            endBluetoothRoutingIfNeeded()
            recorder = null
            throw e
        }
    }

    override fun pause() {
        recorder?.pause() ?: error("MediaRecorder not started")
    }

    override fun resume() {
        recorder?.resume() ?: error("MediaRecorder not started")
    }

    override fun stop() {
        val mediaRecorder = recorder ?: error("MediaRecorder not started")
        try {
            mediaRecorder.stop()
        } catch (e: RuntimeException) {
            AppLogger.w(TAG, "MediaRecorder.stop failed: ${e.message}", e)
            outputFile?.delete()
            throw e
        } finally {
            runCatching { mediaRecorder.release() }
            recorder = null
            endBluetoothRoutingIfNeeded()
        }
    }

    override fun release() {
        val mediaRecorder = recorder
        recorder = null
        if (mediaRecorder != null) {
            runCatching { mediaRecorder.release() }
                .onFailure { e -> AppLogger.w(TAG, "MediaRecorder.release: ${e.message}", e) }
        }
        endBluetoothRoutingIfNeeded()
        // Incomplete/orphan output — same policy as WAV backend.
        outputFile?.delete()
        outputFile = null
    }

    override fun amplitude(): Int {
        return try {
            val mediaRecorder = recorder
            if (mediaRecorder == null) {
                lastAmplitude = 0
                return 0
            }
            // MediaRecorder maxAmplitude: peak since last call (resets on read).
            // Soft sounds are often tiny — mild gain. Zero reads decay (not hard 0) to
            // cut flicker while avoiding a sticky high peak.
            val peak = mediaRecorder.maxAmplitude
            lastAmplitude = if (peak > 0) {
                (peak * AMPLITUDE_GAIN).coerceAtMost(AMPLITUDE_MAX)
            } else {
                (lastAmplitude * AMPLITUDE_DECAY_NUM) / AMPLITUDE_DECAY_DEN
            }
            lastAmplitude
        } catch (e: Exception) {
            AppLogger.w(TAG, "maxAmplitude: ${e.message}", e)
            lastAmplitude
        }
    }

    private fun createMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(appContext)
        } else {
            // TODO(2026-08-03): MediaRecorder(Context) requires API 31 — remove when minSdk >= 31
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    private fun routeToBluetoothIfNeeded() {
        isRoutedToBluetooth = false
        if (microphoneSource != MicrophoneSource.Bluetooth) return
        if (MicrophoneSourceRouting.findConnectedBluetoothMic(appContext) != null) {
            isRoutedToBluetooth = MicrophoneSourceRouting.beginRouting(appContext)
        }
    }

    private fun endBluetoothRoutingIfNeeded() {
        if (!isRoutedToBluetooth) return
        MicrophoneSourceRouting.endRouting(appContext)
        isRoutedToBluetooth = false
    }

    companion object {
        private const val TAG = "MediaRecorderAac"

        /** AAC encoding bitrate (bps). Single source for capture + [RecordingStorageEstimator]. */
        internal const val AAC_BITRATE_BPS = 128_000

        /**
         * AAC sample rate (Hz). Same value as [AudioRecordWavBackend.SAMPLE_RATE]
         * (enforced in companion init — no separate RecordingAudioParams file).
         * Captured via [MediaRecorder.setAudioSamplingRate]; Estimator uses CBR
         * [AAC_BITRATE_BPS] (not sample-rate × channels) for B/s.
         * CBR byte estimates omit M4A/container and mux overhead.
         */
        internal const val AAC_SAMPLE_RATE_HZ = 44_100

        /** AAC channel count (mono). Captured via [MediaRecorder.setAudioChannels]. */
        internal const val AAC_CHANNEL_COUNT = 1

        private const val AMPLITUDE_MAX = 32_767
        private const val AMPLITUDE_GAIN = 3
        private const val AMPLITUDE_DECAY_NUM = 4
        private const val AMPLITUDE_DECAY_DEN = 5

        init {
            check(AAC_SAMPLE_RATE_HZ == AudioRecordWavBackend.SAMPLE_RATE) {
                "AAC_SAMPLE_RATE_HZ ($AAC_SAMPLE_RATE_HZ) must equal " +
                    "AudioRecordWavBackend.SAMPLE_RATE (${AudioRecordWavBackend.SAMPLE_RATE})"
            }
        }
    }
}
