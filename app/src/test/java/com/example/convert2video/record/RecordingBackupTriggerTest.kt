package com.example.convert2video.record

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingBackupTriggerTest {

    @Test
    fun success_enqueueActive_usesStableIdentityWorkNameAndSsotInputKeys() {
        // Given
        val event = RecordingBackupEvent(
            action = RecordingBackupAction.ACTIVE,
            backupId = 15L,
            format = RecordingFormat.AAC,
            metadata = RecordingBackupMetadata(
                displayName = "same-name.m4a",
                durationMs = 1_000L,
                sizeBytes = 2_000L,
                createdAt = 3_000L,
            ),
            sourceFilePath = "/local/recording.m4a",
        )
        var capturedName: String? = null
        var capturedPolicy: ExistingWorkPolicy? = null
        var capturedRequest: OneTimeWorkRequest? = null

        // When
        RecordingBackupTrigger.enqueue(event) { name, policy, request ->
            capturedName = name
            capturedPolicy = policy
            capturedRequest = request
        }

        // Then
        assertEquals("recording_backup_15", capturedName)
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, capturedPolicy)
        val input = requireNotNull(capturedRequest).workSpec.input
        assertEquals("ACTIVE", input.getString(RecordingBackupTrigger.KEY_ACTION))
        assertEquals(15L, input.getLong(RecordingBackupTrigger.KEY_BACKUP_ID, 0L))
        assertEquals("AAC", input.getString(RecordingBackupTrigger.KEY_FORMAT))
        assertEquals("same-name.m4a", input.getString(RecordingBackupTrigger.KEY_DISPLAY_NAME))
        assertEquals(1_000L, input.getLong(RecordingBackupTrigger.KEY_DURATION_MS, -1L))
        assertEquals(2_000L, input.getLong(RecordingBackupTrigger.KEY_SIZE_BYTES, -1L))
        assertEquals(3_000L, input.getLong(RecordingBackupTrigger.KEY_CREATED_AT, -1L))
        assertEquals("/local/recording.m4a", input.getString(RecordingBackupTrigger.KEY_SOURCE_FILE_PATH))
        assertNull(input.getString(RecordingBackupTrigger.KEY_DELETED_AT))
    }

    @Test
    fun success_recordingBackupWorkName_usesOnlyBackupId() {
        // Given / When
        val first = recordingBackupWorkName(9L)
        val second = recordingBackupWorkName(9L)

        // Then
        assertEquals("recording_backup_9", first)
        assertEquals(first, second)
    }
}
