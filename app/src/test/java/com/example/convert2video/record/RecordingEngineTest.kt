package com.example.convert2video.record

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecordingEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private var fakeElapsedMs: Long = 1_000L
    private lateinit var fakeBackend: FakeAudioCaptureBackend

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("C2V")
        fakeElapsedMs = 1_000L
        fakeBackend = FakeAudioCaptureBackend()
    }

    private fun engine(tickIntervalMs: Long = 60_000L): RecordingEngine =
        RecordingEngine(
            backendFactory = { fakeBackend },
            elapsedRealtimeMs = { fakeElapsedMs },
            resolveStorageDir = { storageDir },
            tickIntervalMs = tickIntervalMs,
            engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )

    @Test
    fun success_start_fromIdle_transitionsToRecording() {
        // Given
        val eng = engine()
        // When
        val ok = eng.start(RecordingFormat.AAC)
        // Then
        assertTrue(ok)
        val recording = eng.state.value as RecordingState.Recording
        assertEquals(0L, recording.elapsedMs)
        assertEquals(1, fakeBackend.startCount)
        eng.release()
    }

    @Test
    fun success_pause_fromRecording_accumulatesElapsedAndTransitionsToPaused() {
        // Given
        val eng = engine()
        eng.start(RecordingFormat.AAC)
        fakeElapsedMs = 1_500L
        // When
        val ok = eng.pause()
        // Then
        assertTrue(ok)
        val paused = eng.state.value as RecordingState.Paused
        assertEquals(500L, paused.elapsedMs)
        assertEquals(1, fakeBackend.pauseCount)
        eng.release()
    }

    @Test
    fun success_resume_fromPaused_transitionsToRecording() {
        // Given
        val eng = engine()
        eng.start(RecordingFormat.WAV)
        eng.pause()
        // When
        val ok = eng.resume()
        // Then
        assertTrue(ok)
        assertTrue(eng.state.value is RecordingState.Recording)
        assertEquals(1, fakeBackend.resumeCount)
        eng.release()
    }

    @Test
    fun success_stop_fromRecording_transitionsToStoppedWithAccumulatedDuration() {
        // Given
        val eng = engine()
        eng.start(RecordingFormat.AAC)
        fakeElapsedMs = 3_000L
        // When
        val ok = eng.stop()
        // Then
        assertTrue(ok)
        val stopped = eng.state.value as RecordingState.Stopped
        assertEquals(2_000L, stopped.result.durationMs)
        assertEquals(RecordingFormat.AAC, stopped.result.format)
        assertTrue(stopped.result.file.name.endsWith(".m4a"))
        assertEquals(1, fakeBackend.stopCount)
        eng.release()
    }

    @Test
    fun success_stop_doesNotEmitRecordingAfterwards_evenWithFastTicker() = runBlocking {
        // Given — short tick; after stop ticker must not push Recording again
        fakeBackend.amplitudeValue = 99
        val eng = engine(tickIntervalMs = 20L)
        eng.start(RecordingFormat.AAC)
        fakeElapsedMs = 1_200L
        // When
        assertTrue(eng.stop())
        val stopped = eng.state.value as RecordingState.Stopped
        fakeElapsedMs = 9_999L
        delay(80L)
        // Then — still Stopped (not Recording), result unchanged
        assertTrue(
            "expected Stopped after stop, was ${eng.state.value}",
            eng.state.value is RecordingState.Stopped,
        )
        assertEquals(stopped, eng.state.value)
        assertEquals(1, fakeBackend.stopCount)
        eng.release()
    }

    @Test
    fun success_stop_fromPaused_doesNotDoubleCountPausedInterval() {
        // Given
        val eng = engine()
        eng.start(RecordingFormat.AAC)
        fakeElapsedMs = 2_000L
        eng.pause()
        fakeElapsedMs = 5_000L
        // When
        val ok = eng.stop()
        // Then
        assertTrue(ok)
        val stopped = eng.state.value as RecordingState.Stopped
        assertEquals(1_000L, stopped.result.durationMs)
        eng.release()
    }

    @Test
    fun success_recording_tick_updatesElapsedMsAndAmplitude() = runBlocking {
        // Given
        fakeBackend.amplitudeValue = 1234
        val eng = engine(tickIntervalMs = 50L)
        eng.start(RecordingFormat.AAC)
        fakeElapsedMs = 1_250L
        // When
        delay(120L)
        // Then
        val recording = eng.state.value as RecordingState.Recording
        assertEquals(250L, recording.elapsedMs)
        assertEquals(1234, recording.amplitude)
        eng.release()
    }

    @Test
    fun failure_start_whileRecording_isRejectedAndStateUnchanged() {
        // Given
        val eng = engine()
        eng.start(RecordingFormat.AAC)
        // When
        val ok = eng.start(RecordingFormat.WAV)
        // Then
        assertFalse(ok)
        assertTrue(eng.state.value is RecordingState.Recording)
        assertEquals(1, fakeBackend.startCount)
        eng.release()
    }

    @Test
    fun failure_pause_whileIdle_isRejected() {
        // Given
        val eng = engine()
        // When / Then
        assertFalse(eng.pause())
        assertEquals(RecordingState.Idle, eng.state.value)
    }

    @Test
    fun failure_resume_whileRecording_isRejected() {
        // Given
        val eng = engine()
        eng.start(RecordingFormat.AAC)
        // When / Then
        assertFalse(eng.resume())
        assertTrue(eng.state.value is RecordingState.Recording)
        eng.release()
    }

    @Test
    fun failure_stop_whileIdle_isRejected() {
        // Given
        val eng = engine()
        // When / Then
        assertFalse(eng.stop())
        assertEquals(RecordingState.Idle, eng.state.value)
    }

    @Test
    fun exception_start_whenBackendThrows_transitionsToFailedAndReleasesBackend() {
        // Given
        fakeBackend.shouldThrowOnStart = true
        val eng = engine()
        // When
        val ok = eng.start(RecordingFormat.AAC)
        // Then
        assertFalse(ok)
        val failed = eng.state.value as RecordingState.Failed
        assertEquals(RecordingErrorCodes.START_FAILED, failed.errorCode)
        assertEquals(1, fakeBackend.releaseCount)
    }

    @Test
    fun exception_stop_whenBackendThrows_transitionsToFailed() {
        // Given
        val eng = engine()
        eng.start(RecordingFormat.AAC)
        fakeBackend.shouldThrowOnStop = true
        // When
        val ok = eng.stop()
        // Then
        assertFalse(ok)
        val failed = eng.state.value as RecordingState.Failed
        assertEquals(RecordingErrorCodes.STOP_FAILED, failed.errorCode)
    }

    @Test
    fun exception_asyncCaptureFailure_transitionsToFailedImmediately() {
        // Given
        fakeBackend.failAsyncAfterStart = true
        val eng = engine()
        // When
        eng.start(RecordingFormat.AAC)
        // Then — callback fires from start; Service must not stay Recording
        val failed = eng.state.value as RecordingState.Failed
        assertEquals(RecordingErrorCodes.CAPTURE_FAILED, failed.errorCode)
    }

    @Test
    fun success_isTooShortForSave_boundaryInclusiveAtMin() {
        val min = RecordingErrorCodes.MIN_SAVE_DURATION_MS
        // Given / When / Then — 0·MIN-1·MIN → true, MIN+1 → false
        assertTrue(RecordingErrorCodes.isTooShortForSave(0L))
        assertTrue(RecordingErrorCodes.isTooShortForSave(min - 1L))
        assertTrue(RecordingErrorCodes.isTooShortForSave(min))
        assertFalse(RecordingErrorCodes.isTooShortForSave(min + 1L))
    }

    @Test
    fun success_deleteRecordingOutputOrLog_deletesExistingFile() {
        // Given
        val file = tempFolder.newFile("clip.m4a")
        assertTrue(file.exists())
        // When
        val ok = deleteRecordingOutputOrLog(file, reason = RecordingErrorCodes.TOO_SHORT)
        // Then
        assertTrue(ok)
        assertFalse(file.exists())
    }

    @Test
    fun failure_deleteRecordingOutputOrLog_missingFile_returnsFalse() {
        // Given — 이미 없음
        val file = File(tempFolder.root, "gone.m4a")
        assertFalse(file.exists())
        // When / Then — false(로그 경로) + 파일 미존재 유지
        assertFalse(deleteRecordingOutputOrLog(file, reason = RecordingErrorCodes.INDEX_FAILED))
        assertFalse(file.exists())
    }

    private class FakeAudioCaptureBackend : AudioCaptureBackend {
        var startCount = 0
        var pauseCount = 0
        var resumeCount = 0
        var stopCount = 0
        var releaseCount = 0
        var amplitudeValue = 0
        var shouldThrowOnStart = false
        var shouldThrowOnStop = false
        var failAsyncAfterStart = false

        override fun start(outputFile: File, onCaptureFailed: (Exception) -> Unit) {
            startCount++
            if (shouldThrowOnStart) error("fake start failure")
            if (failAsyncAfterStart) {
                onCaptureFailed(IllegalStateException("fake async capture failure"))
            }
        }

        override fun pause() {
            pauseCount++
        }

        override fun resume() {
            resumeCount++
        }

        override fun stop() {
            stopCount++
            if (shouldThrowOnStop) error("fake stop failure")
        }

        override fun release() {
            releaseCount++
        }

        override fun amplitude(): Int = amplitudeValue
    }
}
