package com.example.convert2video.record

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import com.example.convert2video.utils.AppLogger
import java.io.File
import java.io.RandomAccessFile

/**
 * WAV(PCM 16-bit mono) 캡처 백엔드 — [AudioRecord] + 수기 RIFF 헤더.
 *
 * pause는 [AudioRecord.stop]으로 하드웨어 캡처를 멈추고, resume은 [AudioRecord.startRecording].
 * pause 중 negative read는 Failed로 올리지 않는다(RECORDSTATE_STOPPED 예상 동작).
 * 실제 캡처 실패는 [onCaptureFailed]로 즉시 Engine에 전달한다.
 */
internal class AudioRecordWavBackend(
    private val noiseReductionMode: NoiseReductionMode = NoiseReductionMode.DeviceDefault,
    private val microphoneSource: MicrophoneSource = MicrophoneSource.Default,
    private val context: Context? = null,
) : AudioCaptureBackend {

    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var raf: RandomAccessFile? = null
    private var captureThread: Thread? = null
    private var onCaptureFailed: ((Exception) -> Unit)? = null

    private var isRoutedToBluetooth: Boolean = false
    private var negotiatedSampleRate: Int = SAMPLE_RATE

    @Volatile
    private var isCapturing: Boolean = false

    @Volatile
    private var isPaused: Boolean = false

    @Volatile
    private var totalPcmBytes: Long = 0L

    /** 최근 PCM 피크(0..32767). pause 중에는 0. */
    @Volatile
    private var lastAmplitude: Int = 0

    /** pause/resume/stop에서 throw — 비동기 콜백과 중복 전이 방지용. */
    @Volatile
    private var captureFailure: Exception? = null

    private var outputFile: File? = null

    override fun start(outputFile: File, onCaptureFailed: (Exception) -> Unit) {
        this.outputFile = outputFile
        this.onCaptureFailed = onCaptureFailed
        captureFailure = null
        isRoutedToBluetooth = false
        negotiatedSampleRate = SAMPLE_RATE

        val ctx = context
        if (microphoneSource == MicrophoneSource.Bluetooth && ctx != null) {
            if (MicrophoneSourceRouting.findConnectedBluetoothMic(ctx) != null) {
                isRoutedToBluetooth = MicrophoneSourceRouting.beginRouting(ctx)
            }
        }

        val sampleRates = if (microphoneSource == MicrophoneSource.Bluetooth) {
            BLUETOOTH_SAMPLE_RATE_CANDIDATES
        } else {
            listOf(SAMPLE_RATE)
        }
        val (record, sampleRate, bufferSize) = initializeAudioRecord(sampleRates)
        negotiatedSampleRate = sampleRate
        val file = RandomAccessFile(outputFile, "rw")
        file.setLength(0)
        file.write(buildWavHeader(0L, negotiatedSampleRate, CHANNEL_COUNT, BITS_PER_SAMPLE))
        totalPcmBytes = 0L
        isPaused = false
        isCapturing = true
        audioRecord = record
        raf = file
        noiseSuppressor = NoiseSuppressionEffect.attach(record.audioSessionId, noiseReductionMode)
        record.startRecording()
        val thread = Thread(
            {
                val buf = ByteArray(bufferSize)
                while (isCapturing) {
                    if (isPaused) {
                        try {
                            Thread.sleep(20L)
                        } catch (_: InterruptedException) {
                            break
                        }
                        continue
                    }
                    val read = record.read(buf, 0, buf.size)
                    if (read > 0) {
                        try {
                            file.write(buf, 0, read)
                            totalPcmBytes += read.toLong()
                            lastAmplitude = peakAmplitudePcm16le(buf, read)
                        } catch (e: Exception) {
                            AppLogger.e(TAG, "WAV write failed: ${e.message}", e)
                            reportCaptureFailure(e)
                            break
                        }
                    } else if (read < 0) {
                        // pause→AudioRecord.stop() 레이스: STOPPED 중 negative read는 무시.
                        if (isPaused ||
                            record.recordingState == AudioRecord.RECORDSTATE_STOPPED
                        ) {
                            continue
                        }
                        val err = IllegalStateException("AudioRecord.read error code=$read")
                        AppLogger.w(TAG, err.message ?: "read error")
                        reportCaptureFailure(err)
                        break
                    }
                }
            },
            "AudioRecordWavCapture",
        )
        captureThread = thread
        thread.start()
    }

    override fun pause() {
        // captureFailure first — including !isCapturing && captureFailure != null
        throwIfCaptureFailed()
        check(isCapturing) { "WAV capture not started" }
        isPaused = true
        lastAmplitude = 0
        val record = audioRecord ?: error("AudioRecord not started")
        if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            record.stop()
        }
    }

    override fun resume() {
        // captureFailure first — including !isCapturing && captureFailure != null
        throwIfCaptureFailed()
        check(isCapturing) { "WAV capture not started" }
        val record = audioRecord ?: error("AudioRecord not started")
        record.startRecording()
        isPaused = false
    }

    override fun stop() {
        throwIfCaptureFailed()
        isCapturing = false
        isPaused = false
        val thread = captureThread
        captureThread = null
        val joinedCleanly = joinCaptureThread(thread)

        val effect = noiseSuppressor
        noiseSuppressor = null
        runCatching { effect?.release() }

        val record = audioRecord
        audioRecord = null
        if (record != null) {
            runCatching {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            }.onFailure { e -> AppLogger.w(TAG, "AudioRecord.stop: ${e.message}", e) }
            runCatching { record.release() }
        }

        val file = raf
        raf = null

        if (!joinedCleanly) {
            runCatching { file?.close() }
            outputFile?.delete()
            outputFile = null
            throw IllegalStateException("WAV capture thread join timed out")
        }

        val failure = captureFailure
        if (failure != null) {
            runCatching { file?.close() }
            outputFile?.delete()
            outputFile = null
            throw failure
        }

        if (file != null) {
            try {
                file.seek(0)
                file.write(
                    buildWavHeader(
                        totalPcmBytes = totalPcmBytes,
                        sampleRate = negotiatedSampleRate,
                        channelCount = CHANNEL_COUNT,
                        bitsPerSample = BITS_PER_SAMPLE,
                    ),
                )
            } finally {
                runCatching { file.close() }
            }
        }
        endBluetoothRoutingIfNeeded()
        outputFile = null
        onCaptureFailed = null
        lastAmplitude = 0
    }

    override fun release() {
        isCapturing = false
        isPaused = false
        onCaptureFailed = null
        lastAmplitude = 0
        val thread = captureThread
        captureThread = null
        thread?.interrupt()
        joinCaptureThread(thread)
        val effectOnRelease = noiseSuppressor
        noiseSuppressor = null
        runCatching { effectOnRelease?.release() }

        val record = audioRecord
        audioRecord = null
        if (record != null) {
            runCatching {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            }
            runCatching { record.release() }
        }
        runCatching { raf?.close() }
        raf = null
        endBluetoothRoutingIfNeeded()
        outputFile?.delete()
        outputFile = null
        totalPcmBytes = 0L
        captureFailure = null
    }

    override fun amplitude(): Int = if (isPaused || !isCapturing) 0 else lastAmplitude

    private fun initializeAudioRecord(
        sampleRates: List<Int>,
    ): Triple<AudioRecord, Int, Int> {
        for (rate in sampleRates) {
            val minBuf = AudioRecord.getMinBufferSize(rate, CHANNEL_CONFIG, AUDIO_ENCODING)
            if (minBuf <= 0) continue
            val bufferSize = minBuf * 2
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                rate,
                CHANNEL_CONFIG,
                AUDIO_ENCODING,
                bufferSize,
            )
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                return Triple(record, rate, bufferSize)
            }
            record.release()
        }
        error("AudioRecord failed to initialize")
    }

    private fun endBluetoothRoutingIfNeeded() {
        if (!isRoutedToBluetooth) return
        context?.let { MicrophoneSourceRouting.endRouting(it) }
        isRoutedToBluetooth = false
    }

    private fun reportCaptureFailure(e: Exception) {
        if (captureFailure != null) return
        captureFailure = e
        isCapturing = false
        onCaptureFailed?.invoke(e)
    }

    private fun throwIfCaptureFailed() {
        val failure = captureFailure ?: return
        throw failure
    }

    private fun joinCaptureThread(thread: Thread?): Boolean {
        if (thread == null) return true
        thread.join(JOIN_TIMEOUT_MS)
        return !thread.isAlive
    }

    companion object {
        private const val TAG = "AudioRecordWav"

        /** PCM sample rate (Hz). Single source for capture + [RecordingStorageEstimator]. */
        internal const val SAMPLE_RATE = 44_100

        /** Bluetooth SCO 협상 시도 순서 (Hz). [Default] 경로는 [SAMPLE_RATE]만 사용. */
        private val BLUETOOTH_SAMPLE_RATE_CANDIDATES = listOf(SAMPLE_RATE, 16_000, 8_000)

        /** PCM channel count (mono). Single source for capture + [RecordingStorageEstimator]. */
        internal const val CHANNEL_COUNT = 1

        /** PCM bits per sample. Single source for capture + [RecordingStorageEstimator]. */
        internal const val BITS_PER_SAMPLE = 16

        /**
         * Derived from [CHANNEL_COUNT] — keeps AudioRecord config ↔ public channel count in sync.
         * Only mono is supported; stereo would need a new branch here.
         */
        private val CHANNEL_CONFIG: Int = when (CHANNEL_COUNT) {
            1 -> AudioFormat.CHANNEL_IN_MONO
            else -> error("Unsupported CHANNEL_COUNT=$CHANNEL_COUNT (expected 1=mono)")
        }

        /**
         * Derived from [BITS_PER_SAMPLE] — keeps AudioRecord encoding ↔ public bit depth in sync.
         * Only 16-bit PCM is supported.
         */
        private val AUDIO_ENCODING: Int = when (BITS_PER_SAMPLE) {
            16 -> AudioFormat.ENCODING_PCM_16BIT
            else -> error("Unsupported BITS_PER_SAMPLE=$BITS_PER_SAMPLE (expected 16)")
        }

        private const val JOIN_TIMEOUT_MS = 3_000L

        init {
            // Invariants: public constants must match derived AudioFormat values used by AudioRecord.
            check(CHANNEL_CONFIG == AudioFormat.CHANNEL_IN_MONO) {
                "CHANNEL_COUNT=$CHANNEL_COUNT must map to CHANNEL_IN_MONO"
            }
            check(AUDIO_ENCODING == AudioFormat.ENCODING_PCM_16BIT) {
                "BITS_PER_SAMPLE=$BITS_PER_SAMPLE must map to ENCODING_PCM_16BIT"
            }
        }

        /** Little-endian PCM16 mono 버퍼에서 절대 피크. */
        internal fun peakAmplitudePcm16le(buf: ByteArray, read: Int): Int {
            var max = 0
            var i = 0
            while (i + 1 < read) {
                val sample = (buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)
                val signed = sample.toShort().toInt()
                val abs = if (signed < 0) -signed else signed
                if (abs > max) max = abs
                i += 2
            }
            return max.coerceAtMost(Short.MAX_VALUE.toInt())
        }
    }
}
