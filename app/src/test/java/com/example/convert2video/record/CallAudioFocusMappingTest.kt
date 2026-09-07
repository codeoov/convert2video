package com.example.convert2video.record

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * [resolveCallAudioFocusAction] 매핑 SSOT 단위 테스트.
 */
class CallAudioFocusMappingTest {

    private val recording = RecordingState.Recording(elapsedMs = 1_000L, amplitude = 1)
    private val paused = RecordingState.Paused(elapsedMs = 1_000L)
    private val dummyFile = File("dummy.m4a")

    @Test
    fun success_lossWhileRecording_resolvesPause() {
        // Given
        val focusChange = AudioManager.AUDIOFOCUS_LOSS
        // When
        val action = resolveCallAudioFocusAction(
            focusChange = focusChange,
            state = recording,
            pausedByCallDetection = false,
            isCallModeActive = true,
        )
        // Then
        assertEquals(CallAudioFocusAction.Pause, action)
    }

    @Test
    fun success_lossTransientWhileRecording_resolvesPause() {
        // Given
        val focusChange = AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        // When
        val action = resolveCallAudioFocusAction(
            focusChange = focusChange,
            state = recording,
            pausedByCallDetection = false,
            isCallModeActive = true,
        )
        // Then
        assertEquals(CallAudioFocusAction.Pause, action)
    }

    @Test
    fun failure_lossWhileRecordingNotInCallMode_resolvesNone() {
        // Given — media playback etc. must not pause recording
        val focusChange = AudioManager.AUDIOFOCUS_LOSS
        // When
        val action = resolveCallAudioFocusAction(
            focusChange = focusChange,
            state = recording,
            pausedByCallDetection = false,
            isCallModeActive = false,
        )
        // Then
        assertEquals(CallAudioFocusAction.None, action)
    }

    @Test
    fun failure_lossTransientWhileRecordingNotInCallMode_resolvesNone() {
        // Given
        val focusChange = AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        // When
        val action = resolveCallAudioFocusAction(
            focusChange = focusChange,
            state = recording,
            pausedByCallDetection = false,
            isCallModeActive = false,
        )
        // Then
        assertEquals(CallAudioFocusAction.None, action)
    }

    @Test
    fun success_gainWhilePausedByCall_resolvesResume() {
        // Given
        val focusChange = AudioManager.AUDIOFOCUS_GAIN
        // When
        val action = resolveCallAudioFocusAction(
            focusChange = focusChange,
            state = paused,
            pausedByCallDetection = true,
            isCallModeActive = false,
        )
        // Then
        assertEquals(CallAudioFocusAction.Resume, action)
    }

    @Test
    fun success_gainTransientWhilePausedByCall_resolvesResume() {
        // Given
        val focusChange = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        // When
        val action = resolveCallAudioFocusAction(
            focusChange = focusChange,
            state = paused,
            pausedByCallDetection = true,
            isCallModeActive = false,
        )
        // Then
        assertEquals(CallAudioFocusAction.Resume, action)
    }

    @Test
    fun failure_duckWhileRecording_resolvesNone() {
        // Given — duck is not applied to capture
        val focusChange = AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
        // When
        val action = resolveCallAudioFocusAction(
            focusChange = focusChange,
            state = recording,
            pausedByCallDetection = false,
            isCallModeActive = true,
        )
        // Then
        assertEquals(CallAudioFocusAction.None, action)
    }

    @Test
    fun failure_gainWhileUserPaused_resolvesNone() {
        // Given — user pause must not auto-resume
        val focusChange = AudioManager.AUDIOFOCUS_GAIN
        // When
        val action = resolveCallAudioFocusAction(
            focusChange = focusChange,
            state = paused,
            pausedByCallDetection = false,
            isCallModeActive = false,
        )
        // Then
        assertEquals(CallAudioFocusAction.None, action)
    }

    @Test
    fun failure_lossWhileAlreadyPaused_resolvesNone() {
        // Given — flag stays with the caller; mapping is None
        val focusChange = AudioManager.AUDIOFOCUS_LOSS
        // When
        val action = resolveCallAudioFocusAction(
            focusChange = focusChange,
            state = paused,
            pausedByCallDetection = true,
            isCallModeActive = false,
        )
        // Then
        assertEquals(CallAudioFocusAction.None, action)
    }

