package com.example.convert2video.ui.screens.options

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.example.convert2video.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OptionsYoutubeAccountContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val appContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun success_signedOutShowsLoginButtonAndHidesSignOut() {
        // Given
        composeTestRule.setContent {
            OptionsYoutubeAccountContent(
                isAuthorized = false,
                channelTitle = null,
                isChannelTitleLoading = false,
                channelTitleFetchFailed = false,
                youtubeAutoUploadEnabled = false,
                onLoginClick = {},
                onSignOutClick = {},
                onRetryClick = {},
                onYoutubeAutoUploadChange = {},
            )
        }

        // When — 별도 조작 없음, signed-out 상태로 컴포지션

        // Then
        composeTestRule.onNodeWithTag("youtube_login_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("youtube_login_button").assertIsEnabled()
        composeTestRule.onAllNodesWithTag("youtube_sign_out_button").assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("youtube_retry_button").assertCountEquals(0)
        composeTestRule.onNodeWithTag("youtube_auto_upload_switch").assertIsDisplayed()
    }

    @Test
    fun success_loadingShowsNoButtonsWhileCheckingChannel() {
        // Given — isAuthorized=true, channelTitle=null, isChannelTitleLoading=true, channelTitleFetchFailed=false
        composeTestRule.setContent {
            OptionsYoutubeAccountContent(
                isAuthorized = true,
                channelTitle = null,
                isChannelTitleLoading = true,
                channelTitleFetchFailed = false,
                youtubeAutoUploadEnabled = false,
                onLoginClick = {},
                onSignOutClick = {},
                onRetryClick = {},
                onYoutubeAutoUploadChange = {},
            )
        }

        // When — 별도 조작 없음; isChannelTitleLoading=true 상태가 UI를 결정한다

        // Then — 로그인/로그아웃/재시도 버튼 전부 없음, 로딩 인디케이터 노출
        composeTestRule.onAllNodesWithTag("youtube_login_button").assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("youtube_sign_out_button").assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("youtube_retry_button").assertCountEquals(0)
        composeTestRule.onNodeWithTag("youtube_channel_loading_indicator").assertIsDisplayed()
    }

    @Test
    fun success_fetchFailedShowsRetryButtonInvokesCallback() {
        // Given
        var retried = false
        composeTestRule.setContent {
            OptionsYoutubeAccountContent(
                isAuthorized = true,
                channelTitle = null,
                isChannelTitleLoading = false,
                channelTitleFetchFailed = true,
                youtubeAutoUploadEnabled = false,
                onLoginClick = {},
                onSignOutClick = {},
                onRetryClick = { retried = true },
                onYoutubeAutoUploadChange = {},
            )
        }

        // When
        composeTestRule.onNodeWithTag("youtube_retry_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("youtube_retry_button").performClick()

        // Then
        composeTestRule.onAllNodesWithTag("youtube_sign_out_button").assertCountEquals(0)
        composeTestRule.runOnIdle {
            assertTrue(retried)
        }
    }

    @Test
    fun success_signedInShowsEmailAndSignOutButton() {
        // Given
        var signedOut = false
        composeTestRule.setContent {
            OptionsYoutubeAccountContent(
                isAuthorized = true,
                channelTitle = "테스트채널",
                youtubeAccountEmail = "user@example.com",
                isChannelTitleLoading = false,
                channelTitleFetchFailed = false,
                youtubeAutoUploadEnabled = false,
                onLoginClick = {},
                onSignOutClick = { signedOut = true },
                onRetryClick = {},
                onYoutubeAutoUploadChange = {},
            )
        }

        // When
        composeTestRule.onNodeWithText(
            appContext.getString(R.string.youtube_signed_in, "user@example.com"),
        ).assertIsDisplayed()
        composeTestRule.onNodeWithTag("youtube_sign_out_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("youtube_sign_out_button").performClick()

        // Then
        composeTestRule.onAllNodesWithTag("youtube_login_button").assertCountEquals(0)
        composeTestRule.runOnIdle {
            assertTrue(signedOut)
        }
    }

    @Test
    fun success_signedInFallsBackToChannelTitleWhenEmailNull() {
        // Given — 게이트는 channelTitle, 표시만 이메일. null → 채널명 폴백
        composeTestRule.setContent {
            OptionsYoutubeAccountContent(
                isAuthorized = true,
                channelTitle = "테스트채널",
                youtubeAccountEmail = null,
                isChannelTitleLoading = false,
                channelTitleFetchFailed = false,
                youtubeAutoUploadEnabled = false,
                onLoginClick = {},
                onSignOutClick = {},
                onRetryClick = {},
                onYoutubeAutoUploadChange = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText(
            appContext.getString(R.string.youtube_signed_in, "테스트채널"),
        ).assertIsDisplayed()
        composeTestRule.onNodeWithTag("youtube_sign_out_button").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("youtube_login_button").assertCountEquals(0)
    }

    @Test
    fun success_signedInFallsBackToChannelTitleWhenEmailBlank() {
        // Given
        composeTestRule.setContent {
            OptionsYoutubeAccountContent(
                isAuthorized = true,
                channelTitle = "테스트채널",
                youtubeAccountEmail = "",
                isChannelTitleLoading = false,
                channelTitleFetchFailed = false,
                youtubeAutoUploadEnabled = false,
                onLoginClick = {},
                onSignOutClick = {},
                onRetryClick = {},
                onYoutubeAutoUploadChange = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText(
            appContext.getString(R.string.youtube_signed_in, "테스트채널"),
        ).assertIsDisplayed()
        composeTestRule.onNodeWithTag("youtube_sign_out_button").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("youtube_login_button").assertCountEquals(0)
    }

    @Test
    fun success_signedInFallsBackToChannelTitleWhenEmailWhitespace() {
        // Given
        composeTestRule.setContent {
            OptionsYoutubeAccountContent(
                isAuthorized = true,
                channelTitle = "테스트채널",
                youtubeAccountEmail = "   ",
                isChannelTitleLoading = false,
                channelTitleFetchFailed = false,
                youtubeAutoUploadEnabled = false,
                onLoginClick = {},
                onSignOutClick = {},
                onRetryClick = {},
                onYoutubeAutoUploadChange = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText(
            appContext.getString(R.string.youtube_signed_in, "테스트채널"),
        ).assertIsDisplayed()
        composeTestRule.onNodeWithTag("youtube_sign_out_button").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("youtube_login_button").assertCountEquals(0)
    }

    @Test
    fun success_autoUploadSwitchInvokesCallbackWhenReady() {
        // Given
        var enabled by mutableStateOf(false)
        var lastChange: Boolean? = null
        composeTestRule.setContent {
            OptionsYoutubeAccountContent(
                isAuthorized = false,
                channelTitle = null,
                isChannelTitleLoading = false,
                channelTitleFetchFailed = false,
                youtubeAutoUploadEnabled = enabled,
                onLoginClick = {},
                onSignOutClick = {},
                onRetryClick = {},
                onYoutubeAutoUploadChange = {
                    lastChange = it
                    enabled = it
                },
            )
        }
        composeTestRule.onNodeWithTag("youtube_auto_upload_switch").assertIsEnabled()

        // When
        composeTestRule.onNodeWithTag("youtube_auto_upload_switch").performClick()

        // Then
        composeTestRule.runOnIdle {
            assertEquals(true, lastChange)
        }
    }

    @Test
    fun success_nullAutoUploadKeepsSwitchDisabled() {
        // Given: DataStore 미준비
        composeTestRule.setContent {
            OptionsYoutubeAccountContent(
                isAuthorized = false,
                channelTitle = null,
                isChannelTitleLoading = false,
                channelTitleFetchFailed = false,
                youtubeAutoUploadEnabled = null,
                onLoginClick = {},
                onSignOutClick = {},
                onRetryClick = {},
                onYoutubeAutoUploadChange = {},
            )
        }

        // Then
        composeTestRule.onNodeWithTag("youtube_auto_upload_switch").assertIsDisplayed()
        composeTestRule.onNodeWithTag("youtube_auto_upload_switch").assertIsNotEnabled()
    }

    @Test
    fun success_autoUploadInfoButtonShowsDialog() {
        // Given
        composeTestRule.setContent {
            OptionsYoutubeAccountContent(
                isAuthorized = false,
                channelTitle = null,
                isChannelTitleLoading = false,
                channelTitleFetchFailed = false,
                youtubeAutoUploadEnabled = false,
                onLoginClick = {},
                onSignOutClick = {},
                onRetryClick = {},
                onYoutubeAutoUploadChange = {},
            )
        }

        // When
        composeTestRule.onNodeWithTag("youtube_auto_upload_info_button").performClick()

        // Then
        composeTestRule.onNodeWithTag("youtube_auto_upload_info_confirm_button").assertIsDisplayed()
    }

    @Test
    fun success_signedInKeepsAutoUploadSwitchEnabled() {
        // Given — signed-in + youtubeAutoUploadEnabled=false(비null) → Switch는 로그인 무관하게 enabled
        composeTestRule.setContent {
            OptionsYoutubeAccountContent(
                isAuthorized = true,
                channelTitle = "테스트채널",
                isChannelTitleLoading = false,
                channelTitleFetchFailed = false,
                youtubeAutoUploadEnabled = false,
                onLoginClick = {},
                onSignOutClick = {},
                onRetryClick = {},
                onYoutubeAutoUploadChange = {},
            )
        }

        // When — 별도 조작 없음; signed-in + 토글 값 확정

        // Then
        composeTestRule.onNodeWithTag("youtube_sign_out_button").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("youtube_login_button").assertCountEquals(0)
        composeTestRule.onNodeWithTag("youtube_auto_upload_switch").assertIsDisplayed()
        composeTestRule.onNodeWithTag("youtube_auto_upload_switch").assertIsEnabled()
    }

    @Test
    fun success_languageApplyingDoesNotDisableAutoUploadSwitch() {
        // Given — 미로그인 + isLanguageApplying=true + 토글 값 확정
        composeTestRule.setContent {
            OptionsYoutubeAccountContent(
                isAuthorized = false,
                channelTitle = null,
                isChannelTitleLoading = false,
                channelTitleFetchFailed = false,
                youtubeAutoUploadEnabled = false,
                onLoginClick = {},
                onSignOutClick = {},
                onRetryClick = {},
                onYoutubeAutoUploadChange = {},
                isLanguageApplying = true,
            )
        }

        // When — 별도 조작 없음; language apply 중에도 Switch 게이트는 토글 값만

        // Then — Switch는 enabled, 로그인 버튼만 not enabled
        composeTestRule.onNodeWithTag("youtube_auto_upload_switch").assertIsDisplayed()
        composeTestRule.onNodeWithTag("youtube_auto_upload_switch").assertIsEnabled()
        composeTestRule.onNodeWithTag("youtube_login_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("youtube_login_button").assertIsNotEnabled()
    }

    @Test
    fun success_authorizedWaitingShowsCheckingWithoutIndicator() {
        // Given — live when: isAuthorized && !isYoutubeSignedInUi && !channelTitleFetchFailed && !isChannelTitleLoading
        composeTestRule.setContent {
            OptionsYoutubeAccountContent(
                isAuthorized = true,
                channelTitle = null,
                isChannelTitleLoading = false,
                channelTitleFetchFailed = false,
                youtubeAutoUploadEnabled = false,
                onLoginClick = {},
                onSignOutClick = {},
                onRetryClick = {},
                onYoutubeAutoUploadChange = {},
            )
        }

        // When — 별도 조작 없음; authorized+title 미확정+!loading 대기 분기

        // Then — 확인 중 텍스트만, 인디케이터·로그인/로그아웃/재시도 없음
        composeTestRule.onNodeWithText(appContext.getString(R.string.youtube_channel_checking))
            .assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("youtube_channel_loading_indicator").assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("youtube_login_button").assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("youtube_sign_out_button").assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("youtube_retry_button").assertCountEquals(0)
    }

    @Test
    fun success_languageApplyingKeepsSignOutAndInfoEnabled() {
        // Given — signed-in + isLanguageApplying=true + youtubeAutoUploadEnabled non-null
        composeTestRule.setContent {
            OptionsYoutubeAccountContent(
                isAuthorized = true,
                channelTitle = "테스트채널",
                isChannelTitleLoading = false,
                channelTitleFetchFailed = false,
                youtubeAutoUploadEnabled = false,
                onLoginClick = {},
                onSignOutClick = {},
                onRetryClick = {},
                onYoutubeAutoUploadChange = {},
                isLanguageApplying = true,
            )
        }

        // When — 별도 조작 없음; language apply는 로그인 버튼만 잠금

        // Then — 로그아웃·info·Switch는 enabled, 로그인 버튼 없음
        composeTestRule.onNodeWithTag("youtube_sign_out_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("youtube_sign_out_button").assertIsEnabled()
        composeTestRule.onNodeWithTag("youtube_auto_upload_info_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("youtube_auto_upload_info_button").assertIsEnabled()
        composeTestRule.onNodeWithTag("youtube_auto_upload_switch").assertIsEnabled()
        composeTestRule.onAllNodesWithTag("youtube_login_button").assertCountEquals(0)
    }

    @Test
    fun success_autoUploadInfoConfirmDismissesDialog() {
        // Given
        composeTestRule.setContent {
            OptionsYoutubeAccountContent(
                isAuthorized = false,
                channelTitle = null,
                isChannelTitleLoading = false,
                channelTitleFetchFailed = false,
                youtubeAutoUploadEnabled = false,
                onLoginClick = {},
                onSignOutClick = {},
                onRetryClick = {},
                onYoutubeAutoUploadChange = {},
            )
        }

        // When
        composeTestRule.onNodeWithTag("youtube_auto_upload_info_button").performClick()
        composeTestRule.onNodeWithTag("youtube_auto_upload_info_confirm_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("youtube_auto_upload_info_confirm_button")
            .assertTextEquals(appContext.getString(R.string.action_confirm))
        composeTestRule.onNodeWithTag("youtube_auto_upload_info_confirm_button").performClick()

        // Then
        composeTestRule.runOnIdle {
            composeTestRule.onAllNodesWithTag("youtube_auto_upload_info_confirm_button")
                .assertCountEquals(0)
        }
    }

    @Test
    fun success_signedInAutoUploadSwitchInvokesCallback() {
        // Given — signed-in + youtubeAutoUploadEnabled=false → Switch click → callback true
        var lastChange: Boolean? = null
        composeTestRule.setContent {
            OptionsYoutubeAccountContent(
                isAuthorized = true,
                channelTitle = "테스트채널",
                isChannelTitleLoading = false,
                channelTitleFetchFailed = false,
                youtubeAutoUploadEnabled = false,
                onLoginClick = {},
                onSignOutClick = {},
                onRetryClick = {},
                onYoutubeAutoUploadChange = { lastChange = it },
            )
        }

        // When
        composeTestRule.onNodeWithTag("youtube_auto_upload_switch").assertIsEnabled()
        composeTestRule.onNodeWithTag("youtube_auto_upload_switch").performClick()

        // Then
        composeTestRule.runOnIdle {
            assertEquals(true, lastChange)
        }
    }
}
