package com.example.convert2video.ui.screens.convert

import com.example.convert2video.video.ConversionWorker
import com.example.convert2video.video.VideoSegment
import com.example.convert2video.video.VideoSegmentPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentPlanWorkDataMappingTest {

    @Test
    fun success_buildConversionInputData_omitsSegmentKeysWhenOff() {
        // Given / When
        val data = buildConversionInputData(
            backgroundPath = "/bg.jpg",
            audioUri = "content://audio/1",
        )

        // Then — KEY_SEGMENT_* must be absent (none), not default 0
        assertFalse(data.keyValueMap.containsKey(ConversionWorker.KEY_SEGMENT_START_US))
        assertFalse(data.keyValueMap.containsKey(ConversionWorker.KEY_SEGMENT_END_US))
        assertFalse(data.keyValueMap.containsKey(ConversionWorker.KEY_SEGMENT_BATCH_ID))
        assertFalse(data.keyValueMap.containsKey(ConversionWorker.KEY_SEGMENT_INDEX))
        assertFalse(data.keyValueMap.containsKey(ConversionWorker.KEY_SEGMENT_TOTAL))
        assertEquals("/bg.jpg", data.getString(ConversionWorker.KEY_BACKGROUND_PATH))
        assertEquals("content://audio/1", data.getString(ConversionWorker.KEY_AUDIO_URI))
    }

    @Test
    fun success_buildConversionInputData_includesAllFiveSegmentKeysWhenOn() {
        // Given
        val segment = VideoSegment(0L, 60_000_000L)
        val batchId = "batch-uuid-1"

        // When
        val data = buildConversionInputData(
            backgroundPath = "/bg.jpg",
            audioUri = "content://audio/1",
            segment = segment,
            segmentBatchId = batchId,
            segmentIndex = 1,
            segmentTotal = 3,
        )

        // Then
        assertEquals(0L, data.getLong(ConversionWorker.KEY_SEGMENT_START_US, -1L))
        assertEquals(60_000_000L, data.getLong(ConversionWorker.KEY_SEGMENT_END_US, -1L))
        assertEquals(batchId, data.getString(ConversionWorker.KEY_SEGMENT_BATCH_ID))
        assertEquals(1, data.getInt(ConversionWorker.KEY_SEGMENT_INDEX, -1))
        assertEquals(3, data.getInt(ConversionWorker.KEY_SEGMENT_TOTAL, -1))
        assertTrue(data.keyValueMap.containsKey(ConversionWorker.KEY_SEGMENT_START_US))
        assertTrue(data.keyValueMap.containsKey(ConversionWorker.KEY_SEGMENT_BATCH_ID))
    }

    @Test
    fun success_equalPlan_mapsToOneBasedIndexes() {
        // Given
        val totalDurationUs = 180_000_000L
        val segments = VideoSegmentPlanner.computeEqualSegments(totalDurationUs, 3).getOrThrow()
        val batchId = "shared-batch"

        // When
        val dataList = segments.mapIndexed { zeroBased, segment ->
            buildConversionInputData(
                backgroundPath = "/bg.jpg",
                audioUri = "content://audio/1",
                segment = segment,
                segmentBatchId = batchId,
                segmentIndex = zeroBased + 1,
                segmentTotal = segments.size,
            )
        }

        // Then
        assertEquals(3, dataList.size)
        dataList.forEachIndexed { index, data ->
            assertEquals(index + 1, data.getInt(ConversionWorker.KEY_SEGMENT_INDEX, -1))
            assertEquals(3, data.getInt(ConversionWorker.KEY_SEGMENT_TOTAL, -1))
            assertEquals(batchId, data.getString(ConversionWorker.KEY_SEGMENT_BATCH_ID))
            assertTrue(data.keyValueMap.containsKey(ConversionWorker.KEY_SEGMENT_START_US))
            assertTrue(data.keyValueMap.containsKey(ConversionWorker.KEY_SEGMENT_END_US))
        }
        assertEquals(0L, dataList[0].getLong(ConversionWorker.KEY_SEGMENT_START_US, -1L))
        assertEquals(180_000_000L, dataList[2].getLong(ConversionWorker.KEY_SEGMENT_END_US, -1L))
    }

    @Test
    fun success_parseCustomSegmentDrafts_convertsSecondsToUs() {
        // Given
        val drafts = listOf(
            ConvertViewModel.CustomSegmentDraft(startSeconds = "0", endSeconds = "60"),
            ConvertViewModel.CustomSegmentDraft(startSeconds = "120", endSeconds = "180.5"),
        )

        // When
        val result = parseCustomSegmentDrafts(drafts)

        // Then
        val segments = result.getOrThrow()
        assertEquals(2, segments.size)
        assertEquals(VideoSegment(0L, 60_000_000L), segments[0])
        assertEquals(120_000_000L, segments[1].startUs)
        assertEquals(180_500_000L, segments[1].endUs)
    }

    @Test
    fun failure_parseCustomSegmentDrafts_rejectsNonNumeric() {
        // Given
        val drafts = listOf(
            ConvertViewModel.CustomSegmentDraft(startSeconds = "abc", endSeconds = "60"),
        )

        // When
        val result = parseCustomSegmentDrafts(drafts)

        // Then
        assertTrue(result.isFailure)
    }

    @Test
    fun failure_buildConversionInputData_rejectsPartialSegmentKeys() {
        // Given / When / Then — range without batch
        try {
            buildConversionInputData(
                backgroundPath = "/bg.jpg",
                audioUri = "content://audio/1",
                segment = VideoSegment(0L, 60_000_000L),
            )
            throw AssertionError("expected IllegalArgumentException for range-only keys")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("all-or-none"))
        }

        // Given / When / Then — batch without range
        try {
            buildConversionInputData(
                backgroundPath = "/bg.jpg",
                audioUri = "content://audio/1",
                segmentBatchId = "batch-only",
                segmentIndex = 1,
                segmentTotal = 2,
            )
            throw AssertionError("expected IllegalArgumentException for batch-only keys")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("all-or-none"))
        }
    }

    @Test
    fun failure_buildConversionInputData_rejectsPartialBatchKeysTwoOfThree() {
        // Given / When / Then — 2/3: batchId + index (no total, no range)
        try {
            buildConversionInputData(
                backgroundPath = "/bg.jpg",
                audioUri = "content://audio/1",
                segmentBatchId = "batch-partial",
                segmentIndex = 1,
            )
            throw AssertionError("expected IllegalArgumentException for 2/3 batch keys")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("2/3"))
        }

        // Given / When / Then — 2/3: index + total (no batchId)
        try {
            buildConversionInputData(
                backgroundPath = "/bg.jpg",
                audioUri = "content://audio/1",
                segmentIndex = 2,
                segmentTotal = 3,
            )
            throw AssertionError("expected IllegalArgumentException for index+total without batchId")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("2/3"))
        }

        // Given / When / Then — 2/3: batchId + total (no index) with range present
        try {
            buildConversionInputData(
                backgroundPath = "/bg.jpg",
                audioUri = "content://audio/1",
                segment = VideoSegment(0L, 1_000_000L),
                segmentBatchId = "batch-partial",
                segmentTotal = 3,
            )
            throw AssertionError("expected IllegalArgumentException for range + 2/3 batch keys")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("2/3"))
        }
    }

    @Test
    fun exception_buildConversionInputData_rangeOnlyMentionsRangeKeys() {
        // Given
        val segment = VideoSegment(0L, 60_000_000L)

        // When
        val error = runCatching {
            buildConversionInputData(
                backgroundPath = "/bg.jpg",
                audioUri = "content://audio/1",
                segment = segment,
            )
        }.exceptionOrNull()

        // Then
        assertTrue(error is IllegalArgumentException)
        val message = error!!.message.orEmpty()
        assertTrue(message.contains("all-or-none"))
        assertTrue(message.contains(ConversionWorker.KEY_SEGMENT_START_US))
        assertTrue(message.contains(ConversionWorker.KEY_SEGMENT_END_US))
    }

    @Test
    fun exception_buildConversionInputData_partialBatchMentionsBatchKeys() {
        // Given
        val segmentBatchId = "batch-partial"

        // When
        val error = runCatching {
            buildConversionInputData(
                backgroundPath = "/bg.jpg",
                audioUri = "content://audio/1",
                segmentBatchId = segmentBatchId,
                segmentIndex = 1,
            )
        }.exceptionOrNull()

        // Then
        assertTrue(error is IllegalArgumentException)
        val message = error!!.message.orEmpty()
        assertTrue(message.contains("2/3"))
        assertTrue(message.contains(ConversionWorker.KEY_SEGMENT_BATCH_ID))
        assertTrue(message.contains(ConversionWorker.KEY_SEGMENT_INDEX))
        assertTrue(message.contains(ConversionWorker.KEY_SEGMENT_TOTAL))
    }
}
