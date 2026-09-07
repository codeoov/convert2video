package com.example.convert2video.record

import android.content.Context
import android.os.StatFs
import com.example.convert2video.utils.AppLogger

/**
 * 녹음 잔여 용량 → 예상 가능 시간 추정.
 *
 * - [estimateRemainingSeconds]: pure — [availableBytes] 주입 (단위: **초**, floor 나눗셈).
 * - [queryAvailableBytes]: StatFs 조회 — 경로는 [C2vRecordingNames.appStorageDir]만 사용.
 *
 * 바이트레이트 단일 소스:
 * - AAC: [MediaRecorderAacBackend.AAC_BITRATE_BPS] / 8 (= 16_000 B/s).
 *   Capture also sets [MediaRecorderAacBackend.AAC_SAMPLE_RATE_HZ] /
 *   [MediaRecorderAacBackend.AAC_CHANNEL_COUNT]; Estimator uses CBR bitrate only.
 *   CBR estimate does not include M4A/container or mux overhead — the storage margin
 *   absorbs only limited slack, not a full container budget.
 * - WAV: [AudioRecordWavBackend.SAMPLE_RATE] × [CHANNEL_COUNT] × ([BITS_PER_SAMPLE]/8)
 *   (= 88_200 B/s PCM payload). The canonical [WAV_HEADER_BYTES]-byte RIFF header is
 *   intentionally ignored (payload-only) — at ~88 KB/s the header is under 1 ms and
 *   does not change floor seconds.
 *
 * Floor 전에 [STORAGE_SAFETY_MARGIN_NUM]/[STORAGE_SAFETY_MARGIN_DEN] 정수 산술로
 * 가용 바이트를 축소한다 (`(available * NUM) / DEN`).
 *
 * [com.example.convert2video.ui.screens.record.RecordViewModel]가 Idle 상태에서 호출해
 * StateFlow로 노출하고, [com.example.convert2video.ui.screens.record.RecordScreen]이 표시한다.
 */
object RecordingStorageEstimator {

    private const val TAG = "RecordingStorageEst"

    /**
     * Floor 전 안전 마진 분자. 예: NUM=95, DEN=100 → 가용의 95%만 예산에 사용.
     * 정수 산술만 사용 — Double 곱·반올림 드리프트 금지.
     */
    internal const val STORAGE_SAFETY_MARGIN_NUM = 95L

    /** Floor 전 안전 마진 분모. [STORAGE_SAFETY_MARGIN_NUM]과 함께 사용. */
    internal const val STORAGE_SAFETY_MARGIN_DEN = 100L

    init {
        check(STORAGE_SAFETY_MARGIN_NUM > 0L) { "MARGIN_NUM must be > 0" }
        check(STORAGE_SAFETY_MARGIN_DEN > 0L) { "MARGIN_DEN must be > 0" }
        check(STORAGE_SAFETY_MARGIN_NUM <= STORAGE_SAFETY_MARGIN_DEN) {
            "MARGIN_NUM ($STORAGE_SAFETY_MARGIN_NUM) must be <= MARGIN_DEN ($STORAGE_SAFETY_MARGIN_DEN)"
        }
    }

    /**
     * [availableBytes]로 [format] 녹음을 몇 **초**까지 담을 수 있는지 추정한다.
     *
     * 순서: 가용 ≤ 0 → 0 → `(available * NUM) / DEN` → floor(÷ B/s).
     * WAV는 PCM payload-only ([WAV_HEADER_BYTES] 헤더 무시 — 위 KDoc).
     */
    fun estimateRemainingSeconds(availableBytes: Long, format: RecordingFormat): Long {
        if (availableBytes <= 0L) return 0L
        val budgetBytes =
            (availableBytes * STORAGE_SAFETY_MARGIN_NUM) / STORAGE_SAFETY_MARGIN_DEN
        if (budgetBytes <= 0L) return 0L
        val bytesPerSecond = bytesPerSecond(format)
        return budgetBytes / bytesPerSecond
    }

    /**
     * 포맷별 분당 대략적인 바이트. [bytesPerSecond] × 60 — 단일 소스 재사용(상수 중복 없음).
     * [com.example.convert2video.ui.screens.record.RecordScreen]의 포맷·용량 힌트 표시용.
     */
    fun approxBytesPerMinute(format: RecordingFormat): Long = bytesPerSecond(format) * 60L

    /**
     * 포맷별 초당 바이트. 캡처 백엔드 companion 상수만 참조 (중복 상수 금지).
     * 반환값은 항상 > 0 (AAC CBR / WAV PCM 상수 조합).
     */
    internal fun bytesPerSecond(format: RecordingFormat): Long = when (format) {
        RecordingFormat.AAC -> MediaRecorderAacBackend.AAC_BITRATE_BPS / 8L
        RecordingFormat.WAV ->
            AudioRecordWavBackend.SAMPLE_RATE.toLong() *
                AudioRecordWavBackend.CHANNEL_COUNT.toLong() *
                (AudioRecordWavBackend.BITS_PER_SAMPLE / 8L)
    }

    /**
     * 녹음 저장 볼륨의 가용 바이트를 [StatFs]로 조회한다.
     * 경로: [C2vRecordingNames.appStorageDir] 단일 소스.
     *
     * Side effect: [C2vRecordingNames.appStorageDir] may call [java.io.File.mkdirs]
     * when the C2V recordings directory does not yet exist.
     *
     * Semantics: returns `0L` for both StatFs failure and a volume with no available
     * bytes — callers cannot distinguish failure from empty capacity.
     */
    fun queryAvailableBytes(context: Context): Long {
        return try {
            val dir = C2vRecordingNames.appStorageDir(context)
            StatFs(dir.absolutePath).availableBytes
        } catch (e: Exception) {
            AppLogger.e(TAG, "StatFs availableBytes failed: ${e.message}", e)
            0L
        }
    }
}
