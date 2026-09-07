package com.example.convert2video.video

import androidx.work.Data
import androidx.work.workDataOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversionWorkerResolveSegmentKeysTest {

    @Test
    fun success_resolveSegmentKeys_none_bothNull() {
        // Given — no segment keys
        val data = workDataOf(
            ConversionWorker.KEY_BACKGROUND_PATH to "/bg.png",
            ConversionWorker.KEY_AUDIO_URI to "content://audio/1",
        )
        // When
        val resolved = ConversionWorker.resolveSegmentKeys(data)
        // Then
        assertNotNull(resolved)
        assertNull(resolved!!.range)
        assertNull(resolved.batch)
    }

    @Test
    fun success_resolveSegmentKeys_rangeAll() {
        // Given
        val data = workDataOf(
            ConversionWorker.KEY_SEGMENT_START_US to 0L,
            ConversionWorker.KEY_SEGMENT_END_US to 1_000_000L,
        )
        // When
        val resolved = ConversionWorker.resolveSegmentKeys(data)
        // Then
        assertNotNull(resolved)
        assertEquals(0L, resolved!!.range!!.startUs)
        assertEquals(1_000_000L, resolved.range!!.endUs)
        assertNull(resolved.batch)
    }

    @Test
    fun success_resolveSegmentKeys_batchAll() {
        // Given
        val data = workDataOf(
            ConversionWorker.KEY_SEGMENT_BATCH_ID to "batch-uuid",
            ConversionWorker.KEY_SEGMENT_INDEX to 2,
            ConversionWorker.KEY_SEGMENT_TOTAL to 5,
        )
        // When
        val resolved = ConversionWorker.resolveSegmentKeys(data)
        // Then
        assertNotNull(resolved)
        assertNull(resolved!!.range)
        assertEquals("batch-uuid", resolved.batch!!.batchId)
        assertEquals(2, resolved.batch!!.index)
        assertEquals(5, resolved.batch!!.total)
    }

    @Test
    fun success_resolveSegmentKeys_rangeAndBatchTogether() {
        // Given — independent sets both complete
        val data = workDataOf(
            ConversionWorker.KEY_SEGMENT_START_US to 100L,
            ConversionWorker.KEY_SEGMENT_END_US to 200L,
            ConversionWorker.KEY_SEGMENT_BATCH_ID to "b1",
            ConversionWorker.KEY_SEGMENT_INDEX to 1,
            ConversionWorker.KEY_SEGMENT_TOTAL to 1,
        )
        // When
        val resolved = ConversionWorker.resolveSegmentKeys(data)
        // Then
        assertNotNull(resolved)
        assertEquals(100L, resolved!!.range!!.startUs)
        assertEquals(200L, resolved.range!!.endUs)
        assertEquals("b1", resolved.batch!!.batchId)
        assertEquals(1, resolved.batch!!.index)
        assertEquals(1, resolved.batch!!.total)
    }

    @Test
    fun failure_resolveSegmentKeys_rangePartialStartOnly() {
        // Given
        val data = workDataOf(ConversionWorker.KEY_SEGMENT_START_US to 0L)
        // When / Then
        assertNull(ConversionWorker.resolveSegmentKeys(data))
    }

    @Test
    fun failure_resolveSegmentKeys_rangePartialEndOnly() {
        // Given
        val data = workDataOf(ConversionWorker.KEY_SEGMENT_END_US to 1_000_000L)
        // When / Then
        assertNull(ConversionWorker.resolveSegmentKeys(data))
    }

    @Test
    fun failure_resolveSegmentKeys_batchPartialOneKey() {
        // Given
        val data = workDataOf(ConversionWorker.KEY_SEGMENT_BATCH_ID to "only-id")
        // When / Then
        assertNull(ConversionWorker.resolveSegmentKeys(data))
    }

    @Test
    fun failure_resolveSegmentKeys_batchPartialTwoKeys() {
        // Given
        val data = workDataOf(
            ConversionWorker.KEY_SEGMENT_BATCH_ID to "id",
            ConversionWorker.KEY_SEGMENT_INDEX to 1,
        )
        // When / Then
        assertNull(ConversionWorker.resolveSegmentKeys(data))
    }

    @Test
    fun failure_resolveSegmentKeys_batchIdBlank() {
        // Given
        val data = workDataOf(
            ConversionWorker.KEY_SEGMENT_BATCH_ID to "   ",
            ConversionWorker.KEY_SEGMENT_INDEX to 1,
            ConversionWorker.KEY_SEGMENT_TOTAL to 2,
        )
        // When / Then
        assertNull(ConversionWorker.resolveSegmentKeys(data))
    }

    @Test
    fun success_resolveSegmentKeys_startZeroWithEnd_vs_missingStartKey() {
        // Given — START=0 is a real key (valid window); missing START is partial
        val withStartZero = workDataOf(
            ConversionWorker.KEY_SEGMENT_START_US to 0L,
            ConversionWorker.KEY_SEGMENT_END_US to 500L,
        )
        val endOnly = workDataOf(ConversionWorker.KEY_SEGMENT_END_US to 500L)
        // When / Then
        val resolved = ConversionWorker.resolveSegmentKeys(withStartZero)
        assertNotNull(resolved)
        assertEquals(0L, resolved!!.range!!.startUs)
        assertEquals(500L, resolved.range!!.endUs)
        assertNull(ConversionWorker.resolveSegmentKeys(endOnly))
    }

    @Test
    fun failure_resolveSegmentKeys_rangeStartNegative() {
        // Given
        val data = workDataOf(
            ConversionWorker.KEY_SEGMENT_START_US to -1L,
            ConversionWorker.KEY_SEGMENT_END_US to 100L,
        )
        // When / Then
        assertNull(ConversionWorker.resolveSegmentKeys(data))
    }

    @Test
    fun failure_resolveSegmentKeys_rangeStartNotLessThanEnd() {
        // Given
        val equal = workDataOf(
            ConversionWorker.KEY_SEGMENT_START_US to 100L,
            ConversionWorker.KEY_SEGMENT_END_US to 100L,
        )
        val inverted = workDataOf(
            ConversionWorker.KEY_SEGMENT_START_US to 200L,
            ConversionWorker.KEY_SEGMENT_END_US to 100L,
        )
        // When / Then
        assertNull(ConversionWorker.resolveSegmentKeys(equal))
        assertNull(ConversionWorker.resolveSegmentKeys(inverted))
    }

    @Test
    fun failure_resolveSegmentKeys_indexZeroOrOutOfRangeOrTotalZero() {
        // Given / When / Then
        assertNull(
            ConversionWorker.resolveSegmentKeys(
                workDataOf(
                    ConversionWorker.KEY_SEGMENT_BATCH_ID to "b",
                    ConversionWorker.KEY_SEGMENT_INDEX to 0,
                    ConversionWorker.KEY_SEGMENT_TOTAL to 3,
                ),
            ),
        )
        assertNull(
            ConversionWorker.resolveSegmentKeys(
                workDataOf(
                    ConversionWorker.KEY_SEGMENT_BATCH_ID to "b",
                    ConversionWorker.KEY_SEGMENT_INDEX to 4,
                    ConversionWorker.KEY_SEGMENT_TOTAL to 3,
                ),
            ),
        )
        assertNull(
            ConversionWorker.resolveSegmentKeys(
                workDataOf(
                    ConversionWorker.KEY_SEGMENT_BATCH_ID to "b",
                    ConversionWorker.KEY_SEGMENT_INDEX to 1,
                    ConversionWorker.KEY_SEGMENT_TOTAL to 0,
                ),
            ),
        )
    }

    @Test
    fun success_resolveSegmentKeys_emptyDataBuilderStillNone() {
        // Given — empty Data (no keys at all)
        val data = Data.Builder().build()
        // When
        val resolved = ConversionWorker.resolveSegmentKeys(data)
        // Then
        assertNotNull(resolved)
        assertNull(resolved!!.range)
        assertNull(resolved.batch)
    }

    @Test
    fun failure_resolveSegmentKeys_batchIdKeyPresentWithNullValue() {
        // Given — hasKey(BATCH_ID) true, getString null (Worker-safe, not trio IAE)
        val data = Data.Builder()
            .putString(ConversionWorker.KEY_SEGMENT_BATCH_ID, null)
            .putInt(ConversionWorker.KEY_SEGMENT_INDEX, 1)
            .putInt(ConversionWorker.KEY_SEGMENT_TOTAL, 2)
            .build()
        // When
        val resolved = ConversionWorker.resolveSegmentKeys(data)
        // Then
        assertTrue(data.keyValueMap.containsKey(ConversionWorker.KEY_SEGMENT_BATCH_ID))
        assertNull(data.getString(ConversionWorker.KEY_SEGMENT_BATCH_ID))
        assertNull(resolved)
    }
}
