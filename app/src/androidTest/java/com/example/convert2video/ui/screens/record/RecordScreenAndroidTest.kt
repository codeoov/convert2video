package com.example.convert2video.ui.screens.record

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.example.convert2video.record.RecordingController
import com.example.convert2video.record.RecordingFormat
import com.example.convert2video.record.RecordingSavedWait
import com.example.convert2video.record.RecordingState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Sprint 2-3/2-4 [RecordScreen] stateful 계약 (실제 Screen 조성).
 *
 * VM boolean / RecordContent 중복 검증은 각각
 * [RecordViewModelAndroidTest] · [RecordContentTest] 에 둔다.
 */
@RunWith(AndroidJUnit4::class)
class RecordScreenAndroidTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

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
    fun success_withPermission_showsIdleRecordButton() {
        // Given — RECORD_AUDIO 승인 + Controller Idle
        composeTestRule.setContent {
            RecordScreen(
                onNavigateBack = {},
                onOpenDrawer = {},
            )
        }

        // Then — Idle 세션 컨트롤
        composeTestRule.onNodeWithTag("record_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("record_timer_text").assertTextEquals("00:00")
    }

    @Test
    @SdkSuppress(minSdkVersion = 28)
    fun success_autoRequestPermission_onlyOnceAcrossRecomposition() {
        // Given — 권한 철회 + 동일 VM으로 Screen 조성/해제/재조성
        revokeRecordAudioPermission()
        val viewModel = RecordViewModel(app)
        var visible by mutableStateOf(true)

        composeTestRule.setContent {
            if (visible) {
                RecordScreen(
                    onNavigateBack = {},
                    onOpenDrawer = {},
                    viewModel = viewModel,
                )
            }
        }
        composeTestRule.waitForIdle()

        // When — 첫 진입 LaunchedEffect가 auto-request를 소비
        assertFalse(
            "첫 조성 후 auto-request는 이미 소비되어야 함",
            viewModel.consumeAutoRequestPermission(),
        )

        composeTestRule.runOnIdle { visible = false }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { visible = true }
        composeTestRule.waitForIdle()

        // Then — 재조성에서 launcher 재요청 없음
        assertFalse(viewModel.consumeAutoRequestPermission())
    }

    @Test
    @SdkSuppress(minSdkVersion = 28)
    fun success_onResume_refreshesPermissionSnapshot() {
        // Given — 승인 상태로 Idle
        val viewModel = RecordViewModel(app)
        composeTestRule.setContent {
            RecordScreen(
                onNavigateBack = {},
                onOpenDrawer = {},
                viewModel = viewModel,
            )
        }
        composeTestRule.onNodeWithTag("record_button").assertIsDisplayed()

        // When — 런타임 권한 철회 후 ON_RESUME
        revokeRecordAudioPermission()
        composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeTestRule.waitForIdle()

        // Then — PermissionDenied UI
        composeTestRule.onNodeWithTag("record_permission_denied_state").assertIsDisplayed()
    }

    @Test
    fun success_dispose_doesNotCallPauseOrStop() {
        // Given — pause/stop 호출 추적 VM
        val viewModel = TrackingRecordViewModel(app)
        var visible by mutableStateOf(true)
        composeTestRule.setContent {
            if (visible) {
                RecordScreen(
                    onNavigateBack = {},
                    onOpenDrawer = {},
                    viewModel = viewModel,
                )
            }
        }
        composeTestRule.waitForIdle()
        assertEquals(0, viewModel.pauseCalls)
        assertEquals(0, viewModel.stopCalls)

        // When — Screen dispose (FGS 유지 계약 — pause/stop 금지)
        composeTestRule.runOnIdle { visible = false }
        composeTestRule.waitForIdle()

        // Then
        assertEquals("dispose에서 pause 호출 금지", 0, viewModel.pauseCalls)
        assertEquals("dispose에서 stop 호출 금지", 0, viewModel.stopCalls)
        assertTrue(RecordingController.getInstance(app).state.value is RecordingState.Idle)
    }

    @Test
    fun success_onRecordingSaved_firesOncePerSavedFile() {
        // Given — Screen + seam 콜백 수집 (기기 마이크 필요)
        val viewModel = RecordViewModel(app)
        val controller = RecordingController.getInstance(app)
        val savedFiles = CopyOnWriteArrayList<File>()
        val savedDurations = CopyOnWriteArrayList<Long>()
        val legacySavedFiles = CopyOnWriteArrayList<File>()
        var visible by mutableStateOf(true)

        composeTestRule.setContent {
            if (visible) {
                RecordScreen(
                    onNavigateBack = {},
                    onOpenDrawer = {},
                    onRecordingSaved = { legacySavedFiles.add(it) },
                    onRecordingSavedWithDuration = { file, durationMs ->
                        savedFiles.add(file)
                        savedDurations.add(durationMs)
                    },
                    viewModel = viewModel,
                )
            }
        }
        composeTestRule.waitForIdle()

        // When — MIN+slack 초과 후 Saved (Thread.sleep 금지 — elapsed waitUntil)
        recordUntilPastMinSave(controller)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("record_saved_state").assertIsDisplayed()

        // Then — seam 정확히 1회
        assertEquals(1, savedFiles.size)
        assertTrue(savedFiles[0].exists())
        assertTrue(savedDurations.single() > 0L)
        assertTrue(legacySavedFiles.isEmpty())
        val firstPath = savedFiles[0].absolutePath

        // When — dispose/재조성 (sticky Saved + VM consume 유지)
        composeTestRule.runOnIdle { visible = false }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { visible = true }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("record_saved_state").assertIsDisplayed()

        // Then — 재발사 없음
        assertEquals(1, savedFiles.size)
        assertEquals(firstPath, savedFiles[0].absolutePath)

        runCatching { controller.clearTerminalState() }
    }

    @Test
    fun success_onRecordingSaved_legacyCallbackRunsWhenPreferredIsAbsent() {
        // Given — only the legacy Saved seam is supplied.
        val viewModel = RecordViewModel(app)
        val controller = RecordingController.getInstance(app)
        val legacySavedFiles = CopyOnWriteArrayList<File>()
        composeTestRule.setContent {
            RecordScreen(
                onNavigateBack = {},
                onOpenDrawer = {},
                onRecordingSaved = { legacySavedFiles.add(it) },
                viewModel = viewModel,
            )
        }
        composeTestRule.waitForIdle()

        // When
        recordUntilPastMinSave(controller)
        composeTestRule.waitForIdle()

        // Then — legacy is used only when the preferred duration seam is absent.
        assertEquals(1, legacySavedFiles.size)
        assertTrue(legacySavedFiles.single().exists())
        runCatching { controller.clearTerminalState() }
    }

    @Test
    fun failure_onRecordingSaved_preferredThrowUsesCleanupAndNeverFallsBackToLegacy() {
        // Given — preferred callback throws; legacy must not be selected as a fallback.
        val viewModel = RecordViewModel(app)
        val controller = RecordingController.getInstance(app)
        val legacySavedFiles = CopyOnWriteArrayList<File>()
        composeTestRule.setContent {
            RecordScreen(
                onNavigateBack = {},
                onOpenDrawer = {},
                onRecordingSaved = { legacySavedFiles.add(it) },
                onRecordingSavedWithDuration = { _, _ -> error("preferred callback failure") },
                viewModel = viewModel,
            )
        }
        composeTestRule.waitForIdle()

        // When — callback failure follows the same terminal cleanup contract as URI failure.
        recordUntilPastMinSave(controller)
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            controller.state.value is RecordingState.Idle
        }

        // Then — sticky Saved cannot remain dead, and legacy never runs.
        assertTrue(controller.state.value is RecordingState.Idle)
        assertTrue(legacySavedFiles.isEmpty())
        assertTrue(
            composeTestRule.onAllNodesWithTag("record_saved_state")
                .fetchSemanticsNodes()
                .isEmpty(),
        )
    }

    @Test
    fun success_savedToast_oneShotAcrossReenter() {
        // Given — Screen이 Saved Toast를 소비한 뒤 재진입해도 consumeSavedToast → false
        val viewModel = RecordViewModel(app)
        val controller = RecordingController.getInstance(app)
        var visible by mutableStateOf(true)

        composeTestRule.setContent {
            if (visible) {
                RecordScreen(
                    onNavigateBack = {},
                    onOpenDrawer = {},
                    viewModel = viewModel,
                )
            }
        }
        composeTestRule.waitForIdle()

        // When — Saved까지 (Screen LaunchedEffect가 Toast one-shot 소비)
        recordUntilPastMinSave(controller)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("record_saved_state").assertIsDisplayed()

        val saved = viewModel.uiState.value as RecordUiState.Saved
        val path = saved.outputFile.absolutePath

        // Then — Screen이 이미 true를 소비 → 재호출 false
        assertFalse(
            "Screen LaunchedEffect가 Toast를 1회 소비했어야 함",
            viewModel.consumeSavedToast(path),
        )

        // When — dispose/재조성 (sticky Saved 유지)
        composeTestRule.runOnIdle { visible = false }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { visible = true }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("record_saved_state").assertIsDisplayed()

        // Then — 재진입 후에도 Toast 재허용 없음 (false)
        assertFalse(
            "재진입에서 consumeSavedToast는 false여야 함",
            viewModel.consumeSavedToast(path),
        )

        runCatching { controller.clearTerminalState() }
    }

    @Test
    fun success_offScreenSaved_firesSeamOnceOnReenter() {
        // Given — Activity-scoped VM + seam 수집. 저장은 Screen 밖에서 완료.
        val viewModel = RecordViewModel(app)
        val controller = RecordingController.getInstance(app)
        val savedFiles = CopyOnWriteArrayList<File>()
        val savedDurations = CopyOnWriteArrayList<Long>()
        val legacySavedFiles = CopyOnWriteArrayList<File>()
        var visible by mutableStateOf(true)

        composeTestRule.setContent {
            if (visible) {
                RecordScreen(
                    onNavigateBack = {},
                    onOpenDrawer = {},
                    onRecordingSaved = { legacySavedFiles.add(it) },
                    onRecordingSavedWithDuration = { file, durationMs ->
                        savedFiles.add(file)
                        savedDurations.add(durationMs)
                    },
                    viewModel = viewModel,
                )
            }
        }
        composeTestRule.waitForIdle()

        // When — 녹음 시작 후 Screen dispose (FGS 유지, composition 없음)
        controller.start(RecordingFormat.AAC)
        composeTestRule.waitUntil(timeoutMillis = RecordingSavedWait.timeoutForSavedWaitMs) {
            val state = controller.state.value
            state is RecordingState.Recording &&
                state.elapsedMs >= RecordingSavedWait.minElapsedForSavedMs
        }
        composeTestRule.runOnIdle { visible = false }
        composeTestRule.waitForIdle()

        // When — off-screen에서 stop → Saved (seam composition 없음 → 0회)
        controller.stop()
        composeTestRule.waitUntil(timeoutMillis = 12_000L) {
            controller.state.value is RecordingState.Review
        }
        assertEquals("off-screen Review는 composition 전 seam 0회", 0, savedFiles.size)

        controller.keep()
        composeTestRule.waitUntil(timeoutMillis = 12_000L) {
            controller.state.value is RecordingState.Saved
        }
        assertEquals("off-screen Saved(Keep 후)도 composition 전 seam 0회", 0, savedFiles.size)

        // When — 재진입 (sticky Saved + VM path consume → 1회)
        composeTestRule.runOnIdle { visible = true }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("record_saved_state").assertIsDisplayed()

        // Then — 재진입 1회만
        assertEquals(1, savedFiles.size)
        assertTrue(savedFiles[0].exists())
        assertEquals(1, savedDurations.size)
        assertTrue(savedDurations.single() > 0L)
        assertTrue(legacySavedFiles.isEmpty())

        // When — 재이탈/재진입
        composeTestRule.runOnIdle { visible = false }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { visible = true }
        composeTestRule.waitForIdle()

        // Then — 재발사 없음
        assertEquals(1, savedFiles.size)

        runCatching { controller.clearTerminalState() }
    }

    @Test
    fun success_recordAgain_flushesPendingSeamBeforeClear() {
        // Given — off-screen Saved (seam 미발사) 후 재진입 → Record Again
        val viewModel = RecordViewModel(app)
        val controller = RecordingController.getInstance(app)
        val savedFiles = CopyOnWriteArrayList<File>()
        val savedDurations = CopyOnWriteArrayList<Long>()
        val legacySavedFiles = CopyOnWriteArrayList<File>()
        var visible by mutableStateOf(false)

        // When — Screen 없이 녹음→Saved (pending seam)
        controller.start(RecordingFormat.AAC)
        composeTestRule.waitUntil(timeoutMillis = RecordingSavedWait.timeoutForSavedWaitMs) {
            val state = controller.state.value
            state is RecordingState.Recording &&
                state.elapsedMs >= RecordingSavedWait.minElapsedForSavedMs
        }
        controller.stop()
        composeTestRule.waitUntil(timeoutMillis = 12_000L) {
            controller.state.value is RecordingState.Review
        }
        controller.keep()
        composeTestRule.waitUntil(timeoutMillis = 12_000L) {
            controller.state.value is RecordingState.Saved
        }
        assertEquals(0, savedFiles.size)

        composeTestRule.setContent {
            if (visible) {
                RecordScreen(
                    onNavigateBack = {},
                    onOpenDrawer = {},
                    onRecordingSaved = { legacySavedFiles.add(it) },
                    onRecordingSavedWithDuration = { file, durationMs ->
                        savedFiles.add(file)
                        savedDurations.add(durationMs)
                    },
                    viewModel = viewModel,
                )
            }
        }

        // When — 재진입 후 Record Again (flush→clear; LaunchedEffect와 race-safe one-shot)
        composeTestRule.runOnIdle { visible = true }
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule
                .onAllNodesWithTag("record_record_again_button")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithTag("record_record_again_button").performClick()
        composeTestRule.waitForIdle()

        // Then — seam 정확히 1회 + Idle (flush 또는 LaunchedEffect 중 하나만)
        assertEquals(1, savedFiles.size)
        assertTrue(savedFiles[0].exists())
        assertEquals(1, savedDurations.size)
        assertTrue(savedDurations.single() > 0L)
        assertTrue(legacySavedFiles.isEmpty())
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            viewModel.uiState.value is RecordUiState.Idle
        }
        assertTrue(viewModel.uiState.value is RecordUiState.Idle)
    }

    @Test
    fun success_offScreenReview_discard_doesNotFireSeam() {
        // Given
        val viewModel = RecordViewModel(app)
        val controller = RecordingController.getInstance(app)
        val savedFiles = CopyOnWriteArrayList<File>()
        var visible by mutableStateOf(true)

        composeTestRule.setContent {
            if (visible) {
                RecordScreen(
                    onNavigateBack = {},
                    onOpenDrawer = {},
                    onRecordingSaved = { savedFiles.add(it) },
                    viewModel = viewModel,
                )
            }
        }
        composeTestRule.waitForIdle()

        controller.start(RecordingFormat.AAC)
        composeTestRule.waitUntil(timeoutMillis = RecordingSavedWait.timeoutForSavedWaitMs) {
            val state = controller.state.value
            state is RecordingState.Recording &&
                state.elapsedMs >= RecordingSavedWait.minElapsedForSavedMs
        }
        composeTestRule.runOnIdle { visible = false }
        composeTestRule.waitForIdle()

        controller.stop()
        composeTestRule.waitUntil(timeoutMillis = 12_000L) {
            controller.state.value is RecordingState.Review
        }
        assertEquals(0, savedFiles.size)

        controller.discard()
        composeTestRule.waitUntil(timeoutMillis = 8_000L) {
            controller.state.value is RecordingState.Idle
        }
        composeTestRule.runOnIdle { visible = true }
        composeTestRule.waitForIdle()

        assertEquals(0, savedFiles.size)
        assertTrue(viewModel.uiState.value is RecordUiState.Idle)
    }

    /** MIN+slack 초과 후 Saved까지 — [RecordingSavedWait] SSOT. */
    private fun recordUntilPastMinSave(controller: RecordingController) {
        controller.start(RecordingFormat.AAC)
        composeTestRule.waitUntil(timeoutMillis = RecordingSavedWait.timeoutForSavedWaitMs) {
            val state = controller.state.value
            state is RecordingState.Recording &&
                state.elapsedMs >= RecordingSavedWait.minElapsedForSavedMs
        }
        controller.stop()
        composeTestRule.waitUntil(timeoutMillis = 12_000L) {
            controller.state.value is RecordingState.Review
        }
        controller.keep()
        composeTestRule.waitUntil(timeoutMillis = 12_000L) {
            controller.state.value is RecordingState.Saved
        }
    }

    private fun revokeRecordAudioPermission() {
        val packageName = app.packageName
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .revokeRuntimePermission(packageName, Manifest.permission.RECORD_AUDIO)
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO),
        )
    }

    /** dispose 경로에서 pause/stop 호출 여부를 추적. */
    private class TrackingRecordViewModel(
        application: Application,
    ) : RecordViewModel(application) {
        var pauseCalls: Int = 0
            private set
        var stopCalls: Int = 0
            private set

        override fun pause() {
            pauseCalls++
            super.pause()
        }

        override fun stop() {
            stopCalls++
            super.stop()
        }
    }
}
