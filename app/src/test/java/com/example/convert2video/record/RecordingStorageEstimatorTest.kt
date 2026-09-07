package com.example.convert2video.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStorageEstimatorTest {

    private val aacBps = RecordingStorageEstimator.bytesPerSecond(RecordingFormat.AAC)
    private val wavBps = RecordingStorageEstimator.bytesPerSecond(RecordingFormat.WAV)
    private val marginNum = RecordingStorageEstimator.STORAGE_SAFETY_MARGIN_NUM
    private val marginDen = RecordingStorageEstimator.STORAGE_SAFETY_MARGIN_DEN

    @Test
    fun success_bytesPerSecond_aacFromBackendBitrate() {
        // Given / When / Then: AAC_BITRATE_BPS / 8
        assertEquals(MediaRecorderAacBackend.AAC_BITRATE_BPS / 8L, aacBps)
        assertTrue(aacBps > 0L)
    }

    @Test
    fun success_bytesPerSecond_wavFromBackendPcmConstants() {
        // Given / When / Then: SAMPLE_RATE × CHANNEL_COUNT × (BITS/8)
        // Touching SAMPLE_RATE also runs AudioRecordWavBackend companion init (WAV invariants).
        val expected =
            AudioRecordWavBackend.SAMPLE_RATE.toLong() *
                AudioRecordWavBackend.CHANNEL_COUNT.toLong() *
                (AudioRecordWavBackend.BITS_PER_SAMPLE / 8L)
        assertEquals(expected, wavBps)
        assertTrue(wavBps > 0L)
        assertTrue(AudioRecordWavBackend.SAMPLE_RATE > 0)
    }

    @Test
    fun success_aacSampleRateEqualsWavSampleRate() {
        // Given / When / Then: companion init equality (no RecordingAudioParams)
        assertEquals(
            AudioRecordWavBackend.SAMPLE_RATE,
            MediaRecorderAacBackend.AAC_SAMPLE_RATE_HZ,
        )
    }

    @Test
    fun success_marginInvariant_numLessOrEqualDenAndPositive() {
        // Given / When / Then: companion init + public constants
        assertTrue(marginNum > 0L)
        assertTrue(marginDen > 0L)
        assertTrue(marginNum <= marginDen)
        assertEquals(95L, marginNum)
        assertEquals(100L, marginDen)
    }

    @Test
    fun success_estimateRemainingSeconds_availableBytesOneYieldsZero() {
        // Given: (1 * 95) / 100 = 0 → budget 0
        // When
        val seconds = RecordingStorageEstimator.estimateRemainingSeconds(
            1L,
            RecordingFormat.AAC,
        )
        // Then
        assertEquals(0L, seconds)
        assertEquals(0L, applyMargin(1L))
    }

    @Test
    fun success_estimateRemainingSeconds_aacExactTenSecondsWithMargin() {
        // Given: raw bytes that yield exactly 10s after integer margin
        val availableBytes = secondsToRawAvailable(10L, aacBps)
        // When
        val seconds = RecordingStorageEstimator.estimateRemainingSeconds(
            availableBytes,
            RecordingFormat.AAC,
        )
        // Then
        assertEquals(10L, seconds)
    }

    @Test
    fun success_estimateRemainingSeconds_wavExactTenSecondsWithMargin() {
        // Given
        val availableBytes = secondsToRawAvailable(10L, wavBps)
        // When
        val seconds = RecordingStorageEstimator.estimateRemainingSeconds(
            availableBytes,
            RecordingFormat.WAV,
        )
        // Then
        assertEquals(10L, seconds)
    }

    @Test
    fun success_estimateRemainingSeconds_aacAvailableLessThanOneSecondReturnsZero() {
        // Given: after margin, budget < aacBps → floor 0
        val availableBytes = aacBps - 1L
        // When
        val seconds = RecordingStorageEstimator.estimateRemainingSeconds(
            availableBytes,
            RecordingFormat.AAC,
        )
        // Then
        assertEquals(0L, seconds)
        assertTrue(availableBytes < aacBps)
    }

    @Test
    fun success_estimateRemainingSeconds_wavFloorsPartialSecond() {
        // Given: after integer margin, budget = 2*wavBps - 1 → floor 1s
        val availableBytes = ceilDiv((2L * wavBps - 1L) * marginDen, marginNum)
        val budget = applyMargin(availableBytes)
        // When
        val seconds = RecordingStorageEstimator.estimateRemainingSeconds(
            availableBytes,
            RecordingFormat.WAV,
        )
        // Then: partial second discarded by floor
        assertEquals(1L, seconds)
        assertTrue(budget >= wavBps)
        assertTrue(budget < 2L * wavBps)
    }

    @Test
    fun success_estimateRemainingSeconds_marginReducesBudgetBeforeFloor() {
        // Given: without margin, available == 10 * aacBps → 10s;
        // with (95/100) → budget = (10 * aacBps * 95) / 100 → floor 9s
        val availableBytes = 10L * aacBps
        val expectedBudget = applyMargin(availableBytes)
        val expectedSeconds = expectedBudget / aacBps
        // When
        val seconds = RecordingStorageEstimator.estimateRemainingSeconds(
            availableBytes,
            RecordingFormat.AAC,
        )
        // Then
        assertEquals(9L, expectedSeconds)
        assertEquals(expectedSeconds, seconds)
        assertTrue(seconds < 10L)
    }

    @Test
    fun success_estimateRemainingSeconds_zeroOrNegativeReturnsZero() {
        assertEquals(
            0L,
            RecordingStorageEstimator.estimateRemainingSeconds(0L, RecordingFormat.AAC),
        )
        assertEquals(
            0L,
            RecordingStorageEstimator.estimateRemainingSeconds(-1L, RecordingFormat.WAV),
        )
    }

    @Test
    fun success_estimateRemainingSeconds_wavIgnoresHeaderBytesConstant() {
        // Given: payload-only — [WAV_HEADER_BYTES] RIFF header is intentionally not subtracted.
        // Mid-second slack so ±header cannot cross a floor boundary.
        val midSecondSlack = wavBps / 2L
        val availableBytes = secondsToRawAvailable(5L, wavBps) + midSecondSlack
        val withHeaderPenalty = availableBytes - WAV_HEADER_BYTES.toLong()
        // When
        val withoutPenalty = RecordingStorageEstimator.estimateRemainingSeconds(
            availableBytes,
            RecordingFormat.WAV,
        )
        val withPenalty = RecordingStorageEstimator.estimateRemainingSeconds(
            withHeaderPenalty,
            RecordingFormat.WAV,
        )
        // Then: both still 5s — header ≪ 1s @ wavBps
        assertEquals(5L, withoutPenalty)
        assertEquals(5L, withPenalty)
        assertTrue(WAV_HEADER_BYTES.toLong() < wavBps)
        assertEquals(44, WAV_HEADER_BYTES)
    }

    @Test
    fun success_approxBytesPerMinute_matchesBytesPerSecondTimesSixty() {
        // Given / When / Then: single-source reuse — no duplicated bitrate constants
        assertEquals(aacBps * 60L, RecordingStorageEstimator.approxBytesPerMinute(RecordingFormat.AAC))
        assertEquals(wavBps * 60L, RecordingStorageEstimator.approxBytesPerMinute(RecordingFormat.WAV))
    }

    /** Integer margin: `(available * NUM) / DEN` — same formula as Estimator. */
    private fun applyMargin(availableBytes: Long): Long =
        (availableBytes * marginNum) / marginDen

    /**
     * Invert margin: min raw such that `applyMargin(raw) / bps == [seconds]`.
     * `raw = ceil(budgetNeeded * DEN / NUM)`.
     */
    private fun secondsToRawAvailable(seconds: Long, bytesPerSecond: Long): Long {
        val budgetNeeded = seconds * bytesPerSecond
        return ceilDiv(budgetNeeded * marginDen, marginNum)
    }

    private fun ceilDiv(numerator: Long, denominator: Long): Long =
        (numerator + denominator - 1L) / denominator
}
