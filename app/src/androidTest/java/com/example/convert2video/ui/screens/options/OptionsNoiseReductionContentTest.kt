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
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.example.convert2video.R
import com.example.convert2video.record.NoiseReductionMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OptionsNoiseReductionContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val appContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun success_initialDeviceDefaultShowsCorrectSelection() {
        // Given: DeviceDefault(ordinal=0) 초기 상태
        var selected by mutableStateOf(NoiseReductionMode.DeviceDefault)
        composeTestRule.setContent {
            OptionsNoiseReductionContent(
                noiseReductionMode = selected,
                onSelectMode = { selected = it },
            )
        }

        // Then: device_default 선택됨, on/off 미선택
        composeTestRule.onNodeWithTag("noise_reduction_segmented_control").assertIsDisplayed()
        composeTestRule.onNodeWithTag("noise_reduction_device_default").assertIsSelected()
        composeTestRule.onNodeWithTag("noise_reduction_on").assertIsNotSelected()
        composeTestRule.onNodeWithTag("noise_reduction_off").assertIsNotSelected()
    }

    @Test
    fun success_tappingOnSwitchesSelectionAndCallsCallback() {
        // Given
        var selected by mutableStateOf(NoiseReductionMode.DeviceDefault)
        composeTestRule.setContent {
            OptionsNoiseReductionContent(
                noiseReductionMode = selected,
                onSelectMode = { selected = it },
            )
        }
        composeTestRule.onNodeWithTag("noise_reduction_device_default").assertIsSelected()

        // When
        composeTestRule.onNodeWithTag("noise_reduction_on").performClick()

        // Then: callback + selected semantics with On
        composeTestRule.runOnIdle {
            assertEquals(NoiseReductionMode.On, selected)
        }
        composeTestRule.onNodeWithTag("noise_reduction_on").assertIsSelected()
        composeTestRule.onNodeWithTag("noise_reduction_device_default").assertIsNotSelected()
        composeTestRule.onNodeWithTag("noise_reduction_off").assertIsNotSelected()
    }

    @Test
    fun success_tappingOffAfterOnSwitchesToOff() {
        // Given
        var selected by mutableStateOf(NoiseReductionMode.On)
        composeTestRule.setContent {
            OptionsNoiseReductionContent(
                noiseReductionMode = selected,
                onSelectMode = { selected = it },
            )
        }
        composeTestRule.onNodeWithTag("noise_reduction_on").assertIsSelected()

        // When
        composeTestRule.onNodeWithTag("noise_reduction_off").performClick()

        // Then: callback + selected semantics with Off
        composeTestRule.runOnIdle {
            assertEquals(NoiseReductionMode.Off, selected)
        }
        composeTestRule.onNodeWithTag("noise_reduction_off").assertIsSelected()
        composeTestRule.onNodeWithTag("noise_reduction_on").assertIsNotSelected()
        composeTestRule.onNodeWithTag("noise_reduction_device_default").assertIsNotSelected()
    }

    @Test
    fun success_wavOnlyHintIsAlwaysDisplayedRegardlessOfMode() {
        // Given/When — AAC에서도 그대로 보여 "켜도 효과 없음"을 사용자가 오해하지 않게 함
        composeTestRule.setContent {
            OptionsNoiseReductionContent(
                noiseReductionMode = NoiseReductionMode.On,
                onSelectMode = {},
            )
        }

        // Then
        composeTestRule.onNodeWithTag("noise_reduction_wav_only_hint").assertIsDisplayed()
    }

    @Test
    fun success_nullModeDisablesAllOptionsAndIgnoresClick() {
        // Given: DataStore 미준비 — SegmentedControl disabled + onSelectMode no-op
        var selected: NoiseReductionMode? = null
        composeTestRule.setContent {
            OptionsNoiseReductionContent(
                noiseReductionMode = null,
                onSelectMode = { selected = it },
            )
        }

        // Then: 모든 옵션 비활성
        composeTestRule.onNodeWithTag("noise_reduction_device_default").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("noise_reduction_on").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("noise_reduction_off").assertIsNotEnabled()

        // When: 탭해도 콜백 미호출
        composeTestRule.onNodeWithTag("noise_reduction_on").performClick()

        // Then
        composeTestRule.runOnIdle {
            assertEquals(null, selected)
        }
    }

    @Test
    fun success_infoButtonShowsConfirmOnlyDialog() {
        // Given
        composeTestRule.setContent {
            OptionsNoiseReductionContent(
                noiseReductionMode = NoiseReductionMode.DeviceDefault,
                onSelectMode = {},
            )
        }

        // When
        composeTestRule.onNodeWithTag("noise_reduction_info_button").performClick()

        // Then
        composeTestRule.onNodeWithTag("noise_reduction_info_confirm_button").assertIsDisplayed()
    }

    @Test
    fun success_deviceDefaultHintShowsAvailableWhenSeamTrue() {
        // Given — 모드 무관, seam으로 isAvailable 강제
        composeTestRule.setContent {
            OptionsNoiseReductionContent(
                noiseReductionMode = NoiseReductionMode.On,
                onSelectMode = {},
                noiseSuppressorAvailable = true,
            )
        }

        // Then
        composeTestRule.onNodeWithTag("noise_reduction_device_default_hint").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            appContext.getString(R.string.options_noise_reduction_device_default_hint_available),
        ).assertIsDisplayed()
    }

    @Test
    fun success_deviceDefaultHintShowsUnavailableWhenSeamFalse() {
        // Given
        composeTestRule.setContent {
            OptionsNoiseReductionContent(
                noiseReductionMode = NoiseReductionMode.Off,
                onSelectMode = {},
                noiseSuppressorAvailable = false,
            )
        }

        // Then
        composeTestRule.onNodeWithTag("noise_reduction_device_default_hint").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            appContext.getString(R.string.options_noise_reduction_device_default_hint_unavailable),
        ).assertIsDisplayed()
    }
}
