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
import com.example.convert2video.drive.DriveStorageQuota
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OptionsDriveAccountContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val appContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun success_signedOutShowsLoginAndAutoUploadTags() {
        // Given
        composeTestRule.setContent {
            OptionsDriveAccountContent(
                isDriveAuthorized = false,
                driveAccountEmail = null,
                driveAutoUploadEnabled = false,
                isDriveAuthInFlight = false,
                driveFolderName = "TestFolder",
                onLoginClick = {},
                onSignOutClick = {},
                onDriveAutoUploadChange = {},
                onChangeFolderNameClick = {},
            )
        }

        // Then
        composeTestRule.onNodeWithTag("drive_login_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("drive_login_button").assertIsEnabled()
        composeTestRule.onNodeWithTag("drive_auto_upload_switch").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("drive_sign_out_button").assertCountEquals(0)
    }

    @Test
    fun success_authInFlightDisablesLoginButton() {
        // Given — 상태 머신 A: in-flight → 「확인 중…」+ 로그인 비활성
        composeTestRule.setContent {
            OptionsDriveAccountContent(
                isDriveAuthorized = false,
                driveAccountEmail = null,
                driveAutoUploadEnabled = false,
                isDriveAuthInFlight = true,
                driveFolderName = "TestFolder",
                onLoginClick = {},
                onSignOutClick = {},
                onDriveAutoUploadChange = {},
                onChangeFolderNameClick = {},
            )
        }

        // Then
        composeTestRule.onNodeWithTag("drive_login_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("drive_login_button").assertIsNotEnabled()
        composeTestRule.onAllNodesWithTag("drive_sign_out_button").assertCountEquals(0)
    }

    @Test
    fun success_authorizedWithoutEmailKeepsLoginNotSignedInUi() {
        // Given — 상태 머신 A: prefs authorized만으로 로그인됨 UI 금지
        composeTestRule.setContent {
            OptionsDriveAccountContent(
                isDriveAuthorized = true,
                driveAccountEmail = null,
                driveAutoUploadEnabled = false,
                isDriveAuthInFlight = false,
                driveFolderName = "TestFolder",
                onLoginClick = {},
                onSignOutClick = {},
                onDriveAutoUploadChange = {},
                onChangeFolderNameClick = {},
            )
        }

        // Then
        composeTestRule.onNodeWithTag("drive_login_button").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("drive_sign_out_button").assertCountEquals(0)
    }

    @Test
    fun success_signedInShowsSignOutTagAndHidesLogin() {
        // Given
        var signedOut = false
        composeTestRule.setContent {
            OptionsDriveAccountContent(
                isDriveAuthorized = true,
                driveAccountEmail = "user@example.com",
                driveAutoUploadEnabled = true,
                isDriveAuthInFlight = false,
                driveFolderName = "TestFolder",
                onLoginClick = {},
                onSignOutClick = { signedOut = true },
                onDriveAutoUploadChange = {},
                onChangeFolderNameClick = {},
            )
        }

        // When
        composeTestRule.onNodeWithTag("drive_sign_out_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("drive_sign_out_button").performClick()

        // Then
        composeTestRule.onAllNodesWithTag("drive_login_button").assertCountEquals(0)
        composeTestRule.runOnIdle {
            assertTrue(signedOut)
        }
    }

    @Test
    fun success_autoUploadSwitchInvokesCallbackWhenReady() {
        // Given
        var enabled by mutableStateOf(false)
        var lastChange: Boolean? = null
        composeTestRule.setContent {
            OptionsDriveAccountContent(
                isDriveAuthorized = false,
                driveAccountEmail = null,
                driveAutoUploadEnabled = enabled,
                isDriveAuthInFlight = false,
                driveFolderName = "TestFolder",
                onLoginClick = {},
                onSignOutClick = {},
                onDriveAutoUploadChange = {
                    lastChange = it
                    enabled = it
                },
                onChangeFolderNameClick = {},
            )
        }
        composeTestRule.onNodeWithTag("drive_auto_upload_switch").assertIsEnabled()

        // When
        composeTestRule.onNodeWithTag("drive_auto_upload_switch").performClick()

        // Then
        composeTestRule.runOnIdle {
            assertEquals(true, lastChange)
        }
    }

    @Test
    fun success_nullAutoUploadKeepsSwitchDisabled() {
        // Given: DataStore 미준비
        composeTestRule.setContent {
            OptionsDriveAccountContent(
                isDriveAuthorized = false,
                driveAccountEmail = null,
                driveAutoUploadEnabled = null,
                isDriveAuthInFlight = false,
                driveFolderName = "TestFolder",
                onLoginClick = {},
                onSignOutClick = {},
                onDriveAutoUploadChange = {},
                onChangeFolderNameClick = {},
            )
        }

        // Then
        composeTestRule.onNodeWithTag("drive_auto_upload_switch").assertIsDisplayed()
        composeTestRule.onNodeWithTag("drive_auto_upload_switch").assertIsNotEnabled()
    }

    @Test
    fun success_signedInShowsFolderNameLabel() {
        // Given: 로그인 상태에서 폴더 이름 라벨/값이 노출되어야 한다
        composeTestRule.setContent {
            OptionsDriveAccountContent(
                isDriveAuthorized = true,
                driveAccountEmail = "user@example.com",
                driveAutoUploadEnabled = true,
                isDriveAuthInFlight = false,
                driveFolderName = "TestFolder",
                onLoginClick = {},
                onSignOutClick = {},
                onDriveAutoUploadChange = {},
                onChangeFolderNameClick = {},
            )
        }

        // Then: 폴더 이름 행과 "변경" 버튼이 화면에 표시된다
        composeTestRule.onNodeWithTag("drive_folder_name_row").assertIsDisplayed()
        composeTestRule.onNodeWithText("TestFolder", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("drive_folder_name_change_button").assertIsDisplayed()
    }

    @Test
    fun success_changeFolderNameButtonInvokesCallback() {
        // Given
        var changeClicked = false
        composeTestRule.setContent {
            OptionsDriveAccountContent(
                isDriveAuthorized = true,
                driveAccountEmail = "user@example.com",
                driveAutoUploadEnabled = true,
                isDriveAuthInFlight = false,
                driveFolderName = "TestFolder",
                onLoginClick = {},
                onSignOutClick = {},
                onDriveAutoUploadChange = {},
                onChangeFolderNameClick = { changeClicked = true },
            )
        }

        // When
        composeTestRule.onNodeWithTag("drive_folder_name_change_button").performClick()

        // Then
        composeTestRule.runOnIdle {
            assertTrue(changeClicked)
        }
    }

    @Test
    fun success_signedInShowsQuotaPercentWhenLimitPresent() {
        // Given — 50 GB / 100 GB = 50%
        composeTestRule.setContent {
            OptionsDriveAccountContent(
                isDriveAuthorized = true,
                driveAccountEmail = "user@example.com",
                driveAutoUploadEnabled = true,
                isDriveAuthInFlight = false,
                driveFolderName = "TestFolder",
                onLoginClick = {},
                onSignOutClick = {},
                onDriveAutoUploadChange = {},
                onChangeFolderNameClick = {},
                driveStorageQuota = DriveStorageQuota(
                    usageBytes = 50_000_000_000L,
                    limitBytes = 100_000_000_000L,
                ),
            )
        }

        // Then
        composeTestRule.onNodeWithTag("drive_storage_quota_indicator").assertIsDisplayed()
        composeTestRule.onNodeWithTag("drive_storage_quota_label").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            appContext.getString(
                R.string.options_drive_storage_used_percent,
                50,
                "50 GB",
                "100 GB",
            ),
        ).assertIsDisplayed()
    }

    @Test
    fun success_signedInShowsQuotaUnlimitedWhenLimitNull() {
        // Given — unlimited: label only, no indeterminate spinner
        composeTestRule.setContent {
            OptionsDriveAccountContent(
                isDriveAuthorized = true,
                driveAccountEmail = "user@example.com",
                driveAutoUploadEnabled = true,
                isDriveAuthInFlight = false,
                driveFolderName = "TestFolder",
                onLoginClick = {},
                onSignOutClick = {},
                onDriveAutoUploadChange = {},
                onChangeFolderNameClick = {},
                driveStorageQuota = DriveStorageQuota(
                    usageBytes = 1_000L,
                    limitBytes = null,
                ),
            )
        }

        // Then
        composeTestRule.onAllNodesWithTag("drive_storage_quota_indicator").assertCountEquals(0)
        composeTestRule.onNodeWithTag("drive_storage_quota_label").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            appContext.getString(R.string.options_drive_storage_used_unlimited, "1 KB"),
        ).assertIsDisplayed()
    }

    @Test
    fun success_signedOutHidesQuotaEvenWhenQuotaProvided() {
        // Given
        composeTestRule.setContent {
            OptionsDriveAccountContent(
                isDriveAuthorized = false,
                driveAccountEmail = null,
                driveAutoUploadEnabled = false,
                isDriveAuthInFlight = false,
                driveFolderName = "TestFolder",
                onLoginClick = {},
                onSignOutClick = {},
                onDriveAutoUploadChange = {},
                onChangeFolderNameClick = {},
                driveStorageQuota = DriveStorageQuota(
                    usageBytes = 50_000_000_000L,
                    limitBytes = 100_000_000_000L,
                ),
            )
        }

        // Then
        composeTestRule.onAllNodesWithTag("drive_storage_quota_indicator").assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("drive_storage_quota_label").assertCountEquals(0)
    }

    @Test
    fun success_signedInHidesQuotaWhenQuotaNull() {
        // Given
        composeTestRule.setContent {
            OptionsDriveAccountContent(
                isDriveAuthorized = true,
                driveAccountEmail = "user@example.com",
                driveAutoUploadEnabled = true,
                isDriveAuthInFlight = false,
                driveFolderName = "TestFolder",
                onLoginClick = {},
                onSignOutClick = {},
                onDriveAutoUploadChange = {},
                onChangeFolderNameClick = {},
                driveStorageQuota = null,
            )
        }

        // Then
        composeTestRule.onAllNodesWithTag("drive_storage_quota_indicator").assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("drive_storage_quota_label").assertCountEquals(0)
    }

    @Test
    fun success_signedInHidesQuotaWhenLimitBytesNonPositive() {
        // Given — limitBytes <= 0 is invalid, not unlimited
        composeTestRule.setContent {
            OptionsDriveAccountContent(
                isDriveAuthorized = true,
                driveAccountEmail = "user@example.com",
                driveAutoUploadEnabled = true,
                isDriveAuthInFlight = false,
                driveFolderName = "TestFolder",
                onLoginClick = {},
                onSignOutClick = {},
                onDriveAutoUploadChange = {},
                onChangeFolderNameClick = {},
                driveStorageQuota = DriveStorageQuota(
                    usageBytes = 50_000_000_000L,
                    limitBytes = 0L,
                ),
            )
        }

        // Then
        composeTestRule.onAllNodesWithTag("drive_storage_quota_indicator").assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("drive_storage_quota_label").assertCountEquals(0)
    }

    @Test
    fun success_signedInQuotaPercentUsesRoundToInt() {
        // Given — 996/1000 = 99.6% → roundToInt 100, not toInt 99
        val unitGb = appContext.getString(R.string.options_drive_storage_unit_gb)
        composeTestRule.setContent {
            OptionsDriveAccountContent(
                isDriveAuthorized = true,
                driveAccountEmail = "user@example.com",
                driveAutoUploadEnabled = true,
                isDriveAuthInFlight = false,
                driveFolderName = "TestFolder",
                onLoginClick = {},
                onSignOutClick = {},
                onDriveAutoUploadChange = {},
                onChangeFolderNameClick = {},
                driveStorageQuota = DriveStorageQuota(
                    usageBytes = 996_000_000_000L,
                    limitBytes = 1_000_000_000_000L,
                ),
            )
        }

        // Then
        composeTestRule.onNodeWithTag("drive_storage_quota_indicator").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            appContext.getString(
                R.string.options_drive_storage_used_percent,
                100,
                "996 $unitGb",
                "1000 $unitGb",
            ),
        ).assertIsDisplayed()
    }
}
