package com.example.convert2video.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.assertIsDisplayed
import com.example.convert2video.data.BackgroundImage
import org.junit.Rule
import org.junit.Test

class BackgroundLibraryContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun emptyLibraryShowsEmptyStateAndEnabledAddButton() {
        composeTestRule.setContent {
            BackgroundLibraryContent(
                backgrounds = emptyList(),
                onAddClick = {},
                onSelect = {},
                onDelete = {},
            )
        }

        composeTestRule.onNodeWithTag("empty_state").assertIsDisplayed()
        composeTestRule.onNodeWithTag("add_background_button").assertIsDisplayed()
    }

    @Test
    fun tappingAddButtonInvokesCallback() {
        var clicked = false
        composeTestRule.setContent {
            BackgroundLibraryContent(
                backgrounds = emptyList(),
                onAddClick = { clicked = true },
                onSelect = {},
                onDelete = {},
            )
        }

        composeTestRule.onNodeWithTag("add_background_button").performClick()
        assert(clicked)
    }

    @Test
    fun tappingThumbnailSelectsIt() {
        var selectedId: Long? = null
        composeTestRule.setContent {
            BackgroundLibraryContent(
                backgrounds = listOf(
                    BackgroundImage(id = 1, filePath = "/tmp/a.jpg", addedAt = 0, isSelected = true),
                    BackgroundImage(id = 2, filePath = "/tmp/b.jpg", addedAt = 1, isSelected = false),
                ),
                onAddClick = {},
                onSelect = { selectedId = it },
                onDelete = {},
            )
        }

        composeTestRule.onNodeWithTag("background_thumbnail_2").performClick()
        assert(selectedId == 2L)
    }

    @Test
    fun longPressThenConfirmDeletesBackground() {
        var deleted: BackgroundImage? = null
        val background = BackgroundImage(id = 1, filePath = "/tmp/a.jpg", addedAt = 0, isSelected = true)
        composeTestRule.setContent {
            BackgroundLibraryContent(
                backgrounds = listOf(background),
                onAddClick = {},
                onSelect = {},
                onDelete = { deleted = it },
            )
        }

        composeTestRule.onNodeWithTag("background_thumbnail_1").performTouchInput { longClick() }
        composeTestRule.onNodeWithTag("delete_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("delete_confirm_button").performClick()

        assert(deleted == background)
    }
}
