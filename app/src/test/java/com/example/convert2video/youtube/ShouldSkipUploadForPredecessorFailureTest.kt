package com.example.convert2video.youtube

import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import com.example.convert2video.video.ConversionWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShouldSkipUploadForPredecessorFailureTest {

    @Test
    fun success_shouldSkip_whenItemFailedTrue() {
        // Given
        val input = workDataOf(ConversionWorker.KEY_ITEM_FAILED to true)

        // When
        val skip = shouldSkipUploadForPredecessorFailure(input)

        // Then
        assertTrue(skip)
    }

    @Test
    fun success_shouldNotSkip_whenKeyAbsent() {
        // Given
        val input = Data.EMPTY

        // When
        val skip = shouldSkipUploadForPredecessorFailure(input)

        // Then
        assertFalse(skip)
    }

    @Test
    fun success_shouldNotSkip_whenItemFailedFalse() {
        // Given
        val input = workDataOf(ConversionWorker.KEY_ITEM_FAILED to false)

        // When
        val skip = shouldSkipUploadForPredecessorFailure(input)

        // Then
        assertFalse(skip)
    }

    @Test
    fun success_shouldSkip_whenItemFailedTrueEvenIfVideoUriPresent() {
        // Given — predecessor merged KEY_VIDEO_URI into the next worker input
        val input = workDataOf(
            ConversionWorker.KEY_ITEM_FAILED to true,
            YouTubeUploadWorker.KEY_VIDEO_URI to "content://video/1",
        )

        // When
        val skip = shouldSkipUploadForPredecessorFailure(input)

        // Then
        assertTrue(skip)
    }

    @Test
    fun success_predecessorFailureSkipResult_hasEmptyOutputData() {
        // Given / When
        val result = predecessorFailureSkipResult()

        // Then
        val success = result as ListenableWorker.Result.Success
        assertTrue(success.outputData.keyValueMap.isEmpty())
        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun success_youtubeUploadPreludeResult_skipInput_isEmptySuccess() {
        // Given
        val input = workDataOf(ConversionWorker.KEY_ITEM_FAILED to true)

        // When
        val result = youtubeUploadPreludeResult(input)

        // Then
        val success = result as ListenableWorker.Result.Success
        assertTrue(success.outputData.keyValueMap.isEmpty())
        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun success_youtubeUploadPreludeResult_normalInput_isNull() {
        // Given
        val input = Data.EMPTY

        // When
        val result = youtubeUploadPreludeResult(input)

        // Then
        assertNull(result)
    }
}
