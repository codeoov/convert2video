package com.example.convert2video.record

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingBackupReconcilerTest {

    @Test
    fun success_trashEntryAtRetentionBoundary_isExpired() {
        // Given
        val deletedAt = 1_000L
        val retentionMs = 15L * 24L * 60L * 60L * 1_000L

        // When / Then
        assertTrue(isRecordingBackupTrashEntryExpired(deletedAt, deletedAt + retentionMs))
    }

    @Test
    fun success_trashEntryBeforeRetentionBoundary_isRetained() {
        // Given
        val deletedAt = 1_000L
        val retentionMs = 15L * 24L * 60L * 60L * 1_000L

        // When / Then
        assertFalse(isRecordingBackupTrashEntryExpired(deletedAt, deletedAt + retentionMs - 1L))
    }

    @Test
    fun success_futureTrashEntry_isRetained() {
        // Given / When / Then
        assertFalse(isRecordingBackupTrashEntryExpired(2_000L, 1_000L))
    }

    @Test
    fun success_missingDeletedAt_isRetained() {
        // Given / When / Then
        assertFalse(isRecordingBackupTrashEntryExpired(null, Long.MAX_VALUE))
    }
}
