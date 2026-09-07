package com.example.convert2video.ui.screens.recordings_list

import android.net.Uri
import com.example.convert2video.data.AudioItem
import com.example.convert2video.ui.screens.audio_pick.AudioSourceFilter
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RecordingsListSourceFilterMappingTest {

    @Test
    fun success_all_excludesRecordings_preservesMediaAndImported_sortedByDateAddedDescending() {
        // Given
        val olderMedia = audioItem(1L, "media-old", dateAdded = 10L)
        val imported = audioItem(2L, "imported", dateAdded = 30L)
        val newerMedia = audioItem(3L, "media-new", dateAdded = 20L)
        val recording = audioItem(-1L, "recording", dateAdded = 40L)

        // When
        val result = filterListenAudioItems(
            mediaAndImported = listOf(olderMedia, imported, newerMedia),
            recordings = listOf(recording),
            filter = AudioSourceFilter.All,
        )

        // Then
        assertEquals(listOf(imported, newerMedia, olderMedia), result)
    }

    @Test
    fun success_myRecordings_usesRecordings_sortedByDateAddedDescending() {
        // Given
        val olderRecording = audioItem(-1L, "recording-old", dateAdded = 10L)
        val newerRecording = audioItem(-2L, "recording-new", dateAdded = 20L)

        // When
        val result = filterListenAudioItems(
            mediaAndImported = listOf(audioItem(1L, "media", dateAdded = 30L)),
            recordings = listOf(olderRecording, newerRecording),
            filter = AudioSourceFilter.MyRecordings,
        )

        // Then
        assertEquals(listOf(newerRecording, olderRecording), result)
    }

    @Test
    fun success_files_usesMediaAndImportedInput_sortedByDateAddedDescending() {
        // Given
        val olderMedia = audioItem(1L, "media-old", dateAdded = 10L)
        val newerImported = audioItem(2L, "imported-new", dateAdded = 20L)
        val recording = audioItem(-1L, "recording", dateAdded = 30L)

        // When
        val result = filterListenAudioItems(
            mediaAndImported = listOf(olderMedia, newerImported),
            recordings = listOf(recording),
            filter = AudioSourceFilter.Files,
        )

        // Then
        assertEquals(listOf(newerImported, olderMedia), result)
    }

    @Test
    fun success_all_sameDateAdded_usesIdDescendingTieBreaker() {
        // Given — equal timestamps arrive in the opposite order.
        val lowerId = audioItem(1L, "lower-id", dateAdded = 20L)
        val higherId = audioItem(2L, "higher-id", dateAdded = 20L)

        // When
        val result = filterListenAudioItems(
            mediaAndImported = listOf(lowerId, higherId),
            recordings = emptyList(),
            filter = AudioSourceFilter.All,
        )

        // Then — equal timestamps have a deterministic id tie-breaker.
        assertEquals(listOf(higherId, lowerId), result)
    }

    private fun audioItem(id: Long, title: String, dateAdded: Long): AudioItem = AudioItem(
        id = id,
        title = title,
        artist = null,
        durationMs = 1_000L,
        uri = Uri.parse("content://test/$id"),
        dateAdded = dateAdded,
    )
}
