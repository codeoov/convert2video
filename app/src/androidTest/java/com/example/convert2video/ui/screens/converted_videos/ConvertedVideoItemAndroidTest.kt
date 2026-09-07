package com.example.convert2video.ui.screens.converted_videos

import android.net.Uri
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import com.example.convert2video.R
import com.example.convert2video.data.ConvertedVideo
import com.example.convert2video.ui.screens.youtube_upload.YouTubeUploadUiState
import com.example.convert2video.ui.theme.Convert2videoTheme
import com.example.convert2video.video.C2vOutputNames
import org.junit.Rule
import org.junit.Test

/**
 * ConvertedVideoItem is internal so this androidTest can compose it without
 * standing up TabContent / ViewModel. Folder mapping SSOT remains the unit tests.
 */
class ConvertedVideoItemAndroidTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val appContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val sampleUri = Uri.parse("content://media/external/video/media/99")

    private val sampleVideo = ConvertedVideo(
        uri = sampleUri,
        displayName = "clip.mp4",
        dateAdded = 0L,
        durationMs = 60_000L,
        sizeBytes = 1_048_576L,
        file = null,
    )

    private fun setItem(
        video: ConvertedVideo = sampleVideo,
        isSelectionMode: Boolean = false,
        isSelected: Boolean = false,
    ) {
        composeTestRule.setContent {
            Convert2videoTheme {
                ConvertedVideoItem(
                    item = ConvertedVideoListItem(video = video, uploadRecord = null),
                    uploadUiState = YouTubeUploadUiState.Idle,
                    isSelectionMode = isSelectionMode,
                    isSelected = isSelected,
                    isMenuOpen = false,
                    onItemClick = {},
                    onItemLongClick = {},
                    onToggleMenu = {},
                    onPlayClick = {},
                    onDeleteClick = {},
                    onRenameClick = {},
                    onUploadClick = {},
                    onWatchUrlClick = {},
                )
            }
        }
    }

    @Test
    fun success_folderTestTagExposesFolderLabelOnly() {
        // Given: MediaStore-only row (file == null → LEGACY_FOLDER)
        val rowId = convertedVideoRowId(sampleUri)

        // When
        setItem()

        // Then: dedicated folder node (not the whole AudioRowBody) exposes folderLabel
        composeTestRule
            .onNodeWithTag("converted_video_folder_$rowId", useUnmergedTree = true)
            .assertTextEquals(C2vOutputNames.LEGACY_FOLDER)
    }

    @Test
    fun success_selectionCheckboxAnnouncesSelectOnce() {
        // Given: selection mode with check icon visible
        val selectCd = appContext.getString(R.string.cd_recording_select)

        // When
        setItem(isSelectionMode = true, isSelected = true)

        // Then: Box owns cd_recording_select; Icon CD is null (no double-announce)
        composeTestRule
            .onAllNodesWithContentDescription(selectCd, useUnmergedTree = true)
            .assertCountEquals(1)
    }
}
