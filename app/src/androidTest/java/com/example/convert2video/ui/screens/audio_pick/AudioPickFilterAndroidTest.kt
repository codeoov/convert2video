package com.example.convert2video.ui.screens.audio_pick

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.convert2video.R
import com.example.convert2video.data.AudioItem
import com.example.convert2video.ui.theme.Convert2videoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AudioPickFilterAndroidTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val mediaItem = AudioItem(
        id = 1L,
        title = "Media Track",
        artist = null,
        durationMs = 60_000L,
        uri = Uri.parse("content://media/external/audio/1"),
        dateAdded = 100L,
    )
    private val recordingItem = AudioItem(
        id = -2L,
        title = "(C2V)2026-08-03.m4a",
        artist = null,
        durationMs = 30_000L,
        uri = Uri.parse("content://file/recording/1"),
        dateAdded = 200L,
    )

    private val appContext: Context
        get() = ApplicationProvider.getApplicationContext()

    private val mediaLoadFailedMessage: String
        get() = appContext.getString(R.string.audio_pick_load_media_failed)

    private val emptyMyRecordingsMessage: String
        get() = appContext.getString(R.string.audio_pick_empty_my_recordings)

    // Given / When / Then

    @Test
    fun success_all_mergesAndSortsByDateAddedDesc() {
        val result = filterDisplayedItems(
            media = listOf(mediaItem),
            recordings = listOf(recordingItem),
            filter = AudioSourceFilter.All,
        )
        assertEquals(listOf(recordingItem, mediaItem), result)
    }

    @Test
    fun success_myRecordings_returnsOnlyRecordings() {
        val result = filterDisplayedItems(
            media = listOf(mediaItem),
            recordings = listOf(recordingItem),
            filter = AudioSourceFilter.MyRecordings,
        )
        assertEquals(listOf(recordingItem), result)
    }

    @Test
    fun success_files_returnsOnlyMediaStoreAudio() {
        // Given — MediaStore audio and app recording entries.
        // When — AudioPick Files is applied.
        val result = filterDisplayedItems(
            media = listOf(mediaItem),
            recordings = listOf(recordingItem),
            filter = AudioSourceFilter.Files,
        )

        // Then — only MediaStore audio remains.
        assertEquals(listOf(mediaItem), result)
    }

    @Test
    fun success_singleAudioTap_callsExistingCallbackWithoutStartingConversion() {
        // Given — AudioPick has one visible item and the existing completion callback.
        var pickedItems: List<AudioItem>? = null
        composeTestRule.setContent {
            Convert2videoTheme {
                AudioPickContent(
                    audioItems = listOf(recordingItem),
                    mediaItems = emptyList(),
                    recordingItems = listOf(recordingItem),
                    audioFilter = AudioSourceFilter.MyRecordings,
                    isLoadingMedia = false,
                    isLoadingRecordings = false,
                    mediaError = null,
                    recordingsError = null,
                    selectedIds = emptySet(),
                    isSelectionMode = false,
                    onAudioBatchPicked = { pickedItems = it },
                    onEnterSelectionMode = {},
                    onToggleSelection = {},
                    onClearSelection = {},
                    onFilterChange = {},
                    onRetryMedia = {},
                    onRetryRecordings = {},
                    onNavigateBack = {},
                    onOpenDrawer = {},
                )
            }
        }

        // When — the user taps one AudioPick item.
        composeTestRule.onNodeWithTag("audio_item_${recordingItem.id}").performClick()
        composeTestRule.waitForIdle()

        // Then — the existing callback receives the item; no conversion callback is introduced.
        assertEquals(listOf(recordingItem), pickedItems)
    }

    @Test
    fun success_selectionModeConfirm_clickCallsCallbackWithExactlySelectedItems() {
        // Given — selection mode renders MediaStore and app-recording items, with only MediaStore selected.
        var pickedItems: List<AudioItem>? = null
        composeTestRule.setContent {
            Convert2videoTheme {
                AudioPickContent(
                    audioItems = listOf(mediaItem, recordingItem),
                    mediaItems = listOf(mediaItem),
                    recordingItems = listOf(recordingItem),
                    audioFilter = AudioSourceFilter.All,
                    isLoadingMedia = false,
                    isLoadingRecordings = false,
                    mediaError = null,
                    recordingsError = null,
                    selectedIds = setOf(mediaItem.id),
                    isSelectionMode = true,
                    onAudioBatchPicked = { pickedItems = it },
                    onEnterSelectionMode = {},
                    onToggleSelection = {},
                    onClearSelection = {},
                    onFilterChange = {},
                    onRetryMedia = {},
                    onRetryRecordings = {},
                    onNavigateBack = {},
                    onOpenDrawer = {},
                )
            }
        }

        // When — the selection confirmation button is tapped.
        composeTestRule.onNodeWithTag("audio_confirm_selection_button").performClick()
        composeTestRule.waitForIdle()

        // Then — the existing callback receives exactly the selected MediaStore item.
        assertEquals(listOf(mediaItem), pickedItems)
    }

    @Test
    fun success_selectionModeClear_clickCallsClearSelectionCallback() {
        // Given — AudioPick is in selection mode with one selected item.
        var clearCalls = 0
        composeTestRule.setContent {
            Convert2videoTheme {
                AudioPickContent(
                    audioItems = listOf(recordingItem),
                    mediaItems = emptyList(),
                    recordingItems = listOf(recordingItem),
                    audioFilter = AudioSourceFilter.MyRecordings,
                    isLoadingMedia = false,
                    isLoadingRecordings = false,
                    mediaError = null,
                    recordingsError = null,
                    selectedIds = setOf(recordingItem.id),
                    isSelectionMode = true,
                    onAudioBatchPicked = {},
                    onEnterSelectionMode = {},
                    onToggleSelection = {},
                    onClearSelection = { clearCalls++ },
                    onFilterChange = {},
                    onRetryMedia = {},
                    onRetryRecordings = {},
                    onNavigateBack = {},
                    onOpenDrawer = {},
                )
            }
        }

        // When — the selection clear button is tapped.
        composeTestRule.onNodeWithTag("audio_clear_selection_button").performClick()
        composeTestRule.waitForIdle()

        // Then — only the existing selection-clear callback is invoked.
        assertEquals(1, clearCalls)
    }

    @Test
    fun success_normalModeBack_clickCallsNavigateBackCallback() {
        // Given — AudioPick is in normal mode.
        var navigateBackCalls = 0
        composeTestRule.setContent {
            Convert2videoTheme {
                AudioPickContent(
                    audioItems = emptyList(),
                    mediaItems = emptyList(),
                    recordingItems = emptyList(),
                    audioFilter = AudioSourceFilter.MyRecordings,
                    isLoadingMedia = false,
                    isLoadingRecordings = false,
                    mediaError = null,
                    recordingsError = null,
                    selectedIds = emptySet(),
                    isSelectionMode = false,
                    onAudioBatchPicked = {},
                    onEnterSelectionMode = {},
                    onToggleSelection = {},
                    onClearSelection = {},
                    onFilterChange = {},
                    onRetryMedia = {},
                    onRetryRecordings = {},
                    onNavigateBack = { navigateBackCalls++ },
                    onOpenDrawer = {},
                )
            }
        }

        // When — the normal-mode back button is tapped.
        composeTestRule.onNodeWithContentDescription(
            appContext.getString(R.string.cd_navigate_back),
        ).performClick()
        composeTestRule.waitForIdle()

        // Then — the existing navigation callback is invoked.
        assertEquals(1, navigateBackCalls)
    }

    @Test
    fun success_audioPickFilterLabels_exposesAllThreeOptions() {
        // Given — AudioPick renders all source options.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val allLabel = context.getString(R.string.audio_pick_filter_all)
        val myRecordingsLabel = context.getString(R.string.audio_pick_filter_my_recordings)
        val filesLabel = context.getString(R.string.audio_pick_filter_files)
        composeTestRule.setContent {
            Convert2videoTheme {
                AudioPickContent(
                    audioItems = emptyList(),
                    mediaItems = emptyList(),
                    recordingItems = emptyList(),
                    audioFilter = AudioSourceFilter.All,
                    isLoadingMedia = false,
                    isLoadingRecordings = false,
                    mediaError = null,
                    recordingsError = null,
                    selectedIds = emptySet(),
                    isSelectionMode = false,
                    onAudioBatchPicked = {},
                    onEnterSelectionMode = {},
                    onToggleSelection = {},
                    onClearSelection = {},
                    onFilterChange = {},
                    onRetryMedia = {},
                    onRetryRecordings = {},
                    onNavigateBack = {},
                    onOpenDrawer = {},
                )
            }
        }

        // When — the filter row is displayed.
        composeTestRule.waitForIdle()

        // Then — All, My recordings, and Files are all available to AudioPick.
        composeTestRule.onNodeWithTag("audio_filter_all").assertIsDisplayed()
        composeTestRule.onNodeWithTag("audio_filter_my_recordings").assertIsDisplayed()
        composeTestRule.onNodeWithTag("audio_filter_files").assertIsDisplayed()
        composeTestRule.onNodeWithText(allLabel).assertIsDisplayed()
        composeTestRule.onNodeWithText(myRecordingsLabel).assertIsDisplayed()
        composeTestRule.onNodeWithText(filesLabel).assertIsDisplayed()
        composeTestRule.onNodeWithTag("listen_filter_files").assertDoesNotExist()
    }

    @Test
    fun success_myRecordingsWithData_mediaErrorDoesNotBlockList() {
        composeTestRule.setContent {
            Convert2videoTheme {
                AudioPickContent(
                    audioItems = listOf(recordingItem),
                    mediaItems = emptyList(),
                    recordingItems = listOf(recordingItem),
                    audioFilter = AudioSourceFilter.MyRecordings,
                    isLoadingMedia = false,
                    isLoadingRecordings = false,
                    mediaError = mediaLoadFailedMessage,
                    recordingsError = null,
                    selectedIds = emptySet(),
                    isSelectionMode = false,
                    onAudioBatchPicked = {},
                    onEnterSelectionMode = {},
                    onToggleSelection = {},
                    onClearSelection = {},
                    onFilterChange = {},
                    onRetryMedia = {},
                    onRetryRecordings = {},
                    onNavigateBack = {},
                    onOpenDrawer = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("audio_list").assertIsDisplayed()
        composeTestRule.onNodeWithTag("audio_item_-2").assertIsDisplayed()
        composeTestRule.onNodeWithTag("audio_error_state").assertDoesNotExist()
    }

    @Test
    fun success_allInlineError_whenRecordingsVisibleAndMediaFailed() {
        composeTestRule.setContent {
            Convert2videoTheme {
                AudioPickContent(
                    audioItems = listOf(recordingItem),
                    mediaItems = emptyList(),
                    recordingItems = listOf(recordingItem),
                    audioFilter = AudioSourceFilter.All,
                    isLoadingMedia = false,
                    isLoadingRecordings = false,
                    mediaError = mediaLoadFailedMessage,
                    recordingsError = null,
                    selectedIds = emptySet(),
                    isSelectionMode = false,
                    onAudioBatchPicked = {},
                    onEnterSelectionMode = {},
                    onToggleSelection = {},
                    onClearSelection = {},
                    onFilterChange = {},
                    onRetryMedia = {},
                    onRetryRecordings = {},
                    onNavigateBack = {},
                    onOpenDrawer = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("audio_inline_error").assertIsDisplayed()
        composeTestRule.onNodeWithTag("audio_list").assertIsDisplayed()
    }

    @Test
    fun success_filterSwitchToFiles_clearsSelectionViaCallback() {
        var currentFilter = AudioSourceFilter.MyRecordings
        var selectedIds = setOf(recordingItem.id)

        composeTestRule.setContent {
            Convert2videoTheme {
                val items = filterDisplayedItems(
                    media = listOf(mediaItem),
                    recordings = listOf(recordingItem),
                    filter = currentFilter,
                )
                AudioPickContent(
                    audioItems = items,
                    mediaItems = listOf(mediaItem),
                    recordingItems = listOf(recordingItem),
                    audioFilter = currentFilter,
                    isLoadingMedia = false,
                    isLoadingRecordings = false,
                    mediaError = null,
                    recordingsError = null,
                    selectedIds = selectedIds,
                    isSelectionMode = selectedIds.isNotEmpty(),
                    onAudioBatchPicked = {},
                    onEnterSelectionMode = { id -> selectedIds = setOf(id) },
                    onToggleSelection = {},
                    onClearSelection = { selectedIds = emptySet() },
                    onFilterChange = { filter ->
                        currentFilter = filter
                        selectedIds = emptySet()
                    },
                    onRetryMedia = {},
                    onRetryRecordings = {},
                    onNavigateBack = {},
                    onOpenDrawer = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("audio_filter_files").performClick()
        composeTestRule.waitForIdle()
        assertEquals(AudioSourceFilter.Files, currentFilter)
        assertEquals(emptySet<Long>(), selectedIds)
    }

    @Test
    fun success_emptyStateMessageForMyRecordings() {
        composeTestRule.setContent {
            Convert2videoTheme {
                AudioPickContent(
                    audioItems = emptyList(),
                    mediaItems = emptyList(),
                    recordingItems = emptyList(),
                    audioFilter = AudioSourceFilter.MyRecordings,
                    isLoadingMedia = false,
                    isLoadingRecordings = false,
                    mediaError = null,
                    recordingsError = null,
                    selectedIds = emptySet(),
                    isSelectionMode = false,
                    onAudioBatchPicked = {},
                    onEnterSelectionMode = {},
                    onToggleSelection = {},
                    onClearSelection = {},
                    onFilterChange = {},
                    onRetryMedia = {},
                    onRetryRecordings = {},
                    onNavigateBack = {},
                    onOpenDrawer = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("audio_empty_state").assertIsDisplayed()
        composeTestRule.onNodeWithTag("audio_empty_state")
            .assertTextEquals(emptyMyRecordingsMessage)
    }

    @Test
    fun success_filesFilter_mediaErrorShowsFullScreen() {
        composeTestRule.setContent {
            Convert2videoTheme {
                AudioPickContent(
                    audioItems = emptyList(),
                    mediaItems = emptyList(),
                    recordingItems = listOf(recordingItem),
                    audioFilter = AudioSourceFilter.Files,
                    isLoadingMedia = false,
                    isLoadingRecordings = false,
                    mediaError = mediaLoadFailedMessage,
                    recordingsError = null,
                    selectedIds = emptySet(),
                    isSelectionMode = false,
                    onAudioBatchPicked = {},
                    onEnterSelectionMode = {},
                    onToggleSelection = {},
                    onClearSelection = {},
                    onFilterChange = {},
                    onRetryMedia = {},
                    onRetryRecordings = {},
                    onNavigateBack = {},
                    onOpenDrawer = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("audio_error_state").assertIsDisplayed()
        composeTestRule.onNodeWithTag("audio_retry_button").assertIsDisplayed()
    }

    @Test
    fun success_filesRetryCallback_canDelegatePermissionFallback() {
        // Given — AudioPickContent delegates the permission/GetContent decision to onRetryMedia.
        var mediaRetryCalls = 0
        var recordingRetryCalls = 0
        composeTestRule.setContent {
            Convert2videoTheme {
                AudioPickContent(
                    audioItems = emptyList(),
                    mediaItems = emptyList(),
                    recordingItems = listOf(recordingItem),
                    audioFilter = AudioSourceFilter.Files,
                    isLoadingMedia = false,
                    isLoadingRecordings = false,
                    mediaError = mediaLoadFailedMessage,
                    recordingsError = null,
                    selectedIds = emptySet(),
                    isSelectionMode = false,
                    onAudioBatchPicked = {},
                    onEnterSelectionMode = {},
                    onToggleSelection = {},
                    onClearSelection = {},
                    onFilterChange = {},
                    onRetryMedia = { mediaRetryCalls++ },
                    onRetryRecordings = { recordingRetryCalls++ },
                    onNavigateBack = {},
                    onOpenDrawer = {},
                )
            }
        }

        // When — Files retry is tapped after a permission-denied MediaStore load.
        composeTestRule.onNodeWithTag("audio_retry_button").performClick()
        composeTestRule.waitForIdle()

        // Then — the external fallback seam is called for MediaStore only.
        assertEquals(1, mediaRetryCalls)
        assertEquals(0, recordingRetryCalls)
    }
}
