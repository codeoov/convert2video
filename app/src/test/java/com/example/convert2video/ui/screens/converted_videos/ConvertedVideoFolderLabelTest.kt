package com.example.convert2video.ui.screens.converted_videos

import android.net.Uri
import com.example.convert2video.data.ConvertedVideo
import com.example.convert2video.video.C2vOutputNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ConvertedVideoFolderLabelTest {

    @Test
    fun success_nonNullFile_returnsC2vFolder() {
        // Given: app-owned file handle (ConvertedVideo.file != null)
        val file = File("/ignored/path/video.mp4")

        // When
        val label = convertedVideoFolderLabel(file)

        // Then: not a path parse — any non-null File maps to C2V_FOLDER
        assertEquals(C2vOutputNames.C2V_FOLDER, label)
    }

    @Test
    fun success_nullFile_returnsLegacyFolder() {
        // Given: MediaStore-only row (ConvertedVideo.file == null), including C2V_FOLDER paths
        val file: File? = null

        // When
        val label = convertedVideoFolderLabel(file)

        // Then
        assertEquals(C2vOutputNames.LEGACY_FOLDER, label)
    }

    @Test
    fun success_convertedVideoWithFile_returnsC2vFolder() {
        // Given: ConvertedVideo.file non-null (wrapper overload)
        val video = ConvertedVideo(
            uri = Uri.parse("content://media/external/video/media/42"),
            displayName = "owned.mp4",
            dateAdded = 0L,
            durationMs = 0L,
            sizeBytes = 0L,
            file = File("/ignored/path/owned.mp4"),
        )

        // When
        val label = convertedVideoFolderLabel(video)

        // Then
        assertEquals(C2vOutputNames.C2V_FOLDER, label)
    }

    @Test
    fun success_convertedVideoWithNullFile_returnsLegacyFolder() {
        // Given: ConvertedVideo.file == null (MediaStore-only, wrapper overload)
        val video = ConvertedVideo(
            uri = Uri.parse("content://media/external/video/media/42"),
            displayName = "legacy.mp4",
            dateAdded = 0L,
            durationMs = 0L,
            file = null,
        )

        // When
        val label = convertedVideoFolderLabel(video)

        // Then
        assertEquals(C2vOutputNames.LEGACY_FOLDER, label)
    }

    @Test
    fun success_rowId_usesLastPathSegmentWhenPresent() {
        // Given: MediaStore-style URI with a numeric last path segment
        val uri = Uri.parse("content://media/external/video/media/99")

        // When
        val rowId = convertedVideoRowId(uri)

        // Then: not the full uri.toString() (no ://)
        assertEquals("99", rowId)
        assertFalse(rowId.contains("://"))
    }

    @Test
    fun success_rowId_fallsBackWithoutSchemeWhenSegmentBlank() {
        // Given: authority-only URI — no path, lastPathSegment is null
        val uri = Uri.parse("content://authorityonly")

        // When
        val rowId = convertedVideoRowId(uri)

        // Then: unsigned hash fallback — no ://
        assertFalse(rowId.isBlank())
        assertFalse(rowId.contains("://"))
        assertEquals(uri.toString().hashCode().toUInt().toString(), rowId)
    }
}
