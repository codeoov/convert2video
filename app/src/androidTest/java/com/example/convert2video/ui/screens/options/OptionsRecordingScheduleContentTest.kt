package com.example.convert2video.ui.screens.options

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
import com.example.convert2video.data.RecordingSchedule
import com.example.convert2video.record.RecordingScheduleRepeatMode
import com.example.convert2video.ui.components.pickers.formatScheduleTimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class OptionsRecordingScheduleContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val appContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun success_emptyStateShowsCalendarIconAndLabel() {
        // Given
        composeTestRule.setContent {
            OptionsRecordingScheduleContent(
                schedules = emptyList(),
                onAddClick = {},
                onEditClick = {},
                onDeleteClick = {},
                onEnabledChange = { _, _ -> },
            )
        }

        // Then
        composeTestRule.onNodeWithTag("recording_schedule_list").assertIsDisplayed()
        composeTestRule.onNodeWithTag("recording_schedule_empty_state").assertIsDisplayed()
        composeTestRule.onNodeWithTag("recording_schedule_empty_icon").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            appContext.getString(R.string.options_recording_schedule_empty),
        ).assertIsDisplayed()
        composeTestRule.onNodeWithTag("recording_schedule_add_button").assertIsEnabled()
    }

    @Test
    fun success_onceRowShowsLargeTimeAndRepeatSummary() {
        // Given
        val schedule = sampleSchedule(
            id = 11L,
            startMinuteOfDay = 7 * 60,
            endMinuteOfDay = 7 * 60 + 30,
            repeatMode = RecordingScheduleRepeatMode.ONCE.name,
        )
        composeTestRule.setContent {
            OptionsRecordingScheduleContent(
                schedules = listOf(schedule),
                onAddClick = {},
                onEditClick = {},
                onDeleteClick = {},
                onEnabledChange = { _, _ -> },
            )
        }

        // Then: 행 카드 + 시간 강조 + 반복 라벨. 빈 상태는 없음.
        composeTestRule.onNodeWithTag("recording_schedule_row_11").assertIsDisplayed()
        composeTestRule.onNodeWithTag("recording_schedule_repeat_icon_11").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            formatScheduleTimeRange(7 * 60, 7 * 60 + 30),
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText(
            appContext.getString(R.string.options_recording_schedule_once),
        ).assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("recording_schedule_empty_state").assertCountEquals(0)
    }

    @Test
    fun success_weeklyAndDailyRowsShowDistinctRepeatIcons() {
        // Given
        val weekly = sampleSchedule(
            id = 2L,
            startMinuteOfDay = 9 * 60,
            endMinuteOfDay = 10 * 60,
            repeatMode = RecordingScheduleRepeatMode.WEEKLY.name,
            daysOfWeekMask = 1 shl 0,
        )
        val daily = sampleSchedule(
            id = 3L,
            startMinuteOfDay = 22 * 60,
            endMinuteOfDay = 6 * 60,
            repeatMode = RecordingScheduleRepeatMode.DAILY.name,
        )
        composeTestRule.setContent {
            OptionsRecordingScheduleContent(
                schedules = listOf(weekly, daily),
                onAddClick = {},
                onEditClick = {},
                onDeleteClick = {},
                onEnabledChange = { _, _ -> },
            )
        }

        // Then
        composeTestRule.onNodeWithTag("recording_schedule_repeat_icon_2").assertIsDisplayed()
        composeTestRule.onNodeWithTag("recording_schedule_repeat_icon_3").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            formatScheduleTimeRange(22 * 60, 6 * 60),
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText(
            appContext.getString(R.string.options_recording_schedule_daily),
        ).assertIsDisplayed()
    }

    @Test
    fun success_tappingTimeRangeCallsOnEdit() {
        // Given
        val schedule = sampleSchedule(
            id = 4L,
            startMinuteOfDay = 8 * 60,
            endMinuteOfDay = 8 * 60 + 15,
            repeatMode = RecordingScheduleRepeatMode.ONCE.name,
        )
        var edited: RecordingSchedule? = null
        composeTestRule.setContent {
            OptionsRecordingScheduleContent(
                schedules = listOf(schedule),
                onAddClick = {},
                onEditClick = { edited = it },
                onDeleteClick = {},
                onEnabledChange = { _, _ -> },
            )
        }

        // When
        composeTestRule.onNodeWithText(
            formatScheduleTimeRange(8 * 60, 8 * 60 + 15),
        ).performClick()

        // Then
        composeTestRule.runOnIdle {
            assertEquals(schedule, edited)
        }
    }

    @Test
    fun success_switchAndDeleteKeepExistingCallbacks() {
        // Given
        val schedule = sampleSchedule(
            id = 5L,
            startMinuteOfDay = 6 * 60,
            endMinuteOfDay = 7 * 60,
            repeatMode = RecordingScheduleRepeatMode.DAILY.name,
            enabled = true,
        )
        var enabledId: Long? = null
        var enabledValue: Boolean? = null
        var deletedId: Long? = null
        composeTestRule.setContent {
            OptionsRecordingScheduleContent(
                schedules = listOf(schedule),
                onAddClick = {},
                onEditClick = {},
                onDeleteClick = { deletedId = it },
                onEnabledChange = { id, enabled ->
                    enabledId = id
                    enabledValue = enabled
                },
            )
        }

        // When
        composeTestRule.onNodeWithTag("recording_schedule_enabled_5").performClick()
        composeTestRule.onNodeWithTag("recording_schedule_delete_5").performClick()

        // Then
        composeTestRule.runOnIdle {
            assertEquals(5L, enabledId)
            assertEquals(false, enabledValue)
            assertEquals(5L, deletedId)
        }
    }

    @Test
    fun failure_languageApplyingDisablesAddAndIgnoresEdit() {
        // Given
        val schedule = sampleSchedule(
            id = 6L,
            startMinuteOfDay = 12 * 60,
            endMinuteOfDay = 13 * 60,
            repeatMode = RecordingScheduleRepeatMode.ONCE.name,
        )
        var addClicks = 0
        var edited: RecordingSchedule? = null
        composeTestRule.setContent {
            OptionsRecordingScheduleContent(
                schedules = listOf(schedule),
                onAddClick = { addClicks++ },
                onEditClick = { edited = it },
                onDeleteClick = {},
                onEnabledChange = { _, _ -> },
                isLanguageApplying = true,
            )
        }

        // When
        composeTestRule.onNodeWithTag("recording_schedule_add_button").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("recording_schedule_add_button").performClick()
        composeTestRule.onNodeWithText(
            formatScheduleTimeRange(12 * 60, 13 * 60),
        ).performClick()

        // Then
        composeTestRule.runOnIdle {
            assertEquals(0, addClicks)
            assertNull(edited)
        }
    }

    private fun sampleSchedule(
        id: Long,
        startMinuteOfDay: Int,
        endMinuteOfDay: Int,
        repeatMode: String,
        daysOfWeekMask: Int = 0,
        enabled: Boolean = true,
    ): RecordingSchedule = RecordingSchedule(
        id = id,
        startMinuteOfDay = startMinuteOfDay,
        endMinuteOfDay = endMinuteOfDay,
        repeatMode = repeatMode,
        daysOfWeekMask = daysOfWeekMask,
        enabled = enabled,
        createdAt = 0L,
    )
}
