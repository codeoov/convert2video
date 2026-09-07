package com.example.convert2video.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.convert2video.record.C2vRecordingNames
import com.example.convert2video.video.C2vOutputNames
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TrashRepositoryRestoreTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: TrashRepository
    private lateinit var context: Context
    private lateinit var trashDir: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = TrashRepository(context, db.trashedItemDao(), db.recordingDao(), db.importedAudioDao())
        trashDir = TrashRepository.trashDir(context)
        trashDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    @After
    fun tearDown() {
        db.close()
        trashDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    @Test
    fun success_restore_recordingAudio_wasIndexedTrue_movesFileAndInsertsRecord() = runTest {
        // Given — a RECORDING_AUDIO item in trash with wasIndexed=true
        val name = "restore_rec_true_${System.nanoTime()}.m4a"
        val source = writeSource("src_${System.nanoTime()}.m4a")
        val trashed = repository.moveToTrash(
            sourceFile = source,
            itemType = TrashedItem.RECORDING_AUDIO,
            displayName = name,
            wasIndexed = true,
            durationMs = 1_500L,
            recordingFormat = "AAC",
        )!!
        val destFile = File(C2vRecordingNames.appStorageDir(context), name)

        // When
        val restored = repository.restore(trashed)

        // Then
        assertTrue(restored)
        assertFalse(File(trashed.trashFilePath).exists())
        assertTrue(destFile.isFile)
        val records = db.recordingDao().observeAll().first()
        assertEquals(1, records.size)
        assertEquals(destFile.absolutePath, records.single().filePath)
        assertEquals("AAC", records.single().format)
        assertEquals(1_500L, records.single().durationMs)
        assertTrue(repository.observeAll().first().isEmpty())

        destFile.delete()
    }

    @Test
    fun success_restore_recordingAudio_preservesNonzeroBackupId() = runTest {
        // Given — a kept recording's stable backup identity persisted on its trash row
        val source = writeSource("restore_backup_${System.nanoTime()}.m4a")
        val trashed = repository.moveToTrash(
            sourceFile = source,
            itemType = TrashedItem.RECORDING_AUDIO,
            displayName = "restore_backup.m4a",
            wasIndexed = true,
            durationMs = 1_500L,
            recordingFormat = "AAC",
            recordingBackupId = 77L,
        )!!

        // When
        val restored = repository.restore(trashed)

        // Then — the newly generated Room primary key is independent of the stable backup ID
        assertTrue(restored)
        val record = db.recordingDao().observeAll().first().single()
        assertEquals(77L, record.backupId)
        File(record.filePath).delete()
    }

    @Test
    fun success_restore_recordingAudio_wasIndexedFalse_movesFileAndInsertsRecord() = runTest {
        // Given — F8: a recording discarded from the post-record Review step (never indexed)
        val name = "restore_rec_false_${System.nanoTime()}.m4a"
        val source = writeSource("src_${System.nanoTime()}.m4a")
        val trashed = repository.moveToTrash(
            sourceFile = source,
            itemType = TrashedItem.RECORDING_AUDIO,
            displayName = name,
            wasIndexed = false,
            durationMs = 800L,
            recordingFormat = "AAC",
        )!!
        val destFile = File(C2vRecordingNames.appStorageDir(context), name)

        // When
        val restored = repository.restore(trashed)

        // Then — wasIndexed=false still inserts a RecordingRecord so the item appears in Listen
        assertTrue(restored)
        assertTrue(destFile.isFile)
        val records = db.recordingDao().observeAll().first()
        assertEquals(1, records.size)
        assertEquals(destFile.absolutePath, records.single().filePath)
        assertTrue(repository.observeAll().first().isEmpty())

        destFile.delete()
    }

    @Test
    fun success_restore_importedAudio_wasIndexedTrue_movesFileAndInsertsRecord() = runTest {
        // Given
        val name = "restore_imp_true_${System.nanoTime()}.m4a"
        val source = writeSource("src_${System.nanoTime()}.m4a")
        val trashed = repository.moveToTrash(
            sourceFile = source,
            itemType = TrashedItem.IMPORTED_AUDIO,
            displayName = name,
            wasIndexed = true,
            durationMs = 2_000L,
        )!!
        val destFile = File(ImportedAudioRepository.appStorageDir(context), name)

        // When
        val restored = repository.restore(trashed)

        // Then
        assertTrue(restored)
        assertTrue(destFile.isFile)
        val records = db.importedAudioDao().observeAll().first()
        assertEquals(1, records.size)
        assertEquals(destFile.absolutePath, records.single().filePath)
        assertEquals(name, records.single().originalDisplayName)
        assertTrue(repository.observeAll().first().isEmpty())

        destFile.delete()
    }

    @Test
    fun success_restore_importedAudio_wasIndexedFalse_movesFileAndInsertsRecord() = runTest {
        // Given — no production path creates this today, but restore() must stay symmetric
        val name = "restore_imp_false_${System.nanoTime()}.m4a"
        val source = writeSource("src_${System.nanoTime()}.m4a")
        val trashed = repository.moveToTrash(
            sourceFile = source,
            itemType = TrashedItem.IMPORTED_AUDIO,
            displayName = name,
            wasIndexed = false,
            durationMs = 500L,
        )!!
        val destFile = File(ImportedAudioRepository.appStorageDir(context), name)

        // When
        val restored = repository.restore(trashed)

        // Then
        assertTrue(restored)
        assertTrue(destFile.isFile)
        val records = db.importedAudioDao().observeAll().first()
        assertEquals(1, records.size)
        assertTrue(repository.observeAll().first().isEmpty())

        destFile.delete()
    }

    @Test
    fun success_restore_convertedVideo_movesFileNoDbInsert() = runTest {
        // Given
        val name = "restore_vid_${System.nanoTime()}.mp4"
        val source = writeSource("src_${System.nanoTime()}.mp4")
        val trashed = repository.moveToTrash(
            sourceFile = source,
            itemType = TrashedItem.CONVERTED_VIDEO,
            displayName = name,
            wasIndexed = true,
        )!!
        val destFile = File(C2vOutputNames.appStorageDir(context), name)

        // When
        val restored = repository.restore(trashed)

        // Then — no Room insert for CONVERTED_VIDEO; ConvertedVideoRepository's filesystem
        // scan of appStorageDir is what picks it back up
        assertTrue(restored)
        assertTrue(destFile.isFile)
        assertTrue(repository.observeAll().first().isEmpty())
        assertTrue(db.recordingDao().observeAll().first().isEmpty())
        assertTrue(db.importedAudioDao().observeAll().first().isEmpty())

        destFile.delete()
    }

    @Test
    fun success_restore_recordingAudio_originalPathAvailable_usesOriginalPath() = runTest {
        // Given — originalFilePath points at an unoccupied slot inside the recording appStorageDir
        val destDir = C2vRecordingNames.appStorageDir(context)
        val originalPath = File(destDir, "restore_orig_${System.nanoTime()}.m4a").absolutePath
        val source = writeSource("src_${System.nanoTime()}.m4a")
        val trashed = repository.moveToTrash(
            sourceFile = source,
            itemType = TrashedItem.RECORDING_AUDIO,
            displayName = "unrelated_display_${System.nanoTime()}.m4a",
            wasIndexed = true,
            originalFilePath = originalPath,
            durationMs = 1_000L,
            recordingFormat = "AAC",
        )!!

        // When
        val restored = repository.restore(trashed)

        // Then — restored exactly to originalFilePath, not a name derived from displayName
        assertTrue(restored)
        val destFile = File(originalPath)
        assertTrue(destFile.isFile)
        assertEquals(destFile.absolutePath, db.recordingDao().observeAll().first().single().filePath)

        destFile.delete()
    }

    @Test
    fun success_restore_recordingAudio_nameCollision_usesSuffixedName() = runTest {
        // Given — a file with the same display name already occupies the recording appStorageDir
        val destDir = C2vRecordingNames.appStorageDir(context)
        val name = "restore_collide_${System.nanoTime()}.m4a"
        val occupied = File(destDir, name)
        occupied.writeBytes(byteArrayOf(9))
        val source = writeSource("src_${System.nanoTime()}.m4a")
        val trashed = repository.moveToTrash(
            sourceFile = source,
            itemType = TrashedItem.RECORDING_AUDIO,
            displayName = name,
            wasIndexed = true,
            recordingFormat = "AAC",
        )!!

        // When
        val restored = repository.restore(trashed)

        // Then — SSOT collision helper (C2vRecordingNames.firstAvailableDisplayName) bumps to "_2"
        assertTrue(restored)
        val destFile = File(destDir, name.removeSuffix(".m4a") + "_2.m4a")
        assertTrue(destFile.isFile)
        assertEquals(destFile.absolutePath, db.recordingDao().observeAll().first().single().filePath)

        occupied.delete()
        destFile.delete()
    }

    @Test
    fun success_restore_convertedVideo_nameCollision_usesSuffixedName() = runTest {
        // Given — a file with the same display name already occupies the video appStorageDir
        val destDir = C2vOutputNames.appStorageDir(context)
        val name = "restore_vid_collide_${System.nanoTime()}.mp4"
        val occupied = File(destDir, name)
        occupied.writeBytes(byteArrayOf(9))
        val source = writeSource("src_${System.nanoTime()}.mp4")
        val trashed = repository.moveToTrash(
            sourceFile = source,
            itemType = TrashedItem.CONVERTED_VIDEO,
            displayName = name,
            wasIndexed = true,
        )!!

        // When
        val restored = repository.restore(trashed)

        // Then — SSOT collision helper (C2vOutputNames.firstAvailableDisplayName) bumps to "_2"
        assertTrue(restored)
        val destFile = File(destDir, name.removeSuffix(".mp4") + "_2.mp4")
        assertTrue(destFile.isFile)

        occupied.delete()
        destFile.delete()
    }

    @Test
    fun failure_restore_missingTrashFile_returnsFalseKeepsRow() = runTest {
        // Given — a trash row whose backing file was already lost
        val missing = File(trashDir, "gone_${System.nanoTime()}.m4a")
        val id = db.trashedItemDao().insert(
            TrashedItem(
                itemType = TrashedItem.RECORDING_AUDIO,
                displayName = "gone.m4a",
                trashFilePath = missing.absolutePath,
                deletedAt = System.currentTimeMillis(),
                wasIndexed = true,
                durationMs = 1_000L,
                recordingFormat = "AAC",
            ),
        )
        assertFalse(missing.exists())

        // When
        val restored = repository.restore(
            TrashedItem(
                id = id,
                itemType = TrashedItem.RECORDING_AUDIO,
                displayName = "gone.m4a",
                trashFilePath = missing.absolutePath,
                deletedAt = System.currentTimeMillis(),
                wasIndexed = true,
                durationMs = 1_000L,
                recordingFormat = "AAC",
            ),
        )

        // Then
        assertFalse(restored)
        assertEquals(1, repository.observeAll().first().size)
        assertTrue(db.recordingDao().observeAll().first().isEmpty())
    }

    @Test
    fun failure_restore_recordingAudio_insertFails_rollsBackFileKeepsRow() = runTest {
        // Given — recordingDao.insert always throws
        val name = "restore_fail_${System.nanoTime()}.m4a"
        val source = writeSource("src_${System.nanoTime()}.m4a")
        val trashed = repository.moveToTrash(
            sourceFile = source,
            itemType = TrashedItem.RECORDING_AUDIO,
            displayName = name,
            wasIndexed = true,
            recordingFormat = "AAC",
        )!!
        val failing = TrashRepository(
            context,
            db.trashedItemDao(),
            InsertThrowingRecordingDao(db.recordingDao(), RuntimeException("insert boom")),
            db.importedAudioDao(),
        )

        // When
        val restored = failing.restore(trashed)

        // Then — file rolled back to trash, row kept, no orphan RecordingRecord
        assertFalse(restored)
        assertTrue(File(trashed.trashFilePath).isFile)
        assertFalse(File(C2vRecordingNames.appStorageDir(context), name).exists())
        assertEquals(1, repository.observeAll().first().size)
        assertTrue(db.recordingDao().observeAll().first().isEmpty())
    }

    @Test
    fun failure_restore_nameCollisionExhausted_returnsFalseKeepsRow() = runTest {
        // Given — preferred name plus all "_2".."_99" variants already occupy the appStorageDir,
        // so the SSOT collision helper has no name left to hand out (throws IllegalStateException).
        val destDir = C2vRecordingNames.appStorageDir(context)
        val name = "restore_exhausted_${System.nanoTime()}.m4a"
        val stem = name.removeSuffix(".m4a")
        val occupied = mutableListOf<File>()
        occupied += File(destDir, name).apply { writeBytes(byteArrayOf(1)) }
        for (n in 2..99) {
            occupied += File(destDir, "${stem}_$n.m4a").apply { writeBytes(byteArrayOf(1)) }
        }
        val source = writeSource("src_${System.nanoTime()}.m4a")
        val trashed = repository.moveToTrash(
            sourceFile = source,
            itemType = TrashedItem.RECORDING_AUDIO,
            displayName = name,
            wasIndexed = true,
            recordingFormat = "AAC",
        )!!

        try {
            // When
            val restored = repository.restore(trashed)

            // Then — IllegalStateException caught and converted to false; row kept
            assertFalse(restored)
            assertTrue(File(trashed.trashFilePath).isFile)
            assertEquals(1, repository.observeAll().first().size)
            assertTrue(db.recordingDao().observeAll().first().isEmpty())
        } finally {
            occupied.forEach { it.delete() }
        }
    }

    private fun writeSource(name: String): File {
        val file = File(context.cacheDir, name)
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        return file
    }
}

private class InsertThrowingRecordingDao(
    private val real: RecordingDao,
    private val error: Throwable,
) : RecordingDao {
    override fun observeAll(): Flow<List<RecordingRecord>> = real.observeAll()
    override suspend fun insert(record: RecordingRecord): Long = throw error
    override suspend fun updateBackupId(id: Long, backupId: Long) = real.updateBackupId(id, backupId)
    override suspend fun deleteById(id: Long) = real.deleteById(id)
    override suspend fun updateFilePath(id: Long, filePath: String) = real.updateFilePath(id, filePath)
}
