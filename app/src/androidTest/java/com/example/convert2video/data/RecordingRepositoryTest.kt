package com.example.convert2video.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.convert2video.record.C2vRecordingNames
import com.example.convert2video.record.RecordingFormat
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
class RecordingRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: RecordingRepository
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RecordingRepository(
            context,
            db.recordingDao(),
            TrashRepository(context, db.trashedItemDao(), db.recordingDao(), db.importedAudioDao()),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun success_recordFinishedRecording_indexesFileAsRow() = runTest {
        // Given — dummy bytes under Music/C2V (no MediaRecorder)
        val dir = C2vRecordingNames.appStorageDir(context)
        val file = File(dir, "repo_test_${System.nanoTime()}.m4a")
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))

        // When
        val recorded = repository.recordFinishedRecording(
            file = file,
            format = RecordingFormat.AAC,
            durationMs = 1_500L,
            createdAtMillis = 42L,
        )

        // Then
        assertTrue(recorded.id > 0)
        assertEquals(recorded.id, recorded.backupId)
        val list = repository.recordings.first()
        assertEquals(1, list.size)
        assertEquals(file.absolutePath, list.single().filePath)
        assertEquals("AAC", list.single().format)
        assertEquals(1_500L, list.single().durationMs)
        assertEquals(5L, list.single().sizeBytes)
        assertEquals(42L, list.single().createdAt)
        assertEquals(list.single().id, list.single().backupId)
    }

    @Test
    fun success_deleteRecording_movesToTrashAndRemovesRow() = runTest {
        // Given
        val dir = C2vRecordingNames.appStorageDir(context)
        val file = File(dir, "repo_del_${System.nanoTime()}.wav")
        file.writeBytes(byteArrayOf(9, 8, 7))
        val recorded = repository.recordFinishedRecording(
            file = file,
            format = RecordingFormat.WAV,
            durationMs = 100L,
        )

        // When
        val success = repository.deleteRecording(recorded)

        // Then
        assertTrue(success)
        assertTrue(repository.recordings.first().isEmpty())
        assertFalse(file.exists())
        val trashRows = db.trashedItemDao().observeAll().first()
        assertEquals(1, trashRows.size)
        assertEquals(TrashedItem.RECORDING_AUDIO, trashRows.single().itemType)
        assertTrue(trashRows.single().wasIndexed)
        assertEquals(recorded.backupId, trashRows.single().recordingBackupId)
        val trashFile = File(trashRows.single().trashFilePath)
        assertTrue(trashFile.isFile)
    }

    @Test
    fun success_uriFor_returnsFileProviderContentUri() = runTest {
        // Given
        val dir = C2vRecordingNames.appStorageDir(context)
        val file = File(dir, "repo_uri_${System.nanoTime()}.m4a")
        file.writeBytes(byteArrayOf(1))
        val recorded = repository.recordFinishedRecording(
            file = file,
            format = RecordingFormat.AAC,
            durationMs = 10L,
        )

        // When
        val uri = repository.uriFor(recorded)
        val fileUri = repository.uriFor(file)
        val companionUri = RecordingRepository.contentUriFor(context, file)

        // Then — record/file/companion 동일 authority SSOT
        assertEquals("content", uri.scheme)
        assertTrue(uri.authority == "com.convert2video.fileprovider")
        assertEquals(uri, fileUri)
        assertEquals(uri, companionUri)
    }

    @Test
    fun failure_contentUriFor_rejectsFileOutsideProviderPaths() {
        // Given — FileProvider paths 밖(cacheDir)
        val outside = File(context.cacheDir, "outside_${System.nanoTime()}.m4a")
        outside.writeBytes(byteArrayOf(1))

        // When
        val result = runCatching { RecordingRepository.contentUriFor(context, outside) }

        // Then — MainActivity onRecordingSaved runCatching 분기와 동일 실패
        assertTrue(result.isFailure)
        outside.delete()
    }

    @Test
    fun failure_recordFinishedRecording_rejectsMissingFile() = runTest {
        // Given — path does not exist
        val dir = C2vRecordingNames.appStorageDir(context)
        val missing = File(dir, "missing_${System.nanoTime()}.m4a")

        // When
        val error = runCatching {
            repository.recordFinishedRecording(
                file = missing,
                format = RecordingFormat.AAC,
                durationMs = 100L,
            )
        }.exceptionOrNull()

        // Then
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun failure_recordFinishedRecording_rejectsEmptyFile() = runTest {
        // Given — exists but length 0
        val dir = C2vRecordingNames.appStorageDir(context)
        val empty = File(dir, "empty_${System.nanoTime()}.m4a")
        empty.createNewFile()

        // When
        val error = runCatching {
            repository.recordFinishedRecording(
                file = empty,
                format = RecordingFormat.WAV,
                durationMs = 100L,
            )
        }.exceptionOrNull()

        // Then
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun success_renameRecording_updatesFileAndDbPath() = runTest {
        // Given
        val dir = C2vRecordingNames.appStorageDir(context)
        val file = File(dir, "old_name_${System.nanoTime()}.m4a")
        file.writeBytes(byteArrayOf(1, 2, 3))
        val recorded = repository.recordFinishedRecording(
            file = file,
            format = RecordingFormat.AAC,
            durationMs = 500L,
        )

        // When
        val success = repository.renameRecording(recorded, "new_name")

        // Then
        assertTrue(success)
        assertTrue(!file.exists())
        val newFile = File(dir, "new_name.m4a")
        assertTrue(newFile.exists())
        val list = repository.recordings.first()
        assertEquals(newFile.absolutePath, list.single().filePath)
    }

    @Test
    fun failure_renameRecording_rejectsInvalidStem() = runTest {
        // Given
        val dir = C2vRecordingNames.appStorageDir(context)
        val file = File(dir, "invalid_stem_${System.nanoTime()}.m4a")
        file.writeBytes(byteArrayOf(1, 2))
        val recorded = repository.recordFinishedRecording(
            file = file,
            format = RecordingFormat.AAC,
            durationMs = 100L,
        )

        // When — forbidden char in stem
        val success = repository.renameRecording(recorded, "bad/name")

        // Then
        assertTrue(!success)
        assertTrue(file.exists())
        assertEquals(file.absolutePath, repository.recordings.first().single().filePath)
    }

    @Test
    fun failure_renameRecording_rejectsExistingTargetName() = runTest {
        // Given
        val dir = C2vRecordingNames.appStorageDir(context)
        val file = File(dir, "rename_src_${System.nanoTime()}.wav")
        file.writeBytes(byteArrayOf(1, 2))
        val blocker = File(dir, "rename_block_${System.nanoTime()}.wav")
        blocker.writeBytes(byteArrayOf(9))
        val recorded = repository.recordFinishedRecording(
            file = file,
            format = RecordingFormat.WAV,
            durationMs = 100L,
        )

        // When — target stem matches existing blocker file (without ext in input)
        val blockerStem = blocker.name.removeSuffix(".wav")
        val success = repository.renameRecording(recorded, blockerStem)

        // Then
        assertTrue(!success)
        assertTrue(file.exists())
        assertEquals(file.absolutePath, repository.recordings.first().single().filePath)
    }
}
