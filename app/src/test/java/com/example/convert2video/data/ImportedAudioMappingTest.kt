package com.example.convert2video.data

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ImportedAudioMappingTest {

    private val sampleRecord = ImportedAudioRecord(
        id = 3L,
        filePath = "/music/C2VImported/song.mp3",
        originalDisplayName = "My Song.mp3",
        durationMs = 60_000L,
        sizeBytes = 4096L,
        createdAt = 100L,
    )

    @Test
    fun success_importedIdFromAudioItemId_isInverseOfImportedAudioItemId() {
        // Given
        val importedId = 3L
        // When
        val audioItemId = importedAudioItemId(importedId)
        val restored = importedIdFromAudioItemId(audioItemId)
        // Then
        assertEquals(importedId, requireNotNull(restored))
    }

    @Test
    fun success_importedIdFromAudioItemId_recordingRange_returnsNull() {
        // Given — recording negative id range
        // When / Then
        assertNull(importedIdFromAudioItemId(-6L))
        assertNull(importedIdFromAudioItemId(-1L))
        assertNull(importedIdFromAudioItemId(123L))
    }

    @Test
    fun success_recordingIdFromAudioItemId_importedRange_returnsNull() {
        // Given — imported negative id range
        val importedAudioItemId = importedAudioItemId(1L)
        // When / Then
        assertNull(recordingIdFromAudioItemId(importedAudioItemId))
    }

    @Test
    fun success_mapImportedToAudioItem_mapsFields() {
        // Given
        val uri = Uri.parse("content://test/import/3")
        // When
        val item = mapImportedToAudioItem(sampleRecord, uri)
        // Then
        assertEquals(importedAudioItemId(3L), item.id)
        assertEquals("My Song.mp3", item.title)
        assertEquals("song.mp3", item.fileName)
        assertEquals(IMPORTED_AUDIO_FOLDER_LABEL, item.folderLabel)
        assertEquals(uri, item.uri)
        assertEquals(60_000L, item.durationMs)
        assertEquals(100L, item.dateAdded)
    }
}
