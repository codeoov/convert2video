package com.example.convert2video.record

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class RecordingBackupWorkerTest {

    @Test
    fun success_activeAbsentEntry_copiesIdentityFileAndCreatesActiveManifestEntry() = runTest {
        // Given
        val source = File.createTempFile("recording_backup_worker", ".m4a")
        source.writeBytes(byteArrayOf(1, 2, 3, 4))
        val adapter = FakeSafAdapter()
        val event = activeEvent(backupId = 31L)

        try {
            // When
            RecordingBackupWorker.processEvent(event, adapter, source)

            // Then
            val identity = recordingBackupFileName(31L, RecordingFormat.AAC)
            assertArrayEquals(source.readBytes(), adapter.bytesFor(identity))
            val entry = requireNotNull(RecordingBackupManifest.readFromSaf(adapter)).entries.single()
            assertEquals(31L, entry.id)
            assertEquals(RecordingBackupManifest.Entry.Kind.ACTIVE, entry.kind)
            assertEquals("recording.m4a", entry.displayName)
        } finally {
            source.delete()
        }
    }

    @Test
    fun success_trashedExistingEntry_retainsOriginalMetadata() = runTest {
        // Given
        val source = File.createTempFile("recording_backup_worker", ".m4a")
        source.writeBytes(byteArrayOf(4, 3, 2, 1))
        val adapter = FakeSafAdapter()
        RecordingBackupWorker.processEvent(activeEvent(backupId = 32L), adapter, source)
        val trashed = RecordingBackupEvent(
            action = RecordingBackupAction.TRASHED,
            backupId = 32L,
            format = RecordingFormat.AAC,
            metadata = RecordingBackupMetadata(
                displayName = "changed-name.m4a",
                durationMs = 9_999L,
                sizeBytes = 8_888L,
                createdAt = 7_777L,
                deletedAt = 6_666L,
            ),
        )

        try {
            // When
            RecordingBackupWorker.processEvent(trashed, adapter, sourceFile = null)

            // Then
            val entry = requireNotNull(RecordingBackupManifest.readFromSaf(adapter)).entries.single()
            assertEquals(RecordingBackupManifest.Entry.Kind.TRASHED, entry.kind)
            assertEquals("recording.m4a", entry.displayName)
            assertEquals(1_000L, entry.durationMs)
            assertEquals(2_000L, entry.sizeBytes)
            assertEquals(3_000L, entry.createdAt)
            assertEquals(6_666L, entry.deletedAt)
        } finally {
            source.delete()
        }
    }

    @Test
    fun success_activeAbsentEntryWithoutSource_leavesSafUntouched() = runTest {
        // Given
        val adapter = FakeSafAdapter()

        // When
        RecordingBackupWorker.processEvent(activeEvent(backupId = 33L), adapter, sourceFile = null)

        // Then
        assertNull(RecordingBackupManifest.readFromSaf(adapter))
        assertFalse(adapter.has(recordingBackupFileName(33L, RecordingFormat.AAC)))
    }

    @Test
    fun success_removeWithoutManifest_deletesIdentityFile() = runTest {
        // Given
        val adapter = FakeSafAdapter()
        val identity = recordingBackupFileName(34L, RecordingFormat.WAV)
        adapter.put(identity, byteArrayOf(7, 8, 9))
        val event = RecordingBackupEvent(
            action = RecordingBackupAction.REMOVE,
            backupId = 34L,
            format = RecordingFormat.WAV,
        )

        // When
        RecordingBackupWorker.processEvent(event, adapter, sourceFile = null)

        // Then
        assertFalse(adapter.has(identity))
        assertNull(RecordingBackupManifest.readFromSaf(adapter))
    }

    private fun activeEvent(backupId: Long): RecordingBackupEvent = RecordingBackupEvent(
        action = RecordingBackupAction.ACTIVE,
        backupId = backupId,
        format = RecordingFormat.AAC,
        metadata = RecordingBackupMetadata(
            displayName = "recording.m4a",
            durationMs = 1_000L,
            sizeBytes = 2_000L,
            createdAt = 3_000L,
        ),
    )

    private class FakeSafAdapter : RecordingBackupSafAdapter {
        private val files = linkedMapOf<String, FakeFile>()

        override val rootExists: Boolean = true
        override val rootIsDirectory: Boolean = true

        override fun findFile(displayName: String): RecordingBackupSafFile? = files[displayName]

        override fun createFile(mimeType: String, displayName: String): RecordingBackupSafFile? =
            FakeFile(displayName).also { file -> files[displayName] = file }

        override fun openInputStream(file: RecordingBackupSafFile): InputStream =
            ByteArrayInputStream((file as FakeFile).bytes)

        override fun openOutputStream(file: RecordingBackupSafFile): OutputStream {
            val target = file as FakeFile
            return object : ByteArrayOutputStream() {
                override fun close() {
                    target.bytes = toByteArray()
                    super.close()
                }
            }
        }

        override fun delete(file: RecordingBackupSafFile): Boolean =
            files.entries.removeIf { (_, value) -> value === file }

        override fun rename(file: RecordingBackupSafFile, displayName: String): Boolean {
            val target = file as FakeFile
            files.entries.removeIf { (_, value) -> value === target }
            target.name = displayName
            files[displayName] = target
            return true
        }

        fun put(name: String, bytes: ByteArray) {
            files[name] = FakeFile(name, bytes)
        }

        fun has(name: String): Boolean = files.containsKey(name)

        fun bytesFor(name: String): ByteArray? = files[name]?.bytes
    }

    private class FakeFile(
        var name: String,
        var bytes: ByteArray = ByteArray(0),
    ) : RecordingBackupSafFile {
        override val exists: Boolean = true
        override val isFile: Boolean = true
        override val isDirectory: Boolean = false
    }
}
