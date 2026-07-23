package com.example.convert2video.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import com.example.convert2video.data.BackgroundImage
import org.junit.Rule
import org.junit.Test

class BackgroundLibraryContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setContent(
        backgrounds: List<BackgroundImage> = emptyList(),
        conversionState: ConversionUiState = ConversionUiState.Idle,
        onAddClick: () -> Unit = {},
        onSelect: (Long) -> Unit = {},
        onDelete: (BackgroundImage) -> Unit = {},
        onConvertClick: () -> Unit = {},
        onDismissResult: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            BackgroundLibraryContent(
                backgrounds = backgrounds,
                conversionState = conversionState,
                onAddClick = onAddClick,
                onSelect = onSelect,
                onDelete = onDelete,
                onConvertClick = onConvertClick,
                onDismissResult = onDismissResult,
            )
        }
    }

    @Test
    fun emptyLibraryShowsEmptyStateAndDisabledConvertButton() {
        setContent(backgrounds = emptyList())

        composeTestRule.onNodeWithTag("empty_state").assertIsDisplayed()
        composeTestRule.onNodeWithTag("add_background_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("convert_button").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("convert_hint").assertIsDisplayed()
    }

    @Test
    fun tappingAddButtonInvokesCallback() {
        var clicked = false
        setContent(onAddClick = { clicked = true })

        composeTestRule.onNodeWithTag("add_background_button").performClick()
        assert(clicked)
    }

    @Test
    fun tappingThumbnailSelectsIt() {
        var selectedId: Long? = null
        setContent(
            backgrounds = listOf(
                BackgroundImage(id = 1, filePath = "/tmp/a.jpg", addedAt = 0, isSelected = true),
                BackgroundImage(id = 2, filePath = "/tmp/b.jpg", addedAt = 1, isSelected = false),
            ),
            onSelect = { selectedId = it },
        )

        composeTestRule.onNodeWithTag("background_thumbnail_2").performClick()
        assert(selectedId == 2L)
    }

    @Test
    fun longPressThenConfirmDeletesBackground() {
        var deleted: BackgroundImage? = null
        val background = BackgroundImage(id = 1, filePath = "/tmp/a.jpg", addedAt = 0, isSelected = true)
        setContent(
            backgrounds = listOf(background),
            onDelete = { deleted = it },
        )

        composeTestRule.onNodeWithTag("background_thumbnail_1").performTouchInput { longClick() }
        composeTestRule.onNodeWithTag("delete_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("delete_confirm_button").performClick()

        assert(deleted == background)
    }

    @Test
    fun convertButtonEnabledOnlyWhenABackgroundIsSelected() {
        setContent(
            backgrounds = listOf(
                BackgroundImage(id = 1, filePath = "/tmp/a.jpg", addedAt = 0, isSelected = false),
            ),
        )

        // Backgrounds exist but none is selected: still disabled, with the "select one" hint.
        composeTestRule.onNodeWithTag("convert_button").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("convert_hint").assertIsDisplayed()
    }

    @Test
    fun tappingConvertButtonInvokesCallbackWhenEnabled() {
        var clicked = false
        setContent(
            backgrounds = listOf(
                BackgroundImage(id = 1, filePath = "/tmp/a.jpg", addedAt = 0, isSelected = true),
            ),
            onConvertClick = { clicked = true },
        )

        composeTestRule.onNodeWithTag("convert_button").assertIsEnabled()
        composeTestRule.onNodeWithTag("convert_button").performClick()
        assert(clicked)
    }

    @Test
    fun progressDialogShowsPercentAndBlocksConvertButton() {
        setContent(
            backgrounds = listOf(
                BackgroundImage(id = 1, filePath = "/tmp/a.jpg", addedAt = 0, isSelected = true),
            ),
            conversionState = ConversionUiState.InProgress(percent = 42),
        )

        composeTestRule.onNodeWithTag("conversion_progress_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("convert_button").assertIsNotEnabled()
    }

    @Test
    fun failedStateShowsErrorDialogAndDismissInvokesCallback() {
        var dismissed = false
        setContent(
            conversionState = ConversionUiState.Failed("boom"),
            onDismissResult = { dismissed = true },
        )

        composeTestRule.onNodeWithTag("conversion_failed_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("확인").performClick()
        assert(dismissed)
    }
}
