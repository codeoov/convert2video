package com.example.convert2video.ui.screens.record

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.example.convert2video.R
import com.example.convert2video.record.RecordingFormat
import com.example.convert2video.record.RecordingStorageEstimator
import com.example.convert2video.ui.shared.MediaDurationStyle
import com.example.convert2video.ui.shared.formatApproxSizePerMinute
import com.example.convert2video.ui.shared.formatMediaDurationMs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class RecordContentTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val appContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun setContent(
        uiState: RecordUiState,
        onStart: () -> Unit = {},
        onPause: () -> Unit = {},
        onResume: () -> Unit = {},
        onStop: () -> Unit = {},
        onKeep: () -> Unit = {},
        onDiscard: () -> Unit = {},
        onRequestPermission: () -> Unit = {},
        onOpenSettings: () -> Unit = {},
        onRecordAgain: () -> Unit = {},
        onDismissError: () -> Unit = {},
        onNavigateBack: () -> Unit = {},
        onOpenDrawer: () -> Unit = {},
        isSavingGate: Boolean = false,
        isStartEnabled: Boolean = true,
        /** 제품 기본 false — TopBar 회귀 fixture만 true. */
        showTopBar: Boolean = false,
        storageRemainingSeconds: Long? = null,
        recordingFormat: RecordingFormat? = null,
        reviewActionsEnabled: Boolean = true,
    ) {
        composeTestRule.setContent {
            RecordContent(
                uiState = uiState,
                onStart = onStart,
                onPause = onPause,
                onResume = onResume,
                onStop = onStop,
                onKeep = onKeep,
                onDiscard = onDiscard,
                onRequestPermission = onRequestPermission,
                onOpenSettings = onOpenSettings,
                onRecordAgain = onRecordAgain,
                onDismissError = onDismissError,
                onNavigateBack = onNavigateBack,
                onOpenDrawer = onOpenDrawer,
                isSavingGate = isSavingGate,
                isStartEnabled = isStartEnabled,
                showTopBar = showTopBar,
                storageRemainingSeconds = storageRemainingSeconds,
                recordingFormat = recordingFormat,
                reviewActionsEnabled = reviewActionsEnabled,
            )
        }
    }

    private fun expectedStorageRemainingText(seconds: Long): String {
        val formatted = formatMediaDurationMs(seconds * 1_000L, MediaDurationStyle.Timer)
        return appContext.getString(R.string.record_storage_remaining, formatted)
    }

    private fun expectedFormatSizeHintText(format: RecordingFormat): String {
        val formatLabelRes = when (format) {
            RecordingFormat.AAC -> R.string.options_recording_format_aac
            RecordingFormat.WAV -> R.string.options_recording_format_wav
        }
        val formatLabel = appContext.getString(formatLabelRes)
        val sizeText =
            formatApproxSizePerMinute(RecordingStorageEstimator.approxBytesPerMinute(format))
        return appContext.getString(R.string.record_format_size_hint, formatLabel, sizeText)
    }

    private fun contentDescriptionMatcher(expected: String): SemanticsMatcher =
        SemanticsMatcher.expectValue(
            SemanticsProperties.ContentDescription,
            listOf(expected),
        )

    // Given / When / Then

    @Test
    fun success_idleTapInvokesOnStart() {
        // Given
        var started = false
        setContent(
            uiState = RecordUiState.Idle,
            onStart = { started = true },
        )

        // When
        composeTestRule.onNodeWithTag("record_button").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("record_timer_text").assertTextEquals("00:00")

        // Then
        assertTrue(started)
        composeTestRule.onNodeWithTag("record_level_meter").assertDoesNotExist()
    }

    @Test
    fun success_recordingTapInvokesOnPause() {
        // Given
        var paused = false
        setContent(
            uiState = RecordUiState.Recording(elapsedMs = 12_000L, amplitude = 1000),
            onPause = { paused = true },
        )

        // When
        composeTestRule.onNodeWithTag("record_stop_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("record_level_meter").assertIsDisplayed()
        composeTestRule.onNodeWithTag("record_button").assertIsEnabled().performClick()

        // Then
        assertTrue(paused)
    }

    @Test
    fun success_pausedTapInvokesOnResume() {
        // Given
        var resumed = false
        setContent(
            uiState = RecordUiState.Paused(elapsedMs = 5_000L),
            onResume = { resumed = true },
        )

        // When
        composeTestRule.onNodeWithTag("record_level_meter").assertIsDisplayed()
        composeTestRule.onNodeWithTag("record_button").performClick()

        // Then
        assertTrue(resumed)
    }

    @Test
    fun success_recordingStopInvokesOnStop() {
        // Given
        var stopped = false
        setContent(
            uiState = RecordUiState.Recording(elapsedMs = 1_000L, amplitude = 0),
            onStop = { stopped = true },
        )

        // When
        composeTestRule.onNodeWithTag("record_stop_button").assertIsEnabled().performClick()

        // Then
        assertTrue(stopped)
    }

    @Test
    fun success_pausedStopInvokesOnStop() {
        // Given
        var stopped = false
        setContent(
            uiState = RecordUiState.Paused(elapsedMs = 5_000L),
            onStop = { stopped = true },
        )

        // When
        composeTestRule.onNodeWithTag("record_stop_button").assertIsEnabled().performClick()

        // Then
        assertTrue(stopped)
    }

    @Test
    fun success_recordAndStopButtonsHaveRoleButtonAndContentDescription() {
        // Given
        setContent(
            uiState = RecordUiState.Recording(elapsedMs = 1_000L, amplitude = 0),
        )
        val roleButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        val pauseCd = appContext.getString(R.string.cd_record_pause)
        val stopCd = appContext.getString(R.string.cd_record_stop)

        // Then — mergeDescendants + Role.Button + contentDescription
        composeTestRule.onNodeWithTag("record_button")
            .assert(roleButton)
            .assert(contentDescriptionMatcher(pauseCd))
            .assertIsEnabled()
        composeTestRule.onNodeWithTag("record_stop_button")
            .assert(roleButton)
            .assert(contentDescriptionMatcher(stopCd))
            .assertIsEnabled()
    }

    @Test
    fun success_timerShowsMmSs() {
        // Given
        setContent(
            uiState = RecordUiState.Recording(elapsedMs = 65_000L, amplitude = 0),
        )

        // Then
        composeTestRule.onNodeWithTag("record_timer_text").assertTextEquals("01:05")
    }

    @Test
    fun success_timerShowsHMmSs() {
        // Given
        setContent(
            uiState = RecordUiState.Recording(elapsedMs = 3_661_000L, amplitude = 0),
        )

        // Then
        composeTestRule.onNodeWithTag("record_timer_text").assertTextEquals("1:01:01")
    }

    @Test
    fun success_permissionDeniedRequestsPermission() {
        // Given
        var requested = false
        var openedSettings = false
        setContent(
            uiState = RecordUiState.PermissionDenied(canRequestAgain = true),
            onRequestPermission = { requested = true },
            onOpenSettings = { openedSettings = true },
        )
        val requestLabel = appContext.getString(R.string.record_permission_action_request)

        // When
        composeTestRule.onNodeWithTag("record_permission_denied_state").assertIsDisplayed()
        composeTestRule.onNodeWithTag("record_permission_action_button")
            .assert(contentDescriptionMatcher(requestLabel))
            .performClick()

        // Then
        assertTrue(requested)
        assertFalse(openedSettings)
    }

    @Test
    fun success_permissionDeniedOpensSettingsWhenCannotRequestAgain() {
        // Given
        var requested = false
        var openedSettings = false
        setContent(
            uiState = RecordUiState.PermissionDenied(canRequestAgain = false),
            onRequestPermission = { requested = true },
            onOpenSettings = { openedSettings = true },
        )
        val settingsLabel = appContext.getString(R.string.record_permission_action_settings)

        // When
        composeTestRule.onNodeWithTag("record_permission_action_button")
            .assert(contentDescriptionMatcher(settingsLabel))
            .performClick()

        // Then
        assertTrue(openedSettings)
        assertFalse(requested)
    }

    @Test
    fun success_savingStateDisplayed() {
        // Given
        setContent(uiState = RecordUiState.Saving)
        val savingCd = appContext.getString(R.string.cd_record_saving)

        // Then
        composeTestRule.onNodeWithTag("record_saving_state").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(savingCd).assertIsDisplayed()
    }

    @Test
    fun success_savingBlocksTopBarNavigation() {
        // Given — legacy TopBar path (showTopBar=true): Saving 중 뒤로/드로어 비활성
        var navigatedBack = false
        var openedDrawer = false
        setContent(
            uiState = RecordUiState.Saving,
            onNavigateBack = { navigatedBack = true },
            onOpenDrawer = { openedDrawer = true },
            showTopBar = true,
        )

        // Then
        composeTestRule.onNodeWithTag("navigate_back_button").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("open_drawer_button").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("record_navigate_recordings_list_button").assertDoesNotExist()
        assertFalse(navigatedBack)
        assertFalse(openedDrawer)
    }

    @Test
    fun success_isSavingGateBlocksTopBarWhileRecording() {
        // Given — stop() 직후 레이스: uiState=Recording + isSavingGate=true
        // shouldBlockNavigationWhileRecordingSaving SSOT → TopBar 비활성·콜백 불변
        var navigatedBack = false
        var openedDrawer = false
        setContent(
            uiState = RecordUiState.Recording(elapsedMs = 1_000L, amplitude = 0),
            isSavingGate = true,
            onNavigateBack = { navigatedBack = true },
            onOpenDrawer = { openedDrawer = true },
            showTopBar = true,
        )

        // Then — 버튼 disabled (performClick 불가·콜백 불변)
        composeTestRule.onNodeWithTag("navigate_back_button").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("open_drawer_button").assertIsNotEnabled()
        assertFalse(navigatedBack)
        assertFalse(openedDrawer)
    }

    @Test
    fun success_recordingWithoutGate_allowsTopBarNavigation() {
        // Given — Recording + gate=false → 이탈 허용 (FGS 유지); legacy TopBar fixture
        var navigatedBack = false
        setContent(
            uiState = RecordUiState.Recording(elapsedMs = 500L, amplitude = 10),
            isSavingGate = false,
            onNavigateBack = { navigatedBack = true },
            showTopBar = true,
        )

        // When
        composeTestRule.onNodeWithTag("navigate_back_button").assertIsEnabled()
        composeTestRule.onNodeWithTag("navigate_back_button").performClick()
        composeTestRule.waitForIdle()

        // Then
        assertTrue(navigatedBack)
    }

    @Test
    fun success_savingConsumesSystemBack() {
        // Given — outer BackHandler는 Saving의 empty BackHandler에 가로채여 호출되지 않아야 함
        var outerBackCalled = false
        composeTestRule.setContent {
            BackHandler { outerBackCalled = true }
            RecordContent(
                uiState = RecordUiState.Saving,
                onStart = {},
                onPause = {},
                onResume = {},
                onStop = {},
                onRequestPermission = {},
                onOpenSettings = {},
                onRecordAgain = {},
                onDismissError = {},
                onNavigateBack = {},
                onOpenDrawer = {},
                isSavingGate = false,
                isStartEnabled = false,
            )
        }

        // When
        composeTestRule.runOnIdle {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        // Then
        assertFalse(outerBackCalled)
    }

    @Test
    fun success_isSavingGateConsumesSystemBackWhileRecording() {
        // Given — Recording + isSavingGate → BackHandler SSOT가 시스템 Back 소비
        var outerBackCalled = false
        composeTestRule.setContent {
            BackHandler { outerBackCalled = true }
            RecordContent(
                uiState = RecordUiState.Recording(elapsedMs = 2_000L, amplitude = 1),
                onStart = {},
                onPause = {},
                onResume = {},
                onStop = {},
                onRequestPermission = {},
                onOpenSettings = {},
                onRecordAgain = {},
                onDismissError = {},
                onNavigateBack = {},
                onOpenDrawer = {},
                isSavingGate = true,
                isStartEnabled = true,
            )
        }

        // When
        composeTestRule.runOnIdle {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        // Then
        assertFalse(outerBackCalled)
    }

    @Test
    fun success_savedRecordAgainInvokesCallback() {
        // Given
        var again = false
        setContent(
            uiState = RecordUiState.Saved(
                outputFile = File("/tmp/sample.m4a"),
                elapsedMs = 3_000L,
            ),
            onRecordAgain = { again = true },
        )

        // When
        composeTestRule.onNodeWithTag("record_saved_state").assertIsDisplayed()
        composeTestRule.onNodeWithTag("record_timer_text").assertTextEquals("00:03")
        composeTestRule.onNodeWithTag("record_record_again_button").performClick()

        // Then
        assertTrue(again)
    }

    @Test
    fun success_reviewKeepAndDiscardInvokeCallbacks() {
        // Given
        var kept = false
        var discarded = false
        setContent(
            uiState = RecordUiState.Review(
                outputFile = File("/tmp/sample.m4a"),
                elapsedMs = 6_000L,
            ),
            onKeep = { kept = true },
            onDiscard = { discarded = true },
        )

        // When
        composeTestRule.onNodeWithTag("record_review_keep_button").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("record_review_discard_button").assertIsDisplayed().performClick()

        // Then
        assertTrue(kept)
        assertTrue(discarded)
        composeTestRule.onNodeWithTag("record_saved_state").assertDoesNotExist()
        composeTestRule.onNodeWithTag("record_record_again_button").assertDoesNotExist()
    }

    @Test
    fun success_reviewActionsDisabled_keepAndDiscardDoNotFire() {
        // Given — Keep in-flight disables both buttons
        var kept = false
        var discarded = false
        setContent(
            uiState = RecordUiState.Review(
                outputFile = File("/tmp/sample.m4a"),
                elapsedMs = 6_000L,
            ),
            onKeep = { kept = true },
            onDiscard = { discarded = true },
            reviewActionsEnabled = false,
        )

        // When
        composeTestRule.onNodeWithTag("record_review_keep_button").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("record_review_discard_button").assertIsNotEnabled()

        // Then — disabled라 onClick 미발사
        assertFalse(kept)
        assertFalse(discarded)
    }

    @Test
    fun success_reviewHidesStorageRemainingText() {
        // Given
        setContent(
            uiState = RecordUiState.Review(
                outputFile = File("/tmp/sample.m4a"),
                elapsedMs = 6_000L,
            ),
            storageRemainingSeconds = 300L,
        )

        // Then
        composeTestRule.onNodeWithTag("record_storage_remaining_text").assertDoesNotExist()
    }

    @Test
    fun success_savedShowsFileNameNotFullPath() {
        // Given — 절대 경로 File이어도 UI는 name만
        val fileName = "(C2V)2026-08-03_12-00-00.m4a"
        setContent(
            uiState = RecordUiState.Saved(
                outputFile = File("/data/user/0/com.convert2video/files/Music/C2V/$fileName"),
                elapsedMs = 1_500L,
            ),
        )

        // Then — 파일명 + a11y (경로 미포함)
        val expectedCd = composeTestRule.activity.getString(R.string.record_saved_file_cd, fileName)
        composeTestRule.onNodeWithTag("record_saved_file_name")
            .assertIsDisplayed()
            .assertTextEquals(fileName)
        composeTestRule.onNodeWithContentDescription(expectedCd).assertIsDisplayed()
        assertFalse(expectedCd.contains("/data/"))
    }

    @Test
    fun success_idleShowsStorageRemainingWhenSecondsProvided() {
        // Given
        val remainingSeconds = 125L
        setContent(
            uiState = RecordUiState.Idle,
            storageRemainingSeconds = remainingSeconds,
        )

        // Then
        composeTestRule.onNodeWithTag("record_storage_remaining_text")
            .assertIsDisplayed()
            .assertTextEquals(expectedStorageRemainingText(remainingSeconds))
    }

    @Test
    fun success_idleZeroSecondsShowsFormattedDuration() {
        // Given — 0L은 null과 달리 안내 표시
        setContent(
            uiState = RecordUiState.Idle,
            storageRemainingSeconds = 0L,
        )

        // Then
        composeTestRule.onNodeWithTag("record_storage_remaining_text")
            .assertIsDisplayed()
            .assertTextEquals(expectedStorageRemainingText(0L))
    }

    @Test
    fun success_idleNullStorageRemainingHidesText() {
        // Given
        setContent(
            uiState = RecordUiState.Idle,
            storageRemainingSeconds = null,
        )

        // Then
        composeTestRule.onNodeWithTag("record_storage_remaining_text").assertDoesNotExist()
    }

    @Test
    fun success_idleShowsFormatSizeHintForAac() {
        // Given
        setContent(
            uiState = RecordUiState.Idle,
            recordingFormat = RecordingFormat.AAC,
        )

        // Then
        composeTestRule.onNodeWithTag("record_format_size_hint_text")
            .assertIsDisplayed()
            .assertTextEquals(expectedFormatSizeHintText(RecordingFormat.AAC))
    }

    @Test
    fun success_idleShowsFormatSizeHintForWav() {
        // Given
        setContent(
            uiState = RecordUiState.Idle,
            recordingFormat = RecordingFormat.WAV,
        )

        // Then
        composeTestRule.onNodeWithTag("record_format_size_hint_text")
            .assertIsDisplayed()
            .assertTextEquals(expectedFormatSizeHintText(RecordingFormat.WAV))
    }

    @Test
    fun success_idleNullRecordingFormatHidesFormatSizeHintText() {
        // Given
        setContent(
            uiState = RecordUiState.Idle,
            recordingFormat = null,
        )

        // Then
        composeTestRule.onNodeWithTag("record_format_size_hint_text").assertDoesNotExist()
    }

    @Test
    fun success_recordingHidesFormatSizeHintText() {
        // Given
        setContent(
            uiState = RecordUiState.Recording(elapsedMs = 1_000L, amplitude = 0),
            recordingFormat = RecordingFormat.AAC,
        )

        // Then
        composeTestRule.onNodeWithTag("record_format_size_hint_text").assertDoesNotExist()
    }

    @Test
    fun success_idleShowsHourFormatStorageRemaining() {
        // Given — Timer 스타일 1h+ → h:mm:ss (3661초 = 1:01:01)
        val remainingSeconds = 3_661L
        setContent(
            uiState = RecordUiState.Idle,
            storageRemainingSeconds = remainingSeconds,
        )

        // Then
        composeTestRule.onNodeWithTag("record_storage_remaining_text")
            .assertIsDisplayed()
            .assertTextEquals(expectedStorageRemainingText(remainingSeconds))
    }

    @Test
    fun success_permissionDeniedHidesStorageRemainingText() {
        // Given
        setContent(
            uiState = RecordUiState.PermissionDenied(canRequestAgain = true),
            storageRemainingSeconds = 300L,
        )

        // Then
        composeTestRule.onNodeWithTag("record_storage_remaining_text").assertDoesNotExist()
    }

    @Test
    fun success_savingHidesStorageRemainingText() {
        // Given
        setContent(
            uiState = RecordUiState.Saving,
            storageRemainingSeconds = 300L,
        )

        // Then
        composeTestRule.onNodeWithTag("record_storage_remaining_text").assertDoesNotExist()
    }

    @Test
    fun success_idleToRecordingHidesStorageRemainingText() {
        // Given — Idle + 잔여 시간 표시
        var uiState by mutableStateOf<RecordUiState>(RecordUiState.Idle)
        val remainingSeconds = 300L
        composeTestRule.setContent {
            RecordContent(
                uiState = uiState,
                onStart = {},
                onPause = {},
                onResume = {},
                onStop = {},
                onRequestPermission = {},
                onOpenSettings = {},
                onRecordAgain = {},
                onDismissError = {},
                onNavigateBack = {},
                onOpenDrawer = {},
                storageRemainingSeconds = remainingSeconds,
            )
        }

        // Then — Idle에서 tag 존재
        composeTestRule.onNodeWithTag("record_storage_remaining_text")
            .assertIsDisplayed()
            .assertTextEquals(expectedStorageRemainingText(remainingSeconds))

        // When — Recording으로 전환
        composeTestRule.runOnIdle {
            uiState = RecordUiState.Recording(elapsedMs = 0L, amplitude = 0)
        }
        composeTestRule.waitForIdle()

        // Then — tag 사라짐
        composeTestRule.onNodeWithTag("record_storage_remaining_text").assertDoesNotExist()
    }

    @Test
    fun success_recordingHidesStorageRemainingText() {
        // Given
        setContent(
            uiState = RecordUiState.Recording(elapsedMs = 1_000L, amplitude = 0),
            storageRemainingSeconds = 300L,
        )

        // Then
        composeTestRule.onNodeWithTag("record_storage_remaining_text").assertDoesNotExist()
    }

    @Test
    fun success_pausedHidesStorageRemainingText() {
        // Given
        setContent(
            uiState = RecordUiState.Paused(elapsedMs = 5_000L),
            storageRemainingSeconds = 300L,
        )

        // Then
        composeTestRule.onNodeWithTag("record_storage_remaining_text").assertDoesNotExist()
    }

    @Test
    fun success_savedHidesStorageRemainingText() {
        // Given
        setContent(
            uiState = RecordUiState.Saved(
                outputFile = File("/tmp/sample.m4a"),
                elapsedMs = 3_000L,
            ),
            storageRemainingSeconds = 300L,
        )

        // Then
        composeTestRule.onNodeWithTag("record_storage_remaining_text").assertDoesNotExist()
    }

    @Test
    fun success_errorHidesStorageRemainingText() {
        // Given
        setContent(
            uiState = RecordUiState.Error(message = "녹음에 실패했습니다"),
            storageRemainingSeconds = 300L,
        )

        // Then
        composeTestRule.onNodeWithTag("record_storage_remaining_text").assertDoesNotExist()
    }

    @Test
    fun success_errorRetryInvokesDismiss() {
        // Given
        var dismissed = false
        setContent(
            uiState = RecordUiState.Error(message = "녹음에 실패했습니다"),
            onDismissError = { dismissed = true },
        )

        // When
        composeTestRule.onNodeWithTag("record_error_state").assertIsDisplayed()
        composeTestRule.onNodeWithTag("record_retry_button").performClick()

        // Then
        assertTrue(dismissed)
    }
}
