package com.example.convert2video.ui.screens.options

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.example.convert2video.R
import com.example.convert2video.data.PendingCountdown
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OptionsRecordingCountdownContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val appContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun success_idleShowsCardSteppersStartEnabledAndIdleStatus() {
        composeTestRule.setContent {
            OptionsRecordingCountdownContent(
                startInMinutes = 5,
                durationMinutes = 10,
                pendingCountdown = null,
                nowElapsedMillis = 0L,
                needsExactAlarmPermission = false,
                onStartInDecrement = {},
                onStartInIncrement = {},
                onDurationDecrement = {},
                onDurationIncrement = {},
                onStartClick = {},
                onCancelClick = {},
            )
        }

        composeTestRule.onNodeWithTag("recording_countdown_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("recording_countdown_start_in_decrement").assertIsDisplayed()
        composeTestRule.onNodeWithTag("recording_countdown_start_in_increment").assertIsDisplayed()
        composeTestRule.onNodeWithTag("recording_countdown_duration_decrement").assertIsDisplayed()
        composeTestRule.onNodeWithTag("recording_countdown_duration_increment").assertIsDisplayed()
        composeTestRule.onNodeWithTag("recording_countdown_start_button").assertIsEnabled()
        composeTestRule.onNodeWithTag("recording_countdown_cancel_button").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("recording_countdown_status").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            appContext.getString(R.string.options_recording_countdown_status_idle),
        ).assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("recording_schedule_exact_alarm_banner")
            .assertCountEquals(0)
    }

    @Test
    fun failure_startDisabled_whenNeedsExactAlarmPermission() {
        composeTestRule.setContent {
            OptionsRecordingCountdownContent(
                startInMinutes = 5,
                durationMinutes = 10,
                pendingCountdown = null,
                nowElapsedMillis = 0L,
                needsExactAlarmPermission = true,
                onStartInDecrement = {},
                onStartInIncrement = {},
                onDurationDecrement = {},
                onDurationIncrement = {},
                onStartClick = {},
                onCancelClick = {},
            )
        }

        composeTestRule.onNodeWithTag("recording_countdown_start_button").assertIsNotEnabled()
        composeTestRule.onAllNodesWithTag("recording_schedule_exact_alarm_banner")
            .assertCountEquals(0)
    }

    @Test
    fun success_tappingStartCallsOnStartClick() {
        var starts = 0
        composeTestRule.setContent {
            OptionsRecordingCountdownContent(
                startInMinutes = 5,
                durationMinutes = 10,
                pendingCountdown = null,
                nowElapsedMillis = 0L,
                needsExactAlarmPermission = false,
                onStartInDecrement = {},
                onStartInIncrement = {},
                onDurationDecrement = {},
                onDurationIncrement = {},
                onStartClick = { starts += 1 },
                onCancelClick = {},
            )
        }

        composeTestRule.onNodeWithTag("recording_countdown_start_button").performClick()
        composeTestRule.runOnIdle {
            assertEquals(1, starts)
        }
    }

    @Test
    fun success_pendingWaitingShowsCancelAndWaitingStatus() {
        // Given
        var cancels = 0
        composeTestRule.setContent {
            OptionsRecordingCountdownContent(
                startInMinutes = 5,
                durationMinutes = 10,
                pendingCountdown = PendingCountdown(
                    startElapsedMillis = 5 * 60_000L,
                    durationMinutes = 10,
                    recordingStartedElapsedMillis = null,
                ),
                nowElapsedMillis = 0L,
                needsExactAlarmPermission = false,
                onStartInDecrement = {},
                onStartInIncrement = {},
                onDurationDecrement = {},
                onDurationIncrement = {},
                onStartClick = {},
                onCancelClick = { cancels += 1 },
            )
        }

        composeTestRule.onNodeWithTag("recording_countdown_start_button").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("recording_countdown_cancel_button").assertIsEnabled()
        composeTestRule.onNodeWithText(
            appContext.getString(R.string.options_recording_countdown_status_waiting, 5),
        ).assertIsDisplayed()

        composeTestRule.onNodeWithTag("recording_countdown_cancel_button").performClick()
        composeTestRule.runOnIdle {
            assertEquals(1, cancels)
        }
    }

    @Test
    fun success_recordingStatusWhenStartedElapsedPresent() {
        // Given — 3 minutes elapsed of 8
        composeTestRule.setContent {
            OptionsRecordingCountdownContent(
                startInMinutes = 5,
                durationMinutes = 10,
                pendingCountdown = PendingCountdown(
                    startElapsedMillis = 1_000L,
                    durationMinutes = 8,
                    recordingStartedElapsedMillis = 2_000L,
                ),
                nowElapsedMillis = 2_000L + 3 * 60_000L,
                needsExactAlarmPermission = false,
                onStartInDecrement = {},
                onStartInIncrement = {},
                onDurationDecrement = {},
                onDurationIncrement = {},
                onStartClick = {},
                onCancelClick = {},
            )
        }

        composeTestRule.onNodeWithText(
            appContext.getString(R.string.options_recording_countdown_status_recording, 3, 8),
        ).assertIsDisplayed()
    }

    @Test
    fun success_incrementStartInCallsCallback() {
        var startIn by mutableStateOf(5)
        composeTestRule.setContent {
            OptionsRecordingCountdownContent(
                startInMinutes = startIn,
                durationMinutes = 10,
                pendingCountdown = null,
                nowElapsedMillis = 0L,
                needsExactAlarmPermission = false,
                onStartInDecrement = {},
                onStartInIncrement = { startIn += 1 },
                onDurationDecrement = {},
                onDurationIncrement = {},
                onStartClick = {},
                onCancelClick = {},
            )
        }

        composeTestRule.onNodeWithTag("recording_countdown_start_in_increment").performClick()
        composeTestRule.runOnIdle {
            assertEquals(6, startIn)
        }
    }

    @Test
    fun success_waitingOneMsRemainingShowsOneMinuteNotZero() {
        // Given — 1ms left until START
        composeTestRule.setContent {
            OptionsRecordingCountdownContent(
                startInMinutes = 5,
                durationMinutes = 10,
                pendingCountdown = PendingCountdown(
                    startElapsedMillis = 1L,
                    durationMinutes = 10,
                    recordingStartedElapsedMillis = null,
                ),
                nowElapsedMillis = 0L,
                needsExactAlarmPermission = false,
                onStartInDecrement = {},
                onStartInIncrement = {},
                onDurationDecrement = {},
                onDurationIncrement = {},
                onStartClick = {},
                onCancelClick = {},
            )
        }

        // When / Then — ceil → 1 min copy
        composeTestRule.onNodeWithText(
            appContext.getString(R.string.options_recording_countdown_status_waiting, 1),
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText(
            appContext.getString(R.string.options_recording_countdown_status_waiting, 0),
        ).assertDoesNotExist()
    }

    @Test
    fun success_waitingPastStartShowsSoonNotZeroMinutes() {
        // Given — START elapsed already passed, still waiting (not recording)
        composeTestRule.setContent {
            OptionsRecordingCountdownContent(
                startInMinutes = 5,
                durationMinutes = 10,
                pendingCountdown = PendingCountdown(
                    startElapsedMillis = 1_000L,
                    durationMinutes = 10,
                    recordingStartedElapsedMillis = null,
                ),
                nowElapsedMillis = 5_000L,
                needsExactAlarmPermission = false,
                onStartInDecrement = {},
                onStartInIncrement = {},
                onDurationDecrement = {},
                onDurationIncrement = {},
                onStartClick = {},
                onCancelClick = {},
            )
        }

        // When / Then
        composeTestRule.onNodeWithText(
            appContext.getString(R.string.options_recording_countdown_status_waiting_soon),
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText(
            appContext.getString(R.string.options_recording_countdown_status_waiting, 0),
        ).assertDoesNotExist()
    }

    @Test
    fun success_recordingInvalidDurationDoesNotCrash() {
        // Given — non-positive duration from DataStore
        composeTestRule.setContent {
            OptionsRecordingCountdownContent(
                startInMinutes = 5,
                durationMinutes = 10,
                pendingCountdown = PendingCountdown(
                    startElapsedMillis = 1_000L,
                    durationMinutes = -3,
                    recordingStartedElapsedMillis = 2_000L,
                ),
                nowElapsedMillis = 62_000L,
                needsExactAlarmPermission = false,
                onStartInDecrement = {},
                onStartInIncrement = {},
                onDurationDecrement = {},
                onDurationIncrement = {},
                onStartClick = {},
                onCancelClick = {},
            )
        }

        // When / Then — elapsed helper returns 0; M coerced to 0
        composeTestRule.onNodeWithText(
            appContext.getString(R.string.options_recording_countdown_status_recording, 0, 0),
        ).assertIsDisplayed()
    }
}
