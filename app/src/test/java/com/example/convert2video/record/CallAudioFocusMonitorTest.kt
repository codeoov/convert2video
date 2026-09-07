package com.example.convert2video.record

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CallAudioFocusMonitor] → [RecordingAudioFocusBackend] 위임 단위 테스트.
 */
class CallAudioFocusMonitorTest {

    @Test
    fun success_request_forwardsListenerAndReturnsGranted() {
        // Given
        val backend = FakeRecordingAudioFocusBackend(granted = true)
        val monitor = CallAudioFocusMonitor(backend)
        var received = 0
        // When
        val ok = monitor.request { received = it }
        backend.lastListener?.invoke(7)
        // Then
        assertTrue(ok)
        assertEquals(1, backend.requestCount)
        assertEquals(7, received)
    }

    @Test
    fun failure_requestDenied_returnsFalseFailOpen() {
        // Given
        val backend = FakeRecordingAudioFocusBackend(granted = false)
        val monitor = CallAudioFocusMonitor(backend)
        // When
        val ok = monitor.request { }
        // Then
        assertFalse(ok)
        assertEquals(1, backend.requestCount)
    }

    @Test
    fun success_abandon_forwardsToBackend() {
        // Given
        val backend = FakeRecordingAudioFocusBackend()
        val monitor = CallAudioFocusMonitor(backend)
        monitor.request { }
        // When
        monitor.abandon()
        // Then
        assertEquals(1, backend.abandonCount)
        assertNull(backend.lastListener)
    }

    // ── resolveCallAudioFocusAction ─────────────────────────────────────────

    @Test
    fun success_loss_recording_callModeActive_pauses() {
        for (focusChange in listOf(
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
        )) {
            val action = resolveCallAudioFocusAction(
                focusChange = focusChange,
                state = RecordingState.Recording(elapsedMs = 1_000L, amplitude = 0),
                pausedByCallDetection = false,
                isCallModeActive = true,
            )
            assertEquals(CallAudioFocusAction.Pause, action)
        }
    }

    @Test
    fun failure_loss_recording_callModeInactive_doesNothing() {
        for (focusChange in listOf(
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
        )) {
            val action = resolveCallAudioFocusAction(
                focusChange = focusChange,
                state = RecordingState.Recording(elapsedMs = 1_000L, amplitude = 0),
                pausedByCallDetection = false,
                isCallModeActive = false,
            )
            assertEquals(CallAudioFocusAction.None, action)
        }
    }

    @Test
    fun failure_lossTransientCanDuck_neverPauses() {
        for (isCallModeActive in listOf(true, false)) {
            for (state in listOf(
                RecordingState.Recording(elapsedMs = 1_000L, amplitude = 0),
                RecordingState.Paused(elapsedMs = 1_000L),
            )) {
                val action = resolveCallAudioFocusAction(
                    focusChange = AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                    state = state,
                    pausedByCallDetection = false,
                    isCallModeActive = isCallModeActive,
                )
                assertEquals(CallAudioFocusAction.None, action)
            }
        }
    }

    @Test
    fun success_gain_paused_pausedByCallDetection_resumes() {
        for (focusChange in listOf(
            AudioManager.AUDIOFOCUS_GAIN,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
        )) {
            val action = resolveCallAudioFocusAction(
                focusChange = focusChange,
                state = RecordingState.Paused(elapsedMs = 1_000L),
                pausedByCallDetection = true,
                isCallModeActive = true,
            )
            assertEquals(CallAudioFocusAction.Resume, action)
        }
    }

    @Test
    fun failure_gain_paused_userPause_doesNotResume() {
        for (focusChange in listOf(
            AudioManager.AUDIOFOCUS_GAIN,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
        )) {
            val action = resolveCallAudioFocusAction(
                focusChange = focusChange,
                state = RecordingState.Paused(elapsedMs = 1_000L),
                pausedByCallDetection = false,
                isCallModeActive = true,
            )
            assertEquals(CallAudioFocusAction.None, action)
        }
    }

    @Test
    fun failure_loss_alreadyPaused_doesNothing() {
        for (focusChange in listOf(
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
        )) {
            for (isCallModeActive in listOf(true, false)) {
                val action = resolveCallAudioFocusAction(
                    focusChange = focusChange,
                    state = RecordingState.Paused(elapsedMs = 1_000L),
                    pausedByCallDetection = true,
                    isCallModeActive = isCallModeActive,
                )
                assertEquals(CallAudioFocusAction.None, action)
            }
        }
    }

    @Test
    fun failure_gain_recording_doesNothing() {
        for (focusChange in listOf(
            AudioManager.AUDIOFOCUS_GAIN,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
        )) {
            for (isCallModeActive in listOf(true, false)) {
                val action = resolveCallAudioFocusAction(
                    focusChange = focusChange,
                    state = RecordingState.Recording(elapsedMs = 1_000L, amplitude = 0),
                    pausedByCallDetection = false,
                    isCallModeActive = isCallModeActive,
                )
                assertEquals(CallAudioFocusAction.None, action)
            }
        }
    }

    private class FakeRecordingAudioFocusBackend(
        private val granted: Boolean = true,
    ) : RecordingAudioFocusBackend {
        var requestCount: Int = 0
        var abandonCount: Int = 0
        var lastListener: ((Int) -> Unit)? = null

        override fun request(onFocusChange: (Int) -> Unit): Boolean {
            requestCount++
            lastListener = onFocusChange
            return granted
        }

        override fun abandon() {
            abandonCount++
            lastListener = null
        }
    }
}
