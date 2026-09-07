package com.example.convert2video.ui.screens.converted_videos

import android.net.Uri
import com.example.convert2video.data.RECORDING_AUDIO_FILE_PROVIDER_SEGMENT
import com.example.convert2video.data.ConvertedVideo
import com.example.convert2video.data.isRecordingSourcedAudioUri
import com.example.convert2video.video.C2vOutputNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ConvertedVideosFilterSortMappingTest {

    private val recordingAudioUri =
        "content://${C2vOutputNames.FILE_PROVIDER_AUTHORITY}/$RECORDING_AUDIO_FILE_PROVIDER_SEGMENT/rec.m4a"
    private val importedAudioUri =
        "content://${C2vOutputNames.FILE_PROVIDER_AUTHORITY}/c2v_music_imported/import.m4a"
    private val mediaStoreAudioUri = "content://media/external/audio/media/42"

    private fun video(
        uri: String,
        name: String,
        dateAdded: Long,
        durationMs: Long,
    ) = ConvertedVideo(
        uri = Uri.parse(uri),
        displayName = name,
        dateAdded = dateAdded,
        durationMs = durationMs,
        sizeBytes = 0L,
        file = null,
    )

    private fun standalone(
        uri: String,
        name: String,
        dateAdded: Long,
        durationMs: Long,
        audioUri: String?,
    ) = ConvertedVideosListRow.Standalone(
        ConvertedVideoListItem(
            video = video(uri, name, dateAdded, durationMs),
            uploadRecord = null,
            audioUri = audioUri,
        ),
    )

    // Given / When / Then

    @Test
    fun success_isRecordingSourcedAudioUri_recordingFileProvider_returnsTrue() {
        // Given / When / Then
        assertTrue(isRecordingSourcedAudioUri(recordingAudioUri))
    }

    @Test
    fun success_isRecordingSourcedAudioUri_importedFileProvider_returnsFalse() {
        // Given / When / Then
        assertFalse(isRecordingSourcedAudioUri(importedAudioUri))
    }

    @Test
    fun success_isRecordingSourcedAudioUri_malformedAndNearMatch_returnsFalse() {
        // Given
        val nonRecordingUris = listOf(
            "not a uri",
            "://",
            "content://${C2vOutputNames.FILE_PROVIDER_AUTHORITY}/c2v_music_backup/rec.m4a",
            "content://${C2vOutputNames.FILE_PROVIDER_AUTHORITY}/c2v_music_extra/rec.m4a",
        )

        // When / Then
        nonRecordingUris.forEach { audioUri ->
            assertFalse(isRecordingSourcedAudioUri(audioUri))
        }
    }

    @Test
    fun success_filterMyRecordings_keepsRecordingSourcedOnly() {
        // Given
        val rows = listOf(
            standalone("content://v/1", "rec.mp4", 100L, 60_000L, recordingAudioUri),
            standalone("content://v/2", "imp.mp4", 200L, 30_000L, importedAudioUri),
        )

        // When
        val filtered = filterConvertedVideosListRowsBySource(
            rows,
            ConvertedVideoSourceFilter.MyRecordings,
        )

        // Then
        assertEquals(1, filtered.size)
        assertTrue(filtered.single().isRecordingSourced())
        assertEquals(
            listOf("content://v/1"),
            filtered.map { (it as ConvertedVideosListRow.Standalone).item.video.uri.toString() },
        )
        assertFalse(filtered.any { (it as ConvertedVideosListRow.Standalone).item.video.uri.toString() == "content://v/2" })
    }

    @Test
    fun success_filterAll_excludesRecordingRowsAndKeepsImportedAndNonRecordingRows() {
        // Given
        val rows = listOf(
            standalone("content://v/1", "a.mp4", 100L, 60_000L, recordingAudioUri),
            standalone("content://v/2", "b.mp4", 200L, 30_000L, importedAudioUri),
            standalone("content://v/3", "c.mp4", 300L, 20_000L, null),
            standalone("content://v/4", "d.mp4", 400L, 10_000L, mediaStoreAudioUri),
        )

        // When
        val filtered = filterConvertedVideosListRowsBySource(rows, ConvertedVideoSourceFilter.All)

        // Then
        assertEquals(3, filtered.size)
        assertTrue(filtered.none { it.isRecordingSourced() })
        assertEquals(
            listOf("content://v/2", "content://v/3", "content://v/4"),
            filtered.map { (it as ConvertedVideosListRow.Standalone).item.video.uri.toString() },
        )
    }

    @Test
    fun success_filterAll_preservesInputOrderAndGroupChildrenOrder() {
        // Given
        val group = ConvertedVideosListRow.Group(
            segmentBatchId = "batch-order",
            title = "ordered.mp4",
            segmentCount = 2,
            children = listOf(
                ConvertedVideoListItem(
                    video = video("content://v/group-1", "part-1.mp4", 100L, 10_000L),
                    uploadRecord = null,
                    audioUri = importedAudioUri,
                ),
                ConvertedVideoListItem(
                    video = video("content://v/group-2", "part-2.mp4", 200L, 20_000L),
                    uploadRecord = null,
                    audioUri = null,
                ),
            ),
        )
        val rows = listOf(
            standalone("content://v/recording", "recording.mp4", 50L, 30_000L, recordingAudioUri),
            standalone("content://v/imported", "imported.mp4", 100L, 20_000L, importedAudioUri),
            group,
            standalone("content://v/non-recording", "non-recording.mp4", 150L, 10_000L, null),
        )

        // When
        val filtered = filterConvertedVideosListRowsBySource(rows, ConvertedVideoSourceFilter.All)

        // Then
        assertEquals(3, filtered.size)
        assertEquals(
            "content://v/imported",
            (filtered[0] as ConvertedVideosListRow.Standalone).item.video.uri.toString(),
        )
        assertEquals("batch-order", (filtered[1] as ConvertedVideosListRow.Group).segmentBatchId)
        assertEquals(
            "content://v/non-recording",
            (filtered[2] as ConvertedVideosListRow.Standalone).item.video.uri.toString(),
        )
        assertEquals(
            listOf("content://v/group-1", "content://v/group-2"),
            (filtered[1] as ConvertedVideosListRow.Group).children.map { it.video.uri.toString() },
        )
    }

    @Test
    fun success_filterAllThenSortByTime_ordersLatestAndUsesStableTieBreak() {
        // Given
        val rows = listOf(
            standalone("content://v/recording", "recording.mp4", 400L, 30_000L, recordingAudioUri),
            standalone("content://v/b", "b.mp4", 300L, 20_000L, importedAudioUri),
            standalone("content://v/a", "a.mp4", 300L, 10_000L, null),
            standalone("content://v/old", "old.mp4", 200L, 10_000L, importedAudioUri),
        )

        // When
        val sorted = sortConvertedVideosListRows(
            filterConvertedVideosListRowsBySource(rows, ConvertedVideoSourceFilter.All),
            ConvertedVideosSortOrder.Time,
        )

        // Then: the excluded recording is absent; latest remaining rows come first and tie-break by URI
        assertEquals(
            listOf("content://v/a", "content://v/b", "content://v/old"),
            sorted.map { (it as ConvertedVideosListRow.Standalone).item.video.uri.toString() },
        )
    }

    @Test
    fun success_sortByName_ordersAscending() {
        // Given
        val rows = listOf(
            standalone("content://v/2", "beta.mp4", 200L, 30_000L, null),
            standalone("content://v/1", "alpha.mp4", 100L, 60_000L, null),
        )

        // When
        val sorted = sortConvertedVideosListRows(rows, ConvertedVideosSortOrder.Name)

        // Then
        assertEquals("alpha.mp4", (sorted[0] as ConvertedVideosListRow.Standalone).item.video.displayName)
        assertEquals("beta.mp4", (sorted[1] as ConvertedVideosListRow.Standalone).item.video.displayName)
    }

    @Test
    fun success_sortByDuration_groupUsesChildrenSum() {
        // Given
        val group = ConvertedVideosListRow.Group(
            segmentBatchId = "batch",
            title = "group.mp4",
            segmentCount = 2,
            children = listOf(
                ConvertedVideoListItem(
                    video = video("content://v/1", "g1.mp4", 100L, 40_000L),
                    uploadRecord = null,
                    audioUri = recordingAudioUri,
                ),
                ConvertedVideoListItem(
                    video = video("content://v/2", "g2.mp4", 100L, 50_000L),
                    uploadRecord = null,
                    audioUri = recordingAudioUri,
                ),
            ),
        )
        val shortStandalone = standalone("content://v/3", "solo.mp4", 300L, 60_000L, null)
        val rows = listOf(shortStandalone, group)

        // When
        val sorted = sortConvertedVideosListRows(rows, ConvertedVideosSortOrder.Duration)

        // Then: group total 90_000 > standalone 60_000
        assertTrue(sorted.first() is ConvertedVideosListRow.Group)
    }

    @Test
    fun success_groupUsesFirstChildAudioUriForSourceFilter() {
        // Given: first child recording-sourced, second imported — group counts as recording
        val group = ConvertedVideosListRow.Group(
            segmentBatchId = "batch",
            title = "mixed.mp4",
            segmentCount = 2,
            children = listOf(
                ConvertedVideoListItem(
                    video = video("content://v/1", "g1.mp4", 100L, 40_000L),
                    uploadRecord = null,
                    audioUri = recordingAudioUri,
                ),
                ConvertedVideoListItem(
                    video = video("content://v/2", "g2.mp4", 100L, 50_000L),
                    uploadRecord = null,
                    audioUri = importedAudioUri,
                ),
            ),
        )

        // When
        val allRows = filterConvertedVideosListRowsBySource(
            listOf(group),
            ConvertedVideoSourceFilter.All,
        )
        val myRecordingsRows = filterConvertedVideosListRowsBySource(
            listOf(group),
            ConvertedVideoSourceFilter.MyRecordings,
        )

        // Then
        assertTrue(group.isRecordingSourced())
        assertTrue(allRows.isEmpty())
        assertEquals(1, myRecordingsRows.size)
    }

    @Test
    fun success_groupFirstChildImported_keepsGroupInAllAndExcludesFromMyRecordings() {
        // Given: first child imported, second recording — existing first-child rule counts group as non-recording
        val group = ConvertedVideosListRow.Group(
            segmentBatchId = "batch-imported-first",
            title = "mixed-source.mp4",
            segmentCount = 2,
            children = listOf(
                ConvertedVideoListItem(
                    video = video("content://v/imported-first", "imported-first.mp4", 100L, 40_000L),
                    uploadRecord = null,
                    audioUri = importedAudioUri,
                ),
                ConvertedVideoListItem(
                    video = video("content://v/recording-second", "recording-second.mp4", 100L, 50_000L),
                    uploadRecord = null,
                    audioUri = recordingAudioUri,
                ),
            ),
        )
        val rows = listOf(group)

        // When
        val allRows = filterConvertedVideosListRowsBySource(rows, ConvertedVideoSourceFilter.All)
        val myRecordingsRows = filterConvertedVideosListRowsBySource(
            rows,
            ConvertedVideoSourceFilter.MyRecordings,
        )

        // Then
        assertEquals(1, allRows.size)
        assertEquals("batch-imported-first", (allRows.single() as ConvertedVideosListRow.Group).segmentBatchId)
        assertTrue(myRecordingsRows.isEmpty())
    }

    @Test
    fun success_emptyGroup_isKeptInAllAndExcludedFromMyRecordings() {
        // Given
        val emptyGroup = ConvertedVideosListRow.Group(
            segmentBatchId = "batch-empty",
            title = "empty.mp4",
            segmentCount = 0,
            children = emptyList(),
        )
        val rows = listOf(emptyGroup)

        // When
        val allRows = filterConvertedVideosListRowsBySource(rows, ConvertedVideoSourceFilter.All)
        val myRecordingsRows = filterConvertedVideosListRowsBySource(
            rows,
            ConvertedVideoSourceFilter.MyRecordings,
        )

        // Then
        assertFalse(emptyGroup.isRecordingSourced())
        assertEquals(1, allRows.size)
        assertEquals("batch-empty", (allRows.single() as ConvertedVideosListRow.Group).segmentBatchId)
        assertTrue(myRecordingsRows.isEmpty())
    }

    @Test
    fun success_groupFirstChildNull_isExcludedFromMyRecordingsAndKeptInAll() {
        // Given: first child has no audio source, second child is recording-sourced
        val group = ConvertedVideosListRow.Group(
            segmentBatchId = "batch-null-first",
            title = "null-first.mp4",
            segmentCount = 2,
            children = listOf(
                ConvertedVideoListItem(
                    video = video("content://v/null-first", "null-first.mp4", 100L, 40_000L),
                    uploadRecord = null,
                    audioUri = null,
                ),
                ConvertedVideoListItem(
                    video = video("content://v/recording-second", "recording-second.mp4", 100L, 50_000L),
                    uploadRecord = null,
                    audioUri = recordingAudioUri,
                ),
            ),
        )

        // When
        val allRows = filterConvertedVideosListRowsBySource(
            listOf(group),
            ConvertedVideoSourceFilter.All,
        )
        val myRecordingsRows = filterConvertedVideosListRowsBySource(
            listOf(group),
            ConvertedVideoSourceFilter.MyRecordings,
        )

        // Then
        assertFalse(group.isRecordingSourced())
        assertEquals(1, allRows.size)
        assertEquals("batch-null-first", (allRows.single() as ConvertedVideosListRow.Group).segmentBatchId)
        assertTrue(myRecordingsRows.isEmpty())
    }

    @Test
    fun success_myRecordingsGroup_keepsChildrenOrder() {
        // Given: first child is recording-sourced, so MyRecordings keeps the whole group
        val group = ConvertedVideosListRow.Group(
            segmentBatchId = "batch-recording-group",
            title = "recording-group.mp4",
            segmentCount = 2,
            children = listOf(
                ConvertedVideoListItem(
                    video = video("content://v/child-1", "child-1.mp4", 100L, 40_000L),
                    uploadRecord = null,
                    audioUri = recordingAudioUri,
                ),
                ConvertedVideoListItem(
                    video = video("content://v/child-2", "child-2.mp4", 100L, 50_000L),
                    uploadRecord = null,
                    audioUri = importedAudioUri,
                ),
            ),
        )

        // When
        val filtered = filterConvertedVideosListRowsBySource(
            listOf(group),
            ConvertedVideoSourceFilter.MyRecordings,
        )

        // Then
        assertEquals(1, filtered.size)
        assertEquals(
            listOf("content://v/child-1", "content://v/child-2"),
            (filtered.single() as ConvertedVideosListRow.Group).children.map { it.video.uri.toString() },
        )
    }

    @Test
    fun success_filterAllThenSortByNameAndDuration_preservesGroupChildrenOrder() {
        // Given: first child is non-recording, so All keeps the group before sorting
        val group = ConvertedVideosListRow.Group(
            segmentBatchId = "batch-sort-group",
            title = "z-group.mp4",
            segmentCount = 2,
            children = listOf(
                ConvertedVideoListItem(
                    video = video("content://v/part-2", "part-2.mp4", 100L, 20_000L),
                    uploadRecord = null,
                    audioUri = importedAudioUri,
                ),
                ConvertedVideoListItem(
                    video = video("content://v/part-1", "part-1.mp4", 100L, 10_000L),
                    uploadRecord = null,
                    audioUri = recordingAudioUri,
                ),
            ),
        )
        val rows = listOf(
            group,
            standalone("content://v/standalone", "a-standalone.mp4", 100L, 5_000L, null),
        )

        // When
        val filtered = filterConvertedVideosListRowsBySource(rows, ConvertedVideoSourceFilter.All)
        val nameSorted = sortConvertedVideosListRows(filtered, ConvertedVideosSortOrder.Name)
        val durationSorted = sortConvertedVideosListRows(filtered, ConvertedVideosSortOrder.Duration)

        // Then
        val expectedChildUris = listOf("content://v/part-2", "content://v/part-1")
        val nameSortedGroup = nameSorted.filterIsInstance<ConvertedVideosListRow.Group>().single()
        val durationSortedGroup = durationSorted.filterIsInstance<ConvertedVideosListRow.Group>().single()
        assertEquals(
            expectedChildUris,
            nameSortedGroup.children.map { it.video.uri.toString() },
        )
        assertEquals(
            expectedChildUris,
            durationSortedGroup.children.map { it.video.uri.toString() },
        )
    }
}
