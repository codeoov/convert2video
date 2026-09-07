package com.example.convert2video.ui.screens.options

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.example.convert2video.record.RecordingFormat
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OptionsRecordingFormatContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun success_tappingWavUpdatesSelectedFormatAndRecomposes() {
        // Given
        var selected by mutableStateOf(RecordingFormat.AAC)
        composeTestRule.setContent {
            OptionsRecordingFormatContent(
                recordingFormat = selected,
                onSelectFormat = { selected = it },
            )
        }
        composeTestRule.onNodeWithTag("recording_format_segmented_control").assertIsDisplayed()
        composeTestRule.onNodeWithTag("recording_format_aac").assertIsSelected()
        composeTestRule.onNodeWithTag("recording_format_wav").assertIsNotSelected()

        // When
        composeTestRule.onNodeWithTag("recording_format_wav").performClick()

        // Then: callback + selected semantics with WAV
        composeTestRule.runOnIdle {
            assertEquals(RecordingFormat.WAV, selected)
        }
        composeTestRule.onNodeWithTag("recording_format_wav").assertIsSelected()
        composeTestRule.onNodeWithTag("recording_format_aac").assertIsNotSelected()
    }

    @Test
    fun success_tappingAacAfterWavRestoresAac() {
        // Given
        var selected by mutableStateOf(RecordingFormat.WAV)
        composeTestRule.setContent {
            OptionsRecordingFormatContent(
                recordingFormat = selected,
                onSelectFormat = { selected = it },
            )
        }
        composeTestRule.onNodeWithTag("recording_format_wav").assertIsSelected()

        // When
        composeTestRule.onNodeWithTag("recording_format_aac").performClick()

        // Then
        composeTestRule.runOnIdle {
            assertEquals(RecordingFormat.AAC, selected)
        }
        composeTestRule.onNodeWithTag("recording_format_aac").assertIsSelected()
        composeTestRule.onNodeWithTag("recording_format_wav").assertIsNotSelected()
    }

    @Test
    fun success_nullFormatKeepsTagsButIgnoresSelect() {
        // Given: DataStore 미준비 — SegmentedControl disabled + onSelect no-op
        var selected: RecordingFormat? = null
        composeTestRule.setContent {
            OptionsRecordingFormatContent(
                recordingFormat = null,
                onSelectFormat = { selected = it },
            )
        }

        composeTestRule.onNodeWithTag("recording_format_aac").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("recording_format_wav").assertIsNotEnabled()

        // When
        composeTestRule.onNodeWithTag("recording_format_wav").performClick()

        // Then
        composeTestRule.runOnIdle {
            assertEquals(null, selected)
        }
    }

    @Test
    fun success_infoButtonShowsConfirmOnlyDialog() {
        // Given
        composeTestRule.setContent {
            OptionsRecordingFormatContent(
                recordingFormat = RecordingFormat.AAC,
                onSelectFormat = {},
            )
        }

        // When
        composeTestRule.onNodeWithTag("recording_format_info_button").performClick()

        // Then
        composeTestRule.onNodeWithTag("recording_format_info_confirm_button").assertIsDisplayed()
    }
}
