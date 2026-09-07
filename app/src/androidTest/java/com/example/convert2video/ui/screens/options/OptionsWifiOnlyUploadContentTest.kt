package com.example.convert2video.ui.screens.options

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OptionsWifiOnlyUploadContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun success_readySwitchIsEnabledAndTogglesCallback() {
        // Given
        var wifiOnly by mutableStateOf(true)
        composeTestRule.setContent {
            OptionsWifiOnlyUploadContent(
                wifiOnlyUpload = wifiOnly,
                onWifiOnlyUploadChange = { wifiOnly = it },
            )
        }

        // Then
        composeTestRule.onNodeWithTag("wifi_only_upload_switch").assertIsDisplayed()
        composeTestRule.onNodeWithTag("wifi_only_upload_switch").assertIsEnabled()

        // When
        composeTestRule.onNodeWithTag("wifi_only_upload_switch").performClick()

        // Then
        composeTestRule.runOnIdle {
            assertEquals(false, wifiOnly)
        }
    }

    @Test
    fun success_nullWifiOnlyKeepsSwitchDisabled() {
        // Given: DataStore 미준비
        var lastChange: Boolean? = null
        composeTestRule.setContent {
            OptionsWifiOnlyUploadContent(
                wifiOnlyUpload = null,
                onWifiOnlyUploadChange = { lastChange = it },
            )
        }

        // Then
        composeTestRule.onNodeWithTag("wifi_only_upload_switch").assertIsDisplayed()
        composeTestRule.onNodeWithTag("wifi_only_upload_switch").assertIsNotEnabled()

        // When: 탭해도 콜백 미호출
        composeTestRule.onNodeWithTag("wifi_only_upload_switch").performClick()

        // Then
        composeTestRule.runOnIdle {
            assertEquals(null, lastChange)
        }
    }
}
