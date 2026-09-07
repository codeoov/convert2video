package com.example.convert2video.ui.screens.options

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OptionsRecordingBackupContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun success_allThreeStatesRenderTheirExpectedStatusAndAction() {
        // Given
        var backupState by mutableStateOf<RecordingBackupFolderUiState>(
            RecordingBackupFolderUiState.Unavailable,
        )
        composeTestRule.setContent {
            OptionsRecordingBackupContent(
                backupState = backupState,
                onSelectFolder = {},
                onReconnect = {},
            )
        }

        // Then: no stored URI → select action
        composeTestRule.onNodeWithTag("recording_backup_status_unavailable").assertIsDisplayed()
        composeTestRule.onNodeWithTag("recording_backup_select_folder_button").assertIsDisplayed()

        // When: persisted read/write grant is valid
        composeTestRule.runOnIdle {
            backupState = RecordingBackupFolderUiState.Connected
        }

        // Then: connected status has no reconnect action
        composeTestRule.onNodeWithTag("recording_backup_status_connected").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("recording_backup_reconnect_button").assertCountEquals(0)

        // When: the stored URI loses its persisted grant
        composeTestRule.runOnIdle {
            backupState = RecordingBackupFolderUiState.BrokenGrant
        }

        // Then: reconnect action is available
        composeTestRule.onNodeWithTag("recording_backup_status_broken_grant").assertIsDisplayed()
        composeTestRule.onNodeWithTag("recording_backup_reconnect_button").assertIsDisplayed()
    }

    @Test
    fun success_selectAndReconnectInvokeTheirSeparateCallbacks() {
        // Given
        var backupState by mutableStateOf<RecordingBackupFolderUiState>(
            RecordingBackupFolderUiState.Unavailable,
        )
        var selectCalls = 0
        var reconnectCalls = 0
        composeTestRule.setContent {
            OptionsRecordingBackupContent(
                backupState = backupState,
                onSelectFolder = { selectCalls += 1 },
                onReconnect = { reconnectCalls += 1 },
            )
        }

        // When
        composeTestRule.onNodeWithTag("recording_backup_select_folder_button").performClick()
        composeTestRule.runOnIdle {
            backupState = RecordingBackupFolderUiState.BrokenGrant
        }
        composeTestRule.onNodeWithTag("recording_backup_reconnect_button").performClick()

        // Then
        composeTestRule.runOnIdle {
            assertEquals(1, selectCalls)
            assertEquals(1, reconnectCalls)
        }
    }

    @Test
    fun success_infoDialogOpensAndDismissesFromConfirm() {
        // Given
        composeTestRule.setContent {
            OptionsRecordingBackupContent(
                backupState = RecordingBackupFolderUiState.Connected,
                onSelectFolder = {},
                onReconnect = {},
            )
        }

        // When
        composeTestRule.onNodeWithTag("recording_backup_info_button").performClick()

        // Then
        composeTestRule.onNodeWithTag("recording_backup_info_confirm_button").assertIsDisplayed()

        // When
        composeTestRule.onNodeWithTag("recording_backup_info_confirm_button").performClick()

        // Then
        composeTestRule.onAllNodesWithTag("recording_backup_info_confirm_button").assertCountEquals(0)
    }
}
