package com.example.convert2video.ui.screens.options

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.example.convert2video.desktopsync.ServerState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OptionsDesktopPairingContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun success_serverStopped_toggleIsOff() {
        // Given: 서버 Stopped → toggle unchecked
        composeTestRule.setContent {
            OptionsDesktopPairingContent(
                serverState = ServerState.Stopped,
                isPaired = false,
                pairedDeviceName = null,
                onToggleServer = {},
                onUnpairClick = {},
            )
        }

        // Then
        composeTestRule.onNodeWithTag("desktop_sync_server_toggle").assertIsDisplayed()
        composeTestRule.onNodeWithTag("desktop_sync_server_toggle").assertIsOff()
        composeTestRule.onAllNodesWithTag("desktop_sync_status_text").assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("desktop_sync_unpair_button").assertCountEquals(0)
    }

    @Test
    fun success_serverRunning_toggleIsOn_andStatusVisible() {
        // Given: 서버 Running → toggle checked + enabled + 상태 텍스트 노출
        composeTestRule.setContent {
            OptionsDesktopPairingContent(
                serverState = ServerState.Running,
                isPaired = false,
                pairedDeviceName = null,
                onToggleServer = {},
                onUnpairClick = {},
            )
        }

        // Then: N-5 — Running 중 toggle은 enabled
        composeTestRule.onNodeWithTag("desktop_sync_server_toggle").assertIsDisplayed()
        composeTestRule.onNodeWithTag("desktop_sync_server_toggle").assertIsOn()
        composeTestRule.onNodeWithTag("desktop_sync_server_toggle").assertIsEnabled()
        composeTestRule.onNodeWithTag("desktop_sync_status_text").assertIsDisplayed()
    }

    @Test
    fun success_serverStarting_toggleIsOn_andStatusVisible() {
        // Given: 서버 Starting → toggle checked + disabled + 상태 텍스트 노출, 대기 힌트 없음
        composeTestRule.setContent {
            OptionsDesktopPairingContent(
                serverState = ServerState.Starting,
                isPaired = false,
                pairedDeviceName = null,
                onToggleServer = {},
                onUnpairClick = {},
            )
        }

        // Then: N-5 — Starting 중 toggle은 checked이고 disabled
        composeTestRule.onNodeWithTag("desktop_sync_server_toggle").assertIsOn()
        composeTestRule.onNodeWithTag("desktop_sync_server_toggle").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("desktop_sync_status_text").assertIsDisplayed()
        // N-4/G-8: Starting 중 waiting hint 미표시
        composeTestRule.onAllNodesWithTag("desktop_sync_waiting_hint").assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("desktop_sync_unpair_button").assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("desktop_sync_paired_device_name").assertCountEquals(0)
    }

    @Test
    fun success_serverFailed_toggleIsOff_andStatusVisible() {
        // Given: 서버 Failed → toggle unchecked + 실패 상태 텍스트 노출
        composeTestRule.setContent {
            OptionsDesktopPairingContent(
                serverState = ServerState.Failed,
                isPaired = false,
                pairedDeviceName = null,
                onToggleServer = {},
                onUnpairClick = {},
            )
        }

        // Then
        composeTestRule.onNodeWithTag("desktop_sync_server_toggle").assertIsOff()
        composeTestRule.onNodeWithTag("desktop_sync_status_text").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("desktop_sync_unpair_button").assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("desktop_sync_waiting_hint").assertCountEquals(0)
    }

    @Test
    fun success_pairedShowsDeviceNameAndUnpairButton() {
        // Given: isPaired true → 기기명 + Unpair 버튼 노출
        composeTestRule.setContent {
            OptionsDesktopPairingContent(
                serverState = ServerState.Running,
                isPaired = true,
                pairedDeviceName = "My MacBook",
                onToggleServer = {},
                onUnpairClick = {},
            )
        }

        // Then
        composeTestRule.onNodeWithTag("desktop_sync_paired_device_name").assertIsDisplayed()
        composeTestRule.onNodeWithTag("desktop_sync_unpair_button").assertIsDisplayed()
        // isPaired true이면 대기 힌트 미표시
        composeTestRule.onAllNodesWithTag("desktop_sync_waiting_hint").assertCountEquals(0)
    }

    @Test
    fun success_unpairButtonInvokesCallback() {
        // Given
        var unpaired = false
        composeTestRule.setContent {
            OptionsDesktopPairingContent(
                serverState = ServerState.Running,
                isPaired = true,
                pairedDeviceName = "Desktop PC",
                onToggleServer = {},
                onUnpairClick = { unpaired = true },
            )
        }

        // When
        composeTestRule.onNodeWithTag("desktop_sync_unpair_button").performClick()

        // Then
        composeTestRule.runOnIdle {
            assertTrue(unpaired)
        }
    }

    @Test
    fun success_unpairedAndRunning_showsWaitingHint() {
        // Given: 페어링 없음 + 서버 Running → 대기 힌트 표시
        composeTestRule.setContent {
            OptionsDesktopPairingContent(
                serverState = ServerState.Running,
                isPaired = false,
                pairedDeviceName = null,
                onToggleServer = {},
                onUnpairClick = {},
            )
        }

        // Then: N-4 — waiting hint 노출
        composeTestRule.onNodeWithTag("desktop_sync_waiting_hint").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("desktop_sync_unpair_button").assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("desktop_sync_paired_device_name").assertCountEquals(0)
    }

    @Test
    fun success_unpairedAndStopped_hidesWaitingHint() {
        // Given: 페어링 없음 + 서버 Stopped → 힌트 없음, Unpair 없음
        composeTestRule.setContent {
            OptionsDesktopPairingContent(
                serverState = ServerState.Stopped,
                isPaired = false,
                pairedDeviceName = null,
                onToggleServer = {},
                onUnpairClick = {},
            )
        }

        // Then
        composeTestRule.onAllNodesWithTag("desktop_sync_waiting_hint").assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("desktop_sync_unpair_button").assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("desktop_sync_paired_device_name").assertCountEquals(0)
    }

    @Test
    fun success_toggleCallbackInvoked() {
        // Given
        var toggleValue: Boolean? = null
        var serverState by mutableStateOf<ServerState>(ServerState.Stopped)
        composeTestRule.setContent {
            OptionsDesktopPairingContent(
                serverState = serverState,
                isPaired = false,
                pairedDeviceName = null,
                onToggleServer = { on ->
                    toggleValue = on
                    serverState = if (on) ServerState.Running else ServerState.Stopped
                },
                onUnpairClick = {},
            )
        }

        // When
        composeTestRule.onNodeWithTag("desktop_sync_server_toggle").performClick()

        // Then
        composeTestRule.runOnIdle {
            assertTrue(toggleValue == true)
        }
    }
}
