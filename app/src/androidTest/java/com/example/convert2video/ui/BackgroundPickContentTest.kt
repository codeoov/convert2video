package com.example.convert2video.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.example.convert2video.data.BackgroundImage
import org.junit.Rule
import org.junit.Test

class BackgroundPickContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setContent(
        backgrounds: List<BackgroundImage> = emptyList(),
        onAddClick: () -> Unit = {},
        onSelect: (Long) -> Unit = {},
        onDelete: (BackgroundImage) -> Unit = {},
        onNavigateBack: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            BackgroundPickContent(
                backgrounds = backgrounds,
                onAddClick = onAddClick,
                onSelect = onSelect,
                onDelete = onDelete,
                onNavigateBack = onNavigateBack,
            )
        }
    }

    // Given / When / Then

    @Test
    fun success_emptyPickShowsEmptyState() {
        // Given: 배경화면 없음
        setContent(backgrounds = emptyList())

        // Then: 빈 상태 + 추가 버튼 표시
        composeTestRule.onNodeWithTag("empty_state").assertIsDisplayed()
        composeTestRule.onNodeWithTag("add_background_button").assertIsDisplayed()
    }

    @Test
    fun success_tappingAddButtonInvokesCallback() {
        // Given
        var clicked = false
        setContent(onAddClick = { clicked = true })

        // When
        composeTestRule.onNodeWithTag("add_background_button").performClick()

        // Then
        assert(clicked)
    }

    @Test
    fun success_tappingThumbnailInvokesSelectCallback() {
        // Given
        var selectedId: Long? = null
        setContent(
            backgrounds = listOf(
                BackgroundImage(id = 1, filePath = "/tmp/a.jpg", addedAt = 0, isSelected = true),
                BackgroundImage(id = 2, filePath = "/tmp/b.jpg", addedAt = 1, isSelected = false),
            ),
            onSelect = { selectedId = it },
        )

        // When
        composeTestRule.onNodeWithTag("background_thumbnail_2").performClick()

        // Then
        assert(selectedId == 2L)
    }

    @Test
    fun success_longPressThenConfirmDeletesBackground() {
        // Given
        var deleted: BackgroundImage? = null
        val background = BackgroundImage(id = 1, filePath = "/tmp/a.jpg", addedAt = 0, isSelected = true)
        setContent(
            backgrounds = listOf(background),
            onDelete = { deleted = it },
        )

        // When
        composeTestRule.onNodeWithTag("background_thumbnail_1").performTouchInput { longClick() }
        composeTestRule.onNodeWithTag("delete_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("delete_confirm_button").performClick()

        // Then
        assert(deleted == background)
    }

    @Test
    fun success_backgroundGridShownWhenBackgroundsExist() {
        // Given
        setContent(
            backgrounds = listOf(
                BackgroundImage(id = 1, filePath = "/tmp/a.jpg", addedAt = 0, isSelected = false),
                BackgroundImage(id = 2, filePath = "/tmp/b.jpg", addedAt = 1, isSelected = true),
            ),
        )

        // Then
        composeTestRule.onNodeWithTag("background_grid").assertIsDisplayed()
        composeTestRule.onNodeWithTag("background_thumbnail_1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("background_thumbnail_2").assertIsDisplayed()
    }
}
