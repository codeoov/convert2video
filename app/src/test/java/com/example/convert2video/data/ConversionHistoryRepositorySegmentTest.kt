package com.example.convert2video.data

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [requireSegmentTrioConsistent] (Uri-free).
 * Full [ConversionHistoryRepository.recordConversion] pass-through is covered in androidTest.
 */
class ConversionHistoryRepositorySegmentTest {

    @Test
    fun success_requireSegmentTrioConsistent_allNull() {
        // Given / When / Then — no throw
        requireSegmentTrioConsistent(null, null, null)
    }

    @Test
    fun success_requireSegmentTrioConsistent_allPresent() {
        // Given / When / Then — pass-through-valid trio
        requireSegmentTrioConsistent("batch-1", 2, 3)
    }

    @Test
    fun exception_requireSegmentTrioConsistent_partialTrio() {
        // Given / When / Then
        val error = assertThrows(IllegalArgumentException::class.java) {
            requireSegmentTrioConsistent("batch-only", null, null)
        }
        assertTrue(error.message.orEmpty().contains("all be null or all non-null"))
    }

    @Test
    fun exception_requireSegmentTrioConsistent_indexOutOfRange() {
        // Given / When / Then
        val error = assertThrows(IllegalArgumentException::class.java) {
            requireSegmentTrioConsistent("batch-1", 0, 3)
        }
        assertTrue(error.message.orEmpty().contains("segmentIndex"))
    }

    @Test
    fun exception_requireSegmentTrioConsistent_blankBatchId() {
        // Given / When / Then
        val error = assertThrows(IllegalArgumentException::class.java) {
            requireSegmentTrioConsistent("  ", 1, 1)
        }
        assertTrue(error.message.orEmpty().contains("segmentBatchId"))
    }

    @Test
    fun exception_requireSegmentIndexTotalConsistent_mixed() {
        // Given / When / Then — shared index/total SSOT used by buildDisplayName
        val error = assertThrows(IllegalArgumentException::class.java) {
            requireSegmentIndexTotalConsistent(1, null)
        }
        assertTrue(error.message.orEmpty().contains("segmentIndex"))
    }

    @Test
    fun success_requireSegmentIndexTotalConsistent_bothNull() {
        requireSegmentIndexTotalConsistent(null, null)
    }

    @Test
    fun exception_requireSegmentTrioConsistent_totalZero() {
        // Given / When / Then
        val error = assertThrows(IllegalArgumentException::class.java) {
            requireSegmentTrioConsistent("batch-1", 1, 0)
        }
        assertTrue(error.message.orEmpty().contains("segmentTotal"))
    }
}