    @Test
    fun failure_idleIgnoresLoss_resolvesNone() {
        // Given
        val focusChange = AudioManager.AUDIOFOCUS_LOSS
        // When
        val action = resolveCallAudioFocusAction(
            focusChange = focusChange,
            state = RecordingState.Idle,
            pausedByCallDetection = false,
            isCallModeActive = false,
        )
        // Then
        assertEquals(CallAudioFocusAction.None, action)
    }

    @Test
    fun failure_reviewIgnoresGain_resolvesNone() {
        // Given
        val review = RecordingState.Review(outputFile = dummyFile, elapsedMs = 6_000L)
        // When
        val action = resolveCallAudioFocusAction(
            focusChange = AudioManager.AUDIOFOCUS_GAIN,
            state = review,
            pausedByCallDetection = true,
            isCallModeActive = false,
        )
        // Then
        assertEquals(CallAudioFocusAction.None, action)
    }

    @Test
    fun failure_recordingGain_resolvesNone() {
        // Given
        val focusChange = AudioManager.AUDIOFOCUS_GAIN
        // When
        val action = resolveCallAudioFocusAction(
            focusChange = focusChange,
            state = recording,
            pausedByCallDetection = false,
            isCallModeActive = true,
        )
        // Then
        assertEquals(CallAudioFocusAction.None, action)
    }

    @Test
    fun failure_stoppingIgnoresLoss_resolvesNone() {
        // Given / When
        val action = resolveCallAudioFocusAction(
            focusChange = AudioManager.AUDIOFOCUS_LOSS,
            state = RecordingState.Stopping,
            pausedByCallDetection = true,
            isCallModeActive = false,
        )
        // Then
        assertEquals(CallAudioFocusAction.None, action)
    }

    @Test
    fun failure_savedIgnoresGain_resolvesNone() {
        // Given
        val saved = RecordingState.Saved(outputFile = dummyFile, elapsedMs = 6_000L)
        // When
        val action = resolveCallAudioFocusAction(
            focusChange = AudioManager.AUDIOFOCUS_GAIN,
            state = saved,
            pausedByCallDetection = true,
            isCallModeActive = false,
        )
        // Then
        assertEquals(CallAudioFocusAction.None, action)
    }

    @Test
    fun failure_failedIgnoresLoss_resolvesNone() {
        // Given
        val failed = RecordingState.Failed(RecordingErrorCodes.CAPTURE_FAILED)
        // When
        val action = resolveCallAudioFocusAction(
            focusChange = AudioManager.AUDIOFOCUS_LOSS,
            state = failed,
            pausedByCallDetection = false,
            isCallModeActive = false,
        )
        // Then
        assertEquals(CallAudioFocusAction.None, action)
    }

    @Test
    fun failure_unknownFocusChange_resolvesNone() {
        // Given
        val unknownFocusChange = 99
        // When
        val action = resolveCallAudioFocusAction(
            focusChange = unknownFocusChange,
            state = recording,
            pausedByCallDetection = false,
            isCallModeActive = false,
        )
        // Then
        assertEquals(CallAudioFocusAction.None, action)
        assertEquals("OTHER(99)", audioFocusChangeLabel(unknownFocusChange))
    }

    @Test
    fun failure_gainTransientMayDuck_resolvesNone() {
        // Given
        val focusChange = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        // When
        val action = resolveCallAudioFocusAction(
            focusChange = focusChange,
            state = paused,
            pausedByCallDetection = true,
            isCallModeActive = false,
        )
        // Then
        assertEquals(CallAudioFocusAction.None, action)
    }

    @Test
    fun success_audioFocusChangeLabel_knownCodes() {
        // Given / When / Then
        assertEquals("LOSS", audioFocusChangeLabel(AudioManager.AUDIOFOCUS_LOSS))
        assertEquals("GAIN", audioFocusChangeLabel(AudioManager.AUDIOFOCUS_GAIN))
        assertEquals(
            "LOSS_TRANSIENT_CAN_DUCK",
            audioFocusChangeLabel(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK),
        )
    }
}
