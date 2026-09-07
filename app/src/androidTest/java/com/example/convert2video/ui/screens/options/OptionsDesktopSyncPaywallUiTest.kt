package com.example.convert2video.ui.screens.options

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.convert2video.R
import com.example.convert2video.store.StoreCapabilities
import org.junit.Rule
import org.junit.Test

class OptionsDesktopSyncPaywallUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun success_actualOptionsScreen_hidesDesktopSyncSectionAndPaywall() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        composeTestRule.setContent {
            OptionsScreen(onOpenDrawer = {})
        }

        // Given/When: the actual Options root is rendered.
        composeTestRule.onAllNodesWithText(
            app.getString(R.string.options_desktop_sync_section),
            substring = false,
        ).assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("desktop_sync_server_toggle").assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("paywall_dialog").assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("paywall_title").assertCountEquals(0)
    }

    @Test
    fun success_actualOptionsScreen_keepsUnrelatedUploadSections() {
        composeTestRule.setContent {
            OptionsScreen(onOpenDrawer = {})
        }

        if (StoreCapabilities.current.supportsYouTube) {
            composeTestRule.onNodeWithTag("youtube_auto_upload_switch")
                .performScrollTo()
                .assertIsDisplayed()
        } else {
            composeTestRule.onAllNodesWithTag("youtube_auto_upload_switch").assertCountEquals(0)
        }
        if (StoreCapabilities.current.supportsDrive) {
            composeTestRule.onNodeWithTag("drive_auto_upload_switch")
                .performScrollTo()
                .assertIsDisplayed()
        } else {
            composeTestRule.onAllNodesWithTag("drive_auto_upload_switch").assertCountEquals(0)
        }
    }
}
