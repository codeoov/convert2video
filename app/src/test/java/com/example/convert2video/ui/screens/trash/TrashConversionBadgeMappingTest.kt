package com.example.convert2video.ui.screens.trash

import com.example.convert2video.data.TrashedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrashConversionBadgeMappingTest {

    private fun item(
        itemType: String,
        displayName: String = "clip.m4a",
        sourceAudioUri: String? = null,
    ): TrashedItem = TrashedItem(
        id = 1L,
        itemType = itemType,
        displayName = displayName,
        trashFilePath = "/files/trash/x",
        deletedAt = 1L,
        wasIndexed = true,
        sourceAudioUri = sourceAudioUri,
    )

    @Test
    fun success_importedAudio_matchesSourceAudioUri() {
        // Given
        val uri = "content://media/audio/42"
        val audio = item(TrashedItem.IMPORTED_AUDIO, sourceAudioUri = uri)

        // When / Then
        assertTrue(isTrashedAudioConverted(audio, setOf(uri)))
        assertFalse(isTrashedAudioConverted(audio, setOf("content://media/audio/99")))
        assertFalse(isTrashedAudioConverted(audio, emptySet()))
    }

    @Test
    fun success_recordingAudio_matchesDisplayNameInUriPath() {
        // Given — F2 녹음 삭제는 sourceAudioUri를 안 남김
        val audio = item(
            itemType = TrashedItem.RECORDING_AUDIO,
            displayName = "rec.m4a",
            sourceAudioUri = null,
        )

        // When / Then
        assertTrue(
            isTrashedAudioConverted(
                audio,
                setOf("content://app/recordings/rec.m4a"),
            ),
        )
        assertFalse(
            isTrashedAudioConverted(
                audio,
                setOf("content://app/recordings/other.m4a"),
            ),
        )
    }

    @Test
    fun success_blankSourceUri_fallsBackToDisplayName() {
        // Given
        val audio = item(
            TrashedItem.IMPORTED_AUDIO,
            displayName = "song.mp3",
            sourceAudioUri = "   ",
        )

        // When / Then
        assertTrue(
            isTrashedAudioConverted(audio, setOf("content://media/external/song.mp3")),
        )
    }

    @Test
    fun success_videoNeverConvertedBadge() {
        // Given
        val video = item(
            itemType = TrashedItem.CONVERTED_VIDEO,
            displayName = "out.mp4",
            sourceAudioUri = "content://media/audio/1",
        )

        // When / Then
        assertFalse(isTrashedAudioConverted(video, setOf("content://media/audio/1")))
        assertFalse(isTrashedAudioType(video.itemType))
        assertTrue(isTrashedVideoType(video.itemType))
    }

    @Test
    fun success_toTrashRowUi_mapsAudioConvertedFlag() {
        // Given
        val uri = "content://audio/7"
        val audio = item(TrashedItem.IMPORTED_AUDIO, sourceAudioUri = uri)

        // When
        val row = toTrashRowUi(audio, setOf(uri))

        // Then
        assertTrue(row.isAudio)
        assertTrue(row.isConverted)
        assertEquals(audio, row.item)
    }

    @Test
    fun success_unknownType_isNotAudioOrVideo() {
        // Given
        val unknown = item(itemType = "OTHER")

        // When / Then
        assertFalse(isTrashedAudioType(unknown.itemType))
        assertFalse(isTrashedVideoType(unknown.itemType))
        assertFalse(isTrashedAudioConverted(unknown, setOf("x")))
    }
}
