package com.example.convert2video.ui.screens.record

import android.Manifest
import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.example.convert2video.record.RecordingController
import com.example.convert2video.record.RecordingFormat
import com.example.convert2video.record.RecordingSavedWait
import com.example.convert2video.record.RecordingState
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [RecordViewModel] 계약 (Screen Compose 없음 — 의도적 VM 단위).
 *
 * - auto-request 1회 consume
 * - Saved Toast one-shot (**path** identity) + clearTerminal 재허용
 * - Saved [onRecordingSaved] seam one-shot (**path**, Toast와 독립) + clearTerminal 재허용
 * - stop() → [isSavingGate] 즉시 true, Idle/Saved/Failed에서 clear
 * - [flushPendingSavedCallback] → clear 전 pending seam 1회
 */
@RunWith(AndroidJUnit4::class)
class RecordViewModelAndroidTest {

    @get:Rule
    val grantPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as Application
        RecordingController.clearInstanceForTest()
    }

    @After
    fun tearDown() {
        RecordingController.clearInstanceForTest()
    }

    // Given / When / Then

    @Test
    fun success_consumeAutoRequestPermission_onlyOnce() {
        // Given
        val viewModel = RecordViewModel(app)

        // When / Then
        assertTrue(viewModel.consumeAutoRequestPermission())
        assertFalse(viewModel.consumeAutoRequestPermission())
    }

    @Test
    fun success_savedToastOneShot_clearsOnClearTerminal() {
        // Given — Toast identity = absolute path
        val viewModel = RecordViewModel(app)
        val path = "/data/user/0/com.convert2video/files/Music/C2V/clip.m4a"

        // When / Then — 동일 path 재Toast 금지
        assertTrue(viewModel.consumeSavedToast(path))
        assertFalse(viewModel.consumeSavedToast(path))

        // clearTerminal → 다음 Saved Toast 재허용
        viewModel.clearTerminalState()
        assertTrue(viewModel.consumeSavedToast(path))
    }

    @Test
    fun success_savedToastOneShot_newPathAllowed() {
        // Given
        val viewModel = RecordViewModel(app)

        // When
        assertTrue(viewModel.consumeSavedToast("/tmp/a.m4a"))

        // Then — 다른 path는 새 Saved로 간주
        assertTrue(viewModel.consumeSavedToast("/tmp/b.m4a"))
        assertFalse(viewModel.consumeSavedToast("/tmp/b.m4a"))
    }

    @Test
    fun success_savedCallbackOneShot_clearsOnClearTerminal() {
        // Given
        val viewModel = RecordViewModel(app)
        val path = "/data/user/0/com.convert2video/files/Music/C2V/clip.m4a"

        // When / Then — 동일 path 재발사 금지
        assertTrue(viewModel.consumeSavedCallback(path))
        assertFalse(viewModel.consumeSavedCallback(path))

        // clearTerminal → 다음 Saved seam 재허용
        viewModel.clearTerminalState()
        assertTrue(viewModel.consumeSavedCallback(path))
    }

    @Test
    fun success_savedCallbackOneShot_independentFromToast() {
        // Given — Toast 소비와 seam 소비는 동일 path identity여도 키 저장소 독립
        val viewModel = RecordViewModel(app)
        val path = "/tmp/clip.m4a"

        // When — Toast만 소비
        assertTrue(viewModel.consumeSavedToast(path))

        // Then — seam은 아직 발사 가능 (및 그 반대)
        assertTrue(viewModel.consumeSavedCallback(path))
        assertFalse(viewModel.consumeSavedCallback(path))
        assertFalse(viewModel.consumeSavedToast(path))
    }

    @Test
    fun success_savedCallbackOneShot_newPathAllowed() {
        // Given
        val viewModel = RecordViewModel(app)

        // When / Then
        assertTrue(viewModel.consumeSavedCallback("/tmp/a.m4a"))
        assertTrue(viewModel.consumeSavedCallback("/tmp/b.m4a"))
        assertFalse(viewModel.consumeSavedCallback("/tmp/b.m4a"))
    }

    @Test
    fun success_stop_setsSavingGateUntilTerminal() {
        // Given — 녹음 중
        val viewModel = RecordViewModel(app)
        val controller = RecordingController.getInstance(app)
        assertFalse(viewModel.isSavingGate.value)

        controller.start(RecordingFormat.AAC)
        waitUntil(timeoutMs = RecordingSavedWait.timeoutForSavedWaitMs) {
            val state = controller.state.value
            state is RecordingState.Recording &&
                state.elapsedMs >= RecordingSavedWait.minElapsedForSavedMs
        }

        // When — stop 직후 게이트 (Stopping 도착 전 레이스 가드)
        viewModel.stop()
        val gateOrSavingOrTerminal =
            viewModel.isSavingGate.value ||
                viewModel.uiState.value is RecordUiState.Saving ||
                viewModel.uiState.value is RecordUiState.Review ||
                viewModel.uiState.value is RecordUiState.Saved ||
                viewModel.uiState.value is RecordUiState.Error
        assertTrue(
            "stop() 직후 isSavingGate 또는 Saving/단말이어야 함",
            gateOrSavingOrTerminal,
        )

        // Then — Review/Failed 후 게이트 clear (Saved는 Keep 후)
        waitUntil(timeoutMs = 12_000L) {
            when (val state = controller.state.value) {
                is RecordingState.Review,
                is RecordingState.Saved,
                is RecordingState.Failed,
                -> true
                else -> false
            }
        }
        waitUntil(timeoutMs = 2_000L) { !viewModel.isSavingGate.value }
        assertFalse(viewModel.isSavingGate.value)
        assertTrue(controller.state.value is RecordingState.Review)

        runCatching { viewModel.discardRecording() }
    }

    @Test
    fun success_flushPendingSavedCallback_consumesOnceBeforeClear() {
        // Given — Screen 없이 sticky Saved
        val viewModel = RecordViewModel(app)
        val controller = RecordingController.getInstance(app)
        controller.start(RecordingFormat.AAC)
        waitUntil(timeoutMs = RecordingSavedWait.timeoutForSavedWaitMs) {
            val state = controller.state.value
            state is RecordingState.Recording &&
                state.elapsedMs >= RecordingSavedWait.minElapsedForSavedMs
        }
        controller.stop()
        waitUntil(timeoutMs = 12_000L) {
            controller.state.value is RecordingState.Review
        }
        viewModel.keepRecording()
        waitUntil(timeoutMs = 12_000L) {
            controller.state.value is RecordingState.Saved
        }

        // When — Record Again 직전 duration-aware flush (Controller sticky Saved)
        val flushed = viewModel.flushPendingSavedCallbackWithDuration()
        assertNotNull(flushed)
        assertTrue(flushed!!.outputFile.exists())
        assertTrue(flushed.elapsedMs > 0L)

        // Then — 재flush null + clear 후 Idle
        assertNull(viewModel.flushPendingSavedCallbackWithDuration())
        viewModel.clearTerminalState()
        waitUntil(timeoutMs = 2_000L) {
            controller.state.value is RecordingState.Idle
        }
        assertTrue(controller.state.value is RecordingState.Idle)
    }

    @Test
    fun success_keepThenImmediateDiscard_savedSeamOnceOrReviewRemains() {
        // Given
        val viewModel = RecordViewModel(app)
        val controller = RecordingController.getInstance(app)
        controller.start(RecordingFormat.AAC)
        waitUntil(timeoutMs = RecordingSavedWait.timeoutForSavedWaitMs) {
            val state = controller.state.value
            state is RecordingState.Recording &&
                state.elapsedMs >= RecordingSavedWait.minElapsedForSavedMs
        }
        controller.stop()
        waitUntil(timeoutMs = 12_000L) {
            controller.state.value is RecordingState.Review
        }

        // When — Keep in-flight + immediate Discard
        viewModel.keepRecording()
        viewModel.discardRecording()

        // Then — Saved + seam 1, or Discard no-op + Review remains (not Idle)
        waitUntil(timeoutMs = 12_000L) {
            val state = controller.state.value
            state is RecordingState.Saved || state is RecordingState.Review
        }
        when (val state = controller.state.value) {
            is RecordingState.Saved -> {
                assertTrue(state.outputFile.exists())
                val path = state.outputFile.absolutePath
                assertTrue(viewModel.consumeSavedCallback(path))
                assertFalse(viewModel.consumeSavedCallback(path))
            }
            is RecordingState.Review -> {
                assertTrue(state.outputFile.exists())
            }
            else -> {
                assertTrue(
                    "Keep-then-Discard must not drop to Idle; last=$state",
                    false,
                )
            }
        }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.yield()
        }
        assertTrue("waitUntil timed out after ${timeoutMs}ms", condition())
    }
}
